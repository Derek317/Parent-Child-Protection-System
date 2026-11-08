package app.family.igguard

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.Dialog
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
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.ColorDrawable
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
        seen.put(id, now)
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
 * IG Guard — explicit, user-controlled Android special access
 * Family-Friendly Instagram DM & Comment-Reply Safety Guard
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
    private val recentChildSentTexts = LinkedHashMap<String, Long>()
    // State transactions only. Never hold this lock during network I/O.
    private val pollLock = Any()
    private val deliveryInFlight = AtomicBoolean(false)
    private data class DeliveryCallback(val origin:Triple<String,String,Int>,val complete:(Boolean,String)->Unit)
    private val deliveryCallbacks = java.util.concurrent.ConcurrentHashMap<Pair<String,String>, DeliveryCallback>()
    private var relayGeneration = 0

    fun captureRelaySession(ctx: Context): Triple<String, String, Int> = synchronized(pollLock) {
        Triple(getRole(ctx), getOrCreateFamilyToken(ctx), relayGeneration)
    }

    // Live Checkpoint & Heartbeat States
    @Volatile var cpNotifConnected: Boolean = false
    @Volatile var cpLastNotifTime: String = "Never"
    @Volatile var cpAccConnected: Boolean = false
    @Volatile var cpLastAccTime: String = "Never"
    @Volatile var cpLastCaptureStatus: String = "尚未讀取 Instagram 對話畫面"
    @Volatile var cpLastHttpStatus: String = "Ready (TLS + AES-256-GCM)"
    @Volatile var cpLastPollStatus: String = "尚未同步"
    @Volatile var cpParentStreamState: String = "Idle"

    private val EMOJI_PALETTE = arrayOf(
        "🐼", "🦁", "🐬", "🦊", "🐨", "🐯", "🦄", "🐝",
        "🚀", "🌈", "🍀", "🌻", "🍎", "🍉", "⚽", "🎸"
    )

    private val PAIR_V42_REGEX = Regex("IGGUARD-PAIR-V42:([a-zA-Z0-9\\-]{8,64}):([a-zA-Z0-9_\\-=]+)")
    private val PAIR_LEGACY_REGEX = Regex("IGGUARD-PAIR:([a-zA-Z0-9\\-]{8,64}):([^\\]\\s]+)")
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

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun nowTimeStr(): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
    fun nowDateTimeStr(): String = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())

    fun constantTimeEquals(a: String, b: String): Boolean {
        val bytesA = a.toByteArray(Charsets.UTF_8)
        val bytesB = b.toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(bytesA, bytesB)
    }

    // Tracks messages typed by the child in Instagram's DM input bar so the child's own outgoing messages are never alerted
    @Synchronized
    fun recordChildTypedDmText(raw: String) {
        val clean = raw.trim()
        if (clean.length < 2 || isNonMessageUiString(clean)) return
        val now = System.currentTimeMillis()
        val iter = recentChildSentTexts.entries.iterator()
        while (iter.hasNext()) {
            if (now - iter.next().value > 10 * 60_000L) iter.remove()
        }
        recentChildSentTexts[clean.lowercase(Locale.US)] = now
    }

    @Synchronized
    fun wasRecentlyTypedByChild(candidate: String): Boolean {
        val clean = candidate.trim().lowercase(Locale.US)
        if (clean.isEmpty()) return false
        val now = System.currentTimeMillis()
        for ((typed, ts) in recentChildSentTexts) {
            if (now - ts <= 10 * 60_000L) {
                if (clean == typed || (clean.length >= 4 && typed.startsWith(clean)) || (typed.length >= 4 && clean.startsWith(typed))) {
                    return true
                }
            }
        }
        return false
    }

    fun isSelfSenderLabel(ctx: Context, rawSender: String): Boolean {
        val norm = rawSender.trim().lowercase(Locale.US).removePrefix("@")
        if (norm.isEmpty()) return false
        if (norm == "you" || norm == "你" || norm == "本人" || norm == "me") return true
        return isChildSelfHandle(ctx, norm)
    }

    // Theme Mode: true = Warm Bright Mode ("Morning Harbor"), false = Warm Dark Mode ("Cozy Lantern")
    fun isBrightTheme(ctx: Context): Boolean = prefs(ctx).getBoolean("bright_theme", true)
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

    fun decryptPayload(token: String, wireStr: String): JSONObject? = try {
        val combined = Base64.decode(JSONObject(wireStr).getString("e2ee"), Base64.NO_WRAP)
        if (combined.size < 28) null
        else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, deriveAesKey(token), GCMParameterSpec(128, combined.copyOfRange(0, 12)))
            JSONObject(String(cipher.doFinal(combined.copyOfRange(12, combined.size)), Charsets.UTF_8))
        }
    } catch (_: Exception) { null }

    // ================= 2. RANDOM 30-95 MIN HEARTBEAT RELAY & WATCHDOG =================
    fun scheduleNextRandomHeartbeat(ctx: Context): Long {
        val randomDelayMs = Random.Default.nextLong(30 * 60_000L, 95 * 60_000L + 1L)
        val nextAt = System.currentTimeMillis() + randomDelayMs
        prefs(ctx).edit()
            .putLong("next_heartbeat_at_ms", nextAt)
            .putLong("last_heartbeat_interval_min", randomDelayMs / 60_000L)
            .apply()
        return nextAt
    }

    fun checkAndSendChildHeartbeatIfDue(ctx: Context, force: Boolean = false) {
        if (!isChildMode(ctx)) return
        val now = System.currentTimeMillis()
        var nextAt = prefs(ctx).getLong("next_heartbeat_at_ms", 0L)
        if (nextAt == 0L) {
            nextAt = scheduleNextRandomHeartbeat(ctx)
            if (!force) return
        }
        if (!force && now < nextAt) return

        val nextScheduled = scheduleNextRandomHeartbeat(ctx)
        val nextMins = maxOf(30L, (nextScheduled - now) / 60_000L)
        val notifOn = isNotificationListenerEnabledStatic(ctx)
        val accOn = isAccessibilityEnabledStatic(ctx)

        val statusSummary = "裝置 ${Build.MODEL} IG Guard 運作中 (通知讀取: ${if (notifOn) "ON ✅" else "OFF ❌"} | 畫面保護: ${if (accOn) "ON ✅" else "選用"}) • 下次隨機回報約 ${nextMins} 分鐘後"
        val reason = if (notifOn) "heartbeat_ok" else "tamper_attempt"

        sendAlertFromChild(
            ctx,
            reason = reason,
            sender = "💓 子女手機定時回報",
            text = statusSummary,
            bypassDedupe = true
        )
    }

    fun recordChildHeartbeatOnParent(ctx: Context, summary: String, sentAt: Long) {
        val now = System.currentTimeMillis()
        if (sentAt <= 0 || sentAt > now + 120_000L) return
        val timestamp = minOf(sentAt, now)
        if (timestamp <= prefs(ctx).getLong("parent_last_child_hb_ms", 0L)) return
        prefs(ctx).edit().putLong("parent_last_child_hb_ms", timestamp)
            .putString("parent_last_child_hb_text", "${nowDateTimeStr()} — $summary")
            .putBoolean("parent_hb_missing_alerted", false).apply()
    }

    fun getLastChildHeartbeatInfo(ctx: Context): String {
        val txt = prefs(ctx).getString("parent_last_child_hb_text", null)
        return txt ?: "等待子女手機首次回報 (每 30–95 分鐘隨機發送一次心跳)"
    }

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

    private fun parentPasswordVerifier(ctx: Context): String = synchronized(pollLock) {
        prefs(ctx).getString("parent_share_verifier", null)?.takeIf { FamilySecurity.isVerifier(it) }
            ?: FamilySecurity.passwordVerifier(getPin(ctx)).also { prefs(ctx).edit().putString("parent_share_verifier", it).apply() }
    }

    private fun parentIdentity(ctx: Context): FamilySecurity.SigningIdentity {
        check(isParentMode(ctx))
        val privateKey = prefs(ctx).getString("parent_signing_private", "") ?: ""
        val publicKey = prefs(ctx).getString("parent_signing_public", "") ?: ""
        if (privateKey.isNotEmpty() && FamilySecurity.validPublicKey(publicKey)) return FamilySecurity.SigningIdentity(privateKey, publicKey)
        return FamilySecurity.newSigningIdentity().also {
            prefs(ctx).edit().putString("parent_signing_private", it.privateKey).putString("parent_signing_public", it.publicKey).apply()
        }
    }

    fun relayPublicKey(ctx:Context):String = synchronized(pollLock) {
        val key=if(isParentMode(ctx))parentIdentity(ctx).publicKey else prefs(ctx).getString("parent_command_public_key","").orEmpty()
        require(FamilySecurity.validPublicKey(key)){"Pairing needs a pinned parent key"};key
    }
    fun relayAuthorization(ctx:Context,token:String):String = synchronized(pollLock) {
        val scope=getChannelTopicForToken(token)
        if(isParentMode(ctx))FamilyRelay.parentAuthorization(parentIdentity(ctx),scope,token)
        else {check(isChildMode(ctx));FamilyRelay.childAuthorization(relayPublicKey(ctx),scope,token)}
    }
    private fun relayRouteTokens(ctx:Context):List<String> = synchronized(pollLock) {
        val current=getOrCreateFamilyToken(ctx)
        val pending=try{JSONObject(prefs(ctx).getString("pending_rotation_json","{}").orEmpty())}catch(_:Exception){JSONObject()}
        val old=pending.optString("old_token")
        if(isParentMode(ctx) && current==pending.optString("new_token") && FamilySecurity.validToken(old))listOf(current,old).distinct() else listOf(current)
    }

    fun isStrongPasswordValid(password: String): Boolean {
        val clean = password.trim()
        if (clean.length < 8) return false
        val hasLetter = clean.any { it.isLetter() }
        val hasDigit = clean.any { it.isDigit() }
        return hasLetter && hasDigit
    }

    fun generateRandomStrongPassword(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$"
        val rnd = SecureRandom()
        return buildString {
            append("Hk")
            repeat(6) { append(chars[rnd.nextInt(chars.length)]) }
            append(10 + rnd.nextInt(90))
        }
    }

    fun checkPasswordLockoutRemainingSec(ctx: Context): Long {
        val until = prefs(ctx).getLong("pw_lockout_until_ms", 0L)
        return maxOf(0L, (until - System.currentTimeMillis()) / 1000L)
    }

    fun recordPasswordAttempt(ctx: Context, success: Boolean) {
        if (success) {
            prefs(ctx).edit().putInt("pw_failed_count", 0).putLong("pw_lockout_until_ms", 0L).apply()
            return
        }
        val count = prefs(ctx).getInt("pw_failed_count", 0) + 1
        if (count >= 5) {
            val lockUntil = System.currentTimeMillis() + 10 * 60_000L
            prefs(ctx).edit().putInt("pw_failed_count", 0).putLong("pw_lockout_until_ms", lockUntil).apply()
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

    fun isTemporarilyUnlocked(ctx: Context): Boolean {
        val persistedUntil = prefs(ctx).getLong("unlock_until_ms", 0L)
        val effectiveUntil = maxOf(pauseTamperUntilMs, persistedUntil)
        pauseTamperUntilMs = effectiveUntil
        return System.currentTimeMillis() < effectiveUntil
    }

    fun getUnlockRemainingSeconds(ctx: Context): Long {
        val persistedUntil = prefs(ctx).getLong("unlock_until_ms", 0L)
        val effectiveUntil = maxOf(pauseTamperUntilMs, persistedUntil)
        return maxOf(0L, (effectiveUntil - System.currentTimeMillis()) / 1000L)
    }

    fun grantTemporaryUnlock(ctx: Context, durationMs: Long) {
        val until = System.currentTimeMillis() + durationMs
        pauseTamperUntilMs = until
        prefs(ctx).edit().putLong("unlock_until_ms", until).apply()
    }

    fun generate15MinUnlockCode(ctx: Context): String {
        check(isParentMode(ctx))
        return FamilySecurity.randomUnlockCode()
    }

    fun verify15MinUnlockCode(ctx: Context, inputCode: String): Boolean {
        val clean = inputCode.trim().uppercase(Locale.US)
        val code = prefs(ctx).getString("synced_15m_code", "") ?: ""
        return FamilySecurity.validCode(clean) && System.currentTimeMillis() < prefs(ctx).getLong("synced_15m_exp", 0L) && constantTimeEquals(clean, code)
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

    // ================= 4. REMOTE KEY ROTATION & REMOTE CHILD UNLOCK/RESET =================
    fun rotateKeyAndNotifyChild(ctx: Context, resetChildToUnconfigured: Boolean, onComplete: ((Boolean) -> Unit)? = null): String = synchronized(pollLock) {
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
        if (!resetChildToUnconfigured) check(prefs(ctx).edit().putString("pending_rotation_json",
            JSONObject().put("old_token", oldToken).put("new_token", newToken).put("command",command).toString()).commit())
        postEncryptedToChannel(ctx, getChannelTopicForToken(oldToken), oldToken, command.toString(),onComplete={ success, _ ->
            val accepted = synchronized(pollLock) {
                if(resetChildToUnconfigured)success && captureRelaySession(ctx)==session
                else isParentMode(ctx) && getOrCreateFamilyToken(ctx)==newToken
            }
            // Retain a pending transition until a child report arrives on the new channel.
            addCheckpointLog(ctx, "CP3-KEY", if (accepted) "Published parent command; waiting for child confirmation" else "Publish failed or session changed; original family retained")
            onComplete?.invoke(accepted)
            ctx.sendBroadcast(Intent(ACTION_UI_REFRESH).setPackage(ctx.packageName))
        })
        newToken
    }

    @Synchronized
    fun addCheckpointLog(ctx: Context, step: String, detail: String) {
        val entry = "[${nowTimeStr()}] $step: $detail"
        val raw = prefs(ctx).getString("checkpoint_logs", "") ?: ""
        val lines = if (raw.isEmpty()) emptyList() else raw.split("\n")
        val updated = (listOf(entry) + lines).take(40).joinToString("\n")
        prefs(ctx).edit().putString("checkpoint_logs", updated).apply()
    }

    fun getCheckpointLogs(ctx: Context): String {
        return prefs(ctx).getString("checkpoint_logs", "No checkpoint events recorded yet.") ?: ""
    }

    fun clearCheckpointLogs(ctx: Context) {
        prefs(ctx).edit().putString("checkpoint_logs", "").apply()
        ctx.sendBroadcast(Intent(ACTION_UI_REFRESH).setPackage(ctx.packageName))
    }

    fun getRole(ctx: Context): String = prefs(ctx).getString("role", "unconfigured") ?: "unconfigured"
    fun setRole(ctx: Context, role: String) {
        require(role in setOf("child", "parent", "unconfigured"))
        synchronized(pollLock) {
            if (getRole(ctx) != role) {relayGeneration++;CloudRelay.disconnect()}
            prefs(ctx).edit().putString("role", role).apply()
        }
        syncChildComponents(ctx)
    }

    fun syncChildComponents(ctx: Context) {
        if (isChildMode(ctx)) {
            prefs(ctx).edit().remove("parent_signing_private").remove("parent_signing_public").apply()
            val legacyPassword = prefs(ctx).getString("parent_master_password", null)
            if (!legacyPassword.isNullOrBlank()) storeChildVerifier(ctx, FamilySecurity.passwordVerifier(legacyPassword))
        }
        val state = if (isChildMode(ctx)) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
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

    fun refreshMonitoringDisclosure(ctx: Context) {
        val manager = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (!isChildMode(ctx)) { manager.cancel(7001); return }
        manager.createNotificationChannel(NotificationChannel("child_disclosure", "子女模式監察提示", NotificationManager.IMPORTANCE_LOW))
        if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getActivity(ctx, 7001, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val active = isNotificationListenerEnabledStatic(ctx) || isAccessibilityEnabledStatic(ctx)
        val notification = Notification.Builder(ctx, "child_disclosure")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle(if (active) "IG Guard · 子女監察模式" else "IG Guard · 子女模式等待設定")
            .setContentText(if(fullCaptureEnabled(ctx))"IG 雙向擷取包含傳入、傳出及信任對象。點按查看範圍及狀態。" else "IG 警報會加密傳送給已配對的家長。點按查看權限及狀態。")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).build()
        try { manager.notify(7001, notification) } catch (_: SecurityException) { /* Permission changed while posting. */ }
    }
    fun isChildMode(ctx: Context): Boolean = getRole(ctx) == "child"
    fun isParentMode(ctx: Context): Boolean = getRole(ctx) == "parent"

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
        recoverEvidenceTransition(ctx)
        val existing = prefs(ctx).getString("family_uuid_token", null)
        if (!existing.isNullOrBlank()) return@synchronized existing
        val generated = UUID.randomUUID().toString()
        prefs(ctx).edit().putString("family_uuid_token", generated).apply()
        generated
    }

    fun setFamilyToken(ctx: Context, token: String, preserveFamilyHistory: Boolean = false) = synchronized(pollLock) {
        recoverEvidenceTransition(ctx)
        val clean = token.trim().lowercase(Locale.US)
        if (clean.isNotEmpty()) {
            val old = prefs(ctx).getString("family_uuid_token", "") ?: ""
            val editor = prefs(ctx).edit()
            if (old != clean) {
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
                        "capture_policy_id","capture_local_ack_id","capture_child_ack_id","capture_sequence",
                        "parent_master_password", "parent_share_verifier", "parent_password_verifier", "parent_command_public_key", "trusted_csv", "flagged_offenders_csv",
                        "child_self_handle", "pw_failed_count", "pw_lockout_until_ms", "sent_setup_ok", "next_heartbeat_at_ms", "checkpoint_logs")) editor.remove(key)
                    pauseTamperUntilMs = 0L
                    synchronized(this) { recentAlerts.clear(); recentChildSentTexts.clear(); seenNotifMsgTimestamps.clear() }
                    editor.remove("weekly_stats_json").remove("parent_alerts_json").remove("seen_msg_ids")
                        .remove("parent_last_child_hb_ms").remove("parent_last_child_hb_text").remove("parent_hb_missing_alerted")
                }
            }
            // Token and family-scoped history change in the same state transaction.
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

    fun getSafetyEmojis(ctx: Context): String {
        val token = getOrCreateFamilyToken(ctx)
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

    fun buildPairingInviteText(ctx: Context): String {
        val token = getOrCreateFamilyToken(ctx)
        val verifier = FamilySecurity.encode(parentPasswordVerifier(ctx))
        val publicKey = parentIdentity(ctx).publicKey
        return "🛡️ 【IG Guard 家庭專屬加密配對邀請】\n核對圖案 (Safety Emojis): ${getSafetyEmojis(ctx)}\n安全代碼：${getSafetyCode(ctx)}\n\n" +
            "請先將兩部手機更新至最新版本，再在子女手機開啟邀請並確認配對。請妥善保管邀請。\n" +
            "igguard://pair?version=43&token=$token&pw=$verifier&parent=$publicKey\n\n[IGGUARD-PAIR-V43:$token:$verifier:$publicKey]"
    }

    fun parsePairingString(raw: String): FamilySecurity.Invite? {
        return try {
        val text = raw.trim()
        if (text.length > 16_384) null
        else {
            val v43 = Regex("IGGUARD-PAIR-V43:([a-zA-Z0-9-]{8,64}):([a-zA-Z0-9_=-]+):([a-zA-Z0-9_=-]+)").find(text)
            val v42 = PAIR_V42_REGEX.find(text)
            val legacy = PAIR_LEGACY_REGEX.find(text)
            val link = Regex("igguard://pair\\?[^\\s]+", RegexOption.IGNORE_CASE).find(text)?.value
            val uri = link?.let { Uri.parse(it) }
            val token: String; val credential: String; val publicKey: String
            when {
                v43 != null -> { token = v43.groupValues[1]; credential = FamilySecurity.decode(v43.groupValues[2]); publicKey = v43.groupValues[3] }
                v42 != null -> { token = v42.groupValues[1]; credential = FamilySecurity.decode(v42.groupValues[2]); publicKey = "" }
                legacy != null -> { token = legacy.groupValues[1]; credential = legacy.groupValues[2]; publicKey = "" }
                uri != null && uri.scheme.equals("igguard", true) && uri.host.equals("pair", true) -> {
                    token = uri.getQueryParameter("token") ?: ""
                    credential = uri.getQueryParameter("pw")?.let { FamilySecurity.decode(it) } ?: uri.getQueryParameter("pin") ?: ""
                    publicKey = uri.getQueryParameter("parent") ?: ""
                    if (uri.getQueryParameter("version") == "43" && publicKey.isEmpty()) return null
                }
                else -> return null
            }
            if (!FamilySecurity.validToken(token)) null
            else if (publicKey.isNotEmpty()) {
                if (FamilySecurity.validPublicKey(publicKey) && FamilySecurity.isVerifier(credential)) FamilySecurity.Invite(token, credential, publicKey) else null
            } else if (isStrongPasswordValid(credential) && !FamilySecurity.isVerifier(credential))
                FamilySecurity.Invite(token, FamilySecurity.passwordVerifier(credential), "")
            else null
        }
        } catch (_: Exception) { null }
    }

    fun applyPairing(ctx: Context, invite: FamilySecurity.Invite): Boolean = synchronized(pollLock) {
        if (!FamilySecurity.validToken(invite.token) || !FamilySecurity.isVerifier(invite.passwordVerifier) ||
            (invite.parentPublicKey.isNotEmpty() && !FamilySecurity.validPublicKey(invite.parentPublicKey)) ||
            (isChildMode(ctx) && !isTemporarilyUnlocked(ctx))) return@synchronized false
        setFamilyToken(ctx, invite.token)
        // Pairing is a fresh authorization, even if the same family invite is reused.
        grantTemporaryUnlock(ctx, 0)
        storeChildVerifier(ctx, invite.passwordVerifier)
        prefs(ctx).edit().putString("parent_command_public_key", invite.parentPublicKey)
            .remove("capture_policy_id").remove("capture_local_ack_id").remove("capture_child_ack_id")
            .remove("synced_15m_code").remove("synced_15m_exp").putBoolean("sent_setup_ok", false).apply()
        setRole(ctx, "child"); scheduleNextRandomHeartbeat(ctx)
        true
    }

    fun hasSecureParentCommands(ctx: Context): Boolean = FamilySecurity.validPublicKey(prefs(ctx).getString("parent_command_public_key", "") ?: "")

    fun pendingCapturePolicy(ctx:Context):String = prefs(ctx).getString("capture_policy_id","") ?: ""
    fun fullCaptureEnabled(ctx:Context):Boolean = isChildMode(ctx) && hasSecureParentCommands(ctx) &&
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
                if(!fullCaptureEnabled(ctx) || expectedSession!=null && captureRelaySession(ctx)!=expectedSession)return emptyList()
                val token=getOrCreateFamilyToken(ctx);val scope=getChannelTopicForToken(token)
                require(observations.size<=128){"Capture batch exceeds 128 observations"}
                val rows=observations;val now=System.currentTimeMillis()
                val hashes=rows.map { c->AlertFrames.digest(JSONArray().put(c.direction).put(c.author).put(c.text).put(c.messageTime).toString().toByteArray(Charsets.UTF_8)) }
                FamilyEvidence(ctx).use { db ->
                    val sql=db.writableDatabase;sql.beginTransaction()
                    try {
                        val window=if(windowKey!=null)db.captureWindowState(scope,windowKey)else CaptureWindow()
                        val previous=window.tokens
                        val overlap=ConversationCapture.overlap(previous,hashes)
                        val orderedScreen=windowKey!=null && rows.isNotEmpty() && rows.all{it.source=="visible_dm" && it.messageTime==0L && ConversationCapture.key(it.account,it.conversation,it.conversationKind)==windowKey}
                        val orderBase=prefs(ctx).getLong("capture_sequence",0)
                        val anchor=if(orderedScreen && overlap.third>0 && window.anchor>0)window.anchor else now
                        val start=if(orderedScreen && overlap.third>0 && window.anchor>0)window.start+overlap.first-overlap.second else orderBase+1
                        val indices=if(windowKey!=null)ConversationCapture.newIndices(previous,hashes)else rows.indices.toList()
                        // A sequence gap after a failed SQLite transaction is harmless; timestamps remain unchanged.
                        if(indices.isNotEmpty())check(prefs(ctx).edit().putLong("capture_sequence",orderBase+indices.size).commit())
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
                            val stamp=r.getLong("ts_ms")
                            r.put("time",SimpleDateFormat("MM-dd HH:mm:ss",Locale.US).format(Date(stamp)))
                            r.put("receipt_requested",true)
                            val frames=AlertFrames.split(r).mapIndexed { n,f -> "${r.getString("event_id")}:$n" to encryptPayload(token,f.toString()) }
                            if(db.enqueueCaptured(scope,r.getString("event_id"),frames,true,ConversationCapture.priority(r)))accepted.add(r)
                        }
                        if(windowKey!=null && hashes.isNotEmpty())db.setCaptureWindow(scope,windowKey,hashes,if(orderedScreen)anchor else 0,if(orderedScreen)start else 0)
                        sql.setTransactionSuccessful()
                    } finally {sql.endTransaction()}
                }
                accepted.filter{it.optBoolean("flag_partner")}.forEach{markSenderAsFlaggedOffender(ctx,it.optString("conversation_label"))}
            }
        } catch(e:Exception) {
            accepted.clear();cpLastHttpStatus="未能保留對話 · ${e.javaClass.simpleName} · 請保持畫面並重試"
            return emptyList()
        }
        if(accepted.isNotEmpty()){retryPendingAlerts(ctx);ctx.sendBroadcast(Intent(ACTION_UI_REFRESH).setPackage(ctx.packageName))}
        return accepted
    }

    // ================= 5. AUTO-DETECTED CHILD IG HANDLE + TRUSTED FRIENDS + FLAGGED OFFENDERS =================
    fun notificationRecipient(title:String):String {
        if(!title.startsWith("[") || !title.contains("]"))return ""
        val candidate=title.substringAfter("[").substringBefore("]").trim().removePrefix("@").lowercase(Locale.US)
        return candidate.takeIf{VALID_IG_HANDLE_REGEX.matches(it) && it !in RESERVED_UI_HEADERS}.orEmpty()
    }
    fun getChildSelfHandle(ctx: Context): String {
        // Older builds inferred the child's identity from a conversation title (the peer).
        // Discard that unverified cache once; a recipient prefix or own-profile screen can restore it.
        if (prefs(ctx).contains("child_self_handle") && prefs(ctx).getInt("child_self_handle_source_version", 0) < 2) {
            prefs(ctx).edit().remove("child_self_handle").apply()
        }
        return (prefs(ctx).getString("child_self_handle", "") ?: "").trim().lowercase(Locale.US).removePrefix("@")
    }

    fun setChildSelfHandle(ctx: Context, handle: String) {
        val clean = handle.trim().lowercase(Locale.US).removePrefix("@")
        if (clean.isEmpty() || clean in RESERVED_UI_HEADERS || !VALID_IG_HANDLE_REGEX.matches(clean)) return
        prefs(ctx).edit().putString("child_self_handle", clean).putInt("child_self_handle_source_version", 2).apply()
        val cleanedTrusted = getTrustedHandles(ctx).filter { it != clean }
        prefs(ctx).edit().putString("trusted_csv", cleanedTrusted.joinToString(",")).apply()
        val cleanedOffenders = getFlaggedOffenders(ctx).filter { it != clean }
        prefs(ctx).edit().putString("flagged_offenders_csv", cleanedOffenders.joinToString(",")).apply()
    }

    // Issue #1 Fix: Child phone automatically detects its own IG handle and syncs it to Parent phone!
    fun updateAutoDetectedChildHandle(ctx: Context, candidateHandle: String) {
        val clean = candidateHandle.trim().lowercase(Locale.US).removePrefix("@")
        if (clean.isEmpty() || clean in RESERVED_UI_HEADERS || !VALID_IG_HANDLE_REGEX.matches(clean)) return
        val existing = getChildSelfHandle(ctx)
        if (clean == existing) return
        setChildSelfHandle(ctx, clean)
        addCheckpointLog(ctx, "CP1-ID", "Auto-detected Child IG ID: @$clean")
        if (isChildMode(ctx)) {
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
        return norm == selfHandle || norm.contains("(@$selfHandle)")
    }

    private fun storedHandles(ctx: Context, key: String): Set<String> {
        val self = getChildSelfHandle(ctx)
        return (prefs(ctx).getString(key, "") ?: "").split(",")
            .map { it.trim().lowercase(Locale.US).removePrefix("@") }.filter { it.isNotEmpty() && it != self }.toSet()
    }

    fun getFlaggedOffenders(ctx: Context): Set<String> = storedHandles(ctx, "flagged_offenders_csv")

    fun markSenderAsFlaggedOffender(ctx: Context, sender: String) {
        val clean = sender.trim().lowercase(Locale.US).removePrefix("@")
        if (clean.isEmpty() || clean in RESERVED_UI_HEADERS || clean == "instagram_dm" || isSelfSenderLabel(ctx, clean)) return
        val next = getFlaggedOffenders(ctx).toMutableSet()
        if (next.add(clean)) {
            prefs(ctx).edit().putString("flagged_offenders_csv", next.joinToString(",")).apply()
        }
    }

    fun isFlaggedOffender(ctx: Context, rawSender: String): Boolean {
        val offenders = getFlaggedOffenders(ctx)
        if (offenders.isEmpty()) return false
        val norm = rawSender.trim().lowercase(Locale.US).removePrefix("@")
        if (norm in offenders) return true
        for (off in offenders) {
            if (norm.contains("(@$off)") || norm.startsWith("$off ") || norm.endsWith(" $off")) return true
        }
        return false
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
        if (isSelfSenderLabel(ctx, senderTitle)) return null
        if (wasRecentlyTypedByChild(text)) return null

        if (SEXUAL_PATTERN.containsMatchIn(text) || SEXUAL_PATTERN.containsMatchIn(senderTitle)) {
            markSenderAsFlaggedOffender(ctx, senderTitle)
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
        onComplete: ((Boolean, String) -> Unit)? = null
    ) {
        if(expectedSession!=null && captureRelaySession(ctx)!=expectedSession) {
            completeAlertCallback(onComplete,false,"家庭配對已變更，原擷取已停止");return
        }
        val token=expectedSession?.second ?: getOrCreateFamilyToken(ctx)
        val topic=getChannelTopicForToken(token)
        val cleanSender = sender.trim().ifEmpty { "unknown" }
        val cleanText = text
        val dedupeKey = "$topic|${reason.lowercase(Locale.US)}|${cleanSender.lowercase(Locale.US).removePrefix("@")}|${cleanText.lowercase(Locale.US)}"
        val windowMs = if (fromNotification) NOTIF_BURST_DEDUPE_MS else SCREEN_DEDUPE_MS

        if (!shouldSendAlert(
                dedupeKey,
                windowMs = windowMs,
                bypassDedupe = bypassDedupe || reason == "test_alert" || reason == "heartbeat_ok"
            )
        ) {
            addCheckpointLog(ctx, "CP2-DEDUPE", "Skipped duplicate alert")
            onComplete?.invoke(false, "Duplicate suppressed")
            return
        }

        addCheckpointLog(ctx, "CP2-MATCH", "[$reason] Captured alert; message content omitted from diagnostics")

        val plain = JSONObject().apply {
            put("type", "alert")
            put("event_id", UUID.randomUUID().toString())
            put("capture_source", if (fromNotification) "notification" else "app_or_visible_screen")
            put("reason", reason)
            put("sender", cleanSender)
            put("text", cleanText)
            put("ts_ms", System.currentTimeMillis())
            put("time", nowDateTimeStr())
            put("trusted_csv", getTrustedHandles(ctx).joinToString(","))
            put("child_self_handle", getChildSelfHandle(ctx))
        }.toString()

        postEncryptedToChannel(ctx, topic, token, plain, onComplete,expectedSession)
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

    private fun postEncryptedToChannel(
        ctx: Context,
        topic: String,
        token: String,
        plainJson: String,
        onComplete: ((Boolean, String) -> Unit)?,
        expectedSession: Triple<String,String,Int>? = null
    ) {
        val payload = JSONObject(plainJson)
        if (payload.optString("type") == "alert") {
            try {
                val receiptRequested = hasSecureParentCommands(ctx)
                payload.put("receipt_requested",receiptRequested)
                val frames = AlertFrames.split(payload)
                val queuedSession = expectedSession ?: captureRelaySession(ctx)
                val wires = frames.mapIndexed { index,frame -> "${payload.getString("event_id")}:$index" to encryptPayload(token,frame.toString()) }
                val queued=synchronized(pollLock) {
                    if (!isChildMode(ctx) || getOrCreateFamilyToken(ctx) != token || captureRelaySession(ctx)!=queuedSession) false
                    else { FamilyEvidence(ctx).use { it.enqueueBatch(topic,wires,waitForReceipt=receiptRequested) };true }
                }
                if(!queued){completeAlertCallback(onComplete,false,"家庭配對已變更，原傳送已停止");return}
                if(onComplete!=null)deliveryCallbacks[topic to payload.getString("event_id")] = DeliveryCallback(queuedSession) { success,message ->
                    val same=captureRelaySession(ctx)==queuedSession
                    completeAlertCallback(onComplete,same && success,if(same)message else "家庭配對已變更，原傳送已停止")
                }
                cpLastHttpStatus = "已加密儲存，等待中繼站接收"
                retryPendingAlerts(ctx)
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
            check(if(parentCommand)isParentMode(ctx) else isChildMode(ctx) && type in setOf("capture_ack","sync_child_handle"))
            val commandId=payload.optString("command_id").ifEmpty{UUID.randomUUID().toString()}
            require(UUID.fromString(commandId).toString()==commandId)
            payload.put("scope",topic).put("command_id",commandId)
            val envelope=if(parentCommand)FamilySecurity.signCommand(payload,parentIdentity(ctx).privateKey) else payload
            val eventId="control:$commandId"
            val wrapped=JSONObject().put("type","alert").put("event_id",eventId).put("relay_control",true).put("envelope",envelope)
            val frames=AlertFrames.split(wrapped).mapIndexed{index,frame->"$eventId:$index" to encryptPayload(token,frame.toString())}
            synchronized(pollLock) {
                check(captureRelaySession(ctx)==origin)
                FamilyEvidence(ctx).use{it.enqueueBatch(topic,frames,role=origin.first,kind=if(type=="alert_receipt")"receipt" else "control")}
                if(onComplete!=null)deliveryCallbacks[topic to eventId]=DeliveryCallback(origin){success,message->
                    val same=captureRelaySession(ctx)==origin
                    completeAlertCallback(onComplete,same && success,if(same)message else "家庭配對已變更，原傳送已停止")
                }
            }
            cpLastHttpStatus="指令已加密保留 · 等待中繼確認"
            retryPendingAlerts(ctx)
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
    }
    fun retryPendingAlerts(ctx: Context, force: Boolean = false) {
        if (getRole(ctx)=="unconfigured") return
        try{prepareRelayOutbox(ctx)}catch(_:Exception){
            cpLastHttpStatus="加密佇列需要復原 · 已保留原有片段，請保留 App 資料"
            val origin=captureRelaySession(ctx)
            for((key,callback) in deliveryCallbacks.entries.toList())if(callback.origin==origin && deliveryCallbacks.remove(key,callback))completeAlertCallback(callback.complete,false,cpLastHttpStatus)
            return
        }
        if (force) FamilyEvidence(ctx).use { it.retryNow(getChannelTopic(ctx)) }
        if (!deliveryInFlight.compareAndSet(false, true)) return
        val app=ctx.applicationContext
        val session=captureRelaySession(app)
        thread {
            try {
                deliveryLoop@ while(captureRelaySession(app)==session) {
                    val next=relayRouteTokens(app).firstNotNullOfOrNull{token->
                        val scope=getChannelTopicForToken(token)
                        val batch=FamilyEvidence(app).use{it.due(scope,System.currentTimeMillis(),session.first)}
                        if(batch.isEmpty())null else Triple(token,scope,batch)
                    } ?: break
                    for(frame in next.third) {
                        if(captureRelaySession(app)!=session)break@deliveryLoop
                        val result=CloudRelay.publishBlocking(app,next.first,frame.id,frame.wire,frame.kind)
                        synchronized(pollLock) {
                        if(captureRelaySession(app)!=session)return@thread
                        if(result.accepted)recordRelayAcceptance(app,next.second,frame)
                        else FamilyEvidence(app).use{it.failed(next.second,frame.id,System.currentTimeMillis(),session.first,if(result.contextFull)"data" else null)}
                        }
                        if(captureRelaySession(app)==session) {
                            // A signed receipt may have overtaken the publish acknowledgment.
                            val stillPending=FamilyEvidence(app).use{it.pendingEvent(next.second,frame.id)}
                            if(!result.accepted || !frame.waitForReceipt || stillPending)cpLastHttpStatus=result.message
                            addCheckpointLog(app,if(result.accepted)"CP3-RELAY" else "CP3-ERR",if(result.accepted)"Encrypted packet accepted; parent receipt is separate" else result.message)
                        }
                        if(!result.accepted){if(result.contextFull)continue@deliveryLoop else break@deliveryLoop}
                    }
                }
            } finally {
                deliveryInFlight.set(false)
                finishDeliveryCallbacks(app)
                app.sendBroadcast(Intent(ACTION_UI_REFRESH).setPackage(app.packageName))
                // A new capture arriving as the worker exits must not be stranded.
                if(captureRelaySession(app)==session && relayRouteTokens(app).any{token->FamilyEvidence(app).use{it.due(getChannelTopicForToken(token),System.currentTimeMillis(),session.first).isNotEmpty()}})retryPendingAlerts(app)
            }
        }
    }

    fun pollChannelOnce(ctx: Context, since: String = "all", onNewAlertForParent: ((JSONObject) -> Unit)? = null) {
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
        if (role != getRole(ctx) || role == "unconfigured") return RelayConsumption.RETRY
        if (expectedGeneration != null && expectedGeneration != relayGeneration) return RelayConsumption.RETRY
        if (expectedToken != null && expectedToken != getOrCreateFamilyToken(ctx)) return RelayConsumption.RETRY
        val outer = try { JSONObject(line) } catch (_: Exception) { return RelayConsumption.REJECTED }
        if (outer.optString("event") != "message") return RelayConsumption.REJECTED
        val msgId = outer.optString("id")
        if (msgId.isEmpty()) return RelayConsumption.REJECTED

        val existingSet = prefs(ctx).getStringSet("seen_msg_ids", emptySet()) ?: emptySet()
        if (existingSet.contains(msgId)) return RelayConsumption.CONSUMED

        val innerStr = outer.optString("message")
        val token = getOrCreateFamilyToken(ctx)
        val decoded = decryptPayload(token, innerStr) ?: return RelayConsumption.REJECTED
        val decrypted = if (decoded.optString("type") == "alert_fragment") {
            if (role != "parent" && !(role=="child" && decoded.optString("event_id").startsWith("control:"))) return RelayConsumption.REJECTED
            val completed = FamilyEvidence(ctx).use { it.fragment(getChannelTopicForToken(token), decoded) }
            if (completed == null) {
                if(FamilyEvidence(ctx).use { it.hasSeen(getChannelTopicForToken(token),decoded.optString("event_id")) })
                    sendDeliveryReceipt(ctx,decoded.optString("event_id"),decoded.optBoolean("receipt_requested"))
                val durable=FamilyEvidence(ctx).use { db -> db.hasSeen(getChannelTopicForToken(token),decoded.optString("event_id")) || db.hasFragment(getChannelTopicForToken(token),decoded.optString("event_id"),decoded.optInt("part",-1),decoded.optString("digest")) }
                return if(durable)RelayConsumption.CONSUMED else RelayConsumption.REJECTED
            }
            if(decoded.optBoolean("receipt_requested"))completed.put("receipt_requested",true) else completed
        } else decoded
        val controlId=if(decrypted.optBoolean("relay_control"))decrypted.optString("event_id") else ""
        val envelope=if(controlId.isNotEmpty())decrypted.optJSONObject("envelope") ?: return RelayConsumption.REJECTED else decrypted
        val inner = if (envelope.has("payload")) {
            if (role != "child") return RelayConsumption.REJECTED
            FamilySecurity.readCommand(envelope, prefs(ctx).getString("parent_command_public_key", "") ?: "", getChannelTopicForToken(token), System.currentTimeMillis()) ?: return RelayConsumption.REJECTED
        } else {
            if (envelope.optString("type") in FamilySecurity.controlTypes) return RelayConsumption.REJECTED
            if(controlId.isNotEmpty() && (role!="parent" || envelope.optString("type") !in setOf("capture_ack","sync_child_handle")))return RelayConsumption.REJECTED
            envelope
        }
        if(controlId.isNotEmpty() && controlId!="control:"+inner.optString("command_id"))return RelayConsumption.REJECTED
        val msgType = inner.optString("type")
        val msgTs = inner.optLong("ts_ms", 0L)

        var committedControlState:String?=null
        if (msgType in FamilySecurity.controlTypes) {
            val state = try { JSONObject(prefs(ctx).getString("control_state_json", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
            val ids = state.optJSONObject("ids") ?: JSONObject().also { state.put("ids", it) }
            val id = inner.getString("command_id")
            if (ids.has(id)) {
                if(controlId.isNotEmpty())FamilyEvidence(ctx).use{it.controlConsumed(getChannelTopicForToken(token),controlId)}
                return RelayConsumption.CONSUMED
            }
            if (msgType != "alert_receipt" && msgTs < state.optLong(msgType)) return RelayConsumption.REJECTED
            for (key in ids.keys().asSequence().toList()) if (ids.optLong(key) < System.currentTimeMillis() - 24 * 60 * 60_000L) ids.remove(key)
            ids.put(id, msgTs); state.put(msgType, msgTs)
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
            "alert_receipt" -> {
                FamilyEvidence(ctx).use{it.acknowledgeEvent(getChannelTopic(ctx),inner.getString("event_id"))}
                cpLastHttpStatus = if(inner.optString("outcome")=="discarded") "家長已刪除此記錄 · 停止重試" else "家長手機已確認接收 · 不代表已閱讀"
                ctx.sendBroadcast(Intent(ACTION_UI_REFRESH).setPackage(ctx.packageName))
            }
            "sync_trusted" -> {
                val csv = inner.optString("trusted_csv")
                val list = csv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                setTrustedHandles(ctx, list)
                addCheckpointLog(ctx, "CP4-SYNC", "Updated trusted list (${list.size} handles)")
                ctx.sendBroadcast(Intent(ACTION_UI_REFRESH).setPackage(ctx.packageName))
            }
            "sync_child_handle" -> {
                if (role == "parent") {
                    val detected = inner.optString("child_self_handle")
                    if (detected.isNotEmpty()) {
                        setChildSelfHandle(ctx, detected)
                        addCheckpointLog(ctx, "CP4-ID", "Auto-synced Child IG ID from child phone: @$detected")
                        ctx.sendBroadcast(Intent(ACTION_UI_REFRESH).setPackage(ctx.packageName))
                    }
                }
            }
            "unlock_code" -> {
                val code = inner.optString("code")
                val exp = inner.optLong("exp", 0L)
                if (code.isNotEmpty() && exp > System.currentTimeMillis()) {
                    prefs(ctx).edit()
                        .putString("synced_15m_code", code)
                        .putLong("synced_15m_exp", exp)
                        .apply()
                    addCheckpointLog(ctx, "CP4-SYNC", "Received encrypted 15-min unlock code")
                }
            }
            "key_rotated" -> {
                val newToken = inner.optString("new_token")
                val newPin = inner.optString("new_pin")
                if (newToken.isNotEmpty()) {
                    setFamilyToken(ctx, newToken, preserveFamilyHistory = true)
                    storeChildVerifier(ctx, newPin)
                    addCheckpointLog(ctx, "CP4-KEY", "Auto-updated to new Parent Family Key ✅")
                    ctx.sendBroadcast(Intent(ACTION_UI_REFRESH).setPackage(ctx.packageName))
                }
            }
            "remote_reset_child" -> {
                if (role == "child") {
                    grantTemporaryUnlock(ctx, 10 * 60_000L)
                    setRole(ctx, "unconfigured")
                    addCheckpointLog(ctx, "CP4-RESET", "Parent remotely unlocked & reset Child Phone")
                    ctx.sendBroadcast(Intent(ACTION_UI_REFRESH).setPackage(ctx.packageName))
                }
            }
            "alert", "captured_message" -> {
                if (role == "parent") {
                    if(msgType=="captured_message") {
                        if(!ConversationCapture.valid(inner) || pendingCapturePolicy(ctx).isEmpty() || inner.optString("policy_id")!=pendingCapturePolicy(ctx))return RelayConsumption.REJECTED
                        prefs(ctx).edit().putString("capture_child_ack_id",inner.getString("policy_id")).apply()
                    }
                    val childSelf = inner.optString("child_self_handle")
                    if (childSelf.isNotEmpty()) {
                        setChildSelfHandle(ctx, childSelf)
                    }
                    val inserted = recordParentAlert(ctx, inner, "relay:$msgId")
                    sendDeliveryReceipt(ctx,inner.optString("event_id"),inner.optBoolean("receipt_requested"))
                    if (!inserted) return RelayConsumption.CONSUMED
                    val reason = inner.optString("reason")
                    if (reason in setOf("heartbeat_ok", "pairing_verified", "tamper_attempt", "test_alert")) {
                        recordChildHeartbeatOnParent(ctx, inner.optString("text"), msgTs)
                    }
                    val pending = try { JSONObject(prefs(ctx).getString("pending_rotation_json", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
                    if (token == pending.optString("new_token")) prefs(ctx).edit().remove("pending_rotation_json").apply()
                    addCheckpointLog(ctx, "CP4-RECV", "Decrypted $reason from ${inner.optString("sender")}")
                    if(ConversationCapture.actionable(inner) || reason=="test_alert")onNewAlertForParent?.invoke(inner)
                    ctx.sendBroadcast(Intent(ACTION_UI_REFRESH).setPackage(ctx.packageName))
                }
            }
        }
        val updatedIds = HashSet<String>(existingSet)
        updatedIds.add(msgId)
        val persistedSet = if (updatedIds.size > 300) {
            HashSet(updatedIds.toList().takeLast(300))
        } else {
            updatedIds
        }
        val editor=prefs(ctx).edit().putStringSet("seen_msg_ids", persistedSet)
        if(committedControlState!=null)editor.putString("control_state_json",committedControlState)
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

    private fun sendDeliveryReceipt(ctx: Context,eventId: String,requested: Boolean) {
        if(!requested || !isParentMode(ctx) || !eventId.matches(Regex("[A-Za-z0-9:_-]{1,160}")))return
        val token=getOrCreateFamilyToken(ctx);val topic=getChannelTopicForToken(token)
        if(!shouldSendAlert("receipt:$topic:$eventId",30_000L))return
        val outcome = FamilyEvidence(ctx).use{if(it.find(topic,eventId)==null) "discarded" else "stored"}
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
    fun prepareParentArchive(ctx: Context) = synchronized(pollLock) { if(isParentMode(ctx))migrateEvidence(ctx) }
    fun hasUnmigratedEvidence(ctx: Context): Boolean = prefs(ctx).contains("parent_alerts_json")
    fun recordParentAlert(ctx: Context, alertObj: JSONObject, eventId: String): Boolean = synchronized(pollLock) {
        if (!isParentMode(ctx)) return false
        migrateEvidence(ctx)
        val id = alertObj.optString("event_id").takeIf { it.matches(Regex("[A-Za-z0-9:_-]{1,160}")) }
            ?: if (eventId.startsWith("relay:")) legacyEvidenceId(alertObj) else eventId
        val scope = getChannelTopic(ctx)
        FamilyEvidence(ctx).use { db ->
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
        migrateEvidence(ctx)
        FamilyEvidence(ctx).use { it.messages(getChannelTopic(ctx), EvidenceQuery(filter = "any"), limit = 60) }
    }
    fun clearParentAlerts(ctx: Context): Unit = synchronized(pollLock) {
        if (!isParentMode(ctx)) return
        val statistics=loadParentStatistics(ctx)
        check(prefs(ctx).edit().putString("weekly_stats_json",statistics.state.toString()).commit())
        migrateEvidence(ctx)
        FamilyEvidence(ctx).use { it.deleteArchive(getChannelTopic(ctx)) }
        prefs(ctx).edit().remove("parent_alerts_json").commit()
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
        GuardEngine.addCheckpointLog(this, "CP1-NOTIF", "IG NotificationListener CONNECTED ✅")
        heartbeatHandler.removeCallbacks(periodicCheckRunnable)
        heartbeatHandler.postDelayed(periodicCheckRunnable, 15_000L)
    }

    override fun onListenerDisconnected() {
        GuardEngine.cpNotifConnected = false
        heartbeatHandler.removeCallbacks(periodicCheckRunnable)
        GuardEngine.addCheckpointLog(this, "CP1-NOTIF", "NotificationListener DISCONNECTED ❌")
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
        val observationSession=GuardEngine.captureRelaySession(this)
        GuardEngine.checkAndSendChildHeartbeatIfDue(this)

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
            val recipient = GuardEngine.notificationRecipient(rawTitle)
            GuardEngine.updateAutoDetectedChildHandle(this, recipient)
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

                // Issue #2 Fix: If the latest message in the notification bundle was sent by the child (or matches selfDisplayName / "You" / "你"),
                // STOP IMMEDIATELY so we never misattribute the child's own sent message to the conversation partner!
                if (bundleSender.isNotEmpty()) {
                    if (GuardEngine.isSelfSenderLabel(this, bundleSender) ||
                        (selfDisplayName.isNotEmpty() && bundleSender.equals(selfDisplayName, ignoreCase = true))
                    ) {
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
            if (GuardEngine.isSelfSenderLabel(this, candidateHeader)) {
                return
            }
            resolvedSender = candidateHeader
        }

        if (resolvedMessage.isEmpty() || resolvedSender.isEmpty()) return
        if (GuardEngine.isSelfSenderLabel(this, resolvedSender) || GuardEngine.wasRecentlyTypedByChild(resolvedMessage)) {
            return
        }

        val tsSig = "$resolvedSender|$resolvedTime|$resolvedMessage"
        if (!GuardEngine.isNewNotificationMessageTimestamp(tsSig)) {
            return
        }

        if(GuardEngine.captureRelaySession(this)!=observationSession)return
        val reason = GuardEngine.evaluateMessage(this, resolvedSender, resolvedMessage)
        if (reason == null) {
            GuardEngine.addCheckpointLog(this, "CP2-TRUSTED", "Ignored trusted/self/status: @$resolvedSender")
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

        if ((reason == "sexual_or_grooming_content" || GuardEngine.isFlaggedOffender(this, resolvedSender)) &&
            !GuardEngine.isTemporarilyUnlocked(this)
        ) {
            try {
                cancelNotification(sbn.key)
            } catch (_: Exception) {
            }
        }
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
        fun own(label:String)=GuardEngine.isSelfSenderLabel(this,label) || self.isNotEmpty() && label.equals(self,true) || account.isNotEmpty() && label.removePrefix("@").equals(account,true)
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
        val accepted=observed.chunked(128).flatMap{GuardEngine.captureMessages(this,it,expectedSession=observationSession)}
        val flaggedIncoming=observed.any{it.direction=="incoming" && it.author.isNotBlank() && GuardEngine.isFlaggedOffender(this,it.author)}
        if(GuardEngine.captureRelaySession(this)==observationSession && !GuardEngine.isTemporarilyUnlocked(this) && (flaggedIncoming || accepted.any{it.optBoolean("flag_partner")}))try{cancelNotification(sbn.key)}catch(_:Exception){}
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

// 2. CHILD ONLY: Strict Context-Gated Instagram DM Incoming Bubble Reader + Child Comment-Reply Reader
// Issue #3 & #4 Fix: Only hooks com.instagram.android (no OS Settings hooking) and NEVER scans middle-of-screen feed/reel texts!
class IgAccessibilityService : AccessibilityService() {

    private var lastScanMs = 0L
    private var lastRemoteSyncMs = 0L
    private var lastCapturedBottomMsg = ""
    private var pendingScan:Runnable? = null

    private val debounceHandler = Handler(Looper.getMainLooper())
    private var pendingReplyText: String = ""
    private var pendingReplyContextAuthor: String = ""
    private var pendingReplySession:Triple<String,String,Int>? = null
    private var lastSentCommentReply: String = ""

    private val flushPendingCommentReplyRunnable = Runnable {
        val origin=pendingReplySession
        if (!GuardEngine.isChildMode(this@IgAccessibilityService) || origin==null || GuardEngine.captureRelaySession(this)!=origin) {
            pendingReplyText = ""
            pendingReplySession=null
            return@Runnable
        }
        val textToSend = pendingReplyText.trim()
        val targetAuthor = pendingReplyContextAuthor.ifEmpty { "IG_Post_Or_Story" }
        if (textToSend.length >= 2 && textToSend != lastSentCommentReply) {
            lastSentCommentReply = textToSend
            val isSexual = GuardEngine.SEXUAL_PATTERN.containsMatchIn(textToSend)
            if(GuardEngine.fullCaptureEnabled(this))GuardEngine.captureMessages(this,listOf(CapturedText(
                targetAuthor,textToSend,"outgoing","子女","","comment_story_draft")),expectedSession=origin)
            else GuardEngine.sendAlertFromChild(
                this@IgAccessibilityService,
                reason = if (isSexual) "sexual_or_grooming_content" else "stranger_message",
                sender = "子女留言/限時動態回覆 (@$targetAuthor)",
                text = textToSend,
                expectedSession = origin
            )
            if (GuardEngine.captureRelaySession(this)==origin && isSexual && !GuardEngine.isTemporarilyUnlocked(this@IgAccessibilityService)) {
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
        }
        pendingReplyText = ""
        pendingReplySession=null
    }

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
        if (!GuardEngine.isChildMode(this)) {
            disableSelf()
            return
        }
        GuardEngine.cpAccConnected = true
        GuardEngine.refreshMonitoringDisclosure(this)
        GuardEngine.scheduleNextRandomHeartbeat(this)
        GuardEngine.addCheckpointLog(this, "CP1-ACC", "IG DM & Comment-Reply Guard Connected ✅")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!GuardEngine.isChildMode(this)) return
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
        GuardEngine.cpAccConnected = true
        GuardEngine.cpLastAccTime = GuardEngine.nowTimeStr()

        GuardEngine.checkAndSendChildHeartbeatIfDue(this)
        if (now - lastRemoteSyncMs > 45_000L) {
            lastRemoteSyncMs = now
            thread { GuardEngine.pollChannelOnce(applicationContext, since = "all") }
        }

        val root = rootInActiveWindow ?: run{GuardEngine.cpLastCaptureStatus="Instagram 尚未提供可讀取的前景視窗";return}
        if(root.packageName?.toString()?.let{it!=GuardEngine.INSTAGRAM_PKG}==true){GuardEngine.cpLastCaptureStatus="目前前景視窗不是 Instagram";return}
        val dmUnlocked = GuardEngine.isTemporarilyUnlocked(this)

        val metrics = resources.displayMetrics
        val rootBounds=Rect();root.getBoundsInScreen(rootBounds)
        val screenHeight = rootBounds.height().takeIf{it>0}?:metrics.heightPixels.coerceAtLeast(1)
        val screenWidth = rootBounds.width().takeIf{it>0}?:metrics.widthPixels.coerceAtLeast(1)

        val items = ArrayList<ScreenNodeItem>(90)
        collectScreenNodes(root,items,0,rootBounds,intArrayOf(0))
        if (items.isEmpty()){GuardEngine.cpLastCaptureStatus="Instagram 未提供可讀取的文字節點";return}

        val joined = items.joinToString(" ") { it.text }

        // A. Prevent muting notifications inside Instagram settings
        val isExplicitMuteScreen = joined.contains("Pause all", ignoreCase = true) ||
            joined.contains("暫停所有") ||
            joined.contains("Mute messages", ignoreCase = true) ||
            joined.contains("Mute notifications", ignoreCase = true) ||
            joined.contains("將訊息設為靜音") ||
            joined.contains("將通知設為靜音") ||
            joined.contains("Quiet mode", ignoreCase = true) ||
            joined.contains("靜音模式")

        // A named or semantic DM composer remains valid when a keyboard raises it above mid-screen.
        val dmComposerNode = items.firstOrNull { item ->
            item.top>=0 && (item.isEditable && (ConversationCapture.composerLabel(item.text) || ConversationCapture.composerLabel(item.hintText)) ||
                ConversationCapture.composerRole(item.viewId) || !fullCapture && item.isEditable && item.top>=screenHeight*.72)
        }

        if (!dmUnlocked && dmComposerNode==null && isExplicitMuteScreen) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            GuardEngine.sendAlertFromChild(
                this,
                "tamper_attempt",
                "IG Mute Settings",
                "Child tried to mute Instagram DM notifications.",
                expectedSession=observationSession
            )
            return
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

        // ================= CONTEXT 1: STORY / POST COMMENTS REPLY (ONLY READ CHILD'S TYPED REPLY!) =================
        if (isCommentsOrStoryReplySheet) {
            val activeReplyEdit = items.firstOrNull { item ->
                isReplyComposer(item) && item.text.isNotEmpty() &&
                    item.text != item.hintText &&
                    !GuardEngine.isNonMessageUiString(item.text)
            }
            if (activeReplyEdit != null) {
                val typed = activeReplyEdit.text.trim()
                if (typed.length >= 2) {
                    pendingReplyText = typed
                    pendingReplySession=observationSession
                    pendingReplyContextAuthor = topHeaderSender.ifEmpty { "IG_Post" }
                    debounceHandler.removeCallbacks(flushPendingCommentReplyRunnable)
                    debounceHandler.postDelayed(flushPendingCommentReplyRunnable, 1_400L)
                }
            } else if (pendingReplyText.isNotEmpty()) {
                debounceHandler.removeCallbacks(flushPendingCommentReplyRunnable)
                flushPendingCommentReplyRunnable.run()
            }
            // Issue #4 Fix: Never read public comments or middle-of-screen text when viewing comments/stories!
            return
        }

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
            if(GuardEngine.captureRelaySession(this)!=observationSession)return
            if(!dmUnlocked && (GuardEngine.isFlaggedOffender(this,topHeaderSender) || accepted.any{it.optString("direction")=="incoming" && it.optString("reason")=="sexual_or_grooming_content"}))performGlobalAction(GLOBAL_ACTION_BACK)
            return
        }

        GuardEngine.cpLastCaptureStatus="僅傳入警報模式 · 雙向範圍尚未確認"

        // Record anything the child is currently typing in the DM composer EditText so we never alert on the child's own sent messages!
        items.filter { it.isEditable && it.text.isNotEmpty() && it.text != it.hintText }.forEach { editNode ->
            GuardEngine.recordChildTypedDmText(editNode.text)
        }

        // Block child from staying in a DM session with any flagged sexual/grooming offender
        if (!dmUnlocked && GuardEngine.isFlaggedOffender(this, topHeaderSender)) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            return
        }

        // Read incoming left-aligned bubbles between the confirmed header and composer, including above a keyboard:
        // - Left edge must start near the left margin (<= 24% width)
        // - Right edge must NOT reach the right-aligned outgoing margin (<= 82% width)
        // - Horizontal midpoint must sit on the left half of the screen (centerX <= 50% width)
        // - Must NOT match any text recently typed/sent by the child
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
                !GuardEngine.isSelfSenderLabel(this, item.text) &&
                !GuardEngine.wasRecentlyTypedByChild(item.text) &&
                !item.text.trim().removePrefix("@").equals(topHeaderSender, ignoreCase = true)
        }.sortedBy { it.top }

        if (incomingLeftBubbles.isEmpty()) return

        // Check for sexual/grooming keywords on incoming left-aligned DM bubbles
        val sexualHit = incomingLeftBubbles.firstOrNull { GuardEngine.SEXUAL_PATTERN.containsMatchIn(it.text) }
        if (sexualHit != null) {
            GuardEngine.markSenderAsFlaggedOffender(this, topHeaderSender)
            GuardEngine.sendAlertFromChild(
                this,
                reason = "sexual_or_grooming_content",
                sender = topHeaderSender,
                text = sexualHit.text,
                expectedSession=observationSession
            )
            if (!dmUnlocked) {
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
            return
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
                if (reason == "sexual_or_grooming_content" && !dmUnlocked) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun collectScreenNodes(node: AccessibilityNodeInfo?, out: MutableList<ScreenNodeItem>, depth: Int,
        origin:Rect,visited:IntArray,editableAncestor:Boolean=false,direction:String="unknown",bubble:Rect?=null) {
        if (node == null || visited[0]++ >= 2048 || depth > 64) return
        try {
            if (!node.isVisibleToUser) return
            val text=node.text?.toString().orEmpty()
            val rawText = text.takeIf{it.isNotBlank()}?:node.contentDescription?.toString().orEmpty()
            val rawHint = if (Build.VERSION.SDK_INT >= 26) (node.hintText?.toString()?.trim() ?: "") else ""
            val rect=Rect();node.getBoundsInScreen(rect);rect.offset(-origin.left,-origin.top)
            val id=node.viewIdResourceName.orEmpty()
            val ownDirection=ConversationCapture.semanticDirection(id).takeUnless{it=="unknown"}?:direction
            val ownBubble=if(ConversationCapture.messageRole(id) && node.childCount>0 && rect.width()>0 && rect.width()<origin.width())rect else bubble
            val editable=editableAncestor || node.isEditable || ConversationCapture.composerRole(id)
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
        pendingReplyText = ""
        pendingReplySession=null
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
            .setContentTitle("IG Guard 家長加密守護連線中 🔒")
            .setContentText("核對圖案: ${GuardEngine.getSafetyEmojis(this)} • AES-256-GCM 即時守護")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
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
                put("sender", "🚨 子女手機離線/解除安裝警告")
                put("text", "已超過 100 分鐘未收到子女手機嘅定時心跳回報！子女可能已熄機、斷網或解除安裝 IG Guard。")
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
        val tag=if(alert.optString("type")=="captured_message" && conversation.isNotEmpty())"$scope:conversation:$conversation" else "$scope:$eventId"
        val previous=nm.activeNotifications.firstOrNull{it.tag==tag}?.notification
        val priority=ConversationCapture.priority(alert)
        val elevate=previous==null || priority>previous.extras.getInt("ig_review_priority",0)
        val open = Intent(this, MainActivity::class.java).setAction("app.family.igguard.OPEN_EVIDENCE")
            .setData(Uri.Builder().scheme("igguard").authority("evidence").appendPath(scope).appendPath(eventId).build())
        val openIntent = PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val generic = when(reason) {
            "test_alert" -> "已收到子女手機的加密測試。可在裝置與傳送查看記錄。"
            "sexual_or_grooming_content" -> "偵測到需留意的關鍵字。開啟查看記錄及尋求協助建議。"
            else -> "有新的家庭提醒，請開啟 App 查看。"
        }
        val publicNotice = Notification.Builder(this, "ig_guard_alerts")
            .setContentTitle("IG Guard 家庭提醒").setContentText("解鎖後開啟 App 查看。")
            .setSmallIcon(android.R.drawable.stat_sys_warning).build()
        val notif = Notification.Builder(this, "ig_guard_alerts")
            .setContentTitle(if(reason == "sexual_or_grooming_content") "有一則需要留意的對話" else "IG Guard 家庭提醒")
            .setContentText(generic).setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(publicNotice)
            .setOnlyAlertOnce(!elevate).addExtras(Bundle().apply{putInt("ig_review_priority",maxOf(priority,previous?.extras?.getInt("ig_review_priority",0)?:0))})
            .setSmallIcon(android.R.drawable.stat_sys_warning).setContentIntent(openIntent).setAutoCancel(true).build()
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

// Native family UI. Monitoring, cryptography and pairing remain in the existing engine.
class MainActivity : Activity() {
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
            uiHandler.postDelayed(this, 5_000L)
        }
    }

    private data class ThemeColors(
        val bgCanvas: String, val bgCard: String, val bgHero: String, val bgInput: String,
        val bgChip: String, val borderSubtle: String, val textPrimary: String,
        val textSecondary: String, val textMuted: String, val accentSeafoam: String,
        val accentPowderSky: String, val btnPrimaryBg: String, val btnPrimaryText: String,
        val btnSecondaryBg: String, val btnSecondaryText: String, val warmHoney: String,
        val warmCoral: String, val warningButtonBg: String, val warningButtonText: String
    )
    private fun currentTheme(): ThemeColors {
        return if (GuardEngine.isBrightTheme(this)) {
            ThemeColors(
                bgCanvas = "#F6F8F5", bgCard = "#FFFFFF", bgHero = "#EAF1EB", bgInput = "#FFFFFF",
                bgChip = "#EAF1EB", borderSubtle = "#DEE7DF", textPrimary = "#202B24",
                textSecondary = "#5D6C61", textMuted = "#5D6C61", accentSeafoam = "#315644",
                accentPowderSky = "#61786A", btnPrimaryBg = "#315644", btnPrimaryText = "#FFFFFF",
                btnSecondaryBg = "#FFFFFF", btnSecondaryText = "#315644", warmHoney = "#865917",
                warmCoral = "#A83142", warningButtonBg = "#865917", warningButtonText = "#FFFFFF"
            )
        } else {
            ThemeColors(
                bgCanvas = "#151E18", bgCard = "#1D2820", bgHero = "#2B3F2E", bgInput = "#1D2820",
                bgChip = "#2B3F2E", borderSubtle = "#354436", textPrimary = "#ECF2EC",
                textSecondary = "#AFBEB0", textMuted = "#AFBEB0", accentSeafoam = "#CEE1CE",
                accentPowderSky = "#AFBEB0", btnPrimaryBg = "#CEE1CE", btnPrimaryText = "#1D3422",
                btnSecondaryBg = "#1D2820", btnSecondaryText = "#CEE1CE", warmHoney = "#E8CC96",
                warmCoral = "#FFB2BF", warningButtonBg = "#E8CC96", warningButtonText = "#1D3422"
            )
        }
    }
    private fun font(bold: Boolean = false): Typeface = fonts.getOrPut(bold) {
        val id = resources.getIdentifier(if (bold) "manrope_semibold" else "manrope_regular", "font", packageName)
        if (id != 0) resources.getFont(id) else Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
    }
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
    private fun focusSurface(fill:String,radius:Int,border:String?=null):StateListDrawable {
        val t=currentTheme()
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused),background(fill,radius).apply {
                setStroke(dp(2),color(if(fill==t.btnPrimaryBg)t.btnPrimaryText else t.accentSeafoam))
            })
            addState(intArrayOf(),background(fill,radius,border))
        }
    }
    private fun ripple(fill: String, radius: Int = 10, border: String? = null): RippleDrawable {
        val t=currentTheme();val feedback=color(if(fill==t.btnPrimaryBg)t.btnPrimaryText else t.accentSeafoam)
        return RippleDrawable(ColorStateList.valueOf((feedback and 0x00ffffff) or 0x24000000), focusSurface(fill,radius,border), background("#FFFFFF", radius))
    }
    private fun View.describeAsControl(choice:Boolean=false) {
        accessibilityDelegate=object:View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host:View,info:AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host,info)
                info.className=if(choice)"android.widget.RadioButton" else "android.widget.Button"
                if(choice){info.isCheckable=true;info.isChecked=host.isSelected}
            }
        }
    }
    private fun applyTheme() {
        val bright = GuardEngine.isBrightTheme(this)
        val id = resources.getIdentifier(if (bright) "GuardThemeLight" else "GuardThemeDark", "style", packageName)
        if (id != 0) setTheme(id)
        val t = currentTheme()
        window.statusBarColor = color(t.bgCanvas)
        window.navigationBarColor = color(t.bgCard)
        window.decorView.systemUiVisibility = if (bright) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
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
                if (changed || (currentFocus !is EditText && section in setOf("overview", "alerts"))) renderUi()
                maybeShowSafetyAdvice()
                maybeShowCaptureNotice()
            },200)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)
        GuardEngine.syncChildComponents(this)
        section = savedInstanceState?.getString("section") ?: "role"
        renderedRole = savedInstanceState?.getString("rendered_role") ?: GuardEngine.getRole(this)
        selectedEntryRole = savedInstanceState?.getString("entry_role") ?: ""
        chartMode = savedInstanceState?.getString("chart_mode") ?: "trend"
        alertFilter = savedInstanceState?.getString("alert_filter") ?: "all"
        displayed15MinCode = savedInstanceState?.getString("unlock_code") ?: ""
        if (savedInstanceState?.getString("draft_family") == GuardEngine.getChannelTopic(this) && renderedRole == GuardEngine.getRole(this)) {
            evidenceSearch = savedInstanceState.getString("evidence_search") ?: ""
            evidenceSender = savedInstanceState.getString("evidence_sender")
            evidenceConversationKey=savedInstanceState.getString("evidence_conversation_key")
            evidenceSection=savedInstanceState.getString("evidence_section")?:"conversations"
            evidenceReview=savedInstanceState.getString("evidence_review")?:"new"
            evidenceDirection=savedInstanceState.getString("evidence_direction")?:"all"
            evidencePage = savedInstanceState.getInt("evidence_page").coerceAtLeast(0)
            if(evidenceSender!=null && evidencePage>0) {
                try {
                    val raw=savedInstanceState.getString("evidence_cursors")?:error("Missing page boundaries")
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
            evidenceDays = savedInstanceState.getInt("evidence_days")
            evidenceSaved = savedInstanceState.getBoolean("evidence_saved")
            savedInstanceState.getBundle("drafts")?.let { b -> for (key in b.keySet()) inputDrafts[key] = b.getString(key) ?: "" }
        }
        screenShell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
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
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("section", section); outState.putString("rendered_role", renderedRole)
        outState.putString("entry_role", selectedEntryRole); outState.putString("chart_mode", chartMode)
        outState.putString("alert_filter", alertFilter); outState.putString("unlock_code", displayed15MinCode)
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
        super.onSaveInstanceState(outState)
    }
    @Deprecated("Activity back navigation")
    override fun onBackPressed() {
        when {
            section == "alerts" && evidenceSender != null -> { evidenceSender = null;evidenceConversationKey=null;evidenceDirection="all"; evidencePage = 0; renderUi(animate = true) }
            section.endsWith("_setup") -> navigateTo("role")
            section == "role" -> super.onBackPressed()
            section != "overview" -> navigateTo("overview")
            else -> navigateTo("role")
        }
    }
    override fun onNewIntent(intent: Intent?) { super.onNewIntent(intent); handleDeepLinkIntent(intent) }
    private fun handleDeepLinkIntent(incoming: Intent?) {
        if (incoming?.action == "app.family.igguard.OPEN_EVIDENCE") { handleEvidenceNotification(incoming); return }
        val data = incoming?.data ?: return
        setIntent(Intent(incoming).apply { this.data = null })
        if (incoming.action != Intent.ACTION_VIEW || !data.scheme.equals("igguard", true) || !data.host.equals("pair", true)) return
        val parsed = GuardEngine.parsePairingString(data.toString()) ?: return
        if (GuardEngine.isChildMode(this) && !GuardEngine.isTemporarilyUnlocked(this)) {
            GuardEngine.sendAlertFromChild(this, "tamper_attempt", "Deep-Link Guard", "Blocked unauthorized attempt to replace locked family pairing.")
            Toast.makeText(this, "子女保護鎖定中，請先由家長解鎖。", Toast.LENGTH_LONG).show(); return
        }
        confirmPairing(parsed)
    }
    private fun confirmPairing(parsed: FamilySecurity.Invite, onAccepted: () -> Unit = {}) {
        dismissUiDialogs()
        val t = currentTheme()
        showSheet("確認家庭配對") { _ ->
            addView(label("只接受由家長傳送的邀請。確認後，此手機會切換為子女模式，並更換目前的家庭配對。請在兩部手機核對安全圖案。", 14f, t.textSecondary, bottomPad = 18))
            if (parsed.parentPublicKey.isNotEmpty()) {
                addView(label(FamilySecurity.safetyCode(parsed.token, parsed.passwordVerifier, parsed.parentPublicKey), 19f, t.textPrimary, true, bottomPad = 12))
                addView(label("請由家長在家長手機查看並核對相同安全代碼，再確認配對。", 14f, t.textSecondary, bottomPad = 18))
            }
            addView(actionButton("確認配對並切換為子女手機", t.btnPrimaryBg, t.btnPrimaryText) { if (acceptPairing(parsed)) onAccepted() })
        }
    }
    private fun acceptPairing(invite: FamilySecurity.Invite): Boolean {
        if (!GuardEngine.applyPairing(this, invite)) {
            Toast.makeText(this, "保護已上鎖，請先由家長解鎖再配對。", Toast.LENGTH_LONG).show(); return false
        }
        dismissUiDialogs(); section = "permissions"
        if (activityVisible) syncRoleServices()
        renderUi(animate = true)
        Toast.makeText(this, "已完成家庭配對。", Toast.LENGTH_LONG).show()
        return true
    }
    override fun onResume() {
        super.onResume(); activityVisible = true
        val filter = IntentFilter(GuardEngine.ACTION_UI_REFRESH)
        // The flag protects Android 13+; the signature permission protects earlier releases.
        registerReceiver(refreshReceiver, filter, "$packageName.INTERNAL", null, Context.RECEIVER_NOT_EXPORTED)
        if (GuardEngine.isChildMode(this) && GuardEngine.isNotificationListenerEnabledStatic(this)) {
            try { NotificationListenerService.requestRebind(ComponentName(this, IgNotificationListener::class.java)) } catch (_: Exception) {}
        }
        syncRoleServices(); checkInitialPairingConfirmation(); renderUi()
        if (GuardEngine.isChildMode(this)) {
            GuardEngine.retryPendingAlerts(this,force=true)
            if (GuardEngine.isNotificationListenerEnabledStatic(this) && !GuardEngine.cpNotifConnected)
                try { NotificationListenerService.requestRebind(ComponentName(this, IgNotificationListener::class.java)) } catch (_: Exception) {}
        }
        openPendingEvidence(); maybeShowSafetyAdvice();maybeShowCaptureNotice()
        uiHandler.removeCallbacks(statusTicker); uiHandler.post(statusTicker)
    }
    override fun onPause() {
        super.onPause(); activityVisible = false; uiHandler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(refreshReceiver) } catch (_: Exception) {}
    }
    override fun onDestroy() { activityVisible = false; uiHandler.removeCallbacksAndMessages(null); dismissUiDialogs(); super.onDestroy() }

    private fun navigateTo(destination: String) {
        val role = GuardEngine.getRole(this)
        val permitted = if (role == "parent") setOf("role", "overview", "alerts", "family", "settings")
            else if (role == "child") setOf("role", "overview", "permissions", "unlock", "settings")
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
        val proceed = {
            if (current != "unconfigured") { GuardEngine.setRole(this, "unconfigured"); syncRoleServices() }
            section = if (selectedEntryRole == "parent") "parent_setup" else "child_setup"
            renderUi(animate = true)
        }
        if (current == "child") verifyParentForRoleChange(proceed) else proceed()
    }
    private fun verifyParentForRoleChange(onSuccess: () -> Unit) {
        val t = currentTheme()
        showSheet("切換身份，需要家長確認。") { dialog ->
            addView(label("為保護既有家庭設定，請輸入目前的家長主密碼。", 14f, t.textSecondary, bottomPad = 18))
            val password = inputBox(t, "家長主密碼", "", isPassword = true, remember = false).apply { tag = "role_password" }
            addView(password)
            val error = label("", 13f, t.warmCoral); addView(error)
            addView(actionButton("驗證並繼續", t.btnPrimaryBg, t.btnPrimaryText) {
                val remaining = GuardEngine.checkPasswordLockoutRemainingSec(this@MainActivity)
                if (remaining > 0) { error.text = "輸入已鎖定，請等 $remaining 秒，或由家長手機遠端救援。"; return@actionButton }
                val ok = GuardEngine.verifyParentPassword(this@MainActivity, password.text.toString())
                GuardEngine.recordPasswordAttempt(this@MainActivity, ok)
                if (ok) { inputDrafts.remove("家長主密碼"); dialog.dismiss(); onSuccess() }
                else { error.text = "家長主密碼不正確。"; password.error = "請重新輸入" }
            })
        }
    }
    private fun renderUi(animate: Boolean = false) {
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
        val focus = currentFocus as? EditText
        val focusHint = focus?.hint?.toString(); val selection = focus?.selectionStart ?: -1
        val sameSection = lastRenderedSection == section
        val savedY = if (sameSection) scrollView.scrollY else 0
        val old = if (contentFrame.childCount > 0) contentFrame.getChildAt(contentFrame.childCount - 1) else null
        old?.animate()?.cancel()
        contentFrame.removeAllViews()
        if (animate && old != null && !sameSection && ValueAnimator.areAnimatorsEnabled()) contentFrame.addView(old)
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
        when {
            section == "role" -> renderRoleChoice(t)
            section == "parent_setup" && role == "unconfigured" -> renderParentSetup(t)
            section == "child_setup" && role == "unconfigured" -> renderChildSetup(t)
            section == "settings" -> renderSettings(t)
            role == "parent" -> when(section) { "alerts" -> renderParentAlerts(t); "family" -> renderParentFamily(t); else -> renderParentOverview(t) }
            role == "child" -> when(section) { "permissions" -> renderChildPermissions(t); "unlock" -> renderChildUnlock(t); else -> renderChildOverview(t) }
            else -> renderRoleChoice(t)
        }
        if (sameSection && focusHint != null) {
            descendants(rootContainer).filterIsInstance<EditText>().firstOrNull { it.hint?.toString() == focusHint }?.let {
                it.requestFocus(); it.setSelection(selection.coerceIn(0, it.text.length))
            }
        }
        scrollView.post { scrollView.scrollTo(0, savedY) }
        if (animate && old != null && !sameSection && ValueAnimator.areAnimatorsEnabled()) {
            val incoming = scrollView
            incoming.alpha = 0f; incoming.translationX = dp(12).toFloat()
            incoming.animate().alpha(1f).translationX(0f).setDuration(240).setInterpolator(DecelerateInterpolator()).start()
            old.animate().alpha(0f).translationX(-dp(8).toFloat()).setDuration(180).withEndAction {
                if (old.parent === contentFrame) contentFrame.removeView(old)
            }.start()
            old.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            for (view in descendants(old)) { view.isEnabled = false; view.isClickable = false; view.isFocusable = false }
        }
        lastRenderedSection = section; updateStatus()
    }
    private fun buildHeader(t: ThemeColors, role: String) {
        fixedHeader.removeAllViews(); fixedHeader.setBackgroundColor(color(t.bgCanvas))
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(60) }
        if (section.endsWith("_setup")) row.addView(iconButton("arrow-left", "返回身份選擇") { navigateTo("role") })
        else row.addView(icon("shield-check", t.accentSeafoam, 28))
        row.addView(label("IG Guard", 18f, t.textPrimary, true).apply { setPadding(dp(9), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(iconButton(if (GuardEngine.isBrightTheme(this)) "moon" else "sun", "切換淺色或深色模式") {
            GuardEngine.setBrightTheme(this, !GuardEngine.isBrightTheme(this)); renderUi()
        })
        fixedHeader.addView(row)
        statusText = label("", 12f, t.textSecondary).apply { setPadding(0, dp(5), 0, dp(12)) }
        if (section != "role" && !section.endsWith("_setup") && role != "unconfigured") {
            fixedHeader.addView(statusText); divider(fixedHeader, t)
        }
    }
    private fun buildBottomNavigation(t: ThemeColors, role: String) {
        bottomNav.removeAllViews()
        bottomNav.visibility = if (section == "role" || section.endsWith("_setup") || role == "unconfigured") View.GONE else View.VISIBLE
        if (bottomNav.visibility != View.VISIBLE) return
        bottomNav.setBackgroundColor(color(t.bgCard)); bottomNav.setPadding(dp(12), dp(8), dp(12), dp(10))
        val destinations = if (role == "parent") listOf(Triple("overview", "house", "總覽"), Triple("alerts", "bell-simple", "提醒"), Triple("family", "users-three", "家庭"), Triple("settings", "gear-six", "設定"))
            else listOf(Triple("overview", "house", "總覽"), Triple("permissions", "shield", "權限"), Triple("unlock", "key", "解鎖"), Triple("settings", "gear-six", "設定"))
        for ((destination, glyph, title) in destinations) {
            val selected = section == destination
            val tab = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; minimumHeight = dp(56)
                background = ripple(if (selected) t.bgChip else t.bgCard, 8)
                isClickable = true; isFocusable = true; isSelected = selected; contentDescription = title
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                setOnClickListener { if (section != destination) navigateTo(destination) }
                tag = "nav_$destination"
                describeAsControl()
            }
            tab.addView(icon(glyph, if (selected) t.accentSeafoam else t.textMuted, 23))
            tab.addView(label(title, 11f, if (selected) t.accentSeafoam else t.textMuted, selected, topPad = 4).apply { gravity = Gravity.CENTER })
            bottomNav.addView(tab, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
        }
    }
    private fun updateStatus() {
        if (!::statusText.isInitialized) return
        val t = currentTheme()
        statusText.text = when (GuardEngine.getRole(this)) {
            "parent" -> when {
                GuardEngine.isChildHeartbeatOverdue(this) -> "子女回報逾時 · 請查看連接狀態"
                GuardEngine.hasChildHeartbeat(this) -> "已收到子女回報 · 加密守護通道"
                else -> "等待子女手機回報"
            }
            "child" -> {
                val notification = if (!GuardEngine.isNotificationListenerEnabledStatic(this)) "通知未授權" else if (GuardEngine.cpNotifConnected) "通知已連接" else "通知等待連接"
                val screen = if (!GuardEngine.isAccessibilityEnabledStatic(this)) "畫面未授權" else if (GuardEngine.cpAccConnected) "畫面已連接" else "畫面等待連接"
                val capture=if(GuardEngine.fullCaptureEnabled(this))"雙向擷取已啟用" else "只擷取傳入警報"
                "$notification · $screen · $capture" + GuardEngine.pendingDeliveryCount(this).let { if (it > 0) " · 待確認 $it" else "" } + if (GuardEngine.isTemporarilyUnlocked(this)) " · 暫時解鎖中" else ""
            }
            else -> "選擇這部手機的身份"
        }
        statusText.setTextColor(color(if (GuardEngine.isParentMode(this) && GuardEngine.isChildHeartbeatOverdue(this)) t.warmCoral else t.textSecondary))
        unlockTimerText?.text = if (GuardEngine.getUnlockRemainingSeconds(this) > 0) "暫時解鎖中 · 剩餘 ${formatSeconds(GuardEngine.getUnlockRemainingSeconds(this))}" else "保護已重新上鎖"
    }
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
                background = ripple(if (selected) t.bgChip else t.bgCard, 12, if (selected) t.accentSeafoam else t.accentPowderSky)
                isClickable = true; isFocusable = true; isSelected = selected
                contentDescription = "$title。$description"
                tag = "entry_$role"; setOnClickListener { selectEntryRole(role) }
                describeAsControl(choice=true)
            }
            card.addView(icon(if (role == "parent") "users-three" else "plant", t.accentSeafoam, 28))
            val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(8), 0) }
            words.addView(label(title, 17f, t.textPrimary, true, bottomPad = 6))
            words.addView(label(description, 12f, t.textSecondary))
            card.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(icon(if (selected) "check-circle" else "caret-right", t.accentSeafoam, 20))
            rootContainer.addView(card, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(12)) })
        }
        rootContainer.addView(Space(this), LinearLayout.LayoutParams(1, 0, 1f))
        rootContainer.addView(actionButton("繼續", t.btnPrimaryBg, t.btnPrimaryText) { continueEntryRole() }.apply {
            isEnabled = selectedEntryRole.isNotEmpty(); alpha = if (isEnabled) 1f else .5f; tag = "continue_role"
        })
        rootContainer.addView(label("配對後，子女手機切換身份需要家長驗證。", 12f, t.textMuted, topPad = 10).apply { gravity = Gravity.CENTER })
    }
    private fun renderParentSetup(t: ThemeColors) {
        heading("從一個安心的家開始。", "設定只由家長保管的主密碼，再邀請子女手機加入。", t)
        rootContainer.addView(label("家長主密碼", 14f, t.textPrimary, true, bottomPad = 10))
        val password = inputBox(t, "最少 8 位字元，包含字母及數字", "", true); rootContainer.addView(password)
        val error = label("", 13f, t.warmCoral); rootContainer.addView(error)
        rootContainer.addView(textAction("產生高強度密碼") {
            password.setText(GuardEngine.generateRandomStrongPassword()); error.text = ""; password.error = null
        })
        rootContainer.addView(actionButton("建立家庭", t.btnPrimaryBg, t.btnPrimaryText) {
            val candidate = password.text.toString().trim()
            if (!GuardEngine.isStrongPasswordValid(candidate)) { error.text = "請輸入最少 8 位字元，並同時包含字母及數字。"; password.error = "密碼長度或格式不正確"; return@actionButton }
            GuardEngine.setFamilyToken(this, UUID.randomUUID().toString()); GuardEngine.setPin(this, candidate); GuardEngine.setRole(this, "parent")
            section = "overview"; syncRoleServices(); renderUi(animate = true)
        })
        rootContainer.addView(label("主密碼用於保護角色切換及解鎖設定，請妥善保管。", 13f, t.textSecondary, topPad = 16))
    }
    private fun renderChildSetup(t: ThemeColors) {
        heading("讓家人，安心相連。", "請家長傳送配對邀請，再將邀請貼到下方。", t)
        val invite = inputBox(t, "貼上家長的配對邀請", "").apply { minLines = 3; gravity = Gravity.TOP }
        rootContainer.addView(invite)
        val error = label("", 13f, t.warmCoral); rootContainer.addView(error)
        fun pair(raw: String) {
            val parsed = GuardEngine.parsePairingString(raw)
            if (parsed == null) { error.text = "未找到有效邀請，請貼上家長發出的完整配對訊息。"; return }
            confirmPairing(parsed) { openDirectNotificationToggle() }
        }
        rootContainer.addView(actionButton("連結家庭", t.btnPrimaryBg, t.btnPrimaryText) { pair(invite.text.toString()) })
        rootContainer.addView(actionButton("從剪貼簿貼上並配對", t.btnSecondaryBg, t.btnSecondaryText) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            pair(cm?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: "")
        })
        rootContainer.addView(label("配對前，請與家長核對兩部手機上的完整安全代碼及 4 個安全圖案。", 13f, t.textSecondary, topPad = 18))
    }

    private fun renderParentOverview(t: ThemeColors) {
        heading("家人，安心相連。", "今天的守護資訊，都在這裡。", t)
        val connected = GuardEngine.hasChildHeartbeat(this)
        val card = makeCard(t)
        val handle = GuardEngine.getChildSelfHandle(this)
        card.addView(label(if (handle.isEmpty()) "子女手機" else "@$handle", 16f, t.textPrimary, true, bottomPad = 8))
        card.addView(label(if (connected) GuardEngine.getLastChildHeartbeatInfo(this) else "等待子女手機回報", 13f, t.textSecondary))
        if (!connected) card.addView(textAction("邀請子女配對") { showPairingInvite(t) })
        rootContainer.addView(card)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            rootContainer.addView(textAction("尚未授權系統通知 · 前往設定") { navigateTo("settings") })
        }
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
            tabs.addView(Button(this).apply {
                text = title; textSize = 13f; typeface = font(chartMode == mode); isAllCaps = false
                setTextColor(color(if (chartMode == mode) t.accentSeafoam else t.textMuted))
                background = ripple(if (chartMode == mode) t.bgChip else t.bgCard, 7, t.accentPowderSky)
                minimumHeight = dp(48); isSelected = chartMode == mode
                setOnClickListener { chartMode = mode; renderUi() }
            }, LinearLayout.LayoutParams(0, -2, 1f))
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
            val left = maxOf(dp(25).toFloat(), paint.measureText(maximum.toString()) + dp(8)); val right = width - dp(10).toFloat()
            val top = paint.textSize + dp(15); val bottom = height - paint.textSize - dp(15)
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
        rootContainer.addView(row("link", "邀請子女配對", "傳送家庭專屬配對邀請", t) { showPairingInvite(t) })
        rootContainer.addView(row("key", "15 分鐘臨時解鎖", "給需要的片刻空間，無需透露主密碼", t) {
            displayed15MinCode = GuardEngine.generate15MinUnlockCode(this)
            GuardEngine.push15MinUnlockCodeFromParent(this, displayed15MinCode)
            showSheet("給孩子，15 分鐘的空間。") { dialog ->
                addView(label("把這個臨時碼告訴子女。", 14f, t.textSecondary))
                addView(label(displayed15MinCode, 32f, t.textPrimary, true, topPad = 22, bottomPad = 22).apply { gravity = Gravity.CENTER; setTextIsSelectable(true) })
                addView(label("正在透過加密通道傳送。子女手機收到後才能使用；有效期為產生後 15 分鐘。", 13f, t.textSecondary, bottomPad = 18))
                addView(actionButton("複製解鎖碼", t.btnPrimaryBg, t.btnPrimaryText) { copyText("IGGuardUnlock", displayed15MinCode); dialog.dismiss() })
            }
        })
        sectionTitle("信任好友", t)
        val current = GuardEngine.getTrustedHandles(this)
        if (current.isEmpty()) rootContainer.addView(label("尚未加入信任好友。請確認對方身份後，再加入名單。", 14f, t.textSecondary, bottomPad = 16))
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
            val updated = handles.text.toString().split(",")
            clearTrustDraft(); GuardEngine.setTrustedHandles(this, updated); GuardEngine.syncTrustedFromParent(this)
            Toast.makeText(this, "信任名單已儲存並同步。", Toast.LENGTH_SHORT).show(); renderUi()
        })
        val self = GuardEngine.getChildSelfHandle(this)
        rootContainer.addView(label(if (self.isEmpty()) "子女 Instagram 帳號會在收到通知或開啟個人檔案後自動識別。" else "子女 Instagram 帳號：@$self", 13f, t.textSecondary, topPad = 12))
    }
    private fun showPairingInvite(t: ThemeColors) {
        showSheet("讓家人，加入這份守護。") { dialog ->
            addView(label("將家庭專屬邀請傳送到子女手機，並核對兩部手機上的安全圖案。", 14f, t.textSecondary, bottomPad = 20))
            addView(label(GuardEngine.getSafetyEmojis(this@MainActivity), 30f, t.textPrimary, bottomPad = 22).apply { gravity = Gravity.CENTER })
            addView(label("安全代碼：${GuardEngine.getSafetyCode(this@MainActivity)}", 16f, t.textPrimary, true, bottomPad = 12))
            addView(label("請在家長手機查看並與子女手機核對完整安全代碼；代碼同時驗證家長金鑰及密碼設定。", 13f, t.textSecondary, bottomPad = 18))
            addView(actionButton("傳送配對邀請", t.btnPrimaryBg, t.btnPrimaryText) {
                val intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, GuardEngine.buildPairingInviteText(this@MainActivity)) }
                startActivity(Intent.createChooser(intent, "傳送配對邀請"))
            })
            addView(actionButton("複製配對訊息", t.btnSecondaryBg, t.btnSecondaryText) { copyText("IGGuardInvite", GuardEngine.buildPairingInviteText(this@MainActivity)); dialog.dismiss() })
        }
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
            else->"方向未能判斷 · $author"
        }
    }
    private fun evidenceSource(message:JSONObject):String=when(message.optString("source_context")) {
        "notification"->if(message.optString("conversation_kind")=="group")"群組通知擷取" else "通知擷取"
        "visible_dm"->"畫面擷取 · 方向按介面資訊及版面辨識"
        "comment_story_draft"->"留言 / 限時動態草稿 · 未確認送出"
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
        if(inConversation)rootContainer.addView(textAction("返回對話列表") { evidenceSender=null;evidenceConversationKey=null;evidenceDirection="all";evidencePage=0;renderUi() })
        else {
            val tabs=LinearLayout(this)
            for((key,title) in listOf("conversations" to "對話記錄","review" to "需要跟進","device" to "裝置與傳送")) {
                tabs.addView(Button(this).apply {
                    text=title;textSize=12f;isAllCaps=false;minimumHeight=dp(48);typeface=font(evidenceSection==key)
                    setTextColor(color(if(evidenceSection==key)t.btnPrimaryText else t.textSecondary))
                    background=ripple(if(evidenceSection==key)t.btnPrimaryBg else t.bgCanvas,7)
                    isSelected=evidenceSection==key
                    setOnClickListener(sessionClick{evidenceSection=key;evidencePage=0;alertFilter="all";evidenceDirection="all";renderUi()})
                },LinearLayout.LayoutParams(0,-2,1f))
            }
            rootContainer.addView(tabs)
            rootContainer.addView(textAction(if(GuardEngine.parentFullCaptureConfirmed(this))"雙向擷取已確認 · 查看範圍" else "雙向擷取尚未確認 · 前往設定"){showFullCaptureSetup(t)})
        }
        if(inConversation)rootContainer.addView(textAction("排序與擷取說明") {
            showSheet("排序與擷取說明") { dialog ->
                addView(label("按訊息時間或重疊畫面的先後排列；無法對齊的片段保留擷取順序。通知時間與畫面順序可能無法互相對齊。\n\n只包含實際擷取內容。未顯示或未擷取的訊息無法復原，這不是完整 Instagram 對話備份。",14f,t.textSecondary,bottomPad=18))
                addView(actionButton("關閉",t.btnPrimaryBg,t.btnPrimaryText){dialog.dismiss()})
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
        results.addView(label("正在搜尋本機記錄…",13f,t.textMuted).apply{tag="evidence_loading"})
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
                            val caption=(if(contact.kind=="group")"群組 · " else "")+"${contact.account.ifBlank{"帳號未確認"}} · 最近記錄 ${evidenceTime(contact.latest)}\n${contact.count} 則記錄 · ${contact.unreviewed} 則需查看 · $category\n${shortEvidence(contact.preview)}"
                            val display=ConversationCapture.displayLabel(contact.label,contact.unidentified)
                            results.addView(row("chat-circle",display,caption,t){openConversation(contact.key,display)}.apply{
                                tag="conversation_${contact.key}";getChildAt(0).tag="evidence_contact_${contact.label.trim().removePrefix("@").lowercase(Locale.ROOT)}"
                            })
                        }
                        if(contacts.isEmpty())results.addView(label(if(query.section=="review")"沒有符合條件的跟進項目。" else "尚無符合條件的對話記錄。",15f,t.textSecondary,topPad=24,bottomPad=20))
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
                            else results.addView(row("info",alertTitle(message.optString("reason")),"${evidenceClock(message)}\n${shortEvidence(message.optString("text"))}",t){showAlertDetails(message,t)})
                        }
                        if(shown.isEmpty())results.addView(label("沒有符合條件的訊息。",15f,t.textSecondary,topPad=24,bottomPad=20))
                    }
                    if(incomplete>0)results.addView(label("另有 $incomplete 則訊息尚未完整接收，內容可能缺少。",13f,t.warmHoney,topPad=16))
                    val paging=LinearLayout(this)
                    if(evidencePage>0)paging.addView(textAction(if(inConversation)"較新訊息" else "上一頁"){evidencePage--;scrollView.scrollTo(0,0);renderUi()},LinearLayout.LayoutParams(0,-2,1f))
                    paging.addView(label("第 ${evidencePage+1} 頁",13f,t.textMuted).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(0,dp(48),1f))
                    if(more)paging.addView(textAction(if(inConversation)"較早訊息" else "下一頁"){evidencePage++;scrollView.scrollTo(0,0);renderUi()},LinearLayout.LayoutParams(0,-2,1f))
                    results.addView(paging)
                }
            } catch(_:Exception) {
                uiHandler.post{if(!isDestroyed && rootContainer===container && GuardEngine.captureRelaySession(this)==session){results.removeAllViews();results.addView(label("未能讀取本機記錄，請保留資料並重試。",14f,t.warmCoral))}}
            }
        }
        rootContainer.addView(textAction("重新同步可用記錄"){thread{GuardEngine.pollChannelOnce(applicationContext,since="all")};Toast.makeText(this,"正在同步；未擷取或中繼站已過期的內容無法復原。",Toast.LENGTH_LONG).show()})
        if(!inConversation)rootContainer.addView(textAction("刪除此家庭的全部證據"){
            alert().setTitle("永久刪除證據？").setMessage("將刪除此家庭本機記錄及收藏。圖表統計保留；操作無法復原。")
                .setNegativeButton("取消",null).setPositiveButton("永久刪除"){_,_->GuardEngine.clearParentAlerts(this);evidencePage=0;renderUi()}.show()
        })
    }
    private fun conversationBubble(message:JSONObject,t:ThemeColors):View {
        val outgoing=message.optString("direction")=="outgoing"
        val holder=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;setPadding(0,dp(5),0,dp(5))}
        val bubble=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;minimumHeight=dp(64);setPadding(dp(14),dp(13),dp(14),dp(13))
            background=ripple(if(outgoing)t.bgHero else t.bgCard,12,t.borderSubtle)
            isClickable=true;isFocusable=true;tag="evidence_${message.optString("_id")}"
            describeAsControl()
            setOnClickListener(sessionClick{showAlertDetails(message,t)})
        }
        bubble.addView(label((if(message.optBoolean("_saved"))"已儲存 · " else "")+evidenceSpeaker(message),13f,t.textPrimary,true,bottomPad=6))
        if(ConversationCapture.actionable(message))bubble.addView(label(alertTitle(message.optString("reason"))+" · "+when(message.optString("_review_state")){"reviewed"->"已查看";"follow_up"->"待跟進";else->"新記錄"},12f,t.warmCoral,bottomPad=6))
        if(signalLabels(message).isNotEmpty())bubble.addView(label(signalLabels(message),12f,t.warmHoney,bottomPad=6))
        bubble.addView(label(shortEvidence(message.optString("text")),15f,t.textPrimary,bottomPad=8).apply{maxLines=3;ellipsize=android.text.TextUtils.TruncateAt.END})
        bubble.addView(label("${evidenceClock(message)}\n${evidenceSource(message)}"+(if(message.optBoolean("possible_duplicate"))" · 可能重複" else ""),10.5f,t.textMuted))
        val details=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(0,dp(10),0,0)}
        details.addView(label("查看完整記錄",12f,t.accentSeafoam,true),LinearLayout.LayoutParams(0,-2,1f))
        details.addView(icon("caret-right",t.accentSeafoam,16));bubble.addView(details)
        bubble.contentDescription="${evidenceSpeaker(message)}。${shortEvidence(message.optString("text"))}。${evidenceClock(message)}。點按查看完整內容"
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
        val stored = FamilyEvidence(this).use { it.find(scope,id) } ?: return
        val unidentified=ConversationCapture.unidentifiedLegacyLabel(stored)
        showSheet(if(stored.optString("type")=="captured_message")ConversationCapture.displayLabel(stored.optString("conversation_label"),unidentified)else alertTitle(stored.optString("reason"))) { dialog ->
            val reason = stored.optString("reason");val sender = stored.optString("sender","未識別來源")
            addView(label("${evidenceSpeaker(stored)}\n${evidenceClock(stored)}\n${evidenceSource(stored)}",13f,t.textSecondary,bottomPad=16))
            if(unidentified)addView(label("舊版曾把導覽按鈕當作對話名稱，這些內容可能來自不同對話。原文保留，無法可靠還原對象，請勿據此判定發訊人。",13f,t.warmHoney,bottomPad=14))
            addView(label(if(reason=="archive_recovery")"原始舊資料未能自動解析，完整原始內容保留於此，可複製後尋求復原協助。" else if(stored.optBoolean("_legacy_partial"))"舊版本記錄，內容可能已截短，無法復原未保留的部分。" else "保留 App 實際擷取的文字，未必涵蓋 Instagram 原始對話的全部內容。",12f,t.textMuted,bottomPad=14))
            addView(label(stored.optString("text"),16f,t.textPrimary,bottomPad=20).apply{setTextIsSelectable(true)})
            if(reason=="sexual_or_grooming_content")addView(actionButton("尋求協助建議",t.btnSecondaryBg,t.btnSecondaryText) { dialog.dismiss();showSafetyAdvice(stored,t) })
            fun actions(vararg buttons:Button) {
                val vertical=resources.configuration.fontScale>1.3f
                val row=LinearLayout(this@MainActivity).apply{orientation=if(vertical)LinearLayout.VERTICAL else LinearLayout.HORIZONTAL}
                buttons.forEach{button->row.addView(button,LinearLayout.LayoutParams(if(vertical)-1 else 0,-2,if(vertical)0f else 1f).apply{setMargins(0,dp(5),dp(4),dp(8))})}
                addView(row)
            }
            actions(actionButton(if(stored.optBoolean("_saved"))"取消儲存" else "儲存此訊息",t.btnSecondaryBg,t.btnSecondaryText) {
                FamilyEvidence(this@MainActivity).use{it.save(scope,id,!stored.optBoolean("_saved"))};dialog.dismiss();renderUi()
            },actionButton("複製完整記錄",t.btnSecondaryBg,t.btnSecondaryText) { copyText("IG Guard 證據","對話：$sender\n作者：${evidenceSpeaker(stored)}\n時間：${evidenceClock(stored)}\n來源：${evidenceSource(stored)}\n\n${stored.optString("text")}") })
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
                addView(label("關鍵字相符，不代表已確認誘導或侵害。\n"+phrases.joinToString("；"),13f,t.warmCoral,bottomPad=14))
            }
            if(stored.optBoolean("possible_duplicate"))addView(label("此項擷取可能與其他來源重複；無法確認為獨立訊息。",12f,t.warmHoney,bottomPad=12))
            if(stored.optString("_conversation_key").isNotEmpty())addView(textAction("查看此對象的對話") {
                dialog.dismiss();evidenceSearch="";evidenceSaved=false;openConversation(stored.optString("_conversation_key"),ConversationCapture.displayLabel(stored.optString("_conversation_label",sender),unidentified))
            })
            val handle = sender.trim().lowercase(Locale.US).removePrefix("@")
            if(!unidentified && reason in setOf("stranger_message","sexual_or_grooming_content") && !sender.startsWith("子女留言") &&
                stored.optString("conversation_kind")!="group" && handle.matches(Regex("[a-z0-9._]{2,30}")) &&
                handle !in GuardEngine.getTrustedHandles(this@MainActivity) && !GuardEngine.isChildSelfHandle(this@MainActivity,handle) && !handle.contains("instagram")) {
                addView(actionButton("加入信任好友並解除封鎖",t.btnSecondaryBg,t.btnSecondaryText) { addTrustedSender(handle);GuardEngine.syncTrustedFromParent(this@MainActivity);dialog.dismiss() })
            }
            addView(textAction("刪除此記錄") {
                alert().setTitle("永久刪除此記錄？").setMessage("此操作會移除本機文字及收藏，無法復原。")
                    .setNegativeButton("取消",null).setPositiveButton("刪除") { _,_ ->
                        FamilyEvidence(this@MainActivity).use{it.deleteMessage(scope,id)};dialog.dismiss();renderUi()
                    }.show()
            })
            addView(actionButton("關閉",t.btnPrimaryBg,t.btnPrimaryText){dialog.dismiss()})
        }
    }
    private fun maybeShowSafetyAdvice() {
        if(!activityVisible || !GuardEngine.isParentMode(this) || section=="role" || activeSheet?.isShowing==true)return
        val alert = FamilyEvidence(this).use{it.nextAdvice(GuardEngine.getChannelTopic(this))} ?: return
        showSafetyAdvice(alert,currentTheme())
    }
    private fun showSafetyAdvice(alert: JSONObject,t: ThemeColors) {
        if(!GuardEngine.isParentMode(this))return
        val scope = GuardEngine.getChannelTopic(this)
        showSheet("先了解，再尋求合適協助。") { dialog ->
            addView(label("偵測到需留意的關鍵字，不代表已確認誘導或侵害。請先冷靜了解情況，避免責備孩子。",15f,t.textPrimary,bottomPad=18))
            addView(label("保留相關記錄，與孩子確認是否感到不安、被威脅或被要求保密。若有疑慮，請向學校社工、合資格兒童保障或心理專業人士尋求協助。",14f,t.textSecondary,bottomPad=18))
            addView(label("如在香港遇到即時人身危險，請致電 999；其他地區請使用當地緊急求助號碼。App 不能代替專業評估或緊急支援。",14f,t.warmCoral,bottomPad=18))
            addView(actionButton("查看相關記錄",t.btnSecondaryBg,t.btnSecondaryText) {
                dialog.dismiss();section="alerts";evidenceSearch="";openConversation(alert.optString("_conversation_key").takeIf{it.isNotEmpty()},alert.optString("_conversation_label").ifBlank{alert.optString("sender")})
                showAlertDetails(alert,t)
            })
            addView(actionButton("我明白",t.btnPrimaryBg,t.btnPrimaryText){dialog.dismiss()})
        }
        // One sheet covers the current burst; individual records keep an advice action for later review.
        FamilyEvidence(this).use{it.acknowledgeAdvice(scope)}
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
        val id=pendingEvidenceId ?: return;pendingEvidenceId=null
        if(intent?.action=="app.family.igguard.OPEN_EVIDENCE")setIntent(Intent(intent).apply{action=Intent.ACTION_MAIN;data=null})
        if(!GuardEngine.isParentMode(this))return
        val alert=FamilyEvidence(this).use{it.find(GuardEngine.getChannelTopic(this),id)} ?: return
        if(alert.optString("reason")=="sexual_or_grooming_content")showSafetyAdvice(alert,currentTheme()) else showAlertDetails(alert,currentTheme())
    }
    private fun childCaptureStatus():String=when {
        GuardEngine.fullCaptureEnabled(this)->"雙向擷取已啟用 · 傳入、傳出及信任對象的實際擷取內容。"
        GuardEngine.pendingCapturePolicy(this).isNotEmpty()->"只擷取傳入警報 · 已收到家長設定，點按此處閱讀及確認雙向範圍。"
        else->"只擷取傳入警報 · 請家長先傳送雙向擷取設定，點按查看步驟。"
    }
    private fun renderChildOverview(t: ThemeColors) {
        rootContainer.addView(icon("plant", t.accentSeafoam, 42))
        heading("安心上網，\n自在成長。", "給你探索的空間，也給家人一份安心。", t)
        val notification = GuardEngine.isNotificationListenerEnabledStatic(this) && GuardEngine.cpNotifConnected
        val screen = GuardEngine.isAccessibilityEnabledStatic(this) && GuardEngine.cpAccConnected
        val card = makeCard(t)
        card.addView(label(if (notification && screen) "你的保護已準備好" else "完成設定，讓保護正常運作。", 17f, t.textPrimary, true, bottomPad = 8))
        card.addView(label(if (notification && screen) "通知及畫面保護已連接。" else "通知讀取保護背景訊息；畫面保護處理對話、留言回覆及防靜音。", 14f, t.textSecondary))
        rootContainer.addView(card)
        rootContainer.addView(row("chat-circle","雙向對話記錄",childCaptureStatus(),t){showFullCaptureNotice(t)})
        rootContainer.addView(actionButton("查看保護狀態", t.btnPrimaryBg, t.btnPrimaryText) { navigateTo("permissions") })
        rootContainer.addView(row("key", "需要暫時解鎖？", "輸入家長提供的解鎖碼", t) { navigateTo("unlock") })
        rootContainer.addView(textAction("裝置及家庭詳情") {
            showSheet("裝置及家庭詳情") { dialog ->
                val handle = GuardEngine.getChildSelfHandle(this@MainActivity)
                val offenders = GuardEngine.getFlaggedOffenders(this@MainActivity)
                addView(label("家庭核對圖案：${GuardEngine.getSafetyEmojis(this@MainActivity)}\n安全代碼：${GuardEngine.getSafetyCode(this@MainActivity).ifEmpty { "需要更新家庭配對" }}\n\nInstagram 帳號：${if (handle.isEmpty()) "等待自動識別" else "@$handle"}\n\n高危帳號：${if (offenders.isEmpty()) "暫無記錄" else offenders.joinToString(", ") { "@$it" }}", 14f, t.textPrimary, bottomPad = 20))
                addView(actionButton("關閉", t.btnPrimaryBg, t.btnPrimaryText) { dialog.dismiss() })
            }
        })
    }
    private fun showFullCaptureSetup(t:ThemeColors) {
        if(!GuardEngine.isParentMode(this))return
        val scope=GuardEngine.getChannelTopic(this)
        showSheet("設定雙向對話擷取") { dialog ->
            addView(label("兩部手機均需更新。家長在此傳送設定，再在子女手機的「權限 → 雙向對話記錄」閱讀並確認範圍。請只為你有權監護的子女設定，並與孩子說明範圍。",15f,t.textPrimary,bottomPad=16))
            addView(label("新版會保留實際擷取的傳入及傳出訊息，包括信任名單中的一般對話。一般內容不會逐則發出警報，家長可按對話對象及時間查看。",14f,t.textSecondary,bottomPad=14))
            addView(label("家長送出設定後，子女手機需閱讀並確認範圍。未確認前只維持原有保護。Instagram 未向通知或畫面提供的內容無法擷取；這不是完整對話備份。",14f,t.textSecondary,bottomPad=18))
            addView(actionButton("傳送雙向擷取設定",t.btnPrimaryBg,t.btnPrimaryText) {
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
            addView(label("家長可收到 Instagram 實際擷取的傳入及傳出訊息，包括信任名單中的一般對話。這比舊版只轉傳部分傳入警報的範圍更廣。",15f,t.textPrimary,bottomPad=16))
            addView(label("DM 輸入框內尚未送出的草稿不會作為已傳出訊息收集。既有留言及限時動態回覆檢查可能擷取未送出的草稿，會另行標示。",14f,t.textSecondary,bottomPad=14))
            addView(label("內容會先在此手機加密，經本 App 的 Cloudflare 中繼傳送至配對家長手機。離線內容可在雲端加密暫存最多七天；手機會保留未確認記錄並重試。家長的本機記錄保留至刪除。開發者及 Cloudflare 可處理密文及連線資料，中繼不會取得家庭解密金鑰。",14f,t.textSecondary,bottomPad=14))
            addView(label("只能保留通知及畫面實際提供的內容；未顯示、未擷取或已過期的內容無法復原。確認範圍不代表 App 已獲法律認證。",13f,t.textMuted,bottomPad=18))
            if(policy.isNotEmpty() && !GuardEngine.fullCaptureEnabled(this@MainActivity)) {
                addView(actionButton("我已了解，啟用雙向擷取",t.btnPrimaryBg,t.btnPrimaryText) {
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
        heading("每個設定，都說清楚。", "了解每項權限的用途，再前往 Android 設定確認。", t)
        rootContainer.addView(row("chat-circle","雙向對話記錄",childCaptureStatus(),t){showFullCaptureNotice(t)}.apply{getChildAt(0).tag="full_capture_setup"})
        if (!GuardEngine.hasSecureParentCommands(this)) rootContainer.addView(label("需要更新家庭配對：現有監察仍會運作。新版家長指令與臨時碼需要家長傳送新邀請；請先更新兩部手機，並以新主密碼重新建立家庭。", 14f, t.warmHoney, bottomPad = 20))
        val notification = GuardEngine.isNotificationListenerEnabledStatic(this)
        val screen = GuardEngine.isAccessibilityEnabledStatic(this)
        rootContainer.addView(row("bell-simple", "Instagram 通知", if (notification) { if (GuardEngine.cpNotifConnected) "已授權 · 已連接" else "已授權 · 等待連接" } else "未開啟。讀取 IG 通知並加密傳送警報。", t) { openDirectNotificationToggle() })
        rootContainer.addView(row("shield-check", "進階畫面保護", if (screen) { if (GuardEngine.cpAccConnected) "已授權 · 已連接" else "已授權 · 等待連接" } else "未開啟。辨識高危對話、留言及靜音操作。", t) { openAccessibilityToggle() })
        rootContainer.addView(row("info", "監察狀態通知", "持續顯示子女保護模式，與通知讀取是不同權限。", t) { requestAppNotifications() })
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
    private fun renderChildUnlock(t: ThemeColors) {
        heading("需要一點，自己的時間。", "輸入家長提供的 6 位臨時碼，可解鎖 15 分鐘。家長主密碼可解鎖 3 分鐘。", t)
        val input = inputBox(t, "臨時解鎖碼 或 家長主密碼", "", true); rootContainer.addView(input)
        val error = label("", 13f, t.warmCoral); rootContainer.addView(error)
        val lockout = GuardEngine.checkPasswordLockoutRemainingSec(this)
        if (lockout > 0) error.text = "輸入已鎖定，剩餘 $lockout 秒。"
        rootContainer.addView(actionButton("驗證並暫時解鎖", t.btnPrimaryBg, t.btnPrimaryText) {
            val remaining = GuardEngine.checkPasswordLockoutRemainingSec(this)
            if (remaining > 0) { error.text = "請等 $remaining 秒後再試。"; return@actionButton }
            val entered = input.text.toString().trim()
            when {
                GuardEngine.verify15MinUnlockCode(this, entered) -> {
                    GuardEngine.recordPasswordAttempt(this, true); GuardEngine.grantTemporaryUnlock(this, 15 * 60_000L)
                    input.setText("")
                    GuardEngine.sendAlertFromChild(this, "unlock_used", "@${GuardEngine.getChildSelfHandle(this).ifEmpty { "child" }}", "子女手機已使用 6 位臨時解鎖碼解鎖 15 分鐘", bypassDedupe = true)
                    renderUi()
                }
                GuardEngine.verifyParentPassword(this, entered) -> {
                    GuardEngine.recordPasswordAttempt(this, true); GuardEngine.grantTemporaryUnlock(this, 3 * 60_000L)
                    input.setText(""); renderUi()
                }
                else -> { GuardEngine.recordPasswordAttempt(this, false); error.text = "解鎖碼或家長主密碼不正確。"; input.error = "請重新輸入" }
            }
        })
        unlockTimerText = label("", 16f, t.accentSeafoam, true, topPad = 24); rootContainer.addView(unlockTimerText)
        if (GuardEngine.isTemporarilyUnlocked(this)) rootContainer.addView(textAction("立即重新上鎖") { GuardEngine.grantTemporaryUnlock(this, 0); renderUi() })
    }
    private fun renderSettings(t: ThemeColors) {
        heading("保護，照你的步調。", "把常用設定留在容易找到的地方。", t)
        sectionTitle("顯示方式", t)
        val modes = LinearLayout(this)
        for ((bright, text) in listOf(true to "淺色模式", false to "深色模式")) {
            modes.addView(actionButton(text, if (GuardEngine.isBrightTheme(this) == bright) t.bgChip else t.bgCard, t.accentSeafoam) {
                GuardEngine.setBrightTheme(this, bright); renderUi()
            }.apply{isSelected=GuardEngine.isBrightTheme(this@MainActivity)==bright}, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(0, 0, dp(8), 0) })
        }
        rootContainer.addView(modes)
        sectionTitle("保護與通知", t)
        rootContainer.addView(row("bell-simple", if (GuardEngine.isParentMode(this)) "家長警報通知" else "監察狀態通知", "管理系統通知的顯示方式", t) { requestAppNotifications() })
        rootContainer.addView(row("shield", "權限與相容性", "了解 Android 授權及銀行 App 提醒", t) {
            showSheet("清楚了解，每一份授權。") { dialog ->
                addView(label("通知存取、無障礙服務及系統通知是不同權限，正式授權由 Android 處理。\n\n子女模式會讀取 Instagram 通知及畫面。銀行 App 或 Play Protect 仍可能提示風險，允許受限設定不代表這些服務已認可本 App。家長模式不啟用子女監察服務。\n\n若遇到封鎖，請保留完整警告及版本資料，交由開發者向 Google 提交審查。", 14f, t.textSecondary, bottomPad = 18))
                addView(actionButton("受限設定協助", t.btnSecondaryBg, t.btnSecondaryText) { dialog.dismiss(); openAppDetailsSettings() })
            }
        })
        rootContainer.addView(row("info", "字型與圖示授權", "開放字型與圖示的來源及授權", t) {
            showSheet("字型與圖示授權") { _ ->
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
                        FamilyEvidence(this).use{it.deleteOtherArchives(scope)};renderUi()
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
                    addView(label("待確認片段：${GuardEngine.pendingDeliveryCount(this@MainActivity)}\n啟用安全配對後，訊息保留至家長手機確認接收；確認接收不代表已閱讀。斷網後會保留並重試。", 13f, t.textSecondary, bottomPad = 16))
                    addView(actionButton("立即重試傳送", t.btnSecondaryBg, t.btnSecondaryText) { GuardEngine.retryPendingAlerts(this@MainActivity, force = true); dialog.dismiss() })
                    addView(actionButton("檢查系統背景電池限制", t.btnSecondaryBg, t.btnSecondaryText) {
                        launchSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    })
                }
                addView(label(GuardEngine.getCheckpointLogs(this@MainActivity), 12f, t.textSecondary, bottomPad = 18))
                addView(actionButton("清除診斷記錄", t.btnSecondaryBg, t.btnSecondaryText) { GuardEngine.clearCheckpointLogs(this@MainActivity); dialog.dismiss() })
            }
        })
    }
    private fun renderRoleSwitchFooter(t: ThemeColors, requirePin: Boolean) {
        sectionTitle("家庭管理", t)
        if (GuardEngine.isParentMode(this)) rootContainer.addView(row("key", "家長主密碼與金鑰", "密碼查看、金鑰更新及遠端救援", t) {
            showSheet("家長主密碼與金鑰") { dialog ->
                addView(actionButton("查看家長主密碼", t.btnSecondaryBg, t.btnSecondaryText) {
                    alert().setTitle("家長主密碼").setMessage(GuardEngine.getPin(this@MainActivity)).setPositiveButton("關閉", null).show()
                })
                addView(actionButton("更新家庭金鑰並同步子女手機", t.btnSecondaryBg, t.btnSecondaryText) {
                    sendParentCommand(resetChild = false); dialog.dismiss()
                })
                addView(actionButton("遠端解除子女保護並重設身份", t.btnSecondaryBg, t.btnSecondaryText) {
                    alert().setTitle("遠端重設子女手機？").setMessage("發送加密救援指令，暫時解除子女手機保護並返回身份設定。")
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
    private fun sendParentCommand(resetChild: Boolean) {
        Toast.makeText(this, "正在傳送已簽署的家長指令。", Toast.LENGTH_SHORT).show()
        GuardEngine.rotateKeyAndNotifyChild(this, resetChild) { success ->
            uiHandler.post {
                if (!activityVisible || !GuardEngine.isParentMode(this)) return@post
                Toast.makeText(this, if (success) "指令已發送，請等待子女手機確認。" else "未能發送，保留目前配對。請查看診斷後重試。", Toast.LENGTH_LONG).show()
                renderUi()
            }
        }
    }
    private var activeSheet: Dialog? = null
    private val uiDialogs = mutableSetOf<Dialog>()
    private fun trackDialog(dialog: Dialog) {
        uiDialogs.add(dialog)
        dialog.setOnDismissListener {
            uiDialogs.remove(dialog)
            if (activeSheet === dialog) activeSheet = null
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
            if (GuardEngine.captureRelaySession(this) == session) action()
            else { dismissUiDialogs(); renderUi() }
        }
    }
    private fun alert(): AlertDialog.Builder {
        val session = GuardEngine.captureRelaySession(this)
        return object : AlertDialog.Builder(this@MainActivity) {
            override fun show(): AlertDialog = super.show().also { trackDialog(it) }
            override fun setPositiveButton(text: CharSequence?, listener: DialogInterface.OnClickListener?): AlertDialog.Builder =
                super.setPositiveButton(text) { dialog, which ->
                    if (GuardEngine.captureRelaySession(this@MainActivity) == session) listener?.onClick(dialog, which)
                    else { dialog.dismiss(); dismissUiDialogs(); renderUi() }
                }
        }
    }
    private fun showSheet(title: String, content: LinearLayout.(Dialog) -> Unit) {
        activeSheet?.dismiss()
        val t = currentTheme(); val dialog = Dialog(this)
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = background(t.bgCard, 18); setPadding(dp(24), dp(10), dp(24), dp(24)) }
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
        trackDialog(dialog)
        dialog.show()
        val width = resources.displayMetrics.widthPixels
        scroll.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec((resources.displayMetrics.heightPixels * .82f).toInt(), View.MeasureSpec.AT_MOST))
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, scroll.measuredHeight.coerceAtMost((resources.displayMetrics.heightPixels * .82f).toInt()))
        if (Build.VERSION.SDK_INT >= 28) wrap.accessibilityPaneTitle = title
        activeSheet = dialog
    }
    private fun copyText(title: String, value: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(android.content.ClipData.newPlainText(title, value))
        Toast.makeText(this, "已複製。", Toast.LENGTH_SHORT).show()
    }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun divider(parent: LinearLayout, t: ThemeColors) { parent.addView(View(this).apply { setBackgroundColor(color(t.borderSubtle)) }, LinearLayout.LayoutParams(-1, dp(1))) }
    private fun sectionTitle(title: String, t: ThemeColors) { rootContainer.addView(label(title, 16f, t.textPrimary, true, topPad = 22, bottomPad = 14)) }
    private fun makeCard(t: ThemeColors, isHero: Boolean = false): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; background = background(if (isHero) t.bgHero else t.bgCard, 12, t.borderSubtle)
        setPadding(dp(18), dp(18), dp(18), dp(18))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(12), 0, dp(12)) }
    }
    private fun row(glyph: String, title: String, description: String, t: ThemeColors, tint: String = t.textPrimary, onClick: () -> Unit): LinearLayout {
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val line = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(74); setPadding(0, dp(14), 0, dp(14))
            isClickable = true; isFocusable = true; background = ripple(t.bgCanvas, 8)
            contentDescription = "$title。$description"; setOnClickListener(sessionClick(onClick))
            describeAsControl()
        }
        line.addView(icon(glyph, t.accentSeafoam, 24))
        val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(10), 0) }
        words.addView(label(title, 15f, tint, true, bottomPad = 5)); words.addView(label(description, 13f, t.textSecondary))
        line.addView(words, LinearLayout.LayoutParams(0, -2, 1f)); line.addView(icon("caret-right", t.textMuted, 18))
        outer.addView(line); divider(outer, t); return outer
    }
    private fun label(text: String, sizeSp: Float, hexColor: String, bold: Boolean = false, topPad: Int = 0, bottomPad: Int = 0): TextView = TextView(this).apply {
        this.text = text; textSize = sizeSp; typeface = font(bold); setTextColor(color(hexColor))
        includeFontPadding = false; setLineSpacing(dp(3).toFloat(), 1.05f)
        setPadding(0, dp(topPad), 0, dp(bottomPad))
        if (bold && sizeSp >= 20 && Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
    }
    private fun inputBox(t: ThemeColors, hintText: String, initialValue: String, isPassword: Boolean = false, remember: Boolean = true): EditText = EditText(this).apply {
        hint = hintText; contentDescription = hintText; textSize = 15f; typeface = font()
        setTextColor(color(t.textPrimary)); setHintTextColor(color(t.textMuted)); background = focusSurface(t.bgInput, 8, t.accentPowderSky)
        inputType = if (isPassword) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT
        setText(if (remember) inputDrafts[hintText] ?: initialValue else initialValue)
        if (remember) addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { inputDrafts[hintText] = s.toString() }
            override fun afterTextChanged(s: Editable?) {}
        })
        minimumHeight = dp(54); setPadding(dp(14), dp(14), dp(14), dp(14)); isSaveEnabled = false
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(12)) }
    }
    private fun actionButton(title: String, hexBg: String, hexText: String, onClick: () -> Unit): Button = Button(this).apply {
        text = title; textSize = 14f; typeface = font(true); isAllCaps = false
        setTextColor(color(hexText)); background = ripple(hexBg, 8, if (hexBg != currentTheme().btnPrimaryBg) currentTheme().accentPowderSky else null)
        minimumHeight = dp(52); setPadding(dp(14), dp(13), dp(14), dp(13)); setOnClickListener(sessionClick(onClick))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(5), 0, dp(8)) }
    }
    private fun textAction(title: String, onClick: () -> Unit): Button = Button(this).apply {
        val t = currentTheme(); text = title; textSize = 13f; typeface = font(true); isAllCaps = false
        setTextColor(color(t.accentSeafoam)); background = ripple(t.bgCard, 8, t.accentPowderSky); minimumHeight = dp(48)
        gravity = Gravity.CENTER_VERTICAL or Gravity.START; setPadding(dp(12), dp(10), dp(12), dp(10)); setOnClickListener(sessionClick(onClick))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply{setMargins(0,dp(4),0,dp(6))}
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
            Toast.makeText(this, "IG Guard 保護已啟動並通知家長手機。", Toast.LENGTH_LONG).show()
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
            "讀取 Instagram 畫面以偵測高危對話、留言回覆及靜音操作，並在需要時返回上一頁。此功能需要無障礙存取；銀行 App 可能因此提示風險。"
        else "讀取 Instagram 通知，將警報加密傳送到家長手機，並清除高危通知。Android 授予的是通知存取權；本 App 只處理 Instagram。"
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
            .setMessage("1. 先在通知存取或無障礙頁嘗試開啟 IG Guard，閱讀系統顯示的限制。\n\n2. 若系統要求允許受限設定，再開啟本 App 的資訊頁。在支援此流程的裝置，右上角 ⋮ 才可能出現「允許受限設定」。依系統提示確認身份。\n\n3. 返回本 App，再次開啟原來的權限。\n\n若沒有該選項，請遵從裝置顯示的說明；選項取決於系統版本及安裝來源。這項操作不代表 Play Protect 或銀行 App 已認可本 App。")
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
