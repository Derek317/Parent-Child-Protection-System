package app.family.igguard

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.Dialog
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcelable
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.InputType
import android.text.TextWatcher
import android.text.Editable
import android.util.Base64
import android.view.View
import android.view.MotionEvent
import android.view.Window
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ImageButton
import android.widget.Space
import android.view.Gravity
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.HashSet
import java.util.Locale
import java.util.UUID
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread
import kotlin.random.Random

/** Calendar-day counters; no Android dependencies, so replay/date behavior is JVM-testable. */
class AlertStatistics(val state: JSONObject, private val now: Long) {
    val zone: ZoneId = try { ZoneId.of(state.optString("zone", ZoneId.systemDefault().id)) }
        catch (_: Exception) { ZoneId.systemDefault() }
    val dates: List<String> = (6L downTo 0L).map { Instant.ofEpochMilli(now).atZone(zone).toLocalDate().minusDays(it).toString() }
    private val days = state.optJSONObject("days") ?: JSONObject().also { state.put("days", it) }
    private val seen = state.optJSONObject("seen") ?: JSONObject().also { state.put("seen", it) }
    private val legacy = state.optJSONObject("legacy") ?: JSONObject().also { state.put("legacy", it) }

    companion object {
        val reasons = listOf("stranger_message", "sexual_or_grooming_content", "tamper_attempt", "unlock_used", "other")
        val labels = listOf("未核准訊息", "關鍵字相符", "防篡改 / 離線", "臨時解鎖", "其他警報")
        private val routine = setOf("heartbeat_ok", "pairing_verified", "test_alert")
    }

    init {
        state.put("zone", zone.id)
        if (!state.has("coverage_start")) state.put("coverage_start", now)
        for (key in days.keys().asSequence().toList()) if (key !in dates) days.remove(key)
        for (map in listOf(seen, legacy)) {
            for (key in map.keys().asSequence().toList()) if (map.optLong(key) < now - 8 * 86_400_000L) map.remove(key)
        }
    }

    private fun fingerprint(alert: JSONObject): String {
        val canonical = JSONArray().put(alert.optString("reason")).put(alert.optString("sender"))
            .put(alert.optString("text")).put(alert.optLong("ts_ms")).toString()
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun migrate(alerts: List<JSONObject>) {
        for (alert in alerts) {
            // Old entries with no timestamp cannot honestly be placed on the chart.
            if (alert.optLong("ts_ms") <= 0L || alert.optLong("ts_ms") > now) continue
            val key = fingerprint(alert)
            record("legacy:$key", alert)
            legacy.put(key, now)
        }
        state.put("history_partial", alerts.isNotEmpty())
    }

    /** Returns false for a previously accepted event, even after statistics reset. */
    fun record(id: String, alert: JSONObject): Boolean {
        if (seen.has(id)) return false
        if (legacy.has(fingerprint(alert))) return false
        val reason = alert.optString("reason", "other")
        if(alert.optString("type")=="captured_message" && !ConversationCapture.actionable(alert))return true
        if (reason in routine) return true
        val timestamp = alert.optLong("ts_ms").takeIf { it in 1..now } ?: now
        val date = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate().toString()
        if (date !in dates) return true
        val bucket = days.optJSONObject(date) ?: JSONObject().also { days.put(date, it) }
        val type = reason.takeIf { it in reasons } ?: "other"
        bucket.put(type, bucket.optLong(type) + 1L)
        seen.put(id, now)
        return true
    }

    fun dailyTotals(): List<Long> = dates.map { date -> reasons.sumOf { days.optJSONObject(date)?.optLong(it) ?: 0L } }
    fun typeTotals(): List<Long> = reasons.map { type -> dates.sumOf { days.optJSONObject(it)?.optLong(type) ?: 0L } }

    fun reset() {
        for (key in days.keys().asSequence().toList()) days.remove(key)
        state.put("coverage_start", now).put("history_partial", false)
        // Keep replay IDs, including imported legacy fingerprints.
    }
}

/**
 * Family-Friendly Instagram DM Safety Guard
 * Co-authored & maintained with @citreae_313
 * Example protected teen account handle: @zuck
 */
object GuardEngine {
    const val ACTION_UI_REFRESH = "app.family.igguard.ACTION_UI_REFRESH"
    const val INSTAGRAM_PKG = "com.instagram.android"
    private const val PREFS_NAME = "ig_guard_prefs"

    private const val NOTIF_BURST_DEDUPE_MS = 3_000L
    private const val SCREEN_DEDUPE_MS = 45_000L
    private val recentAlerts = LinkedHashMap<String, Long>()
    private val seenNotifMsgTimestamps = LinkedHashSet<String>()
    // State transactions only. Never hold this lock during network I/O.
    private val pollLock = Any()
    private val deliveryInFlight = AtomicBoolean(false)
    /** A request to deliver, made right after queueing a record, that found the worker busy: the worker runs it once when it
     *  ends. Without it, a record queued for a new family while an older worker was ending (a pairing, rotation or reset)
     *  waited for a later trigger. */
    private val deliveryAskedMeanwhile = java.util.concurrent.atomic.AtomicReference<Context?>(null)
    /** Tests only: runs on a delivery worker as it ends, before it gives the delivery slot back. */
    @Volatile internal var beforeDeliveryWorkerEndsForTest: (() -> Unit)? = null
    /** A QR-join password check runs off the main thread; set and cleared on it, so the UI-thread checks refuse meanwhile and every attempt stays in order. */
    internal val passwordCheckRunning = AtomicBoolean(false)
    private data class DeliveryCallback(val origin:Triple<String,String,Int>,val complete:(Boolean,String)->Unit)
    private val deliveryCallbacks = java.util.concurrent.ConcurrentHashMap<Pair<String,String>, DeliveryCallback>()
    private var relayGeneration = 0

    fun captureRelaySession(ctx: Context): Triple<String, String, Int> = synchronized(pollLock) {
        Triple(getRole(ctx), getOrCreateFamilyToken(ctx), relayGeneration)
    }

    fun collectionAllowed(ctx: Context): Boolean = AppConsent.hasAccepted(ctx) && !prefs(ctx).getBoolean("collection_withdrawn", false)
    fun monitoringNoticeAllowed(ctx: Context): Boolean {
        val manager = ctx.getSystemService(NotificationManager::class.java) ?: return false
        if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) return false
        return manager.areNotificationsEnabled() && manager.getNotificationChannel("child_disclosure")?.importance != NotificationManager.IMPORTANCE_NONE
    }
    fun capturePausedByNotice(ctx: Context): Boolean = collectionAllowed(ctx) && !RiskLocal.uploadOnly(ctx) && !monitoringNoticeAllowed(ctx)
    fun transferAllowed(ctx: Context): Boolean = AppConsent.hasAccepted(ctx) && (getRole(ctx) != "child" || collectionAllowed(ctx))
    /**
     * The child may always stop collection. With [noticeWaitMs] > 0 (UI path, background thread) one content-free
     * "stopped by the child" notice is queued first and given that long to reach the relay, because afterwards nothing is sent.
     */
    fun withdrawCollection(ctx: Context, discardPending: Boolean = true, noticeWaitMs: Long = 0L) {
        // The stop must always take effect: nothing about the notice may prevent or crash it.
        try {
            if (noticeWaitMs > 0L && isChildMode(ctx) && transferAllowed(ctx) && withdrawNoticeDue(ctx))
                noticeToParent(ctx, "Capture Control", "子女已在此手機停止擷取與傳送。之後不會再有新的提醒送到家長手機。", noticeWaitMs)
        } catch (_: Exception) { }
        withdrawNow(ctx, discardPending)
    }
    /** At most one "collection stopped" notice per hour (monotonic), so resume then stop again cannot spam the parent. */
    private fun withdrawNoticeDue(ctx: Context): Boolean {
        val p = prefs(ctx); val now = monotonicClock(); val boot = bootMarker(ctx); val last = p.getLong("withdraw_notice_rt", 0L)
        if (last != 0L && p.getString("withdraw_notice_boot", "") == boot && now >= last && now - last < 60 * 60_000L) return false
        p.edit().putLong("withdraw_notice_rt", now).putString("withdraw_notice_boot", boot).apply()
        return true
    }
    /** Test hook: simulates a failure while applying the stop. */
    @Volatile internal var withdrawFault: (() -> Unit)? = null
    private fun withdrawNow(ctx: Context, discardPending: Boolean) = synchronized(pollLock) {
        withdrawFault?.invoke()
        check(isChildMode(ctx))
        check(prefs(ctx).edit().putBoolean("collection_withdrawn", true).remove("capture_local_ack_id").commit())
        relayGeneration++; CloudRelay.disconnect()
        if(discardPending) discardPendingKeepingScores(ctx)
        RiskLocal.wipeData(ctx); try{RiskScoringSchedule.sync(ctx)}catch(_:RuntimeException){}
        refreshMonitoringDisclosure(ctx)
    }
    /** Everything queued goes except score reports, which hold no message text and stay unless the family ends. */
    private fun discardPendingKeepingScores(ctx: Context) {
        val scope = getChannelTopic(ctx)
        FamilyEvidence(ctx).use { it.discardPendingExcept(scope, RiskReporter.queuedEventIds(ctx, scope)) }
    }
    /** Resuming is an explicit fresh start: held old-scope records are not silently resent. */
    fun resumeCollection(ctx: Context) = synchronized(pollLock) {
        check(isChildMode(ctx))
        discardPendingKeepingScores(ctx)
        check(prefs(ctx).edit().putBoolean("collection_withdrawn", false).remove("capture_local_ack_id").commit())
        relayGeneration++; CloudRelay.disconnect(); refreshMonitoringDisclosure(ctx)
    }
    fun setCaptureOptions(ctx: Context, allowOutgoing: Boolean, allowTrustedContext: Boolean) = synchronized(pollLock) {
        check(isChildMode(ctx))
        RiskLocal.discardTextPending(ctx)
        val expands = allowOutgoing && !outgoingCaptureAllowed(ctx) || allowTrustedContext && !trustedContextAllowed(ctx)
        val editor=prefs(ctx).edit().putBoolean("capture_outgoing", allowOutgoing).putBoolean("capture_trusted_context", allowTrustedContext)
        if(expands)editor.remove("capture_local_ack_id")
        check(editor.commit())
        relayGeneration++; CloudRelay.disconnect(); refreshMonitoringDisclosure(ctx)
    }
    fun outgoingCaptureAllowed(ctx: Context) = prefs(ctx).getBoolean("capture_outgoing", true)
    fun trustedContextAllowed(ctx: Context) = prefs(ctx).getBoolean("capture_trusted_context", true)
    fun captureObservationAllowed(ctx: Context, observation: CapturedText, trusted: Boolean, flagged: Boolean): Boolean {
        if(!collectionAllowed(ctx) || observation.source == "comment_story_draft") return false
        if(observation.direction == "outgoing" && !outgoingCaptureAllowed(ctx)) return false
        if(trusted && !trustedContextAllowed(ctx) && !ConversationCapture.actionable(ConversationCapture.record(observation,System.currentTimeMillis(),trusted,flagged))) return false
        return true
    }

    // Live Checkpoint & Heartbeat States
    @Volatile var cpNotifConnected: Boolean = false
    @Volatile var cpLastNotifTime: String = "Never"
    @Volatile var cpAccConnected: Boolean = false
    @Volatile var cpLastAccTime: String = "Never"
    @Volatile var cpLastCaptureStatus: String = "尚未讀取 Instagram 對話畫面"
    @Volatile var cpLastHttpStatus: String = "已就緒，連線已加密"
    @Volatile var cpLastPollStatus: String = "尚未同步"
    @Volatile var cpParentStreamState: String = "閒置"
    /** Child: the relay answered 401 to this phone's own key proof (another key is registered for the room). Nothing is wiped. */
    @Volatile var cpChildKeyRefused: Boolean = false

    // QR pairing state (design m2 rev. 3). Child: the alias of its signing key K once a QR pairing completed; its records are then
    // signed. Parent: K pinned (canonical text), when the accept happened (until the child's first verified record), a failed
    // pairing marker, the replay state of the child's control records, the last tamper notice time and a count of quiet drops.
    private const val CHILD_KEY_ALIAS = "child_signing_alias"
    // Sticky facts (never cleared by unpinning or by the UI marks below). Parent: this family's token came from a QR pairing, or the
    // family was created by this version, which pairs only by QR: no unsigned 5.2 child record is ever taken on it. Child: this
    // pairing came from a QR: no record ever leaves unsigned.
    private const val FAMILY_QR_ONLY = "family_qr_only"
    internal const val LEGACY_PAIRING_END_MS = 1_806_508_800_000L
    private const val CHILD_QR_PAIRED = "child_qr_paired"
    private const val PINNED_CHILD_KEY = "pinned_child_key"
    private const val PAIRING_PENDING_MS = "child_pairing_pending_ms"
    private const val RELAY_CLOCK_OFFSET = "relay_clock_offset_ms"
    private const val PAIRING_FAILED = "child_pairing_failed"
    private const val CHILD_RECORD_STATE = "child_record_state_json"
    private const val TAMPER_NOTICE_MS = "child_tamper_notice_ms"
    private const val QUIET_DROPS = "child_quiet_drops"
    // Parent: the event id of a signed remote reset queued for a pinned child, and the mark set once the relay holds it.
    private const val REMOTE_RESET_EVENT = "child_remote_reset_event"
    private const val CHILD_REMOTE_RESET = "child_remote_reset"
    private const val RELEASED_FAMILY_TOKENS = "released_family_tokens"
    /** Parent: the last rollback was the parent's own cancel, not the 10-minute expiry (PAIRING_FAILED is set either way). */
    private const val PAIRING_CANCELLED = "child_pairing_cancelled"
    private val parentChildKeys = listOf(PINNED_CHILD_KEY, PAIRING_PENDING_MS, PAIRING_FAILED, CHILD_RECORD_STATE, TAMPER_NOTICE_MS, QUIET_DROPS, REMOTE_RESET_EVENT, CHILD_REMOTE_RESET, PAIRING_CANCELLED, RELEASED_FAMILY_TOKENS) + DeliveryHealth.PARENT_KEYS

    internal val EMOJI_PALETTE = arrayOf(
        "🐼", "🦁", "🐬", "🦊", "🐨", "🐯", "🦄", "🐝",
        "🚀", "🌈", "🍀", "🌻", "🍎", "🍉", "⚽", "🎸"
    )

    private val VALID_IG_HANDLE_REGEX = Regex("^[a-z0-9._]{2,30}$")

    // shortcut: regex keyword matching for sexual/grooming DMs, upgrade to on-device classifier if false positives arise
    val SEXUAL_PATTERN = Regex(
        "\\b(nudes?|naked|porn|sex|horny|boobs?|tits|dick|cock|pussy|blowjob|handjob|onlyfans|hook ?up|fwb|send (me )?(a )?pic|take it off|secret between us|don'?t tell (your )?(mum|mom|dad|parents)|ptgf|sugar ?daddy|sugar ?baby)\\b|" +
            "(裸照|露點|色情|做愛|扑嘢|約炮|援交|包養|兼職女友|自慰|手淫|私密照|閃卡|J圖|咸相|鹹相|裸聊|電愛|唔好同(屋企人|阿媽|阿爸|父母)講|拍片比我睇|除衫)",
        RegexOption.IGNORE_CASE
    )

    private val IGNORED_NOTIFICATIONS = Regex(
        "^(Instagram|Checking for new messages|正在檢查新訊息|Uploading|傳送中|\\d+ new messages|\\d+ 則新訊息).*$",
        RegexOption.IGNORE_CASE
    )

    // Filters out Instagram DM / Story / Comment chrome labels, timestamps, and status indicators
    val NON_MESSAGE_UI_PATTERN = Regex(
        "^(" +
            "message\\.{0,3}|發送訊息\\.{0,3}|傳送訊息\\.{0,3}|send message|傳送訊息|發送訊息|write a message\\.{0,3}|" +
            "add a comment\\.{0,3}|新增留言\\.{0,3}|reply\\.{0,3}|回覆\\.{0,3}|" +
            "comments|留言|replies|查看回覆|view all .* comments|查看全部.*則留言|" +
            "recently seen(\\s+.*)?|seen(\\s+.*)?|active(\\s+.*)?|online|typing\\.{0,3}|" +
            "已讀(\\s+.*)?|最近上線(\\s+.*)?|目前在線上|幾分鐘前上線|小時前上線|輸入中\\.{0,3}|" +
            "sent(\\s+.*)?|delivered(\\s+.*)?|已傳送|已送達|" +
            "tap and hold to react|長按即可傳送心情|double tap to like|連按兩下即可讚好|" +
            "accept|delete|block|report|接受|刪除|封鎖|檢舉|" +
            "follow|following|追蹤|追蹤中|requested|已要求|" +
            "(today|yesterday|今天|昨天|今日|昨日)(\\s+\\d{1,2}:\\d{2}(\\s?[ap]m)?)?|" +
            "(mon|tue|wed|thu|fri|sat|sun|星期[一二三四五六日天]|週[一二三四五六日天])(\\s+\\d{1,2}:\\d{2}(\\s?[ap]m)?)?|" +
            "\\d{1,2}:\\d{2}(\\s?[ap]m)?|\\d+\\s?(s|m|h|d|w|秒|分鐘|小時|天|日|週|周)(前| ago)?" +
            ")$",
        RegexOption.IGNORE_CASE
    )

    val RESERVED_UI_HEADERS = setOf(
        "instagram", "messages", "訊息", "requests", "陌生訊息", "comments", "留言",
        "story", "限時動態", "reels", "連續短片", "posts", "貼文", "online", "目前在線上",
        "home", "首頁", "search", "搜尋", "explore", "探索", "profile", "個人檔案",
        "edit profile", "編輯個人檔案", "share profile", "分享個人檔案", "you", "你", "本人"
    )

    @Volatile
    var pauseTamperUntilMs: Long = 0L

    // Clock sources are replaceable only so tests can simulate reboots and wall-clock changes.
    @Volatile internal var wallClock: () -> Long = { System.currentTimeMillis() }
    @Volatile internal var monotonicClock: () -> Long = { SystemClock.elapsedRealtime() }
    @Volatile internal var bootMarker: (Context) -> String = { c ->
        try { Settings.Global.getInt(c.contentResolver, "boot_count", -1).toString() } catch (_: Exception) { "-1" }
    }

    private fun prefs(ctx: Context) = LocalVault.preferences(ctx)

    fun nowTimeStr(): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
    fun nowDateTimeStr(): String = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())

    fun constantTimeEquals(a: String, b: String): Boolean {
        val bytesA = a.toByteArray(Charsets.UTF_8)
        val bytesB = b.toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(bytesA, bytesB)
    }

    // Theme Mode: true = Warm Bright Mode ("Morning Harbor"), false = Warm Dark Mode ("Cozy Lantern")
    /** The user's own choice once made (kept for ever); before that, the phone's dark mode: Clear Sky if dark, Morning sky otherwise. */
    fun isBrightTheme(ctx: Context): Boolean = prefs(ctx).let { if (it.contains("bright_theme")) it.getBoolean("bright_theme", true) else !phoneIsDark(ctx) }
    fun phoneIsDark(ctx: Context): Boolean = ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
    fun setBrightTheme(ctx: Context, bright: Boolean) = prefs(ctx).edit().putBoolean("bright_theme", bright).apply()

    // ================= 1. END-TO-END ENCRYPTION (AES-256-GCM over HTTPS) =================
    private fun deriveAesKey(token: String): SecretKeySpec {
        val clean = token.trim().lowercase(Locale.US)
        val keyBytes = MessageDigest.getInstance("SHA-256")
            .digest("ig_guard_e2ee_aes256_v4.2:$clean".toByteArray(Charsets.UTF_8))
        return SecretKeySpec(keyBytes, "AES")
    }

    fun encryptPayload(token: String, plainJson: String): String {
        val iv = ByteArray(12)
        SecureRandom().nextBytes(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveAesKey(token), GCMParameterSpec(128, iv))
        val cipherBytes = cipher.doFinal(plainJson.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + cipherBytes.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(cipherBytes, 0, combined, iv.size, cipherBytes.size)
        val b64 = Base64.encodeToString(combined, Base64.NO_WRAP)
        return JSONObject().put("e2ee", b64).toString()
    }

    private val V2_MAGIC = "IGE2".toByteArray(Charsets.US_ASCII)
    private val V2_ID = Regex("[A-Za-z0-9:_-]{1,200}")
    private fun v2Key(token: String, senderRole: String) = SecretKeySpec(PairingCrypto.hkdfSha256(deriveAesKey(token).encoded, ByteArray(0),
        "igguard-e2ee-v2\n$senderRole".toByteArray(Charsets.UTF_8), 32), "AES")
    private fun v2Aad(room: String, senderRole: String, recordId: String) = "igguard-e2ee-v2\n$room\n$senderRole\n$recordId".toByteArray(Charsets.UTF_8)
    internal fun encryptPayloadV2(token: String, plainJson: String, room: String, senderRole: String, recordId: String): String {
        require(senderRole in setOf("parent", "child") && V2_ID.matches(recordId))
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, v2Key(token, senderRole), GCMParameterSpec(128, iv))
        cipher.updateAAD(v2Aad(room, senderRole, recordId))
        val id = recordId.toByteArray(Charsets.US_ASCII)
        return JSONObject().put("e2ee", Base64.encodeToString(V2_MAGIC + byteArrayOf(id.size.toByte()) + id + iv + cipher.doFinal(plainJson.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)).toString()
    }
    private fun decryptV2(token: String, combined: ByteArray, receiverRole: String, room: String): JSONObject? = try {
        val length = combined[4].toInt() and 255
        val recordId = String(combined.copyOfRange(5, 5 + length), Charsets.US_ASCII)
        val sender = if (receiverRole == "parent") "child" else "parent"
        require(V2_ID.matches(recordId) && combined.size >= 5 + length + 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, v2Key(token, sender), GCMParameterSpec(128, combined.copyOfRange(5 + length, 17 + length)))
        cipher.updateAAD(v2Aad(room, sender, recordId))
        JSONObject(String(cipher.doFinal(combined.copyOfRange(17 + length, combined.size)), Charsets.UTF_8))
            .takeIf { it.optString("event_id") == recordId.substringBeforeLast(':') }
    } catch (_: Exception) { null }

    fun decryptPayload(token: String, wireStr: String, receiverRole: String? = null, room: String? = null): JSONObject? = try {
        val combined = Base64.decode(JSONObject(wireStr).getString("e2ee"), Base64.NO_WRAP)
        val v2 = if (receiverRole != null && room != null && combined.size > 5 && combined.copyOfRange(0, 4).contentEquals(V2_MAGIC)) decryptV2(token, combined, receiverRole, room) else null
        if (v2 != null) v2
        else if (combined.size < 28) null
        else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, deriveAesKey(token), GCMParameterSpec(128, combined.copyOfRange(0, 12)))
            JSONObject(String(cipher.doFinal(combined.copyOfRange(12, combined.size)), Charsets.UTF_8))
        }
    } catch (_: Exception) { null }

    // ================= 2. RANDOM 30-95 MIN HEARTBEAT RELAY & WATCHDOG =================
    private const val LONGEST_HEARTBEAT_DELAY_MS = 95 * 60_000L
    fun scheduleNextRandomHeartbeat(ctx: Context): Long {
        // The parent warns after 100 min of silence. Without a connected notification listener (its 1-minute loop) only the 15-minute job
        // sends, and Android may run it up to about 30 min apart: 65 + 30 stays inside 100, where 95 + 30 raised a false "child offline" alert (bug-review B-5).
        val onlyTheJobSends = !(isNotificationListenerEnabledStatic(ctx) && cpNotifConnected)
        val randomDelayMs = Random.Default.nextLong(30 * 60_000L, (if (onlyTheJobSends) 65 * 60_000L else LONGEST_HEARTBEAT_DELAY_MS) + 1L)
        val nextAt = System.currentTimeMillis() + randomDelayMs
        prefs(ctx).edit()
            .putLong("next_heartbeat_at_ms", nextAt)
            .putLong("last_heartbeat_interval_min", randomDelayMs / 60_000L)
            .apply()
        return nextAt
    }

    fun checkAndSendChildHeartbeatIfDue(ctx: Context, force: Boolean = false) {
        if (!isChildMode(ctx)) return
        noteClockIntegrity(ctx)
        val now = System.currentTimeMillis()
        var nextAt = prefs(ctx).getLong("next_heartbeat_at_ms", 0L)
        if (nextAt == 0L) {
            nextAt = scheduleNextRandomHeartbeat(ctx)
            if (!force) return
        }
        if (!force && now < nextAt && nextAt - now <= LONGEST_HEARTBEAT_DELAY_MS) return

        val nextScheduled = scheduleNextRandomHeartbeat(ctx)
        val nextMins = maxOf(30L, (nextScheduled - now) / 60_000L)
        val notifOn = isNotificationListenerEnabledStatic(ctx)
        val accOn = isAccessibilityEnabledStatic(ctx)

        val statusSummary = "裝置 ${Build.MODEL} Convoy 運作中（通知讀取：${if (notifOn) "已開啟" else "已關閉"} · 畫面保護：${if (accOn) "已開啟" else "選用"}）· 下次回報約 ${nextMins} 分鐘後（時間隨機）" + (if (capturePausedByNotice(ctx)) NOTICE_OFF_MARK else "") + if (RiskLocal.captureHeld(ctx)) CAPTURE_HELD_MARK else ""
        // Under manual upload the teen may switch notification access off, which is then no tampering. A held capture is not that: access is expected to stay on.
        val reason = if (notifOn || RiskLocal.uploadMethodOn(ctx)) "heartbeat_ok" else "tamper_attempt"

        // A QR-paired child reports, inside the signed heartbeat, how its records wait for a parent receipt (DeliveryHealth).
        // A 5.2-paired child sends the 5.2 heartbeat unchanged.
        val eventId = UUID.randomUUID().toString()
        val health = if (childQrPaired(ctx)) deliveryHealthFields(ctx, eventId, now) else null
        sendAlertFromChild(
            ctx,
            reason = reason,
            sender = "子女手機定時回報",
            text = statusSummary,
            bypassDedupe = true,
            extra = health,
            eventId = eventId
        )
        RiskLocal.dailyReport(ctx)
    }

    /** Child: the heartbeat [heartbeatId]'s three delivery fields. Heartbeats are not counted: their ids are remembered (only while
     *  still queued) so the count and FamilyEvidence.pruneExpired can leave them out. */
    internal fun deliveryHealthFields(ctx: Context, heartbeatId: String, now: Long): JSONObject = synchronized(pollLock) {
        val scope = getChannelTopic(ctx)
        FamilyEvidence(ctx).use { db ->
            val known = prefs(ctx).getStringSet(DeliveryHealth.HEARTBEAT_IDS, emptySet()).orEmpty()
            val stillQueued = known.filter { db.hasQueuedEvent(scope, it) }.toSet()
            check(prefs(ctx).edit().putStringSet(DeliveryHealth.HEARTBEAT_IDS, stillQueued + heartbeatId).commit())
            val (waiting, oldest) = db.receiptWaiting(scope, stillQueued + heartbeatId)
            val since = listOfNotNull(oldest, RiskReporter.unconfirmedSince(ctx, scope, db)).minOrNull()
            DeliveryHealth.fields(waiting, since, prefs(ctx).getLong(DeliveryHealth.EXPIRED_AT_MS, 0L) > 0L, now)
        }
    }
    /** Child, on a parent receipt for [eventId]: a counted record queued after the last unreceipted expiry clears the sticky bit. */
    private fun noteReceiptForDeliveryHealth(ctx: Context, scope: String, eventId: String) {
        val expiredAt = prefs(ctx).getLong(DeliveryHealth.EXPIRED_AT_MS, 0L)
        if (expiredAt <= 0L || eventId in prefs(ctx).getStringSet(DeliveryHealth.HEARTBEAT_IDS, emptySet()).orEmpty()) return
        val queuedAt = FamilyEvidence(ctx).use { it.enqueuedAt(scope, eventId) } ?: return
        if (queuedAt > expiredAt) check(prefs(ctx).edit().remove(DeliveryHealth.EXPIRED_AT_MS).commit())
    }

    fun recordChildHeartbeatOnParent(ctx: Context, summary: String, sentAt: Long) {
        val now = System.currentTimeMillis()
        // A child clock ahead still counts, clamped to now below; only more than 24 h ahead is refused (as readChildRecord does).
        if (sentAt <= 0 || sentAt > now + RiskLimits.REPORT_FUTURE_MS) return
        val timestamp = minOf(sentAt, now)
        if (timestamp <= prefs(ctx).getLong("parent_last_child_hb_ms", 0L)) return
        prefs(ctx).edit().putLong("parent_last_child_hb_ms", timestamp)
            .putString("parent_last_child_hb_text", "${nowDateTimeStr()} — $summary")
            .apply { if (now - timestamp <= 100 * 60_000L) putBoolean("parent_hb_missing_alerted", false) }.apply()
    }

    /** Ends the child's heartbeat summary while its capture is held (content-free). An older parent just shows it as text. */
    const val NOTICE_OFF_MARK = " · 監察提示通知已關閉，擷取已暫停"
    const val NOTICE_OFF_STATUS = "擷取已暫停：未允許通知 · Capture paused: notifications not allowed"
    const val CAPTURE_HELD_MARK = " | 擷取已暫停 (capture held)"

    fun getLastChildHeartbeatInfo(ctx: Context): String {
        val txt = prefs(ctx).getString("parent_last_child_hb_text", null)
        return txt?.removeSuffix(CAPTURE_HELD_MARK) ?: "等待子女手機首次回報（每 30–95 分鐘自動回報一次，時間隨機）"
    }

    /** The child's latest report carried the exact marker at its end; anywhere else it counts for nothing. */
    fun isChildCaptureHeld(ctx: Context): Boolean = prefs(ctx).getString("parent_last_child_hb_text", null)?.endsWith(CAPTURE_HELD_MARK) == true

    fun hasChildHeartbeat(ctx: Context): Boolean = prefs(ctx).getLong("parent_last_child_hb_ms", 0L) > 0L

    fun isChildHeartbeatOverdue(ctx: Context): Boolean {
        val lastMs = prefs(ctx).getLong("parent_last_child_hb_ms", 0L)
        if (lastMs == 0L) return false
        return (System.currentTimeMillis() - lastMs) > 100 * 60_000L
    }

    fun shouldFireHeartbeatMissingAlert(ctx: Context): Boolean {
        if (!isChildHeartbeatOverdue(ctx)) return false
        val already = prefs(ctx).getBoolean("parent_hb_missing_alerted", false)
        if (already) return false
        prefs(ctx).edit().putBoolean("parent_hb_missing_alerted", true).apply()
        return true
    }

    // ================= 3. STRONG PASSWORD + BRUTE-FORCE LOCKOUT + 15-MIN UNLOCK =================
    fun getPin(ctx: Context): String {
        if (isChildMode(ctx)) return prefs(ctx).getString("parent_password_verifier", "") ?: ""
        val existing = prefs(ctx).getString("parent_master_password", null)
        if (!existing.isNullOrBlank()) return existing
        val generated = generateRandomStrongPassword()
        prefs(ctx).edit().putString("parent_master_password", generated).apply()
        return generated
    }

    fun setPin(ctx: Context, password: String) {
        val clean = password.trim()
        require(isStrongPasswordValid(clean)) { "A strong parent password is required" }
        if (isChildMode(ctx)) storeChildVerifier(ctx, FamilySecurity.passwordVerifier(clean))
        else prefs(ctx).edit().putString("parent_master_password", clean)
            .putString("parent_share_verifier", FamilySecurity.passwordVerifier(clean)).remove("parent_password_verifier").apply()
    }

    private fun storeChildVerifier(ctx: Context, verifier: String) {
        require(FamilySecurity.isVerifier(verifier))
        prefs(ctx).edit().putString("parent_password_verifier", verifier).remove("parent_master_password").remove("parent_share_verifier").apply()
    }

    fun verifyParentPassword(ctx: Context, candidate: String): Boolean {
        val verifier = prefs(ctx).getString("parent_password_verifier", null)
        return if (verifier != null) FamilySecurity.verifiesPassword(candidate.trim(), verifier)
        else constantTimeEquals(candidate.trim(), prefs(ctx).getString("parent_master_password", "") ?: "") && candidate.isNotBlank()
    }

    internal fun parentPasswordVerifier(ctx: Context): String = synchronized(pollLock) {
        prefs(ctx).getString("parent_share_verifier", null)?.takeIf { FamilySecurity.isVerifier(it) }
            ?: FamilySecurity.passwordVerifier(getPin(ctx)).also { prefs(ctx).edit().putString("parent_share_verifier", it).apply() }
    }

    private class IdentityCache(val identity: FamilySecurity.SigningIdentity, val publicPref: String?, val legacyPref: String?, val legacyFallback: Boolean)
    @Volatile private var identityCache: IdentityCache? = null
    /** Count of preference commits made while resolving the identity (tests assert repeated calls do not write). */
    @Volatile internal var identityWrites = 0
    /** Test hook: behave like a freshly started process, which retries a failed Keystore import. */
    internal fun forgetParentIdentityCache() { identityCache = null }
    /** True while the parent still signs with its verified pre-update key because the Keystore import failed. */
    fun parentKeyMigrationPending(ctx: Context): Boolean {
        if (!isParentMode(ctx)) return false
        try { parentIdentity(ctx) } catch (_: Exception) { return false }
        return identityCache?.legacyFallback == true
    }
    /**
     * The identity is resolved once per process (and again whenever the stored key preferences change), so relay frames and
     * UI renders do not hit the Keystore or preferences each time. A failed Keystore import never blocks reception: the verified
     * legacy key keeps working and the import is retried at the next start. The legacy key is removed only after the Keystore key
     * has been proven to match the public key the child trusts.
     */
    private fun parentIdentity(ctx: Context): FamilySecurity.SigningIdentity {
        check(isParentMode(ctx))
        recoverFreshFamily(ctx)
        val p = prefs(ctx)
        val pub = p.getString("parent_signing_public", null); val legacy = p.getString("parent_signing_private", null)
        identityCache?.let { if (it.publicPref == pub && it.legacyPref == legacy) return it.identity }
        return synchronized(pollLock) {
            try {
                val identity = FamilySecurity.loadOrCreateParentIdentity(ctx, legacy, pub)
                if (pub != identity.publicKey || legacy != null) {
                    // Remove legacy exportable material only after the imported key and pinned trust verify.
                    check(p.edit().putString("parent_signing_public", identity.publicKey).remove("parent_signing_private").commit())
                    identityWrites++
                }
                identityCache = IdentityCache(identity, identity.publicKey, null, false)
                identity
            } catch (failure: Exception) {
                val oldKey = legacy?.takeIf { it.isNotEmpty() }; val trusted = pub?.takeIf { it.isNotEmpty() }
                if (oldKey == null || trusted == null) throw failure
                val identity = try { FamilySecurity.verifiedLegacyIdentity(oldKey, trusted) } catch (_: Exception) { throw failure }
                addCheckpointLog(ctx, "CP0-KEY", "Keystore 匯入失敗；繼續使用已核對的原有金鑰，下次啟動再試")
                identityCache = IdentityCache(identity, pub, legacy, true)
                identity
            }
        }
    }

    fun relayPublicKey(ctx:Context):String = synchronized(pollLock) {
        val key=if(isParentMode(ctx))parentIdentity(ctx).publicKey else prefs(ctx).getString("parent_command_public_key","").orEmpty()
        require(FamilySecurity.validPublicKey(key)){"Pairing needs a pinned parent key"};key
    }
    /** v2 proofs only for the current family room of a QR pairing (parent: carrying the pinned K; child: signed by K). Every other
     *  room (5.2 pairings, the room left by a rotation, a rendezvous room) keeps v1; a v1 parent never removes the relay's child lock. */
    fun relayAuthorization(ctx:Context,token:String):String = synchronized(pollLock) {
        loadRelayClock(ctx)
        val scope=getChannelTopicForToken(token)
        val family=token==getOrCreateFamilyToken(ctx)
        if(isParentMode(ctx)) {
            val pinned=pinnedChildKey(ctx)
            if(family && pinned!=null)FamilyRelay.parentAuthorizationV2(parentIdentity(ctx),scope,token,pinned)
            else FamilyRelay.parentAuthorization(parentIdentity(ctx),scope,token)
        } else {
            check(isChildMode(ctx))
            val alias=childKeyAlias(ctx)
            if(family && alias!=null)FamilyRelay.childAuthorizationV2(relayPublicKey(ctx),scope,token,FamilySecurity.loadChildSigningKey(ctx,alias))
            else FamilyRelay.childAuthorization(relayPublicKey(ctx),scope,token)
        }
    }
    fun loadRelayClock(ctx:Context) { FamilyRelay.clockOffsetMs=prefs(ctx).getLong(RELAY_CLOCK_OFFSET,0L) }
    fun noteRelayClock(ctx:Context,serverMs:Long,now:Long=System.currentTimeMillis()):Boolean {
        if(kotlin.math.abs(serverMs-now-FamilyRelay.clockOffsetMs)<=120_000L)return false
        FamilyRelay.clockOffsetMs=serverMs-now
        prefs(ctx).edit().putLong(RELAY_CLOCK_OFFSET,serverMs-now).apply()
        addCheckpointLog(ctx,"CP4-RELAY","手機時間與中繼相差超過 2 分鐘；已按中繼時間校正")
        return true
    }
    /** Child: K's alias after a completed QR pairing; null for a 5.2 pairing (records keep the unsigned 5.2 format). */
    fun childKeyAlias(ctx:Context):String? = prefs(ctx).getString(CHILD_KEY_ALIAS,null)?.takeIf{isChildMode(ctx)}
    /** Parent: the child key pinned by a QR pairing; null for a 5.2 pairing, shown as not verified. */
    fun pinnedChildKey(ctx:Context):String? = prefs(ctx).getString(PINNED_CHILD_KEY,null)?.takeIf{isParentMode(ctx)}
    /** Parent: no unsigned 5.2 child record is ever taken on this family (sticky; see FAMILY_QR_ONLY). */
    fun familyQrOnly(ctx:Context):Boolean = prefs(ctx).getBoolean(FAMILY_QR_ONLY,false)
    /** Child: this pairing came from a QR, so every record leaves signed with K or not at all (sticky; see CHILD_QR_PAIRED). */
    fun childQrPaired(ctx:Context):Boolean = isChildMode(ctx) && prefs(ctx).getBoolean(CHILD_QR_PAIRED,false)
    /** Parent: accepted on this phone, waiting for the child's first verified record (design 2.5 P7). */
    fun childPairingPending(ctx:Context):Boolean = isParentMode(ctx) && prefs(ctx).getLong(PAIRING_PENDING_MS,0L)>0L
    /** Parent: the last QR pairing was rolled back because the child never confirmed within 10 minutes. */
    fun childPairingFailed(ctx:Context):Boolean = isParentMode(ctx) && prefs(ctx).getBoolean(PAIRING_FAILED,false)
    /** Parent: that rollback was the parent's own "取消並重新配對". */
    fun childPairingCancelled(ctx:Context):Boolean = childPairingFailed(ctx) && prefs(ctx).getBoolean(PAIRING_CANCELLED,false)
    /** Parent: the relay took a signed remote reset for the pinned child; no child is paired until a new QR pairing. */
    fun childRemotelyReset(ctx:Context):Boolean = isParentMode(ctx) && prefs(ctx).getBoolean(CHILD_REMOTE_RESET,false)
    enum class ChildTrust { NO_CHILD, NOT_VERIFIED, PAIRING, PAIRING_FAILED, VERIFIED, REMOTE_RESET }
    /** Parent overview model: NOT_VERIFIED is a child that reports without a pinned key (a 5.2 pairing): a permanent banner. */
    fun childTrust(ctx:Context):ChildTrust = when {
        childPairingPending(ctx) -> ChildTrust.PAIRING
        pinnedChildKey(ctx)!=null -> ChildTrust.VERIFIED
        childRemotelyReset(ctx) -> ChildTrust.REMOTE_RESET
        childPairingFailed(ctx) -> ChildTrust.PAIRING_FAILED
        hasChildHeartbeat(ctx) -> ChildTrust.NOT_VERIFIED
        else -> ChildTrust.NO_CHILD
    }
    /** Parent: signed records refused quietly (valid signature, outside the rules); a local count only. */
    fun childQuietDrops(ctx:Context):Int = prefs(ctx).getInt(QUIET_DROPS,0)
    /** Child after a QR pairing: [recordText] (built exactly as 5.2 does, without the receipt flag) wrapped and signed with K
     *  (design 3.2); null for a 5.2 pairing, which keeps the unsigned format. A key that cannot be used throws: never a fallback. */
    private fun childSigningKey(ctx:Context):FamilySecurity.ChildSigningKey? {
        // A QR pairing never falls back to the unsigned 5.2 format: without a usable K nothing leaves (the caller's error path).
        val alias=childKeyAlias(ctx) ?: if(childQrPaired(ctx))throw SecurityException("Child signing key alias is missing") else return null
        return FamilySecurity.loadChildSigningKey(ctx,alias)
    }
    private fun signedForParent(ctx:Context,recordText:String,id:String,receiptRequested:Boolean?,key:FamilySecurity.ChildSigningKey?=childSigningKey(ctx)):JSONObject? {
        key ?: return null
        return ChildRecords.wrapForParent(recordText,id,prefs(ctx).getString("parent_command_public_key","").orEmpty(),key.publicKey,{FamilySecurity.signChildBytes(it,key.alias)},receiptRequested)
    }
    /** Forgets this phone's child signing key (new pairing, reset, role change, purge). A Keystore entry that cannot be deleted
     *  is left behind unreferenced: nothing signs with it again. */
    private fun forgetChildKey(ctx:Context) {
        val alias=prefs(ctx).getString(CHILD_KEY_ALIAS,null) ?: return
        check(prefs(ctx).edit().remove(CHILD_KEY_ALIAS).commit())
        try{FamilySecurity.deleteChildSigningKey(ctx,alias)}catch(_:Exception){}
    }
    private fun relayRouteTokens(ctx:Context):List<String> = synchronized(pollLock) {
        val current=getOrCreateFamilyToken(ctx)
        val pending=try{JSONObject(prefs(ctx).getString("pending_rotation_json","{}").orEmpty())}catch(_:Exception){JSONObject()}
        val old=pending.optString("old_token")
        val routes=if(isParentMode(ctx) && current==pending.optString("new_token") && FamilySecurity.validToken(old))listOf(current,old).distinct() else listOf(current)
        (routes+releasedFamilyTokens(ctx)).distinct()
    }
    private fun releasedFamilyTokens(ctx:Context):List<String> = if(!isParentMode(ctx))emptyList()
        else prefs(ctx).getString(RELEASED_FAMILY_TOKENS,"").orEmpty().split(',').filter{FamilySecurity.validToken(it) && FamilyEvidence(ctx).use{db->db.pendingCount(getChannelTopicForToken(it))>0}}
    fun pairingReplacesChild(ctx:Context):Boolean = childTrust(ctx).let{it==ChildTrust.VERIFIED || it==ChildTrust.NOT_VERIFIED}

    private val COMMON_PASSWORD_PARTS = listOf("password", "passw0rd", "qwerty", "123456", "abc123", "iloveyou", "igguard")
    /**
     * Rule for setting a NEW parent password: 12+ characters with a letter and a digit, at least 6 different characters, and none of
     * the common stems above. The invite carries a PBKDF2 verifier that can be attacked offline, so short or common passwords are
     * refused. Existing passwords are never re-checked: verification ([verifyParentPassword]) does not use this rule.
     */
    fun isStrongPasswordValid(password: String): Boolean {
        val clean = password.trim()
        if (clean.length < 12 || clean.toSet().size < 6) return false
        val lower = clean.lowercase(Locale.ROOT)
        if (COMMON_PASSWORD_PARTS.any { lower.contains(it) }) return false
        return clean.any { it.isLetter() } && clean.any { it.isDigit() }
    }

    fun generateRandomStrongPassword(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$"
        val rnd = SecureRandom()
        return generateSequence {
            buildString {
                append("Hk")
                repeat(10) { append(chars[rnd.nextInt(chars.length)]) }
                append(10 + rnd.nextInt(90))
            }
        }.first { isStrongPasswordValid(it) }
    }

    private const val LOCKOUT_MS = 10 * 60_000L
    private const val UNLOCK_CODE_MS = 15 * 60_000L
    private const val CLOCK_BACK_TOLERANCE_MS = 120_000L
    private const val CLOCK_ALERT_WINDOW_MS = 60 * 60_000L

    /** Remaining wrong-password lockout. Counted on the monotonic clock, so wall-clock changes cannot skip it;
     * a reboot restarts the whole lockout instead of ending it (elapsedRealtime cannot be compared across boots). */
    private fun lockoutRemainingMs(ctx: Context): Long {
        val p = prefs(ctx); val now = monotonicClock(); val boot = bootMarker(ctx)
        var until = p.getLong("pw_lockout_rt", 0L)
        if (until == 0L) {
            // Lockout begun by an older version used the wall clock: adopt it once, capped to the normal length.
            val legacy = p.getLong("pw_lockout_until_ms", 0L); val left = legacy - wallClock()
            if (left <= 0L) { if (legacy != 0L) p.edit().remove("pw_lockout_until_ms").apply(); return 0L }
            val length = minOf(left, LOCKOUT_MS)
            p.edit().putLong("pw_lockout_rt", now + length).putLong("pw_lockout_start_rt", now).putString("pw_lockout_boot", boot).apply()
            return length
        }
        if (p.getString("pw_lockout_boot", "") != boot || now < p.getLong("pw_lockout_start_rt", 0L)) {
            until = now + LOCKOUT_MS
            p.edit().putLong("pw_lockout_rt", until).putLong("pw_lockout_start_rt", now).putString("pw_lockout_boot", boot).apply()
        }
        val left = until - now
        if (left <= 0L) { clearLockout(ctx); return 0L }
        return left
    }
    private fun clearLockout(ctx: Context) {
        prefs(ctx).edit().remove("pw_lockout_rt").remove("pw_lockout_start_rt").remove("pw_lockout_boot").remove("pw_lockout_until_ms").apply()
    }
    fun checkPasswordLockoutRemainingSec(ctx: Context): Long = (lockoutRemainingMs(ctx) + 999L) / 1000L

    // UI thread and applyPairing's thread both count: pollLock, not @Synchronized (the 5th failure's alert takes pollLock; code elsewhere holds pollLock then the monitor).
    fun recordPasswordAttempt(ctx: Context, success: Boolean): Unit = synchronized(pollLock) {
        if (success) {
            prefs(ctx).edit().putInt("pw_failed_count", 0).apply()
            clearLockout(ctx)
            return@synchronized
        }
        val count = prefs(ctx).getInt("pw_failed_count", 0) + 1
        if (count >= 5) {
            prefs(ctx).edit().putInt("pw_failed_count", 0).remove("pw_lockout_until_ms")
                .putLong("pw_lockout_rt", monotonicClock() + LOCKOUT_MS).putLong("pw_lockout_start_rt", monotonicClock())
                .putString("pw_lockout_boot", bootMarker(ctx)).apply()
            sendAlertFromChild(
                ctx,
                "tamper_attempt",
                "Brute-Force Protection",
                "子女手機連續 5 次輸入錯誤解鎖碼/密碼，已鎖定密碼輸入 10 分鐘！",
                bypassDedupe = true
            )
        } else {
            prefs(ctx).edit().putInt("pw_failed_count", count).apply()
        }
    }

    private fun clearTemporaryUnlock(ctx: Context) {
        prefs(ctx).edit().remove("unlock_until_ms").remove("unlock_until_rt").remove("unlock_start_rt").remove("unlock_boot").apply()
        pauseTamperUntilMs = 0L
    }
    /** An unlock is valid only on the monotonic clock of the boot that granted it (it ends at reboot),
     * and additionally only before its absolute wall-clock expiry. Moving the wall clock back cannot extend it. */
    fun isTemporarilyUnlocked(ctx: Context): Boolean = unlockRemainingMs(ctx) > 0L
    private fun unlockRemainingMs(ctx: Context): Long {
        val p = prefs(ctx)
        val until = p.getLong("unlock_until_rt", 0L)
        if (until == 0L) { pauseTamperUntilMs = 0L; return 0L }
        val now = monotonicClock(); val wallUntil = p.getLong("unlock_until_ms", 0L)
        val valid = p.getString("unlock_boot", "") == bootMarker(ctx) && now >= p.getLong("unlock_start_rt", 0L) && now < until && wallClock() < wallUntil
        if (!valid) { clearTemporaryUnlock(ctx); return 0L }
        pauseTamperUntilMs = wallUntil
        return minOf(until - now, wallUntil - wallClock())
    }

    fun getUnlockRemainingSeconds(ctx: Context): Long = unlockRemainingMs(ctx) / 1000L

    fun grantTemporaryUnlock(ctx: Context, durationMs: Long) {
        if (durationMs <= 0L) { clearTemporaryUnlock(ctx); return }
        val now = monotonicClock(); val wallUntil = wallClock() + durationMs
        pauseTamperUntilMs = wallUntil
        prefs(ctx).edit().putLong("unlock_until_ms", wallUntil).putLong("unlock_until_rt", now + durationMs)
            .putLong("unlock_start_rt", now).putString("unlock_boot", bootMarker(ctx)).apply()
    }

    fun generate15MinUnlockCode(ctx: Context): String {
        check(isParentMode(ctx))
        return FamilySecurity.randomUnlockCode()
    }

    /** Checks a code without using it. Production unlock paths call [redeem15MinUnlockCode]. */
    fun verify15MinUnlockCode(ctx: Context, inputCode: String): Boolean {
        val clean = inputCode.trim().uppercase(Locale.US)
        val p = prefs(ctx)
        val code = p.getString("synced_15m_code", "") ?: ""
        val now = monotonicClock(); val until = p.getLong("synced_15m_rt", 0L)
        return FamilySecurity.validCode(clean) && wallClock() < p.getLong("synced_15m_exp", 0L) &&
            until > 0L && p.getString("synced_15m_boot", "") == bootMarker(ctx) && now >= p.getLong("synced_15m_start_rt", 0L) && now < until &&
            constantTimeEquals(clean, code)
    }
    /** Unlock codes are single-use: a correct code is removed in the same step that accepts it. */
    fun redeem15MinUnlockCode(ctx: Context, inputCode: String): Boolean = synchronized(pollLock) {
        if (!verify15MinUnlockCode(ctx, inputCode)) return@synchronized false
        check(prefs(ctx).edit().remove("synced_15m_code").remove("synced_15m_exp").remove("synced_15m_rt")
            .remove("synced_15m_start_rt").remove("synced_15m_boot").commit())
        true
    }

    fun push15MinUnlockCodeFromParent(ctx: Context, code: String) {
        val now = System.currentTimeMillis()
        val exp = now + 15 * 60 * 1000L
        prefs(ctx).edit()
            .putString("synced_15m_code", code)
            .putLong("synced_15m_exp", exp)
            .apply()
        val token = getOrCreateFamilyToken(ctx)
        val topic = getChannelTopicForToken(token)
        val plain = JSONObject().apply {
            put("type", "unlock_code")
            put("code", code)
            put("exp", exp)
            put("ts_ms", now)
            put("time", nowDateTimeStr())
        }.toString()
        postEncryptedToChannel(ctx, topic, token, plain, null)
    }

    /** Child only: a wall clock that moved back by more than a few minutes (beyond what the monotonic clock explains)
     * is reported to the parent once per hour. The baseline always follows the current clock. */
    private fun noteClockIntegrity(ctx: Context) {
        val p = prefs(ctx); val wall = wallClock(); val now = monotonicClock(); val boot = bootMarker(ctx)
        val lastWall = p.getLong("clock_last_wall", 0L)
        var backBy = 0L
        if (lastWall != 0L) {
            val sameBoot = p.getString("clock_last_boot", "") == boot && now >= p.getLong("clock_last_rt", 0L)
            val expected = lastWall + if (sameBoot) now - p.getLong("clock_last_rt", 0L) else 0L
            if (wall < expected - CLOCK_BACK_TOLERANCE_MS) backBy = expected - wall
        }
        val editor = p.edit().putLong("clock_last_wall", wall).putLong("clock_last_rt", now).putString("clock_last_boot", boot)
        var alert = false
        if (backBy > 0L) {
            val quiet = p.getString("clock_alert_boot", "") == boot && now >= p.getLong("clock_alert_rt", 0L) && now - p.getLong("clock_alert_rt", 0L) < CLOCK_ALERT_WINDOW_MS
            if (!quiet) { editor.putLong("clock_alert_rt", now).putString("clock_alert_boot", boot); alert = true }
        }
        editor.apply()
        if (alert) sendAlertFromChild(ctx, "tamper_attempt", "Clock Guard",
            "子女手機的系統時間被往回調整約 ${backBy / 60_000L} 分鐘。這可能令此手機拒收家長的已簽署指令，請當面查看。", bypassDedupe = true)
    }

    private fun recoverFreshFamily(ctx: Context) {
        val raw=prefs(ctx).getString("fresh_family_recovery_json",null) ?: return
        check(getRole(ctx)=="parent")
        val change=JSONObject(raw);val old=change.getString("old_token");val fresh=change.getString("new_token")
        require(FamilySecurity.validToken(old) && FamilySecurity.validToken(fresh) && old!=fresh)
        val identity=FamilySecurity.SigningIdentity(change.getString("signing_ref"),change.getString("public_key"))
        FamilyEvidence(ctx).use{it.discardPending(getChannelTopicForToken(old))}
        FamilySecurity.activateParentIdentity(ctx,identity)
        val editor=prefs(ctx).edit().putString("family_uuid_token",fresh).putString("parent_signing_public",identity.publicKey).putBoolean(FAMILY_QR_ONLY,true)
        for(key in listOf("parent_signing_private","pending_rotation_json","evidence_transition_json","control_state_json","last_control_ts_ms",
            "capture_policy_id","capture_local_ack_id","capture_child_ack_id","capture_sequence","parent_master_password","parent_share_verifier",
            "parent_password_verifier","parent_command_public_key","trusted_csv","flagged_offenders_csv","child_self_handle","child_self_handle_ts_ms","sent_setup_ok",
            "parent_last_child_hb_ms","parent_last_child_hb_text","parent_hb_missing_alerted","weekly_stats_json","parent_alerts_json",
            "synced_15m_code","synced_15m_exp","unlock_until_ms","synced_15m_rt","synced_15m_start_rt","synced_15m_boot",
            "unlock_until_rt","unlock_start_rt","unlock_boot","pw_lockout_rt","pw_lockout_start_rt","pw_lockout_boot")+parentChildKeys)editor.remove(key)
        check(editor.remove("fresh_family_recovery_json").commit())
        pauseTamperUntilMs=0L;relayGeneration++;CloudRelay.disconnect()
    }
    /** Explicit authenticated UI recovery on clean devices; never a remote key-rotation message. */
    fun createFreshFamilyForRecovery(ctx: Context): String = synchronized(pollLock) {
        check(isParentMode(ctx))
        val old=getOrCreateFamilyToken(ctx)
        prepareParentArchive(ctx)
        val identity=FamilySecurity.stageFreshParentIdentity(ctx)
        val fresh=UUID.randomUUID().toString()
        val change=JSONObject().put("old_token",old).put("new_token",fresh).put("signing_ref",identity.privateKey).put("public_key",identity.publicKey)
        if(!prefs(ctx).edit().putString("fresh_family_recovery_json",change.toString()).commit()){
            prefs(ctx).edit().remove("fresh_family_recovery_json").commit()
            throw SecurityException("Fresh pairing recovery could not be journaled; old family was retained")
        }
        relayGeneration++;CloudRelay.disconnect()
        recoverFreshFamily(ctx)
        fresh
    }

    // ================= 4. REMOTE KEY ROTATION & REMOTE CHILD UNLOCK/RESET =================
    fun rotateKeyAndNotifyChild(ctx: Context, resetChildToUnconfigured: Boolean, purgeFamily: Boolean = false, onComplete: ((Boolean) -> Unit)? = null): String = synchronized(pollLock) {
        val session = captureRelaySession(ctx)
        check(session.first == "parent")
        val pending = try { JSONObject(prefs(ctx).getString("pending_rotation_json", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        val retry = !resetChildToUnconfigured && session.second in listOf(pending.optString("old_token"), pending.optString("new_token"))
        val oldToken = if (retry) pending.getString("old_token") else session.second
        val newToken = if (retry) pending.getString("new_token") else UUID.randomUUID().toString()
        val oldCommand=pending.optJSONObject("command")
        val queued=retry && oldCommand!=null && FamilyEvidence(ctx).use{it.hasQueuedEvent(getChannelTopicForToken(oldToken),"control:"+oldCommand.optString("command_id"))}
        val command = if(queued)JSONObject(oldCommand.toString()) else JSONObject().put("type", if (resetChildToUnconfigured) "remote_reset_child" else "key_rotated")
            .put("new_token", newToken).put("new_pin", parentPasswordVerifier(ctx))
            .put("command_id",UUID.randomUUID().toString()).put("ts_ms", System.currentTimeMillis()).put("time", nowDateTimeStr())
        // Sent just before the parent deletes the relay room: the child must not keep that room's secrets or unsent records.
        if (resetChildToUnconfigured && purgeFamily && !queued) command.put("purge_family", true)
        if (resetChildToUnconfigured) { lastResetEvent = getChannelTopicForToken(oldToken) to "control:" + command.getString("command_id"); lastResetWritten = null }
        // A pinned child deletes K on receipt: once the relay holds this reset the pin is dropped (recordRelayAcceptance).
        if (resetChildToUnconfigured && pinnedChildKey(ctx) != null)
            check(prefs(ctx).edit().putString(REMOTE_RESET_EVENT, "control:" + command.getString("command_id")).commit())
        if (!resetChildToUnconfigured) check(prefs(ctx).edit().putString("pending_rotation_json",
            JSONObject().put("old_token", oldToken).put("new_token", newToken).put("command",command).toString()).commit())
        postEncryptedToChannel(ctx, getChannelTopicForToken(oldToken), oldToken, command.toString(),onComplete={ success, _ ->
            val accepted = synchronized(pollLock) {
                if(resetChildToUnconfigured)success && captureRelaySession(ctx)==session
                else isParentMode(ctx) && getOrCreateFamilyToken(ctx)==newToken
            }
            // Retain a pending transition until a child report arrives on the new channel.
            addCheckpointLog(ctx, "CP3-KEY", if (accepted) "已傳送家長指令，等待子女手機確認" else "傳送失敗或配對已變更；原有家庭保留")
            onComplete?.invoke(accepted)
            ctx.sendBroadcast(Intent(ACTION_UI_REFRESH).setPackage(ctx.packageName))
        })
        newToken
    }

    /** Diagnostics never hold contact identifiers. Earlier versions wrote sender names and @handles into some steps;
     * those lines are rewritten without the name whenever the log is read or extended. */
    private fun scrubCheckpointLine(line: String): String = when {
        " CP4-RECV: " in line -> line.substringBefore(" from ")
        " CP2-TRUSTED: " in line -> line.substringBefore(" CP2-TRUSTED: ") + " CP2-TRUSTED: 已略過信任對象、自己或狀態訊息"
        " CP1-ID: " in line -> line.substringBefore(" CP1-ID: ") + " CP1-ID: 已自動識別子女 IG 帳號"
        " CP4-ID: " in line -> line.substringBefore(" CP4-ID: ") + " CP4-ID: 已從子女手機同步子女 IG 帳號"
        else -> line
    }
    private fun scrubbedCheckpointLines(raw: String): List<String> = if (raw.isEmpty()) emptyList() else raw.split("\n").map { scrubCheckpointLine(it) }

    @Synchronized
    fun addCheckpointLog(ctx: Context, step: String, detail: String) {
        val entry = "[${nowTimeStr()}] $step: $detail"
        val lines = scrubbedCheckpointLines(prefs(ctx).getString("checkpoint_logs", "") ?: "")
        val updated = (listOf(entry) + lines).take(40).joinToString("\n")
        prefs(ctx).edit().putString("checkpoint_logs", updated).apply()
    }

    @Synchronized
    fun getCheckpointLogs(ctx: Context): String {
        val raw = prefs(ctx).getString("checkpoint_logs", null) ?: return "尚未有診斷記錄。"
        val clean = scrubbedCheckpointLines(raw).joinToString("\n")
        if (clean != raw) prefs(ctx).edit().putString("checkpoint_logs", clean).apply()
        return clean
    }

    fun clearCheckpointLogs(ctx: Context) {
        prefs(ctx).edit().putString("checkpoint_logs", "").apply()
        ctx.sendBroadcast(Intent(ACTION_UI_REFRESH).setPackage(ctx.packageName))
    }

    fun getRole(ctx: Context): String = prefs(ctx).getString("role", "unconfigured") ?: "unconfigured"
    /**
     * Explicit, authenticated departure from the parent role: the master password and the signing identity are removed
     * here (and nowhere as a side effect of pairing). Stored family records stay on the phone.
     */
    /**
     * Queues a signed remote_reset_child for the paired child and waits up to [waitMs] (background thread) for the relay to
     * accept it. True means the relay took it; the child itself cannot confirm, because it becomes unconfigured on receipt.
     */
    /** Outbox scope and event id of the most recent signed reset, so a reset reported as not sent can really be withdrawn. */
    @Volatile private var lastResetEvent: Pair<String, String>? = null
    /** Set to [lastResetEvent] once a frame of that reset was handed to the relay's socket: from then on it is never "not sent". */
    @Volatile private var lastResetWritten: Pair<String, String>? = null
    /** SENT_UNCONFIRMED: no answer within the wait; the reset stays queued and may already be with the relay. */
    enum class ReleaseOutcome { SENT, SENT_UNSURE, SENT_UNCONFIRMED, NOT_SENT }
    /** With a sent release (Chinese, English): the outbox and the relay hold it this long for a child that stays offline. */
    fun releaseDelayNotice(): Pair<String, String> {
        val days = FamilySecurity.RELAYED_COMMAND_LIFETIME_MS / 86_400_000L
        return "子女手機會在下次連線時解除，最長可能需要 $days 天；在此之前，子女手機本身的畫面仍會顯示受監察。" to
            "The child phone is released when it next comes online, which can take up to $days days; until then its own screen still shows that it is monitored."
    }
    /** Test hook replacing the relay round trip of [releaseChildBeforeLeaving]. */
    @Volatile internal var releaseOverride: ((Context, Long) -> ReleaseOutcome)? = null
    /** During a pending key rotation the reset is posted to the new token only; a child still on the old token will not get it. */
    internal fun classifyRelease(ctx: Context, accepted: Boolean, written: Boolean = false): ReleaseOutcome = when {
        !accepted -> if (written) ReleaseOutcome.SENT_UNCONFIRMED else ReleaseOutcome.NOT_SENT
        pendingRotation(ctx).optString("new_token").let { FamilySecurity.validToken(it) && it == getOrCreateFamilyToken(ctx) } -> ReleaseOutcome.SENT_UNSURE
        else -> ReleaseOutcome.SENT
    }
    /** The parent deleted this family's relay room for good: nothing queued for it can be delivered and its secrets must not outlive it. */
    private fun purgeChildFamily(ctx: Context, token: String) {
        // The scoring store (messages, scores, consent receipt, mode state) belongs to this family too; wipe it while the token still resolves its queued reports.
        RiskLocal.resetFamily(ctx)
        if (FamilySecurity.validToken(token)) FamilyEvidence(ctx).use { it.discardPending(getChannelTopicForToken(token)) }
        val editor = prefs(ctx).edit()
        keepReleasedCommandIds(ctx, editor)
        for (key in listOf("family_uuid_token", "parent_command_public_key", "parent_password_verifier", "control_state_json", "last_control_ts_ms",
            "pending_rotation_json", "capture_policy_id", "capture_local_ack_id", "capture_child_ack_id", "capture_sequence",
            "trusted_csv", "flagged_offenders_csv", "child_self_handle", "child_self_handle_ts_ms", "sent_setup_ok", "next_heartbeat_at_ms",
            "synced_15m_code", "synced_15m_exp", "synced_15m_rt", "synced_15m_start_rt", "synced_15m_boot", CHILD_QR_PAIRED)) editor.remove(key)
        check(editor.commit())
        forgetChildKey(ctx)
        relayGeneration++
        CloudRelay.disconnect()
    }
    private fun keepReleasedCommandIds(ctx: Context, editor: android.content.SharedPreferences.Editor) {
        val kept = releasedCommandIds(ctx)
        val current = try { JSONObject(prefs(ctx).getString("control_state_json", "{}") ?: "{}").optJSONObject("relayed_ids") } catch (_: Exception) { null }
        current?.keys()?.forEach { kept.put(it, current.optLong(it)) }
        for (key in kept.keys().asSequence().toList()) if (kept.optLong(key) < System.currentTimeMillis() - FamilySecurity.RELAYED_COMMAND_LIFETIME_MS) kept.remove(key)
        editor.putString("released_relayed_ids", kept.toString())
    }
    private fun releasedCommandIds(ctx: Context): JSONObject = try { JSONObject(prefs(ctx).getString("released_relayed_ids", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
    fun releaseChildBeforeLeaving(ctx: Context, waitMs: Long, purgeFamily: Boolean = false): ReleaseOutcome {
        releaseOverride?.let { return it(ctx, waitMs) }
        if (!isParentMode(ctx)) return ReleaseOutcome.NOT_SENT
        val done = java.util.concurrent.CountDownLatch(1); var accepted = false
        try { rotateKeyAndNotifyChild(ctx, true, purgeFamily) { ok -> accepted = ok; done.countDown() } } catch (_: Exception) { return ReleaseOutcome.NOT_SENT }
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            try { done.await(waitMs, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
        // No answer yet: the frame may already be on the socket (a publish takes up to 16 s). Never "not sent", and the reset stays
        // queued so "sent, not confirmed" holds whichever way the publish ends.
        if (done.count > 0L) return ReleaseOutcome.SENT_UNCONFIRMED
        // A failure after a frame was written (the socket closed before the relay answered) may still have reached the relay.
        val outcome = classifyRelease(ctx, accepted, lastResetEvent.let { it != null && it == lastResetWritten })
        if (outcome == ReleaseOutcome.NOT_SENT) {
            // "Not sent" must be true: the signed reset would otherwise keep retrying and could unprotect the child later.
            lastResetEvent?.let { (scope, event) -> try { FamilyEvidence(ctx).use { it.acknowledgeEvent(scope, event) } } catch (_: Exception) { } }
            lastResetEvent = null
        }
        return outcome
    }
    fun leaveParentRole(ctx: Context): Boolean = synchronized(pollLock) {
        check(getRole(ctx) == "parent")
        // Secrets first: a failure leaves the phone a parent and reports it; the role changes only once nothing is left to strand.
        if (!wipeParentSecrets(ctx)) return@synchronized false
        setRole(ctx, "unconfigured")
        true
    }
    /** Removes parent data left on an unconfigured phone (after an older version or an interrupted leave). */
    fun removeLeftoverParentData(ctx: Context): Boolean = synchronized(pollLock) {
        check(getRole(ctx) == "unconfigured")
        wipeParentSecrets(ctx)
    }
    private fun wipeParentSecrets(ctx: Context): Boolean = try {
        // The child's pinned public key and the rest of the per-child pairing state go with the parent role.
        val editor = prefs(ctx).edit().remove("parent_master_password").remove("parent_share_verifier")
            .remove("parent_signing_public").remove("parent_signing_private").remove(FAMILY_QR_ONLY)
        for (key in parentChildKeys) editor.remove(key)
        check(editor.commit())
        FamilySecurity.deleteParentIdentity(ctx)
        identityCache = null
        true
    } catch (_: Exception) { false }
    fun setRole(ctx: Context, role: String) {
        require(role in setOf("child", "parent", "unconfigured"))
        synchronized(pollLock) {
            val previous = getRole(ctx)
            if (previous != role) {relayGeneration++;CloudRelay.disconnect()}
            prefs(ctx).edit().putString("role", role).apply()
            // Leaving the child role (including a remote reset) ends scores-only mode and deletes the scoring store.
            if (previous == "child" && role != "child") { RiskLocal.resetFamily(ctx); forgetChildKey(ctx); prefs(ctx).edit().remove(CHILD_QR_PAIRED).remove(DeliveryHealth.EXPIRED_AT_MS).remove(DeliveryHealth.HEARTBEAT_IDS).apply() }
            if (previous == "child" && role == "unconfigured") purgeChildFamily(ctx, prefs(ctx).getString("family_uuid_token", "").orEmpty())
        }
        syncChildComponents(ctx)
        // The child role was set or confirmed: install the model included in the app, if any (no-op everywhere else).
        if (role == "child") try { BundledModel.maybeAutoInstall(ctx) } catch (_: RuntimeException) {}
    }

    fun syncChildComponents(ctx: Context) {
        try{ArchiveMaintenance.schedule(ctx)}catch(_:RuntimeException){android.util.Log.w("IGGuard","Archive maintenance could not be scheduled")}
        try{RiskScoringSchedule.sync(ctx)}catch(_:RuntimeException){android.util.Log.w("IGGuard","Risk scoring could not be scheduled")}
        if (isChildMode(ctx)) {
            prefs(ctx).edit().remove("parent_signing_private").remove("parent_signing_public").apply()
            val legacyPassword = prefs(ctx).getString("parent_master_password", null)
            if (!legacyPassword.isNullOrBlank()) storeChildVerifier(ctx, FamilySecurity.passwordVerifier(legacyPassword))
        }
        // Preserve existing Android grants while the independent consent gate blocks all events.
        val state = if (getRole(ctx) == "child") PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        for (service in listOf(IgNotificationListener::class.java, IgAccessibilityService::class.java)) {
            val name = ComponentName(ctx, service)
            if (ctx.packageManager.getComponentEnabledSetting(name) != state) {
                ctx.packageManager.setComponentEnabledSetting(name, state, PackageManager.DONT_KILL_APP)
            }
        }
        if (!isChildMode(ctx)) {
            cpNotifConnected = false
            cpAccConnected = false
        }
        refreshMonitoringDisclosure(ctx)
    }

    /** Every notification: the app's shield glyph as small icon (the stock lock only if the drawable is missing) and the brand colour. */
    fun notificationIcon(ctx: Context): Int = ctx.resources.getIdentifier("ic_guard_shield", "drawable", ctx.packageName).takeIf { it != 0 } ?: android.R.drawable.ic_lock_idle_lock
    val NOTIFICATION_COLOR: Int = 0xFF1565C0.toInt()   // Morning sky brand primary (light accentSeafoam); the shade reads on light and dark shades
    fun captureScopeSummary(ctx:Context):String = "擴展擷取已啟用 · 傳入訊息" +
        (if(outgoingCaptureAllowed(ctx))"、已送出的傳出訊息" else "；不含傳出訊息") +
        (if(trustedContextAllowed(ctx))"；包含信任對象的一般脈絡" else "；不含信任對象的一般脈絡")

    fun refreshMonitoringDisclosure(ctx: Context) {
        val manager = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (!isChildMode(ctx)) { manager.cancel(7001); return }
        manager.createNotificationChannel(NotificationChannel("child_disclosure", "子女模式監察提示", NotificationManager.IMPORTANCE_LOW))
        if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getActivity(ctx, 7001, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val active = collectionAllowed(ctx) && (isNotificationListenerEnabledStatic(ctx) || isAccessibilityEnabledStatic(ctx))
        val notification = Notification.Builder(ctx, "child_disclosure")
            .setSmallIcon(notificationIcon(ctx)).setColor(NOTIFICATION_COLOR)
            .setContentTitle(if (!collectionAllowed(ctx)) "Convoy · 擷取及傳送已停止" else if (active) "Convoy · 子女監察模式" else "Convoy · 子女模式等待設定")
            .setContentText(if(!collectionAllowed(ctx))"不擷取新內容，也不傳送待傳記錄。" else RiskLocal.disclosure(ctx,if(fullCaptureEnabled(ctx))captureScopeSummary(ctx) else "IG 警報會加密傳送給已配對的家長。點按查看權限及狀態。"))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).build()
        try { manager.notify(7001, notification) } catch (_: SecurityException) { /* Permission changed while posting. */ }
    }
    fun isChildMode(ctx: Context): Boolean = AppConsent.hasAccepted(ctx) && getRole(ctx) == "child"
    fun isParentMode(ctx: Context): Boolean = AppConsent.hasAccepted(ctx) && getRole(ctx) == "parent"

    private fun recoverEvidenceTransition(ctx: Context) {
        val raw=prefs(ctx).getString("evidence_transition_json",null) ?: return
        val transition=JSONObject(raw)
        val old=transition.getString("old_token");val next=transition.getString("new_token")
        require(FamilySecurity.validToken(old) && FamilySecurity.validToken(next) && old!=next)
        FamilyEvidence(ctx).use { db ->
            db.rotate(getChannelTopicForToken(old),getChannelTopicForToken(next)) { wire ->
                encryptPayload(next,(decryptPayload(old,wire) ?: error("Queued ciphertext requires recovery")).toString())
            }
        }
        check(prefs(ctx).edit().putString("family_uuid_token",next).remove("evidence_transition_json").commit())
        relayGeneration++
    }
    fun getOrCreateFamilyToken(ctx: Context): String = synchronized(pollLock) {
        recoverFreshFamily(ctx)
        recoverEvidenceTransition(ctx)
        val existing = prefs(ctx).getString("family_uuid_token", null)
        if (!existing.isNullOrBlank()) return@synchronized existing
        val generated = UUID.randomUUID().toString()
        prefs(ctx).edit().putString("family_uuid_token", generated).apply()
        generated
    }

    /** [qrOnly] marks the family FAMILY_QR_ONLY in the same commit as the token. With [preserveFamilyHistory] (rotation, QR pairing)
     *  an existing mark is kept; a new family (no history) keeps it only when [qrOnly], and the child's CHILD_QR_PAIRED is dropped. */
    fun setFamilyToken(ctx: Context, token: String, preserveFamilyHistory: Boolean = false, qrOnly: Boolean = false) = synchronized(pollLock) {
        recoverEvidenceTransition(ctx)
        val clean = token.trim().lowercase(Locale.US)
        if (clean.isNotEmpty()) {
            val old = prefs(ctx).getString("family_uuid_token", "") ?: ""
            val editor = prefs(ctx).edit()
            if (old != clean) {
                // The scoring store never survives a key change; a new family also loses the mode state and consent.
                if (preserveFamilyHistory) RiskLocal.wipeData(ctx) else RiskLocal.resetFamily(ctx)
                if (old.isNotEmpty()) FamilyEvidence(ctx).use { db ->
                    if (isParentMode(ctx)) migrateEvidence(ctx)
                    if (preserveFamilyHistory) {
                        val journal=JSONObject().put("old_token",old).put("new_token",clean)
                        check(prefs(ctx).edit().putString("evidence_transition_json",journal.toString()).commit())
                        db.rotate(getChannelTopicForToken(old), getChannelTopicForToken(clean)) { wire ->
                            encryptPayload(clean, (decryptPayload(old, wire) ?: error("Invalid queued ciphertext")).toString())
                        }
                    } else db.discardPending(getChannelTopicForToken(old))
                }
                val manager = ctx.getSystemService(NotificationManager::class.java)
                manager?.activeNotifications?.filter { it.tag?.startsWith(getChannelTopicForToken(old) + ":") == true }
                    ?.forEach { manager.cancel(it.tag, it.id) }
                relayGeneration++
                CloudRelay.disconnect()
                if (!preserveFamilyHistory) {
                    for (key in listOf("control_state_json", "last_control_ts_ms", "pending_rotation_json", "synced_15m_code", "synced_15m_exp", "unlock_until_ms",
                        "synced_15m_rt", "synced_15m_start_rt", "synced_15m_boot", "unlock_until_rt", "unlock_start_rt", "unlock_boot",
                        "pw_lockout_rt", "pw_lockout_start_rt", "pw_lockout_boot",
                        "capture_policy_id","capture_local_ack_id","capture_child_ack_id","capture_sequence",
                        "parent_master_password", "parent_share_verifier", "parent_password_verifier", "parent_command_public_key", "trusted_csv", "flagged_offenders_csv",
                        "child_self_handle", "child_self_handle_ts_ms", "pw_failed_count", "pw_lockout_until_ms", "sent_setup_ok", "next_heartbeat_at_ms", "checkpoint_logs") + parentChildKeys) editor.remove(key)
                    // A new family never keeps the previous family's child key; a QR pairing stores its own after this.
                    forgetChildKey(ctx)
                    editor.remove(CHILD_QR_PAIRED)
                    if (!qrOnly) editor.remove(FAMILY_QR_ONLY)
                    pauseTamperUntilMs = 0L
                    synchronized(this) { recentAlerts.clear(); seenNotifMsgTimestamps.clear() }
                    editor.remove("weekly_stats_json").remove("parent_alerts_json").remove("seen_msg_ids")
                        .remove("parent_last_child_hb_ms").remove("parent_last_child_hb_text").remove("parent_hb_missing_alerted")
                }
            }
            // Token and family-scoped history change in the same state transaction.
            if (qrOnly) editor.putBoolean(FAMILY_QR_ONLY, true)
            check(editor.putString("family_uuid_token", clean).remove("evidence_transition_json").commit())
            if(getRole(ctx)!="unconfigured")CloudRelay.connect(ctx)
        }
    }

    fun hasSentInitialSetupConfirmation(ctx: Context): Boolean = prefs(ctx).getBoolean("sent_setup_ok", false)
    fun markInitialSetupConfirmationSent(ctx: Context, sent: Boolean) = prefs(ctx).edit().putBoolean("sent_setup_ok", sent).apply()

    // Stable local family scope; the network route additionally binds the parent's signing key.
    fun getChannelTopic(ctx: Context): String = getChannelTopicForToken(getOrCreateFamilyToken(ctx))

    fun getChannelTopicForToken(token: String): String {
        val clean = token.trim().lowercase(Locale.US)
        val digest = MessageDigest.getInstance("SHA-256").digest("ig_guard_hk_v4:$clean".toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }.take(28)
        return "ig_hk_$hex"
    }

    fun getSafetyEmojis(ctx: Context): String = safetyEmojis(getOrCreateFamilyToken(ctx))

    /** The pictures for a stored (trimmed, lower-case) token; the child's confirmation sheet shows them before the token is stored. */
    fun safetyEmojis(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
        return (0..3).joinToString(" ") { idx ->
            val b = digest[idx].toInt() and 0xFF
            EMOJI_PALETTE[b % EMOJI_PALETTE.size]
        }
    }

    fun getSafetyCode(ctx: Context): String = synchronized(pollLock) {
        val publicKey = if (isParentMode(ctx)) parentIdentity(ctx).publicKey else prefs(ctx).getString("parent_command_public_key", "") ?: ""
        val verifier = if (isParentMode(ctx)) parentPasswordVerifier(ctx) else prefs(ctx).getString("parent_password_verifier", "") ?: ""
        if (publicKey.isEmpty() || verifier.isEmpty()) "" else FamilySecurity.safetyCode(getOrCreateFamilyToken(ctx), verifier, publicKey)
    }

    /** Chat invitations (V43 text and igguard://pair links) are not accepted for new pairings (design 2.8). */
    const val INVITE_REFUSED = "此手機不再接受聊天邀請或配對連結。請在家長手機選擇「加入子女」，再用此手機掃描家長手機上的 QR 碼。\nThis phone no longer accepts chat invitations or pairing links. On the parent phone choose “Add a child”, then scan the QR code shown there with this phone."

    /** Parent secrets that a pairing link must never remove as a side effect. */
    fun parentSecretsPresent(ctx: Context): Boolean = listOf("parent_master_password", "parent_share_verifier", "parent_signing_public", "parent_signing_private")
        .any { !prefs(ctx).getString(it, null).isNullOrBlank() }
    enum class PairingBlock { PARENT_ROLE, PARENT_DATA_PRESENT }
    /** Why a pairing link cannot be used on this phone right now; null for a phone that may pair. */
    fun pairingBlock(ctx: Context): PairingBlock? = when {
        getRole(ctx) == "parent" -> PairingBlock.PARENT_ROLE
        getRole(ctx) == "unconfigured" && parentSecretsPresent(ctx) -> PairingBlock.PARENT_DATA_PRESENT
        else -> null
    }

    /** Test hook: runs after the re-pair notice is queued/delivered and before the old family is replaced. */
    @Volatile internal var beforeFamilySwitch: ((Context) -> Unit)? = null
    private fun inviteUsable(ctx: Context, invite: FamilySecurity.Invite): Boolean =
        AppConsent.hasAccepted(ctx) && FamilySecurity.validToken(invite.token) && FamilySecurity.isVerifier(invite.passwordVerifier) &&
            FamilySecurity.validPublicKey(invite.parentPublicKey) && pairingBlock(ctx) == null

    /**
     * Only an unconfigured phone may pair freely. A child phone may be re-paired only with the current parent password
     * (checked against the stored verifier, with the normal lockout); a temporary unlock alone is not enough.
     * Before the old family is replaced, a tamper alert is queued to it; with [noticeWaitMs] > 0 (background thread only)
     * delivery is awaited for that long. Relay delivery after the switch is impossible, so a notice not yet accepted is lost.
     */
    fun applyPairing(ctx: Context, invite: FamilySecurity.Invite, parentPassword: String? = null, noticeWaitMs: Long = 0L, childKeyAlias: String? = null): Boolean {
        if (!inviteUsable(ctx, invite)) return false
        if (isChildMode(ctx)) {
            if (parentPassword.isNullOrBlank() || lockoutRemainingMs(ctx) > 0L) return false
            val verified = verifyParentPassword(ctx, parentPassword)
            recordPasswordAttempt(ctx, verified)
            if (!verified) return false
            announceRepair(ctx, noticeWaitMs)
        }
        return synchronized(pollLock) {
            if (!inviteUsable(ctx, invite)) return@synchronized false
            setFamilyToken(ctx, invite.token)
            // Pairing is a fresh authorization, even if the same family invite is reused.
            grantTemporaryUnlock(ctx, 0)
            storeChildVerifier(ctx, invite.passwordVerifier)
            val editor = prefs(ctx).edit().putString("parent_command_public_key", invite.parentPublicKey)
                .remove("capture_policy_id").remove("capture_local_ack_id").remove("capture_child_ack_id")
                .remove("synced_15m_code").remove("synced_15m_exp").remove("synced_15m_rt").remove("synced_15m_start_rt").remove("synced_15m_boot")
                .putBoolean("sent_setup_ok", false).remove(DeliveryHealth.EXPIRED_AT_MS).remove(DeliveryHealth.HEARTBEAT_IDS)
            // A QR pairing (design 2.5 C6): from now on every record to the parent is signed with this key.
            if (childKeyAlias != null) { forgetChildKey(ctx); editor.putString(CHILD_KEY_ALIAS, childKeyAlias).putBoolean(CHILD_QR_PAIRED, true) }
            check(editor.commit())
            setRole(ctx, "child"); scheduleNextRandomHeartbeat(ctx)
            true
        }
    }
    /** An app opened a pairing link on a locked child phone. At most one alert per hour (monotonic), so a hostile app cannot spam the parent. */
    fun noteInviteLinkOnChild(ctx: Context) {
        if (!isChildMode(ctx) || isTemporarilyUnlocked(ctx)) return
        val p = prefs(ctx); val now = monotonicClock(); val boot = bootMarker(ctx)
        val last = p.getLong("link_alert_rt", 0L)
        if (p.getString("link_alert_boot", "") == boot && now >= last && last != 0L && now - last < 60 * 60_000L) return
        p.edit().putLong("link_alert_rt", now).putString("link_alert_boot", boot).apply()
        sendAlertFromChild(ctx, "tamper_attempt", "Deep-Link Guard",
            "有程式在子女手機開啟了家庭配對邀請。更換家庭需要家長主密碼；每小時最多通知一次。", bypassDedupe = true)
    }
    // ---------------- QR pairing (design m2 rev. 3, 2.5): storage and relay side of the state machines ----------------
    fun newParentPairing(ctx: Context): ParentPairing {
        check(isParentMode(ctx))
        return ParentPairing({ FamilySecurity.signCommand(it, parentIdentity(ctx).privateKey) }, EMOJI_PALETTE.toList()).also { live(it) }
    }
    /** [created] receives K as soon as it exists: the child proves it to the new family room before M3 (relay v2). */
    fun newChildPairing(ctx: Context, created: (FamilySecurity.ChildSigningKey) -> Unit = {}): ChildPairing =
        ChildPairing({ FamilySecurity.createChildSigningKey(ctx).also(created) }, { FamilySecurity.deleteChildSigningKey(ctx, it) }, EMOJI_PALETTE.toList()).also { live(it) }

    /** The pairing machines of this process, held weakly: a machine nobody holds any more stops counting. */
    private val livePairings = java.util.Collections.newSetFromMap(java.util.WeakHashMap<Any, Boolean>())
    private fun live(machine: Any) = synchronized(livePairings) { livePairings.add(machine) }
    /** Test hook: unit tests share one GuardEngine, so machines another test left mid-pairing would count here. */
    internal fun forgetLivePairingsForTest() = synchronized(livePairings) { livePairings.clear() }
    private fun pairingInProgress(storedAlias: String?): Boolean = synchronized(livePairings) {
        livePairings.any { when (it) {
            is ParentPairing -> it.state !is ParentPairingState.Cancelled && it.state != ParentPairingState.Accepted
            // Paired counts until completeChildPairing has stored K's alias.
            is ChildPairing -> it.state !is ChildPairingState.Cancelled && !(it.state == ChildPairingState.Paired && it.keyAlias == storedAlias)
            else -> false
        } }
    }
    /**
     * App start, every role, best effort: deletes the child signing keys and markers that no stored pairing references (the process
     * died mid-pairing). Never the stored alias; the parent identity has another alias pattern. Skipped while a pairing machine of
     * this process is running (both are main-thread only, so none can start meanwhile). Never throws.
     */
    /** Parent: the token S of each QR shown here whose rendezvous room the relay has not yet confirmed gone, stored as
     *  "<time first stored, ms>:<token>" in the same encrypted preferences as the other pairing state. It is that room's credential,
     *  so it is kept only until then, at most RENDEZVOUS_MAX_AGE_MS (by then the relay has retired the unused room itself) and at
     *  most RENDEZVOUS_MAX entries (the oldest go first). */
    private const val RENDEZVOUS_TOKENS = "pairing_rendezvous_tokens"
    /** relay/src/worker.js REGISTRATION_TTL: an idle room is retired by the relay after 90 days. */
    internal const val RENDEZVOUS_MAX_AGE_MS = 90L * 24 * 60 * 60_000L
    internal const val RENDEZVOUS_MAX = 8
    private fun storedRendezvous(ctx: Context, now: Long): List<Pair<Long, String>> =
        prefs(ctx).getStringSet(RENDEZVOUS_TOKENS, emptySet()).orEmpty().mapNotNull { entry ->
            val at = entry.substringBefore(':').toLongOrNull() ?: return@mapNotNull null
            (at to entry.substringAfter(':')).takeIf { now - at < RENDEZVOUS_MAX_AGE_MS && it.second.isNotEmpty() }
        }.sortedBy { it.first }.takeLast(RENDEZVOUS_MAX)
    private fun storeRendezvous(ctx: Context, entries: List<Pair<Long, String>>) =
        check(prefs(ctx).edit().putStringSet(RENDEZVOUS_TOKENS, entries.map { "${it.first}:${it.second}" }.toSet()).commit())
    fun rememberRendezvous(ctx: Context, token: String, now: Long = System.currentTimeMillis()) = synchronized(pollLock) {
        storeRendezvous(ctx, (storedRendezvous(ctx, now).filter { it.second != token } + (now to token)).takeLast(RENDEZVOUS_MAX))
    }
    fun forgetRendezvous(ctx: Context, token: String, now: Long = System.currentTimeMillis()) = synchronized(pollLock) {
        storeRendezvous(ctx, storedRendezvous(ctx, now).filter { it.second != token })
    }
    /** App start: retire rendezvous rooms a process death left alive (best effort, never throws; skipped while a pairing runs).
     *  A phone that is no longer a parent cannot prove it owns them: their tokens are dropped and the rooms expire at the relay. */
    fun retireLeftoverRendezvous(ctx: Context, now: Long = System.currentTimeMillis()) {
        try {
            val tokens = synchronized(pollLock) {
                // Age and count bounds first, whatever runs: a running pairing's own token is fresh, so it is never the one dropped.
                val kept = storedRendezvous(ctx, now).also { if (prefs(ctx).contains(RENDEZVOUS_TOKENS)) storeRendezvous(ctx, it) }
                if (pairingInProgress(prefs(ctx).getString(CHILD_KEY_ALIAS, null))) return
                if (!isParentMode(ctx)) { if (prefs(ctx).contains(RENDEZVOUS_TOKENS)) check(prefs(ctx).edit().remove(RENDEZVOUS_TOKENS).commit()); return }
                kept.map { it.second }
            }
            for (token in tokens) CloudRelay.retirePairingRoom(ctx, token) { settled -> if (settled) forgetRendezvous(ctx, token) }
        } catch (_: Exception) {}
    }
    fun removeOrphanedChildKeys(ctx: Context) {
        try {
            synchronized(pollLock) {
                val current = prefs(ctx).getString(CHILD_KEY_ALIAS, null)
                if (pairingInProgress(current)) return
                for (alias in FamilySecurity.childSigningKeyAliases(ctx) - setOfNotNull(current))
                    try { FamilySecurity.deleteChildSigningKey(ctx, alias) } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    /**
     * Parent, after ParentPairing.accept and the fresh authentication (design 2.5 P6). (a) In one state transaction T1 replaces the
     * family token (history kept, as a rotation does), K is pinned and the child's record state starts empty. (b) M3 is queued in the
     * new room's outbox: the family connection opens that room with the v2 proof carrying K and publishes nothing before the relay's
     * `ready`, so the room is registered and locked to K before (c) M3 can go out. A failed registration is retried by the outbox
     * like any record; the overview says the pairing is not complete until the child's first verified record, and after
     * [Pairing.CONFIRM_WINDOW_MS] without one [expireUnconfirmedChildPairing] unpins K. [onSent] reports M3's relay acceptance, after
     * which the caller retires the rendezvous room (d). Null: nothing changed. [StoredPairing.session] is the relay session this
     * pairing created, captured under the same lock: the only session the Accept may grant (a later change still locks).
     */
    data class StoredPairing(val eventId: String, val session: Triple<String, String, Int>)
    fun completeParentPairing(ctx: Context, result: ParentPairing.Result, now: Long = System.currentTimeMillis(), onSent: ((Boolean) -> Unit)? = null): StoredPairing? {
        val stored = synchronized(pollLock) {
            if (!isParentMode(ctx) || !FamilySecurity.validToken(result.token) || result.family.token != result.token) return null
            val key = FamilySecurity.canonicalKeyText(result.childKey) ?: return null
            val parentKey = parentIdentity(ctx).publicKey
            if (FamilySecurity.canonicalKeyText(parentKey) == key) return null
            val eventId = result.complete.optString("event_id")
            if (!eventId.startsWith("control:")) return null
            val replaced = if (pairingReplacesChild(ctx)) getOrCreateFamilyToken(ctx) else null
            if (replaced != null) try { rotateKeyAndNotifyChild(ctx, true); lastResetEvent?.let { deliveryCallbacks.remove(it) } } catch (_: Exception) { addCheckpointLog(ctx, "CP3-PAIR", "未能為被取代的子女手機保留解除指令") }
            val released = (releasedFamilyTokens(ctx) + listOfNotNull(replaced)).distinct().joinToString(",")
            setFamilyToken(ctx, result.token, preserveFamilyHistory = true, qrOnly = true)
            val editor = prefs(ctx).edit().putString(PINNED_CHILD_KEY, key).putLong(PAIRING_PENDING_MS, now).remove("pending_rotation_json").putString(RELEASED_FAMILY_TOKENS, released)
            for (stale in listOf(PAIRING_FAILED, PAIRING_CANCELLED, CHILD_REMOTE_RESET, REMOTE_RESET_EVENT, CHILD_RECORD_STATE, TAMPER_NOTICE_MS, QUIET_DROPS, "parent_last_child_hb_ms", "parent_last_child_hb_text", "parent_hb_missing_alerted", "capture_child_ack_id") + DeliveryHealth.PARENT_KEYS) editor.remove(stale)
            check(editor.commit())
            relayGeneration++; CloudRelay.disconnect()
            val session = captureRelaySession(ctx)
            val scope = getChannelTopicForToken(result.token)
            val frames = AlertFrames.split(result.complete).mapIndexed { index, frame -> "$eventId:$index" to encryptPayload(result.token, frame.toString()) }
            FamilyEvidence(ctx).use { it.enqueueBatch(scope, frames, role = "parent", kind = "control") }
            if (onSent != null) deliveryCallbacks[scope to eventId] = DeliveryCallback(session) { ok, _ -> onSent(ok) }
            addCheckpointLog(ctx, "CP3-PAIR", "已固定子女手機金鑰，等待子女手機確認")
            StoredPairing(eventId, session)
        }
        CloudRelay.connect(ctx); retryPendingAlerts(ctx, justQueued = true)
        return stored
    }

    /** Parent: an accepted pairing whose child sent no verified record within 10 minutes is undone (nothing stays pinned, the queued
     *  M3 is dropped, the phone is marked unpaired) and the new family room is retired at the relay, so a child that confirms late
     *  gets 410 and deletes its key instead of waiting. [force]: the parent's own "取消並重新配對", the same rollback at once and
     *  marked as a deliberate cancel. True when it rolled back. */
    fun expireUnconfirmedChildPairing(ctx: Context, now: Long = System.currentTimeMillis(), force: Boolean = false): Boolean = synchronized(pollLock) {
        if (!isParentMode(ctx)) return false
        val since = prefs(ctx).getLong(PAIRING_PENDING_MS, 0L)
        if (since == 0L || !force && now - since < Pairing.CONFIRM_WINDOW_MS) return false
        FamilyEvidence(ctx).use { it.discardPending(getChannelTopic(ctx), "parent") }
        check(prefs(ctx).edit().remove(PINNED_CHILD_KEY).remove(PAIRING_PENDING_MS).remove(CHILD_RECORD_STATE).also { e -> DeliveryHealth.PARENT_KEYS.forEach { e.remove(it) } }.putBoolean(PAIRING_FAILED, true)
            .putBoolean(PAIRING_CANCELLED, force).commit())
        addCheckpointLog(ctx, "CP3-PAIR", if (force) "家長已取消未完成的配對" else "子女手機未在 10 分鐘內確認；配對已取消")
        relayGeneration++; CloudRelay.disconnect()
        CloudRelay.retireRoom(ctx) { ok, _ -> addCheckpointLog(ctx, "CP3-PAIR", if (ok) "已刪除未完成配對的中繼房間" else "未能確認刪除未完成配對的中繼房間") }
        true
    }

    /**
     * Child, after ChildPairing.onComplete (design 2.5 C6): T1, P, the verifier and K's alias are stored through [applyPairing], so a
     * phone already in child mode needs the current parent password and tells its old family first, exactly as a 5.2 re-pair. Then a
     * signed pairing_verified goes out at once. Off the main thread when [noticeWaitMs] > 0. False: nothing stored and K deleted.
     */
    fun completeChildPairing(ctx: Context, result: ChildPairing.Result, parentPassword: String? = null, noticeWaitMs: Long = 0L): Boolean {
        val ok = try {
            FamilySecurity.canonicalKeyText(FamilySecurity.loadChildSigningKey(ctx, result.childKeyAlias).publicKey) == FamilySecurity.canonicalKeyText(result.childKey) &&
                applyPairing(ctx, FamilySecurity.Invite(result.token, result.passwordVerifier, result.parentKey), parentPassword, noticeWaitMs, result.childKeyAlias)
        } catch (_: Exception) { false }
        if (!ok) { try { FamilySecurity.deleteChildSigningKey(ctx, result.childKeyAlias) } catch (_: Exception) {}; return false }
        cpChildKeyRefused = false
        sendAlertFromChild(ctx, "pairing_verified", Build.MODEL ?: "Child_Phone", "子女手機已用 QR 碼完成配對。", bypassDedupe = true)
        return true
    }

    private fun announceRepair(ctx: Context, waitMs: Long) = noticeToParent(ctx, "Re-pair Guard",
        "子女手機已用家長主密碼確認，改為配對另一個家庭。若這不是你操作的，請盡快查看。", waitMs)
    /** The child phone confirmed with the parent password that it is leaving the family (role switch to unconfigured). */
    fun announceChildLeaving(ctx: Context, waitMs: Long) {
        if (isChildMode(ctx)) noticeToParent(ctx, "Role Guard", "子女手機已用家長主密碼確認，離開目前的家庭，回到未設定狀態。", waitMs)
    }
    /** Queues one content-free alert and, off the main thread, waits up to [waitMs] for the relay to take it. */
    private fun noticeToParent(ctx: Context, sender: String, text: String, waitMs: Long) {
        val delivered = java.util.concurrent.CountDownLatch(1)
        sendAlertFromChild(ctx, "tamper_attempt", sender, text, bypassDedupe = true) { _, _ -> delivered.countDown() }
        if (waitMs > 0L && android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            try { delivered.await(waitMs, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
        beforeFamilySwitch?.invoke(ctx)
    }

    fun hasSecureParentCommands(ctx: Context): Boolean = FamilySecurity.validPublicKey(prefs(ctx).getString("parent_command_public_key", "") ?: "")

    fun pendingCapturePolicy(ctx:Context):String = prefs(ctx).getString("capture_policy_id","") ?: ""
    fun fullCaptureEnabled(ctx:Context):Boolean = isChildMode(ctx) && collectionAllowed(ctx) && hasSecureParentCommands(ctx) &&
        pendingCapturePolicy(ctx).isNotEmpty() && prefs(ctx).getString("capture_local_ack_id","")==pendingCapturePolicy(ctx)
    fun parentFullCaptureConfirmed(ctx:Context):Boolean = isParentMode(ctx) && pendingCapturePolicy(ctx).isNotEmpty() &&
        prefs(ctx).getString("capture_child_ack_id","")==pendingCapturePolicy(ctx)
    fun requestFullCapture(ctx:Context,onComplete:((Boolean,String)->Unit)?=null) = synchronized(pollLock) {
        check(isParentMode(ctx))
        val id=pendingCapturePolicy(ctx).ifEmpty{UUID.randomUUID().toString()}
        prefs(ctx).edit().putString("capture_policy_id",id).commit()
        val payload=JSONObject().put("type","capture_policy").put("schema_version",2).put("mode","full_visible_conversation")
            .put("policy_id",id).put("ts_ms",System.currentTimeMillis())
        val token=getOrCreateFamilyToken(ctx)
        postEncryptedToChannel(ctx,getChannelTopicForToken(token),token,payload.toString(),onComplete)
    }
    fun confirmFullCapture(ctx:Context):Boolean = synchronized(pollLock) {
        if(!isChildMode(ctx) || !hasSecureParentCommands(ctx) || pendingCapturePolicy(ctx).isEmpty())return@synchronized false
        if(!prefs(ctx).edit().putString("capture_local_ack_id",pendingCapturePolicy(ctx)).commit())return@synchronized false
        val token=getOrCreateFamilyToken(ctx)
        postEncryptedToChannel(ctx,getChannelTopicForToken(token),token,JSONObject().put("type","capture_ack")
            .put("policy_id",pendingCapturePolicy(ctx)).put("schema_version",2).put("ts_ms",System.currentTimeMillis()).toString(),null)
        refreshMonitoringDisclosure(ctx);true
    }

    /** Queue observations and their content-free screen window atomically; network stays outside pollLock. */
    fun captureMessages(ctx:Context,observations:List<CapturedText>,windowKey:String?=null,expectedSession:Triple<String,String,Int>?=null):List<JSONObject> {
        val accepted=mutableListOf<JSONObject>()
        try {
            synchronized(pollLock) {
                if(!fullCaptureEnabled(ctx) || !monitoringNoticeAllowed(ctx) || expectedSession!=null && captureRelaySession(ctx)!=expectedSession)return emptyList()
                val token=getOrCreateFamilyToken(ctx);val scope=getChannelTopicForToken(token)
                require(observations.size<=128){"Capture batch exceeds 128 observations"}
                val rows=observations;val now=System.currentTimeMillis()
                // Scores-only mode: text is stored on this phone, never queued; keyword alerts go out without text.
                val riskMode=RiskLocal.captureMode(ctx);val contentFreeAlerts=mutableListOf<JSONObject>()
                if(riskMode==RiskCapture.UPLOAD)return emptyList()
                val hashes=rows.map { c->AlertFrames.digest(JSONArray().put(c.direction).put(c.author).put(c.text).put(c.messageTime).toString().toByteArray(Charsets.UTF_8)) }
                FamilyEvidence(ctx).use { db ->
                    val sql=db.writableDatabase;sql.beginTransaction()
                    try {
                        val window=if(windowKey!=null)db.captureWindowState(scope,windowKey)else CaptureWindow()
                        val previous=window.tokens
                        val overlap=ConversationCapture.overlap(previous,hashes)
                        val orderedScreen=windowKey!=null && rows.isNotEmpty() && rows.all{it.source=="visible_dm" && it.messageTime==0L && ConversationCapture.key(it.account,it.conversation,it.conversationKind)==windowKey}
                        val orderBase=prefs(ctx).getLong("capture_sequence",0)
                        val reuse=orderedScreen && overlap.third>0 && window.anchor>0 && window.anchor<=now
                        val anchor=if(reuse)window.anchor else now
                        val start=if(reuse)window.start+overlap.first-overlap.second else orderBase+1
                        val indices=if(windowKey!=null)ConversationCapture.newIndices(previous,hashes)else rows.indices.toList()
                        // A sequence gap after a failed SQLite transaction is harmless; timestamps remain unchanged.
                        if(indices.isNotEmpty())check(prefs(ctx).edit().putLong("capture_sequence",orderBase+indices.size).commit())
                        val childKey by lazy { childSigningKey(ctx) }
                        for((ordinal,index) in indices.withIndex()) {
                            val c=rows[index]
                            if(c.text.isBlank())continue
                            val contact=if(c.conversationKind=="group")c.author else c.conversation
                            val r=ConversationCapture.record(c,now,contact.isNotBlank() && isTrustedSender(ctx,contact,c.text),contact.isNotBlank() && isFlaggedOffender(ctx,contact))
                                .put("policy_id",pendingCapturePolicy(ctx)).put("child_self_handle",getChildSelfHandle(ctx)).put("capture_order",orderBase+ordinal+1)
                                .put("possible_duplicate",c.possibleDuplicate || (windowKey!=null && previous.isNotEmpty() && indices.size==rows.size))
                            if(orderedScreen) {
                                r.put("screen_anchor_ms",anchor).put("screen_order",start+index)
                                // Returning to an overlapping part of the same thread must not duplicate an occurrence.
                                r.put("event_id","screen:"+AlertFrames.digest(JSONArray().put(windowKey).put(anchor).put(start+index).put(hashes[index]).toString().toByteArray(Charsets.UTF_8)))
                            }
                            if(c.sourceKey.isNotEmpty())r.put("event_id","capture:"+AlertFrames.digest(JSONArray().put(r.getString("conversation_key")).put(c.source).put(c.sourceKey).toString().toByteArray(Charsets.UTF_8)))
                            if(!captureObservationAllowed(ctx,c,contact.isNotBlank() && isTrustedSender(ctx,contact,c.text),contact.isNotBlank() && isFlaggedOffender(ctx,contact)))continue
                            if(riskMode!=RiskCapture.NORMAL){
                                if(RiskLocal.captureContentFree(ctx,db,scope,r,riskMode==RiskCapture.ACTIVE,now)){accepted.add(r);if(ConversationCapture.actionable(r))contentFreeAlerts.add(r)}
                                continue
                            }
                            if(db.captured(scope,r.getString("event_id")))continue
                            val stamp=r.getLong("ts_ms")
                            r.put("time",SimpleDateFormat("MM-dd HH:mm:ss",Locale.US).format(Date(stamp)))
                            val outer=signedForParent(ctx,r.toString(),r.getString("event_id"),true,childKey) ?: r.put("receipt_requested",true)
                            val frames=AlertFrames.split(outer).mapIndexed { n,f -> "${r.getString("event_id")}:$n" to encryptPayload(token,f.toString()) }
                            if(db.enqueueCaptured(scope,r.getString("event_id"),frames,true,ConversationCapture.priority(r)))accepted.add(r)
                        }
                        if(windowKey!=null && hashes.isNotEmpty())db.setCaptureWindow(scope,windowKey,hashes,if(orderedScreen)anchor else 0,if(orderedScreen)start else 0)
                        sql.setTransactionSuccessful()
                    } finally {sql.endTransaction()}
                }
                RiskLocal.sendContentFreeAlerts(ctx,contentFreeAlerts,expectedSession)
            }
        } catch(e:Exception) {
            accepted.clear();cpLastHttpStatus="未能保留對話 · ${e.javaClass.simpleName} · 請保持畫面並重試"
            return emptyList()
        }
        if(accepted.isNotEmpty()){retryPendingAlerts(ctx,justQueued=true);ctx.sendBroadcast(Intent(ACTION_UI_REFRESH).setPackage(ctx.packageName))}
        return accepted
    }

    // ================= 5. AUTO-DETECTED CHILD IG HANDLE + TRUSTED FRIENDS + FLAGGED OFFENDERS =================
    fun notificationRecipient(title:String):String {
        if(!title.startsWith("[") || !title.contains("]"))return ""
        val candidate=title.substringAfter("[").substringBefore("]").trim().removePrefix("@").lowercase(Locale.US)
        return candidate.takeIf{VALID_IG_HANDLE_REGEX.matches(it) && it !in RESERVED_UI_HEADERS}.orEmpty()
    }
    fun getChildSelfHandle(ctx: Context): String {
        if (prefs(ctx).contains("child_self_handle") && prefs(ctx).getInt("child_self_handle_source_version", 0) < 2) {
            prefs(ctx).edit().remove("child_self_handle").remove("child_self_handle_ts_ms").apply()
        }
        return (prefs(ctx).getString("child_self_handle", "") ?: "").trim().lowercase(Locale.US).removePrefix("@")
    }

    /** [recordTimeMs]: parent only, the time of the child record the handle came from, stored in the same write as the handle. */
    fun setChildSelfHandle(ctx: Context, handle: String, recordTimeMs: Long? = null): Boolean {
        val clean = handle.trim().lowercase(Locale.US).removePrefix("@")
        if (clean.isEmpty() || clean in RESERVED_UI_HEADERS || !VALID_IG_HANDLE_REGEX.matches(clean)) return false
        prefs(ctx).edit().putString("child_self_handle", clean).putInt("child_self_handle_source_version", 2)
            .apply { if (recordTimeMs != null) putLong("child_self_handle_ts_ms", recordTimeMs) }.apply()
        val cleanedTrusted = getTrustedHandles(ctx).filter { it != clean }
        prefs(ctx).edit().putString("trusted_csv", cleanedTrusted.joinToString(",")).apply()
        return true
    }

    /** Parent: a child record's handle is taken only if the record is newer than the one that set the stored handle (no stored
     *  time, as after an upgrade: taken). Stored clamped to now, as the gate's ordering mark: a record dated ahead cannot pin a handle. */
    private fun applyChildHandleFromRecord(ctx: Context, handle: String, recordTimeMs: Long): Boolean {
        if (prefs(ctx).contains("child_self_handle_ts_ms") && recordTimeMs <= prefs(ctx).getLong("child_self_handle_ts_ms", 0L)) return false
        return setChildSelfHandle(ctx, handle, minOf(recordTimeMs, System.currentTimeMillis()))
    }

    // Issue #1 Fix: Child phone automatically detects its own IG handle and syncs it to Parent phone!
    fun updateAutoDetectedChildHandle(ctx: Context, candidateHandle: String) {
        val clean = candidateHandle.trim().lowercase(Locale.US).removePrefix("@")
        if (clean.isEmpty() || clean in RESERVED_UI_HEADERS || !VALID_IG_HANDLE_REGEX.matches(clean)) return
        val existing = getChildSelfHandle(ctx)
        if (clean == existing) return
        setChildSelfHandle(ctx, clean)
        addCheckpointLog(ctx, "CP1-ID", "已自動識別子女 IG 帳號")
        if (isChildMode(ctx)) {
            sendAlertFromChild(ctx, "tamper_attempt", "Account Guard", "子女手機記錄的 Instagram 帳號已變更。", bypassDedupe = true)
            val token = getOrCreateFamilyToken(ctx)
            val topic = getChannelTopicForToken(token)
            val plain = JSONObject().apply {
                put("type", "sync_child_handle")
                put("child_self_handle", clean)
                put("ts_ms", System.currentTimeMillis())
                put("time", nowDateTimeStr())
            }.toString()
            postEncryptedToChannel(ctx, topic, token, plain, null)
            ctx.sendBroadcast(Intent(ACTION_UI_REFRESH).setPackage(ctx.packageName))
        }
    }

    fun isChildSelfHandle(ctx: Context, candidate: String): Boolean {
        val selfHandle = getChildSelfHandle(ctx)
        if (selfHandle.isEmpty()) return false
        val norm = candidate.trim().lowercase(Locale.US).removePrefix("@")
        return norm == selfHandle
    }

    private fun storedHandles(ctx: Context, key: String): Set<String> {
        val self = getChildSelfHandle(ctx)
        return (prefs(ctx).getString(key, "") ?: "").split(",")
            .map { it.trim().lowercase(Locale.US).removePrefix("@") }.filter { it.isNotEmpty() && it != self }.toSet()
    }

    fun getFlaggedOffenders(ctx: Context): Set<String> = storedHandles(ctx, "flagged_offenders_csv")

    fun isFlaggedOffender(ctx: Context, rawSender: String): Boolean {
        val offenders = getFlaggedOffenders(ctx)
        if (offenders.isEmpty()) return false
        return rawSender.trim().lowercase(Locale.US).removePrefix("@") in offenders
    }

    fun getTrustedHandles(ctx: Context): Set<String> = storedHandles(ctx, "trusted_csv")

    fun setTrustedHandles(ctx: Context, handles: Collection<String>) {
        val selfHandle = getChildSelfHandle(ctx)
        val clean = handles.map { it.trim().lowercase(Locale.US).removePrefix("@") }
            .filter { it.isNotEmpty() && it != selfHandle }
            .distinct()
            .sorted()
        prefs(ctx).edit().putString("trusted_csv", clean.joinToString(",")).apply()
        val remainingOffenders = getFlaggedOffenders(ctx) - clean.toSet()
        prefs(ctx).edit().putString("flagged_offenders_csv", remainingOffenders.joinToString(",")).apply()
    }

    @Synchronized
    fun isNewNotificationMessageTimestamp(sig: String): Boolean {
        if (seenNotifMsgTimestamps.contains(sig)) return false
        if (seenNotifMsgTimestamps.size >= 400) {
            val oldest = seenNotifMsgTimestamps.iterator().next()
            seenNotifMsgTimestamps.remove(oldest)
        }
        seenNotifMsgTimestamps.add(sig)
        return true
    }

    @Synchronized
    fun shouldSendAlert(key: String, windowMs: Long, bypassDedupe: Boolean = false): Boolean {
        if (bypassDedupe) return true
        val now = System.currentTimeMillis()
        val iter = recentAlerts.entries.iterator()
        while (iter.hasNext()) {
            if (now - iter.next().value > SCREEN_DEDUPE_MS) iter.remove()
        }
        val prev = recentAlerts[key]
        if (prev != null && now - prev <= windowMs) {
            return false
        }
        recentAlerts[key] = now
        return true
    }

    fun isTrustedSender(ctx: Context, rawTitle: String, @Suppress("UNUSED_PARAMETER") rawText: String): Boolean =
        rawTitle.trim().lowercase(Locale.US).removePrefix("@") in getTrustedHandles(ctx)

    fun isNonMessageUiString(raw: String): Boolean {
        val clean = raw.trim()
        if (clean.length < 2) return true
        return NON_MESSAGE_UI_PATTERN.matches(clean)
    }
    fun isNotificationSummary(raw:String):Boolean=IGNORED_NOTIFICATIONS.matches(raw.trim())

    fun evaluateMessage(ctx: Context, senderTitle: String, msgBody: String): String? {
        val text = msgBody.trim()
        if (text.isEmpty()) return null
        if (IGNORED_NOTIFICATIONS.matches(text)) return null
        if (isNonMessageUiString(text)) return null
        if (isChildSelfHandle(ctx, senderTitle)) return null

        if (SEXUAL_PATTERN.containsMatchIn(text)) {
            return "sexual_or_grooming_content"
        }

        if (isFlaggedOffender(ctx, senderTitle)) {
            return "sexual_or_grooming_content"
        }

        if (!isTrustedSender(ctx, senderTitle, text)) {
            return "stranger_message"
        }
        return null
    }

    fun sendAlertFromChild(
        ctx: Context,
        reason: String,
        sender: String,
        text: String,
        bypassDedupe: Boolean = false,
        fromNotification: Boolean = false,
        expectedSession: Triple<String,String,Int>? = null,
        /** Extra fields inside the record (signed with it after a QR pairing), and its event id: the heartbeat's delivery counters. */
        extra: JSONObject? = null,
        eventId: String = UUID.randomUUID().toString(),
        onComplete: ((Boolean, String) -> Unit)? = null
    ) {
        if (!transferAllowed(ctx)) {
            completeAlertCallback(onComplete, false, "尚未同意使用條款，擷取及轉傳未啟用"); return
        }
        if(expectedSession!=null && captureRelaySession(ctx)!=expectedSession) {
            completeAlertCallback(onComplete,false,"家庭配對已變更，原擷取已停止");return
        }
        val token=expectedSession?.second ?: getOrCreateFamilyToken(ctx)
        val topic=getChannelTopicForToken(token)
        val cleanSender = sender.trim().ifEmpty { "unknown" }
        // Scores-only mode: alerts about messages carry the category and the account label, never the text.
        val scrubbed = RiskLocal.scrubsText(ctx, reason)
        val cleanText = if (scrubbed) "" else text
        val dedupeKey = "$topic|${reason.lowercase(Locale.US)}|${cleanSender.lowercase(Locale.US).removePrefix("@")}|${text.lowercase(Locale.US)}"
        val windowMs = if (fromNotification) NOTIF_BURST_DEDUPE_MS else SCREEN_DEDUPE_MS
        val bypass = bypassDedupe || reason == "test_alert" || reason == "heartbeat_ok"

        if (!shouldSendAlert(dedupeKey, windowMs = windowMs, bypassDedupe = bypass)) {
            addCheckpointLog(ctx, "CP2-DEDUPE", "略過重複警報")
            onComplete?.invoke(false, "Duplicate suppressed")
            return
        }

        addCheckpointLog(ctx, "CP2-MATCH", "[$reason] 已擷取警報；診斷記錄不含訊息內容")

        val plain = JSONObject().apply {
            put("type", "alert")
            put("event_id", eventId)
            put("capture_source", if (fromNotification) "notification" else "app_or_visible_screen")
            put("reason", reason)
            put("sender", cleanSender)
            put("text", cleanText)
            // Marks an alert whose text was removed by scores-only mode (ordinary alerts never carry this field).
            if (scrubbed) put("content_free", true)
            put("ts_ms", System.currentTimeMillis())
            put("time", nowDateTimeStr())
            put("trusted_csv", getTrustedHandles(ctx).joinToString(","))
            put("child_self_handle", getChildSelfHandle(ctx))
            extra?.keys()?.forEach { put(it, extra.get(it)) }
        }.toString()

        postEncryptedToChannel(ctx, topic, token, plain, onComplete,expectedSession)
        if (!bypass && !runCatching { FamilyEvidence(ctx).use { it.hasQueuedEvent(topic, eventId) } }.getOrDefault(false)) recentAlerts.remove(dedupeKey)
    }

    fun syncTrustedFromParent(ctx: Context) {
        val token = getOrCreateFamilyToken(ctx)
        val topic = getChannelTopicForToken(token)
        val plain = JSONObject().apply {
            put("type", "sync_trusted")
            put("trusted_csv", getTrustedHandles(ctx).joinToString(","))
            put("ts_ms", System.currentTimeMillis())
            put("time", nowDateTimeStr())
        }.toString()
        postEncryptedToChannel(ctx, topic, token, plain, null)
    }

    /** Runs [block] while holding the session lock, so a state change cannot interleave with a capture or a relay line. */
    internal fun <T> withSessionLock(block: () -> T): T = synchronized(pollLock) { block() }

    /** Posts one control record and returns its command id if it was durably queued (a synchronous failure returns null). */
    private fun postControlChecked(ctx: Context, topic: String, token: String, payload: JSONObject): String? {
        val id = UUID.randomUUID().toString()
        var failed = false;var returned = false
        postEncryptedToChannel(ctx, topic, token, JSONObject(payload.toString()).put("command_id", id).toString(), { ok, _ -> if (!returned && !ok) failed = true })
        returned = true
        return if (!failed || FamilyEvidence(ctx).use { it.hasQueuedEvent(topic, "control:$id") }) id else null
    }

    /** Queues one control-wrapped scores report for the parent. Returns its command id, or null if it could not be queued. */
    internal fun queueScoresReport(ctx: Context, payload: JSONObject): String? {
        if (!isChildMode(ctx)) return null
        val token = getOrCreateFamilyToken(ctx)
        return postControlChecked(ctx, getChannelTopicForToken(token), token, payload)
    }

    fun scoresModePolicy(ctx: Context): ScoresModePolicy? = RiskLocal.parentPolicy(ctx)

    /**
     * Parent: sends the signed scores-mode policy. Enabling is refused (false) unless the child's last report says the model is valid.
     * Every new enabling uses a new policy id, and so does a change of [method]; enabling again with the same method while already enabled
     * resends the same id. Disabling is always allowed. False also for an unknown method, or if the record could not be queued; the saved policy then stays as it was.
     */
    fun sendScoresModePolicy(ctx: Context, enabled: Boolean, method: String = ScoresMethod.CAPTURE): Boolean = synchronized(pollLock) {
        check(isParentMode(ctx))
        if (!ScoresMethod.valid(method)) return@synchronized false
        val token = getOrCreateFamilyToken(ctx);val topic = getChannelTopicForToken(token)
        val current = RiskLocal.parentPolicy(ctx)
        val resend = enabled && current?.enabled == true && current.method == method
        if (enabled && !resend && FamilyEvidence(ctx).use { it.scoresModeState(topic) }?.model != "valid") return@synchronized false
        postScoresPolicy(ctx, topic, token, if (resend) current!!.policyId else UUID.randomUUID().toString(), enabled, method)
    }

    /** Parent: sends the CURRENT policy again (same id, same value, fresh signature and time). For a manual "send again". */
    fun resendScoresModePolicy(ctx: Context): Boolean = synchronized(pollLock) {
        check(isParentMode(ctx))
        val current = RiskLocal.parentPolicy(ctx) ?: return@synchronized false
        val token = getOrCreateFamilyToken(ctx)
        postScoresPolicy(ctx, getChannelTopicForToken(token), token, current.policyId, current.enabled, current.method)
    }

    /**
     * Parent: asks the child's phone for a fresh Instagram export. The record is content-free (type and time; the transport adds
     * scope and command id) and goes through the same signed control path as the policy. Refused (false) unless the current policy
     * is on with manual upload and no request was sent in the last 24 hours; false also if it could not be queued.
     */
    fun sendImportRequest(ctx: Context): Boolean = synchronized(pollLock) {
        check(isParentMode(ctx))
        val now = System.currentTimeMillis()
        val policy = RiskLocal.parentPolicy(ctx)
        if (policy?.enabled != true || policy.method != ScoresMethod.UPLOAD || ScoresUi.importRequestLocked(RiskLocal.parentImportRequestAt(ctx), now)) return@synchronized false
        val token = getOrCreateFamilyToken(ctx)
        if (postControlChecked(ctx, getChannelTopicForToken(token), token, JSONObject().put("type", "import_request").put("ts_ms", now)) == null) return@synchronized false
        RiskLocal.saveParentImportRequest(ctx, now)
    }

    private fun postScoresPolicy(ctx: Context, topic: String, token: String, id: String, enabled: Boolean, method: String): Boolean {
        val now = System.currentTimeMillis()
        val payload = JSONObject().put("type", "scores_mode_policy").put("policy_id", id).put("enabled", enabled).put("ts_ms", now)
        // No field means capture, so a capture policy is byte-compatible with what builds before this one send and read.
        if (method != ScoresMethod.CAPTURE) payload.put("method", method)
        if (postControlChecked(ctx, topic, token, payload) == null) return false
        return RiskLocal.saveParentPolicy(ctx, id, enabled, now, method)
    }

    /** A policy the child has not applied (it was rejected as too old, or lost): re-post it, at most once an hour. */
    private fun repostUnappliedScoresPolicy(ctx: Context, state: ChildScoresState) {
        val current = RiskLocal.parentPolicy(ctx) ?: return
        val unapplied = state.policyId != current.policyId || (!current.enabled && state.consent != "none")
        if (unapplied && RiskLocal.repostDue(ctx)) resendScoresModePolicy(ctx)
    }

    private fun postEncryptedToChannel(
        ctx: Context,
        topic: String,
        token: String,
        plainJson: String,
        onComplete: ((Boolean, String) -> Unit)?,
        expectedSession: Triple<String,String,Int>? = null
    ) {
        if(!transferAllowed(ctx)){completeAlertCallback(onComplete,false,"擷取與待傳送已停止");return}
        val payload = JSONObject(plainJson)
        if (payload.optString("type") == "alert") {
            try {
                val receiptRequested = hasSecureParentCommands(ctx)
                val queuedSession = expectedSession ?: captureRelaySession(ctx)
                val queued=synchronized(pollLock) {
                    if (!isChildMode(ctx) || getOrCreateFamilyToken(ctx) != token || captureRelaySession(ctx)!=queuedSession) false
                    else {
                        // Decided under the same lock as accept(): an alert built just before the child agreed loses its text here.
                        if(payload.optString("text").isNotEmpty() && RiskLocal.scrubsText(ctx,payload.optString("reason")))payload.put("text","").put("content_free",true)
                        // Signed after the scrub, so the signature covers exactly what leaves the phone; the receipt flag stays outside it.
                        val outer=signedForParent(ctx,payload.toString(),payload.getString("event_id"),receiptRequested) ?: payload.put("receipt_requested",receiptRequested)
                        val outgoing=AlertFrames.split(outer).mapIndexed { index,frame -> "${payload.getString("event_id")}:$index" to encryptPayload(token,frame.toString()) }
                        FamilyEvidence(ctx).use { it.enqueueBatch(topic,outgoing,waitForReceipt=receiptRequested) };true
                    }
                }
                if(!queued){completeAlertCallback(onComplete,false,"家庭配對已變更，原傳送已停止");return}
                if(onComplete!=null)deliveryCallbacks[topic to payload.getString("event_id")] = DeliveryCallback(queuedSession) { success,message ->
                    val same=captureRelaySession(ctx)==queuedSession
                    completeAlertCallback(onComplete,same && success,if(same)message else "家庭配對已變更，原傳送已停止")
                }
                cpLastHttpStatus = "已加密儲存，等待中繼站接收"
                retryPendingAlerts(ctx, justQueued = true)
            } catch (e: Exception) {
                cpLastHttpStatus = "未能儲存或傳送 · ${e.javaClass.simpleName}"
                completeAlertCallback(onComplete,false,cpLastHttpStatus)
            }
            return
        }
        val origin=captureRelaySession(ctx)
        try {
            val type=payload.optString("type")
            require(topic==getChannelTopicForToken(token) && token in relayRouteTokens(ctx))
            val parentCommand=type in FamilySecurity.controlTypes
            check(if(parentCommand)isParentMode(ctx) else isChildMode(ctx) && type in setOf("capture_ack","sync_child_handle","scores_mode_report"))
            val commandId=payload.optString("command_id").ifEmpty{UUID.randomUUID().toString()}
            require(UUID.fromString(commandId).toString()==commandId)
            payload.put("scope",topic).put("command_id",commandId)
            val envelope=if(parentCommand)FamilySecurity.signCommand(payload,parentIdentity(ctx).privateKey) else payload
            val eventId="control:$commandId"
            // A QR-paired child's scores report waits for the parent's receipt (DeliveryHealth counts it; RiskReporter.confirmed).
            val waits=type=="scores_mode_report" && childQrPaired(ctx)
            // A child's control record after a QR pairing travels as a signed record instead of the relay_control envelope.
            val wrapped=(if(parentCommand)null else signedForParent(ctx,payload.toString(),eventId,if(waits)true else null))
                ?: JSONObject().put("type","alert").put("event_id",eventId).put("relay_control",true).put("envelope",envelope)
            val frames=AlertFrames.split(wrapped).mapIndexed{index,frame->"$eventId:$index" to encryptPayload(token,frame.toString())}
            synchronized(pollLock) {
                check(captureRelaySession(ctx)==origin)
                FamilyEvidence(ctx).use{it.enqueueBatch(topic,frames,waitForReceipt=waits,role=origin.first,kind=if(type=="alert_receipt")"receipt" else "control")}
                if(onComplete!=null)deliveryCallbacks[topic to eventId]=DeliveryCallback(origin){success,message->
                    val same=captureRelaySession(ctx)==origin
                    completeAlertCallback(onComplete,same && success,if(same)message else "家庭配對已變更，原傳送已停止")
                }
            }
            cpLastHttpStatus="指令已加密保留 · 等待中繼確認"
            retryPendingAlerts(ctx, justQueued = true)
        } catch(e:Exception) {
            cpLastHttpStatus="未能保留指令 · ${e.javaClass.simpleName}"
            completeAlertCallback(onComplete,false,cpLastHttpStatus)
        }
    }

    fun pendingDeliveryCount(ctx: Context): Int = FamilyEvidence(ctx).use { it.pendingCount(getChannelTopic(ctx)) }

    private fun completeAlertCallback(callback: ((Boolean,String)->Unit)?,success: Boolean,message: String) {
        try { callback?.invoke(success,message) } catch (_: Exception) {}
    }
    private fun finishDeliveryCallbacks(ctx: Context) {
        for((key,callback) in deliveryCallbacks.entries.toList()) {
            val scope=key.first
            val active=captureRelaySession(ctx)==callback.origin
            if(!active){if(deliveryCallbacks.remove(key,callback))completeAlertCallback(callback.complete,false,"家庭配對已變更，原傳送已停止");continue}
            var accepted=false;var due=false
            try { FamilyEvidence(ctx).use { db -> accepted=db.unacceptedEventCount(scope,key.second)==0;due=db.eventDue(scope,key.second,System.currentTimeMillis()) } }
            catch (_: Exception) {}
            if(!active || accepted || !due) {
                if(deliveryCallbacks.remove(key,callback))completeAlertCallback(callback.complete,active && accepted,
                    if(!active)"家庭配對已變更，原傳送已停止" else if(accepted)cpLastHttpStatus else "未送達：已加密保留，稍後重試")
            }
        }
    }
    fun prepareRelayOutbox(ctx:Context) = synchronized(pollLock) {
        if(isParentMode(ctx)){completePendingRotation(ctx);return@synchronized}
        if(!isChildMode(ctx) || !hasSecureParentCommands(ctx))return@synchronized
        val token=getOrCreateFamilyToken(ctx)
        FamilyEvidence(ctx).use{db->db.migrateRelay(getChannelTopicForToken(token)){wire->
            val decoded=decryptPayload(token,wire) ?: error("Encrypted queue recovery required")
            require(decoded.optString("type") in ConversationCapture.contentTypes || decoded.optString("type")=="alert_fragment")
            decoded.put("receipt_requested",true)
            encryptPayload(token,decoded.toString())
        }}
    }
    private fun pendingRotation(ctx:Context):JSONObject = try{JSONObject(prefs(ctx).getString("pending_rotation_json","{}").orEmpty())}catch(_:Exception){JSONObject()}
    private fun rotationEvent(pending:JSONObject):String = pending.optJSONObject("command")?.optString("command_id")?.takeIf{it.isNotEmpty()}?.let{"control:$it"}.orEmpty()
    private fun completePendingRotation(ctx:Context) {
        if(!isParentMode(ctx))return
        val pending=pendingRotation(ctx);val old=pending.optString("old_token");val next=pending.optString("new_token");val event=rotationEvent(pending)
        if(!FamilySecurity.validToken(old) || !FamilySecurity.validToken(next) || event.isEmpty() || getOrCreateFamilyToken(ctx) !in listOf(old,next))return
        val scope=getChannelTopicForToken(old)
        if(!FamilyEvidence(ctx).use{it.eventAccepted(scope,event)})return
        // Keep accepted controls until the journaled token/history transition has durably committed.
        if(getOrCreateFamilyToken(ctx)==old){setFamilyToken(ctx,next,preserveFamilyHistory=true);ParentAlertService.reconnectActiveStream()}
        FamilyEvidence(ctx).use{it.acknowledgeEvent(scope,event)}
    }
    internal fun recordRelayAcceptance(ctx:Context,scope:String,frame:PendingEvidence) = synchronized(pollLock) {
        val pending=pendingRotation(ctx)
        val rotation=isParentMode(ctx) && scope==getChannelTopicForToken(pending.optString("old_token")) &&
            frame.id.substringBeforeLast(':')==rotationEvent(pending)
        FamilyEvidence(ctx).use{db->
            if(frame.waitForReceipt || rotation)db.relayAccepted(scope,frame.id,System.currentTimeMillis()) else db.acknowledge(scope,frame.id)
        }
        if(rotation)completePendingRotation(ctx)
        val event=frame.id.substringBeforeLast(':')
        if(isParentMode(ctx) && scope==getChannelTopic(ctx) && event==prefs(ctx).getString(REMOTE_RESET_EVENT,null) &&
            FamilyEvidence(ctx).use{it.unacceptedEventCount(scope,event)==0})markChildRemotelyReset(ctx)
    }
    /**
     * The relay holds every frame of a signed remote reset for the pinned child, which deletes K and its pairing on receipt. Rule: the
     * pin, the pending-pairing mark, the child's record state and its last report are dropped and the reset is remembered, so the
     * overview says so and processIncomingRelayLine refuses every later record on this family, signed or not (no fall-back to the
     * unsigned 5.2 format). The family token stays until a new QR pairing replaces it and clears the mark: the reset still waits in its room.
     */
    private fun markChildRemotelyReset(ctx:Context) {
        val editor=prefs(ctx).edit().putBoolean(CHILD_REMOTE_RESET,true)
        for(key in listOf(PINNED_CHILD_KEY,PAIRING_PENDING_MS,CHILD_RECORD_STATE,REMOTE_RESET_EVENT,"parent_last_child_hb_ms","parent_last_child_hb_text","parent_hb_missing_alerted")+DeliveryHealth.PARENT_KEYS)editor.remove(key)
        check(editor.commit())
        addCheckpointLog(ctx,"CP3-RESET","子女手機已遠端重設；已停止信任其金鑰，須用 QR 碼重新配對")
    }
    fun retryPendingAlerts(ctx: Context, force: Boolean = false, justQueued: Boolean = false) {
        if (!AppConsent.hasAccepted(ctx) || getRole(ctx)=="unconfigured") return
        try{prepareRelayOutbox(ctx)}catch(_:Exception){
            cpLastHttpStatus="加密佇列需要復原 · 已保留原有片段，請保留 App 資料"
            val origin=captureRelaySession(ctx)
            for((key,callback) in deliveryCallbacks.entries.toList())if(callback.origin==origin && deliveryCallbacks.remove(key,callback))completeAlertCallback(callback.complete,false,cpLastHttpStatus)
            return
        }
        if (force) FamilyEvidence(ctx).use { it.retryNow(getChannelTopic(ctx)) }
        val app=ctx.applicationContext
        if (!deliveryInFlight.compareAndSet(false, true)) {
            // Only a caller that has just queued a record leaves a note. The connection's ready hook, timers and screens ask
            // while a worker runs all the time; those stay as before: turned away.
            if (!justQueued) return
            deliveryAskedMeanwhile.set(app)
            // The worker may have ended between the two lines: then nobody would read the note, so take the slot here.
            if (!deliveryInFlight.compareAndSet(false, true)) return
            deliveryAskedMeanwhile.compareAndSet(app, null)
        }
        val session=captureRelaySession(app)
        thread {
            try {
                deliveryLoop@ while(transferAllowed(app) && captureRelaySession(app)==session) {
                    val next=relayRouteTokens(app).firstNotNullOfOrNull{token->
                        val scope=getChannelTopicForToken(token)
                        val batch=FamilyEvidence(app).use{it.due(scope,System.currentTimeMillis(),session.first)}
                        if(batch.isEmpty())null else Triple(token,scope,batch)
                    } ?: break
                    for(frame in next.third) {
                        if(!transferAllowed(app) || captureRelaySession(app)!=session)break@deliveryLoop
                        if(!FamilyEvidence(app).use{it.pendingEvent(next.second,frame.id)})continue
                        val result=CloudRelay.publishBlocking(app,next.first,frame.id,frame.wire,frame.kind)
                        if(result.written)lastResetEvent?.takeIf{it==next.second to frame.id.substringBeforeLast(':')}?.let{lastResetWritten=it}
                        synchronized(pollLock) {
                        if(!transferAllowed(app) || captureRelaySession(app)!=session)return@thread
                        if(result.accepted)recordRelayAcceptance(app,next.second,frame)
                        else if(result.expired)FamilyEvidence(app).use{it.refusedAsExpired(next.second,frame.id,System.currentTimeMillis())}
                        else FamilyEvidence(app).use{it.failed(next.second,frame.id,System.currentTimeMillis(),session.first,if(result.contextFull)"data" else null)}
                        }
                        if(captureRelaySession(app)==session) {
                            // A signed receipt may have overtaken the publish acknowledgment.
                            val stillPending=FamilyEvidence(app).use{it.pendingEvent(next.second,frame.id)}
                            if(!result.accepted || !frame.waitForReceipt || stillPending)cpLastHttpStatus=result.message
                            addCheckpointLog(app,if(result.accepted)"CP3-RELAY" else "CP3-ERR",if(result.accepted)"中繼已接收加密封包；家長是否收到另行確認" else result.message)
                        }
                        if(!result.accepted){if(result.contextFull || result.expired)continue@deliveryLoop else break@deliveryLoop}
                    }
                }
            } finally {
                beforeDeliveryWorkerEndsForTest?.invoke()
                deliveryInFlight.set(false)
                if(AppConsent.hasAccepted(app))finishDeliveryCallbacks(app)
                app.sendBroadcast(Intent(ACTION_UI_REFRESH).setPackage(app.packageName))
                // A new capture arriving as the worker exits must not be stranded.
                if(transferAllowed(app) && captureRelaySession(app)==session && relayRouteTokens(app).any{token->FamilyEvidence(app).use{it.due(getChannelTopicForToken(token),System.currentTimeMillis(),session.first).isNotEmpty()}})retryPendingAlerts(app)
                // Someone asked while this worker held the slot (for example a pairing that changed the family): run that one
                // request now. At most one run per request, so a worker that ends with an error cannot keep starting others.
                deliveryAskedMeanwhile.getAndSet(null)?.let{retryPendingAlerts(it)}
            }
        }
    }

    fun pollChannelOnce(ctx: Context, since: String = "all", onNewAlertForParent: ((JSONObject) -> Unit)? = null) {
        if (!AppConsent.hasAccepted(ctx)) return
        // Existing callers now request one shared socket/replay, never a second polling transport.
        CloudRelay.connect(ctx,onNewAlertForParent)
        retryPendingAlerts(ctx)
    }

    fun processIncomingRelayLine(
        ctx: Context,
        line: String,
        role: String,
        onNewAlertForParent: ((JSONObject) -> Unit)?,
        expectedToken: String? = null,
        expectedGeneration: Int? = null
    ): RelayConsumption = synchronized(pollLock) {
        try {
        if (!AppConsent.hasAccepted(ctx) || role != getRole(ctx) || role == "unconfigured") return RelayConsumption.RETRY
        if (expectedGeneration != null && expectedGeneration != relayGeneration) return RelayConsumption.RETRY
        if (expectedToken != null && expectedToken != getOrCreateFamilyToken(ctx)) return RelayConsumption.RETRY
        val outer = try { JSONObject(line) } catch (_: Exception) { return RelayConsumption.REJECTED }
        if (outer.optString("event") != "message") return RelayConsumption.REJECTED
        val msgId = outer.optString("id")
        if (msgId.isEmpty()) return RelayConsumption.REJECTED

        val innerStr = outer.optString("message")
        val token = getOrCreateFamilyToken(ctx)
        val room = try { FamilyRelay.room(relayPublicKey(ctx), getChannelTopicForToken(token)) } catch (_: Exception) { null }
        val decoded = decryptPayload(token, innerStr, role, room) ?: return RelayConsumption.REJECTED
        // Legacy (the unsigned 5.2 format) only for a family without the sticky FAMILY_QR_ONLY fact. With it and no pinned key (rolled
        // back, cancelled, remotely reset, or any other unpinning) no record is taken, signed or not, until a new QR pairing pins a key.
        // The rollback/reset marks only choose the overview text.
        if (role == "parent" && pinnedChildKey(ctx) == null && (familyQrOnly(ctx) || wallClock() >= LEGACY_PAIRING_END_MS)) return RelayConsumption.REJECTED
        val decrypted = if (decoded.optString("type") == "alert_fragment") {
            if (role != "parent" && !(role=="child" && decoded.optString("event_id").startsWith("control:"))) return RelayConsumption.REJECTED
            // A late part of a record already taken: reassembly would return the stored (unwrapped) record, which the gate must not see.
            if (role == "parent" && pinnedChildKey(ctx) != null && FamilyEvidence(ctx).use { it.hasSeen(getChannelTopicForToken(token), decoded.optString("event_id")) }) {
                sendDeliveryReceipt(ctx, decoded.optString("event_id"), decoded.optBoolean("receipt_requested")); return RelayConsumption.CONSUMED
            }
            val completed =FamilyEvidence(ctx).use { it.fragment(getChannelTopicForToken(token), decoded) }
            if (completed == null) {
                if(FamilyEvidence(ctx).use { it.hasSeen(getChannelTopicForToken(token),decoded.optString("event_id")) })
                    sendDeliveryReceipt(ctx,decoded.optString("event_id"),decoded.optBoolean("receipt_requested"))
                val durable=FamilyEvidence(ctx).use { db -> db.hasSeen(getChannelTopicForToken(token),decoded.optString("event_id")) || db.hasFragment(getChannelTopicForToken(token),decoded.optString("event_id"),decoded.optInt("part",-1),decoded.optString("digest")) }
                return if(durable)RelayConsumption.CONSUMED else RelayConsumption.REJECTED
            }
            if(decoded.optBoolean("receipt_requested"))completed.put("receipt_requested",true) else completed
        } else decoded
        if (role == "parent" && decrypted.optJSONObject("envelope")?.let { FamilySecurity.signedBy(it, parentIdentity(ctx).publicKey) } == true) {
            if (decoded.optString("type") == "alert_fragment") FamilyEvidence(ctx).use { it.discardFragments(getChannelTopicForToken(token), decoded.optString("event_id")) }
            return RelayConsumption.REJECTED
        }
        // Parent: every frame passes the child-record gate first (design 3.3). With a pinned key only a record signed by it goes on;
        // without one (a 5.2 pairing) the unsigned format is processed as before and the child is shown as not verified.
        val now = System.currentTimeMillis()
        val pinned = if (role == "parent") pinnedChildKey(ctx) else null
        val gate = if (role == "parent") ChildRecords.gate(decrypted, pinned, if (pinned == null) "" else parentIdentity(ctx).publicKey, now, prefs(ctx).getString(CHILD_RECORD_STATE, null)) else null
        when (gate) {
            is ChildRecords.GateResult.Tamper -> {
                // Nothing of a refused record stays: its stored parts are deleted too. They all belong to this completed set (one
                // digest and count per id), so no part of another record is touched; the id is not marked seen.
                if (decoded.optString("type") == "alert_fragment") FamilyEvidence(ctx).use { it.discardFragments(getChannelTopicForToken(token), decoded.optString("event_id")) }
                noteChildRecordTamper(ctx, onNewAlertForParent, now); return RelayConsumption.REJECTED
            }
            is ChildRecords.GateResult.QuietDrop -> {
                if (decoded.optString("type") == "alert_fragment") FamilyEvidence(ctx).use { it.discardFragments(getChannelTopicForToken(token), decoded.optString("event_id")) }
                check(prefs(ctx).edit().putInt(QUIET_DROPS, childQuietDrops(ctx) + 1).commit()); return RelayConsumption.REJECTED
            }
            ChildRecords.GateResult.Replay -> {
                // The signature and the wrapper id were verified before the replay check, so this id is the record's own.
                val id = decrypted.optString("event_id")
                if (id.startsWith("control:")) FamilyEvidence(ctx).use { it.controlConsumed(getChannelTopicForToken(token), id) }
                return RelayConsumption.CONSUMED
            }
            else -> {}
        }
        val verified = gate as? ChildRecords.GateResult.Verified
        if(verified!=null&&prefs(ctx).contains(PAIRING_PENDING_MS))check(prefs(ctx).edit().remove(PAIRING_PENDING_MS).commit())
        val controlId=if(verified!=null)(if(verified.type in ConversationCapture.contentTypes)"" else "control:"+verified.id)
            else if(decrypted.optBoolean("relay_control"))decrypted.optString("event_id") else ""
        val envelope=if(verified!=null)verified.record else if(controlId.isNotEmpty())decrypted.optJSONObject("envelope") ?: return RelayConsumption.REJECTED else decrypted
        val inner = if (verified != null) verified.record else if (envelope.has("payload")) {
            if (role != "child") return RelayConsumption.REJECTED
            val parentKey = prefs(ctx).getString("parent_command_public_key", "") ?: ""
            FamilySecurity.readCommand(envelope, parentKey, getChannelTopicForToken(token), System.currentTimeMillis())
                ?: return if (FamilySecurity.commandAhead(envelope, parentKey, getChannelTopicForToken(token), System.currentTimeMillis())) RelayConsumption.CLOCK_BEHIND else RelayConsumption.REJECTED
        } else {
            if (envelope.optString("type") in FamilySecurity.controlTypes) return RelayConsumption.REJECTED
            if(controlId.isNotEmpty() && (role!="parent" || envelope.optString("type") !in setOf("capture_ack","sync_child_handle","scores_mode_report")))return RelayConsumption.REJECTED
            envelope
        }
        if(controlId.isNotEmpty() && controlId!="control:"+inner.optString("command_id"))return RelayConsumption.REJECTED
        val msgType = inner.optString("type")
        val msgTs = inner.optLong("ts_ms", 0L)

        var committedControlState:String?=null
        if (verified == null && msgType in FamilySecurity.controlTypes) {
            val state = try { JSONObject(prefs(ctx).getString("control_state_json", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
            val ids = state.optJSONObject("ids") ?: JSONObject().also { state.put("ids", it) }
            // Ids of commands accepted for days (FamilySecurity.relayedCommandTypes) are kept as long as they can be accepted.
            val relayedIds = state.optJSONObject("relayed_ids") ?: JSONObject().also { state.put("relayed_ids", it) }
            val id = inner.getString("command_id")
            if (ids.has(id) || relayedIds.has(id) || releasedCommandIds(ctx).has(id)) {
                if(controlId.isNotEmpty())FamilyEvidence(ctx).use{it.controlConsumed(getChannelTopicForToken(token),controlId)}
                return RelayConsumption.CONSUMED
            }
            if (msgType != "alert_receipt" && msgTs < state.optLong(msgType)) return RelayConsumption.REJECTED
            for (key in ids.keys().asSequence().toList()) if (ids.optLong(key) < System.currentTimeMillis() - 24 * 60 * 60_000L) ids.remove(key)
            for (key in relayedIds.keys().asSequence().toList()) if (relayedIds.optLong(key) < System.currentTimeMillis() - FamilySecurity.RELAYED_COMMAND_LIFETIME_MS) relayedIds.remove(key)
            (if (msgType in FamilySecurity.relayedCommandTypes) relayedIds else ids).put(id, msgTs); state.put(msgType, msgTs)
            committedControlState=state.toString()
        }

        when (msgType) {
            "capture_policy" -> {
                if(role!="child")return RelayConsumption.REJECTED
                val id=inner.getString("policy_id")
                prefs(ctx).edit().putString("capture_policy_id",id).commit()
                ctx.sendBroadcast(Intent(ACTION_UI_REFRESH).setPackage(ctx.packageName))
            }
            "capture_ack" -> {
                if(role=="parent" && inner.optInt("schema_version")==2 && pendingCapturePolicy(ctx).isNotEmpty() && inner.optString("policy_id")==pendingCapturePolicy(ctx)) {
                    prefs(ctx).edit().putString("capture_child_ack_id",inner.getString("policy_id")).commit()
                    ctx.sendBroadcast(Intent(ACTION_UI_REFRESH).setPackage(ctx.packageName))
                }
            }
            "scores_mode_policy" -> if(role=="child")RiskLocal.receivePolicy(ctx,inner.getString("policy_id"),inner.getBoolean("enabled"),inner.optString("method",ScoresMethod.CAPTURE))
            // Content-free: no field of it is read. Shown or ignored on the child's side; consumed either way.
            "import_request" -> if(role=="child")RiskLocal.receiveImportRequest(ctx)
            "scores_mode_report" -> if(role=="parent") {
                val received=System.currentTimeMillis();val topic=getChannelTopicForToken(token)
                val report=ScoresReportCodec.parse(inner,topic,received)
                if(report!=null) {
                    val reached=FamilyEvidence(ctx).use{it.storeScoresReport(topic,report,received)}
                    // Generic notice only: no account label, no score, no text.
                    if(reached>=0)repostUnappliedScoresPolicy(ctx,report.state)
                    if(reached>0)onNewAlertForParent?.invoke(JSONObject().put("type","alert").put("reason","scores_threshold").put("event_id","scores:"+UUID.randomUUID()).put("sender","").put("text","").put("ts_ms",received))
                    ctx.sendBroadcast(Intent(ACTION_UI_REFRESH).setPackage(ctx.packageName))
                }
                // A verified report stored here, now or before, is receipted; one refused by the parser never is (sendDeliveryReceipt checks).
                if(verified!=null)sendDeliveryReceipt(ctx,controlId,decrypted.optBoolean("receipt_requested"))
            }
            "alert_receipt" -> {
                if(role=="child")noteReceiptForDeliveryHealth(ctx,getChannelTopic(ctx),inner.getString("event_id"))
                if(role=="child" && inner.optString("outcome")=="stored")RiskReporter.confirmed(ctx,getChannelTopic(ctx),inner.getString("event_id"))
                FamilyEvidence(ctx).use{it.acknowledgeEvent(getChannelTopic(ctx),inner.getString("event_id"))}
                cpLastHttpStatus = if(inner.optString("outcome")=="discarded") "家長已刪除此記錄 · 停止重試" else "家長手機已確認接收 · 不代表已閱讀"
                ctx.sendBroadcast(Intent(ACTION_UI_REFRESH).setPackage(ctx.packageName))
            }
            "sync_trusted" -> {
                val csv = inner.optString("trusted_csv")
                val list = csv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                setTrustedHandles(ctx, list)
                addCheckpointLog(ctx, "CP4-SYNC", "已更新信任名單（${list.size} 個帳號）")
                ctx.sendBroadcast(Intent(ACTION_UI_REFRESH).setPackage(ctx.packageName))
            }
            "sync_child_handle" -> {
                if (role == "parent") {
                    val detected = inner.optString("child_self_handle")
                    if (detected.isNotEmpty() && applyChildHandleFromRecord(ctx, detected, msgTs)) {
                        addCheckpointLog(ctx, "CP4-ID", "已從子女手機同步子女 IG 帳號")
                        ctx.sendBroadcast(Intent(ACTION_UI_REFRESH).setPackage(ctx.packageName))
                    }
                }
            }
            "unlock_code" -> {
                val code = inner.optString("code")
                val exp = inner.optLong("exp", 0L)
                if (code.isNotEmpty() && exp > wallClock()) {
                    // The code's life is measured on the monotonic clock (at most 15 minutes) as well as by its absolute expiry.
                    val now = monotonicClock()
                    prefs(ctx).edit()
                        .putString("synced_15m_code", code)
                        .putLong("synced_15m_exp", exp)
                        .putLong("synced_15m_rt", now + minOf(exp - wallClock(), UNLOCK_CODE_MS))
                        .putLong("synced_15m_start_rt", now).putString("synced_15m_boot", bootMarker(ctx))
                        .apply()
                    addCheckpointLog(ctx, "CP4-SYNC", "已收到加密的 15 分鐘解鎖碼")
                }
            }
            "key_rotated" -> {
                val newToken = inner.optString("new_token")
                val newPin = inner.optString("new_pin")
                if (newToken.isNotEmpty()) {
                    setFamilyToken(ctx, newToken, preserveFamilyHistory = true)
                    storeChildVerifier(ctx, newPin)
                    // The waiting records moved to the new room (FamilyEvidence.rotate): send them now, not at the next trigger.
                    // Only starts the delivery thread; its relay I/O runs outside pollLock (held here, re-entrant).
                    retryPendingAlerts(ctx, justQueued = true)
                    addCheckpointLog(ctx, "CP4-KEY", "已自動更新為新的家長家庭金鑰")
                    ctx.sendBroadcast(Intent(ACTION_UI_REFRESH).setPackage(ctx.packageName))
                }
            }
            "remote_reset_child" -> {
                if (role == "child") {
                    grantTemporaryUnlock(ctx, 10 * 60_000L)
                    committedControlState?.let { check(prefs(ctx).edit().putString("control_state_json", it).commit()) }
                    setRole(ctx, "unconfigured")
                    addCheckpointLog(ctx, "CP4-RESET", "家長已遠端解除並重設子女手機")
                    ctx.sendBroadcast(Intent(ACTION_UI_REFRESH).setPackage(ctx.packageName))
                }
            }
            "alert", "captured_message" -> {
                if (role == "parent") {
                    if(msgType=="captured_message") {
                        if(!ConversationCapture.valid(inner) || pendingCapturePolicy(ctx).isEmpty() || inner.optString("policy_id")!=pendingCapturePolicy(ctx)) {
                            if (decoded.optString("type") == "alert_fragment") FamilyEvidence(ctx).use { it.discardFragments(getChannelTopicForToken(token), decoded.optString("event_id")) }
                            return RelayConsumption.REJECTED
                        }
                        prefs(ctx).edit().putString("capture_child_ack_id",inner.getString("policy_id")).apply()
                    }
                    val inserted = recordParentAlert(ctx, inner, "relay:$msgId")
                    sendDeliveryReceipt(ctx,inner.optString("event_id"),inner.optBoolean("receipt_requested"))
                    if (!inserted) return RelayConsumption.CONSUMED
                    // Only a newly stored record, and only one newer than the record that set the stored handle.
                    // A captured message's ts_ms is the message's own time; its handle was read at captured_at_ms (the gate's time field).
                    val childSelf = inner.optString("child_self_handle")
                    if (childSelf.isNotEmpty()) applyChildHandleFromRecord(ctx, childSelf, if (msgType == "captured_message") inner.optLong("captured_at_ms") else msgTs)
                    val reason = inner.optString("reason")
                    // A sign of life: a verified record, or any record of a child without a pinned key (5.2 pairing, not verified).
                    if (reason in setOf("heartbeat_ok", "pairing_verified", "tamper_attempt", "test_alert") && (verified != null || pinned == null)) {
                        recordChildHeartbeatOnParent(ctx, inner.optString("text"), msgTs)
                    }
                    // Records-not-delivered warning: only from a VERIFIED heartbeat (never Legacy), whose fields are inside the signed text.
                    if (verified != null && reason in setOf("heartbeat_ok", "tamper_attempt")) noteDeliveryHealth(ctx, inner, onNewAlertForParent, deliveryClock())
                    val pending = try { JSONObject(prefs(ctx).getString("pending_rotation_json", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
                    if (token == pending.optString("new_token")) prefs(ctx).edit().remove("pending_rotation_json").apply()
                    addCheckpointLog(ctx, "CP4-RECV", "已解密 $reason")
                    if(ConversationCapture.actionable(inner) || reason=="test_alert")onNewAlertForParent?.invoke(inner)
                    ctx.sendBroadcast(Intent(ACTION_UI_REFRESH).setPackage(ctx.packageName))
                }
            }
        }
        // No list of relay delivery ids: the relay makes a new id for every delivery, so it never matched a repeat; each record
        // type has its own repeat check. The remove deletes the list an install of 6.0.2 or earlier left behind.
        val editor=prefs(ctx).edit().remove("seen_msg_ids")
        if(committedControlState!=null)editor.putString("control_state_json",committedControlState)
        // The child's own control-record state is committed here; its first verified record completes the pairing.
        if(verified!=null){verified.newControlState?.let{editor.putString(CHILD_RECORD_STATE,it)};editor.remove(PAIRING_PENDING_MS)}
        check(editor.commit())
        if(controlId.isNotEmpty())FamilyEvidence(ctx).use{db->
            db.controlConsumed(getChannelTopicForToken(token),controlId)
            if(getOrCreateFamilyToken(ctx)!=token)db.controlConsumed(getChannelTopic(ctx),controlId)
        }
        RelayConsumption.CONSUMED
        } catch(e:Exception) {
            cpLastPollStatus="本機未能完成儲存 · ${e.javaClass.simpleName} · 保留中繼記錄並重試"
            RelayConsumption.RETRY
        }

    }

    /** Parent: since when the family relay connection has been continuously ready (0: not ready). In memory on purpose: a new
     *  process starts disconnected, so app start clears it; set on `ready`, cleared on every close or failure (CloudRelay). */
    @Volatile internal var parentRelayReadySince = 0L
    /** Test hook: the parent's clock for the delivery warning (several hours are simulated in tests). */
    @Volatile internal var deliveryClock: () -> Long = System::currentTimeMillis
    internal fun parentRelayReady(ready: Boolean, now: Long = System.currentTimeMillis()) {
        if (!ready) parentRelayReadySince = 0L else if (parentRelayReadySince == 0L) parentRelayReadySince = now
    }
    /** Parent, on a verified heartbeat: one content-free notice at most once an hour while the child reports records that wait over
     *  an hour (or expired) without a receipt, and only after 15 minutes of continuous relay connection here (receipts could flow). */
    private fun noteDeliveryHealth(ctx: Context, record: JSONObject, onNewAlertForParent: ((JSONObject) -> Unit)?, now: Long) {
        val report = DeliveryHealth.parse(record) ?: return
        // Only a heartbeat sent after the last one acted on counts: a delayed or replayed older one neither clears nor raises.
        // Clamped to now as recordChildHeartbeatOnParent does: a heartbeat dated ahead must not switch the check off once the clock is back.
        val sentAt = minOf(record.optLong("ts_ms", 0L), now)
        if (sentAt <= prefs(ctx).getLong(DeliveryHealth.LAST_SENT_MS, 0L)) return
        check(prefs(ctx).edit().putLong(DeliveryHealth.LAST_SENT_MS, sentAt).commit())
        if (report.clear) { check(prefs(ctx).edit().remove(DeliveryHealth.NOTICE_MS).remove(DeliveryHealth.FIRST_BAD_MS).commit()); return }
        // Any heartbeat that is not bad ends the bad run (a later bad one starts a new 3 hours); only a clear one resets the timer.
        if (!report.bad) { check(prefs(ctx).edit().remove(DeliveryHealth.FIRST_BAD_MS).commit()); return }
        val firstBad = prefs(ctx).getLong(DeliveryHealth.FIRST_BAD_MS, 0L)
        if (firstBad == 0L) check(prefs(ctx).edit().putLong(DeliveryHealth.FIRST_BAD_MS, now).commit())
        val since = parentRelayReadySince
        val connected = since != 0L && now - since >= DeliveryHealth.READY_FOR_MS
        // A further bad heartbeat 3 hours into the run raises even if this phone's connection keeps dropping (relay-controlled).
        val longRun = firstBad != 0L && now - firstBad >= DeliveryHealth.LONG_BAD_MS
        if (!connected && !longRun) return
        if (!ChildRecords.tamperNoticeDue(prefs(ctx).getLong(DeliveryHealth.NOTICE_MS, 0L), now)) return
        check(prefs(ctx).edit().putLong(DeliveryHealth.NOTICE_MS, now).commit())
        val notice = JSONObject().put("type", "alert").put("event_id", "delivery:" + UUID.randomUUID()).put("reason", "tamper_attempt")
            .put("sender", DeliveryHealth.NOTICE_SENDER).put("text", DeliveryHealth.NOTICE_TEXT).put("ts_ms", now).put("time", nowDateTimeStr())
        if (recordParentAlert(ctx, notice, notice.getString("event_id"))) onNewAlertForParent?.invoke(notice)
    }

    /** One content-free local evidence row for a refused frame (design 3.5), at most one per hour. Never a sign of life. */
    private fun noteChildRecordTamper(ctx: Context, onNewAlertForParent: ((JSONObject) -> Unit)?, now: Long) {
        if (!ChildRecords.tamperNoticeDue(prefs(ctx).getLong(TAMPER_NOTICE_MS, 0L), now)) return
        check(prefs(ctx).edit().putLong(TAMPER_NOTICE_MS, now).commit())
        val notice = JSONObject().put("type", "alert").put("event_id", "tamper:" + UUID.randomUUID()).put("reason", "tamper_attempt")
            .put("sender", ChildRecords.TAMPER_SENDER).put("text", ChildRecords.TAMPER_TEXT).put("ts_ms", now).put("time", nowDateTimeStr())
        if (recordParentAlert(ctx, notice, notice.getString("event_id"))) onNewAlertForParent?.invoke(notice)
        ctx.sendBroadcast(Intent(ACTION_UI_REFRESH).setPackage(ctx.packageName))
    }

    private fun sendDeliveryReceipt(ctx: Context,eventId: String,requested: Boolean) {
        if(!requested || !isParentMode(ctx) || !eventId.matches(Regex("[A-Za-z0-9:_-]{1,160}")))return
        val token=getOrCreateFamilyToken(ctx);val topic=getChannelTopicForToken(token)
        // A control id (a scores report) is receipted only once stored here; a report refused by the parser never is.
        if(eventId.startsWith("control:") && !FamilyEvidence(ctx).use{it.scoresReportStored(topic,eventId.removePrefix("control:"))})return
        if(!shouldSendAlert("receipt:$topic:$eventId",30_000L))return
        val outcome = FamilyEvidence(ctx).use{if(it.find(topic,eventId)==null && !eventId.startsWith("control:")) "discarded" else "stored"}
        val receipt=JSONObject().put("type","alert_receipt").put("outcome",outcome).put("event_id",eventId).put("ts_ms",System.currentTimeMillis())
        postEncryptedToChannel(ctx,topic,token,receipt.toString(),null)
    }

    private fun loadParentStatistics(ctx: Context): AlertStatistics {
        val saved = prefs(ctx).getString("weekly_stats_json", null)
        val state = try { JSONObject(saved ?: "{}") } catch (_: Exception) { JSONObject() }
        val stats = AlertStatistics(state, System.currentTimeMillis())
        if (!state.optBoolean("initialized")) {
            stats.migrate(getParentAlerts(ctx))
            state.put("initialized", true)
        }
        return stats
    }

    fun getParentStatistics(ctx: Context): AlertStatistics = synchronized(pollLock) {
        val stats = loadParentStatistics(ctx)
        prefs(ctx).edit().putString("weekly_stats_json", stats.state.toString()).apply()
        stats
    }

    fun resetParentStatistics(ctx: Context) = synchronized(pollLock) {
        val stats = loadParentStatistics(ctx)
        stats.reset()
        prefs(ctx).edit().putString("weekly_stats_json", stats.state.toString()).apply()
    }

    private fun legacyEvidenceId(alert: JSONObject): String {
        val canonical = JSONArray().put(alert.optString("reason")).put(alert.optString("sender"))
            .put(alert.optString("text")).put(alert.optLong("ts_ms")).toString()
        return "legacy:" + AlertFrames.digest(canonical.toByteArray(Charsets.UTF_8))
    }
    private fun migrateEvidence(ctx: Context) {
        val legacy = prefs(ctx).getString("parent_alerts_json", null) ?: return
        val entries = try {
            val rows=JSONArray(legacy)
            (0 until rows.length()).map { index -> rows.getJSONObject(index).also { require(it.opt("text") is String) } }
        } catch (_: Exception) {
            listOf(JSONObject().put("type","archive_recovery").put("reason","archive_recovery")
                .put("sender","舊版本資料待復原").put("text",legacy).put("ts_ms",0L).put("time","舊格式資料備份"))
        }
        val state = try { JSONObject(prefs(ctx).getString("weekly_stats_json", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        val seen = state.optJSONObject("seen") ?: JSONObject().also { state.put("seen",it) }
        FamilyEvidence(ctx).use { db ->
            for(entry in entries) {
                val id=entry.optString("event_id").takeIf{it.matches(Regex("[A-Za-z0-9:_-]{1,160}"))} ?: legacyEvidenceId(entry)
                db.append(getChannelTopic(ctx),id,entry,legacy=true)
                if(state.optBoolean("initialized"))seen.put(id,System.currentTimeMillis())
            }
        }
        val editor=prefs(ctx).edit().remove("parent_alerts_json")
        if(state.optBoolean("initialized"))editor.putString("weekly_stats_json",state.toString())
        editor.commit()
    }
    fun prepareParentArchive(ctx: Context) = synchronized(pollLock) {
        if(isParentMode(ctx)){migrateEvidence(ctx);FamilyEvidence(ctx).use{it.pruneExpired(getChannelTopic(ctx))}}
    }
    fun hasUnmigratedEvidence(ctx: Context): Boolean = prefs(ctx).contains("parent_alerts_json")
    fun recordParentAlert(ctx: Context, alertObj: JSONObject, eventId: String): Boolean = synchronized(pollLock) {
        if (!isParentMode(ctx)) return false
        migrateEvidence(ctx)
        val id = alertObj.optString("event_id").takeIf { it.matches(Regex("[A-Za-z0-9:_-]{1,160}")) }
            ?: if (eventId.startsWith("relay:")) legacyEvidenceId(alertObj) else eventId
        val scope = getChannelTopic(ctx)
        FamilyEvidence(ctx).use { db ->
            db.pruneExpired(scope)
            if (db.hasSeen(scope, id) && db.find(scope, id) == null) return false
            val stats = loadParentStatistics(ctx)
            val inserted = db.append(scope, id, alertObj)
            alertObj.put("_id", id)
            // Replaying a committed archive row can finish a statistics write interrupted by process death.
            stats.record(id, alertObj)
            check(prefs(ctx).edit().putString("weekly_stats_json", stats.state.toString()).commit())
            inserted
        }
    }
    fun getParentAlerts(ctx: Context): List<JSONObject> = synchronized(pollLock) {
        if (!isParentMode(ctx)) return emptyList()
        prepareParentArchive(ctx)
        FamilyEvidence(ctx).use { it.messages(getChannelTopic(ctx), EvidenceQuery(filter = "any"), limit = 60) }
    }
    fun clearParentAlerts(ctx: Context): Unit = synchronized(pollLock) {
        if (!isParentMode(ctx)) return
        val statistics=loadParentStatistics(ctx)
        check(prefs(ctx).edit().putString("weekly_stats_json",statistics.state.toString()).commit())
        migrateEvidence(ctx)
        FamilyEvidence(ctx).use { it.deleteArchive(getChannelTopic(ctx)) }
        // Diagnostics must not outlive the evidence they describe.
        prefs(ctx).edit().remove("parent_alerts_json").putString("checkpoint_logs", "").commit()
    }

    fun isNotificationListenerEnabledStatic(ctx: Context): Boolean {
        val name = ComponentName(ctx, IgNotificationListener::class.java)
        return if (Build.VERSION.SDK_INT >= 27) {
            ctx.getSystemService(NotificationManager::class.java)?.isNotificationListenerAccessGranted(name) == true
        } else {
            val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: ""
            flat.split(':').any { ComponentName.unflattenFromString(it) == name }
        }
    }

    fun isAccessibilityEnabledStatic(ctx: Context): Boolean {
        val manager = ctx.getSystemService(AccessibilityManager::class.java) ?: return false
        val name = ComponentName(ctx, IgAccessibilityService::class.java)
        return manager.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { ComponentName.unflattenFromString(it.id) == name }
    }

}

// 1. CHILD ONLY: Intercepts Instagram DM notifications, auto-detects Child IG ID, ignores Child's own outgoing replies, and forwards stranger/grooming alerts to Parent
class IgNotificationListener : NotificationListenerService() {

    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private val periodicCheckRunnable = object : Runnable {
        override fun run() {
            if (GuardEngine.isChildMode(this@IgNotificationListener)) {
                GuardEngine.checkAndSendChildHeartbeatIfDue(this@IgNotificationListener)
                GuardEngine.retryPendingAlerts(applicationContext)
                thread { GuardEngine.pollChannelOnce(applicationContext, since = "all") }
            }
            if (GuardEngine.isChildMode(this@IgNotificationListener)) heartbeatHandler.postDelayed(this, 60_000L)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        if (!GuardEngine.isChildMode(this)) {
            requestUnbind()
            return
        }
        GuardEngine.cpNotifConnected = true
        GuardEngine.retryPendingAlerts(applicationContext,force=true)
        GuardEngine.refreshMonitoringDisclosure(this)
        GuardEngine.addCheckpointLog(this, "CP1-NOTIF", "Instagram 通知讀取已連接")
        heartbeatHandler.removeCallbacks(periodicCheckRunnable)
        heartbeatHandler.postDelayed(periodicCheckRunnable, 15_000L)
    }

    override fun onListenerDisconnected() {
        GuardEngine.cpNotifConnected = false
        heartbeatHandler.removeCallbacks(periodicCheckRunnable)
        GuardEngine.addCheckpointLog(this, "CP1-NOTIF", "通知讀取已中斷")
        if (GuardEngine.isChildMode(this) && Build.VERSION.SDK_INT >= 24) {
            try {
                requestRebind(ComponentName(this, IgNotificationListener::class.java))
            } catch (_: Exception) {
            }
        }
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        heartbeatHandler.removeCallbacksAndMessages(null)
        GuardEngine.cpNotifConnected = false
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (!GuardEngine.isChildMode(this)) return
        if(!GuardEngine.collectionAllowed(this))return
        val observationSession=GuardEngine.captureRelaySession(this)
        GuardEngine.checkAndSendChildHeartbeatIfDue(this)
        if(!GuardEngine.monitoringNoticeAllowed(this))return
        if(RiskLocal.uploadOnly(this))return   // manual upload: nothing in a notification is read

        val pkg = sbn?.packageName ?: return
        if (pkg != GuardEngine.INSTAGRAM_PKG) return
        val notif = sbn.notification ?: return
        val extras = notif.extras ?: return

        GuardEngine.cpNotifConnected = true
        GuardEngine.cpLastNotifTime = GuardEngine.nowTimeStr()

        // Instagram's multi-account prefix names the recipient. A conversation title names the peer.
        val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        var cleanTitle = rawTitle
        if (rawTitle.startsWith("[") && rawTitle.contains("]")) {
            cleanTitle = rawTitle.substringAfter("]").trim().ifEmpty { rawTitle }
        }

        if(GuardEngine.fullCaptureEnabled(this)) {
            captureFullNotification(sbn,extras,observationSession)
            return
        }

        // MessagingStyle provides a display name, which need not be an Instagram username.
        var selfDisplayName = extras.getCharSequence(Notification.EXTRA_SELF_DISPLAY_NAME)?.toString()?.trim() ?: ""
        if (selfDisplayName.isEmpty() && Build.VERSION.SDK_INT >= 28) {
            @Suppress("DEPRECATION")
            val selfPerson = extras.getParcelable<Parcelable>(Notification.EXTRA_MESSAGING_PERSON) as? Person
            selfDisplayName = selfPerson?.name?.toString()?.trim() ?: ""
        }
        val convHeader = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim() ?: ""
        val fallbackBody = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: "").toString().trim()

        var resolvedSender = ""
        var resolvedMessage = ""
        var resolvedTime = sbn.postTime

        val msgBundles = getParcelableArrayCompat(extras, Notification.EXTRA_MESSAGES)
        if (!msgBundles.isNullOrEmpty()) {
            val latestBundle = msgBundles.lastOrNull() as? Bundle
            if (latestBundle != null) {
                resolvedMessage = latestBundle.getCharSequence("text")?.toString()?.trim() ?: ""
                resolvedTime = latestBundle.getLong("time", sbn.postTime)
                var bundleSender = latestBundle.getCharSequence("sender")?.toString()?.trim() ?: ""
                if (bundleSender.isEmpty() && Build.VERSION.SDK_INT >= 28) {
                    @Suppress("DEPRECATION")
                    val person = latestBundle.getParcelable<Parcelable>("sender_person") as? Person
                    bundleSender = person?.name?.toString()?.trim() ?: ""
                }

                if (bundleSender.isNotEmpty()) {
                    if (selfDisplayName.isNotEmpty() && bundleSender.equals(selfDisplayName, ignoreCase = true)) {
                        return
                    }
                    resolvedSender = bundleSender
                } else if (selfDisplayName.isNotEmpty()) {
                    // In Android MessagingStyle, a null/empty sender on a message bundle means the message was sent by the local user (the child)!
                    return
                }
            }
        }

        if (resolvedMessage.isEmpty()) {
            resolvedMessage = fallbackBody
        }

        if (resolvedSender.isEmpty()) {
            val candidateHeader = cleanTitle.ifEmpty { convHeader }
            if (selfDisplayName.isNotEmpty() && candidateHeader.equals(selfDisplayName, ignoreCase = true)) return
            resolvedSender = candidateHeader
        }

        if (resolvedMessage.isEmpty() || resolvedSender.isEmpty()) return

        val tsSig = "$resolvedSender|$resolvedTime|$resolvedMessage"
        if (!GuardEngine.isNewNotificationMessageTimestamp(tsSig)) {
            return
        }

        if(GuardEngine.captureRelaySession(this)!=observationSession)return
        val reason = GuardEngine.evaluateMessage(this, resolvedSender, resolvedMessage)
        if (reason == null) {
            GuardEngine.addCheckpointLog(this, "CP2-TRUSTED", "已略過信任對象、自己或狀態訊息")
            return
        }

        GuardEngine.sendAlertFromChild(
            this,
            reason = reason,
            sender = resolvedSender,
            text = resolvedMessage,
            fromNotification = true,
            expectedSession = observationSession
        )
    }

    private fun captureFullNotification(sbn:StatusBarNotification,extras:Bundle,observationSession:Triple<String,String,Int>) {
        @Suppress("DEPRECATION")
        val self=extras.getCharSequence(Notification.EXTRA_SELF_DISPLAY_NAME)?.toString()?.trim().orEmpty().ifEmpty {
            if(Build.VERSION.SDK_INT>=28)(extras.getParcelable<Parcelable>(Notification.EXTRA_MESSAGING_PERSON) as? Person)?.name?.toString()?.trim().orEmpty() else ""
        }
        val rawTitle=extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val title=if(rawTitle.startsWith("[") && rawTitle.contains("]"))rawTitle.substringAfter("]").trim() else rawTitle
        val account=GuardEngine.notificationRecipient(rawTitle)
        val conversationKind=if(extras.containsKey(Notification.EXTRA_IS_GROUP_CONVERSATION)) {
            if(extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION))"group" else "direct"
        } else "unknown"
        fun own(label:String)=self.isNotEmpty() && label.equals(self,true)
        fun authorOf(b:Bundle):String {
            @Suppress("DEPRECATION")
            val person=if(Build.VERSION.SDK_INT>=28)(b.getParcelable<Parcelable>("sender_person") as? Person)?.name?.toString()?.trim().orEmpty() else ""
            return person.ifEmpty{b.getCharSequence("sender")?.toString()?.trim().orEmpty()}
        }
        val bundles=getParcelableArrayCompat(extras,Notification.EXTRA_MESSAGES)
        val header=extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim().orEmpty()
        val conversation=if(conversationKind=="group")header.ifEmpty{title} else {
            val peers=bundles.orEmpty().mapNotNull{it as? Bundle}.map(::authorOf).filter{it.isNotBlank() && !own(it)}.distinctBy{it.lowercase(Locale.ROOT)}
            // MessagingStyle can format EXTRA_TITLE as "account: sender"; it is presentation, not a thread identity.
            header.takeIf{it.isNotBlank() && !it.equals("Instagram",true) && !own(it)} ?:
                peers.singleOrNull() ?: if(peers.isEmpty())title.takeIf{it.isNotBlank() && !it.equals("Instagram",true) && !own(it) &&
                    it.split(':','：').none{part->own(part.trim())}}.orEmpty() else ""
        }
        if(conversation.isBlank())return
        val observed=mutableListOf<CapturedText>()
        val occurrences=mutableMapOf<String,Int>()
        for(bundle in bundles.orEmpty()) {
            val b=bundle as? Bundle ?: continue
            val text=b.getCharSequence("text")?.toString().orEmpty()
            if(text.isBlank())continue
            val author=authorOf(b)
            val outgoing=author.isEmpty() || own(author)
            val direction=if(outgoing)"outgoing" else "incoming"
            val stamp=b.getLong("time",0)
            val identity=JSONArray().put(sbn.key).put(if(stamp>0)stamp else sbn.postTime).put(author).put(text).toString()
            val occurrence=occurrences.getOrDefault(identity,0);occurrences[identity]=occurrence+1
            observed.add(CapturedText(conversation,text,direction,if(outgoing)account.ifEmpty{self.ifEmpty{"子女"}} else author,
                account,"notification",stamp,"$identity:$occurrence",stamp<=0,conversationKind))
        }
        if(bundles.isNullOrEmpty()) {
            val text=(extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?:extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
            val direction=if(title.isNotBlank() && !title.equals("Instagram",true) && !own(title))"incoming" else "unknown"
            if(text.isNotBlank() && !GuardEngine.NON_MESSAGE_UI_PATTERN.matches(text.trim()) && !GuardEngine.isNotificationSummary(text))observed.add(CapturedText(conversation,text,direction,if(direction=="incoming")title else "",account,
                "notification",0,JSONArray().put(sbn.key).put(sbn.postTime).put(text).toString(),true,conversationKind))
        }
        observed.chunked(128).forEach{GuardEngine.captureMessages(this,it,expectedSession=observationSession)}
    }

    @Suppress("DEPRECATION")
    private fun getParcelableArrayCompat(extras: Bundle, key: String): Array<Parcelable>? {
        return try {
            extras.getParcelableArray(key)
        } catch (_: Exception) {
            null
        }
    }
}

// 2. CHILD ONLY: Context-Gated Instagram DM Reader (composer contents excluded)
// Issue #3 & #4 Fix: Only hooks com.instagram.android (no OS Settings hooking) and NEVER scans middle-of-screen feed/reel texts!
class IgAccessibilityService : AccessibilityService() {

    private var lastScanMs = 0L
    private var lastRemoteSyncMs = 0L
    private var lastCapturedBottomMsg = ""
    private var lastKeywordSig = ""
    private var pendingScan:Runnable? = null

    private val debounceHandler = Handler(Looper.getMainLooper())
    private data class ScreenNodeItem(
        val text: String,
        val hintText: String,
        val top: Int,
        val bottom: Int,
        val left: Int,
        val right: Int,
        val isEditable: Boolean,
        val isFocused: Boolean,
        val rawText:String=text,
        val fromText:Boolean=true,
        val children:Int=0,
        val viewId:String="",val button:Boolean=false,val heading:Boolean=false,
        val direction:String="unknown",val bubbleLeft:Int=left,val bubbleRight:Int=right
    ) {
        fun visible()=VisibleCaptureText(rawText,top,bottom,left,right,isEditable,fromText,children,direction,viewId,button,heading,bubbleLeft,bubbleRight)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (GuardEngine.getRole(this) != "child") {
            disableSelf()
            return
        }
        if (!AppConsent.hasAccepted(this)) return
        GuardEngine.cpAccConnected = true
        GuardEngine.refreshMonitoringDisclosure(this)
        GuardEngine.scheduleNextRandomHeartbeat(this)
        GuardEngine.addCheckpointLog(this, "CP1-ACC", "Instagram 畫面保護已連接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!GuardEngine.isChildMode(this)) return
        if(!GuardEngine.collectionAllowed(this))return
        val observationSession=GuardEngine.captureRelaySession(this)

        val fullCapture=GuardEngine.fullCaptureEnabled(this)

        val pkg = event?.packageName?.toString() ?: return
        if (pkg != GuardEngine.INSTAGRAM_PKG) return

        val now = System.currentTimeMillis()
        val scanNow=SystemClock.uptimeMillis()
        if (event.eventType in setOf(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,AccessibilityEvent.TYPE_VIEW_SCROLLED) && scanNow-lastScanMs<450L) {
            pendingScan?.let{debounceHandler.removeCallbacks(it)}
            pendingScan=Runnable {
                pendingScan=null
                if(GuardEngine.captureRelaySession(this)==observationSession) {
                    val retry=AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply{packageName=GuardEngine.INSTAGRAM_PKG}
                    try{onAccessibilityEvent(retry)}finally{@Suppress("DEPRECATION") retry.recycle()}
                }
            }.also{debounceHandler.postDelayed(it,(450L-(scanNow-lastScanMs)).coerceAtLeast(1))}
            return
        }
        pendingScan?.let{debounceHandler.removeCallbacks(it)};pendingScan=null
        lastScanMs = scanNow
        // After the 450 ms throttle, never per raw event: these checks write sealed settings, and per event they froze a real phone.
        // The heartbeat and relay poll run before the upload gate: a phone that reads nothing must still report in and hear the parent.
        GuardEngine.checkAndSendChildHeartbeatIfDue(this)
        if (now - lastRemoteSyncMs > 45_000L) {
            lastRemoteSyncMs = now
            thread { GuardEngine.pollChannelOnce(applicationContext, since = "all") }
        }
        if(!GuardEngine.monitoringNoticeAllowed(this)){GuardEngine.cpLastCaptureStatus="監察提示通知已關閉，已暫停擷取";return}
        if(RiskLocal.uploadOnly(this))return   // manual upload: the Instagram screen is not read
        GuardEngine.cpAccConnected = true
        GuardEngine.cpLastAccTime = GuardEngine.nowTimeStr()

        val root = rootInActiveWindow ?: run{GuardEngine.cpLastCaptureStatus="Instagram 尚未提供可讀取的前景視窗";return}
        if(root.packageName?.toString()!=GuardEngine.INSTAGRAM_PKG){GuardEngine.cpLastCaptureStatus="目前前景視窗不是 Instagram";return}

        val metrics = resources.displayMetrics
        val rootBounds=Rect();root.getBoundsInScreen(rootBounds)
        val screenHeight = rootBounds.height().takeIf{it>0}?:metrics.heightPixels.coerceAtLeast(1)
        val screenWidth = rootBounds.width().takeIf{it>0}?:metrics.widthPixels.coerceAtLeast(1)

        val items = ArrayList<ScreenNodeItem>(90)
        collectScreenNodes(root,items,0,rootBounds,intArrayOf(0))
        if (items.isEmpty()){GuardEngine.cpLastCaptureStatus="Instagram 未提供可讀取的文字節點";return}

        // A named or semantic DM composer remains valid when a keyboard raises it above mid-screen.
        val dmComposerNode = items.firstOrNull { item ->
            item.top>=0 && (item.isEditable && (ConversationCapture.composerLabel(item.text) || ConversationCapture.composerLabel(item.hintText)) ||
                ConversationCapture.composerRole(item.viewId) || !fullCapture && item.isEditable && item.top>=screenHeight*.72)
        }

        // Profile controls quoted inside a DM are message text, not proof of the child's identity.
        val isOwnProfileScreen = items.any { item ->
            val t = item.text.trim()
            t.equals("Edit profile", ignoreCase = true) ||
                t == "編輯個人檔案" ||
                t.equals("Share profile", ignoreCase = true) ||
                t == "分享個人檔案"
        }
        if (isOwnProfileScreen && dmComposerNode==null && items.none{it.isEditable}) {
            val topHandleNode = items.firstOrNull { item ->
                val clean = item.text.trim().removePrefix("@")
                !item.isEditable &&
                    item.bottom <= (screenHeight * 0.16).toInt() &&
                    clean.length in 2..30 &&
                    !clean.contains(" ") &&
                    !clean.all { it.isDigit() } &&
                    clean.lowercase(Locale.US) !in GuardEngine.RESERVED_UI_HEADERS
            }
            if (topHandleNode != null) {
                GuardEngine.updateAutoDetectedChildHandle(this, topHandleNode.text)
            }
            return
        }

        val header=ConversationCapture.threadHeader(items.map{it.visible()}.filterNot{GuardEngine.isChildSelfHandle(this,it.text)},screenHeight)
        val topHeaderSender=header?.text?.trim()?.removePrefix("@").orEmpty()

        // Check if bottom navigation bar (Home/Search/Reels/Profile tabs) is visible -> If visible, user is on Feed/Reels/Explore/Profile, NOT inside a DM chat!
        val hasBottomNavTabs = items.count { item ->
            item.top >= (screenHeight * 0.88).toInt() &&
                item.text.trim().lowercase(Locale.US) in setOf("home", "search", "explore", "reels", "profile", "首頁", "搜尋", "探索", "連續短片", "個人檔案")
        } >= 2

        // Check if Story / Post Comments sheet is open
        fun isReplyComposer(item:ScreenNodeItem):Boolean {
            val hint=item.hintText.trim()
            return item.isEditable && (hint.startsWith("Add a comment",true) || hint.startsWith("新增留言") ||
                hint.startsWith("Reply",true) || hint.startsWith("回覆"))
        }
        val isCommentsOrStoryReplySheet = items.any { item ->
            val t = item.text.trim()
            isReplyComposer(item) || (dmComposerNode==null && item.bottom<=(screenHeight*.3).toInt() &&
                (t.equals("Comments",true) || t=="留言"))
        }

        // Comments/story composers are never a source of captured content.
        if (isCommentsOrStoryReplySheet) return

        // ================= CONTEXT 2: STRICTLY INSIDE A 1-ON-1 / GROUP DM CHAT THREAD =================
        val isStrictlyInsideDmChat = !hasBottomNavTabs && dmComposerNode != null && topHeaderSender.isNotEmpty() && dmComposerNode.top>header!!.bottom
        if (!isStrictlyInsideDmChat) {
            GuardEngine.cpLastCaptureStatus=when{hasBottomNavTabs->"目前是動態／導覽頁，沒有擷取對話";dmComposerNode==null->"未識別訊息輸入框 · 可讀節點 ${items.size}";topHeaderSender.isEmpty()->"未能確認對話對象 · 可讀節點 ${items.size}";else->"對話標題與輸入框位置不一致"}
            // Issue #4 Fix: User is scrolling Home Feed, Reels, Explore, Stories, or DM Inbox list -> DO NOT read middle-of-screen texts!
            return
        }
        if(GuardEngine.captureRelaySession(this)!=observationSession)return

        if(fullCapture) {
            val visible=ConversationCapture.visibleMessages(items.map{it.visible()},
                screenWidth,screenHeight,header!!.bottom,dmComposerNode!!.top)
            // The DM header identifies the peer, not which of the child's accounts is active.
            val account=""
            val snapshots=visible.map{CapturedText(topHeaderSender,it.text,it.direction,
                if(it.direction=="outgoing")account.ifEmpty{"子女"} else "",account,"visible_dm",labelFromToolbar=true)}
            val accepted=GuardEngine.captureMessages(this,snapshots,ConversationCapture.key(account,topHeaderSender),expectedSession=observationSession)
            GuardEngine.cpLastCaptureStatus="雙向模式 · 可見訊息 ${visible.size} 則 · 本輪保留 ${accepted.size} 則（重複內容不重存）"
            return
        }

        GuardEngine.cpLastCaptureStatus="僅傳入警報模式 · 雙向範圍尚未確認"

        // Read incoming left-aligned bubbles between the confirmed header and composer, including above a keyboard:
        // - Left edge must start near the left margin (<= 24% width)
        // - Right edge must NOT reach the right-aligned outgoing margin (<= 82% width)
        // - Horizontal midpoint must sit on the left half of the screen (centerX <= 50% width)
        val dmZoneTop = header!!.bottom
        val dmZoneBottom = (dmComposerNode!!.top - 10).coerceAtMost((screenHeight * 0.84).toInt())
        val maxLeftEdge = (screenWidth * 0.24).toInt()
        val maxRightEdge = (screenWidth * 0.82).toInt()
        val maxCenterX = (screenWidth * 0.50).toInt()

        val incomingLeftBubbles = ConversationCapture.visibleMessages(items.map{it.visible()},screenWidth,screenHeight,dmZoneTop,dmZoneBottom).filter { item ->
            val centerX = (item.left + item.right) / 2
            item.direction=="incoming" &&
                item.top >= dmZoneTop &&
                item.bottom <= dmZoneBottom &&
                item.left in 8..maxLeftEdge &&
                item.right <= maxRightEdge &&
                centerX <= maxCenterX &&
                !GuardEngine.isNonMessageUiString(item.text) &&
                !GuardEngine.isChildSelfHandle(this, item.text) &&
                !item.text.trim().removePrefix("@").equals(topHeaderSender, ignoreCase = true)
        }.sortedBy { it.top }

        if (incomingLeftBubbles.isEmpty()) return

        // Check for sexual/grooming keywords on incoming left-aligned DM bubbles
        val sexualHit = incomingLeftBubbles.firstOrNull { GuardEngine.SEXUAL_PATTERN.containsMatchIn(it.text) }
        if (sexualHit != null) {
            val keywordSig = "$topHeaderSender|${sexualHit.text}"
            if (keywordSig != lastKeywordSig) {
                lastKeywordSig = keywordSig
                GuardEngine.sendAlertFromChild(
                    this,
                    reason = "sexual_or_grooming_content",
                    sender = topHeaderSender,
                    text = sexualHit.text,
                    expectedSession=observationSession
                )
            }
        }

        // Check latest incoming left-aligned DM bubble from stranger
        val newestIncoming = incomingLeftBubbles.last().text
        val sig = "$topHeaderSender|$newestIncoming"
        if (sig != lastCapturedBottomMsg) {
            lastCapturedBottomMsg = sig
            val reason = GuardEngine.evaluateMessage(this, topHeaderSender, newestIncoming)
            if (reason != null) {
                GuardEngine.sendAlertFromChild(
                    this,
                    reason = reason,
                    sender = topHeaderSender,
                    text = newestIncoming,
                    expectedSession=observationSession
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun collectScreenNodes(node: AccessibilityNodeInfo?, out: MutableList<ScreenNodeItem>, depth: Int,
        origin:Rect,visited:IntArray,editableAncestor:Boolean=false,direction:String="unknown",bubble:Rect?=null) {
        if (node == null || visited[0]++ >= 2048 || depth > 64) return
        try {
            if (!node.isVisibleToUser) return
            val id=node.viewIdResourceName.orEmpty()
            val editable=editableAncestor || node.isEditable || ConversationCapture.composerRole(id)
            // Composer text and descriptions can contain unsent private composition.
            val text=if(editable) "" else node.text?.toString().orEmpty()
            val rawText = if(editable) "" else text.takeIf{it.isNotBlank()}?:node.contentDescription?.toString().orEmpty()
            val rawHint = if (Build.VERSION.SDK_INT >= 26) (node.hintText?.toString()?.trim() ?: "") else ""
            val rect=Rect();node.getBoundsInScreen(rect);rect.offset(-origin.left,-origin.top)
            val ownDirection=ConversationCapture.semanticDirection(id).takeUnless{it=="unknown"}?:direction
            val ownBubble=if(ConversationCapture.messageRole(id) && node.childCount>0 && rect.width()>0 && rect.width()<origin.width())rect else bubble
            val ownIndex=out.size
            if (rawText.isNotEmpty() || editable) {
                out.add(
                    ScreenNodeItem(
                        text = rawText.trim(),
                        hintText = rawHint,
                        top = rect.top,
                        bottom = rect.bottom,
                        left = rect.left,
                        right = rect.right,
                        isEditable = editable,
                        isFocused = node.isFocused,
                        rawText=rawText,
                        fromText=text.isNotBlank(),
                        children=node.childCount,
                        viewId=id,button=node.className?.toString()?.contains("Button")==true,
                        heading=Build.VERSION.SDK_INT>=28 && node.isHeading,
                        direction=ownDirection,bubbleLeft=ownBubble?.left?:rect.left,bubbleRight=ownBubble?.right?:rect.right
                    )
                )
            }
            // Keep only layout, role and hint metadata; never traverse composer children.
            if(editable) return
            val count = node.childCount
            val childStart=out.size
            for (i in 0 until count) {
                if(visited[0]>=2048)break
                val child = node.getChild(i) ?: continue
                try {
                    collectScreenNodes(child,out,depth+1,origin,visited,editable,ownDirection,ownBubble)
                } finally {
                    if (Build.VERSION.SDK_INT < 33) {
                        try { child.recycle() } catch (_: Exception) {}
                    }
                }
            }
            // Do not retain aggregate text from a container that also contains a composer.
            if(ownIndex<childStart && out.subList(childStart,out.size).any{it.isEditable}) {
                out[ownIndex]=out[ownIndex].copy(text="",rawText="",fromText=false)
            }
            // Some versions expose the message on a container with only decorative children.
            if(ownIndex<childStart && ConversationCapture.messageRole(id) &&
                out.subList(childStart,out.size).none{!it.isEditable && !it.button && (it.fromText || ConversationCapture.messageRole(it.viewId))}) {
                out[ownIndex]=out[ownIndex].copy(children=0)
            }
        } catch (_: Exception) {
        }
    }

    override fun onInterrupt() {
        debounceHandler.removeCallbacksAndMessages(null)
        pendingScan=null
    }

    override fun onDestroy() {
        onInterrupt()
        GuardEngine.cpAccConnected = false
        super.onDestroy()
    }
}

// 3. PARENT ONLY: Persistent Encrypted TLS Stream Receiver + Child Offline Watchdog
class ParentAlertService : Service() {

    companion object {
        @Volatile private var activeService:ParentAlertService?=null
        fun reconnectActiveStream() {
            val service=activeService ?: return
            CloudRelay.disconnect()
            if(GuardEngine.isParentMode(service))CloudRelay.connect(service.applicationContext){service.fireNativeParentNotification(it)}
        }
    }
    @Volatile private var running=false
    private val watchdogHandler=Handler(Looper.getMainLooper())
    private val watchdog=object:Runnable {
        override fun run() {
            if(!running || !GuardEngine.isParentMode(this@ParentAlertService))return
            checkHeartbeatMissing()
            if(GuardEngine.expireUnconfirmedChildPairing(applicationContext))sendBroadcast(Intent(GuardEngine.ACTION_UI_REFRESH).setPackage(packageName))
            CloudRelay.connect(applicationContext){fireNativeParentNotification(it)}
            GuardEngine.retryPendingAlerts(applicationContext)
            watchdogHandler.postDelayed(this,60_000L)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!GuardEngine.isParentMode(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        ensureChannels()
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val fgNotif = Notification.Builder(this, "ig_guard_monitor")
            .setContentTitle("Convoy · 家長守護連線中")
            .setContentText(if (GuardEngine.pinnedChildKey(this) != null) "已核實配對 · 加密即時守護" else "核對圖案：${GuardEngine.getSafetyEmojis(this)} · 加密即時守護")
            .setSmallIcon(GuardEngine.notificationIcon(this)).setColor(GuardEngine.NOTIFICATION_COLOR)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, fgNotif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, fgNotif)
        }

        if(!running){running=true;activeService=this;watchdogHandler.post(watchdog)}
        return START_STICKY
    }

    private fun checkHeartbeatMissing() {
        if (!GuardEngine.isParentMode(applicationContext)) return
        if (GuardEngine.shouldFireHeartbeatMissingAlert(applicationContext)) {
            val missingAlert = JSONObject().apply {
                put("reason", "tamper_attempt")
                put("ts_ms", System.currentTimeMillis())
                put("sender", "子女手機離線/解除安裝警告")
                put("text", "已超過 100 分鐘未收到子女手機的定時心跳回報！子女可能已熄機、斷網或解除安裝 Convoy。")
                put("time", GuardEngine.nowDateTimeStr())
            }
            if (GuardEngine.recordParentAlert(applicationContext, missingAlert, "offline:${missingAlert.optLong("ts_ms")}")) {
                fireNativeParentNotification(missingAlert)
                sendBroadcast(Intent(GuardEngine.ACTION_UI_REFRESH).setPackage(packageName))
            }
        }
    }

    private fun ensureChannels() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val monitorCh = NotificationChannel(
            "ig_guard_monitor",
            "Parent Background Connection",
            NotificationManager.IMPORTANCE_LOW
        )
        val alertCh = NotificationChannel(
            "ig_guard_alerts",
            "Child Safety Alerts (Urgent)",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            enableVibration(true)
        }
        nm.createNotificationChannel(monitorCh)
        nm.createNotificationChannel(alertCh)
    }

    private fun fireNativeParentNotification(alert: JSONObject) {
        if(!ConversationCapture.actionable(alert) && alert.optString("reason")!="test_alert")return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val reason = alert.optString("reason", "alert")
        val scope = GuardEngine.getChannelTopic(this)
        val eventId = alert.optString("_id").ifEmpty { alert.optString("event_id") }
        val conversation=alert.optString("conversation_key")
        val tag=if(reason=="scores_threshold")"$scope:scores" else if(alert.optString("type")=="captured_message" && conversation.isNotEmpty())"$scope:conversation:$conversation" else "$scope:$eventId"
        val previous=nm.activeNotifications.firstOrNull{it.tag==tag}?.notification
        val priority=ConversationCapture.priority(alert)
        val elevate=previous==null || priority>previous.extras.getInt("ig_review_priority",0)
        val open = if (reason == "scores_threshold") ScoresLink.intent(this, scope)
            else Intent(this, MainActivity::class.java).setAction("app.family.igguard.OPEN_EVIDENCE")
                .setData(Uri.Builder().scheme("igguard").authority("evidence").appendPath(scope).appendPath(eventId).build())
        val openIntent = PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val generic = when(reason) {
            "test_alert" -> "已收到子女手機的加密測試。可在裝置與傳送查看記錄。"
            "sexual_or_grooming_content" -> "偵測到需留意的關鍵字。開啟查看記錄及尋求協助建議。"
            "scores_threshold" -> "有對話的風險分數已達 50 或以上。開啟 App 查看分數。"
            else -> "有新的家庭提醒，請開啟 App 查看。"
        }
        val publicNotice = Notification.Builder(this, "ig_guard_alerts")
            .setContentTitle("Convoy · 家庭提醒").setContentText("解鎖後開啟 App 查看。")
            .setSmallIcon(GuardEngine.notificationIcon(this)).setColor(GuardEngine.NOTIFICATION_COLOR).build()
        val notif = Notification.Builder(this, "ig_guard_alerts")
            .setContentTitle(if(reason == "sexual_or_grooming_content") "Convoy · 有一則需要留意的對話" else "Convoy · 家庭提醒")
            .setContentText(generic).setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(publicNotice)
            .setOnlyAlertOnce(!elevate).addExtras(Bundle().apply{putInt("ig_review_priority",maxOf(priority,previous?.extras?.getInt("ig_review_priority",0)?:0))})
            .setSmallIcon(GuardEngine.notificationIcon(this)).setColor(GuardEngine.NOTIFICATION_COLOR).setContentIntent(openIntent).setAutoCancel(true).build()
        nm.notify(tag, 100, notif)
    }

    override fun onDestroy() {
        running=false;watchdogHandler.removeCallbacks(watchdog)
        if(activeService===this)activeService=null
        if(GuardEngine.isParentMode(this))CloudRelay.disconnect()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

/** Fill for controls that draw only a ripple (the nav tabs sit on the bar's own highlight). */
private const val TRANSPARENT_FILL = "#00000000"

// Native family UI. Monitoring, cryptography and pairing remain in the existing engine.
class MainActivity : Activity() {
    private val parentAccess = ParentAccessSession()
    private var parentAuthSession: Triple<String,String,Int>? = null
    private var parentAuthPending = false
    private var parentAuthFresh = false
    /** Identifies the current BiometricPrompt request so a callback from a cancelled one is ignored. */
    private var parentAuthTicket = 0
    private var parentAuthAction: (() -> Unit)? = null
    private var parentAuthSignal: android.os.CancellationSignal? = null
    private var deferredParentIntent: Intent? = null
    private val parentAuthRequest = 9031
    private fun parentUnlocked(): Boolean = !GuardEngine.isParentMode(this) || parentAccess.isUnlocked(GuardEngine.captureRelaySession(this))

    private fun renderParentLock() {
        if(isDestroyed)return
        themeReveal?.end() // the reveal's snapshot of the old parent screen sits on the decor overlay, above this lock
        val t=currentTheme()
        val box=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;setPadding(dp(24),dp(48),dp(24),dp(24));tag="parent_privacy_lock"
            isSaveFromParentEnabled=false;setBackgroundColor(color(t.bgCanvas))
        }
        box.addView(label("家長資料已鎖定\nParent data is locked",27f,t.textPrimary,true))
        box.addView(label("使用手機的生物辨識或螢幕鎖驗證後查看。背景接收仍會繼續；請勿與其他人共用手機解鎖憑證。\nAuthenticate with your device lock to view family records. Background delivery continues.",15f,t.textSecondary,topPad=16,bottomPad=24))
        box.addView(Button(this).apply {text="驗證並開啟 / Authenticate";tag="parent_authenticate";actionLook(this,t.btnPrimaryBg,t.btnPrimaryText,t);setOnClickListener{requestParentAuthentication()}})
        box.addView(Button(this).apply {text="設定手機螢幕鎖 / Set device lock";actionLook(this,t.btnSecondaryBg,t.btnSecondaryText,t);setOnClickListener{
            try{startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))}catch(_:Exception){Toast.makeText(this@MainActivity,"請在 Android 設定啟用螢幕鎖。",Toast.LENGTH_LONG).show()}
        }})
        setContentView(box)
    }
    /** Secrets and destructive actions need a device authentication performed right now, not just an open session. */
    private fun requireFreshParentAuth(action: () -> Unit) {
        if(!GuardEngine.isParentMode(this)){action();return}
        requestParentAuthentication(action,fresh=true)
    }
    /** A fresh device authentication that is not tied to the parent role (used to remove leftover parent data). */
    private fun requireFreshDeviceAuth(action: () -> Unit) = requestParentAuthentication(action, fresh = true, anyRole = true)
    private fun requestParentAuthentication(action: (() -> Unit)? = null, fresh: Boolean = false, anyRole: Boolean = false) {
        if((!GuardEngine.isParentMode(this) && !anyRole) || parentAuthPending)return
        val keyguard=getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        if(keyguard?.isDeviceSecure != true){
            if(GuardEngine.isParentMode(this)){Toast.makeText(this,"請先設定 Android 螢幕鎖。",Toast.LENGTH_LONG).show();renderParentLock()}
            else {
                // Not a parent screen: explain and stay where we are instead of showing a dead lock screen.
                alert().setTitle("需要手機螢幕鎖").setMessage("要確認這個操作，請先在 Android 設定螢幕鎖，然後再試一次。")
                    .setNegativeButton("知道了",null)
                    .setPositiveButton("開啟安全設定"){_,_->try{startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))}catch(_:Exception){}}.show()
            }
            return
        }
        parentAuthSession=GuardEngine.captureRelaySession(this);parentAuthAction=action;parentAuthPending=true;parentAuthFresh=fresh
        try {
            if(Build.VERSION.SDK_INT>=30) {
                val signal=android.os.CancellationSignal();parentAuthSignal=signal;val ticket=++parentAuthTicket
                android.hardware.biometrics.BiometricPrompt.Builder(this).setTitle(if(fresh)"再次驗證以繼續" else "開啟 Convoy 家長資料")
                    .setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG or android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                    .build().authenticate(signal,mainExecutor,object:android.hardware.biometrics.BiometricPrompt.AuthenticationCallback(){
                        override fun onAuthenticationSucceeded(result:android.hardware.biometrics.BiometricPrompt.AuthenticationResult){if(ticket==parentAuthTicket)completeParentAuthentication(true)}
                        override fun onAuthenticationError(code:Int,message:CharSequence){if(ticket==parentAuthTicket)completeParentAuthentication(false)}
                    })
            } else {
                val request=keyguard.createConfirmDeviceCredentialIntent(if(fresh)"再次驗證以繼續" else "Convoy 家長資料",if(fresh)"驗證後才會執行此操作。" else "驗證後查看家庭記錄及配對設定。")
                    ?: throw SecurityException("Device authentication is unavailable")
                startActivityForResult(request,parentAuthRequest)
            }
        } catch(_:Exception){completeParentAuthentication(false)}
    }
    private fun clearParentAuthRequest() { parentAuthPending=false;parentAuthSession=null;parentAuthAction=null;parentAuthSignal=null;parentAuthFresh=false }
    private fun completeParentAuthentication(success:Boolean) {
        val expected=parentAuthSession;val action=parentAuthAction;val fresh=parentAuthFresh
        if(fresh && !GuardEngine.isParentMode(this)){
            // Authentication for a non-parent action (leftover parent data): run it only for the same, still-unconfigured session.
            parentAuthPending=false;parentAuthSession=null;parentAuthAction=null;parentAuthSignal=null;parentAuthFresh=false
            if(isDestroyed)return
            if(success && expected!=null && expected==GuardEngine.captureRelaySession(this))action?.invoke()
            else Toast.makeText(this,"驗證未完成，操作已取消。",Toast.LENGTH_SHORT).show()
            return
        }
        parentAuthPending=false;parentAuthSession=null;parentAuthAction=null;parentAuthSignal=null;parentAuthFresh=false
        if(isDestroyed)return
        if(!success || expected==null || expected!=GuardEngine.captureRelaySession(this) || !GuardEngine.isParentMode(this)){
            // A cancelled re-check only cancels the action; the already authenticated session stays as it was.
            if(fresh && expected!=null && expected==GuardEngine.captureRelaySession(this) && GuardEngine.isParentMode(this) && parentAccess.isUnlocked(expected)){
                Toast.makeText(this,"驗證未完成，操作已取消。",Toast.LENGTH_SHORT).show();return
            }
            parentAccess.lock();renderUi();return
        }
        parentAccess.authenticated(expected)
        if(fresh){action?.invoke();return}
        renderUi()
        val incoming=deferredParentIntent;deferredParentIntent=null
        if(incoming!=null)handleDeepLinkIntent(incoming)
        openPendingEvidence();action?.invoke()
    }
    @Deprecated("Native device credential result")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==parentAuthRequest && parentAuthPending)completeParentAuthentication(resultCode==RESULT_OK)
        if(requestCode==exportPickRequest && resultCode==RESULT_OK)data?.data?.let{startExportImport(it)}
        if(requestCode==modelPickRequest) {
            // A picker result counts only if the parent's password was accepted before the picker was opened.
            val authorized=modelPickAuthorized;modelPickAuthorized=false
            if(authorized && resultCode==RESULT_OK)data?.data?.let{startModelImport(it)}
        }
    }
    private lateinit var scrollView: ScrollView
    private lateinit var rootContainer: LinearLayout
    private lateinit var screenShell: LinearLayout
    private lateinit var fixedHeader: LinearLayout
    private lateinit var contentFrame: FrameLayout
    private lateinit var bottomNav: LinearLayout
    private lateinit var statusText: TextView
    private var section = "role"
    private var renderedRole = ""
    private var renderedSession: Triple<String, String, Int>? = null
    private var selectedEntryRole = ""
    private var chartMode = "trend"
    private var alertFilter = "all"
    private var evidenceSearch = ""
    private var evidenceSender: String? = null
    private var evidenceConversationKey:String?=null
    private var evidenceSection="conversations"
    private var evidenceReview="new"
    private var evidenceDirection="all"
    private var lastCapturePrompt=""
    private var refreshPending=false
    private var evidencePage = 0
    private val evidenceCursors = mutableMapOf<Int,EvidenceCursor>()
    private var evidenceDays = 0
    private var evidenceSaved = false
    private var pendingEvidenceId: String? = null
    private val inputDrafts = mutableMapOf<String, String>()
    private val uiHandler = Handler(Looper.getMainLooper())
    private var activityVisible = false
    private var displayedChartDate = ""
    private var chartZone = ZoneId.systemDefault()
    private var displayed15MinCode = ""
    private var lastRenderedSection = ""
    private var lastRenderedPage = ""
    private var pageMotion: ValueAnimator? = null
    private var navShown = "" // "role/destination" of the highlighted tab; "" while the bar is hidden
    private var navPillX = 0f // the highlight's last drawn left edge, px inside the bar
    private var navMotion: ValueAnimator? = null
    private var unlockTimerText: TextView? = null
    private val drawableIds = mutableMapOf<String, Int>()
    private val fonts = mutableMapOf<Boolean, Typeface>()
    private val statusTicker = object : Runnable {
        override fun run() {
            if (!activityVisible) return
            updateStatus()
            maybeShowSafetyAdvice()
            maybeShowCaptureNotice()
            if (section == "overview" && GuardEngine.isParentMode(this@MainActivity) &&
                Instant.now().atZone(chartZone).toLocalDate().toString() != displayedChartDate) renderUi()
            // Keep the request's time left current; once the lock ends, one re-render enables the button (the line is then gone).
            if (section == "scores") window.decorView.findViewWithTag<TextView>("scores_import_request_reset")?.let { line ->
                ScoresUi.importRequestReset(RiskLocal.parentImportRequestAt(this@MainActivity))?.let { line.text = it } ?: renderUi()
            }
            uiHandler.postDelayed(this, 5_000L)
        }
    }

    internal data class ThemeColors(
        val bgCanvas: String, val bgCard: String, val bgHero: String, val bgInput: String,
        val bgChip: String, val borderSubtle: String, val textPrimary: String,
        val textSecondary: String, val textMuted: String, val accentSeafoam: String,
        val accentPowderSky: String, val btnPrimaryBg: String, val btnPrimaryText: String,
        val btnSecondaryBg: String, val btnSecondaryText: String, val warmHoney: String,
        val warmCoral: String, val warningButtonBg: String, val warningButtonText: String,
        val riskLow: String, val navPill: String, val navPillInk: String
    )
    // Light "Morning sky" / dark "Clear Sky". accentSeafoam = brand primary, accentPowderSky = secondary (borders only), warningButtonBg = attention.
    // navPill / navPillInk = the selected bottom tab's highlight and its icon and label (bgChip carries accent text elsewhere).
    private fun currentTheme(): ThemeColors = themeColors(GuardEngine.isBrightTheme(this))
    /** The saved theme, or the phone's dark mode when none is saved or the encrypted preferences cannot be read: consent and storage-failure pages. */
    private fun brightOrDefault(): Boolean = try { GuardEngine.isBrightTheme(this) } catch (_: RuntimeException) { !GuardEngine.phoneIsDark(this) }
    private fun themeColors(bright: Boolean): ThemeColors {
        return if (bright) {
            ThemeColors(
                bgCanvas = "#E2EAF3", bgCard = "#FFFFFF", bgHero = "#D8E6F6", bgInput = "#FFFFFF",
                bgChip = "#D8E6F6", borderSubtle = "#C3D1E1", textPrimary = "#0B1B2E",
                textSecondary = "#3D4F66", textMuted = "#3D4F66", accentSeafoam = "#1565C0",
                accentPowderSky = "#1565C0", btnPrimaryBg = "#1565C0", btnPrimaryText = "#FFFFFF",
                btnSecondaryBg = "#FFFFFF", btnSecondaryText = "#1565C0", warmHoney = "#8F5300",
                warmCoral = "#B3261E", warningButtonBg = "#D9822B", warningButtonText = "#0F2238",
                riskLow = "#17703A", navPill = "#1565C0", navPillInk = "#FFFFFF"
            )
        } else {
            ThemeColors(
                bgCanvas = "#0B1724", bgCard = "#12243A", bgHero = "#1F3856", bgInput = "#12243A",
                bgChip = "#1F3856", borderSubtle = "#304155", textPrimary = "#E5F0FB",
                textSecondary = "#A6B3C1", textMuted = "#A6B3C1", accentSeafoam = "#6FB6FF",
                accentPowderSky = "#5ED6BF", btnPrimaryBg = "#6FB6FF", btnPrimaryText = "#0B1724",
                btnSecondaryBg = "#12243A", btnSecondaryText = "#6FB6FF", warmHoney = "#F5B84D",
                warmCoral = "#FF8279", warningButtonBg = "#FFC043", warningButtonText = "#0B1724",
                riskLow = "#66D48F", navPill = "#1F3856", navPillInk = "#6FB6FF"
            )
        }
    }
    /**
     * The palette for everything inside an emphasised notice card ([noticeCard] with hero = true). Light: a solid navy card, so it takes
     * the dark inks (white titles, #9CCBFF links, dark status and risk colours) with white-on-navy / navy-on-white buttons. Dark: [t] itself.
     */
    private fun onNotice(t: ThemeColors): ThemeColors = if (t != themeColors(true)) t else themeColors(false).copy(
        bgCard = "#12345A", bgHero = "#12345A", borderSubtle = "#12345A", textPrimary = "#FFFFFF",
        accentSeafoam = "#9CCBFF", accentPowderSky = "#9CCBFF",
        btnPrimaryBg = "#FFFFFF", btnPrimaryText = "#12345A", btnSecondaryBg = "#12345A", btnSecondaryText = "#FFFFFF"
    )
    /** The system font, so Chinese and Latin text in one line come from one family (no bundled font). */
    internal fun font(bold: Boolean = false): Typeface = fonts.getOrPut(bold) { Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density + .5f).toInt()
    private fun color(s: String) = Color.parseColor(s)
    private fun background(fill: String, radius: Int = 10, border: String? = null) = GradientDrawable().apply {
        setColor(color(fill)); cornerRadius = dp(radius).toFloat()
        if (border != null) setStroke(dp(1), color(border))
    }
    private fun icon(name: String, tint: String, size: Int = 24): ImageView = ImageView(this).apply {
        val id = drawableIds.getOrPut(name) { resources.getIdentifier("ic_guard_" + name.replace('-', '_'), "drawable", packageName) }
        if (id != 0) setImageDrawable(resources.getDrawable(id, theme).mutate().apply { setTint(color(tint)) })
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private fun iconButton(name: String, description: String, onClick: () -> Unit): ImageButton = ImageButton(this).apply {
        val t = currentTheme()
        val id = drawableIds.getOrPut(name) { resources.getIdentifier("ic_guard_" + name.replace('-', '_'), "drawable", packageName) }
        if (id != 0) setImageDrawable(resources.getDrawable(id, theme).mutate().apply { setTint(color(t.textSecondary)) })
        background = ripple(t.bgCard, 10, t.accentPowderSky)
        contentDescription = description
        setPadding(dp(12), dp(12), dp(12), dp(12))
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        setOnClickListener(sessionClick(onClick))
    }
    private fun focusSurface(fill:String,radius:Int,border:String?=null,t:ThemeColors=currentTheme()):StateListDrawable {
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused),background(fill,radius).apply {
                setStroke(dp(2),color(if(fill==t.btnPrimaryBg)t.btnPrimaryText else t.accentSeafoam))
            })
            addState(intArrayOf(),background(fill,radius,border))
        }
    }
    private fun ripple(fill: String, radius: Int = 10, border: String? = null, t: ThemeColors = currentTheme()): RippleDrawable {
        val feedback=color(if(fill==t.btnPrimaryBg)t.btnPrimaryText else t.accentSeafoam)
        return RippleDrawable(ColorStateList.valueOf((feedback and 0x00ffffff) or 0x24000000), focusSurface(fill,radius,border,t), background(t.bgCard, radius)) // mask: only its shape counts
    }
    /** Starts [animator], or returns null when the system "remove animations" setting is on: the caller then places the final state itself. */
    /** Entrances decelerate; a move between two places on screen passes [moveEasing] (fast-out-slow-in, as the sheets use). */
    private fun motion(animator: ValueAnimator, ms: Long, easing: android.animation.TimeInterpolator = DecelerateInterpolator()): ValueAnimator? =
        if (!ValueAnimator.areAnimatorsEnabled()) null else animator.apply { duration = ms; interpolator = easing; start() }
    private val moveEasing get() = android.view.animation.AnimationUtils.loadInterpolator(this, android.R.interpolator.fast_out_slow_in)
    private fun View.describeAsControl(choice:Boolean=false) {
        accessibilityDelegate=object:View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host:View,info:AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host,info)
                info.className=if(choice)"android.widget.RadioButton" else "android.widget.Button"
                if(choice){info.isCheckable=true;info.isChecked=host.isSelected}
            }
        }
    }
    private fun applyTheme(bright: Boolean = GuardEngine.isBrightTheme(this)) {
        val id = resources.getIdentifier(if (bright) "GuardThemeLight" else "GuardThemeDark", "style", packageName)
        if (id != 0) setTheme(id)
        val t = themeColors(bright)
        window.statusBarColor = color(t.bgCanvas)
        window.navigationBarColor = color(t.bgCard)
        window.decorView.systemUiVisibility = if (bright) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
    }
    private var themeReveal: ValueAnimator? = null
    /** Both theme switches: save the choice, rebuild, then uncover the new theme as a circle growing from [from]. */
    private fun switchTheme(bright: Boolean, from: View) {
        themeReveal?.end() // a running reveal removes its overlay and recycles its bitmap in onAnimationEnd
        val root = window.decorView
        val xy = IntArray(2).also { from.getLocationInWindow(it) }
        val cx = xy[0] + from.width / 2f; val cy = xy[1] + from.height / 2f
        val snapshot = if (ValueAnimator.areAnimatorsEnabled() && root.isAttachedToWindow && root.width > 0 && root.height > 0) try {
            Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also { root.draw(Canvas(it)) }
        } catch (_: RuntimeException) { null } catch (_: OutOfMemoryError) { null } else null
        GuardEngine.setBrightTheme(this, bright); renderUi()
        if (snapshot == null) return
        val w = root.width.toFloat(); val h = root.height.toFloat(); var radius = 0f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(snapshot, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        val path = Path()
        // The old screen with a growing hole. A ViewOverlay never takes touches, and renderUi() never removes it.
        val cover = object : Drawable() {
            override fun draw(canvas: Canvas) {
                path.rewind(); path.fillType = Path.FillType.EVEN_ODD
                path.addRect(0f, 0f, w, h, Path.Direction.CW); path.addCircle(cx, cy, radius, Path.Direction.CW)
                canvas.drawPath(path, paint)
            }
            override fun setAlpha(alpha: Int) {}
            override fun setColorFilter(colorFilter: ColorFilter?) {}
            @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
        }.apply { setBounds(0, 0, root.width, root.height) }
        root.overlay.add(cover)
        themeReveal = ValueAnimator.ofFloat(0f, Math.hypot(maxOf(cx, w - cx).toDouble(), maxOf(cy, h - cy).toDouble()).toFloat()).apply {
            duration = 650; interpolator = DecelerateInterpolator()
            addUpdateListener { radius = it.animatedValue as Float; root.invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    root.overlay.remove(cover); snapshot.recycle()
                    if (themeReveal === animation) themeReveal = null
                }
            })
            start()
        }
    }
    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if(refreshPending)return
            refreshPending=true
            uiHandler.postDelayed({
                refreshPending=false
                if (!activityVisible) return@postDelayed
                val changed = renderedSession != GuardEngine.captureRelaySession(this@MainActivity)
                if (changed) syncRoleServices()
                updateStatus()
                if (changed || (currentFocus !is EditText && section in setOf("overview", "alerts", "scores"))) renderUi()
                maybeShowSafetyAdvice()
                maybeShowCaptureNotice()
            },200)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        modelPickAuthorized = savedInstanceState?.getBoolean("model_pick_authorized", false) == true
        // App start on a child phone: install the model included in the app, if there is one and it is missing. Not on a recreation.
        if (savedInstanceState == null) try { BundledModel.maybeAutoInstall(this) } catch (_: RuntimeException) {}
        // App start in every role: child keys left by a pairing that never finished (best effort, never throws).
        if (savedInstanceState == null) { GuardEngine.removeOrphanedChildKeys(this); GuardEngine.retireLeftoverRendezvous(this) }
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        try {
            LocalVault.preferences(this).all
            if (AppConsent.receipt(this) == null) { showInitialConsent(); return }
            FamilyEvidence(this).use { it.readableDatabase }
            storageReady = true
            applyTheme()
        } catch (_: SecurityException) { showStorageFailure(); return }
          catch (_: android.database.sqlite.SQLiteException) { showStorageFailure(); return }
        val restoredState=try{restoreParentUiState(savedInstanceState)}catch(_:Exception){showStorageFailure();return}
        GuardEngine.syncChildComponents(this)
        section = restoredState?.getString("section") ?: if (GuardEngine.getRole(this) == "unconfigured") "role" else "overview" // role page is one-time
        renderedRole = restoredState?.getString("rendered_role") ?: GuardEngine.getRole(this)
        selectedEntryRole = restoredState?.getString("entry_role") ?: ""
        chartMode = restoredState?.getString("chart_mode") ?: "trend"
        scoresReadOnly = restoredState?.getBoolean("scores_read_only") ?: false
        alertFilter = restoredState?.getString("alert_filter") ?: "all"
        displayed15MinCode = "" // a code is never restored from saved state; generating one needs a fresh authentication
        if (restoredState?.getString("draft_family") == GuardEngine.getChannelTopic(this) && renderedRole == GuardEngine.getRole(this)) {
            evidenceSearch = restoredState.getString("evidence_search") ?: ""
            evidenceSender = restoredState.getString("evidence_sender")
            evidenceConversationKey=restoredState.getString("evidence_conversation_key")
            evidenceSection=restoredState.getString("evidence_section")?:"conversations"
            evidenceReview=restoredState.getString("evidence_review")?:"new"
            evidenceDirection=restoredState.getString("evidence_direction")?:"all"
            evidencePage = restoredState.getInt("evidence_page").coerceAtLeast(0)
            if(evidenceSender!=null && evidencePage>0) {
                try {
                    val raw=restoredState.getString("evidence_cursors")?:error("Missing page boundaries")
                    require(raw.length<=64_000)
                    val cursors=JSONArray(raw)
                    for(i in 0 until cursors.length()) {
                        val row=cursors.getJSONArray(i);val page=row.getInt(0)
                        val cursor=EvidenceCursor(row.getLong(1),row.getLong(2),row.getString(3))
                        require(page>0 && cursor.stamp>=0 && cursor.order>= -1_000_000_000L && cursor.id.length in 1..160)
                        evidenceCursors[page]=cursor
                    }
                    require((1..evidencePage).all{evidenceCursors.containsKey(it)})
                } catch(_:Exception) {evidenceCursors.clear();evidencePage=0}
            }
            evidenceDays = restoredState.getInt("evidence_days")
            evidenceSaved = restoredState.getBoolean("evidence_saved")
            if(restoredState.getInt("private_storage_state_version")==1)
                restoredState.getBundle("drafts")?.let { b -> for (key in b.keySet()) inputDrafts[key] = b.getString(key) ?: "" }
        }
        screenShell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; isSaveFromParentEnabled = false }
        fixedHeader = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(4), dp(16), 0) }
        contentFrame = FrameLayout(this)
        bottomNav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        screenShell.addView(fixedHeader)
        screenShell.addView(contentFrame, LinearLayout.LayoutParams(-1, 0, 1f))
        screenShell.addView(bottomNav)
        scrollView = ScrollView(this)
        rootContainer = LinearLayout(this)
        setContentView(screenShell)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        handleDeepLinkIntent(intent)
    }
    private fun restoreParentUiState(saved: Bundle?): Bundle? {
        val sealed=saved?.getString("encrypted_parent_ui_state") ?: return saved
        val raw=java.util.Base64.getDecoder().decode(LocalVault.open(this,"parent_ui_state_v1",sealed))
        val parcel=android.os.Parcel.obtain()
        return try{parcel.unmarshall(raw,0,raw.size);parcel.setDataPosition(0);parcel.readBundle(javaClass.classLoader)}finally{parcel.recycle()}
    }
    override fun onSaveInstanceState(outState: Bundle) {
        if(!storageReady || consentVisible){super.onSaveInstanceState(outState);return}
        if(GuardEngine.isParentMode(this)){
            val privateState=Bundle();saveCurrentUiState(privateState)
            val parcel=android.os.Parcel.obtain()
            try {
                parcel.writeBundle(privateState)
                val raw=java.util.Base64.getEncoder().encodeToString(parcel.marshall())
                outState.putString("encrypted_parent_ui_state",LocalVault.seal(this,"parent_ui_state_v1",raw))
            } finally {parcel.recycle()}
        } else saveCurrentUiState(outState)
        outState.putBoolean("model_pick_authorized", modelPickAuthorized)
        super.onSaveInstanceState(outState)
    }
    private fun saveCurrentUiState(outState: Bundle) {
        outState.putInt("private_storage_state_version",1)
        outState.putString("section", section); outState.putString("rendered_role", renderedRole)
        outState.putString("entry_role", selectedEntryRole); outState.putString("chart_mode", chartMode)
        outState.putBoolean("scores_read_only", scoresReadOnly)
        outState.putString("alert_filter", alertFilter)
        outState.putString("evidence_search", evidenceSearch); outState.putString("evidence_sender", evidenceSender)
        outState.putString("evidence_conversation_key",evidenceConversationKey);outState.putString("evidence_section",evidenceSection)
        outState.putString("evidence_review",evidenceReview);outState.putString("evidence_direction",evidenceDirection)
        outState.putInt("evidence_page", evidencePage); outState.putInt("evidence_days", evidenceDays); outState.putBoolean("evidence_saved", evidenceSaved)
        val cursors=JSONArray().apply {evidenceCursors.toSortedMap().forEach{(page,cursor)->put(JSONArray().put(page).put(cursor.stamp).put(cursor.order).put(cursor.id))}}.toString()
        // Bound Android saved state. The archive remains intact if an exceptionally deep navigation resets.
        if(cursors.length<=64_000)outState.putString("evidence_cursors",cursors)
        else if(evidenceSender!=null)outState.putInt("evidence_page",0)
        outState.putString("draft_family", GuardEngine.getChannelTopic(this))
        outState.putBundle("drafts", Bundle().apply { for ((key, value) in inputDrafts) putString(key, value) })
    }
    @Deprecated("Activity back navigation")
    override fun onBackPressed() {
        if (consentVisible) { AppConsent.leaveToHome(this); return }
        if (!storageReady) { finish(); return }
        if(!parentUnlocked()){AppConsent.leaveToHome(this);return}
        when {
            section == "alerts" && evidenceSender != null -> { evidenceSender = null;evidenceConversationKey=null;evidenceDirection="all"; evidencePage = 0; renderUi(animate = true) }
            section.endsWith("_setup") -> navigateTo("role")
            section == "role" -> super.onBackPressed()
            section != "overview" -> navigateTo("overview")
            else -> navigateTo("role")
        }
    }
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) setIntent(intent)
        if(storageReady && !consentVisible && AppConsent.hasAccepted(this)) handleDeepLinkIntent(intent)
    }
    private fun handleDeepLinkIntent(incoming: Intent?) {
        if(!parentUnlocked()){
            deferredParentIntent=incoming?.let{Intent(it)}
            incoming?.let{setIntent(Intent(it).apply{action=Intent.ACTION_MAIN;data=null})}
            renderParentLock();return
        }
        if (incoming?.action == "app.family.igguard.OPEN_EVIDENCE") { handleEvidenceNotification(incoming); return }
        if (incoming?.action == ScoresUi.OPEN_SCORES_ACTION) { handleScoresNotification(incoming); return }
        // The import reminder: the upload card lives on the child's overview. Carries no data.
        if (incoming?.action == RiskLocal.OPEN_UPLOAD_ACTION) {
            setIntent(Intent(incoming).apply { action = Intent.ACTION_MAIN; data = null })
            if (GuardEngine.isChildMode(this)) { section = "overview"; if (activityVisible) renderUi() }
            return
        }
        val data = incoming?.data ?: return
        setIntent(Intent(incoming).apply { this.data = null })
        if (incoming.action != Intent.ACTION_VIEW || !data.scheme.equals("igguard", true) || !data.host.equals("pair", true)) return
        if (showPairingBlock()) return
        GuardEngine.noteInviteLinkOnChild(this)
        showInviteRefused()
    }
    /** Design 2.8: an invitation link or text never pairs this phone; the person is told to use the QR code instead. */
    private fun showInviteRefused() {
        val t = currentTheme()
        showSheet("請改用 QR 碼 · Use the QR code") { dialog ->
            addView(stateNote(Note.WARNING, GuardEngine.INVITE_REFUSED, t, cardTag = "invite_refused"))
            addView(actionButton("關閉 · Close", t.btnSecondaryBg, t.btnSecondaryText) { dialog.dismiss() })
        }
    }
    /** Plain explanation when a phone that holds parent authority is offered a pairing link. */
    private fun showPairingBlock(): Boolean {
        val message = when (GuardEngine.pairingBlock(this)) {
            GuardEngine.PairingBlock.PARENT_ROLE -> "此手機是家長手機，不能用配對邀請改成子女手機。請先離開家庭或重設，再使用邀請。"
            GuardEngine.PairingBlock.PARENT_DATA_PRESENT -> "此手機仍保存家長資料，不會被配對邀請刪除。請先離開家庭或重設，再使用邀請。"
            null -> return false
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        return true
    }
    private fun finishPairing() {
        section = "permissions"
        if (activityVisible) syncRoleServices()
        renderUi(animate = true)
        Toast.makeText(this, "已完成家庭配對。", Toast.LENGTH_LONG).show()
    }
    override fun onResume() {
        super.onResume()
        if (!storageReady || consentVisible) return
        if (!AppConsent.hasAccepted(this)) { showInitialConsent(); return }
        // onActivityResult runs before onResume, so a request still pending here received no result: drop it.
        if (parentAuthPending && parentAuthSignal == null) clearParentAuthRequest()
        activityVisible = true
        ModelImportJob.listener = importListener; InstagramImport.listener = exportListener
        val filter = IntentFilter(GuardEngine.ACTION_UI_REFRESH)
        // The flag protects Android 13+; the signature permission protects earlier releases.
        registerReceiver(refreshReceiver, filter, "$packageName.INTERNAL", null, Context.RECEIVER_NOT_EXPORTED)
        if (GuardEngine.isChildMode(this) && GuardEngine.isNotificationListenerEnabledStatic(this)) {
            try { NotificationListenerService.requestRebind(ComponentName(this, IgNotificationListener::class.java)) } catch (_: Exception) {}
        }
        syncRoleServices(); checkInitialPairingConfirmation(); renderUi(); fillChildPairing()
        pairingScanner?.takeIf { it.isAttachedToWindow }?.start()
        if (GuardEngine.isChildMode(this)) {
            GuardEngine.retryPendingAlerts(this,force=true)
            if (GuardEngine.isNotificationListenerEnabledStatic(this) && !GuardEngine.cpNotifConnected)
                try { NotificationListenerService.requestRebind(ComponentName(this, IgNotificationListener::class.java)) } catch (_: Exception) {}
        }
        openPendingEvidence(); maybeShowSafetyAdvice();maybeShowCaptureNotice();maybeShowTermsUpdatedNotice()
        uiHandler.removeCallbacks(statusTicker); uiHandler.post(statusTicker)
    }
    override fun onPause() {
        super.onPause(); themeReveal?.end(); activityVisible = false; uiHandler.removeCallbacksAndMessages(null)
        pairingScanner?.stop()
        refreshPending = false   // the runnable that clears it was just removed; left set, every later refresh broadcast is dropped
        importRedrawQueued = false   // same for the throttled model-copy redraw
        if (ModelImportJob.listener === importListener) ModelImportJob.listener = null
        if (InstagramImport.listener === exportListener) InstagramImport.listener = null
        parentAccess.lock()
        // The pending authentication request survives onPause: on Android 8-10 the credential check is a separate activity
        // that covers this one (onPause always precedes its result), and on 11+ an OEM prompt may pause the screen. onStop
        // cancels a BiometricPrompt; onResume drops a credential-activity request that never returned.
        if(storageReady && !consentVisible && GuardEngine.isParentMode(this)) {
            dismissUiDialogs();inputDrafts.clear();displayed15MinCode="";renderParentLock()
        }
        try { unregisterReceiver(refreshReceiver) } catch (_: Exception) {}
    }
    /** Android 11+: cancel the BiometricPrompt only when the screen is really stopped; an OEM prompt or its PIN fallback may merely pause it. */
    override fun onStop() {
        super.onStop()
        parentAuthSignal?.let { parentAuthTicket++; it.cancel(); clearParentAuthRequest() }
    }
    override fun onDestroy() { if (ModelImportJob.listener === importListener) ModelImportJob.listener = null; if (InstagramImport.listener === exportListener) InstagramImport.listener = null; parentAuthSignal?.cancel();parentAccess.lock(); activityVisible = false; uiHandler.removeCallbacksAndMessages(null); dismissUiDialogs(); themeReveal?.end(); super.onDestroy() }

    private fun navigateTo(destination: String) {
        val role = GuardEngine.getRole(this)
        val permitted = if (role == "parent") setOf("role", "overview", "alerts", "family", "settings", "scores")
            else if (role == "child") setOf("role", "overview", "permissions", "unlock", "settings", "scores_consent", "scores_change")
            else setOf("role", "parent_setup", "child_setup", "settings")
        if (destination !in permitted) return
        section = destination
        currentFocus?.clearFocus()
        renderUi(animate = true)
    }
    private fun selectEntryRole(role: String) {
        if (role !in setOf("parent", "child")) return
        selectedEntryRole = role
        renderUi()
    }
    private fun continueEntryRole() {
        if (selectedEntryRole !in setOf("parent", "child")) return
        val current = GuardEngine.getRole(this)
        if (current == selectedEntryRole) { navigateTo("overview"); return }
        val next = selectedEntryRole
        val proceed: () -> Unit = proceed@{
            if (current == "parent") {
                if (!GuardEngine.leaveParentRole(this)) { Toast.makeText(this, "未能完成；家長資料已保留。請重試。", Toast.LENGTH_LONG).show(); return@proceed }
            } else if (current != "unconfigured") GuardEngine.setRole(this, "unconfigured")
            if (current != "unconfigured") syncRoleServices()
            section = if (next == "parent") "parent_setup" else "child_setup"
            renderUi(animate = true)
        }
        when (current) {
            "child" -> verifyParentForRoleChange(onSuccess = proceed)
            "parent" -> alert(critical = true).setTitle("離開家長身份？")
                .setMessage("離開後，此手機會刪除家長主密碼與簽署金鑰，之後無法再管理已配對的子女手機。子女手機會一直受到保護，直到收到解除指令。已保存的家庭記錄會留在本機。")
                .setNegativeButton("取消", null).setPositiveButton("離開家庭") { _, _ ->
                    requireFreshParentAuth { releaseChildSheet("離開家長身份", "下一步會顯示目前的家長主密碼，並可同時解除子女手機。") { _, _ -> proceed() } }
                }.show()
            else -> proceed()
        }
    }
    /** [announceLeaving]: a role change tells the parent the child is leaving; the scoring model import/removal checks only the password. */
    private fun verifyParentForRoleChange(title: String = "切換身份，需要家長確認。", announceLeaving: Boolean = true, onSuccess: () -> Unit) {
        val t = currentTheme()
        showSheet(title) { dialog ->
            addView(label("為保護既有家庭設定，請輸入目前的家長主密碼。", 14f, t.textSecondary, bottomPad = 18))
            val password = inputBox(t, "家長主密碼", "", isPassword = true, remember = false).apply { tag = "role_password" }
            addView(password)
            val error = label("", 13f, t.warmCoral); addView(error)
            lateinit var verify: Button
            verify = actionButton("驗證並繼續", t.btnPrimaryBg, t.btnPrimaryText, critical = true) {
                error.setTextColor(color(t.warmCoral)) // errors; the progress line below switches to the status colour
                val remaining = GuardEngine.checkPasswordLockoutRemainingSec(this@MainActivity)
                if (remaining > 0) { error.text = "輸入已鎖定，請等 $remaining 秒，或由家長手機遠端救援。"; return@actionButton }
                if (!GuardEngine.passwordCheckRunning.compareAndSet(false, true)) { error.text = PASSWORD_CHECK_BUSY; return@actionButton }
                val entered = password.text.toString()
                fun checking(on: Boolean) { verify.isEnabled = !on; password.isEnabled = !on; verify.text = if (on) "核對中… · Checking…" else "驗證並繼續" }
                checking(true)
                // PBKDF2 takes seconds on older phones: off the main thread, as for the QR join (Join this family).
                thread {
                    val ok = GuardEngine.verifyParentPassword(this@MainActivity, entered)
                    runOnUiThread {
                        GuardEngine.passwordCheckRunning.set(false)
                        GuardEngine.recordPasswordAttempt(this@MainActivity, ok) // every attempt counts, even if the sheet was closed meanwhile
                        if (isDestroyed || !dialog.isShowing) return@runOnUiThread
                        if (!ok) { checking(false); error.text = "家長主密碼不正確。"; password.error = "請重新輸入"; return@runOnUiThread }
                        inputDrafts.remove("家長主密碼")
                        if (!announceLeaving) { dialog.dismiss(); onSuccess(); return@runOnUiThread }
                        error.setTextColor(color(t.textSecondary)); error.text = "正在通知家長手機…"
                        thread {
                            GuardEngine.announceChildLeaving(this@MainActivity, 6_000L)
                            runOnUiThread { if (!isDestroyed) { dialog.dismiss(); onSuccess() } }
                        }
                    }
                }
            }
            addView(verify)
        }
    }
    private fun renderUi(animate: Boolean = false) {
        if (!storageReady || consentVisible || !AppConsent.hasAccepted(this)) return
        try {
            if(!parentUnlocked()){renderParentLock();return}
            if(::screenShell.isInitialized && screenShell.parent==null)setContentView(screenShell)
            renderProtectedUi(animate)
        }
        catch (_: SecurityException) { showStorageFailure() }
        catch (_: android.database.sqlite.SQLiteException) { showStorageFailure() }
    }
    private fun renderProtectedUi(animate: Boolean = false) {
        applyTheme()
        val t = currentTheme(); val role = GuardEngine.getRole(this)
        val session = GuardEngine.captureRelaySession(this)
        if (renderedSession != null && renderedSession != session) {
            dismissUiDialogs(); inputDrafts.clear(); displayed15MinCode = ""
            evidenceSearch = ""; evidenceSender = null;evidenceConversationKey=null;evidenceSection="conversations";evidenceReview="new";evidenceDirection="all"; evidencePage = 0; evidenceDays = 0; evidenceSaved = false; pendingEvidenceId = null
        }
        renderedSession = session
        if (renderedRole != role) {
            if (role == "unconfigured" && !section.endsWith("_setup")) section = "role"
            else if (section !in setOf("role", "permissions", "overview") && !section.endsWith("_setup")) section = "overview"
            inputDrafts.clear(); renderedRole = role
        }
        if (role != "unconfigured" && section.endsWith("_setup")) section = if (role == "child") "permissions" else "overview" // never setup chrome on a paired phone
        val focus = currentFocus as? EditText
        val focusHint = focus?.hint?.toString(); val selection = focus?.selectionStart ?: -1
        val sameSection = lastRenderedSection == section
        val page = section + when (section) { "alerts" -> "/$evidenceSection/$evidenceSender"; "overview" -> "/$chartMode"; else -> "" } // a sub-tab, an open conversation or a chart mode is its own page
        val savedY = if (sameSection) scrollView.scrollY else 0
        pageMotion?.cancel()
        contentFrame.removeAllViews()
        screenShell.setBackgroundColor(color(t.bgCanvas))
        buildHeader(t, role); buildBottomNavigation(t, role)
        scrollView = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false }
        rootContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(if (section == "role") 26 else 20), dp(24), dp(28))
        }
        scrollView.addView(rootContainer)
        contentFrame.addView(scrollView, FrameLayout.LayoutParams(-1, -1))
        unlockTimerText = null
        if (role == "parent" && GuardEngine.parentKeyMigrationPending(this))
            rootContainer.addView(stateNote(Note.WARNING, "金鑰升級尚未完成：目前仍使用原有簽署金鑰，接收及指令不受影響；下次開啟 App 會再試一次。", t))
        when {
            section == "role" -> renderRoleChoice(t)
            section == "parent_setup" && role == "unconfigured" -> renderParentSetup(t)
            section == "child_setup" && role == "unconfigured" -> renderChildSetup(t)
            section == "settings" -> renderSettings(t)
            role == "parent" -> when(section) { "alerts" -> renderParentAlerts(t); "family" -> renderParentFamily(t); "scores" -> renderParentScores(t); else -> renderParentOverview(t) }
            role == "child" -> when(section) { "permissions" -> renderChildPermissions(t); "unlock" -> renderChildUnlock(t); "scores_consent" -> renderScoresConsent(t); "scores_change" -> renderScoresChange(t); else -> renderChildOverview(t) }
            else -> renderRoleChoice(t)
        }
        if (sameSection && focusHint != null) {
            descendants(rootContainer).filterIsInstance<EditText>().firstOrNull { it.hint?.toString() == focusHint }?.let {
                it.requestFocus(); it.setSelection(selection.coerceIn(0, it.text.length))
            }
        }
        scrollView.post { scrollView.scrollTo(0, savedY) }
        pageMotion = if (animate && lastRenderedPage.isNotEmpty() && page != lastRenderedPage) motion(ObjectAnimator.ofPropertyValuesHolder(scrollView,
            PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 1f), PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, dp(12).toFloat(), 0f)), 220) else null
        lastRenderedSection = section; lastRenderedPage = page; updateStatus()
    }
    private fun buildHeader(t: ThemeColors, role: String) {
        fixedHeader.removeAllViews(); fixedHeader.setBackgroundColor(color(t.bgCanvas))
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(60) }
        if (section.endsWith("_setup")) row.addView(iconButton("arrow-left", "返回身份選擇") { navigateTo("role") })
        else row.addView(icon("shield-check", t.accentSeafoam, 28))
        row.addView(label("Convoy", 18f, t.textPrimary, true).apply { setPadding(dp(8), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        lateinit var themeToggle: View
        themeToggle = iconButton(if (GuardEngine.isBrightTheme(this)) "moon" else "sun", "切換淺色或深色模式") {
            switchTheme(!GuardEngine.isBrightTheme(this), themeToggle)
        }
        row.addView(themeToggle)
        fixedHeader.addView(row)
        statusText = label("", 12f, t.textSecondary).apply { setPadding(0, dp(4), 0, dp(12)) }
        if (section != "role" && !section.endsWith("_setup") && role != "unconfigured") {
            fixedHeader.addView(statusText); divider(fixedHeader, t)
        }
    }
    private fun buildBottomNavigation(t: ThemeColors, role: String) {
        bottomNav.removeAllViews(); navMotion?.cancel()
        bottomNav.visibility = if (section == "role" || section.endsWith("_setup") || role == "unconfigured") View.GONE else View.VISIBLE
        if (bottomNav.visibility != View.VISIBLE) { navShown = ""; return }
        // The bar's background draws the one highlight, so it can slide between tabs that are rebuilt on every render.
        val pill = object : Drawable() {
            var x = navPillX; var tab: View? = null
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = color(t.navPill) }
            override fun draw(canvas: Canvas) {
                canvas.drawColor(color(t.bgCard))
                tab?.let { canvas.drawRoundRect(x, it.top.toFloat(), x + it.width, it.bottom.toFloat(), dp(10).toFloat(), dp(10).toFloat(), paint) }
            }
            override fun setAlpha(alpha: Int) {}
            override fun setColorFilter(colorFilter: ColorFilter?) {}
            @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.OPAQUE
        }
        bottomNav.background = pill; bottomNav.setPadding(dp(12), dp(8), dp(12), dp(10))
        val destinations = if (role == "parent") listOf(Triple("overview", "house", "總覽"), Triple("alerts", "bell-simple", "提醒"), Triple("family", "users-three", "家庭"), Triple("settings", "gear-six", "設定"))
            else listOf(Triple("overview", "house", "總覽"), Triple("permissions", "shield", "權限"), Triple("unlock", "key", "解鎖"), Triple("settings", "gear-six", "設定"))
        for ((destination, glyph, title) in destinations) {
            val selected = section == destination || (destination == "overview" && section in setOf("scores", "scores_consent", "scores_change"))
            val tab = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; minimumHeight = dp(56)
                background = ripple(TRANSPARENT_FILL, 10, t = if (selected) t.copy(accentSeafoam = t.navPillInk) else t)   // ripple and focus ring readable on the pill
                isClickable = true; isFocusable = true; isSelected = selected; contentDescription = title
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                setOnClickListener { if (section != destination) navigateTo(destination) }
                tag = "nav_$destination"
                describeAsControl()
            }
            val glyphView = icon(glyph, if (selected) t.navPillInk else t.textMuted, 24)
            val titleView = label(title, 12f, if (selected) t.navPillInk else t.textMuted, selected, topPad = 4).apply { gravity = Gravity.CENTER }
            tab.addView(glyphView); tab.addView(titleView)
            bottomNav.addView(tab, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
            if (!selected) continue
            val slide = navShown.substringBefore('/') == role && navShown != "$role/$destination" // a tab change; not first draw, role or theme change
            navShown = "$role/$destination"
            var glide = slide
            // While the pill slides in, the tab keeps the accent ink (readable on the bar); it takes the pill ink once the pill is under it.
            val ink = { hex: String -> glyphView.drawable?.setTint(color(hex)); titleView.setTextColor(color(hex)) }
            tab.addOnLayoutChangeListener { v, l, _, _, _, _, _, _, _ ->
                pill.tab = v
                val place = { x: Float -> pill.x = x; navPillX = x; pill.invalidateSelf() }
                if (glide) navMotion = motion(ValueAnimator.ofFloat(navPillX, l.toFloat()).apply {
                    addUpdateListener { place(it.animatedValue as Float) }
                    // The target was read before the slide; a rotation mid-slide moves the tab, so land on where it is now.
                    addListener(object : AnimatorListenerAdapter() {
                        var cancelled = false
                        override fun onAnimationStart(animation: Animator) { ink(t.accentSeafoam) }
                        override fun onAnimationCancel(animation: Animator) { cancelled = true }
                        override fun onAnimationEnd(animation: Animator) { ink(t.navPillInk); if (!cancelled && pill.tab === v) place(v.left.toFloat()) }
                    })
                }, 280, moveEasing)
                glide = false
                if (navMotion?.isRunning != true) place(l.toFloat())
            }
        }
    }
    private fun updateStatus() {
        if (!::statusText.isInitialized) return
        val t = currentTheme()
        statusText.text = when (GuardEngine.getRole(this)) {
            "parent" -> when {
                GuardEngine.childRemotelyReset(this) -> "子女手機已遠端重設 · 須用 QR 碼重新配對"
                GuardEngine.childPairingFailed(this) && GuardEngine.pinnedChildKey(this) == null -> "未與任何子女手機配對 · 須用 QR 碼重新配對"
                GuardEngine.isChildHeartbeatOverdue(this) -> "子女回報逾時 · 請查看連接狀態"
                GuardEngine.hasChildHeartbeat(this) -> "已收到子女回報 · 加密守護通道"
                else -> childReportWaiting()
            }
            "child" -> {
                val line = if (RiskLocal.uploadOnly(this)) (if (RiskLocal.captureHeld(this)) "擷取已停止，等你決定 · 不讀取 Instagram" else "手動上載 · 不讀取 Instagram") else {
                    val notification = if (!GuardEngine.isNotificationListenerEnabledStatic(this)) "通知未授權" else if (GuardEngine.cpNotifConnected) "通知已連接" else "通知等待連接"
                    val screen = if (!GuardEngine.isAccessibilityEnabledStatic(this)) "畫面未授權" else if (GuardEngine.cpAccConnected) "畫面已連接" else "畫面等待連接"
                    val capture=if(GuardEngine.fullCaptureEnabled(this))"雙向擷取已啟用" else "只擷取傳入警報"
                    "$notification · $screen · $capture"
                }
                line + GuardEngine.pendingDeliveryCount(this).let { if (it > 0) " · 待確認 $it" else "" } + if (GuardEngine.isTemporarilyUnlocked(this)) " · 暫時解鎖中" else ""
            }
            else -> "選擇這部手機的身份"
        }
        statusText.setTextColor(color(if (GuardEngine.isParentMode(this) && GuardEngine.isChildHeartbeatOverdue(this)) t.warmCoral else t.textSecondary))
        unlockTimerText?.text = if (GuardEngine.getUnlockRemainingSeconds(this) > 0) "暫時解鎖中 · 剩餘 ${formatSeconds(GuardEngine.getUnlockRemainingSeconds(this))}" else "保護已重新上鎖"
    }
    /** No heartbeat yet: a scores-mode report from the child phone still counts as having heard from it. */
    private fun childReportWaiting(): String =
        FamilyEvidence(this).use { it.scoresModeState(GuardEngine.getChannelTopic(this)) }?.let { "已收到子女手機報告 · 最後 ${ScoresUi.time(it.receivedAt)}" } ?: "等待子女手機回報"
    private fun formatSeconds(seconds: Long) = "%02d:%02d".format(seconds / 60, seconds % 60)
    private fun heading(title: String, subtitle: String, t: ThemeColors) {
        rootContainer.addView(label(title, 27f, t.textPrimary, true, bottomPad = 10))
        if (subtitle.isNotEmpty()) rootContainer.addView(label(subtitle, 14f, t.textSecondary, bottomPad = 22))
    }
    private fun renderRoleChoice(t: ThemeColors) {
        rootContainer.addView(label("每一段成長，都值得守護", 12f, t.accentSeafoam, true, bottomPad = 14))
        rootContainer.addView(label("一起，\n守護成長。", 36f, t.textPrimary, true, bottomPad = 18))
        rootContainer.addView(label("選擇這部手機的使用者。\n讓每個人都有適合自己的守護方式。", 14f, t.textSecondary, bottomPad = 28))
        for ((role, title, description) in listOf(Triple("parent", "我是家長", "查看提醒、管理家庭與保護設定。"), Triple("child", "這是子女手機", "連結家人，建立安心的上網空間。"))) {
            val selected = selectedEntryRole == role
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(20), dp(16), dp(20)); minimumHeight = dp(100)
                background = ripple(if (selected) t.bgChip else t.bgCard, 14, if (selected) t.accentSeafoam else t.accentPowderSky)
                isClickable = true; isFocusable = true; isSelected = selected
                contentDescription = "$title。$description"
                tag = "entry_$role"; setOnClickListener { selectEntryRole(role) }
                describeAsControl(choice=true)
            }
            card.addView(icon(if (role == "parent") "users-three" else "plant", t.accentSeafoam, 28))
            val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(8), 0) }
            words.addView(label(title, 16f, t.textPrimary, true, bottomPad = 6))
            words.addView(label(description, 12f, t.textSecondary))
            card.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(icon(if (selected) "check-circle" else "caret-right", t.accentSeafoam, 20))
            rootContainer.addView(card, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(12)) })
        }
        if (GuardEngine.pairingBlock(this) == GuardEngine.PairingBlock.PARENT_DATA_PRESENT)
            rootContainer.addView(row("key", "移除此手機上的家長資料", "此手機已不是家長手機，但仍保存家長主密碼或簽署金鑰。", t) {
                alert(critical = true).setTitle("移除家長資料？")
                    .setMessage("將刪除此手機上的家長主密碼與簽署金鑰，之後無法再管理已配對的子女手機。子女手機會一直受到保護，直到收到解除指令或輸入原本的家長主密碼。")
                    .setNegativeButton("取消", null).setPositiveButton("移除家長資料") { _, _ ->
                        requireFreshDeviceAuth {
                            if (GuardEngine.getRole(this) == "unconfigured" && GuardEngine.removeLeftoverParentData(this)) renderUi()
                            else Toast.makeText(this, "未能完成；家長資料已保留。請重試。", Toast.LENGTH_LONG).show()
                        }
                    }.show()
            })
        rootContainer.addView(Space(this), LinearLayout.LayoutParams(1, 0, 1f))
        rootContainer.addView(actionButton("繼續", t.btnPrimaryBg, t.btnPrimaryText) { continueEntryRole() }.apply {
            isEnabled = selectedEntryRole.isNotEmpty(); tag = "continue_role"
        })
        rootContainer.addView(label("配對後，子女手機切換身份需要家長驗證。", 12f, t.textMuted, topPad = 10).apply { gravity = Gravity.CENTER })
    }
    private fun renderParentSetup(t: ThemeColors) {
        heading("從一個安心的家開始。", "設定只由家長保管的主密碼，再邀請子女手機加入。", t)
        rootContainer.addView(label("家長主密碼", 14f, t.textPrimary, true, bottomPad = 10))
        val password = inputBox(t, "最少 12 位字元，包含字母及數字", "", true); rootContainer.addView(password)
        val error = label("", 13f, t.warmCoral); rootContainer.addView(error)
        rootContainer.addView(label("建議使用下方產生的高強度密碼。配對邀請帶有由密碼推算的驗證資料，簡短或常見的密碼較易被猜中。", 13f, t.textSecondary, bottomPad = 8).apply { tag = "password_recommendation" })
        rootContainer.addView(textAction("產生高強度密碼") {
            password.setText(GuardEngine.generateRandomStrongPassword()); error.text = ""; password.error = null
        })
        lateinit var create: Button
        create = actionButton("建立家庭", t.btnPrimaryBg, t.btnPrimaryText) {
            val candidate = password.text.toString().trim()
            if (!GuardEngine.isStrongPasswordValid(candidate)) { error.text = "請輸入最少 12 位字元，並同時包含字母及數字，避免常見密碼（例如 password、123456）。"; password.error = "密碼長度或格式不正確"; return@actionButton }
            if (!GuardEngine.passwordCheckRunning.compareAndSet(false, true)) { error.text = PASSWORD_CHECK_BUSY; return@actionButton }
            fun working(on: Boolean) { create.isEnabled = !on; password.isEnabled = !on; create.text = if (on) "核對中… · Checking…" else "建立家庭" }
            working(true)
            thread {
                val created = try {
                    GuardEngine.setFamilyToken(this, UUID.randomUUID().toString(), qrOnly = true); GuardEngine.setPin(this, candidate); GuardEngine.setRole(this, "parent"); true
                } catch (_: RuntimeException) { false }
                runOnUiThread {
                    GuardEngine.passwordCheckRunning.set(false)
                    if (isDestroyed) return@runOnUiThread
                    if (!created) { working(false); error.text = "未能建立家庭，請重試。"; return@runOnUiThread }
                    section = "overview"; syncRoleServices(); renderUi(animate = true)
                }
            }
        }
        rootContainer.addView(create)
        rootContainer.addView(label("主密碼用於保護角色切換及解鎖設定，請妥善保管。", 13f, t.textSecondary, topPad = 16))
    }
    private fun renderChildSetup(t: ThemeColors) {
        heading("讓家人，安心相連。", "請家長在家長手機選擇「加入子女」，再用此手機掃描家長手機上的 QR 碼。", t)
        // The primary action first: at large font sizes the warning card would push it below the fold.
        rootContainer.addView(actionButton("掃描 QR 碼 · Scan QR code", t.btnPrimaryBg, t.btnPrimaryText) { showChildPairingSheet() }.apply { tag = "scan_qr" })
        rootContainer.addView(stateNote(Note.WARNING, "只可掃描你自己家長手機上的 QR 碼。掃描他人的 QR 碼會把此手機連到該人。\nScan only the QR code on your own parent's phone. Scanning someone else's code connects this phone to that person.", t))
        rootContainer.addView(label("相機只在掃描時使用，不會儲存或傳送任何影像。兩部手機需要在一起。\nThe camera is used only while scanning; no image is stored or sent. Both phones need to be together.", 13f, t.textSecondary, topPad = 18))
    }

    private fun renderParentOverview(t: ThemeColors) {
        heading("家人，安心相連。", "今天的守護資訊，都在這裡。", t)
        val connected = GuardEngine.hasChildHeartbeat(this)
        val card = makeCard(t)
        val handle = GuardEngine.getChildSelfHandle(this)
        card.addView(label(if (handle.isEmpty()) "子女手機" else "@$handle", 16f, t.textPrimary, true, bottomPad = 8))
        GuardEngine.expireUnconfirmedChildPairing(this)
        val trust = GuardEngine.childTrust(this)
        val waiting = if (connected) "" else if (trust == GuardEngine.ChildTrust.REMOTE_RESET) "已遠端重設 · Remotely reset"
            else if (trust == GuardEngine.ChildTrust.PAIRING_FAILED) "未與任何子女手機配對 · Not paired with any child phone" else childReportWaiting()
        card.addView(label(if (connected) (if (trust == GuardEngine.ChildTrust.NOT_VERIFIED) "未核實 · " else "") + GuardEngine.getLastChildHeartbeatInfo(this) else waiting, 13f, t.textSecondary))
        if (connected && GuardEngine.isChildCaptureHeld(this)) card.addView(label(ScoresUi.PARENT_CAPTURE_HELD, 13f, t.textPrimary, true, topPad = 6))
        if (!connected && trust == GuardEngine.ChildTrust.NO_CHILD) card.addView(textAction("加入子女 · Add a child") { startParentPairing() })
        rootContainer.addView(card)
        renderChildTrust(trust, t)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            rootContainer.addView(textAction("尚未授權系統通知 · 前往設定") { navigateTo("settings") })
        }
        rootContainer.addView(scoresOverviewRow(t))
        renderWeeklyCharts(t)
        GuardEngine.prepareParentArchive(this)
        val latest = FamilyEvidence(this).use{it.messages(GuardEngine.getChannelTopic(this),EvidenceQuery(),limit=1).firstOrNull()}
        if (latest != null) {
            sectionTitle("最新提醒", t)
            rootContainer.addView(row("bell-simple", alertTitle(latest.optString("reason")), latest.optString("time"), t) { showAlertDetails(latest, t) })
            rootContainer.addView(textAction("查看全部提醒") { navigateTo("alerts") })
        }
    }
    private fun renderWeeklyCharts(t: ThemeColors) {
        val stats = GuardEngine.getParentStatistics(this)
        chartZone = stats.zone; displayedChartDate = stats.dates.last()
        val values = stats.dailyTotals()
        sectionTitle("本週守護摘要", t)
        val card = makeCard(t)
        card.addView(label("${values.sum()} 則", 34f, t.textPrimary, true, bottomPad = 6))
        card.addView(label("${stats.dates.first()} — ${stats.dates.last()} · ${stats.zone.id}", 12f, t.textMuted, bottomPad = 16))
        val tabs = LinearLayout(this)
        for ((mode, title) in listOf("trend" to "每日趨勢", "types" to "警報分類")) {
            tabs.addView(choiceButton(title, chartMode == mode, t) { chartMode = mode; renderUi(animate = true) },
                LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(0, 0, if (mode == "trend") dp(8) else 0, 0) })
        }
        card.addView(tabs)
        val line = chartMode == "trend"
        card.addView(AlertChartView(if (line) values else stats.typeTotals(), if (line) stats.dates.map { it.takeLast(2) } else AlertStatistics.labels, line, t),
            LinearLayout.LayoutParams(-1, (dp(if (line) 185 else 250) * resources.configuration.fontScale).toInt()))
        card.addView(label(if (values.sum() == 0L) "此期間尚未記錄安全警報。" else "不包括例行心跳、配對成功及測試訊息。", 12f, t.textMuted, topPad = 10))
        card.addView(textAction("統計說明與重設") {
            showSheet("關於這份守護摘要") { dialog ->
                addView(label("最近 7 個日曆天的安全警報。折線顯示每天合計，分類顯示各類型的期間總數。\n\n統計時區：${stats.zone.id}\n持續統計開始：${Instant.ofEpochMilli(stats.state.optLong("coverage_start")).atZone(stats.zone).toLocalDate()}\n\n" + if (stats.state.optBoolean("history_partial")) "已匯入可用舊記錄；早期版本只保留 60 則，因此較早數字可能不完整。" else "清除收件匣不會清除統計。", 14f, t.textSecondary, bottomPad = 18))
                addView(actionButton("重設統計數字", t.btnSecondaryBg, t.btnSecondaryText) {
                    alert().setTitle("重設統計？").setMessage("清除圖表計數，保留收件匣及去重記錄。舊警報不會重新計入。")
                        .setNegativeButton("取消", null).setPositiveButton("重設") { _, _ -> GuardEngine.resetParentStatistics(this@MainActivity); dialog.dismiss(); renderUi() }.show()
                })
            }
        })
        rootContainer.addView(card)
    }
    private inner class AlertChartView(private val values: List<Long>, private val names: List<String>, private val line: Boolean, private val t: ThemeColors) : View(this@MainActivity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = font() }
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES; contentDescription = names.indices.joinToString("；") { "${names[it]}：${values[it]} 則" } }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            paint.textSize = 12f * resources.displayMetrics.scaledDensity
            paint.strokeWidth = dp(2).toFloat()
            val maximum = (values.maxOrNull() ?: 0L).coerceAtLeast(1L)
            if (!line) {
                val row = height.toFloat() / values.size
                for (i in values.indices) {
                    val y = i * row + paint.textSize
                    paint.color = color(t.textSecondary); canvas.drawText("${names[i]} · ${values[i]}", 0f, y, paint)
                    paint.color = color(t.borderSubtle); canvas.drawRoundRect(0f, y + dp(8), width.toFloat(), y + dp(13), dp(2).toFloat(), dp(2).toFloat(), paint)
                    paint.color = color(if (i == 1) t.warmCoral else if (i == 2) t.warmHoney else t.accentSeafoam)
                    canvas.drawRoundRect(0f, y + dp(8), width * (values[i].toDouble() / maximum).toFloat(), y + dp(13), dp(2).toFloat(), dp(2).toFloat(), paint)
                }
                return
            }
            val left = maxOf(dp(24).toFloat(), paint.measureText(maximum.toString()) + dp(8)); val right = width - dp(10).toFloat()
            val top = paint.textSize + dp(16); val bottom = height - paint.textSize - dp(16)
            if (right <= left || bottom <= top) return
            val ticks = if (maximum <= 4) (0L..maximum).toList() else listOf(0L, maximum/2, maximum)
            for (tick in ticks) {
                val y = bottom - (bottom-top) * (tick.toDouble()/maximum).toFloat()
                paint.color = color(t.borderSubtle); canvas.drawLine(left, y, right, y, paint)
                paint.color = color(t.textMuted); canvas.drawText(tick.toString(), 0f, y + paint.textSize/3, paint)
            }
            fun x(i: Int) = left + (right-left)*i/(values.size-1).coerceAtLeast(1)
            fun y(i: Int) = bottom - (bottom-top)*(values[i].toDouble()/maximum).toFloat()
            paint.color = color(t.accentSeafoam)
            for (i in 1 until values.size) canvas.drawLine(x(i-1), y(i-1), x(i), y(i), paint)
            for (i in values.indices) {
                paint.color = color(t.accentSeafoam); canvas.drawCircle(x(i), y(i), dp(3).toFloat(), paint)
                paint.color = color(t.textMuted); canvas.drawText(names[i], x(i)-paint.measureText(names[i])/2, height-dp(3).toFloat(), paint)
            }
        }
    }
    private fun renderParentFamily(t: ThemeColors) {
        heading("一起建立，安心的界線。", "家庭配對、信任好友與臨時解鎖。", t)
        rootContainer.addView(row("link", "加入子女 · Add a child", "以 QR 碼當面配對子女手機", t) { startParentPairing() })
        rootContainer.addView(row("key", "15 分鐘臨時解鎖", "給需要的片刻空間，無需透露主密碼", t) { requireFreshParentAuth {
            displayed15MinCode = GuardEngine.generate15MinUnlockCode(this)
            GuardEngine.push15MinUnlockCodeFromParent(this, displayed15MinCode)
            showSheet("給孩子，15 分鐘的空間。") { dialog ->
                addView(label("把這個臨時碼告訴子女。", 14f, t.textSecondary))
                addView(label(displayed15MinCode, 32f, t.textPrimary, true, topPad = 22, bottomPad = 22).apply { gravity = Gravity.CENTER; setTextIsSelectable(true) })
                addView(label("正在透過加密通道傳送。子女手機收到後才能使用；有效期為產生後 15 分鐘。", 13f, t.textSecondary, bottomPad = 18))
                addView(actionButton("複製解鎖碼", t.btnPrimaryBg, t.btnPrimaryText) { copyText("IGGuardUnlock", displayed15MinCode); dialog.dismiss() })
            }
        } })
        renderScoresSwitch(t)
        sectionTitle("信任好友", t)
        val current = GuardEngine.getTrustedHandles(this)
        if (current.isEmpty()) rootContainer.addView(stateNote(Note.EMPTY, "尚未加入信任好友。請確認對方身份後，再加入名單。", t))
        for (handle in current) rootContainer.addView(row("users-three", "@$handle", "已獲家長批准 · 點按管理", t) {
            alert().setTitle("移除 @$handle？").setMessage("對方的新訊息將重新依照既有保護規則檢查。")
                .setNegativeButton("保留", null).setPositiveButton("移除") { _, _ ->
                    clearTrustDraft(); GuardEngine.setTrustedHandles(this, GuardEngine.getTrustedHandles(this) - handle)
                    GuardEngine.syncTrustedFromParent(this); renderUi()
                }.show()
        })
        rootContainer.addView(label("編輯信任名單", 14f, t.textPrimary, true, topPad = 22, bottomPad = 10))
        val handles = inputBox(t, "輸入好友帳號，以逗號分隔", current.joinToString(", ")); rootContainer.addView(handles)
        rootContainer.addView(actionButton("儲存並加密同步", t.btnPrimaryBg, t.btnPrimaryText) {
            val updated = handles.text.toString().split(',', '，')
            clearTrustDraft(); GuardEngine.setTrustedHandles(this, updated); GuardEngine.syncTrustedFromParent(this)
            Toast.makeText(this, "信任名單已儲存並同步。", Toast.LENGTH_SHORT).show(); renderUi()
        })
        val self = GuardEngine.getChildSelfHandle(this)
        rootContainer.addView(label(if (self.isEmpty()) "子女 Instagram 帳號會在收到通知或開啟個人檔案後自動識別。" else "子女 Instagram 帳號：@$self", 13f, t.textSecondary, topPad = 12))
    }
    // ================= QR pairing screens (design m2 rev. 3, 2.5 and 8.1-8.2) =================
    /** Parent overview: the child's verification state. NOT_VERIFIED (a 5.2 pairing) is a permanent, non-blocking banner. */
    private fun renderChildTrust(trust: GuardEngine.ChildTrust, t: ThemeColors) {
        when (trust) {
            GuardEngine.ChildTrust.NOT_VERIFIED -> rootContainer.addView(noticeCard(t, cardTag = "child_not_verified").apply {
                addView(iconLine("shield-warning", t.warmHoney, "此子女手機未核實 · This child phone is not verified", t, 15f))
                addView(label("此配對在舊版本以聊天邀請建立，未能證明訊息來自子女手機。提醒照常運作至 2027 年 3 月 31 日；由 2027 年 4 月 1 日起不再接收此配對的記錄，請重新配對。\nPaired with an older version through a chat invitation: messages cannot be proven to come from the child's phone. Alerts keep working until 31 March 2027; from 1 April 2027 this pairing delivers nothing, so pair again.", 13f, t.textSecondary, topPad = 6))
                addView(textAction("重新配對以核實 · Pair again to verify") { startParentPairing() })
            })
            GuardEngine.ChildTrust.PAIRING -> {
                rootContainer.addView(stateNote(Note.LOADING, "配對尚未完成 · 等待子女手機首次已核實回報。\nPairing not complete yet · waiting for the child phone's first verified report.", t, cardTag = "child_pairing_pending"))
                rootContainer.addView(textAction("立即重試傳送 · Retry now") { CloudRelay.retryNow(this); GuardEngine.retryPendingAlerts(this, force = true) })
                // The 10-minute rollback at once (unpin, drop M3, retire the new room), then a new QR.
                rootContainer.addView(actionButton("取消並重新配對 · Cancel and pair again", t.btnSecondaryBg, t.btnSecondaryText, critical = true) {
                    requireFreshParentAuth {
                        if (!GuardEngine.expireUnconfirmedChildPairing(this, force = true)) return@requireFreshParentAuth
                        // The relay session changed by this authenticated action itself, as in showCompromiseRecovery.
                        parentAccess.authenticated(GuardEngine.captureRelaySession(this)); renderUi(); showParentPairingSheet()
                    }
                }.apply { tag = "cancel_pairing" })
            }
            GuardEngine.ChildTrust.PAIRING_FAILED -> {
                rootContainer.addView(stateNote(Note.ERROR, if (GuardEngine.childPairingCancelled(this))
                    "配對已取消。此手機現時沒有與任何子女手機配對，之前的配對亦已結束；請用 QR 碼重新配對。\nPairing cancelled. This phone is now not paired with any child phone, and any earlier pairing has ended. Pair again with the QR code."
                    else "上次配對未完成：子女手機未在 10 分鐘內確認。此手機現時沒有與任何子女手機配對，之前的配對亦已結束；請用 QR 碼重新配對。\nThe last pairing did not complete: the child phone did not confirm within 10 minutes. This phone is now not paired with any child phone, and any earlier pairing has ended. Pair again with the QR code.", t, cardTag = "child_pairing_failed"))
                rootContainer.addView(textAction("重新配對 · Pair again") { startParentPairing() })
            }
            GuardEngine.ChildTrust.REMOTE_RESET -> {
                rootContainer.addView(stateNote(Note.WARNING, "子女手機已遠端重設。此手機現時沒有與任何子女手機配對，亦不會接受來自該手機的回報；請用 QR 碼重新配對。\nThe child phone was remotely reset. This phone is now not paired with any child phone and accepts no reports from it. Pair again with the QR code.", t, cardTag = "child_remote_reset"))
                rootContainer.addView(textAction("重新配對 · Pair again") { startParentPairing() })
            }
            else -> {}
        }
    }

    private var parentPairing: ParentPairingLink? = null
    /** P1: a fresh device authentication first, then the QR sheet. */
    private fun startParentPairing() {
        if (!GuardEngine.pairingReplacesChild(this)) { requireFreshParentAuth { showParentPairingSheet() }; return }
        alert(critical = true).setTitle("取代目前的子女手機？ · Replace the current child phone?")
            .setMessage("每個家庭只配對一部子女手機。完成新的配對後，目前的子女手機會被解除並停止傳送記錄；它會在下次連線時收到解除指令。\n" +
                "A family pairs one child phone. Once the new pairing completes, the current child phone will be released and stop sending records; it gets the release when it next connects.")
            .setNegativeButton("取消 / Cancel", null)
            .setPositiveButton("繼續配對 / Continue") { _, _ -> requireFreshParentAuth { showParentPairingSheet() } }.show()
    }
    /** P2-P6 on one sheet. FLAG_SECURE comes from showSheet; this sheet alone also keeps the screen on. Closing it for any reason but the
     *  accept's own authentication prompt ends the pairing and retires its room. */
    private fun showParentPairingSheet() {
        val t = currentTheme()
        parentPairing?.cancel()
        showTrackedSheet("加入子女 · Add a child", onDismiss = { if (!parentAuthPending) parentPairing?.cancel() }) { dialog ->
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val body = this
            var link: ParentPairingLink? = null
            link = ParentPairingLink(this@MainActivity, { link?.let { if (parentPairing === it && !isDestroyed) fillParentPairing(body, dialog, it, t) } })
            parentPairing = link
            try { link.begin() } catch (_: Exception) {
                // The parent signing key cannot be loaded (e.g. its first creation was interrupted): say so; nothing is replaced.
                addView(stateNote(Note.ERROR, parentCancelText(Pairing.CancelReason.KEY_UNAVAILABLE), t, cardTag = "pairing_stopped"))
                addView(actionButton("關閉 · Close", t.btnSecondaryBg, t.btnSecondaryText) { dialog.dismiss() })
            }
        }
    }
    private fun parentCancelText(reason: Pairing.CancelReason): String = when (reason) {
        Pairing.CancelReason.EXPIRED -> "QR 碼已過期，或未在 10 分鐘內接受。\nThe QR code expired, or it was not accepted within 10 minutes."
        Pairing.CancelReason.SECOND_PHONE -> "此 QR 碼被多於一部手機使用，已停止。請再試一次。\nMore than one phone used this QR code; pairing stopped. Please try again."
        Pairing.CancelReason.WRONG_DIGITS -> "輸入錯誤 3 次，配對已取消。請重新顯示 QR 碼。\nWrong digits 3 times; pairing cancelled. Show a new QR code."
        Pairing.CancelReason.USER_CANCEL -> "配對已取消。\nPairing cancelled."
        Pairing.CancelReason.BAD_MESSAGE -> "收到未能核實的回應，配對已停止。請再試一次。\nA reply could not be verified; pairing stopped. Please try again."
        Pairing.CancelReason.KEY_UNAVAILABLE -> "未能使用此手機的簽署金鑰。\nThis phone's signing key is unavailable."
    }
    private fun fillParentPairing(body: LinearLayout, dialog: Dialog, link: ParentPairingLink, t: ThemeColors) {
        val state = link.machine.state
        val phase = listOf(state, link.relay, link.outcome, link.qrText != null)
        val countdown = body.findViewWithTag<TextView>("pairing_countdown")
        fun left(): String { val s = ((link.expiresAt - System.currentTimeMillis()).coerceAtLeast(0L) + 999L) / 1000L; return "尚餘 %d:%02d · %d:%02d left".format(s / 60, s % 60, s / 60, s % 60) }
        // The ticker reports every second: only the countdown changes then, so a field being typed in is never rebuilt.
        if (body.tag == phase) { countdown?.text = left(); return }
        body.tag = phase
        body.removeAllViews()
        fun closeButton(title: String = "取消 · Cancel") = body.addView(actionButton(title, t.btnSecondaryBg, t.btnSecondaryText) { dialog.dismiss() })
        fun stopped(text: String) {
            body.addView(stateNote(Note.ERROR, text, t, cardTag = "pairing_stopped"))
            body.addView(actionButton("再試一次 · Try again", t.btnPrimaryBg, t.btnPrimaryText) { dialog.dismiss(); startParentPairing() })
            closeButton("關閉 · Close")
        }
        when {
            link.outcome == ParentPairingLink.Outcome.ACCEPTED -> {
                body.addView(stateNote(Note.LOADING, "正在完成… 子女手機收到確認後，會傳送第一個已核實回報。\nFinishing… once the child phone has the confirmation, it sends its first verified report.", t, cardTag = "pairing_finishing"))
                closeButton("關閉 · Close")
            }
            link.outcome == ParentPairingLink.Outcome.NOT_STORED -> stopped("中繼或本機未能完成，原有配對沒有改變。請再試一次。\nThe pairing could not be stored; nothing changed. Please try again.")
            link.relay == ParentPairingLink.Relay.TOO_OLD -> stopped("中繼站版本過舊，未能安全配對。\nThe relay is too old to pair safely.")
            link.relay == ParentPairingLink.Relay.REFUSED -> stopped("中繼站拒絕了配對房間，配對已停止。\nThe relay refused the pairing room; pairing stopped.")
            link.relay == ParentPairingLink.Relay.UNREACHABLE -> stopped("未能連接中繼站，請檢查網絡後再試。\nThe relay could not be reached. Check the connection and try again.")
            state is ParentPairingState.Cancelled -> stopped(parentCancelText(state.reason))
            state is ParentPairingState.ShowingQr -> {
                val qr = link.qrText
                if (qr == null) body.addView(stateNote(Note.LOADING, "正在連接中繼站…\nConnecting to the relay…", t, cardTag = "pairing_connecting"))
                else {
                    body.addView(label("此 QR 碼 3 分鐘內有效，只可使用一次。請在子女手機掃描，勿拍照或傳送。\nValid for 3 minutes, once. Scan it with the child's phone; do not photograph or send it.", 14f, t.textSecondary, bottomPad = 14))
                    val size = minOf(resources.displayMetrics.widthPixels - dp(48), dp(300))
                    body.addView(QrImageView(this).apply { show(qr, size); tag = "pairing_qr" }, LinearLayout.LayoutParams(size, size).apply { gravity = Gravity.CENTER_HORIZONTAL })
                    body.addView(label(left(), 14f, t.textPrimary, true, topPad = 12, bottomPad = 12).apply { gravity = Gravity.CENTER; tag = "pairing_countdown" })
                }
                closeButton()
            }
            state is ParentPairingState.EnterDigits || state is ParentPairingState.ReadyToAccept -> {
                val ready = state is ParentPairingState.ReadyToAccept
                val pictures = (state as? ParentPairingState.EnterDigits)?.pictures ?: (state as ParentPairingState.ReadyToAccept).pictures
                val model = (state as? ParentPairingState.EnterDigits)?.childModel ?: (state as ParentPairingState.ReadyToAccept).childModel
                body.addView(label("已收到子女手機回應 · The child phone answered", 13f, t.textSecondary, bottomPad = 8))
                body.addView(label("輸入子女手機上的 6 位數字\nType the 6 digits shown on the child's phone", 16f, t.textPrimary, true, bottomPad = 12))
                if (!ready) {
                    val tries = (state as ParentPairingState.EnterDigits).triesLeft
                    val field = EditText(this).apply {
                        hint = "6 位數字"; contentDescription = "子女手機上的 6 位數字 · The 6 digits on the child's phone"; textSize = 26f; typeface = font(true); gravity = Gravity.CENTER
                        letterSpacing = .3f; setTextColor(color(t.textPrimary)); setHintTextColor(color(t.textMuted)); background = focusSurface(t.bgInput, 10, t.accentPowderSky)
                        // Digits only, no suggestions, never offered to autofill; at most 6.
                        inputType = InputType.TYPE_CLASS_NUMBER; importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                        filters = arrayOf(android.text.InputFilter.LengthFilter(6)); isSaveEnabled = false; tag = "pairing_digits"
                        minimumHeight = dp(58); setPadding(dp(14), dp(12), dp(14), dp(12))
                        addTextChangedListener(object : TextWatcher {
                            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                            override fun afterTextChanged(s: Editable?) {
                                val typed = s?.toString().orEmpty()
                                if (typed.length == 6) post { if (parentPairing === link) link.enterDigits(typed) }
                            }
                        })
                    }
                    body.addView(field, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(8)) })
                    if (tries < Pairing.MAX_DIGIT_TRIES) body.addView(label("數字不正確，尚餘 $tries 次。\nWrong digits, $tries tries left.", 13f, t.warmCoral, bottomPad = 8).apply { tag = "pairing_tries" })
                    field.requestFocus()
                } else body.addView(stateNote(Note.EMPTY, "數字正確 · The digits match", t, cardTag = "pairing_digits_ok"))
                body.addView(label(pictures, 30f, t.textPrimary, topPad = 6, bottomPad = 4).apply { gravity = Gravity.CENTER; tag = "pairing_pictures" })
                body.addView(label("兩部手機會顯示相同圖案，只作輔助。\nBoth phones show the same pictures, as a visual aid only.", 12f, t.textMuted, bottomPad = 10).apply { gravity = Gravity.CENTER })
                body.addView(label("子女手機型號（由該手機提供）：$model\nChild phone model (as it reports it): $model", 13f, t.textSecondary, bottomPad = 12))
                body.addView(actionButton("接受 · Accept", t.btnPrimaryBg, t.btnPrimaryText, critical = true) {
                    // The parent confirms with a fresh device authentication, as for every other sensitive parent action.
                    requireFreshParentAuth {
                        val ok = link.accept()
                        // Owner's decision: the relay session change caused by this Accept, which the fresh authentication just
                        // approved, does not lock the parent UI (the pattern of showCompromiseRecovery). Exactly the session that
                        // completeParentPairing created is granted, never a fresh one: any later change still locks.
                        if (ok) link.acceptedSession?.let { parentAccess.authenticated(it) }
                        Toast.makeText(this, if (ok) "已接受，等待子女手機確認。" else "未能完成配對，原有配對沒有改變。", Toast.LENGTH_LONG).show()
                        renderUi()
                    }
                }.apply { isEnabled = ready; tag = "pairing_accept" })
                body.addView(label("按「接受」後，用此手機的螢幕鎖或生物辨識確認。\nAfter Accept you confirm it with this phone's screen lock or biometric.", 12f, t.textMuted, bottomPad = 6))
                closeButton()
            }
            else -> closeButton("關閉 · Close")
        }
        if (dialog.isShowing) fitSheet(dialog)
    }

    private val CAMERA_REQUEST = 102
    private var childPairing: ChildPairingLink? = null
    private var pairingScanner: QrScannerView? = null
    private var childSheetBody: LinearLayout? = null
    private var childSheetDialog: Dialog? = null
    /** The scanned QR text, in memory only until the join press. */
    private var scannedQr: String? = null
    private var scanProblem: String? = null
    private var cameraAsked = false
    private var cameraDenied = false
    /** The camera could not be opened (none, in use, failed): no preview until the person tries again. */
    private var cameraBroken = false
    /** A paired child's parent password, checked at the join press and held only until the pairing is stored. */
    private var childJoinPassword: String? = null
    private var childPairingStored: Boolean? = null
    /** C1-C6 on one sheet: scan, join consent, digits, waiting for the parent, done. FLAG_SECURE comes from showSheet. */
    private fun showChildPairingSheet() {
        if (showPairingBlock()) return
        childPairing?.cancel(); childPairing = null
        scannedQr = null; scanProblem = null; cameraAsked = false; cameraDenied = false; cameraBroken = false; childJoinPassword = null; childPairingStored = null
        var mine: Dialog? = null
        showTrackedSheet("加入家庭 · Join a family", onDismiss = {
            if (childSheetDialog === mine) {
                pairingScanner?.stop(); pairingScanner = null
                if (childPairing?.machine?.state != ChildPairingState.Paired) { childPairing?.cancel(); childPairing = null; childJoinPassword = null }
                scannedQr = null; childSheetBody = null; childSheetDialog = null
            }
        }) { dialog ->
            mine = dialog
            childSheetBody = this; childSheetDialog = dialog
            fillChildPairing()
        }
    }
    /** What the scanner callback does with a decoded text (and nothing else calls it): check its form, then show the join consent. */
    private fun onQrScanned(text: String) {
        pairingScanner = null
        val now = System.currentTimeMillis()
        val fields = text.split('.')
        scanProblem = when {
            PairingCrypto.parsePairingQr(text, now) != null -> null
            fields.size == 4 && fields[0] == PairingCrypto.QR_PREFIX && (fields[3].toLongOrNull() ?: Long.MAX_VALUE) <= now -> "QR 碼已過期。請在家長手機重新顯示 QR 碼。\nThe QR code has expired. Show a new one on the parent phone."
            fields.size == 4 && PairingCrypto.parsePairingQr(text, (fields[3].toLongOrNull() ?: 0L) - 1) != null -> "此手機的時間比家長手機慢。請檢查手機的日期和時間，然後再掃描。\nThis phone's clock is behind the parent phone. Check the date and time, then scan again."
            else -> "這不是 Convoy 配對碼。\nThis is not a Convoy pairing code."
        }
        scannedQr = if (scanProblem == null) text else null
        childSheetBody?.tag = null
        fillChildPairing()
    }
    private fun childCancelText(link: ChildPairingLink, reason: Pairing.CancelReason): String = if (link.parentStopped)
        "家長手機已停止這次配對（已取消或 QR 碼已過期）。請在家長手機重新顯示 QR 碼，再掃描一次。\nThe parent phone stopped this pairing (cancelled, or its QR code expired). Show a new QR code on the parent phone and scan it again."
        else if (link.refused) "中繼站未能完成配對，請重新配對。\nThe relay could not complete the pairing. Please pair again." else when (reason) {
        Pairing.CancelReason.EXPIRED -> "配對已逾時：QR 碼已過期，或家長未確認。請重新配對。\nPairing timed out: the QR code expired or the parent did not confirm. Please pair again."
        Pairing.CancelReason.BAD_MESSAGE -> "這不是有效的 Convoy 配對碼，或配對訊息未能核實。請重新掃描家長手機上的新 QR 碼。\nNot a valid Convoy pairing code, or a pairing message could not be verified. Scan a new QR code."
        Pairing.CancelReason.KEY_UNAVAILABLE -> "此手機未能建立配對金鑰，請稍後再試。\nThis phone could not create its pairing key. Please try later."
        else -> "配對已取消。\nPairing cancelled."
    }
    private fun fillChildPairing() {
        val body = childSheetBody ?: return; val dialog = childSheetDialog ?: return
        if (isDestroyed) return
        val t = currentTheme()
        val link = childPairing
        // The camera grant is part of the state: a first "Allow" changes nothing else, and the scanner must appear at once.
        val phase = listOf(link?.machine?.state, link?.refused, scannedQr != null, scanProblem, cameraDenied, cameraBroken, childPairingStored,
            checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED, link?.parentStopped)
        fun waited(): String {
            val gone = ((System.currentTimeMillis() - (link?.confirmedAt ?: 0L)).coerceAtLeast(0L)) / 1000L
            val left = (Pairing.CONFIRM_WINDOW_MS / 1000L - gone).coerceAtLeast(0L)
            return "已等候 %d:%02d · 尚餘 %d:%02d\nWaited %d:%02d · %d:%02d left".format(gone / 60, gone % 60, left / 60, left % 60, gone / 60, gone % 60, left / 60, left % 60)
        }
        // The ticker reports every second: only the waiting time changes then.
        if (body.tag == phase) { body.findViewWithTag<TextView>("child_wait_time")?.text = waited(); return }
        body.tag = phase
        pairingScanner?.stop(); pairingScanner = null
        body.removeAllViews()
        fun closeButton(title: String = "取消 · Cancel") = body.addView(actionButton(title, t.btnSecondaryBg, t.btnSecondaryText) { dialog.dismiss() })
        fun again(text: String) {
            body.addView(stateNote(Note.ERROR, text, t, cardTag = "child_pairing_stopped"))
            body.addView(actionButton("重新掃描 · Scan again", t.btnPrimaryBg, t.btnPrimaryText) { showChildPairingSheet() })
            closeButton("關閉 · Close")
        }
        val state = link?.machine?.state
        when {
            link == null && scannedQr == null -> {
                // C1/C2: the camera permission is asked here, and only here.
                body.addView(label("只可掃描你自己家長手機上的 QR 碼。\nScan only the QR code shown on your own parent's phone.", 14f, t.textSecondary, bottomPad = 12))
                scanProblem?.let { body.addView(stateNote(Note.WARNING, it, t, cardTag = "scan_problem")) }
                val granted = checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                when {
                    !granted && cameraDenied -> {
                        body.addView(stateNote(Note.WARNING, "需要相機才可掃描家長手機上的 QR 碼。\nThe camera is needed to scan the QR code on the parent's phone.", t, cardTag = "camera_denied"))
                        body.addView(actionButton("開啟 App 設定 · Open app settings", t.btnPrimaryBg, t.btnPrimaryText) {
                            launchSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                        })
                    }
                    !granted -> {
                        body.addView(stateNote(Note.LOADING, "正在等待相機權限…\nWaiting for camera permission…", t))
                        if (!cameraAsked) { cameraAsked = true; requestPermissions(arrayOf(android.Manifest.permission.CAMERA), CAMERA_REQUEST) }
                    }
                    cameraBroken -> body.addView(actionButton("再試一次 · Try again", t.btnPrimaryBg, t.btnPrimaryText) { cameraBroken = false; scanProblem = null; fillChildPairing() })
                    else -> {
                        val scanner = QrScannerView(this).apply {
                            tag = "qr_scanner"
                            onResult = { onQrScanned(it) }
                            onError = { e ->
                                if (e == ScanError.NO_PERMISSION) cameraDenied = true
                                else cameraBroken = true
                                if (e != ScanError.NO_PERMISSION) scanProblem = when (e) {
                                    ScanError.NO_CAMERA -> "此手機沒有可用的相機。\nThis phone has no usable camera."
                                    ScanError.CAMERA_IN_USE -> "相機正由其他 App 使用，請關閉後再試。\nAnother app is using the camera."
                                    else -> "未能開啟相機，請再試一次。\nThe camera could not be opened. Please try again."
                                }
                                pairingScanner = null; fillChildPairing()
                            }
                        }
                        pairingScanner = scanner
                        body.addView(scanner, LinearLayout.LayoutParams(-1, dp(300)).apply { setMargins(0, 0, 0, dp(12)) })
                        body.post { if (pairingScanner === scanner && activityVisible) scanner.start() }
                    }
                }
                closeButton()
            }
            link == null -> {
                // C3: consent and the warning, then the join press creates K and sends M1.
                val repair = GuardEngine.isChildMode(this)
                body.addView(label(if (repair) "此手機已配對子女保護。更換家庭需要目前的家長主密碼，並會先通知原本的家長手機。\nThis phone is already a protected child phone: the current parent password is needed, and the old parent phone is told first."
                    else "確認後，此手機會切換為子女模式，並加入掃描到的家庭。\nAfter you confirm, this phone switches to child mode and joins the family you scanned.", 14f, t.textSecondary, bottomPad = 12))
                body.addView(stateNote(Note.WARNING, "只可掃描你自己家長手機上的 QR 碼。掃描他人的 QR 碼會把此手機連到該人。\nScan only the QR code on your own parent's phone. Scanning someone else's code connects this phone to that person.", t, cardTag = "join_warning"))
                val password = if (repair) inputBox(t, "家長主密碼", "", isPassword = true, remember = false).apply { tag = "repair_password" }.also { body.addView(it) } else null
                val error = label("", 13f, t.warmCoral); body.addView(error)
                body.addView(actionButton("加入此家庭 · Join this family", t.btnPrimaryBg, t.btnPrimaryText, critical = true) {
                    val qr = scannedQr ?: return@actionButton
                    if (!repair) { startChildPairing(qr); return@actionButton }
                    val remaining = GuardEngine.checkPasswordLockoutRemainingSec(this)
                    if (remaining > 0) { error.text = "輸入已鎖定，請等 $remaining 秒。"; return@actionButton }
                    if (!GuardEngine.passwordCheckRunning.compareAndSet(false, true)) { error.text = PASSWORD_CHECK_BUSY; return@actionButton }
                    val entered = password?.text?.toString().orEmpty()
                    val join = body.findViewWithTag<Button>("join_family"); val shown = body.tag
                    fun checking(on: Boolean) { join.isEnabled = !on; password?.isEnabled = !on; join.text = if (on) "核對中… · Checking…" else "加入此家庭 · Join this family" }
                    checking(true)
                    // PBKDF2 takes seconds on older phones: off the main thread, so taps meanwhile hit this screen (Join disabled), not the next one.
                    thread {
                        val ok = entered.isNotBlank() && GuardEngine.verifyParentPassword(this, entered)
                        runOnUiThread {
                            GuardEngine.passwordCheckRunning.set(false)
                            GuardEngine.recordPasswordAttempt(this, ok) // every attempt counts, even if the sheet was closed meanwhile
                            if (isDestroyed || childSheetBody !== body || body.tag !== shown) return@runOnUiThread
                            if (!ok) { checking(false); error.text = "家長主密碼不正確。"; password?.error = "請重新輸入"; return@runOnUiThread }
                            childJoinPassword = entered
                            startChildPairing(qr)
                        }
                    }
                }.apply { tag = "join_family" })
                closeButton()
            }
            state is ChildPairingState.Cancelled -> again(childCancelText(link, state.reason))
            state == ChildPairingState.AwaitingReply -> { body.addView(stateNote(Note.LOADING, "已傳送到家長手機 · 等待家長手機回應\nSent to the parent phone · waiting for its answer", t, cardTag = "child_waiting_reply")); closeButton() }
            state is ChildPairingState.ShowingDigits -> {
                body.addView(label("請讓家長在家長手機輸入以下 6 位數字\nAsk your parent to type these 6 digits on the parent phone", 16f, t.textPrimary, true, bottomPad = 12))
                body.addView(label(state.digits.chunked(3).joinToString(" "), 42f, t.textPrimary, true, bottomPad = 8).apply { gravity = Gravity.CENTER; tag = "child_digits"; letterSpacing = .08f })
                body.addView(label(state.pictures, 30f, t.textPrimary, bottomPad = 4).apply { gravity = Gravity.CENTER; tag = "child_pictures" })
                body.addView(label("兩部手機會顯示相同圖案，只作輔助。\nBoth phones show the same pictures, as a visual aid only.", 12f, t.textMuted, bottomPad = 12).apply { gravity = Gravity.CENTER })
                body.addView(actionButton("確認加入 · Confirm", t.btnPrimaryBg, t.btnPrimaryText, critical = true) { link.confirm() }.apply { tag = "child_confirm" })
                closeButton()
            }
            state == ChildPairingState.AwaitingComplete -> {
                body.addView(stateNote(Note.LOADING, "等待家長確認\nWaiting for the parent to confirm", t, cardTag = "child_waiting_parent"))
                body.addView(label(waited(), 14f, t.textPrimary, true, topPad = 8, bottomPad = 8).apply { tag = "child_wait_time" })
                body.addView(label("家長手機可能已停止這次配對。如等候太久，可按「取消」，再掃描家長手機上的新 QR 碼。\nThe parent phone may have stopped this pairing. If this takes too long, tap Cancel and scan a new QR code on the parent phone.", 13f, t.textSecondary, bottomPad = 12).apply { tag = "child_wait_hint" })
                closeButton()
            }
            state == ChildPairingState.Paired -> when (childPairingStored) {
                null -> body.addView(stateNote(Note.LOADING, "正在完成配對…\nFinishing the pairing…", t))
                true -> {
                    body.addView(stateNote(Note.EMPTY, "家長已確認此手機。\nThe parent confirmed this phone.", t, cardTag = "child_paired"))
                    body.addView(actionButton("繼續 · Continue", t.btnPrimaryBg, t.btnPrimaryText) { dialog.dismiss(); finishPairing(); openDirectNotificationToggle() })
                }
                false -> again("未能完成配對：家長主密碼不正確或輸入已鎖定。原有配對沒有改變。\nThe pairing could not be completed: wrong parent password or input locked. Nothing changed.")
            }
            else -> closeButton("關閉 · Close")
        }
        if (dialog.isShowing) fitSheet(dialog)
    }
    private fun startChildPairing(qr: String) {
        var link: ChildPairingLink? = null
        link = ChildPairingLink(this, { if (childPairing === link) fillChildPairing() }, { result ->
            val password = childJoinPassword
            childJoinPassword = null
            // Storing may wait up to 6 s for the old family's notice (a paired child): off the main thread.
            thread {
                val ok = GuardEngine.completeChildPairing(applicationContext, result, password, noticeWaitMs = 6_000L)
                // A first pairing (no password: not a re-pair) changes role and session, and that render closes this sheet: finish here, not on its Continue.
                runOnUiThread { childPairingStored = ok; fillChildPairing(); if (ok && password == null && !isDestroyed) finishPairing() }
            }
        })
        childPairing = link
        if (!link.join(qr)) scannedQr = null
        fillChildPairing()
    }
    private fun clearTrustDraft() { inputDrafts.remove("輸入好友帳號，以逗號分隔") }
    private fun addTrustedSender(handle: String) {
        clearTrustDraft()
        val next = GuardEngine.getTrustedHandles(this).toMutableSet(); next.add(handle)
        GuardEngine.setTrustedHandles(this, next)
        Toast.makeText(this, "已將 @$handle 加入信任名單。", Toast.LENGTH_SHORT).show(); renderUi()
    }
    private fun alertTitle(reason: String) = when (reason) {
        "sexual_or_grooming_content" -> "一則需要優先留意的對話"
        "stranger_message" -> "未在信任名單中的訊息 / 回覆"
        "tamper_attempt" -> "防篡改或裝置離線提醒"
        "heartbeat_ok" -> "子女手機連線回報"
        "pairing_verified" -> "子女手機已成功配對"
        "test_alert" -> "子女手機連線測試"
        "unlock_used" -> "子女使用了臨時解鎖碼"
        "archive_recovery" -> "待復原的舊格式資料"
        "conversation_context" -> "對話訊息"
        "flagged_contact_follow_up" -> "已標記聯絡對象的後續訊息"
        else -> "家庭守護通知"
    }
    private fun evidenceTime(stamp: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.TRADITIONAL_CHINESE).format(Date(stamp))
    private fun shortEvidence(text:String):String {
        val clean=text.replace('\n',' ')
        val end=clean.offsetByCodePoints(0,minOf(96,clean.codePointCount(0,clean.length)))
        return clean.substring(0,end)+(if(end<clean.length)"…" else "")
    }
    private fun evidenceSpeaker(message:JSONObject):String {
        val author=message.optString("author").ifBlank{if(message.optString("type")=="captured_message")"作者未確認" else message.optString("sender","未識別來源")}
        return when(message.optString("direction","unknown")) {
            "incoming"->"對方 · $author"
            "outgoing"->"子女 · ${message.optString("author").ifBlank{"子女"}}"
            else->if(message.optBoolean("content_free"))"帳戶 · $author" else "方向未能判斷 · $author"
        }
    }
    private val NO_TEXT_NOTE="此提醒不含訊息文字：僅分數模式不分享訊息內容。"
    private fun evidenceBody(message:JSONObject):String =
        message.optString("text").ifBlank{
            if(message.optBoolean("content_free"))NO_TEXT_NOTE else if(message.optString("type")!="captured_message")ScoresUi.NO_TEXT_PLAIN else ""
        }
    private fun evidenceSource(message:JSONObject):String=when(message.optString("source_context")) {
        "notification"->if(message.optString("conversation_kind")=="group")"群組通知擷取" else "通知擷取"
        "visible_dm"->"畫面擷取 · 方向按介面資訊及版面辨識"
        "comment_story_draft"->"舊版留言 / 限時動態草稿 · 未確認送出"
        else->"舊記錄 · 來源未詳"
    }
    private fun evidenceClock(message:JSONObject):String {
        val stamp=message.optLong("ts_ms").takeIf{it>0}?:message.optLong("received_at_ms")
        val qualifier=if(message.optString("type")!="captured_message")"舊記錄時間" else if(message.optLong("message_time_ms")>0)"訊息時間" else "擷取時間"
        return "${evidenceTime(stamp)} · $qualifier"+if(message.has("screen_order"))" · 按畫面先後排列" else ""
    }
    private fun reviewCategory(reason:String):String=when(reason){
        "sexual_or_grooming_content"->"關鍵字相符"
        "stranger_message"->"未核准聯絡對象"
        "flagged_contact_follow_up"->"已標記對象後續"
        else->"裝置提醒"
    }
    private fun signalLabels(message:JSONObject):String {
        val signals=message.optJSONArray("signals")?:return ""
        return (0 until signals.length()).mapNotNull{when(signals.optString(it)){
            "image_request"->"影像要求";"secrecy"->"保密用語";"sexual_language"->"性相關用語";else->null
        }}.distinct().joinToString(" / ")
    }
    private fun openConversation(key:String?,label:String) {
        evidenceConversationKey=key;evidenceSender=label;evidencePage=0;alertFilter="all";evidenceDirection="all"
        scrollView.scrollTo(0,0);renderUi(animate=true)
    }
    private fun renderParentAlerts(t: ThemeColors) {
        GuardEngine.prepareParentArchive(this)
        val inConversation=evidenceSender!=null
        heading(evidenceSender ?: when(evidenceSection){"review"->"需要跟進";"device"->"裝置與傳送";else->"對話記錄"},
            if(inConversation)"只顯示實際擷取內容，按可確認的先後順序整理。" else "按對話對象整理。一般訊息與需要跟進的內容分開。",t)
        if(inConversation)backLink("返回對話列表") { evidenceSender=null;evidenceConversationKey=null;evidenceDirection="all";evidencePage=0;renderUi(animate=true) }
        else {
            val tabs=LinearLayout(this)
            for((key,title) in listOf("conversations" to "對話記錄","review" to "需要跟進","device" to "裝置與傳送")) {
                tabs.addView(choiceButton(title,evidenceSection==key,t){evidenceSection=key;evidencePage=0;alertFilter="all";evidenceDirection="all";renderUi(animate=true)}.apply{textSize=13f},
                    LinearLayout.LayoutParams(0,-2,1f).apply{setMargins(0,0,if(key=="device")0 else dp(8),0)})
            }
            rootContainer.addView(tabs)
            rootContainer.addView(textAction(if(GuardEngine.parentFullCaptureConfirmed(this))"雙向擷取已確認 · 查看範圍" else "雙向擷取尚未確認 · 前往設定"){showFullCaptureSetup(t)})
        }
        if(inConversation)rootContainer.addView(textAction("排序與擷取說明") {
            showSheet("排序與擷取說明") { dialog ->
                addView(label("按訊息時間或重疊畫面的先後排列；無法對齊的片段保留擷取順序。通知時間與畫面順序可能無法互相對齊。\n\n只包含實際擷取內容。未顯示或未擷取的訊息無法復原，這不是完整 Instagram 對話備份。",14f,t.textSecondary,bottomPad=18))
                addView(actionButton("關閉",t.btnSecondaryBg,t.btnSecondaryText){dialog.dismiss()})
            }
        })
        val search=inputBox(t,"搜尋帳號或訊息",evidenceSearch,remember=false).apply{tag="evidence_search";maxLines=1}
        val searchUpdate=Runnable{evidencePage=0;renderUi()}
        search.addTextChangedListener(object:TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int)=Unit
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){evidenceSearch=s?.toString()?:"";uiHandler.removeCallbacks(searchUpdate);uiHandler.postDelayed(searchUpdate,300)}
            override fun afterTextChanged(s:Editable?)=Unit
        });rootContainer.addView(search)
        val tools=mutableListOf<Button>()
        tools.add(textAction(if(evidenceDays==0)"全部日期" else "最近 $evidenceDays 日"){
            alert().setTitle("篩選日期").setItems(arrayOf("全部日期","最近 7 日","最近 30 日","最近 90 日")){_,i->evidenceDays=listOf(0,7,30,90)[i];evidencePage=0;renderUi()}.show()
        })
        tools.add(textAction(if(evidenceSaved)"已儲存 ✓" else "只看已儲存"){evidenceSaved=!evidenceSaved;evidencePage=0;renderUi()}.apply{isSelected=evidenceSaved})
        if(inConversation)tools.add(textAction(when(evidenceDirection){"incoming"->"只看對方";"outgoing"->"只看子女";"unknown"->"方向未詳";else->"全部方向"}){
            alert().setTitle("訊息方向").setItems(arrayOf("全部方向","只看對方","只看子女","方向未能判斷")){_,i->evidenceDirection=listOf("all","incoming","outgoing","unknown")[i];evidencePage=0;renderUi()}.show()
        })
        if(evidenceSection=="review" && !inConversation)rootContainer.addView(textAction(when(evidenceReview){"follow_up"->"篩選：待跟進";"reviewed"->"篩選：已查看";"all"->"篩選：全部狀態";else->"篩選：新記錄"}){
            alert().setTitle("跟進狀態").setItems(arrayOf("新記錄","待跟進","已查看","全部狀態")){_,i->evidenceReview=listOf("new","follow_up","reviewed","all")[i];evidencePage=0;renderUi()}.show()
        })
        if(evidenceSection!="device")tools.add(textAction(when(alertFilter){"high"->"關鍵字";"stranger"->"未核准";"followup"->"後續訊息";else->"內容分類"}){
            alert().setTitle("內容分類").setItems(arrayOf("全部內容","關鍵字相符","未核准聯絡對象","已標記對象後續")){_,i->alertFilter=listOf("all","high","stranger","followup")[i];evidencePage=0;renderUi()}.show()
        })
        for(pair in tools.chunked(2)) {
            val toolRow=LinearLayout(this)
            pair.forEachIndexed{index,button->toolRow.addView(button,LinearLayout.LayoutParams(0,-2,1f).apply{setMargins(0,dp(4),if(index==0 && pair.size>1)dp(8)else 0,dp(6))})}
            rootContainer.addView(toolRow)
        }
        if(evidencePage==0)evidenceCursors.clear()
        val query=EvidenceQuery(evidenceSearch,if(!inConversation && evidenceSection=="device")"any" else alertFilter,if(evidenceConversationKey==null)evidenceSender?.trim()?.removePrefix("@")?.lowercase(Locale.ROOT) else null,
            evidenceSaved,if(evidenceDays==0)0 else System.currentTimeMillis()-evidenceDays*86_400_000L,if(inConversation)0 else evidencePage,
            evidenceConversationKey,if(inConversation)"conversations" else evidenceSection,if(!inConversation && evidenceSection=="review")evidenceReview else "all",evidenceDirection,
            if(inConversation)evidenceCursors[evidencePage] else null)
        val scope=GuardEngine.getChannelTopic(this);val session=GuardEngine.captureRelaySession(this);val container=rootContainer
        val results=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};container.addView(results)
        results.addView(stateNote(Note.LOADING,"正在搜尋本機記錄…",t,"evidence_loading"))
        thread {
            try {
                val contacts:List<ConversationSummary>;val messages:List<JSONObject>;val incomplete:Int
                FamilyEvidence(applicationContext).use { db ->
                    contacts=if(!inConversation && query.section!="device")db.conversations(scope,query)else emptyList()
                    messages=if(inConversation)db.timeline(scope,query)else if(query.section=="device")db.messages(scope,query)else emptyList()
                    incomplete=db.incompleteCount(scope)
                }
                uiHandler.post {
                    if(isDestroyed || rootContainer!==container || query.text!=evidenceSearch || GuardEngine.captureRelaySession(this)!=session)return@post
                    results.removeAllViews()
                    val more=if(!inConversation && query.section!="device")contacts.size>30 else messages.size>30
                    if(!inConversation && query.section!="device") {
                        for(contact in contacts.take(30)) {
                            val category=contact.categories.map{reviewCategory(it)}.distinct().joinToString(" / ").ifEmpty{"一般對話"}
                            val caption=(if(contact.kind=="group")"群組 · " else "")+"${contact.account.ifBlank{"帳號未確認"}} · 最近記錄 ${evidenceTime(contact.latest)}\n${contact.count} 則記錄 · ${contact.unreviewed} 則需查看 · $category\n${shortEvidence(contact.preview.ifBlank{if(contact.contentFree)NO_TEXT_NOTE else ScoresUi.NO_TEXT_PLAIN})}"
                            val display=ConversationCapture.displayLabel(contact.label,contact.unidentified)
                            results.addView(row("chat-circle",display,caption,t){openConversation(contact.key,display)}.apply{
                                tag="conversation_${contact.key}";getChildAt(0).tag="evidence_contact_${contact.label.trim().removePrefix("@").lowercase(Locale.ROOT)}"
                            })
                        }
                        if(contacts.isEmpty())results.addView(stateNote(Note.EMPTY,if(query.section=="review")"沒有符合條件的跟進項目。" else "尚無符合條件的對話記錄。",t))
                    } else {
                        var day=""
                        val shown=if(inConversation)messages.takeLast(30)else messages.take(30)
                        if(inConversation && shown.isNotEmpty()) {
                            val oldest=shown.first()
                            evidenceCursors[evidencePage+1]=EvidenceCursor(oldest.optLong("_sort_stamp"),oldest.optLong("_sort_order"),oldest.getString("_id"))
                        }
                        for(message in shown) {
                            val stamp=message.optLong("ts_ms").takeIf{it>0}?:message.optLong("received_at_ms")
                            val date=(if(message.optLong("message_time_ms")>0)"訊息日期 " else "擷取日期 ")+evidenceTime(stamp).substringBefore(" ")
                            if(date!=day){day=date;results.addView(label(day,12f,t.textMuted,topPad=18,bottomPad=8))}
                            if(inConversation)results.addView(conversationBubble(message,t))
                            else results.addView(row("info",alertTitle(message.optString("reason")),"${evidenceClock(message)}\n${shortEvidence(evidenceBody(message))}",t){showAlertDetails(message,t)})
                        }
                        if(shown.isEmpty())results.addView(stateNote(Note.EMPTY,"沒有符合條件的訊息。",t))
                    }
                    if(incomplete>0)results.addView(stateNote(Note.WARNING,"另有 $incomplete 則訊息尚未完整接收，內容可能缺少。",t))
                    val paging=LinearLayout(this)
                    if(evidencePage>0)paging.addView(textAction(if(inConversation)"較新訊息" else "上一頁"){evidencePage--;scrollView.scrollTo(0,0);renderUi()},LinearLayout.LayoutParams(0,-2,1f))
                    paging.addView(label("第 ${evidencePage+1} 頁",13f,t.textMuted).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(0,dp(48),1f))
                    if(more)paging.addView(textAction(if(inConversation)"較早訊息" else "下一頁"){evidencePage++;scrollView.scrollTo(0,0);renderUi()},LinearLayout.LayoutParams(0,-2,1f))
                    results.addView(paging)
                }
            } catch(_:Exception) {
                uiHandler.post{if(!isDestroyed && rootContainer===container && GuardEngine.captureRelaySession(this)==session){results.removeAllViews();results.addView(stateNote(Note.ERROR,"未能讀取本機記錄，請保留資料並重試。",t))}}
            }
        }
        rootContainer.addView(textAction("重新同步可用記錄"){thread{GuardEngine.pollChannelOnce(applicationContext,since="all")};Toast.makeText(this,"正在同步；未擷取或中繼站已過期的內容無法復原。",Toast.LENGTH_LONG).show()})
        if(!inConversation)rootContainer.addView(textAction("刪除此家庭的全部證據", t.warmCoral){
            alert(critical = true).setTitle("永久刪除證據？").setMessage("將刪除此家庭本機記錄及收藏。圖表統計保留；操作無法復原。")
                .setNegativeButton("取消",null).setPositiveButton("永久刪除"){_,_->requireFreshParentAuth{GuardEngine.clearParentAlerts(this);evidencePage=0;renderUi()}}.show()
        })
    }
    private fun conversationBubble(message:JSONObject,t:ThemeColors):View {
        val outgoing=message.optString("direction")=="outgoing"
        val holder=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;setPadding(0,dp(4),0,dp(4))}
        val bubble=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;minimumHeight=dp(64);setPadding(dp(14),dp(12),dp(14),dp(12))
            background=ripple(if(outgoing)t.bgHero else t.bgCard,14,t.borderSubtle)
            isClickable=true;isFocusable=true;tag="evidence_${message.optString("_id")}"
            describeAsControl()
            setOnClickListener(sessionClick{showAlertDetails(message,t)})
        }
        bubble.addView(label((if(message.optBoolean("_saved"))"已儲存 · " else "")+evidenceSpeaker(message),13f,t.textPrimary,true,bottomPad=6))
        if(ConversationCapture.actionable(message))bubble.addView(label(alertTitle(message.optString("reason"))+" · "+when(message.optString("_review_state")){"reviewed"->"已查看";"follow_up"->"待跟進";else->"新記錄"},12f,t.warmCoral,bottomPad=6))
        if(signalLabels(message).isNotEmpty())bubble.addView(label(signalLabels(message),12f,t.warmHoney,bottomPad=6))
        bubble.addView(label(shortEvidence(evidenceBody(message)),15f,t.textPrimary,bottomPad=8).apply{maxLines=3;ellipsize=android.text.TextUtils.TruncateAt.END})
        bubble.addView(label("${evidenceClock(message)}\n${evidenceSource(message)}"+(if(message.optBoolean("possible_duplicate"))" · 可能重複" else ""),12f,t.textMuted))
        val details=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(0,dp(10),0,0)}
        details.addView(label("查看完整記錄",12f,t.accentSeafoam,true),LinearLayout.LayoutParams(0,-2,1f))
        details.addView(icon("caret-right",t.accentSeafoam,16));bubble.addView(details)
        bubble.contentDescription="${evidenceSpeaker(message)}。${shortEvidence(evidenceBody(message))}。${evidenceClock(message)}。點按查看完整內容"
        val gap=Space(this)
        if(outgoing)holder.addView(gap,LinearLayout.LayoutParams(0,1,.1f))
        holder.addView(bubble,LinearLayout.LayoutParams(0,-2,.9f))
        if(!outgoing)holder.addView(gap,LinearLayout.LayoutParams(0,1,.1f))
        return holder
    }
    private fun showAlertDetails(alert: JSONObject,t: ThemeColors) {
        if(!GuardEngine.isParentMode(this))return
        val scope = GuardEngine.getChannelTopic(this)
        val id = alert.optString("_id")
        val stored = FamilyEvidence(this).use { it.pruneExpired(scope);it.find(scope,id) } ?: return
        val unidentified=ConversationCapture.unidentifiedLegacyLabel(stored)
        showSheet(if(stored.optString("type")=="captured_message")ConversationCapture.displayLabel(stored.optString("conversation_label"),unidentified)else alertTitle(stored.optString("reason"))) { dialog ->
            val reason = stored.optString("reason");val sender = stored.optString("sender","未識別來源")
            addView(label("${evidenceSpeaker(stored)}\n${evidenceClock(stored)}\n${evidenceSource(stored)}",13f,t.textSecondary,bottomPad=16))
            if(unidentified)addView(stateNote(Note.WARNING,"舊版曾把導覽按鈕當作對話名稱，這些內容可能來自不同對話。原文保留，無法可靠還原對象，請勿據此判定發訊人。",t))
            addView(label(if(reason=="archive_recovery")"原始舊資料未能自動解析，完整原始內容保留於此，可複製後尋求復原協助。" else if(stored.optBoolean("_legacy_partial"))"舊版本記錄，內容可能已截短，無法復原未保留的部分。" else "保留 App 實際擷取的文字，未必涵蓋 Instagram 原始對話的全部內容。",12f,t.textMuted,bottomPad=14))
            addView(label(evidenceBody(stored),16f,t.textPrimary,bottomPad=20).apply{setTextIsSelectable(true)})
            if(reason=="sexual_or_grooming_content")addView(actionButton("尋求協助建議",t.btnSecondaryBg,t.btnSecondaryText) { dialog.dismiss();showSafetyAdvice(stored,t) })
            fun actions(vararg buttons:Button) {
                val vertical=resources.configuration.fontScale>1.3f
                val row=LinearLayout(this@MainActivity).apply{orientation=if(vertical)LinearLayout.VERTICAL else LinearLayout.HORIZONTAL}
                buttons.forEach{button->row.addView(button,LinearLayout.LayoutParams(if(vertical)-1 else 0,-2,if(vertical)0f else 1f).apply{setMargins(0,dp(4),dp(4),dp(8))})}
                addView(row)
            }
            actions(actionButton(if(stored.optBoolean("_saved"))"取消儲存" else "儲存此訊息",t.btnSecondaryBg,t.btnSecondaryText) {
                FamilyEvidence(this@MainActivity).use{it.save(scope,id,!stored.optBoolean("_saved"))};dialog.dismiss();renderUi()
            },actionButton("複製完整記錄",t.btnSecondaryBg,t.btnSecondaryText) { copyText("Convoy 證據","對話：$sender\n作者：${evidenceSpeaker(stored)}\n時間：${evidenceClock(stored)}\n來源：${evidenceSource(stored)}\n\n${stored.optString("text")}") })
            addView(actionButton("明確保留供檢視（一次，90 天）",t.btnSecondaryBg,t.btnSecondaryText){
                val kept=FamilyEvidence(this@MainActivity).use{it.preserveForReview(scope,id)}
                Toast.makeText(this@MainActivity,if(kept)"已限期保留 90 天。" else "已保留或已到期；不會延長既有期限。",Toast.LENGTH_LONG).show()
                dialog.dismiss();renderUi()
            })
            addView(label("收藏不會取消到期日；如需延長，請使用上方明確保留。",12f,t.textMuted,bottomPad=12))
            if(ConversationCapture.actionable(stored)) {
                actions(*listOf("reviewed" to "標記已查看","follow_up" to "標記待跟進","new" to "標記為新記錄").map{(state,title)->
                    actionButton(title,t.btnSecondaryBg,t.btnSecondaryText) {
                        FamilyEvidence(this@MainActivity).use{it.review(scope,id,state)};dialog.dismiss();renderUi()
                    }
                }.toTypedArray())
            }
            val matches=stored.optJSONArray("matches")
            if(matches!=null && matches.length()>0) {
                val phrases=(0 until matches.length()).mapNotNull { index ->
                    matches.optJSONObject(index)?.let { match ->
                        val field=if(match.optString("field")=="author")"作者標籤" else "訊息"
                        "$field：「${match.optString("phrase")}」"
                    }
                }.distinct()
                addView(stateNote(Note.ERROR,"關鍵字相符，不代表已確認誘導或侵害。\n"+phrases.joinToString("；"),t))
            }
            if(stored.optBoolean("possible_duplicate"))addView(stateNote(Note.WARNING,"此項擷取可能與其他來源重複；無法確認為獨立訊息。",t))
            // One button style in this sheet: every action is a secondary button; delete keeps the danger colour on its label.
            if(stored.optString("_conversation_key").isNotEmpty())addView(actionButton("查看此對象的對話",t.btnSecondaryBg,t.btnSecondaryText) {
                dialog.dismiss();evidenceSearch="";evidenceSaved=false;openConversation(stored.optString("_conversation_key"),ConversationCapture.displayLabel(stored.optString("_conversation_label",sender),unidentified))
            })
            val handle = sender.trim().lowercase(Locale.US).removePrefix("@")
            if(!unidentified && reason in setOf("stranger_message","sexual_or_grooming_content") && !sender.startsWith("子女留言") &&
                stored.optString("conversation_kind")!="group" && handle.matches(Regex("[a-z0-9._]{2,30}")) &&
                handle !in GuardEngine.getTrustedHandles(this@MainActivity) && !GuardEngine.isChildSelfHandle(this@MainActivity,handle) && !handle.contains("instagram")) {
                addView(actionButton("加入信任好友並解除封鎖",t.btnSecondaryBg,t.btnSecondaryText) { addTrustedSender(handle);GuardEngine.syncTrustedFromParent(this@MainActivity);dialog.dismiss() })
            }
            addView(actionButton("刪除此記錄",t.btnSecondaryBg,t.warmCoral) {
                alert(critical = true).setTitle("永久刪除此記錄？").setMessage("此操作會移除本機文字及收藏，無法復原。")
                    .setNegativeButton("取消",null).setPositiveButton("刪除") { _,_ ->
                        FamilyEvidence(this@MainActivity).use{it.deleteMessage(scope,id)};dialog.dismiss();renderUi()
                    }.show()
            })
            addView(actionButton("關閉",t.btnSecondaryBg,t.btnSecondaryText){dialog.dismiss()})
        }
    }
    private fun maybeShowSafetyAdvice() {
        if(!parentUnlocked())return
        if(!activityVisible || !GuardEngine.isParentMode(this) || section=="role" || activeSheet?.isShowing==true)return
        val alert = FamilyEvidence(this).use{it.nextAdvice(GuardEngine.getChannelTopic(this))} ?: return
        showSafetyAdvice(alert,currentTheme())
    }
    private fun showSafetyAdvice(alert: JSONObject?,t: ThemeColors) {
        if(!GuardEngine.isParentMode(this))return
        val scope = GuardEngine.getChannelTopic(this)
        showSheet("先了解，再尋求合適協助。") { dialog ->
            addView(label(if(alert==null)"分數只是一個參考訊號，不代表已確認任何事情。請先冷靜了解情況，避免責備孩子。" else "偵測到需留意的關鍵字，不代表已確認誘導或侵害。請先冷靜了解情況，避免責備孩子。",15f,t.textPrimary,bottomPad=18))
            addView(label("保留相關記錄，與孩子確認是否感到不安、被威脅或被要求保密。若有疑慮，請向學校社工、合資格兒童保障或心理專業人士尋求協助。",14f,t.textSecondary,bottomPad=18))
            addView(stateNote(Note.ERROR,"如在香港遇到即時人身危險，請致電 999；其他地區請使用當地緊急求助號碼。App 不能代替專業評估或緊急支援。",t))
            if(alert!=null)addView(actionButton("查看相關記錄",t.btnSecondaryBg,t.btnSecondaryText) {
                dialog.dismiss();section="alerts";evidenceSearch="";openConversation(alert.optString("_conversation_key").takeIf{it.isNotEmpty()},alert.optString("_conversation_label").ifBlank{alert.optString("sender")})
                showAlertDetails(alert,t)
            })
            addView(actionButton("我明白",t.btnPrimaryBg,t.btnPrimaryText){dialog.dismiss()})
        }
        // One sheet covers the current burst; individual records keep an advice action for later review.
        if(alert!=null)FamilyEvidence(this).use{it.acknowledgeAdvice(scope)}
    }
    private fun handleEvidenceNotification(incoming: Intent) {
        if(!GuardEngine.isParentMode(this))return
        val uri=incoming.data ?: return
        if(uri.scheme!="igguard" || uri.host!="evidence" || uri.pathSegments.size!=2)return
        if(uri.pathSegments[0]!=GuardEngine.getChannelTopic(this))return
        val id=uri.pathSegments[1]
        if(FamilyEvidence(this).use{it.find(GuardEngine.getChannelTopic(this),id)}==null)return
        pendingEvidenceId=id;section="alerts"
        if(activityVisible){renderUi();openPendingEvidence()}
    }
    private fun openPendingEvidence() {
        if(!parentUnlocked())return
        val id=pendingEvidenceId ?: return;pendingEvidenceId=null
        if(intent?.action=="app.family.igguard.OPEN_EVIDENCE")setIntent(Intent(intent).apply{action=Intent.ACTION_MAIN;data=null})
        if(!GuardEngine.isParentMode(this))return
        val alert=FamilyEvidence(this).use{it.find(GuardEngine.getChannelTopic(this),id)} ?: return
        if(alert.optString("reason")=="sexual_or_grooming_content")showSafetyAdvice(alert,currentTheme()) else showAlertDetails(alert,currentTheme())
    }
    /** The mode is in a content-free state on this child phone (agreed, paused or withdrawn): message text stays here. */
    private fun scoresContentFree(): Boolean = GuardEngine.isChildMode(this) && RiskLocal.contentFree(this)
    private fun childDisclaimerText(): String = if (scoresContentFree()) ScoresChildCopy.MODE_DISCLAIMER
        else "僅供符合 Instagram 最低年齡要求（至少 13 歲或當地更高要求）的家庭使用。此版本不讀取未送出草稿。通知與可見 DM 擷取可能不完整；警報會加密轉傳至配對家長。可在「擷取範圍與停止」停止擷取及待傳送；Android 權限可另行關閉，已送達副本需另行刪除。"
    private fun childCaptureStatus():String=when {
        !GuardEngine.collectionAllowed(this)->"已停止擷取及傳送；Android 權限不會自動移除。"
        RiskLocal.captureHeld(this)->ScoresChildCopy.HELD_STATUS
        scoresContentFree()->ScoresChildCopy.MODE_CAPTURE_STATUS
        GuardEngine.fullCaptureEnabled(this)->GuardEngine.captureScopeSummary(this)
        GuardEngine.pendingCapturePolicy(this).isNotEmpty()->"只擷取傳入警報 · 已收到家長設定，點按此處閱讀及確認雙向範圍。"
        else->"只擷取傳入警報 · 請家長先傳送雙向擷取設定，點按查看步驟。"
    }
    private fun withdrawWithNotice(discard: Boolean, dialog: Dialog) {
        Toast.makeText(this, "正在通知家長手機，然後停止…", Toast.LENGTH_SHORT).show()
        thread {
            val failed = try { GuardEngine.withdrawCollection(applicationContext, discard, noticeWaitMs = 6_000L); false } catch (_: Exception) { true }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (failed) Toast.makeText(this, "未能完成停止擷取；目前的設定保持不變，請重試。", Toast.LENGTH_LONG).show()
                else dialog.dismiss()
                renderUi()
            }
        }
    }
    private fun showCapturePrivacy(t: ThemeColors) {
        showSheet("你的擷取範圍") { dialog ->
            addView(label(if(scoresContentFree())ScoresChildCopy.MODE_PRIVACY_INTRO else "停止會同時阻止新擷取與尚未送出的內容。已送達家長的副本需在家長手機另行刪除。重新開始不會自動恢復雙向擷取，也不會重傳舊範圍的待傳記錄。",14f,t.textSecondary,bottomPad=12))
            if(!GuardEngine.collectionAllowed(this@MainActivity)) {
                addView(actionButton("清除舊待傳記錄並重新開始傳入警報",t.btnPrimaryBg,t.btnPrimaryText,critical=true){
                    GuardEngine.resumeCollection(this@MainActivity);dialog.dismiss();syncRoleServices();renderUi()
                })
            } else {
                val outgoing=checkRow("允許已送出的傳出訊息（不含草稿）",GuardEngine.outgoingCaptureAllowed(this@MainActivity),t)
                val trusted=checkRow("允許信任對象的一般對話脈絡",GuardEngine.trustedContextAllowed(this@MainActivity),t)
                addView(outgoing);addView(trusted)
                addView(label("選項只在已確認的擴展模式生效。儲存會捨棄舊範圍的未傳送記錄；擴大範圍須重新閱讀及確認。",13f,t.textSecondary,bottomPad=12))
                addView(actionButton("儲存範圍選擇",t.btnPrimaryBg,t.btnPrimaryText){
                    GuardEngine.setCaptureOptions(this@MainActivity,outgoing.isChecked,trusted.isChecked);dialog.dismiss();renderUi()
                })
                addView(actionButton("停止擷取及傳送，並捨棄待傳內容",t.warningButtonBg,t.warningButtonText){
                    withdrawWithNotice(true,dialog)
                })
                addView(actionButton("停止擷取及傳送，暫存待傳內容",t.btnSecondaryBg,t.btnSecondaryText){
                    withdrawWithNotice(false,dialog)
                })
            }
        }
    }

    private fun renderChildOverview(t: ThemeColors) {
        rootContainer.addView(icon("plant", t.accentSeafoam, 42))
        heading("安心上網，\n自在成長。", "給你探索的空間，也給家人一份安心。", t)
        renderScoresDecisionCards(t)
        val notification = GuardEngine.isNotificationListenerEnabledStatic(this) && GuardEngine.cpNotifConnected
        val screen = GuardEngine.isAccessibilityEnabledStatic(this) && GuardEngine.cpAccConnected
        val card = makeCard(t)
        val noRead = RiskLocal.uploadOnly(this)
        card.addView(label(if (noRead) (if (RiskLocal.captureHeld(this)) "擷取已停止，等你決定" else "手動上載 · 不讀取 Instagram") else if (notification && screen) "你的保護已準備好" else "完成設定，讓保護正常運作。", 16f, t.textPrimary, true, bottomPad = 8))
        card.addView(label(if (noRead) "本 App 不讀取 Instagram 的通知或畫面，所以不需要通知或畫面權限。" else if (notification && screen) "通知及畫面保護已連接。" else "通知讀取保護背景訊息；畫面保護處理對話、留言回覆及防靜音。", 14f, t.textSecondary))
        rootContainer.addView(card)
        renderScoresChildBlocks(t)
        if (GuardEngine.cpChildKeyRefused) {
            rootContainer.addView(stateNote(Note.ERROR, "需要重新配對：中繼站未接受此手機的配對金鑰。請在家長手機選擇「加入子女」，再掃描 QR 碼。\nPair again: the relay did not accept this phone's pairing key.", t, cardTag = "child_key_refused"))
            rootContainer.addView(actionButton("掃描 QR 碼 · Scan QR code", t.btnPrimaryBg, t.btnPrimaryText) { showChildPairingSheet() })
        } else if (GuardEngine.childKeyAlias(this) == null) rootContainer.addView(noticeCard(t, cardTag = "child_verify").apply {
            addView(label("請與家長核實此手機 · Verify this phone with your parent", 15f, t.textPrimary, true, bottomPad = 6))
            addView(label("此手機在舊版本配對，家長手機會顯示為「未核實」。請在家長手機選擇「加入子女」，再用此手機掃描 QR 碼（需要家長主密碼）。\nPaired with an older version: the parent phone shows it as not verified. Choose “Add a child” on the parent phone, then scan its QR code here (parent password needed).", 13f, t.textSecondary))
            addView(textAction("掃描 QR 碼 · Scan QR code") { showChildPairingSheet() })
        })
        rootContainer.addView(label(childDisclaimerText(),13f,t.textSecondary,bottomPad=14))
        rootContainer.addView(row("chat-circle","雙向對話記錄",childCaptureStatus(),t){showFullCaptureNotice(t)})
        rootContainer.addView(row("shield-check","擷取範圍與停止",if(GuardEngine.collectionAllowed(this))"選擇範圍、停止新擷取與待傳送。" else "擷取及待傳送已停止。",t){showCapturePrivacy(t)})
        rootContainer.addView(actionButton("查看保護狀態", t.btnPrimaryBg, t.btnPrimaryText) { navigateTo("permissions") })
        rootContainer.addView(row("key", "需要暫時解鎖？", "輸入家長提供的解鎖碼", t) { navigateTo("unlock") })
        rootContainer.addView(textAction("裝置及家庭詳情") {
            showSheet("裝置及家庭詳情") { dialog ->
                val handle = GuardEngine.getChildSelfHandle(this@MainActivity)
                val offenders = GuardEngine.getFlaggedOffenders(this@MainActivity)
                val pairing = if (GuardEngine.childKeyAlias(this@MainActivity) != null) "配對方式：QR 碼（已核實）" else "家庭核對圖案：${GuardEngine.getSafetyEmojis(this@MainActivity)}\n安全代碼：${GuardEngine.getSafetyCode(this@MainActivity).ifEmpty { "需要更新家庭配對" }}"
                addView(label("$pairing\n\nInstagram 帳號：${if (handle.isEmpty()) "等待自動識別" else "@$handle"}\n\n已標記聯絡人：${if (offenders.isEmpty()) "暫無記錄" else offenders.joinToString(", ") { "@$it" }}", 14f, t.textPrimary, bottomPad = 20))
                addView(actionButton("關閉", t.btnSecondaryBg, t.btnSecondaryText) { dialog.dismiss() })
            }
        })
    }
    private fun showFullCaptureSetup(t:ThemeColors) {
        if(!GuardEngine.isParentMode(this))return
        val scope=GuardEngine.getChannelTopic(this)
        showSheet("設定雙向對話擷取") { dialog ->
            addView(label("兩部手機均需更新。家長在此傳送設定，再在子女手機的「權限 → 雙向對話記錄」閱讀並確認範圍。請只為你有權監護的子女設定，並與孩子說明範圍。",15f,t.textPrimary,bottomPad=16))
            addView(label("擴展模式會保留實際擷取的傳入訊息；傳出訊息及信任對象的一般脈絡須符合子女手機的選項及確認。一般內容不會逐則發出警報，家長可按對話對象及時間查看。",14f,t.textSecondary,bottomPad=14))
            addView(label("家長送出設定後，子女手機需閱讀並確認範圍。未確認前只維持原有保護。Instagram 未向通知或畫面提供的內容無法擷取；這不是完整對話備份。",14f,t.textSecondary,bottomPad=18))
            addView(actionButton("傳送雙向擷取設定",t.btnPrimaryBg,t.btnPrimaryText,critical=true) {
                GuardEngine.requestFullCapture(this@MainActivity){success,_->runOnUiThread {
                    if(!isDestroyed && GuardEngine.isParentMode(this@MainActivity) && GuardEngine.getChannelTopic(this@MainActivity)==scope) {
                        Toast.makeText(this@MainActivity,if(success)"已傳送；請在子女手機確認範圍。" else "設定未送達，請保持連線後重試。",Toast.LENGTH_LONG).show();renderUi()
                    }
                }}
                dialog.dismiss();renderUi()
            })
            addView(actionButton("稍後設定",t.btnSecondaryBg,t.btnSecondaryText){dialog.dismiss()})
        }
    }
    /** One dismissible notice when the bundled Terms differ from the revision this phone accepted; no re-consent, no lock-out. */
    private fun maybeShowTermsUpdatedNotice() {
        if (!activityVisible || !parentUnlocked() || !AppConsent.termsUpdateNoticePending(this)) return
        AppConsent.markTermsUpdateNoticeSeen(this)
        alert().setTitle("條款已更新")
            .setMessage("Convoy 的使用條款已更新（修訂 ${AppConsent.VERSION}）。你原來的同意記錄保持不變，不需要重新同意；請在方便時查看新條款。")
            .setNegativeButton("知道了", null)
            .setPositiveButton("查看條款") { _, _ ->
                val t = currentTheme()
                showSheet("使用條款與同意記錄") { _ -> addView(label(AppConsent.reviewText(this@MainActivity), 14f, t.textPrimary).apply { setTextIsSelectable(true) }) }
            }.show()
    }
    private fun maybeShowCaptureNotice() {
        if(!activityVisible || !GuardEngine.isChildMode(this) || section=="role" || activeSheet?.isShowing==true)return
        val id=GuardEngine.pendingCapturePolicy(this)
        if(id.isEmpty() || id==lastCapturePrompt || GuardEngine.fullCaptureEnabled(this))return
        lastCapturePrompt=id;showFullCaptureNotice(currentTheme())
    }
    private fun showFullCaptureNotice(t:ThemeColors) {
        if(!GuardEngine.isChildMode(this))return
        val policy=GuardEngine.pendingCapturePolicy(this)
        showSheet("了解新的對話擷取範圍") { dialog ->
            if(policy.isEmpty())addView(label("目前只擷取傳入警報，不會記錄子女傳出的 DM。\n\n1. 家長手機：提醒 → 對話記錄 → 雙向擷取尚未確認 → 傳送雙向擷取設定。\n2. 保持兩部手機連線，在此重新接收設定。\n3. 子女手機閱讀後，點按「我已了解，啟用雙向擷取」。\n\n未收到時，請家長再次傳送，並在連接與診斷檢查加密通道。",13f,t.textSecondary,bottomPad=12))
            if(policy.isEmpty())addView(actionButton("重新接收家長設定",t.btnPrimaryBg,t.btnPrimaryText) {
                    dialog.dismiss()
                    thread { GuardEngine.pollChannelOnce(applicationContext,since="all");runOnUiThread{if(!isDestroyed)renderUi()} }
                })
            addView(label(if(scoresContentFree())ScoresChildCopy.MODE_NOTICE_MAIN else "家長可收到 Instagram 實際擷取的傳入訊息；按「你的擷取範圍」選項，亦可包含已送出的傳出訊息及信任對象的一般脈絡。這比只轉傳部分傳入警報的範圍更廣。",15f,t.textPrimary,bottomPad=16))
            addView(label("此版本不再讀取 DM、留言及限時動態輸入框的未送出文字；只使用輸入框位置及提示辨識畫面。舊版已保留的草稿仍可查看及刪除；已排隊的舊記錄仍可能送達。",14f,t.textSecondary,bottomPad=14))
            addView(label(if(scoresContentFree())ScoresChildCopy.MODE_NOTICE_TRANSPORT else "內容會先在此手機加密，經本 App 的 Cloudflare 中繼傳送至配對家長手機。手機上新待傳記錄最多保留 72 小時；中繼站上未被取走的密文最多保留 7 天；家長新封存預設 30 天，可縮短為 7 天，個別記錄可明確保留一次 90 天。舊封存須預覽及確認採用期限。開發者及 Cloudflare 可處理密文及連線資料，中繼不會取得家庭解密金鑰。",14f,t.textSecondary,bottomPad=14))
            addView(label("只能保留通知及畫面實際提供的內容；未顯示、未擷取或已過期的內容無法復原。確認範圍不代表 App 已獲法律認證或 Meta 授權。可在「你的擷取範圍」停止擷取及待傳送，並選擇捨棄佇列；也可在 Android 撤銷權限。已送達家長的副本需另行刪除。",13f,t.textMuted,bottomPad=18))
            if(policy.isNotEmpty() && !GuardEngine.fullCaptureEnabled(this@MainActivity)) {
                addView(actionButton("我已了解，啟用雙向擷取",t.btnPrimaryBg,t.btnPrimaryText,critical=true) {
                    if(GuardEngine.pendingCapturePolicy(this@MainActivity)==policy && GuardEngine.confirmFullCapture(this@MainActivity)) {
                        dialog.dismiss();renderUi()
                    } else Toast.makeText(this@MainActivity,"設定已變更，請重新確認家庭及擷取範圍。",Toast.LENGTH_LONG).show()
                })
                addView(actionButton("暫不啟用",t.btnSecondaryBg,t.btnSecondaryText){dialog.dismiss()})
            } else {
                if(policy.isNotEmpty())addView(label("此手機已確認上述範圍。",13f,t.textSecondary,bottomPad=12))
                addView(actionButton("關閉",t.btnSecondaryBg,t.btnSecondaryText){dialog.dismiss()})
            }
        }
    }
    private fun renderChildPermissions(t: ThemeColors) {
        backLink()
        heading("每個設定，都說清楚。", "了解每項權限的用途，再前往 Android 設定確認。", t)
        rootContainer.addView(label(childDisclaimerText(),13f,t.textSecondary,bottomPad=14))
        rootContainer.addView(row("chat-circle","雙向對話記錄",childCaptureStatus(),t){showFullCaptureNotice(t)}.apply{getChildAt(0).tag="full_capture_setup"})
        renderModelImport(t)
        if (!GuardEngine.hasSecureParentCommands(this)) rootContainer.addView(stateNote(Note.WARNING, "需要更新家庭配對：現有監察仍會運作。新版家長指令與臨時碼需要家長傳送新邀請；請先更新兩部手機，並以新主密碼重新建立家庭。", t))
        val notification = GuardEngine.isNotificationListenerEnabledStatic(this)
        val screen = GuardEngine.isAccessibilityEnabledStatic(this)
        rootContainer.addView(row("bell-simple", "Instagram 通知", if (notification) { if (GuardEngine.cpNotifConnected) "已授權 · 已連接" else "已授權 · 等待連接" } else "未開啟。讀取 IG 通知並加密傳送警報。", t) { openDirectNotificationToggle() })
        rootContainer.addView(row("shield-check", "進階畫面保護", if (screen) { if (GuardEngine.cpAccConnected) "已授權 · 已連接" else "已授權 · 等待連接" } else "未開啟。讀取已顯示的 DM，辨識高危對話。", t) { openAccessibilityToggle() })
        rootContainer.addView(row("info", "監察狀態通知", if (GuardEngine.capturePausedByNotice(this)) GuardEngine.NOTICE_OFF_STATUS else "持續顯示子女保護模式，與通知讀取是不同權限。", t) { requestAppNotifications() })
        if ((!notification || !screen) && Build.VERSION.SDK_INT >= 33) {
            val card = makeCard(t, true)
            card.addView(label("設定開關變灰了？", 16f, t.textPrimary, true, bottomPad = 8))
            card.addView(label("部分裝置需要先嘗試開啟權限，才會顯示受限設定選項。", 14f, t.textSecondary))
            card.addView(textAction("受限設定協助") { openAppDetailsSettings() }); rootContainer.addView(card)
        }
        rootContainer.addView(textAction("立即發送測試及心跳回報") {
            GuardEngine.retryPendingAlerts(this, force = true)
            GuardEngine.checkAndSendChildHeartbeatIfDue(this, force = true)
            GuardEngine.sendAlertFromChild(this,"test_alert",Build.MODEL,"子女手機主動發送加密連線測試。",bypassDedupe=true,
                onComplete={_,message->runOnUiThread { if(!isDestroyed)Toast.makeText(this,message,Toast.LENGTH_LONG).show() }})
            Toast.makeText(this, "測試已加入傳送佇列，等候中繼站及家長手機確認。", Toast.LENGTH_LONG).show()
        })
        rootContainer.addView(label("通知已連接代表 Instagram 讀取服務已就緒，並非家長已收妥。\n最新傳送：${GuardEngine.cpLastHttpStatus}",13f,t.textSecondary,topPad=10))
    }
    // ================= Scores-only family mode: shared helpers =================
    private fun bandColor(token: String, t: ThemeColors): String = when (token) {
        "accentSeafoam" -> t.riskLow; "warmHoney" -> t.warmHoney; "warmCoral" -> t.warmCoral; else -> t.textSecondary
    }
    private fun iconLine(glyph: String, tint: String, text: String, t: ThemeColors, sizeSp: Float = 15f, tag: String? = null, bold: Boolean = true): LinearLayout =
        LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(8))
            addView(icon(glyph, tint, 24))
            addView(label(text, sizeSp, t.textPrimary, bold).apply { setPadding(dp(10), 0, 0, 0); if (tag != null) this.tag = tag }, LinearLayout.LayoutParams(0, -2, 1f))
        }
    /** hero = an emphasised card: drawn with [onNotice], and everything added inside must use that palette too. */
    private fun noticeCard(t: ThemeColors, hero: Boolean = false, cardTag: String? = null): LinearLayout = makeCard(if (hero) onNotice(t) else t, hero).apply { if (cardTag != null) tag = cardTag }
    /** Empty, loading, error and inline-warning states: one notice card with one icon line. The icon carries the kind, so colour is never the only signal. */
    private enum class Note(val glyph: String) { EMPTY("info"), LOADING("clock"), ERROR("shield-warning"), WARNING("shield-warning") }   // ERROR (coral) also marks danger warnings
    private fun stateNote(kind: Note, text: String, t: ThemeColors, cardTag: String? = null): LinearLayout = noticeCard(t, cardTag = cardTag).apply {
        val tint = when (kind) { Note.EMPTY, Note.LOADING -> t.textSecondary; Note.WARNING -> t.warmHoney; Note.ERROR -> t.warmCoral }
        addView(iconLine(kind.glyph, tint, text, t, 14f, bold = false))
    }

    // ================= Scores-only family mode: child's phone =================
    private val modelPickRequest = 9032
    private val exportPickRequest = 9033
    private var modelPickAuthorized = false
    private var scoresReadOnly = false
    /** Registered while this activity is on screen; the import itself lives in the process, so a recreated activity re-attaches here. */
    // The copy reports every 8 MB (several times a second); a full rebuild that often drops taps (bug-review B-7). Redraws are
    // throttled to one per IMPORT_REDRAW_MS, with a trailing one so the final state always shows. The copy and its check are untouched.
    private val IMPORT_REDRAW_MS = 500L
    private var importRedrawAt = 0L
    private var importRedrawQueued = false
    private val importRedraw = Runnable {
        importRedrawQueued = false; importRedrawAt = SystemClock.uptimeMillis()
        if (!isDestroyed && activityVisible && section == "permissions") renderUi()
    }
    private val importListener: () -> Unit = { uiHandler.post {
        if (!importRedrawQueued) { importRedrawQueued = true; uiHandler.postAtTime(importRedraw, maxOf(SystemClock.uptimeMillis(), importRedrawAt + IMPORT_REDRAW_MS)) }
    } }
    private val exportListener: () -> Unit = { uiHandler.post { if (!isDestroyed && activityVisible && section == "overview") renderUi() } }

    // The two cards that ask the child to decide; drawn right under the heading so they show without scrolling.
    private fun renderScoresDecisionCards(t: ThemeColors) {
        val s = RiskLocal.status(this)
        if (RiskLocal.captureHeld(this)) {
            val card = noticeCard(t, true, "scores_hold_card"); val n = onNotice(t)
            card.addView(label(ScoresChildCopy.HOLD_TITLE, 16f, n.textPrimary, true, bottomPad = 6))
            card.addView(label(ScoresChildCopy.HOLD_BODY, 14f, n.textSecondary, bottomPad = 8))
            card.addView(label(ScoresChildCopy.HOLD_ASK, 14f, n.textPrimary, true, bottomPad = 8))
            card.addView(actionButton(ScoresChildCopy.HOLD_ALLOW, n.btnSecondaryBg, n.btnSecondaryText, critical = true, t = n) { RiskLocal.releaseCaptureHold(this); ScoresEngineBridge.acknowledgeNotice(this, "off"); renderUi() }.apply { tag = "scores_hold_agree" })
            rootContainer.addView(card)
        }
        if (s.pendingPolicy) {
            val card = noticeCard(t, true, "scores_consent_card"); val n = onNotice(t)
            val again = s.consent == "reconsent"
            val changed = s.consent == "accepted"   // agreed before; the parent now asks for another method
            card.addView(label(if (changed) ScoresChildCopy.METHOD_CARD_TITLE else if (again) ScoresChildCopy.RECONSENT_CARD_TITLE else ScoresChildCopy.CARD_TITLE, 16f, n.textPrimary, true, bottomPad = 8))
            card.addView(label(if (changed) ScoresChildCopy.METHOD_CARD_BODY else if (again) ScoresChildCopy.RECONSENT_CARD_BODY else ScoresChildCopy.CARD_BODY, 14f, n.textSecondary, bottomPad = 8))
            card.addView(actionButton(ScoresChildCopy.CARD_ACTION, n.btnPrimaryBg, n.btnPrimaryText, t = n) { scoresReadOnly = false; navigateTo("scores_consent") }.apply { tag = "scores_consent_open" })
            rootContainer.addView(card)
        }
        // The parent asked for a fresh export. The child may ignore it; nothing waits on it.
        if (RiskLocal.importRequestPending(this)) {
            val card = noticeCard(t, true, "scores_import_request_card"); val n = onNotice(t)
            card.addView(label(ScoresChildCopy.REQUEST_CARD_TITLE, 16f, n.textPrimary, true, bottomPad = 8))
            card.addView(actionButton(ScoresChildCopy.EXPORT_GUIDE_TITLE, n.btnPrimaryBg, n.btnPrimaryText, t = n) { showExportGuide(t) }.apply { tag = "scores_import_request_guide" })
            card.addView(actionButton(ScoresChildCopy.OK_DISMISS, n.btnSecondaryBg, n.btnSecondaryText, t = n) { RiskLocal.dismissImportRequest(this); renderUi() }.apply { tag = "scores_import_request_dismiss" })
            rootContainer.addView(card)
        }
    }
    private fun renderScoresChildBlocks(t: ThemeColors) {
        val s = RiskLocal.status(this)
        ScoresEngineBridge.pendingNotice(this)?.let {
            val card = noticeCard(t, true, "scores_change_card"); val n = onNotice(t)
            card.addView(label(ScoresChildCopy.CHANGE_CARD_TITLE, 16f, n.textPrimary, true, bottomPad = 6))
            card.addView(textAction(ScoresChildCopy.CHANGE_CARD_ACTION, t = n) { navigateTo("scores_change") })
            rootContainer.addView(card)
        }
        if (s.policyEnabled || s.consent != "none") renderScoresStatus(s, t)
    }
    private fun renderScoresStatus(s: RiskChildStatus, t: ThemeColors) {
        val st = ScoresChildCopy.statusState(s)
        val card = noticeCard(t, false, "scores_status_block")
        card.addView(label(ScoresChildCopy.TITLE, 16f, t.textPrimary, true, bottomPad = 4))
        card.addView(iconLine(st.icon, if (st == ScoresChildCopy.StatusState.ON) t.accentSeafoam else if (st == ScoresChildCopy.StatusState.PAUSED || st == ScoresChildCopy.StatusState.WAITING || st == ScoresChildCopy.StatusState.RECONSENT) t.warmHoney else t.warmCoral, st.label, t, 15f, "scores_state_label"))
        // Each state says only what is true in that state; the "parent does not see your messages" line is for ON alone.
        for ((i, line) in ScoresChildCopy.statusLines(st, s.consent, RiskLocal.captureHeld(this)).withIndex()) card.addView(label(line, 13f, if (i == 0 && st != ScoresChildCopy.StatusState.ON) t.textPrimary else t.textSecondary, i == 0 && st != ScoresChildCopy.StatusState.ON, bottomPad = 8).apply { tag = "scores_status_line" })
        if (s.consent == "accepted" && s.agreedMethod == ScoresMethod.UPLOAD) {
            card.addView(label(ScoresChildCopy.UPLOAD_ON_TITLE, 14f, t.textPrimary, true, bottomPad = 4))
            card.addView(label(ScoresChildCopy.UPLOAD_ON_NOTE, 13f, t.textSecondary, bottomPad = 8).apply { tag = "scores_upload_note" })
            renderExportImport(card, t)
        }
        if (s.consent != "none") {
            card.addView(label(ScoresChildCopy.lastRun(s), 13f, t.textSecondary, bottomPad = 8).apply { tag = "scores_last_run" })
            card.addView(label(ScoresChildCopy.unknown(s.unknownThreads), 13f, t.textSecondary, bottomPad = 8))
        }
        if (s.consent == "accepted") {
            if (s.paused) card.addView(actionButton(ScoresChildCopy.RESUME, t.btnPrimaryBg, t.btnPrimaryText) {
                if (!ScoresEngineBridge.resume(this)) Toast.makeText(this, ScoresChildCopy.RESUME_FAILED, Toast.LENGTH_LONG).show()
                renderUi()
            }.apply { tag = "scores_resume" })
            else card.addView(actionButton(ScoresChildCopy.PAUSE, t.btnSecondaryBg, t.btnSecondaryText) {
                if (!ScoresEngineBridge.pause(this)) Toast.makeText(this, ScoresChildCopy.PAUSE_FAILED, Toast.LENGTH_LONG).show()
                renderUi()
            }.apply { tag = "scores_pause" })
        }
        if ((s.consent == "none" || s.consent == "reconsent") && s.pendingPolicy) card.addView(actionButton(ScoresChildCopy.CARD_ACTION, t.btnPrimaryBg, t.btnPrimaryText) { scoresReadOnly = false; navigateTo("scores_consent") })
        card.addView(actionButton(ScoresChildCopy.WHAT, t.btnSecondaryBg, t.btnSecondaryText) { scoresReadOnly = true; navigateTo("scores_consent") }.apply { tag = "scores_what" })
        if (s.consent == "accepted" || s.consent == "reconsent") card.addView(actionButton(ScoresChildCopy.WITHDRAW, t.warningButtonBg, t.warningButtonText) {
            alert().setTitle(ScoresChildCopy.WITHDRAW_TITLE).setMessage(ScoresChildCopy.WITHDRAW_BODY)
                .setNegativeButton(ScoresChildCopy.CANCEL, null).setPositiveButton(ScoresChildCopy.WITHDRAW_CONFIRM) { _, _ ->
                    if (!ScoresEngineBridge.withdraw(this)) Toast.makeText(this, ScoresChildCopy.WITHDRAW_FAILED, Toast.LENGTH_LONG).show()
                    renderUi()
                }.show()
        }.apply { tag = "scores_withdraw" })
        rootContainer.addView(card)
    }
    /** Manual upload only, after the child agreed: the import action, a note while it runs, and what the last one did (nothing stored on any failure). */
    private fun renderExportImport(card: LinearLayout, t: ThemeColors) {
        if (InstagramImport.running) { card.addView(label(ScoresChildCopy.EXPORT_RUNNING, 14f, t.textPrimary, true, bottomPad = 8).apply { tag = "scores_export_running" }); return }
        InstagramImport.last?.let { r ->
            val (title, body) = ScoresChildCopy.exportResult(r)
            val good = r.result == ImportResult.OK && r.conversations > 0
            card.addView(iconLine(if (good) "check-circle" else "shield-warning", if (good) t.accentSeafoam else t.warmCoral, title, t, 15f, "scores_export_result"))
            card.addView(label(body, 13f, t.textSecondary, bottomPad = 6))
            card.addView(actionButton(ScoresChildCopy.OK_DISMISS, t.btnSecondaryBg, t.btnSecondaryText) { InstagramImport.dismiss(); renderUi() }.apply { tag = "scores_export_dismiss" })
        }
        card.addView(textAction(ScoresChildCopy.EXPORT_GUIDE_TITLE) { showExportGuide(t) }.apply { tag = "scores_export_guide_link" })
        card.addView(actionButton(ScoresChildCopy.EXPORT_OPEN, t.btnSecondaryBg, t.btnSecondaryText) { openExportLink(ScoresChildCopy.EXPORT_TOOL_URL) }.apply { tag = "scores_export_open" })
        card.addView(row("plus", ScoresChildCopy.IMPORT_EXPORT_ROW, ScoresChildCopy.IMPORT_EXPORT_DESC, t, fill = t.bgCard) { pickExportFile() }.apply { getChildAt(0).tag = "scores_export_row" })
    }
    /** The steps, the note and the optional cloud export, kept off the upload card so Pause / Withdraw stay near the status. */
    private fun showExportGuide(t: ThemeColors) = showSheet(ScoresChildCopy.EXPORT_GUIDE_TITLE) { dialog ->
        tag = "scores_export_guide"
        for ((i, step) in ScoresChildCopy.EXPORT_GUIDE_STEPS.withIndex()) addView(label("${i + 1}. $step", 13f, t.textSecondary, bottomPad = 6))
        addView(label(ScoresChildCopy.EXPORT_GUIDE_NOTE, 13f, t.textSecondary, bottomPad = 6))
        // Optional, below the guide: Meta's own repeating export. The notice is shown again before the browser opens.
        addView(label(ScoresChildCopy.CLOUD_TITLE, 14f, t.textPrimary, true, topPad = 12, bottomPad = 6).apply { tag = "scores_cloud_title" })
        addView(label(ScoresChildCopy.CLOUD_NOTICE, 13f, t.textSecondary, bottomPad = 6))
        for ((title, url, tag) in listOf(Triple(ScoresChildCopy.CLOUD_GOOGLE_DRIVE, ScoresChildCopy.CLOUD_GOOGLE_DRIVE_URL, "scores_cloud_drive"), Triple(ScoresChildCopy.CLOUD_DROPBOX, ScoresChildCopy.CLOUD_DROPBOX_URL, "scores_cloud_dropbox")))
            addView(textAction(title) {
                alert().setTitle(ScoresChildCopy.CLOUD_CONFIRM_TITLE).setMessage(ScoresChildCopy.CLOUD_NOTICE)
                    .setNegativeButton(ScoresChildCopy.CANCEL, null).setPositiveButton(ScoresChildCopy.CLOUD_CONFIRM) { _, _ -> openExportLink(url) }.show()
            }.apply { this.tag = tag })
        addView(actionButton("關閉", t.btnSecondaryBg, t.btnSecondaryText) { dialog.dismiss() })
    }
    private fun pickExportFile() {
        try { startActivityForResult(ScoresChildCopy.exportPicker(), exportPickRequest) }
        catch (_: Exception) { Toast.makeText(this, ScoresChildCopy.EXPORT_PICKER_MISSING, Toast.LENGTH_LONG).show() }
    }
    private fun openExportLink(url: String) {
        try { startActivity(InstagramExportLink.intent(url)) }
        catch (_: ActivityNotFoundException) { Toast.makeText(this, ScoresChildCopy.EXPORT_LINK_MISSING, Toast.LENGTH_LONG).show() }
    }
    private fun startExportImport(uri: Uri) {
        if (!InstagramImport.allowed(this)) { InstagramImport.last = ImportSummary(ImportResult.NOT_ALLOWED); renderUi(); return }
        InstagramImport.start(this, uri)
    }
    private fun renderScoresConsent(t: ThemeColors) {
        val decide = !scoresReadOnly && RiskLocal.pendingPolicy(this)
        backLink("返回 / Back")
        heading(ScoresChildCopy.CONSENT_TITLE, "", t)
        // To decide: the method the parent asks for. To read again: the one the child agreed to.
        val method = if (decide) RiskLocal.policyMethod(this) else RiskLocal.agreedMethod(this) ?: RiskLocal.policyMethod(this)
        val shownPolicyId = RiskLocal.policyId(this)
        for ((title, body) in ScoresChildCopy.consentPoints(RiskLocal.consentState(this) == "reconsent", method)) {
            rootContainer.addView(label(title, 16f, t.textPrimary, true, topPad = 14, bottomPad = 6))
            rootContainer.addView(label(body, 14f, t.textSecondary))
        }
        if (decide) {
            // No pre-ticked box: the agree button is the only thing that accepts.
            rootContainer.addView(label("", 12f, t.textMuted, bottomPad = 8))
            rootContainer.addView(actionButton(ScoresChildCopy.AGREE, t.btnPrimaryBg, t.btnPrimaryText, critical = true) {
                if (ScoresEngineBridge.accept(this, shownPolicyId, method)) navigateTo(if (ScoresEngineBridge.pendingNotice(this) == "on") "scores_change" else "overview")
                else { Toast.makeText(this, ScoresChildCopy.ACCEPT_FAILED, Toast.LENGTH_LONG).show(); renderUi() }   // the page may be stale: draw what is asked now
            }.apply { tag = "scores_agree" })
            rootContainer.addView(actionButton(ScoresChildCopy.NOT_NOW, t.btnSecondaryBg, t.btnSecondaryText) { navigateTo("overview") }.apply { tag = "scores_not_now" })
        } else rootContainer.addView(actionButton("返回 / Back", t.btnPrimaryBg, t.btnPrimaryText) { navigateTo("overview") }.apply { tag = "scores_back" })
    }
    private fun renderScoresChange(t: ThemeColors) {
        val kind = ScoresEngineBridge.pendingNotice(this)
        backLink("返回 / Back")   // like system Back: leaves without acknowledging, so the notice stays on the overview
        heading(if (kind == "off") ScoresChildCopy.CHANGE_OFF_TITLE else ScoresChildCopy.CHANGE_ON_TITLE, "", t)
        rootContainer.addView(label(if (kind == "off") (if (RiskLocal.captureHeld(this)) ScoresChildCopy.CHANGE_OFF_HELD_BODY else ScoresChildCopy.CHANGE_OFF_BODY) else if (kind == "on") ScoresChildCopy.CHANGE_ON_BODY else "目前沒有新的改變。\nNothing has changed.", 15f, t.textSecondary, bottomPad = 18))
        // Only this button acknowledges the notice; leaving with Back keeps it on the overview.
        rootContainer.addView(actionButton(ScoresChildCopy.CHANGE_DONE, t.btnPrimaryBg, t.btnPrimaryText) {
            if (kind != null) ScoresEngineBridge.acknowledgeNotice(this, kind)
            navigateTo("overview")
        }.apply { tag = "scores_change_done" })
    }

    private fun renderModelImport(t: ThemeColors) {
        sectionTitle(ScoresChildCopy.IMPORT_TITLE, t)
        if (RiskModelStore.state(this).wire == "engine_unavailable") {
            val card = noticeCard(t, false, "scores_engine_unavailable")
            card.addView(iconLine("shield-warning", t.warmCoral, ScoresChildCopy.ENGINE_CARD_TITLE, t))
            card.addView(label(ScoresChildCopy.ENGINE_CARD_BODY, 14f, t.textSecondary))
            rootContainer.addView(card); return
        }
        if (!RiskModelStore.supported(this)) {
            val card = noticeCard(t, false, "scores_model_unsupported")
            card.addView(iconLine("lock-key", t.warmHoney, ScoresChildCopy.UNSUPPORTED_TITLE, t))
            card.addView(label(ScoresChildCopy.UNSUPPORTED_BODY, 14f, t.textSecondary))
            rootContainer.addView(card); return
        }
        val job = ModelImportJob
        val outcome = ScoresChildCopy.result(job.result, job.wasCancelled, job.bundled, RiskModelStore.freeSpace(this))
        if (outcome != null && !job.running) {
            val card = noticeCard(t, false, "scores_import_result")
            card.addView(iconLine(if (job.result == RiskImportResult.OK) "check-circle" else "shield-warning", if (job.result == RiskImportResult.OK) t.accentSeafoam else t.warmCoral, outcome.first, t))
            card.addView(label(outcome.second, 14f, t.textSecondary, bottomPad = 6))
            card.addView(actionButton(ScoresChildCopy.OK_DISMISS, t.btnSecondaryBg, t.btnSecondaryText) { ModelImportJob.dismissResult(); renderUi() })
            if (job.bundled && job.result != RiskImportResult.OK && job.result != RiskImportResult.UNSUPPORTED_DEVICE)
                card.addView(actionButton(ScoresChildCopy.RETRY, t.btnPrimaryBg, t.btnPrimaryText) { startBundledInstall() }.apply { tag = "scores_bundled_retry" })
            rootContainer.addView(card)
        }
        if (job.running) {
            val card = noticeCard(t, false, "scores_import_progress")
            card.addView(label(if (job.bundled) ScoresChildCopy.BUNDLED_PROGRESS else "正在匯入模型檔…\nImporting the model file…", 15f, t.textPrimary, true, bottomPad = 8))
            val bar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000; progress = (job.copied * 1000L / RiskModelSpec.FILE_BYTES).toInt().coerceIn(0, 1000)
                contentDescription = (if (job.bundled) "安裝進度 " else "匯入進度 ") + "${progress / 10}%"; tag = "scores_import_bar"
            }
            card.addView(bar, LinearLayout.LayoutParams(-1, dp(12)))
            card.addView(label(ScoresChildCopy.progress(job.copied), 13f, t.textSecondary, topPad = 8, bottomPad = 6).apply { tag = "scores_import_text" })
            card.addView(actionButton(if (job.bundled) ScoresChildCopy.BUNDLED_CANCEL else ScoresChildCopy.IMPORT_CANCEL, t.btnSecondaryBg, t.btnSecondaryText) { ModelImportJob.requestCancel(); Toast.makeText(this, "正在取消…\nCancelling…", Toast.LENGTH_SHORT).show() }.apply { tag = "scores_import_cancel" })
            rootContainer.addView(card); return
        }
        when (RiskModelStore.state(this)) {
            RiskModelState.INSTALLED -> rootContainer.addView(label(ScoresChildCopy.INSTALLED, 14f, t.textPrimary, true, bottomPad = 8).apply { tag = "scores_model_state" })
            RiskModelState.MISMATCH -> rootContainer.addView(label(ScoresChildCopy.MISMATCH, 14f, t.warmCoral, true, bottomPad = 8).apply { tag = "scores_model_state" })
            else -> {}
        }
        val bundledHere = RiskModelStore.bundledModelPresent(this)
        // A failed or cancelled install shows its own Retry on the result card, so the row appears when no result is on show.
        if (RiskModelStore.state(this) == RiskModelState.MISSING && bundledHere && !(job.bundled && job.result != null && job.result != RiskImportResult.OK)) {
            val password = BundledModel.needsPassword(this)
            rootContainer.addView(row("plus", ScoresChildCopy.BUNDLED_ROW, if (password) ScoresChildCopy.BUNDLED_ROW_DESC_PASSWORD else ScoresChildCopy.BUNDLED_ROW_DESC, t) {
                startBundledInstall()
            }.apply { getChildAt(0).tag = "model_bundled_row" })
        }
        if (RiskModelStore.state(this) != RiskModelState.INSTALLED)
            rootContainer.addView(row("plus", ScoresChildCopy.IMPORT_ROW, if (bundledHere) ScoresChildCopy.IMPORT_ROW_DESC_BUNDLED else ScoresChildCopy.IMPORT_ROW_DESC, t) { verifyParentForRoleChange(ScoresChildCopy.PASSWORD_IMPORT_TITLE, announceLeaving = false) { pickModelFile() } }.apply { getChildAt(0).tag = "model_import_row" })
        if (RiskModelStore.file(this).isFile) rootContainer.addView(actionButton(ScoresChildCopy.REMOVE, t.btnSecondaryBg, t.btnSecondaryText) {
            alert().setTitle(ScoresChildCopy.REMOVE_TITLE).setMessage(ScoresChildCopy.REMOVE_BODY + if (RiskModelStore.bundledModelPresent(this)) ScoresChildCopy.REMOVE_BUNDLED_NOTE else "").setNegativeButton(ScoresChildCopy.CANCEL, null)
                .setPositiveButton(ScoresChildCopy.REMOVE) { _, _ -> verifyParentForRoleChange(ScoresChildCopy.PASSWORD_REMOVE_TITLE, announceLeaving = false) { RiskLocal.removeModel(this); ModelImportJob.dismissResult(); renderUi() } }.show()
        }.apply { tag = "scores_model_remove" })
    }
    private fun pickModelFile() {
        try {
            modelPickAuthorized = true   // only reached after the parent's password was accepted
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), modelPickRequest)
        } catch (_: Exception) { modelPickAuthorized = false; Toast.makeText(this, "找不到檔案選擇器。\nNo file picker is available.", Toast.LENGTH_LONG).show() }
    }
    /** The one entry for installing the included copy (row and Retry): after a parent removed the model it always asks for the password first. */
    private fun startBundledInstall(authorized: Boolean = false) {
        if (!GuardEngine.isChildMode(this)) return
        if (BundledModel.needsPassword(this) && !authorized) { verifyParentForRoleChange(ScoresChildCopy.PASSWORD_BUNDLED_TITLE, announceLeaving = false) { startBundledInstall(true) }; return }
        ModelImportJob.dismissResult()
        ModelImportJob.startBundled(this)
        renderUi()
    }
    private fun startModelImport(uri: Uri) {
        if (!GuardEngine.isChildMode(this) || !RiskModelStore.supported(this)) return
        ModelImportJob.start(this, uri)
        renderUi()
    }

    // ================= Scores-only family mode: parent's phone =================
    private fun readScoresModel(): ScoresPageModel = FamilyEvidence(applicationContext).use { db ->
        val scope = GuardEngine.getChannelTopic(applicationContext)
        val state = db.scoresModeState(scope)
        ScoresUi.classify(GuardEngine.scoresModePolicy(applicationContext), state, db.riskScores(scope), active = state?.let(ScoresEngineBridge.activeOf))
    }
    private fun scoresOverviewRow(t: ThemeColors): View {
        val description = try { ScoresUi.overviewSummary(readScoresModel()) } catch (_: Exception) { "未能讀取分數" }
        return row("shield-check", "對話風險分數", description, t) { navigateTo("scores") }.apply { getChildAt(0).tag = "scores_overview_row" }
    }
    private fun handleScoresNotification(incoming: Intent) {
        if (!GuardEngine.isParentMode(this)) return
        setIntent(Intent(incoming).apply { action = Intent.ACTION_MAIN; data = null })
        if (!ScoresLink.isScoresLink(incoming, GuardEngine.getChannelTopic(this))) return
        section = "scores"
        if (activityVisible) renderUi()
    }

    private fun scoresStateIcon(p: ScoresPage) = when (p) {
        ScoresPage.OFF -> "info"; ScoresPage.WAITING, ScoresPage.RECONSENT, ScoresPage.PAUSED -> "clock"
        ScoresPage.MODEL_UNSUPPORTED -> "lock-key"; ScoresPage.WITHDRAWN -> "x"; ScoresPage.EMPTY -> "pulse"; ScoresPage.NOT_RUNNING -> "info"
        ScoresPage.MODEL_MISSING, ScoresPage.MODEL_WRONG, ScoresPage.ENGINE_UNAVAILABLE, ScoresPage.ERROR -> "shield-warning"; ScoresPage.LIST -> "check-circle"
    }
    private fun renderParentScores(t: ThemeColors) {
        heading("對話風險分數", "分數只是一個參考訊號，不是結論。", t)
        backLink()
        val session = GuardEngine.captureRelaySession(this); val container = rootContainer
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; container.addView(results)
        results.addView(stateNote(Note.LOADING, "正在讀取分數…", t, "scores_loading"))
        thread {
            val model = try { readScoresModel() } catch (_: Exception) { null }
            uiHandler.post {
                if (isDestroyed || rootContainer !== container || GuardEngine.captureRelaySession(this) != session) return@post
                results.removeAllViews()
                showScoresModel(results, model ?: ScoresPageModel(ScoresPage.ERROR, emptyList(), 0, 0, null), t)
            }
        }
    }
    private fun showScoresModel(out: LinearLayout, m: ScoresPageModel, t: ThemeColors) {
        val c = ScoresUi.copy(m)
        if (m.page == ScoresPage.LIST) {
            val lead = noticeCard(t, true, "scores_lead_card"); val n = onNotice(t)
            lead.addView(label(c.title, 14f, n.textSecondary, true, bottomPad = 6))
            lead.addView(label(c.body, 18f, n.textPrimary, true).apply { tag = "scores_lead" })
            ScoresUi.latestAnalysis(m)?.let { lead.addView(label(ScoresUi.latestAnalysisLine(it), 13f, n.textSecondary, topPad = 10).apply { tag = "scores_latest_analysis" }) }
            ScoresUi.oldAnalysisHint(m)?.let { lead.addView(label(it, 13f, n.textPrimary, topPad = 6).apply { tag = "scores_old_analysis" }) }
            out.addView(lead)
        }
        // The banner sits above everything else whenever a child state exists and is out of date.
        if (m.stale) {
            val banner = noticeCard(t, false, "scores_stale_banner")
            banner.addView(iconLine("clock", t.warmHoney, "分數可能已過時", t, 15f, "scores_stale_title"))
            banner.addView(label(ScoresUi.staleBody(m.state?.receivedAt ?: 0L), 14f, t.textSecondary))
            out.addView(banner)
        }
        if (m.page != ScoresPage.LIST) {
            val card = noticeCard(t, true, "scores_state_card"); val n = onNotice(t)
            card.addView(iconLine(scoresStateIcon(m.page), n.warmHoney, c.title, n, 20f, "scores_state_title"))
            card.addView(label(c.body, 15f, n.textSecondary, bottomPad = 10))
            card.addView(label(c.next, 15f, n.textPrimary, true).apply { tag = "scores_next_step" })
            when (m.page) {
                ScoresPage.OFF, ScoresPage.WITHDRAWN -> card.addView(actionButton("前往「家庭」", n.btnPrimaryBg, n.btnPrimaryText, t = n) { navigateTo("family") }.apply { tag = "scores_go_family" })
                ScoresPage.ERROR -> card.addView(actionButton("重試", n.btnPrimaryBg, n.btnPrimaryText, t = n) { renderUi() }.apply { tag = "scores_retry" })
                else -> {}
            }
            if (m.offerResend) {
                card.addView(label(ScoresUi.RESEND_HINT, 13f, n.textSecondary, topPad = 8, bottomPad = 4))
                card.addView(actionButton(ScoresUi.RESEND, n.btnSecondaryBg, n.btnSecondaryText, t = n) {
                    val ok = ScoresEngineBridge.resendPolicy(this)
                    Toast.makeText(this, if (ok) "已再次送出。" else ScoresUi.RESEND_FAILED, Toast.LENGTH_LONG).show(); renderUi()
                }.apply { tag = "scores_resend" })
            }
            out.addView(card)
        }
        if (ScoresUi.importRequestOffered(m)) showImportRequest(out, m, t)
        val notice = noticeCard(t, false, "scores_notice")
        notice.addView(label(ScoresUi.LIMITS_NOTICE, 14f, t.textPrimary, bottomPad = 8))
        notice.addView(label(ScoresUi.NEXT_STEPS, 14f, t.textSecondary, bottomPad = 8))
        m.state?.let { notice.addView(label(ScoresUi.unknownText(it.unknownThreads), 14f, t.textSecondary, bottomPad = 8).apply { tag = "scores_unknown" }) }
        notice.addView(label(ScoresUi.NO_MESSAGES, 14f, t.textSecondary, bottomPad = 4))
        notice.addView(textAction("查看協助建議") { showSafetyAdvice(null, t) }.apply { tag = "scores_safety_advice" })
        out.addView(notice)
        ScoresUi.listHeading(m)?.let { heading ->
            out.addView(label(heading, 16f, t.textPrimary, true, topPad = 16, bottomPad = 10).apply { tag = "scores_list_title" })
            for (row in m.rows) out.addView(scoreRow(row, t))
        }
    }
    /** Manual upload only: one request per 24 hours. Nothing here says the request was delivered or read. */
    private fun showImportRequest(out: LinearLayout, m: ScoresPageModel, t: ThemeColors) {
        val at = RiskLocal.parentImportRequestAt(this); val now = System.currentTimeMillis()
        val locked = ScoresUi.importRequestLocked(at, now)
        out.addView(actionButton(if (locked) ScoresUi.importRequestedAt(at) else ScoresUi.IMPORT_REQUEST, t.btnSecondaryBg, t.btnSecondaryText) {
            val ok = ScoresEngineBridge.requestImport(this)
            Toast.makeText(this, if (ok) ScoresUi.IMPORT_REQUEST_QUEUED else ScoresUi.IMPORT_REQUEST_FAILED, Toast.LENGTH_LONG).show(); renderUi()
        }.apply { tag = "scores_import_request"; isEnabled = !locked })
        ScoresUi.importRequestReset(at, now)?.let { out.addView(label(it, 13f, t.textSecondary, topPad = 4, bottomPad = 10).apply { tag = "scores_import_request_reset" }) }
        if (ScoresUi.noScoreSinceRequest(m, at, now)) out.addView(label(ScoresUi.NO_SCORE_SINCE_REQUEST, 13f, t.textSecondary, bottomPad = 10).apply { tag = "scores_no_score_since_request" })
    }
    private fun scoreRow(row: RiskScoreRow, t: ThemeColors): View {
        val band = ScoresUi.band(row)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(72)
            setPadding(dp(14), dp(12), dp(14), dp(12)); tag = "score_row"
            background = background(t.bgCard, 14, t.borderSubtle)
            isFocusable = true; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = ScoresUi.rowDescription(row)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(8)) }
            addView(icon(ScoresUi.bandIcon(band), bandColor(ScoresUi.bandToken(band), t), 28))
            val words = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
            words.addView(label(ScoresUi.bandTitle(band), 15f, t.textPrimary, true, bottomPad = 4))
            words.addView(label(ScoresUi.scoreText(row), if (row.score != null) 20f else 14f, t.textPrimary, row.score != null, bottomPad = 4))
            words.addView(label("${row.accountLabel} · ${ScoresUi.day(row.scoredAt)}", 13f, t.textSecondary))
            addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        }
    }

    private fun renderScoresSwitch(t: ThemeColors) {
        rootContainer.addView(Space(this), LinearLayout.LayoutParams(1, dp(18)))
        val (policy, state, rows) = try {
            val scope = GuardEngine.getChannelTopic(this)
            FamilyEvidence(this).use { Triple(GuardEngine.scoresModePolicy(this), it.scoresModeState(scope), it.riskScores(scope)) }
        } catch (_: Exception) { Triple(null, null, emptyList()) }
        val on = policy?.enabled == true
        val can = ScoresUi.canEnable(state)
        val usable = on || can
        val status = if (on) ScoresUi.switchStatus(policy, state, rows) else if (can) "未開啟 · 子女手機的模型檔已核對，可以開啟" else "未開啟 · 暫時不能開啟"
        val why = if (!on && !can) ScoresUi.enableBlockedWhy(state) else ""
        val line = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(74); setPadding(dp(14), dp(14), dp(14), dp(14)); tag = "scores_mode_switch"
            background = ripple(t.bgCard, 14, t.borderSubtle); isEnabled = usable; isFocusable = true
            contentDescription = "僅分數模式。${if (on) "已開啟" else "未開啟"}。$status。$why"
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = "android.widget.Switch"; info.isCheckable = true; info.isChecked = on; info.isEnabled = usable
                }
            }
            if (usable) {
                isClickable = true
                setOnClickListener(sessionClick { if (on) confirmScoresOff() else showScoresOnPage(t) })
            }
        }
        val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, dp(10), 0) }
        words.addView(label("僅分數模式 / Scores-only mode", 15f, t.textPrimary, true, bottomPad = 4).apply { if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true })
        words.addView(label(status, 13f, t.textSecondary, bottomPad = if (why.isEmpty()) 0 else 5).apply { tag = "scores_mode_status" })
        if (why.isNotEmpty()) words.addView(label(why, 13f, t.textPrimary).apply { tag = "scores_mode_why" })
        line.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        line.addView(android.widget.Switch(this).apply {
            isChecked = on; isEnabled = usable; isClickable = false; isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO; tag = "scores_mode_switch_view"
        })
        rootContainer.addView(line, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(8)) })
        if (on) {
            val method = policy!!.method
            rootContainer.addView(label(ScoresUi.methodLine(method), 13f, t.textSecondary, bottomPad = 4).apply { tag = "scores_method_line" })
            if (method == ScoresMethod.UPLOAD) rootContainer.addView(label(ScoresUi.METHOD_UPLOAD_BODY, 13f, t.textSecondary, bottomPad = 4).apply { tag = "scores_method_note" })
            rootContainer.addView(textAction(ScoresUi.METHOD_CHANGE) { showScoresMethodChange(t, method) }.apply { tag = "scores_method_change" })
        }
        rootContainer.addView(textAction("查看對話風險分數") { navigateTo("scores") })
    }
    private fun showScoresOnPage(t: ThemeColors) {
        showSheet(ScoresUi.PARENT_ON_TITLE) { dialog ->
            for (point in ScoresUi.PARENT_ON_POINTS) addView(label(point, 14f, t.textSecondary, bottomPad = 12))
            addMethodChooser(this, dialog, t, ScoresMethod.UPLOAD, "確認並開啟 / Confirm and turn on", null)
            addView(actionButton("取消 / Cancel", t.btnSecondaryBg, t.btnSecondaryText) { dialog.dismiss() }.apply { tag = "scores_on_cancel" })
        }
    }
    /** The parent already has the mode on and asks for the other method: the child must agree again. */
    private fun showScoresMethodChange(t: ThemeColors, current: String) {
        showSheet(ScoresUi.METHOD_CHANGE_TITLE) { dialog ->
            addView(label(ScoresUi.METHOD_CHANGE_POINTS, 14f, t.textSecondary, bottomPad = 12))
            addMethodChooser(this, dialog, t, if (current == ScoresMethod.UPLOAD) ScoresMethod.CAPTURE else ScoresMethod.UPLOAD, ScoresUi.METHOD_SEND, current)
            addView(actionButton("取消 / Cancel", t.btnSecondaryBg, t.btnSecondaryText) { dialog.dismiss() }.apply { tag = "scores_method_cancel" })
        }
    }
    /**
     * Method choice for the parent's sheets. Automatic capture shows the warning and an unticked box, and nothing is sent until it is ticked.
     * [current] is the method already in force (null when the mode is off); changing it needs a fresh device authentication, and so does choosing capture.
     */
    private fun addMethodChooser(box: LinearLayout, dialog: Dialog, t: ThemeColors, initial: String, confirmLabel: String, current: String?) {
        var method = initial
        var acked = false
        val choice = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var confirm: Button? = null
        fun allowed() = (method != ScoresMethod.CAPTURE || acked) && method != current
        fun draw() {
            choice.removeAllViews()
            choice.addView(label(ScoresUi.METHOD_TITLE, 15f, t.textPrimary, true, bottomPad = 6))
            for (m in listOf(ScoresMethod.UPLOAD, ScoresMethod.CAPTURE)) {
                val selected = m == method
                choice.addView(choiceButton(ScoresUi.chooserLabel(m), selected, t) {
                    method = m; acked = false; draw()
                }.apply { tag = "scores_method_$m" })
            }
            choice.addView(label(if (method == ScoresMethod.UPLOAD) ScoresUi.METHOD_UPLOAD_BODY else ScoresUi.METHOD_CAPTURE_BODY, 14f, t.textSecondary, topPad = 4, bottomPad = 12).apply { tag = "scores_method_body" })
            if (method == ScoresMethod.CAPTURE) {
                choice.addView(label(ScoresUi.WARNING_ZH_TITLE, 15f, t.warmCoral, true, bottomPad = 4).apply { tag = "scores_capture_warning" })
                choice.addView(label(ScoresUi.WARNING_ZH_BODY, 14f, t.textPrimary, bottomPad = 10))
                choice.addView(label(ScoresUi.WARNING_EN_TITLE, 15f, t.warmCoral, true, bottomPad = 4))
                choice.addView(label(ScoresUi.WARNING_EN_BODY, 14f, t.textPrimary, bottomPad = 10))
                choice.addView(checkRow(ScoresUi.WARNING_ACK, false, t).apply {
                    tag = "scores_capture_ack"
                    setOnCheckedChangeListener { _, on -> acked = on; confirm?.isEnabled = allowed() }
                })
            }
            confirm?.isEnabled = allowed()
        }
        box.addView(choice)
        val confirmButton = actionButton(confirmLabel, t.btnPrimaryBg, t.btnPrimaryText) {
            if (!allowed()) return@actionButton
            val chosen = method
            val send = {
                dialog.dismiss()
                if (ScoresEngineBridge.sendPolicy(this, true, chosen)) Toast.makeText(this, "已送出。子女同意後才會開始。", Toast.LENGTH_LONG).show()
                else Toast.makeText(this, if (current != null) ScoresUi.METHOD_FAILED else ScoresUi.SEND_FAILED, Toast.LENGTH_LONG).show()
                renderUi()
            }
            requireFreshParentAuth(send)
        }.apply { tag = "scores_on_confirm" }
        confirm = confirmButton
        box.addView(confirmButton)
        draw()
    }
    private fun confirmScoresOff() {
        alert().setTitle(ScoresUi.PARENT_OFF_TITLE).setMessage(ScoresUi.parentOffBody(GuardEngine.scoresModePolicy(this)?.method ?: ScoresMethod.CAPTURE)).setNegativeButton("取消 / Cancel", null)
            .setPositiveButton("確認關閉 / Turn off") { _, _ ->
                requireFreshParentAuth {
                    if (!ScoresEngineBridge.sendPolicy(this, false, GuardEngine.scoresModePolicy(this)?.method ?: ScoresMethod.CAPTURE)) Toast.makeText(this, ScoresUi.OFF_FAILED, Toast.LENGTH_LONG).show()
                    renderUi()
                }
            }.show()
    }

    private fun renderChildUnlock(t: ThemeColors) {
        backLink()
        heading("需要一點，自己的時間。", "輸入家長提供的 6 位臨時碼，可解鎖 15 分鐘。家長主密碼可解鎖 3 分鐘。", t)
        val input = inputBox(t, "臨時解鎖碼 或 家長主密碼", "", true); rootContainer.addView(input)
        val error = label("", 13f, t.warmCoral); rootContainer.addView(error)
        val lockout = GuardEngine.checkPasswordLockoutRemainingSec(this)
        if (lockout > 0) error.text = "輸入已鎖定，剩餘 $lockout 秒。"
        lateinit var verify: Button
        verify = actionButton("驗證並暫時解鎖", t.btnPrimaryBg, t.btnPrimaryText) {
            val remaining = GuardEngine.checkPasswordLockoutRemainingSec(this)
            if (remaining > 0) { error.text = "請等 $remaining 秒後再試。"; return@actionButton }
            if (GuardEngine.passwordCheckRunning.get()) { error.text = PASSWORD_CHECK_BUSY; return@actionButton }
            val entered = input.text.toString().trim()
            if (GuardEngine.redeem15MinUnlockCode(this, entered)) {
                GuardEngine.recordPasswordAttempt(this, true); GuardEngine.grantTemporaryUnlock(this, 15 * 60_000L)
                input.setText("")
                GuardEngine.sendAlertFromChild(this, "unlock_used", "@${GuardEngine.getChildSelfHandle(this).ifEmpty { "child" }}", "子女手機已使用 6 位臨時解鎖碼解鎖 15 分鐘", bypassDedupe = true)
                renderUi()
                return@actionButton
            }
            if (!GuardEngine.passwordCheckRunning.compareAndSet(false, true)) { error.text = PASSWORD_CHECK_BUSY; return@actionButton }
            fun checking(on: Boolean) { verify.isEnabled = !on; input.isEnabled = !on; verify.text = if (on) "核對中… · Checking…" else "驗證並暫時解鎖" }
            checking(true)
            // PBKDF2 takes seconds on older phones: off the main thread, as for the QR join (Join this family).
            thread {
                val ok = GuardEngine.verifyParentPassword(this, entered)
                runOnUiThread {
                    GuardEngine.passwordCheckRunning.set(false)
                    GuardEngine.recordPasswordAttempt(this, ok) // every attempt counts, even if the activity is gone
                    // A refresh may rebuild this page meanwhile: the unlock is still granted, as before (a stale view's text is harmless).
                    if (isDestroyed) return@runOnUiThread
                    if (!ok) { checking(false); error.text = "解鎖碼或家長主密碼不正確。"; input.error = "請重新輸入"; return@runOnUiThread }
                    GuardEngine.grantTemporaryUnlock(this, 3 * 60_000L)
                    input.setText(""); renderUi()
                }
            }
        }
        rootContainer.addView(verify)
        unlockTimerText = label("", 16f, t.accentSeafoam, true, topPad = 24); rootContainer.addView(unlockTimerText)
        if (GuardEngine.isTemporarilyUnlocked(this)) rootContainer.addView(textAction("立即重新上鎖") { GuardEngine.grantTemporaryUnlock(this, 0); renderUi() })
    }
    private fun renderSettings(t: ThemeColors) {
        if (GuardEngine.isParentMode(this) || GuardEngine.isChildMode(this)) backLink()   // no overview to go back to before a role is set
        heading("保護，照你的步調。", "把常用設定留在容易找到的地方。", t)
        sectionTitle("顯示方式", t)
        val modes = LinearLayout(this)
        for ((bright, text) in listOf(true to "淺色模式", false to "深色模式")) {
            lateinit var mode: Button
            mode = choiceButton(text, GuardEngine.isBrightTheme(this) == bright, t) { switchTheme(bright, mode) }
            modes.addView(mode, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(0, 0, dp(8), 0) })
        }
        rootContainer.addView(modes)
        sectionTitle("保護與通知", t)
        rootContainer.addView(row("info", "使用條款與同意記錄", "查看此版本的完整條款及最初同意記錄", t) {
            showSheet("使用條款與同意記錄") { _ ->
                addView(label(AppConsent.reviewText(this@MainActivity), 14f, t.textPrimary).apply { setTextIsSelectable(true) })
            }
        }.apply { tag = "terms_review" })
        rootContainer.addView(row("bell-simple", if (GuardEngine.isParentMode(this)) "家長警報通知" else "監察狀態通知", if (!GuardEngine.isParentMode(this) && GuardEngine.capturePausedByNotice(this)) GuardEngine.NOTICE_OFF_STATUS else "管理系統通知的顯示方式", t) { requestAppNotifications() })
        rootContainer.addView(row("shield", "權限與相容性", "了解 Android 授權及銀行 App 提醒", t) {
            showSheet("清楚了解，每一份授權。") { dialog ->
                addView(label("通知存取、無障礙服務及系統通知是不同權限，正式授權由 Android 處理。\n\n子女模式會讀取 Instagram 通知及已顯示的 DM；未送出草稿不會讀取。已確認的擴展模式按子女的選項包含已送出的傳出訊息及信任對象的一般脈絡。" + (if (scoresContentFree()) ScoresChildCopy.MODE_PERMISSIONS_CLAUSE else "記錄會加密傳至配對家長；新封存預設 30 天，待傳記錄最多 72 小時。") + "可在「你的擷取範圍」停止新擷取與待傳送及捨棄佇列；已送達副本須另行刪除。本 App 未獲 Meta 授權，也未整合官方資料轉移機制。銀行 App 或 Play Protect 仍可能提示風險，允許受限設定不代表這些服務已認可本 App。家長模式不啟用子女監察服務。\n\n若遇到封鎖，請保留完整警告及版本資料，交由開發者向 Google 提交審查。", 14f, t.textSecondary, bottomPad = 18))
                addView(actionButton("受限設定協助", t.btnSecondaryBg, t.btnSecondaryText) { dialog.dismiss(); openAppDetailsSettings() })
            }
        })
        rootContainer.addView(row("info", "開放原始碼及模型授權", "圖示、分析引擎及模型的來源及授權", t) {
            showSheet("開放原始碼及模型授權") { _ ->
                val id = resources.getIdentifier("ui_asset_licenses", "raw", packageName)
                if (id != 0) addView(label(resources.openRawResource(id).bufferedReader().use { it.readText() }, 12f, t.textSecondary))
            }
        })
        if(GuardEngine.isParentMode(this)) {
            val scope=GuardEngine.getChannelTopic(this)
            val other=FamilyEvidence(this).use{it.otherArchiveCount(scope)}
            if(other>0)rootContainer.addView(row("shield-check","其他家庭的本機封存","$other 項記錄，與目前家庭分開保存",t) {
                alert().setTitle("刪除其他家庭的封存？").setMessage("只刪除其他家庭在此手機上的封存文字和片段。目前家庭的證據不受影響；此操作無法復原。")
                    .setNegativeButton("取消",null).setPositiveButton("永久刪除") { _,_ ->
                        requireFreshParentAuth{FamilyEvidence(this).use{it.deleteOtherArchives(scope)};renderUi()}
                    }.show()
            })
        }
        renderCollapsibleDiagnostics(t)
        renderRoleSwitchFooter(t, GuardEngine.isChildMode(this))
    }
    private fun renderCollapsibleDiagnostics(t: ThemeColors) {
        rootContainer.addView(row("pulse", "連接與診斷", "查看最新裝置連接狀態", t) {
            showSheet("連接與診斷") { dialog ->
                addView(label("家長接收通道：${GuardEngine.cpParentStreamState}\n\n最新傳送：${GuardEngine.cpLastHttpStatus}\n\n最新同步：${GuardEngine.cpLastPollStatus}", 14f, t.textPrimary, bottomPad = 18))
                if (GuardEngine.isChildMode(this@MainActivity)) {
                    addView(label("畫面擷取：${GuardEngine.cpLastCaptureStatus}",13f,t.textSecondary,bottomPad=16))
                    addView(label("待確認片段：${GuardEngine.pendingDeliveryCount(this@MainActivity)}\n啟用安全配對後，新待傳片段最多保留 72 小時，或提前於家長手機確認接收後移除；確認接收不代表已閱讀。到期內容不再重試。", 13f, t.textSecondary, bottomPad = 16))
                    addView(actionButton("立即重試傳送", t.btnSecondaryBg, t.btnSecondaryText) { CloudRelay.retryNow(this@MainActivity); GuardEngine.retryPendingAlerts(this@MainActivity, force = true); dialog.dismiss() })
                    addView(actionButton("檢查系統背景電池限制", t.btnSecondaryBg, t.btnSecondaryText) {
                        launchSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    })
                }
                addView(label(GuardEngine.getCheckpointLogs(this@MainActivity), 12f, t.textSecondary, bottomPad = 18))
                addView(actionButton("清除診斷記錄", t.btnSecondaryBg, t.btnSecondaryText) { GuardEngine.clearCheckpointLogs(this@MainActivity); dialog.dismiss() })
            }
        })
    }
    private fun showRetentionSettings(t: ThemeColors) {
        val scope=GuardEngine.getChannelTopic(this)
        val preview=FamilyEvidence(this).use{it.retentionPreview(scope)}
        showSheet("記錄保留期限") { dialog ->
            addView(label("新封存預設保留 ${preview.retentionDays} 天，待傳內容最多 72 小時。明確保留供檢視的個別記錄最多 90 天。舊版本記錄不會在升級時直接刪除。",14f,t.textSecondary,bottomPad=12))
            for(days in listOf(7,30))addView(actionButton("新記錄保留 $days 天",t.btnSecondaryBg,t.btnSecondaryText){
                alert().setTitle("變更為 $days 天？").setMessage("現有新格式記錄的期限可能縮短；已過期內容會刪除。舊格式記錄仍須另行確認套用，限期保留的記錄不受縮短影響。")
                    .setNegativeButton("取消",null).setPositiveButton("確認變更"){_,_->requireFreshParentAuth{
                        FamilyEvidence(this@MainActivity).use{it.setRetentionDays(scope,days);it.pruneExpired(scope)};dialog.dismiss();renderUi()
                    }}.show()
            })
            addView(label("舊記錄：${preview.legacyCount} 則；按目前期限已過期：${preview.legacyExpiredCount} 則。採用後，過期內容會刪除，防重播識別碼仍保留。",14f,t.textSecondary,bottomPad=12))
            if(preview.legacyCount>0)addView(actionButton("預覽後，將期限套用至舊記錄",t.warningButtonBg,t.warningButtonText){
                alert().setTitle("將期限套用至舊記錄？").setMessage("${preview.legacyExpiredCount} 則舊記錄可能立即到期。先在記錄詳情選擇限期保留；已複製的外部副本不受影響。")
                    .setNegativeButton("取消",null).setPositiveButton("確認採用") {_,_->requireFreshParentAuth{
                        FamilyEvidence(this@MainActivity).use{it.applyRetentionToLegacy(scope);it.pruneExpired(scope,System.currentTimeMillis())};dialog.dismiss();renderUi()
                    }}.show()
            })
        }
    }

    private fun renderRoleSwitchFooter(t: ThemeColors, requirePin: Boolean) {
        sectionTitle("家庭管理", t)
        if(GuardEngine.isParentMode(this))rootContainer.addView(row("clock","保留期限與舊記錄預覽","設定新記錄到期日；舊記錄須確認後採用。",t){showRetentionSettings(t)})
        if (GuardEngine.isParentMode(this)) rootContainer.addView(row("key", "家長主密碼與金鑰", "密碼查看、金鑰更新及遠端救援", t) {
            showSheet("家長主密碼與金鑰") { dialog ->
                addView(actionButton("查看家長主密碼", t.btnSecondaryBg, t.btnSecondaryText) {
                    requireFreshParentAuth { alert().setTitle("家長主密碼").setMessage(GuardEngine.getPin(this@MainActivity)).setPositiveButton("關閉", null).show() }
                })
                addView(actionButton("秘密可能外洩：重新安全配對",t.warningButtonBg,t.warningButtonText){dialog.dismiss();showCompromiseRecovery(t)})
                addView(actionButton("例行更新金鑰（不能修復秘密外洩）", t.btnSecondaryBg, t.btnSecondaryText) {
                    sendParentCommand(resetChild = false); dialog.dismiss()
                })
                addView(actionButton("遠端解除子女保護並重設身份", t.btnSecondaryBg, t.btnSecondaryText, critical = true) {
                    alert(critical = true).setTitle("遠端重設子女手機？").setMessage("發送加密救援指令，暫時解除子女手機保護並返回身份設定。")
                        .setNegativeButton("取消", null).setPositiveButton("發送救援") { _, _ ->
                            sendParentCommand(resetChild = true); dialog.dismiss()
                        }.show()
                })
            }
        })
        rootContainer.addView(row("arrows-left-right", "切換手機身份", if (requirePin) "需要家長主密碼驗證" else "返回身份選擇畫面", t) {
            if (requirePin) verifyParentForRoleChange {
                GuardEngine.setRole(this, "unconfigured"); syncRoleServices(); selectedEntryRole = ""; section = "role"; renderUi(animate = true)
            } else navigateTo("role")
        })
        if (requirePin) rootContainer.addView(label("忘記家長主密碼時，可由家長手機使用遠端救援。", 13f, t.textSecondary, topPad = 16))
    }
    /**
     * Before the parent password and signing key disappear: show the password, and by default send a signed reset so the paired
     * child becomes unconfigured. The parent is told plainly what was and was not confirmed.
     */
    private fun releaseChildSheet(title: String, intro: String, purgeFamily: Boolean = false, proceed: (resetSent: Boolean, confirmed: Boolean) -> Unit) {
        val t = currentTheme(); val password = GuardEngine.getPin(this)
        showSheet(title) { dialog ->
            addView(label(intro, 14f, t.textSecondary, bottomPad = 14))
            addView(label("目前的家長主密碼（請先記下）：", 13f, t.textPrimary, true))
            addView(label(password, 18f, t.textPrimary, true, bottomPad = 10).apply { setTextIsSelectable(true) })
            addView(label("子女手機在解除前仍受保護。若解除指令沒有送達，子女手機換家庭時，需要在子女手機輸入這個主密碼。", 13f, t.textSecondary, bottomPad = 14))
            val send = checkRow("同時送出解除指令，讓子女手機回到未設定狀態（建議）", true, t).apply { tag = "release_child" }
            addView(send)
            val status = label("", 13f, t.textSecondary, topPad = 8, bottomPad = 8); addView(status)
            lateinit var go: Button
            lateinit var cancel: Button
            fun finished(message: String, resetSent: Boolean, confirmed: Boolean = false) {
                status.text = message; go.text = "我已記下，繼續"; go.isEnabled = true
                go.setOnClickListener(sessionClick { dialog.dismiss(); proceed(resetSent, confirmed) })
                // Once a reset has gone out the child may already be unconfigured: a plain Cancel would hide that.
                if (resetSent) cancel.text = "留在家長身份，之後重新配對子女手機"
            }
            go = actionButton("繼續", t.btnPrimaryBg, t.btnPrimaryText, critical = true) {
                go.isEnabled = false
                if (!send.isChecked) { finished("你選擇不送出解除指令。子女手機仍受保護，請記下上面的主密碼。", false); return@actionButton }
                status.text = "正在送出解除指令…"
                thread {
                    val outcome = GuardEngine.releaseChildBeforeLeaving(this@MainActivity, 8_000L, purgeFamily)
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        val (late, lateEn) = GuardEngine.releaseDelayNotice()
                        when (outcome) {
                            GuardEngine.ReleaseOutcome.SENT -> finished("解除指令已送到中繼站；子女手機開啟並連線後會變成未設定狀態。這不代表子女手機已確認；若它沒有解除，請在子女手機輸入上面的主密碼。若你留在家長身份，之後須用 QR 碼重新配對子女手機。$late / The release reached the relay; the child phone becomes unconfigured once it is on and connected. This does not mean the child phone confirmed it; if it is not released, enter the master password above on the child phone. If you stay a parent, you must pair the child phone again with the QR code later. $lateEn", true, confirmed = true)
                            GuardEngine.ReleaseOutcome.SENT_UNSURE -> finished("解除指令已送出，但此手機正在更新金鑰，子女手機可能沒有收到。請記下上面的主密碼，之後在子女手機輸入才能配對新家庭。若子女手機收到了，它會變成未設定狀態，須用 QR 碼重新配對。 / The release was sent, but this phone is updating its key, so the child phone may not have received it. Note the master password above; it must be entered on the child phone to pair with a new family. If the child phone received it, it becomes unconfigured and must be paired again by QR code.", true)
                            GuardEngine.ReleaseOutcome.SENT_UNCONFIRMED -> finished("解除指令可能已送出，但未獲中繼站確認：子女手機之後連線時可能會變成未設定狀態，也可能仍受保護。請記下上面的主密碼；若子女手機沒有解除，請在子女手機輸入它。$late / The release may have been sent, but the relay did not confirm it: the child phone may become unconfigured when it next connects, or may still be protected. Note the master password above; if the child phone is not released, enter it on the child phone. $lateEn", true)
                            GuardEngine.ReleaseOutcome.NOT_SENT -> finished("解除指令未能送出，子女手機仍受保護。請記下上面的主密碼，之後在子女手機輸入才能配對新家庭。", false)
                        }
                    }
                }
            }
            addView(go)
            cancel = textAction("取消") { dialog.dismiss() }
            addView(cancel)
        }
    }
    private fun showCompromiseRecovery(t: ThemeColors) {
        showSheet("重新安全配對") { dialog ->
            addView(label("請先確認兩部手機及解鎖憑證安全。此操作建立全新的家庭秘密與家長簽署身份，不經舊通道傳送。舊本機封存保留；未傳送內容及舊信任設定會捨棄，子女手機須當面重新配對（掃描 QR 碼並輸入 6 位數字）。已被複製的舊資料無法收回。 可先要求中繼刪除舊房間：App 會先向子女手機送出解除指令，中繼確認後舊房間即永久停用，金鑰不能再使用。若中繼未能確認，舊房間上未被取走的密文最多保留 7 天。",14f,t.textSecondary,bottomPad=18))
            fun create(resetSent: Boolean, roomDeleted: Boolean = false) {
                    try {
                        GuardEngine.createFreshFamilyForRecovery(this@MainActivity)
                        parentAccess.authenticated(GuardEngine.captureRelaySession(this@MainActivity))
                        dialog.dismiss();syncRoleServices();renderUi();showParentPairingSheet()
                    } catch(_:Exception){
                        // After a deletion the current invite points at a room that no longer exists: only a fresh pairing helps.
                        val message = if (roomDeleted) "舊中繼房間已刪除，但此手機沒有完成重新配對；原有本機資料已保留。舊邀請已不能使用，請重試「重新安全配對」以建立新配對。"
                            else if (resetSent) "子女手機可能已收到解除指令並變成未設定狀態，但此手機沒有完成重新配對；原有家庭資料已保留。請重試「重新安全配對」，或在「加入子女」以 QR 碼重新配對子女手機。\nThe child phone may have received the release and become unconfigured, but this phone did not finish pairing again; the existing family data is kept. Retry “重新安全配對” (pair again securely), or pair the child phone again by QR code under “Add a child”."
                            else "未能完成；原有資料已保留。請重試或聯絡支援。"
                        alert().setTitle("未能完成重新配對").setMessage(message).setPositiveButton("知道了", null).show()
                    }
            }
            fun fresh() {
                if(!parentUnlocked())return
                releaseChildSheet("重新安全配對","新的家庭秘密不經舊通道傳送；子女手機須解除舊配對後才能重新配對。") { resetSent, _ -> create(resetSent) }
            }
            addView(actionButton("嘗試刪除舊中繼房間，再建立新配對",t.warningButtonBg,t.warningButtonText){requireFreshParentAuth {
                if(!parentUnlocked())return@requireFreshParentAuth
                // The signed release must leave before the room does: a deleted room delivers nothing.
                releaseChildSheet("刪除舊中繼房間","先向子女手機送出已簽署的解除指令，然後刪除舊中繼房間。子女手機收到後會清除舊配對的金鑰及未傳送記錄；若它離線，需當面重新配對。",purgeFamily=true) { resetSent, confirmed ->
                    if(resetSent && !confirmed){
                        Toast.makeText(this@MainActivity,"解除指令未獲中繼站確認，舊中繼房間未刪除：現在刪除可能令子女手機收不到解除指令。請稍後再試，或改選「只建立新配對」。\nThe relay did not confirm the release, so the old relay room was not deleted: deleting it now could stop the release reaching the child phone. Try again later.",Toast.LENGTH_LONG).show()
                        return@releaseChildSheet
                    }
                    val expected=GuardEngine.captureRelaySession(this@MainActivity)
                    CloudRelay.retireRoom(this@MainActivity){ok,message->runOnUiThread{
                        if(isDestroyed || !parentUnlocked() || GuardEngine.captureRelaySession(this@MainActivity)!=expected)return@runOnUiThread
                        if(ok)create(resetSent,roomDeleted=true)
                        else Toast.makeText(this@MainActivity,if(resetSent)"$message 子女手機如已解除，請在「加入子女」以 QR 碼重新配對。\nIf the child phone was released, pair it again by QR code under “Add a child”." else message,Toast.LENGTH_LONG).show()
                    }}
                }
            }})
            addView(actionButton("只建立新配對（不刪除中繼資料）",t.btnSecondaryBg,t.btnSecondaryText){
                alert(critical = true).setTitle("未確認中繼刪除，仍要繼續？").setMessage("新秘密不會經舊通道傳送，但舊密文可能仍留在中繼直到到期。子女手機需離線停止舊擷取，然後在安全裝置重新配對；已送達或被複製資料不會刪除。")
                    .setNegativeButton("取消",null).setPositiveButton("了解並建立新配對"){_,_->requireFreshParentAuth{fresh()}}.show()
            })
        }
    }

    private fun sendParentCommand(resetChild: Boolean) = requireFreshParentAuth {
        Toast.makeText(this, "正在傳送已簽署的家長指令。", Toast.LENGTH_SHORT).show()
        GuardEngine.rotateKeyAndNotifyChild(this, resetChild) { success ->
            uiHandler.post {
                if (!activityVisible || !GuardEngine.isParentMode(this)) return@post
                val sent = if (!resetChild) "指令已發送，請等待子女手機確認。"
                    else GuardEngine.releaseDelayNotice().let { (zh, en) -> "指令已發送，請等待子女手機確認。$zh / The command was sent; wait for the child phone to confirm. $en" }
                Toast.makeText(this, if (success) sent else "未能發送，保留目前配對。請查看診斷後重試。", Toast.LENGTH_LONG).show()
                renderUi()
            }
        }
    }
    private var activeSheet: Dialog? = null
    private var storageReady = false
    private var consentVisible = false
    private fun showInitialConsent() {
        storageReady = false
        consentVisible = true
        activityVisible = false
        uiHandler.removeCallbacksAndMessages(null)
        dismissUiDialogs()
        CloudRelay.disconnect()
        stopService(Intent(this, ParentAlertService::class.java))
        GuardEngine.syncChildComponents(this)
        val bright = brightOrDefault(); applyTheme(bright)
        setContentView(AppConsent.page(this, themeColors(bright)) { recreate() })
    }
    private fun showStorageFailure() {
        storageReady = false
        activityVisible = false
        uiHandler.removeCallbacksAndMessages(null)
        dismissUiDialogs()
        // The encrypted preferences may be what failed: read the theme defensively and use no helper that reads it again.
        val bright = brightOrDefault(); applyTheme(bright); val t = themeColors(bright)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(48), dp(24), dp(24)); setBackgroundColor(color(t.bgCanvas)) }
        content.addView(label("無法開啟加密儲存空間", 21f, t.textPrimary, true, bottomPad = 16))
        content.addView(label("現有資料已保留。請先解鎖手機再重試。如持續失敗，請保留 App 資料並聯絡支援；請勿清除資料或重新配對。", 16f, t.textPrimary, bottomPad = 24))
        content.addView(Button(this).apply { text = "重試"; actionLook(this, t.btnPrimaryBg, t.btnPrimaryText, t); setOnClickListener { recreate() } })
        setContentView(content)
    }
    private val uiDialogs = mutableSetOf<Dialog>()
    private fun trackDialog(dialog: Dialog, onDismiss: (() -> Unit)? = null) {
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        uiDialogs.add(dialog)
        dialog.setOnDismissListener {
            uiDialogs.remove(dialog)
            if (activeSheet === dialog) activeSheet = null
            onDismiss?.invoke()
        }
    }
    private fun dismissUiDialogs() {
        uiDialogs.toList().forEach { it.dismiss() }
        uiDialogs.clear(); activeSheet = null
    }
    // A detached parent control must never act on a newly paired child's credentials.
    private fun sessionClick(action: () -> Unit): View.OnClickListener {
        val session = GuardEngine.captureRelaySession(this)
        return View.OnClickListener {
            if (GuardEngine.captureRelaySession(this) == session && parentUnlocked()) action()
            else { dismissUiDialogs(); renderUi() }
        }
    }
    private fun warnObscured() { Toast.makeText(this, OBSCURED_TOUCH_MESSAGE, Toast.LENGTH_LONG).show() }
    /** The one dialog builder: the platform alert theme matching the app theme (list rows, ripples), then the card surface, 14 dp corners, token text and the app font.
     *  [critical] dialogs (destructive or identity-changing confirmations) ignore touches under another app's window and say so. */
    private fun alert(critical: Boolean = false): AlertDialog.Builder {
        val session = GuardEngine.captureRelaySession(this)
        val bright = GuardEngine.isBrightTheme(this); val t = themeColors(bright)
        return object : AlertDialog.Builder(this@MainActivity, if (bright) android.R.style.Theme_Material_Light_Dialog_Alert else android.R.style.Theme_Material_Dialog_Alert) {
            override fun show(): AlertDialog = create().also { dialog ->
                dialog.window?.setBackgroundDrawable(android.graphics.drawable.InsetDrawable(background(t.bgCard, 14, t.borderSubtle), dp(16)))
                dialog.setOnShowListener {
                    dialog.findViewById<TextView>(resources.getIdentifier("alertTitle", "id", "android"))?.apply { setTextColor(color(t.textPrimary)); typeface = font(true) }
                    dialog.findViewById<TextView>(android.R.id.message)?.apply { setTextColor(color(t.textSecondary)); typeface = font() }
                    for (which in intArrayOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)) dialog.getButton(which)?.apply { setTextColor(color(t.accentSeafoam)); typeface = font(true); isAllCaps = false }
                    if (critical) {
                        for (which in intArrayOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)) dialog.getButton(which)?.filterTouchesWhenObscured = true
                        dialog.window?.let { window ->
                            val inner = window.callback
                            window.callback = object : Window.Callback by inner {
                                override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                                    if (event.actionMasked == MotionEvent.ACTION_DOWN && event.flags and MotionEvent.FLAG_WINDOW_IS_OBSCURED != 0) warnObscured()
                                    return inner.dispatchTouchEvent(event)
                                }
                            }
                        }
                    }
                }
                trackDialog(dialog);dialog.show()
            }
            override fun setPositiveButton(text: CharSequence?, listener: DialogInterface.OnClickListener?): AlertDialog.Builder =
                super.setPositiveButton(text) { dialog, which ->
                    if (GuardEngine.captureRelaySession(this@MainActivity) == session && parentUnlocked()) listener?.onClick(dialog, which)
                    else { dialog.dismiss(); dismissUiDialogs(); renderUi() }
                }
        }
    }
    private fun showSheet(title: String, content: LinearLayout.(Dialog) -> Unit) = showTrackedSheet(title, null, content)
    /** [showSheet] with [onDismiss] run however the sheet closes (the QR pairing sheets end their pairing then). */
    private fun showTrackedSheet(title: String, onDismiss: (() -> Unit)?, content: LinearLayout.(Dialog) -> Unit) {
        if(!parentUnlocked()){renderParentLock();return}
        activeSheet?.dismiss()
        val t = currentTheme(); val dialog = Dialog(this)
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = background(t.bgCard, 14); setPadding(dp(24), dp(10), dp(24), dp(24)) }
        val handle = View(this).apply { background = background(t.borderSubtle, 3) }
        wrap.addView(handle, LinearLayout.LayoutParams(dp(32), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; setMargins(0, 0, 0, dp(14)) })
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(label(title, 21f, t.textPrimary, true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(iconButton("x", "關閉對話框") { dialog.dismiss() }); wrap.addView(header)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(14), 0, 0) }
        body.content(dialog); wrap.addView(body)
        val scroll = ScrollView(this).apply { addView(wrap); isFillViewport = false }
        dialog.setContentView(scroll)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setGravity(Gravity.BOTTOM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); setDimAmount(.30f)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            attributes = attributes.apply { windowAnimations = if (ValueAnimator.areAnimatorsEnabled()) resources.getIdentifier("GuardSheetAnimation", "style", packageName) else 0 }
        }
        trackDialog(dialog, onDismiss)
        dialog.show()
        fitSheet(dialog)
        if (Build.VERSION.SDK_INT >= 28) wrap.accessibilityPaneTitle = title
        activeSheet = dialog
    }
    /** Sizes a sheet to its content (at most 82 % of the screen); again after a sheet's content changed (QR pairing states). */
    private fun fitSheet(dialog: Dialog) {
        val scroll = dialog.findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0) ?: return
        val width = resources.displayMetrics.widthPixels
        scroll.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec((resources.displayMetrics.heightPixels * .82f).toInt(), View.MeasureSpec.AT_MOST))
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, scroll.measuredHeight.coerceAtMost((resources.displayMetrics.heightPixels * .82f).toInt()))
    }
    private fun copyText(title: String, value: String) {
        if(!parentUnlocked()){renderParentLock();return}
        SensitiveClipboard.copy(this,title,value)
        Toast.makeText(this, "已複製；如仍是此項，60 秒後清除剪貼簿。", Toast.LENGTH_SHORT).show()
    }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun divider(parent: LinearLayout, t: ThemeColors) { parent.addView(View(this).apply { setBackgroundColor(color(t.borderSubtle)) }, LinearLayout.LayoutParams(-1, dp(1))) }
    private fun sectionTitle(title: String, t: ThemeColors) { rootContainer.addView(label(title, 16f, t.textPrimary, true, topPad = 22, bottomPad = 14)) }
    private fun makeCard(t: ThemeColors, isHero: Boolean = false): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; background = background(if (isHero) t.bgHero else t.bgCard, 14, t.borderSubtle)
        if (t == themeColors(true)) elevation = dp(1).toFloat()   // light only: a faint lift off the canvas; its outline follows the 14 dp corners
        setPadding(dp(18), dp(18), dp(18), dp(18))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(12), 0, dp(12)) }
    }
    /** [fill] is the surface the row sits on (the page by default, [ThemeColors.bgCard] inside a card). */
    private fun row(glyph: String, title: String, description: String, t: ThemeColors, tint: String = t.textPrimary, fill: String = t.bgCanvas, onClick: () -> Unit): LinearLayout {
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val line = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(74); setPadding(0, dp(14), 0, dp(14))
            isClickable = true; isFocusable = true; background = ripple(fill, 10)
            contentDescription = "$title。$description"; setOnClickListener(sessionClick(onClick))
            describeAsControl()
        }
        line.addView(icon(glyph, t.accentSeafoam, 24))
        val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(10), 0) }
        words.addView(label(title, 15f, tint, true, bottomPad = 4)); words.addView(label(description, 13f, t.textSecondary))
        line.addView(words, LinearLayout.LayoutParams(0, -2, 1f)); line.addView(icon("caret-right", t.textMuted, 18))
        outer.addView(line); divider(outer, t); return outer
    }
    internal fun label(text: String, sizeSp: Float, hexColor: String, bold: Boolean = false, topPad: Int = 0, bottomPad: Int = 0): TextView = TextView(this).apply {
        this.text = text; textSize = sizeSp; typeface = font(bold); setTextColor(color(hexColor))
        includeFontPadding = false; setLineSpacing(dp(3).toFloat(), 1.05f)
        setPadding(0, dp(topPad), 0, dp(bottomPad))
        if (bold && sizeSp >= 20 && Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
    }
    private fun inputBox(t: ThemeColors, hintText: String, initialValue: String, isPassword: Boolean = false, remember: Boolean = true): EditText = EditText(this).apply {
        hint = hintText; contentDescription = hintText; textSize = 15f; typeface = font()
        setTextColor(color(t.textPrimary)); setHintTextColor(color(t.textMuted)); background = focusSurface(t.bgInput, 10, t.accentPowderSky)
        inputType = if (isPassword) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT
        if (isPassword) inputDrafts.remove(hintText)
        setText(if (remember && !isPassword) inputDrafts[hintText] ?: initialValue else initialValue)
        if (remember && !isPassword) addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { inputDrafts[hintText] = s.toString() }
            override fun afterTextChanged(s: Editable?) {}
        })
        minimumHeight = dp(54); setPadding(dp(14), dp(14), dp(14), dp(14)); isSaveEnabled = false
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(12)) }
    }
    private fun actionButton(title: String, hexBg: String, hexText: String, critical: Boolean = false, t: ThemeColors = currentTheme(), onClick: () -> Unit): Button =
        (if (critical) ObscuredAwareButton(this, { pageMotion?.isRunning == true || themeReveal?.isRunning == true }) { warnObscured() } else Button(this)).apply {
        text = title; actionLook(this, hexBg, hexText, t); setOnClickListener(sessionClick(onClick))
    }
    /** The action-button look only (no click handling). Disabled = the same fill at half alpha and the text at 70 % (still readable on the dark fill), no ripple. */
    internal fun actionLook(button: Button, hexBg: String, hexText: String, t: ThemeColors = currentTheme()) = button.apply {
        textSize = 14f; typeface = font(true); isAllCaps = false
        val ink = color(hexText); val disabled = intArrayOf(-android.R.attr.state_enabled)
        setTextColor(ColorStateList(arrayOf(disabled, intArrayOf()), intArrayOf((ink and 0x00ffffff) or 0xB3000000.toInt(), ink)))
        val border = if (hexBg != t.btnPrimaryBg) t.accentPowderSky else null
        background = StateListDrawable().apply {
            addState(disabled, background(hexBg, 10, border).apply { alpha = 128 })
            addState(intArrayOf(), ripple(hexBg, 10, border, t))
        }
        minimumHeight = dp(52); setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(4), 0, dp(8)) }
    }
    /** The one back affordance at the top of a sub-page; it does exactly what system Back does there. */
    private fun backLink(title: String = "返回總覽", onClick: () -> Unit = { navigateTo("overview") }) =
        rootContainer.addView(textAction(title, onClick = onClick).apply { tag = "back_link" })
    /** The one checkbox look (no state read, so the consent page can use it): token text and box colours, app font, 48 dp row. */
    internal fun checkLook(box: CheckBox, t: ThemeColors, sizeSp: Float = 14f) = box.apply {
        textSize = sizeSp; typeface = font(); setTextColor(color(t.textPrimary)); minimumHeight = dp(48)
        buttonTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(color(t.accentSeafoam), color(t.textSecondary)))
    }
    private fun checkRow(text: String, checked: Boolean, t: ThemeColors = currentTheme()): CheckBox =
        checkLook(CheckBox(this), t).apply { this.text = text; isChecked = checked; setPadding(dp(4), dp(8), 0, dp(8)) }
    /**
     * The one choice control (one of a few options): the primary look plus a check icon when selected, the secondary look otherwise.
     * TalkBack hears a radio button and its checked state; the selected state is never colour alone.
     */
    private fun choiceButton(title: String, selected: Boolean, t: ThemeColors = currentTheme(), onClick: () -> Unit): Button =
        actionButton(title, if (selected) t.btnPrimaryBg else t.btnSecondaryBg, if (selected) t.btnPrimaryText else t.btnSecondaryText, onClick = onClick).apply {
        isSelected = selected; describeAsControl(choice = true)
        contentDescription = title + if (selected) "，已選" else ""
        if (Build.VERSION.SDK_INT >= 30) stateDescription = if (selected) "已選" else "未選"
        if (selected) {
            val id = drawableIds.getOrPut("check") { resources.getIdentifier("ic_guard_check", "drawable", packageName) }
            if (id != 0) { setCompoundDrawablesRelative(resources.getDrawable(id, theme).mutate().apply { setTint(color(t.btnPrimaryText)); setBounds(0, 0, dp(18), dp(18)) }, null, null, null); compoundDrawablePadding = dp(8) }
        }
        setPadding(dp(10), dp(12), dp(10), dp(12))
    }
    /** The third button level: a text link (no fill, no border, 48 dp target). [ink] is the text colour; deletes pass the danger token so they stay as visible as before. */
    private fun textAction(title: String, ink: String? = null, t: ThemeColors = currentTheme(), onClick: () -> Unit): Button = Button(this).apply {
        text = title; textSize = 13f; typeface = font(true); isAllCaps = false
        setTextColor(color(ink ?: t.accentSeafoam)); background = ripple(TRANSPARENT_FILL, 10, t = t); minimumHeight = dp(48)
        gravity = Gravity.CENTER_VERTICAL or Gravity.START; setPadding(dp(12), dp(10), dp(12), dp(10)); setOnClickListener(sessionClick(onClick))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply{setMargins(-dp(12),dp(4),0,dp(6))}   // text lines up with the content edge; the ripple uses the gutter
    }
    private fun checkInitialPairingConfirmation() {
        if (!GuardEngine.isChildMode(this)) return
        val notifOn = GuardEngine.isNotificationListenerEnabledStatic(this)
        if (notifOn && !GuardEngine.hasSentInitialSetupConfirmation(this)) {
            GuardEngine.markInitialSetupConfirmationSent(this, true)
            GuardEngine.scheduleNextRandomHeartbeat(this)
            GuardEngine.sendAlertFromChild(
                this,
                "pairing_verified",
                Build.MODEL ?: "Child_Phone",
                "子女手機 (${Build.MODEL}) 已開啟 IG 私訊保護！系統將自動偵測並同步子女 IG 帳號。",
                bypassDedupe = true
            )
            Toast.makeText(this, "Convoy 保護已啟動並通知家長手機。", Toast.LENGTH_LONG).show()
        }
    }

    private fun openDirectNotificationToggle() {
        showAccessExplanation(false)
    }

    private fun openAccessibilityToggle() {
        showAccessExplanation(true)
    }

    private fun showAccessExplanation(accessibility: Boolean) {
        if (!GuardEngine.isChildMode(this)) return
        val purpose = if (accessibility)
            "讀取 Instagram 畫面上顯示的對話文字，以偵測高危對話。此功能需要無障礙存取；銀行 App 可能因此提示風險。"
        else "讀取 Instagram 通知，將警報加密傳送到家長手機。Android 授予的是通知存取權；本 App 只處理 Instagram。"
        alert().setTitle(if (accessibility) "進階畫面保護" else "通知讀取保護")
            .setMessage(purpose + "\n\n下一步由 Android 顯示授權頁。請自行閱讀及確認；App 不能代你授權。若開關被封鎖，返回此頁選擇「受限設定協助」。")
            .setNegativeButton("稍後", null)
            .setPositiveButton("前往系統設定") { _, _ -> launchAccessSettings(accessibility) }.show()
    }

    private fun launchAccessSettings(accessibility: Boolean) {
        if (!GuardEngine.isChildMode(this)) return
        val fallback = Intent(if (accessibility) Settings.ACTION_ACCESSIBILITY_SETTINGS else Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        if (!accessibility && Build.VERSION.SDK_INT >= 30) {
            val direct = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(this, IgNotificationListener::class.java).flattenToString())
            if (launchSettings(direct, quiet = true)) return
        }
        launchSettings(fallback)
    }

    private fun launchSettings(intent: Intent, quiet: Boolean = false): Boolean {
        return try {
            startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            if (!quiet) Toast.makeText(this, "此裝置未提供該設定捷徑，請從系統設定手動開啟。", Toast.LENGTH_LONG).show()
            false
        } catch (_: SecurityException) {
            if (!quiet) Toast.makeText(this, "系統不允許開啟此設定，請從系統設定手動查看。", Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun openAppDetailsSettings() {
        alert().setTitle("受限設定協助 · Samsung / Android")
            .setMessage("1. 先在通知存取或無障礙頁嘗試開啟 Convoy，閱讀系統顯示的限制。\n\n2. 若系統要求允許受限設定，再開啟本 App 的資訊頁。在支援此流程的裝置，右上角 ⋮ 才可能出現「允許受限設定」。依系統提示確認身份。\n\n3. 返回本 App，再次開啟原來的權限。\n\n若沒有該選項，請遵從裝置顯示的說明；選項取決於系統版本及安裝來源。這項操作不代表 Play Protect 或銀行 App 已認可本 App。")
            .setNegativeButton("返回", null)
            .setPositiveButton("開啟 App 資訊") { _, _ ->
                launchSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            }.show()
    }

    private fun requestAppNotifications() {
        val permission = "android.permission.POST_NOTIFICATIONS"
        val asked = getPreferences(MODE_PRIVATE).getBoolean("notifications_requested", false)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED &&
            (!asked || shouldShowRequestPermissionRationale(permission))) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("notifications_requested", true).apply()
            requestPermissions(arrayOf(permission), 101)
        } else {
            launchSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            GuardEngine.refreshMonitoringDisclosure(this)
            renderUi()
        }
        if (requestCode == CAMERA_REQUEST) {
            cameraDenied = grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED
            fillChildPairing()
        }
    }

    private fun syncRoleServices() {
        GuardEngine.syncChildComponents(this)
        if (GuardEngine.isParentMode(this)) {
            val svc = Intent(this, ParentAlertService::class.java)
            startForegroundService(svc)
        } else {
            stopService(Intent(this, ParentAlertService::class.java))
            if (GuardEngine.isChildMode(this)) {
                GuardEngine.checkAndSendChildHeartbeatIfDue(this)
                thread { GuardEngine.pollChannelOnce(applicationContext, since = "2h") }
            }
        }
    }

}
