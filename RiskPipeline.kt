package app.family.igguard

import android.app.ActivityManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Scores-only family mode (docs/superpowers/specs/2026-11-19-automatic-on-phone-scoring-design.md).
 *
 * Child phone: model file, mode state (parent policy, child consent, pause), an encrypted store of recent direct-thread
 * messages, batch scoring, and a content-free `scores_mode_report`. Parent phone: policy sender (in MainActivity.kt),
 * strict report reader and score tables (FamilyEvidence.kt). Message text never leaves the child's phone in this mode.
 */
internal object RiskLimits {
    const val MESSAGES = 60
    const val DAYS = 14L
    const val STALE_REPORT_MS = 72L * 3_600_000L
    const val FALLBACK_MS = 48L * 3_600_000L
    const val MAX_REPORT_ROWS = 200
    const val MAX_LABEL = 64
}

/** Parent copy of one conversation score. Rows come newest first. [score] is 0..100, or null with a [reviewReason]. */
data class RiskScoreRow(val accountLabel: String, val score: Double?, val reviewReason: String?, val scoredAt: Long)

/**
 * The child's last reported state. [model] is valid|missing|mismatch|unsupported_device|engine_unavailable, [consent] is none|accepted|withdrawn.
 * [stale] is true when no report has arrived for 72 hours.
 */
data class ScoresModeState(
    val policyId: String?, val model: String, val consent: String, val paused: Boolean, val pausedAt: Long?,
    val lastRun: Long?, val scoredCount: Int, val unknownThreads: Int, val sentAt: Long, val receivedAt: Long, val stale: Boolean,
    /** The child's analysis was actually able to run when it reported (false: agreed but not running, e.g. full capture not confirmed). */
    val active: Boolean = false
)

/** How the child's chats reach the analysis: `capture` (notifications and screen, today's behaviour) or `upload` (the teen imports Instagram's own export). */
internal object ScoresMethod {
    const val CAPTURE = "capture"
    const val UPLOAD = "upload"
    fun valid(method: Any?) = method == CAPTURE || method == UPLOAD
}

/** What the parent last asked for. [method] is `capture` when the command carried none. */
data class ScoresModePolicy(val policyId: String, val enabled: Boolean, val sentAt: Long, val method: String = ScoresMethod.CAPTURE)

internal data class RiskReportRow(val thread: String, val label: String, val score: Double?, val reason: String?, val scoredAt: Long, val changed: Boolean = false) {
    fun signature() = "$score|$reason|$scoredAt"
}

/** Child-side snapshot for the settings and status screens. */
internal data class RiskChildStatus(
    val modelState: String, val policyEnabled: Boolean, val consent: String, val paused: Boolean, val pausedAt: Long?,
    val active: Boolean, val lastRun: Long?, val scoredCount: Int, val needsReview: Int, val unknownThreads: Int, val pendingPolicy: Boolean,
    /** The method the parent asks for, and the one the child agreed to (null before any agreement). */
    val method: String = ScoresMethod.CAPTURE, val agreedMethod: String? = null
)

internal enum class RiskImportResult { OK, WRONG_FILE, NOT_ENOUGH_SPACE, IO_ERROR, UNSUPPORTED_DEVICE }

internal enum class RiskModelState(val wire: String) {
    MISSING("missing"), INSTALLED("valid"), MISMATCH("mismatch"), UNSUPPORTED_DEVICE("unsupported_device"),
    /** The phone is supported but the analysis library could not be loaded (cleared by an app update). */
    ENGINE_UNAVAILABLE("engine_unavailable")
}

/** The model file under filesDir/risk. Only a file with the exact size and SHA-256 is ever kept. */
internal object RiskModelStore {
    internal data class Expectation(val bytes: Long, val sha256: String)
    /** Test hook: unit tests install a tiny file. Production never changes it. */
    @Volatile internal var expectation = Expectation(RiskModelSpec.FILE_BYTES, RiskModelSpec.FILE_SHA256)
    /** Test hook for the memory gate. */
    @Volatile internal var memoryProbe: (Context) -> Long = { ctx ->
        val info = ActivityManager.MemoryInfo()
        ctx.getSystemService(ActivityManager::class.java)?.getMemoryInfo(info)
        info.totalMem
    }
    /** Test hook for the CPU gate: the native library is built for arm64-v8a only. */
    @Volatile internal var abiProbe: () -> Boolean = { Build.SUPPORTED_ABIS.contains("arm64-v8a") }
    private const val NATIVE_UNAVAILABLE = "risk_native_unavailable_for"
    private val importing = java.util.concurrent.atomic.AtomicBoolean(false)
    /** Phones sold as 8 GB report slightly less than 8 GiB. */
    const val MIN_MEMORY_BYTES = 7L * 1024 * 1024 * 1024
    private const val SPACE_MARGIN = 32L * 1024 * 1024
    private const val VERIFY_FAILED = "risk_model_verify_failed"
    /** Set when a parent removed the model (password-gated): the included copy is then never installed on its own. */
    private const val REMOVED_BY_PARENT = "risk_model_removed_by_parent"
    /** The [installKey] of an app install whose automatic install of the included copy failed for good (wrong asset) or was cancelled. */
    private const val AUTO_BLOCKED = "risk_bundled_auto_blocked"
    /** Test hook: free space of the volume holding the model. */
    @Volatile internal var freeSpaceProbe: (File) -> Long = { it.usableSpace }
    /** Where a locally built package keeps the model (tools/package_with_model.py): assets/risk/<file name>. */
    fun bundledAssetPath() = "risk/${RiskModelSpec.FILE_NAME}"
    /** Test hook: opens the included copy, or returns null when this app has none. */
    @Volatile internal var bundledOpener: (Context) -> InputStream? = { ctx ->
        try { ctx.applicationContext.assets.open(bundledAssetPath(), android.content.res.AssetManager.ACCESS_STREAMING) } catch (_: IOException) { null }
    }
    /** The app was packaged with the model: the asset exists. Opening it reads nothing. */
    fun bundledModelPresent(ctx: Context): Boolean = try { bundledOpener(ctx)?.use { true } ?: false } catch (_: IOException) { false } catch (_: RuntimeException) { false }
    /** Free space required before a copy starts: the model plus a margin. */
    fun spaceNeeded(bytes: Long = expectation.bytes): Long = bytes + SPACE_MARGIN
    fun freeSpace(ctx: Context): Long = freeSpaceProbe(ctx.applicationContext.filesDir)
    fun removedByParent(ctx: Context): Boolean = prefs(ctx).getBoolean(REMOVED_BY_PARENT, false)
    fun autoBlocked(ctx: Context): Boolean = prefs(ctx).getString(AUTO_BLOCKED, "") == installKey(ctx)
    fun blockAuto(ctx: Context) { prefs(ctx).edit().putString(AUTO_BLOCKED, installKey(ctx)).commit() }

    private fun prefs(ctx: Context) = LocalVault.preferences(ctx)
    /** Enough memory, an arm64 CPU, and a native library that has not failed to load in this app version. */
    fun supported(ctx: Context): Boolean = abiProbe() && memoryProbe(ctx.applicationContext) >= MIN_MEMORY_BYTES
    /** Identifies this installation of this build: a new version code or an app update gives a new key. */
    internal fun installKey(ctx: Context): String {
        val updated = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime } catch (_: Exception) { 0L }
        return "${BuildConfig.VERSION_CODE}:$updated"
    }
    fun engineUnavailable(ctx: Context): Boolean = prefs(ctx).getString(NATIVE_UNAVAILABLE, "") == installKey(ctx)
    /** The library is missing or cannot load: report engine_unavailable until the app is updated. */
    fun markNativeUnavailable(ctx: Context) { prefs(ctx).edit().putString(NATIVE_UNAVAILABLE, installKey(ctx)).commit() }
    /** Deletes a `.part` file left by an interrupted import (not while an import is running). */
    fun cleanStale(ctx: Context) {
        if (!importing.get()) File(directory(ctx), RiskModelSpec.FILE_NAME + ".part").delete()
    }
    fun directory(ctx: Context) = File(ctx.applicationContext.filesDir, "risk")
    fun file(ctx: Context) = File(directory(ctx), RiskModelSpec.FILE_NAME)

    fun state(ctx: Context): RiskModelState {
        if (!supported(ctx)) return RiskModelState.UNSUPPORTED_DEVICE
        if (engineUnavailable(ctx)) return RiskModelState.ENGINE_UNAVAILABLE
        val f = file(ctx)
        if (!f.isFile) return RiskModelState.MISSING
        return if (f.length() != expectation.bytes || prefs(ctx).getBoolean(VERIFY_FAILED, false)) RiskModelState.MISMATCH else RiskModelState.INSTALLED
    }

    /** Full re-hash. A failure is remembered so [state] reports a mismatch until a good file is imported. */
    fun verify(ctx: Context): Boolean {
        val f = file(ctx)
        val ok = try {
            f.isFile && f.inputStream().use { RiskModelFile.sha256(it) }.let { (bytes, sha) -> bytes == expectation.bytes && sha == expectation.sha256 }
        } catch (_: IOException) { false }
        prefs(ctx).edit().putBoolean(VERIFY_FAILED, !ok).commit()
        return ok
    }

    /** Imports from a stream the caller already holds (the file picker); see [installModel]. */
    fun importModel(
        ctx: Context, input: InputStream, onProgress: (Long) -> Unit = {},
        expectedBytes: Long = RiskModelSpec.FILE_BYTES, expectedSha: String = RiskModelSpec.FILE_SHA256
    ): RiskImportResult = installModel(ctx, { input }, onProgress, expectedBytes, expectedSha)

    /**
     * Streams the source to a `.part` file while hashing, and renames it only on an exact size and SHA-256 match. The source
     * is opened (and closed) here, after the space check, so nothing is opened when there is no room. Used by the file picker
     * and by the copy of the model included in the app. The `expected*` parameters exist for tests; production callers use the defaults.
     */
    fun installModel(
        ctx: Context, source: () -> InputStream, onProgress: (Long) -> Unit = {},
        expectedBytes: Long = RiskModelSpec.FILE_BYTES, expectedSha: String = RiskModelSpec.FILE_SHA256
    ): RiskImportResult {
        if (!supported(ctx)) return RiskImportResult.UNSUPPORTED_DEVICE
        val dir = directory(ctx)
        val part = File(dir, RiskModelSpec.FILE_NAME + ".part")
        var done = false
        importing.set(true)
        try {
            part.delete()   // a stale partial file must not count against the free space
            if (!dir.isDirectory && !dir.mkdirs()) return RiskImportResult.IO_ERROR
            if (freeSpaceProbe(dir) - SPACE_MARGIN < expectedBytes) return RiskImportResult.NOT_ENOUGH_SPACE
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(1 shl 16)
            var total = 0L
            source().use { input ->
                FileOutputStream(part).use { out ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > expectedBytes) return RiskImportResult.WRONG_FILE
                        digest.update(buffer, 0, n)
                        out.write(buffer, 0, n)
                        onProgress(total)
                    }
                    out.fd.sync()
                }
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            if (total != expectedBytes || sha != expectedSha) return RiskImportResult.WRONG_FILE
            val target = file(ctx)
            if (!part.renameTo(target)) {
                target.delete()
                if (!part.renameTo(target)) return RiskImportResult.IO_ERROR
            }
            prefs(ctx).edit().remove(VERIFY_FAILED).remove(REMOVED_BY_PARENT).remove(AUTO_BLOCKED).commit()
            done = true
            return RiskImportResult.OK
        } catch (e: IOException) {
            return if (e.message?.contains("ENOSPC") == true || e.message?.contains("No space", true) == true) RiskImportResult.NOT_ENOUGH_SPACE else RiskImportResult.IO_ERROR
        } catch (_: RuntimeException) {
            return RiskImportResult.IO_ERROR
        } finally {
            if (!done) part.delete()
            importing.set(false)
        }
    }

    /** Deletes the file and wipes the scoring store. */
    fun remove(ctx: Context) {
        file(ctx).delete()
        File(directory(ctx), RiskModelSpec.FILE_NAME + ".part").delete()
        prefs(ctx).edit().remove(VERIFY_FAILED).putBoolean(REMOVED_BY_PARENT, true).commit()
        RiskLocal.wipeData(ctx)
    }
}

/** [UPLOAD]: the child agreed to manual upload, so nothing from Instagram's notifications or screen is stored, scored, forwarded or alerted on. */
internal enum class RiskCapture { NORMAL, CONTENT_FREE, ACTIVE, UPLOAD }

/**
 * Child-side mode state and the API the child screens use. Gating:
 * - contentFree: Terms accepted, child role, parent policy enabled, and the child has accepted (or withdrawn).
 *   From then on no message text is queued for the parent, until the parent's policy is switched off.
 * - active: contentFree + accepted + not paused + collection allowed + full capture + valid model on a supported phone.
 *   Only then is anything stored or scored.
 */
internal object RiskLocal {
    /** Bump when the consent wording changes: a child who agreed to older wording is asked again. */
    private const val WORDING_VERSION = "2026-11-19-scores-v2"
    private const val RECEIPT_PURPOSE = "scores_consent_receipt"
    private const val POLICY_ID = "scores_policy_id"
    private const val POLICY_ENABLED = "scores_policy_enabled"
    private const val POLICY_METHOD = "scores_policy_method"
    private const val PAUSED_AT = "scores_paused_at_ms"
    private const val LAST_RUN = "scores_last_run_ms"
    private const val LAST_SCORED = "scores_last_scored"
    private const val LAST_REVIEW = "scores_last_review"
    private const val BASELINE = "scores_baseline_ms"
    private const val WIPE_PENDING = "scores_wipe_pending"
    private const val LAST_FAILURE = "scores_last_failure_ms"
    private const val CHANGE_NOTICE = "scores_change_notice"
    /** Set when the parent switches the mode off after the child had agreed to manual upload: capture stays off until the child agrees to it. */
    private const val CAPTURE_HELD = "scores_capture_held"
    private const val RECONSENT_WIPED = "scores_reconsent_wiped"
    internal const val REPORT_DUE = "scores_report_due"
    private const val PARENT_POLICY_ID = "scores_parent_policy_id"
    private const val PARENT_POLICY_ENABLED = "scores_parent_policy_enabled"
    private const val PARENT_POLICY_METHOD = "scores_parent_policy_method"
    private const val PARENT_POLICY_AT = "scores_parent_policy_at_ms"
    private const val POLICY_REPOST = "scores_policy_repost_ms"
    private val messageReasons = setOf("stranger_message", "sexual_or_grooming_content", "flagged_contact_follow_up")

    private fun prefs(ctx: Context) = LocalVault.preferences(ctx)
    private fun receiptPrefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("ig_guard_scores_consent", Context.MODE_PRIVATE)

    // ---------------- gates ----------------
    fun policyId(ctx: Context): String = prefs(ctx).getString(POLICY_ID, "") ?: ""
    fun policyEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(POLICY_ENABLED, false) && policyId(ctx).isNotEmpty()
    /** The method the parent's current policy asks for. It takes effect only once the child has agreed to it: see [agreedMethod]. */
    fun policyMethod(ctx: Context): String = prefs(ctx).getString(POLICY_METHOD, null)?.takeIf { ScoresMethod.valid(it) } ?: ScoresMethod.CAPTURE
    fun pausedAt(ctx: Context): Long = prefs(ctx).getLong(PAUSED_AT, 0L)
    fun paused(ctx: Context): Boolean = pausedAt(ctx) > 0

    private fun receipt(ctx: Context): JSONObject? {
        val sealed = receiptPrefs(ctx).getString("receipt", null) ?: return null
        return try { JSONObject(LocalVault.open(ctx.applicationContext, RECEIPT_PURPOSE, sealed)) } catch (_: Exception) { null }
    }

    /**
     * none | accepted | withdrawn | reconsent. A receipt, once written, outlives new policy ids: the child stays content-free
     * (accepted or withdrawn) until a policy with enabled=false arrives and clears it. An unreadable receipt blocks
     * (reads as withdrawn), never as none.
     */
    fun consentState(ctx: Context): String {
        if (!receiptPrefs(ctx).contains("receipt")) return "none"
        val json = receipt(ctx) ?: return "withdrawn"
        if (json.optLong("withdrawn_at") > 0) return "withdrawn"
        // Agreement to older wording does not count ("reconsent"): ask again (pending), while alerts stay content-free in the meantime.
        return if (json.optString("version") == WORDING_VERSION) "accepted" else "reconsent"
    }

    /**
     * The method the child agreed to (a receipt without one means capture), or null with no readable receipt. This, not the parent's
     * latest request, is what the phone does: a method change waits for the child's agreement.
     */
    fun agreedMethod(ctx: Context): String? {
        val r = receipt(ctx) ?: return null
        return r.optString("method").takeIf { ScoresMethod.valid(it) } ?: ScoresMethod.CAPTURE
    }

    /** The child agreed and the parent has since asked for another method. */
    private fun methodChangePending(ctx: Context): Boolean = consentState(ctx) == "accepted" && agreedMethod(ctx) != policyMethod(ctx)

    /** The policy id this phone has answered: the receipt's while a method change is waiting for agreement, so the parent sees "waiting". */
    fun answeredPolicyId(ctx: Context): String = if (methodChangePending(ctx)) receipt(ctx)?.optString("policy_id").orEmpty() else policyId(ctx)

    /** Any receipt, whatever its wording version, keeps the phone content-free until the parent switches the mode off. */
    fun contentFree(ctx: Context): Boolean = AppConsent.hasAccepted(ctx) && GuardEngine.isChildMode(ctx) && policyEnabled(ctx) && receiptPrefs(ctx).contains("receipt")

    fun active(ctx: Context): Boolean =
        AppConsent.hasAccepted(ctx) && GuardEngine.isChildMode(ctx) && GuardEngine.collectionAllowed(ctx) &&
            // Manual upload reads nothing from Instagram, so the confirmed two-way capture scope does not apply to it.
            (GuardEngine.fullCaptureEnabled(ctx) || agreedMethod(ctx) == ScoresMethod.UPLOAD) && policyEnabled(ctx) && consentState(ctx) == "accepted" && !paused(ctx) && RiskModelStore.state(ctx) == RiskModelState.INSTALLED

    /** Old wording was agreed to: nothing may stay stored for it. Wipes the store once, at the first chance, not at daily maintenance. */
    fun enforceWording(ctx: Context) {
        if (consentState(ctx) != "reconsent" || prefs(ctx).getBoolean(RECONSENT_WIPED, false)) return
        wipeStore(ctx)
        prefs(ctx).edit().putBoolean(RECONSENT_WIPED, true).commit()
        changed(ctx)
    }

    fun captureMode(ctx: Context): RiskCapture {
        enforceWording(ctx)
        return captureModeNow(ctx)
    }
    private fun captureModeNow(ctx: Context): RiskCapture =
        if (captureHeld(ctx)) RiskCapture.UPLOAD else if (!contentFree(ctx)) RiskCapture.NORMAL else if (readsNothing(ctx)) RiskCapture.UPLOAD
        else if (active(ctx)) RiskCapture.ACTIVE else RiskCapture.CONTENT_FREE

    /** The child agreed to manual upload and the mode is on: the notification listener and the screen service read nothing. */
    fun uploadOnly(ctx: Context): Boolean = captureHeld(ctx) || (contentFree(ctx) && readsNothing(ctx))

    /** Capture is held off (behaves as the upload method) until the child agrees on the card; survives restarts, cleared by [resetFamily] and by a normal consent in [accept]. */
    fun captureHeld(ctx: Context): Boolean = prefs(ctx).getBoolean(CAPTURE_HELD, false)
    fun releaseCaptureHold(ctx: Context) { prefs(ctx).edit().remove(CAPTURE_HELD).commit(); changed(ctx) }

    /** Only meaningful once [contentFree] holds, i.e. a receipt is present: no agreed method then means the receipt cannot be read, which must fail closed (read nothing) rather than re-enable capture. */
    private fun readsNothing(ctx: Context): Boolean = agreedMethod(ctx).let { it == null || it == ScoresMethod.UPLOAD }

    /** Reasons whose alert text is message content; in content-free mode their text is dropped. */
    fun scrubsText(ctx: Context, reason: String): Boolean = reason in messageReasons && contentFree(ctx)

    // ---------------- capture hooks (called from GuardEngine.captureMessages) ----------------
    /** The thread identity: the conversation label alone (no account, no kind), so every route to one conversation shares it. */
    fun threadLabel(r: JSONObject): String = r.optString("conversation_label").trim().removePrefix("@").lowercase(Locale.ROOT)

    private val background = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "Risk report").apply { isDaemon = true } }
    /** Test hook: waits for report work started after an exclusion. */
    internal fun awaitBackground() { background.submit {}.get() }

    /** Test hook: keeps the follow-up send from running so a test can look at the state between purge and resend. */
    @Volatile internal var deferFollowUp = false

    /**
     * After a thread was set aside as a group: inside the capture's own transaction every queued, undelivered scores report is
     * deleted (any of them may name the thread) and a "report due" flag is persisted, so a stale report cannot be sent even if the
     * process dies; a fresh report follows on its own thread (it waits for the capture's lock) or at the next heartbeat or start.
     * A score already delivered to the parent stays there under the archive's normal deletion rules.
     */
    private fun afterExclusion(ctx: Context, db: FamilyEvidence) {
        val app = ctx.applicationContext
        RiskReporter.purgeQueued(app, GuardEngine.getChannelTopic(app), db)
        prefs(app).edit().putBoolean(REPORT_DUE, true).commit()
        if (!deferFollowUp) background.execute { sendIfDue(app) }
    }

    /** Sends the report that an exclusion or upgrade made due (the flag clears when it was queued). */
    fun sendIfDue(ctx: Context) {
        if (!prefs(ctx).getBoolean(REPORT_DUE, false)) return
        try { if (RiskReporter.send(ctx)) prefs(ctx).edit().remove(REPORT_DUE).commit() } catch (e: Exception) { Log.w("IGGuard", "Scores report failed: ${e.javaClass.simpleName}") }
    }

    /**
     * Marks the record seen; keeps it when [store]. True when the record is new. Never lets a store failure lose the alert.
     * While the child's agreement is current (also when paused) the thread's kind and incoming authors are tracked by blind index
     * only, no text: kind `group` sets the thread aside; kind `unknown` (screen reads, whose incoming messages carry no author)
     * is one-to-one until a second distinct incoming author shows; kind `direct` is one-to-one. A thread is identified by its
     * label alone, so a group seen by notification is also set aside when the same title is read from the screen.
     */
    fun captureContentFree(ctx: Context, db: FamilyEvidence, scope: String, r: JSONObject, store: Boolean, now: Long): Boolean {
        if (!db.markCaptured(scope, r.getString("event_id"))) return false
        try {
            if (consentState(ctx) != "accepted") return true
            val label = threadLabel(r)
            if (label.isEmpty()) return true
            val kind = r.optString("conversation_kind", "unknown")
            val direction = r.optString("direction")
            if (kind == "group") {
                // An untitled group is titled by its sender's name; that name must not set a one-to-one chat aside.
                if (label != r.optString("author").trim().removePrefix("@").lowercase(Locale.ROOT) && db.excludeRiskThread(scope, label, now)) afterExclusion(ctx, db)
                return true
            }
            // Only group and unknown records keep a marker alive; a direct record never does, so a wrong marker can lapse.
            if (db.riskExcluded(scope, label, now)) { if (kind != "direct") db.noteRiskUnknown(scope, label, now); return true }
            if (kind == "unknown" && direction == "incoming") {
                val author = r.optString("author").trim().removePrefix("@").lowercase(Locale.ROOT)
                // The child's own handle is not "another person".
                if (author.isNotEmpty() && !GuardEngine.isSelfSenderLabel(ctx, author) && db.noteRiskAuthor(scope, label, author, now) > 1) {
                    if (db.excludeRiskThread(scope, label, now)) afterExclusion(ctx, db)
                    return true
                }
            }
            if (store && direction in setOf("incoming", "outgoing")) db.storeRiskMessage(scope, r.getString("event_id"), label,
                r.optString("conversation_label"), direction, r.getString("text").replace("\u0000", ""), r.optLong("ts_ms"),
                if (r.has("screen_anchor_ms")) r.getLong("screen_anchor_ms") else r.optLong("ts_ms"),
                if (r.has("screen_order")) r.getLong("screen_order") else r.optLong("capture_order"), now, r.optString("capture_source"))
        } catch (_: Exception) { Log.w("IGGuard", "Scoring store could not keep a message") }
        return true
    }

    /** Sends a category-only alert (no text) for each record; the text is also dropped inside sendAlertFromChild. */
    fun sendContentFreeAlerts(ctx: Context, records: List<JSONObject>, session: Triple<String, String, Int>?) {
        for (r in records) GuardEngine.sendAlertFromChild(ctx, r.getString("reason"), r.optString("conversation_label").ifBlank { "unknown" }, "",
            fromNotification = r.optString("capture_source") == "notification", expectedSession = session)
    }

    // ---------------- wipes ----------------
    /**
     * Empties the message store, results and unknown-thread counters. Scores reports already queued are kept: they hold
     * no message text and the parent has not received them yet; only [resetFamily] deletes them. A failed wipe leaves a
     * persisted flag that the daily maintenance retries.
     */
    fun wipeData(ctx: Context) {
        prefs(ctx).edit().putBoolean(WIPE_PENDING, true).commit()
        wipeStore(ctx)
    }

    /** Another thread can hold the database for a moment: wait briefly on a lock before leaving it to the daily retry. */
    private fun retrying(what: String, step: () -> Unit) {
        for (attempt in 1..8) {
            try { step(); return }
            catch (e: android.database.sqlite.SQLiteDatabaseLockedException) {
                if (attempt == 8) Log.w("IGGuard", "Scoring $what wipe failed: database stayed locked") else Thread.sleep(40L * attempt)
            } catch (e: Exception) { Log.w("IGGuard", "Scoring $what wipe failed: ${e.javaClass.simpleName}"); return }
        }
    }

    /** Store and results only: queued reports (such as the "model is valid" state) are left alone. */
    private fun wipeStore(ctx: Context) = retrying("store") {
        FamilyEvidence(ctx).use { it.wipeRisk() }
        prefs(ctx).edit().remove(LAST_SCORED).remove(LAST_REVIEW).remove(WIPE_PENDING).commit()
    }

    /** Leaving the child role, a new family, or a remote reset: data plus all mode state and the consent receipt. */
    fun resetFamily(ctx: Context) {
        wipeData(ctx)
        // The family ended: its queued reports could never be delivered. The chat text is already gone, so a failure here leaves nothing private behind.
        retrying("report") { RiskReporter.purgeQueued(ctx, GuardEngine.getChannelTopic(ctx)) }
        prefs(ctx).edit().remove(POLICY_ID).remove(POLICY_ENABLED).remove(POLICY_METHOD).remove(PAUSED_AT).remove(LAST_RUN).remove(BASELINE)
            .remove(PARENT_POLICY_ID).remove(PARENT_POLICY_ENABLED).remove(PARENT_POLICY_METHOD).remove(PARENT_POLICY_AT).remove(CHANGE_NOTICE).remove(CAPTURE_HELD).remove(POLICY_REPOST).commit()
        receiptPrefs(ctx).edit().clear().commit()
        try { RiskScoringSchedule.sync(ctx) } catch (_: RuntimeException) {}
    }

    /**
     * Daily safety net: the store survives only while the mode is on and the child has agreed. A failed earlier wipe is
     * retried in full; otherwise only the store is emptied and queued reports are never purged here.
     */
    fun maintain(ctx: Context) {
        if (prefs(ctx).getBoolean(WIPE_PENDING, false)) wipeData(ctx)
        else if (!GuardEngine.isChildMode(ctx) || !policyEnabled(ctx) || consentState(ctx) != "accepted") wipeStore(ctx)
    }

    // ---------------- child actions ----------------
    fun modelState(ctx: Context): RiskModelState = RiskModelStore.state(ctx)
    fun importModel(ctx: Context, input: InputStream, onProgress: (Long) -> Unit = {}): RiskImportResult =
        RiskModelStore.importModel(ctx, input, onProgress).also { if (it == RiskImportResult.OK) changed(ctx) }
    /** Copies the model included in the app (see [RiskModelStore.bundledOpener]) with the same checks as [importModel]. No password: the copy is pinned by size and hash. */
    fun installBundledModel(ctx: Context, source: () -> InputStream, onProgress: (Long) -> Unit = {}): RiskImportResult =
        RiskModelStore.installModel(ctx, source, onProgress, RiskModelStore.expectation.bytes, RiskModelStore.expectation.sha256).also { if (it == RiskImportResult.OK) changed(ctx) }
    /** Parent-password gated by the caller. Afterwards the included copy is not installed again until a parent asks. */
    fun removeModel(ctx: Context) { RiskModelStore.remove(ctx); changed(ctx) }

    /**
     * The consent page is due: the policy is on and the child has not agreed to it, either never, or withdrew and the
     * parent has since switched the mode on again (a new policy id). Meanwhile alerts stay content-free after a withdrawal.
     */
    fun pendingPolicy(ctx: Context): Boolean {
        if (!AppConsent.hasAccepted(ctx) || !GuardEngine.isChildMode(ctx) || !policyEnabled(ctx)) return false
        return when (consentState(ctx)) {
            "none", "reconsent" -> true
            "withdrawn" -> receipt(ctx)?.let { it.optString("policy_id") != policyId(ctx) } ?: false
            else -> methodChangePending(ctx)
        }
    }

    /**
     * A signed parent policy arrived (called from processIncomingRelayLine). Only a policy with enabled=false ends
     * content-free mode: it deletes the store, the pause and the consent receipt. A new id while enabled keeps an
     * accepted consent (no gap); after a withdrawal it makes [pendingPolicy] true again.
     */
    fun receivePolicy(ctx: Context, id: String, enabled: Boolean, method: String = ScoresMethod.CAPTURE) {
        require(ScoresMethod.valid(method))
        val wasEnabled = prefs(ctx).getBoolean(POLICY_ENABLED, false)
        if (policyId(ctx) == id && wasEnabled == enabled) return
        val editor = prefs(ctx).edit().putString(POLICY_ID, id).putBoolean(POLICY_ENABLED, enabled).putString(POLICY_METHOD, method)
        // Manual upload was agreed: switching the mode off must not hand Instagram back to capture without the child agreeing (read before the receipt is cleared).
        val hold = !enabled && agreedMethod(ctx) == ScoresMethod.UPLOAD
        if (hold) editor.putBoolean(CAPTURE_HELD, true)
        if (!enabled) {
            editor.remove(PAUSED_AT).commit()
            receiptPrefs(ctx).edit().clear().commit()
            wipeData(ctx)
            if (wasEnabled) {
                prefs(ctx).edit().putString(CHANGE_NOTICE, "off").commit()
                postSwitchOffNotice(ctx, hold || captureHeld(ctx))
            }
        } else editor.commit()
        changed(ctx)
    }

    /** "on" after the child agreed, "off" after the parent switched the mode off, until [acknowledgeChangeNotice]. */
    fun pendingChangeNotice(ctx: Context): String? = prefs(ctx).getString(CHANGE_NOTICE, null)?.takeIf { it == "on" || it == "off" }
    fun acknowledgeChangeNotice(ctx: Context) { prefs(ctx).edit().remove(CHANGE_NOTICE).commit() }

    /** One notification at the moment the parent's switch-off applies, on the monitoring-disclosure channel. */
    private fun postSwitchOffNotice(ctx: Context, held: Boolean) {
        try {
            val manager = ctx.getSystemService(android.app.NotificationManager::class.java) ?: return
            manager.createNotificationChannel(android.app.NotificationChannel("child_disclosure", "子女模式監察提示", android.app.NotificationManager.IMPORTANCE_LOW))
            if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) return
            val open = android.app.PendingIntent.getActivity(ctx, 7002, Intent(ctx, MainActivity::class.java), android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
            val text = if (held) "你之前選擇手動上載，IG Guard 不會讀取 Instagram 的通知或對話畫面，除非你在 App 內同意。\nYou chose manual upload, so IG Guard reads no Instagram notifications or conversation screens unless you agree in the app."
                else "先前的模式再次適用：如已啟用完整擷取，訊息會再傳送給家長。\nThe earlier mode applies again: if full capture is on, messages are sent to your parent again."
            val notice = android.app.Notification.Builder(ctx, "child_disclosure").setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setContentTitle("IG Guard · 家長已關閉「只傳分數」模式 (scores-only mode turned off)")
                .setContentText(text.replace("\n", " "))
                .setStyle(android.app.Notification.BigTextStyle().bigText(text))
                .setContentIntent(open).setAutoCancel(true).build()
            manager.notify(7002, notice)
        } catch (_: RuntimeException) { /* The notice is best effort; the persisted change flag is the record. */ }
    }

    private fun carriesText(token: String, wire: String): Boolean {
        val json = GuardEngine.decryptPayload(token, wire) ?: return true   // unreadable: do not risk forwarding it
        return when (json.optString("type")) {
            "captured_message", "alert_fragment" -> true
            "alert" -> !json.optBoolean("relay_control") && json.optString("reason") in messageReasons && json.optString("text").isNotEmpty()
            else -> false
        }
    }

    /**
     * The child agrees to the policy [shownPolicyId] and method [shownMethod] the consent page showed; false (nothing written) if either is no longer the pending one. Queued records that carry message text are discarded so none reaches the parent.
     * Runs under the session lock on the caller's thread: a few small SQLite statements, one sealed preference and a
     * locally queued report (no network I/O), so it is short enough for a button handler; the return value is synchronous.
     */
    fun accept(ctx: Context, shownPolicyId: String, shownMethod: String): Boolean = GuardEngine.withSessionLock {
        if (!pendingPolicy(ctx)) return@withSessionLock false
        // The child agrees to what the page showed: a policy or method that changed since is not agreed to.
        if (policyId(ctx) != shownPolicyId || policyMethod(ctx) != shownMethod) return@withSessionLock false
        val now = System.currentTimeMillis()
        val method = policyMethod(ctx)
        val methodChanged = agreedMethod(ctx)?.let { it != method } ?: false
        val receipt = JSONObject().put("version", WORDING_VERSION).put("policy_id", policyId(ctx)).put("method", method).put("accepted_at", now).put("withdrawn_at", 0L)
        LocalVault.preferences(ctx)
        if (!receiptPrefs(ctx).edit().putString("receipt", LocalVault.seal(ctx, RECEIPT_PURPOSE, receipt.toString())).commit()) return@withSessionLock false
        // Only queued records that can carry message text go; tamper, unlock, pairing, heartbeat and control records stay.
        val token = GuardEngine.getOrCreateFamilyToken(ctx)
        FamilyEvidence(ctx).use { db -> db.discardPendingMatching(GuardEngine.getChannelTopic(ctx)) { wire -> carriesText(token, wire) } }
        // Text kept for the earlier method must not mix with the new one's: a method change deletes it (queued score reports stay).
        if (methodChanged) wipeData(ctx)
        prefs(ctx).edit().putString(CHANGE_NOTICE, "on").remove(RECONSENT_WIPED).remove(CAPTURE_HELD).commit()
        prefs(ctx).edit().remove(PAUSED_AT).putLong(BASELINE, now).commit()
        changed(ctx)
        true
    }

    /** Deletes the stored chat text (queued score reports stay) and tells the parent. Alerts stay text-free until the parent switches the mode off. */
    fun withdraw(ctx: Context): Boolean = GuardEngine.withSessionLock {
        if (consentState(ctx) != "accepted" && consentState(ctx) != "reconsent") return@withSessionLock false
        val sealed = receiptPrefs(ctx).getString("receipt", null) ?: return@withSessionLock false
        val receipt = JSONObject(LocalVault.open(ctx, RECEIPT_PURPOSE, sealed)).put("withdrawn_at", System.currentTimeMillis())
        if (!receiptPrefs(ctx).edit().putString("receipt", LocalVault.seal(ctx, RECEIPT_PURPOSE, receipt.toString())).commit()) return@withSessionLock false
        prefs(ctx).edit().remove(PAUSED_AT).commit()
        wipeData(ctx)
        changed(ctx)
        true
    }

    /** Takes effect at once: no more storing or scoring. A scorer call already running cannot be interrupted; its result is discarded. */
    fun pause(ctx: Context): Boolean = GuardEngine.withSessionLock {
        if (consentState(ctx) != "accepted" || paused(ctx)) return@withSessionLock false
        prefs(ctx).edit().putLong(PAUSED_AT, System.currentTimeMillis()).commit()
        changed(ctx)
        true
    }

    fun resume(ctx: Context): Boolean = GuardEngine.withSessionLock {
        if (consentState(ctx) != "accepted" || !paused(ctx)) return@withSessionLock false
        prefs(ctx).edit().remove(PAUSED_AT).commit()
        changed(ctx)
        true
    }

    fun status(ctx: Context): RiskChildStatus {
        val scope = GuardEngine.getChannelTopic(ctx)
        val (unknown, review) = try {
            FamilyEvidence(ctx).use { db ->
                val now = System.currentTimeMillis()
                db.riskUnknownCount(scope, now) to db.riskReportRows(scope, now).count { it.score == null }
            }
        } catch (_: Exception) { 0 to 0 }
        val last = prefs(ctx).getLong(LAST_RUN, 0L)
        return RiskChildStatus(RiskModelStore.state(ctx).wire, policyEnabled(ctx), consentState(ctx), paused(ctx), pausedAt(ctx).takeIf { it > 0 },
            active(ctx), last.takeIf { it > 0 }, prefs(ctx).getInt(LAST_SCORED, 0), review, unknown, pendingPolicy(ctx), policyMethod(ctx), agreedMethod(ctx))
    }

    fun lastRun(ctx: Context): Long = prefs(ctx).getLong(LAST_RUN, 0L)
    /** The time the 48-hour charging-only fallback counts from: the last completed run, else when the child agreed. */
    fun runBaseline(ctx: Context): Long = lastRun(ctx).takeIf { it > 0 } ?: prefs(ctx).getLong(BASELINE, 0L).takeIf { it > 0 } ?: System.currentTimeMillis()
    fun recordRun(ctx: Context, scored: Int, now: Long = System.currentTimeMillis()) {
        prefs(ctx).edit().putLong(LAST_RUN, now).putInt(LAST_SCORED, scored).commit()
    }
    fun scoredCount(ctx: Context): Int = prefs(ctx).getInt(LAST_SCORED, 0)
    /** A run that could not start or finish: the fallback job waits at least six hours before trying again. */
    fun recordFailure(ctx: Context, now: Long = System.currentTimeMillis()) { prefs(ctx).edit().putLong(LAST_FAILURE, now).commit() }
    fun lastFailure(ctx: Context): Long = prefs(ctx).getLong(LAST_FAILURE, 0L)

    /** A state change the parent must hear about, and the job schedule may need to follow. Never throws. */
    fun changed(ctx: Context) {
        try { RiskScoringSchedule.sync(ctx) } catch (_: RuntimeException) { Log.w("IGGuard", "Scoring job could not be scheduled") }
        try { RiskReporter.send(ctx) } catch (e: Exception) { Log.w("IGGuard", "Scores report failed: ${e.javaClass.simpleName}") }
        try { GuardEngine.refreshMonitoringDisclosure(ctx) } catch (_: RuntimeException) {}
        try { ctx.sendBroadcast(Intent(GuardEngine.ACTION_UI_REFRESH).setPackage(ctx.packageName)) } catch (_: RuntimeException) {}
    }

    /** Called from the child's heartbeat: a state-only report at least once a day, which also keeps the parent's view fresh. */
    fun dailyReport(ctx: Context, now: Long = System.currentTimeMillis()) {
        if (!GuardEngine.isChildMode(ctx)) return
        if (prefs(ctx).getBoolean(REPORT_DUE, false)) { sendIfDue(ctx); return }
        if (now - prefs(ctx).getLong("scores_last_report_ms", 0L) < RiskReporter.FULL_RESEND_MS) return
        // State only, whatever the mode, once a model file is present or a policy has ever been received.
        if (!RiskModelStore.file(ctx).isFile && policyId(ctx).isEmpty()) return
        try { RiskReporter.send(ctx, now) } catch (e: Exception) { Log.w("IGGuard", "Scores report failed: ${e.javaClass.simpleName}") }
    }

    /**
     * The always-visible line for the child's ongoing notification. [base] is the existing capture text, kept only
     * while message text can still reach the parent (the mode is off or the child has not agreed yet).
     */
    fun disclosure(ctx: Context, base: String): String {
        if (!GuardEngine.isChildMode(ctx) || !policyEnabled(ctx)) return base
        val consent = consentState(ctx)
        return when {
            pendingPolicy(ctx) && !contentFree(ctx) -> "AI 風險分析 (AI risk analysis)：等待你同意，尚未開始。$base"
            uploadOnly(ctx) && consent == "accepted" && !paused(ctx) -> "AI 風險分析（手動上載）：本 App 不讀取 Instagram 的通知或畫面，只分析你匯入的檔案；家長只收到風險分數。 (Manual upload: this app reads nothing from Instagram; your parent receives risk scores only.)"
            pendingPolicy(ctx) -> "AI 風險分析 (AI risk analysis)：等待你再次同意 · 警報不含訊息內容。"
            consent == "withdrawn" -> "你已撤回 AI 風險分析 (withdrawn) · 警報不含訊息內容。"
            consent == "none" -> base
            paused(ctx) -> "AI 風險分析已暫停 (paused) · 不儲存也不分析訊息；警報不含訊息內容。"
            active(ctx) -> "AI 風險分析已開啟 (AI risk analysis ON) · 訊息只在此手機分析，家長只收到風險分數，看不到訊息內容。"
            else -> "AI 風險分析已開啟 (ON)，但此手機暫時未能執行 · 警報不含訊息內容。"
        }
    }

    // ---------------- parent policy bookkeeping ----------------
    fun parentPolicy(ctx: Context): ScoresModePolicy? {
        val id = prefs(ctx).getString(PARENT_POLICY_ID, "") ?: ""
        return if (id.isEmpty()) null else ScoresModePolicy(id, prefs(ctx).getBoolean(PARENT_POLICY_ENABLED, false), prefs(ctx).getLong(PARENT_POLICY_AT, 0L),
            prefs(ctx).getString(PARENT_POLICY_METHOD, null)?.takeIf { ScoresMethod.valid(it) } ?: ScoresMethod.CAPTURE)
    }
    /** Parent: true (and recorded) at most once an hour, for the automatic re-post of an unapplied policy. */
    fun repostDue(ctx: Context, now: Long = System.currentTimeMillis()): Boolean {
        if (now - prefs(ctx).getLong(POLICY_REPOST, 0L) < 3_600_000L) return false
        return prefs(ctx).edit().putLong(POLICY_REPOST, now).commit()
    }
    fun saveParentPolicy(ctx: Context, id: String, enabled: Boolean, at: Long, method: String = ScoresMethod.CAPTURE): Boolean =
        prefs(ctx).edit().putString(PARENT_POLICY_ID, id).putBoolean(PARENT_POLICY_ENABLED, enabled).putString(PARENT_POLICY_METHOD, method).putLong(PARENT_POLICY_AT, at).commit()
}

/** Message text on its way into a prompt. */
internal object RiskMessages {
    /**
     * Cleans untrusted message text before it is stored or prompted: NUL bytes are removed, and anything that could
     * be read as a ChatML, think or tool token (`<|`, `<think>`, `<tool_call>`, `<tool_response>` and their closing forms) gets a zero-width space after its `<`.
     * This is a deliberate deviation from the research harness, which only ever saw ordinary invented chats; it is
     * applied here, in the mapping from stored message to [RiskMessage], so [RiskPrompt] stays byte-identical to the harness.
     */
    fun sanitize(text: String): String = text.replace("\u0000", "").replace(Regex("<(?=\\||/?(?:think|tool_call|tool_response)>)"), "<\u200B")
}

/** Builds and queues `scores_mode_report`. Carries a score, an account label and times, never message text. */
internal object RiskReporter {
    const val FULL_RESEND_MS = 24L * 3_600_000L
    private const val IDS = "scores_report_ids"
    private const val LAST_REPORT = "scores_last_report_ms"
    private fun prefs(ctx: Context) = LocalVault.preferences(ctx)

    fun stateJson(ctx: Context, unknownThreads: Int): JSONObject {
        val paused = RiskLocal.pausedAt(ctx).takeIf { it > 0 }
        val last = RiskLocal.lastRun(ctx).takeIf { it > 0 }
        return JSONObject().put("policy_id", RiskLocal.answeredPolicyId(ctx).ifEmpty { null } ?: JSONObject.NULL)
            .put("model", RiskModelStore.state(ctx).wire).put("consent", RiskLocal.consentState(ctx)).put("active", RiskLocal.active(ctx))
            .put("paused", paused != null).put("paused_at_ms", paused ?: JSONObject.NULL).put("last_run_ms", last ?: JSONObject.NULL)
            .put("scored_count", RiskLocal.scoredCount(ctx)).put("unknown_threads", unknownThreads)
    }

    internal data class Merged(val row: RiskReportRow, val members: List<RiskReportRow>, val changed: Boolean)
    private fun key(label: String) = label.take(RiskLimits.MAX_LABEL).trim().removePrefix("@").lowercase()

    /** One row per normalised label: highest score; with none scored, the review reason of the latest; the latest time. */
    internal fun merge(rows: List<RiskReportRow>): List<Merged> = rows.filter { key(it.label).isNotEmpty() }.groupBy { key(it.label) }.values.map { group ->
        val best = group.mapNotNull { it.score }.maxOrNull()
        val latest = group.maxByOrNull { it.scoredAt }!!
        Merged(RiskReportRow("", latest.label.take(RiskLimits.MAX_LABEL), best, if (best != null) null else latest.reason, latest.scoredAt), group, group.any { it.changed })
    }.sortedBy { it.row.scoredAt }

    private fun tracked(ctx: Context): List<Pair<String, String>> = try {
        val array = JSONArray(prefs(ctx).getString(IDS, "[]") ?: "[]")
        (0 until array.length()).map { array.getString(it).substringBefore('|') to array.getString(it).substringAfter('|') }
    } catch (_: Exception) { emptyList() }

    /** Event ids of the scores reports this phone queued for [scope] (they may already be delivered). */
    fun queuedEventIds(ctx: Context, scope: String): List<String> = tracked(ctx).filter { it.first == scope }.map { "control:${it.second}" }

    /** Deletes scores reports that are still waiting in the outbox. True if any was found. */
    fun purgeQueued(ctx: Context, scope: String, within: FamilyEvidence? = null): Boolean {
        val entries = tracked(ctx)
        if (entries.isEmpty()) return false
        var found = false
        fun purge(db: FamilyEvidence) {
            for ((entryScope, id) in entries) if (db.hasQueuedEvent(entryScope, "control:$id")) { db.acknowledgeEvent(entryScope, "control:$id"); found = true }
        }
        if (within != null) purge(within) else FamilyEvidence(ctx).use { purge(it) }
        prefs(ctx).edit().remove(IDS).commit()
        return found
    }

    private fun track(ctx: Context, scope: String, id: String) {
        val kept = tracked(ctx).takeLast(63).map { "${it.first}|${it.second}" } + "$scope|$id"
        prefs(ctx).edit().putString(IDS, JSONArray(kept).toString()).commit()
    }

    /**
     * Sends the state plus every conversation row that changed since it was last sent (200 per record). Rows go out only
     * while consent is `accepted`; otherwise the record is state-only. The whole build-and-queue runs under the session
     * lock, so rows cannot be queued after a withdrawal. A newer report replaces any older one still queued (the parent
     * follows arrival order); rows are resent in full only after such an undelivered report was purged. True if all records were queued.
     */
    fun send(ctx: Context, now: Long = System.currentTimeMillis()): Boolean = GuardEngine.withSessionLock {
        val app = ctx.applicationContext
        if (!GuardEngine.transferAllowed(app) || !GuardEngine.isChildMode(app)) return@withSessionLock false
        val scope = GuardEngine.getChannelTopic(app)
        FamilyEvidence(app).use { db ->
            // An older queued report is replaced only by one that carries rows again. With no rows to carry (after a wipe, or
            // without consent) it stays queued, so the scores it holds still reach the parent.
            val replacing = RiskLocal.consentState(app) == "accepted" && db.riskReportRows(scope, now).isNotEmpty()
            val discarded = (replacing && purgeQueued(app, scope, db)) || prefs(app).getBoolean(RiskLocal.REPORT_DUE, false)
            if (discarded) db.resetRiskSent(scope)
            val merged = if (RiskLocal.consentState(app) == "accepted") merge(db.riskReportRows(scope, now)).filter { it.changed } else emptyList()
            val unknown = db.riskUnknownCount(scope, now)
            for (chunk in merged.chunked(RiskLimits.MAX_REPORT_ROWS).ifEmpty { listOf(emptyList()) }) {
                val conversations = JSONArray()
                for (m in chunk) conversations.put(JSONObject().put("account_label", m.row.label)
                    .put("score", m.row.score ?: JSONObject.NULL).put("review_reason", m.row.reason ?: JSONObject.NULL).put("scored_at_ms", m.row.scoredAt))
                val payload = JSONObject().put("type", "scores_mode_report").put("sent_at_ms", now).put("state", stateJson(app, unknown)).put("conversations", conversations)
                val id = GuardEngine.queueScoresReport(app, payload) ?: return@withSessionLock false
                track(app, scope, id)
                db.markRiskSent(scope, chunk.flatMap { it.members })
            }
        }
        prefs(app).edit().putLong(LAST_REPORT, now).commit()
        true
    }
}

/** Parent-side strict reader. Anything that is not exactly the documented shape is dropped whole. */
internal data class ScoresReportRow(val label: String, val score: Double?, val reviewReason: String?, val scoredAt: Long)
internal data class ChildScoresState(
    val policyId: String?, val model: String, val consent: String, val paused: Boolean, val pausedAt: Long?,
    val lastRun: Long?, val scoredCount: Int, val unknownThreads: Int, val active: Boolean
)
internal data class ParsedScoresReport(val commandId: String, val sentAt: Long, val state: ChildScoresState, val conversations: List<ScoresReportRow>)

internal object ScoresReportCodec {
    private val models = setOf("valid", "missing", "mismatch", "unsupported_device", "engine_unavailable")
    // "reconsent": the child agreed to older wording and is asked again. A parent that does not know a value drops the whole
    // report (nothing is stored, nothing crashes) and keeps showing its last state until it goes stale after 72 hours.
    private val consents = setOf("none", "accepted", "withdrawn", "reconsent")
    private val reasons = setOf("insufficient_context", "context_too_long")

    private fun keys(o: JSONObject) = o.keys().asSequence().toSet()
    private fun long(v: Any?): Long = when (v) { is Int -> v.toLong(); is Long -> v; else -> error("not an integer") }
    private fun nullableLong(v: Any?): Long? = if (v == JSONObject.NULL) null else long(v).also { require(it > 0) }

    /** [scope] is this family's channel topic; the transport adds it and `command_id` to the payload. */
    fun parse(json: JSONObject, scope: String, now: Long): ParsedScoresReport? = try {
        require(keys(json) == setOf("type", "sent_at_ms", "state", "conversations", "scope", "command_id"))
        require(json.get("type") == "scores_mode_report" && json.get("scope") == scope)
        UUID.fromString(json.getString("command_id"))
        val sentAt = long(json.get("sent_at_ms"))
        require(sentAt > 0 && sentAt <= now + 86_400_000L)
        val state = json.get("state") as JSONObject
        require(keys(state) == setOf("policy_id", "model", "consent", "active", "paused", "paused_at_ms", "last_run_ms", "scored_count", "unknown_threads"))
        val policy = state.get("policy_id").let { if (it == JSONObject.NULL) null else (it as String).also { id -> require(UUID.fromString(id).toString() == id) } }
        val model = state.get("model") as String
        val consent = state.get("consent") as String
        require(model in models && consent in consents)
        val active = state.get("active") as Boolean
        val paused = state.get("paused") as Boolean
        val pausedAt = nullableLong(state.get("paused_at_ms"))
        require(paused == (pausedAt != null))
        val lastRun = nullableLong(state.get("last_run_ms"))
        val scored = long(state.get("scored_count")).also { require(it in 0..100_000) }.toInt()
        val unknown = long(state.get("unknown_threads")).also { require(it in 0..100_000) }.toInt()
        val array = json.get("conversations") as JSONArray
        require(array.length() <= RiskLimits.MAX_REPORT_ROWS)
        val seen = mutableSetOf<String>()
        val rows = (0 until array.length()).map { i ->
            val o = array.get(i) as JSONObject
            require(keys(o) == setOf("account_label", "score", "review_reason", "scored_at_ms"))
            val label = o.get("account_label") as String
            require(label.isNotBlank() && label.length <= RiskLimits.MAX_LABEL && seen.add(label.trim().removePrefix("@").lowercase()))
            val scoreValue = o.get("score")
            val score = if (scoreValue == JSONObject.NULL) null else (scoreValue as Number).toDouble().also { require(it.isFinite() && it in 0.0..100.0) }
            val reasonValue = o.get("review_reason")
            val reason = if (reasonValue == JSONObject.NULL) null else (reasonValue as String).also { require(it in reasons) }
            require((score == null) == (reason != null))
            val at = long(o.get("scored_at_ms"))
            require(at > 0 && at <= sentAt)
            ScoresReportRow(label.trim(), score, reason, at)
        }
        ParsedScoresReport(json.getString("command_id"), sentAt, ChildScoresState(policy, model, consent, paused, pausedAt, lastRun, scored, unknown, active), rows)
    } catch (_: Exception) { null }
}

/** One scoring run over the threads with new messages. The scorer call is not interruptible; pause, stop and session checks run between threads. */
internal object RiskBatch {
    enum class Outcome { COMPLETED, STOPPED, SESSION_CHANGED, INACTIVE, MODEL_MISMATCH, INSTRUCTION_MISMATCH, FAILED }
    data class Result(val outcome: Outcome, val scored: Int = 0, val needsReview: Int = 0, val failedThreads: Int = 0)

    /** Returns the instruction text only if its SHA-256 is the pinned one. */
    internal fun checkedInstruction(bytes: ByteArray): String? {
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return if (sha == RiskModelSpec.INSTRUCTION_SHA256) bytes.toString(Charsets.UTF_8) else null
    }
    /** Test hook: where the instruction comes from. */
    @Volatile internal var instructionLoader: (Context) -> String? = { ctx -> checkedInstruction(ctx.resources.openRawResource(R.raw.risk_instruction_v1).use { it.readBytes() }) }

    private fun failed(app: Context, outcome: Outcome): Result { RiskLocal.recordFailure(app); return Result(outcome) }

    /**
     * A thread whose scorer call throws anything but [RiskContextTooLong] is stored as "insufficient_context" at its
     * newest sequence, so it is not retried until it has new messages, and the run goes on. A scorer that cannot be
     * opened (or a library that cannot load) fails the run: nothing is recorded and the failure time delays the next try.
     */
    fun run(ctx: Context, openScorer: (File) -> RiskScorer, shouldStop: () -> Boolean): Result {
        val app = ctx.applicationContext
        val session = GuardEngine.captureRelaySession(app)
        fun halt(): Outcome? = when {
            shouldStop() -> Outcome.STOPPED
            GuardEngine.captureRelaySession(app) != session -> Outcome.SESSION_CHANGED
            !RiskLocal.active(app) -> Outcome.INACTIVE
            else -> null
        }
        halt()?.let { return Result(it) }
        val instruction = instructionLoader(app) ?: return failed(app, Outcome.INSTRUCTION_MISMATCH)
        val scope = GuardEngine.getChannelTopicForToken(session.second)
        val due = FamilyEvidence(app).use { it.riskDueThreads(scope) }
        var scored = 0
        var review = 0
        var failures = 0
        var outcome = Outcome.COMPLETED
        if (due.isNotEmpty()) {
            if (!RiskModelStore.verify(app)) {
                RiskLocal.recordFailure(app)
                RiskReporter.send(app)
                return Result(Outcome.MODEL_MISMATCH)
            }
            var scorer: RiskScorer? = null
            try {
                scorer = openScorer(RiskModelStore.file(app))
                for ((thread, seq) in due) {
                    val before = halt()
                    if (before != null) { outcome = before; break }
                    val messages = FamilyEvidence(app).use { it.riskThreadMessages(scope, thread) }
                    if (messages.isEmpty()) continue
                    var score: Double? = null
                    var reason: String? = null
                    try {
                        val result = RiskScoring.fromLogits(scorer.score(RiskPrompt.render(instruction, messages)))
                        score = result.reportScore
                        if (score == null) reason = "insufficient_context"
                    } catch (_: RiskContextTooLong) { reason = "context_too_long"
                    } catch (e: Exception) {
                        Log.w("IGGuard", "One thread could not be scored: ${e.javaClass.simpleName}")
                        reason = "insufficient_context"; failures++
                    }
                    // A pause, withdrawal or session change during the (uninterruptible) scorer call discards the result.
                    val after = halt()
                    if (after != null) { outcome = after; break }
                    if (FamilyEvidence(app).use { it.saveRiskResult(scope, thread, seq, score, reason, System.currentTimeMillis()) }) {
                        scored++
                        if (score == null) review++
                    }
                }
            } catch (e: Throwable) {
                // A missing or unloadable native library must never take the process down.
                Log.w("IGGuard", "Scoring run failed: ${e.javaClass.simpleName}")
                if (e is LinkageError) RiskModelStore.markNativeUnavailable(app)
                RiskLocal.recordFailure(app)
                outcome = Outcome.FAILED
            } finally {
                try { scorer?.close() } catch (_: Throwable) {}
            }
        }
        if (outcome == Outcome.SESSION_CHANGED || outcome == Outcome.INACTIVE) return Result(outcome, scored, review, failures)
        if (outcome == Outcome.COMPLETED) {
            val current = FamilyEvidence(app).use { db -> db.riskReportRows(scope, System.currentTimeMillis()).size }
            RiskLocal.recordRun(app, current)
        }
        try { if (outcome == Outcome.FAILED) RiskLocal.changed(app) else RiskReporter.send(app) } catch (e: Exception) { Log.w("IGGuard", "Scores report failed: ${e.javaClass.simpleName}") }
        return Result(outcome, scored, review, failures)
    }
}

/** At most one batch at a time across the periodic and fallback jobs; stop requests only reach the job that is running. */
internal object RiskRunGuard {
    private val busy = AtomicBoolean(false)
    private val stop = AtomicBoolean(false)
    @Volatile private var runningJob = -1
    fun tryEnter(jobId: Int): Boolean { if (!busy.compareAndSet(false, true)) return false; runningJob = jobId; stop.set(false); return true }
    fun leave() { runningJob = -1; stop.set(false); busy.set(false) }
    /** True only if [jobId] is the job that is running. */
    fun requestStop(jobId: Int): Boolean { if (runningJob != jobId) return false; stop.set(true); return true }
    fun stopRequested() = stop.get()
    fun running() = busy.get()
}

/**
 * Two persisted jobs: a periodic one that needs idle + charging, and a one-shot fallback that needs charging only and
 * becomes eligible 48 hours after the last completed run (or after the child agreed, if none has run). After a failed
 * run the fallback waits at least six hours.
 */
internal object RiskScoringSchedule {
    const val PRIMARY_ID = 0x49475253
    const val FALLBACK_ID = PRIMARY_ID + 1
    const val PERIOD_MS = 6L * 3_600_000L

    private fun scheduler(ctx: Context) = ctx.applicationContext.getSystemService(JobScheduler::class.java)

    fun fallbackLatency(ctx: Context, now: Long = System.currentTimeMillis()): Long {
        val failure = RiskLocal.lastFailure(ctx).takeIf { it > 0 }?.let { it + PERIOD_MS - now } ?: 0L
        return maxOf(0L, RiskLocal.runBaseline(ctx) + RiskLimits.FALLBACK_MS - now, failure)
    }

    /** Schedules both jobs while [RiskLocal.active], cancels both otherwise. Safe to call often; pending jobs are kept. */
    fun sync(ctx: Context) {
        val app = ctx.applicationContext
        RiskModelStore.cleanStale(app)
        RiskLocal.enforceWording(app)
        RiskLocal.sendIfDue(app)
        val scheduler = scheduler(app) ?: return
        if (!RiskLocal.active(app)) { scheduler.cancel(PRIMARY_ID); scheduler.cancel(FALLBACK_ID); return }
        val component = ComponentName(app, RiskScoringJob::class.java)
        if (scheduler.getPendingJob(PRIMARY_ID) == null) scheduler.schedule(JobInfo.Builder(PRIMARY_ID, component)
            .setRequiresCharging(true).setRequiresDeviceIdle(true).setPeriodic(PERIOD_MS).setPersisted(true).build())
        if (scheduler.getPendingJob(FALLBACK_ID) == null) scheduleFallback(app, scheduler, component)
    }

    /** After a run: restarts the 48-hour clock (or the six-hour back-off after a failure). */
    fun rearm(ctx: Context) {
        val app = ctx.applicationContext
        val scheduler = scheduler(app) ?: return
        scheduler.cancel(FALLBACK_ID)
        if (RiskLocal.active(app)) scheduleFallback(app, scheduler, ComponentName(app, RiskScoringJob::class.java))
    }

    private fun scheduleFallback(app: Context, scheduler: JobScheduler, component: ComponentName) {
        scheduler.schedule(JobInfo.Builder(FALLBACK_ID, component).setRequiresCharging(true)
            .setMinimumLatency(fallbackLatency(app)).setPersisted(true).build())
    }
}

class RiskScoringJob : JobService() {
    companion object {
        /** The one place that names the native scorer. */
        internal var scorerFactory: (File) -> RiskScorer = { RiskNative.open(it, minOf(4, Runtime.getRuntime().availableProcessors())) }
    }
    private val completion = Handler(Looper.getMainLooper())
    @Volatile private var ownedJob = -1
    @Volatile private var stoppedBySystem = false

    override fun onStartJob(params: JobParameters): Boolean {
        // The periodic and fallback jobs never run two batches at once; the second one simply ends.
        if (!RiskRunGuard.tryEnter(params.jobId)) return false
        ownedJob = params.jobId; stoppedBySystem = false
        Thread({
            try { RiskBatch.run(applicationContext, scorerFactory) { RiskRunGuard.stopRequested() } }
            catch (e: Throwable) {
                if (e is LinkageError) RiskModelStore.markNativeUnavailable(applicationContext)
                try { RiskLocal.recordFailure(applicationContext) } catch (_: Throwable) {}
            }
            try { RiskScoringSchedule.rearm(applicationContext) } catch (_: Throwable) {}
            RiskRunGuard.leave()
            completion.post { if (!stoppedBySystem) jobFinished(params, false) }
        }, "Risk scoring").start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        if (params.jobId == ownedJob) { stoppedBySystem = true; RiskRunGuard.requestStop(params.jobId) }
        return params.jobId == RiskScoringSchedule.FALLBACK_ID
    }

    override fun onDestroy() {
        if (ownedJob != -1) RiskRunGuard.requestStop(ownedJob)
        super.onDestroy()
    }
}
