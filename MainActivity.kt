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
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcelable
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
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URL
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
import javax.net.ssl.HttpsURLConnection
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
        val labels = listOf("陌生人 / 回覆", "色情 / 誘導", "防篡改 / 離線", "臨時解鎖", "其他警報")
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
    private val pollInFlight = AtomicBoolean(false)
    private var relayGeneration = 0

    fun captureRelaySession(ctx: Context): Triple<String, String, Int> = synchronized(pollLock) {
        Triple(getRole(ctx), getOrCreateFamilyToken(ctx), relayGeneration)
    }

    // O(1) in-memory caches for AES-256 key, Trusted Handles Set, and Flagged Offenders Set
    @Volatile private var cachedKeyToken: String = ""
    @Volatile private var cachedSecretKey: SecretKeySpec? = null
    @Volatile private var cachedTrustedRaw: String = ""
    @Volatile private var cachedTrustedSet: Set<String> = emptySet()
    @Volatile private var cachedOffendersRaw: String = ""
    @Volatile private var cachedOffendersSet: Set<String> = emptySet()

    // Live Checkpoint & Heartbeat States
    @Volatile var cpNotifConnected: Boolean = false
    @Volatile var cpLastNotifTime: String = "Never"
    @Volatile var cpAccConnected: Boolean = false
    @Volatile var cpLastAccTime: String = "Never"
    @Volatile var cpLastHttpStatus: String = "Ready (TLS + AES-256-GCM)"
    @Volatile var cpParentStreamState: String = "Idle"

    private val EMOJI_PALETTE = arrayOf(
        "🐼", "🦁", "🐬", "🦊", "🐨", "🐯", "🦄", "🐝",
        "🚀", "🌈", "🍀", "🌻", "🍎", "🍉", "⚽", "🎸"
    )

    private val PAIR_V42_REGEX = Regex("IGGUARD-PAIR-V42:([a-zA-Z0-9\\-]{8,64}):([a-zA-Z0-9_\\-=]+)")
    private val PAIR_LEGACY_REGEX = Regex("IGGUARD-PAIR:([a-zA-Z0-9\\-]{8,64}):([^\\]\\s]+)")
    private val TOKEN_PARAM_REGEX = Regex("token=([a-zA-Z0-9\\-]{8,64})")
    private val PW_PARAM_REGEX = Regex("pw=([a-zA-Z0-9_\\-=]+)")
    private val PIN_PARAM_REGEX = Regex("pin=([^&\\s]+)")
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
            "add a comment\\.{0,3}|新增留言\\.{0,3}|reply\\.{0,3}|回覆\\.{0,3}|reply to .*|回覆.*|" +
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
        val existingKey = cachedSecretKey
        if (clean == cachedKeyToken && existingKey != null) {
            return existingKey
        }
        val keyBytes = MessageDigest.getInstance("SHA-256")
            .digest("ig_guard_e2ee_aes256_v4.2:$clean".toByteArray(Charsets.UTF_8))
        val newSpec = SecretKeySpec(keyBytes, "AES")
        cachedKeyToken = clean
        cachedSecretKey = newSpec
        return newSpec
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

    fun decryptPayload(token: String, wireStr: String): JSONObject? {
        return try {
            val wrapper = JSONObject(wireStr)
            if (wrapper.has("e2ee")) {
                val combined = Base64.decode(wrapper.getString("e2ee"), Base64.NO_WRAP)
                if (combined.size <= 12) return null
                val iv = combined.copyOfRange(0, 12)
                val cipherBytes = combined.copyOfRange(12, combined.size)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, deriveAesKey(token), GCMParameterSpec(128, iv))
                val plainBytes = cipher.doFinal(cipherBytes)
                JSONObject(String(plainBytes, Charsets.UTF_8))
            } else {
                wrapper
            }
        } catch (_: Exception) {
            null
        }
    }

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

    fun recordChildHeartbeatOnParent(ctx: Context, summary: String) {
        prefs(ctx).edit()
            .putLong("parent_last_child_hb_ms", System.currentTimeMillis())
            .putString("parent_last_child_hb_text", "${nowDateTimeStr()} — $summary")
            .putBoolean("parent_hb_missing_alerted", false)
            .apply()
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
        val existing = prefs(ctx).getString("parent_master_password", null)
        if (!existing.isNullOrBlank()) return existing
        val generated = generateRandomStrongPassword()
        prefs(ctx).edit().putString("parent_master_password", generated).apply()
        return generated
    }

    fun setPin(ctx: Context, password: String) {
        val clean = password.trim()
        if (clean.length >= 8) {
            prefs(ctx).edit().putString("parent_master_password", clean).apply()
        }
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

    fun generate15MinUnlockCode(ctx: Context, bucketOffset: Long = 0L): String {
        val alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
        val token = getOrCreateFamilyToken(ctx)
        val bucket = (System.currentTimeMillis() / 900_000L) + bucketOffset
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("unlock15_v4.2:$token:$bucket".toByteArray(Charsets.UTF_8))
        return (0 until 6).map { i ->
            alphabet[(digest[i].toInt() and 0xFF) % alphabet.length]
        }.joinToString("")
    }

    fun verify15MinUnlockCode(ctx: Context, inputCode: String): Boolean {
        val clean = inputCode.trim().uppercase(Locale.US)
        if (clean.length != 6) return false
        for (offset in -1L..1L) {
            if (constantTimeEquals(clean, generate15MinUnlockCode(ctx, offset))) return true
        }
        val syncedCode = (prefs(ctx).getString("synced_15m_code", "") ?: "").uppercase(Locale.US)
        val syncedExp = prefs(ctx).getLong("synced_15m_exp", 0L)
        if (syncedCode.isNotEmpty() && System.currentTimeMillis() < syncedExp && constantTimeEquals(clean, syncedCode)) {
            return true
        }
        return false
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
    fun rotateKeyAndNotifyChild(ctx: Context, resetChildToUnconfigured: Boolean): String {
        val oldToken = getOrCreateFamilyToken(ctx)
        val oldTopic = getChannelTopicForToken(oldToken)
        val newToken = UUID.randomUUID().toString()
        val newPin = getPin(ctx)
        val now = System.currentTimeMillis()

        val plain = JSONObject().apply {
            put("type", if (resetChildToUnconfigured) "remote_reset_child" else "key_rotated")
            put("new_token", newToken)
            put("new_pin", newPin)
            put("ts_ms", now)
            put("time", nowDateTimeStr())
        }.toString()

        if (!resetChildToUnconfigured) {
            setFamilyToken(ctx, newToken, preserveFamilyHistory = true)
            ParentAlertService.reconnectActiveStream()
        }

        postEncryptedToChannel(ctx, oldTopic, oldToken, plain) { _, _ ->
            ctx.sendBroadcast(Intent(ACTION_UI_REFRESH).setPackage(ctx.packageName))
        }
        return newToken
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
            if (getRole(ctx) != role) relayGeneration++
            prefs(ctx).edit().putString("role", role).apply()
        }
        syncChildComponents(ctx)
    }

    fun syncChildComponents(ctx: Context) {
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
            .setContentText("IG 警報會加密傳送給已配對的家長。點按查看權限及狀態。")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).build()
        try { manager.notify(7001, notification) } catch (_: SecurityException) { /* Permission changed while posting. */ }
    }
    fun isChildMode(ctx: Context): Boolean = getRole(ctx) == "child"
    fun isParentMode(ctx: Context): Boolean = getRole(ctx) == "parent"

    fun getOrCreateFamilyToken(ctx: Context): String {
        val existing = prefs(ctx).getString("family_uuid_token", null)
        if (!existing.isNullOrBlank()) return existing
        val generated = UUID.randomUUID().toString()
        prefs(ctx).edit().putString("family_uuid_token", generated).apply()
        return generated
    }

    fun setFamilyToken(ctx: Context, token: String, preserveFamilyHistory: Boolean = false) = synchronized(pollLock) {
        val clean = token.trim().lowercase(Locale.US)
        if (clean.isNotEmpty()) {
            val old = prefs(ctx).getString("family_uuid_token", "")
            val editor = prefs(ctx).edit()
            if (old != clean) {
                relayGeneration++
                if (!preserveFamilyHistory) {
                    editor.remove("weekly_stats_json").remove("parent_alerts_json").remove("seen_msg_ids")
                        .remove("parent_last_child_hb_ms").remove("parent_last_child_hb_text").remove("parent_hb_missing_alerted")
                }
            }
            // Token and family-scoped history change in the same state transaction.
            editor.putString("family_uuid_token", clean).apply()
        }
    }

    fun hasSentInitialSetupConfirmation(ctx: Context): Boolean = prefs(ctx).getBoolean("sent_setup_ok", false)
    fun markInitialSetupConfirmationSent(ctx: Context, sent: Boolean) = prefs(ctx).edit().putBoolean("sent_setup_ok", sent).apply()

    // shortcut: uses public ntfy.sh HTTPS relay with AES-256-GCM E2EE payload encryption, upgrade to self-hosted relay for enterprise scale
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

    fun buildPairingInviteText(ctx: Context): String {
        val token = getOrCreateFamilyToken(ctx)
        val pin = getPin(ctx)
        val emojis = getSafetyEmojis(ctx)
        val pinB64 = Base64.encodeToString(pin.toByteArray(Charsets.UTF_8), Base64.NO_WRAP or Base64.URL_SAFE)
        return buildString {
            append("🛡️ 【IG Guard 家庭專屬加密配對邀請】\n")
            append("核對圖案 (Safety Emojis): $emojis\n\n")
            append("步驟 1：在子女手機複製此整段訊息，然後打開 IG Guard 即可一鍵完成配對！\n")
            append("或直接點選專屬配對連結：\n")
            append("igguard://pair?token=$token&pw=$pinB64\n\n")
            append("[IGGUARD-PAIR-V42:$token:$pinB64]")
        }
    }

    fun parsePairingString(raw: String): Pair<String, String>? {
        val text = raw.trim()
        val v42Match = PAIR_V42_REGEX.find(text)
        if (v42Match != null) {
            val token = v42Match.groupValues[1]
            val pw = try {
                String(Base64.decode(v42Match.groupValues[2], Base64.NO_WRAP or Base64.URL_SAFE), Charsets.UTF_8)
            } catch (_: Exception) {
                "HkGuard2026"
            }
            return token to pw
        }
        val legacyMatch = PAIR_LEGACY_REGEX.find(text)
        if (legacyMatch != null) {
            return legacyMatch.groupValues[1] to legacyMatch.groupValues[2]
        }
        if (text.contains("token=")) {
            val token = TOKEN_PARAM_REGEX.find(text)?.groupValues?.get(1)
            val pwB64 = PW_PARAM_REGEX.find(text)?.groupValues?.get(1)
            val pw = if (!pwB64.isNullOrEmpty()) {
                try {
                    String(Base64.decode(pwB64, Base64.NO_WRAP or Base64.URL_SAFE), Charsets.UTF_8)
                } catch (_: Exception) {
                    "HkGuard2026"
                }
            } else {
                PIN_PARAM_REGEX.find(text)?.groupValues?.get(1) ?: "HkGuard2026"
            }
            if (!token.isNullOrEmpty()) return token to pw
        }
        return null
    }

    // ================= 5. AUTO-DETECTED CHILD IG HANDLE + TRUSTED FRIENDS + FLAGGED OFFENDERS =================
    fun getChildSelfHandle(ctx: Context): String {
        return (prefs(ctx).getString("child_self_handle", "") ?: "").trim().lowercase(Locale.US).removePrefix("@")
    }

    fun setChildSelfHandle(ctx: Context, handle: String) {
        val clean = handle.trim().lowercase(Locale.US).removePrefix("@")
        if (clean.isEmpty() || clean in RESERVED_UI_HEADERS || !VALID_IG_HANDLE_REGEX.matches(clean)) return
        prefs(ctx).edit().putString("child_self_handle", clean).apply()
        cachedTrustedRaw = ""
        cachedOffendersRaw = ""
        val cleanedTrusted = getTrustedHandles(ctx).filter { it != clean }
        prefs(ctx).edit().putString("trusted_csv", cleanedTrusted.joinToString(",")).apply()
        val cleanedOffenders = getFlaggedOffenders(ctx).filter { it != clean }
        prefs(ctx).edit().putString("flagged_offenders_csv", cleanedOffenders.joinToString(",")).apply()
        cachedTrustedRaw = ""
        cachedOffendersRaw = ""
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

    fun getFlaggedOffenders(ctx: Context): Set<String> {
        val selfHandle = getChildSelfHandle(ctx)
        val raw = prefs(ctx).getString("flagged_offenders_csv", "") ?: ""
        val key = "$selfHandle|$raw"
        if (key == cachedOffendersRaw) return cachedOffendersSet
        val parsed = raw.split(",")
            .map { it.trim().lowercase(Locale.US).removePrefix("@") }
            .filter { it.isNotEmpty() && it != selfHandle }
            .toSet()
        cachedOffendersRaw = key
        cachedOffendersSet = parsed
        return parsed
    }

    fun markSenderAsFlaggedOffender(ctx: Context, sender: String) {
        val clean = sender.trim().lowercase(Locale.US).removePrefix("@")
        if (clean.isEmpty() || clean in RESERVED_UI_HEADERS || clean == "instagram_dm" || isSelfSenderLabel(ctx, clean)) return
        val next = getFlaggedOffenders(ctx).toMutableSet()
        if (next.add(clean)) {
            prefs(ctx).edit().putString("flagged_offenders_csv", next.joinToString(",")).apply()
            cachedOffendersRaw = ""
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

    fun getTrustedHandles(ctx: Context): Set<String> {
        val selfHandle = getChildSelfHandle(ctx)
        val raw = prefs(ctx).getString("trusted_csv", "") ?: ""
        val key = "$selfHandle|$raw"
        if (key == cachedTrustedRaw) return cachedTrustedSet
        val parsed = raw.split(",")
            .map { it.trim().lowercase(Locale.US).removePrefix("@") }
            .filter { it.isNotEmpty() && it != selfHandle }
            .toSet()
        cachedTrustedRaw = key
        cachedTrustedSet = parsed
        return parsed
    }

    fun setTrustedHandles(ctx: Context, handles: Collection<String>) {
        val selfHandle = getChildSelfHandle(ctx)
        val clean = handles.map { it.trim().lowercase(Locale.US).removePrefix("@") }
            .filter { it.isNotEmpty() && it != selfHandle }
            .distinct()
            .sorted()
        prefs(ctx).edit().putString("trusted_csv", clean.joinToString(",")).apply()
        cachedTrustedRaw = ""
        val remainingOffenders = getFlaggedOffenders(ctx) - clean.toSet()
        prefs(ctx).edit().putString("flagged_offenders_csv", remainingOffenders.joinToString(",")).apply()
        cachedOffendersRaw = ""
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

    fun isTrustedSender(ctx: Context, rawTitle: String, rawText: String): Boolean {
        val trustedSet = getTrustedHandles(ctx)
        val titleNorm = rawTitle.trim().lowercase(Locale.US).removePrefix("@")
        if (titleNorm.isEmpty() || titleNorm == "instagram") return false
        if (titleNorm in trustedSet) return true
        val bodyPrefix = rawText.substringBefore(":", "").trim().lowercase(Locale.US).removePrefix("@")
        if (bodyPrefix.isNotEmpty() && bodyPrefix in trustedSet) return true

        for (trusted in trustedSet) {
            if (titleNorm.contains("(@$trusted)") ||
                titleNorm.startsWith("$trusted ") ||
                titleNorm.endsWith(" $trusted")
            ) {
                return true
            }
        }
        return false
    }

    fun isNonMessageUiString(raw: String): Boolean {
        val clean = raw.trim()
        if (clean.length < 2) return true
        return NON_MESSAGE_UI_PATTERN.matches(clean)
    }

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
        onComplete: ((Boolean, String) -> Unit)? = null
    ) {
        val cleanSender = sender.trim().ifEmpty { "unknown" }.take(80)
        val cleanText = text.trim().take(280)
        val dedupeKey = "${reason.lowercase(Locale.US)}|${cleanSender.lowercase(Locale.US).removePrefix("@")}|${cleanText.lowercase(Locale.US)}"
        val windowMs = if (fromNotification) NOTIF_BURST_DEDUPE_MS else SCREEN_DEDUPE_MS

        if (!shouldSendAlert(
                dedupeKey,
                windowMs = windowMs,
                bypassDedupe = bypassDedupe || reason == "test_alert" || reason == "heartbeat_ok"
            )
        ) {
            addCheckpointLog(ctx, "CP2-DEDUPE", "Skipped duplicate: @$cleanSender (${cleanText.take(30)})")
            onComplete?.invoke(false, "Duplicate suppressed")
            return
        }

        addCheckpointLog(ctx, "CP2-MATCH", "[$reason] @$cleanSender: ${cleanText.take(40)}")

        val token = getOrCreateFamilyToken(ctx)
        val topic = getChannelTopicForToken(token)
        val plain = JSONObject().apply {
            put("type", "alert")
            put("reason", reason)
            put("sender", cleanSender)
            put("text", cleanText)
            put("ts_ms", System.currentTimeMillis())
            put("time", nowDateTimeStr())
            put("trusted_csv", getTrustedHandles(ctx).joinToString(","))
            put("child_self_handle", getChildSelfHandle(ctx))
        }.toString()

        postEncryptedToChannel(ctx, topic, token, plain, onComplete)
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
        onComplete: ((Boolean, String) -> Unit)?
    ) {
        thread {
            try {
                val encryptedBody = encryptPayload(token, plainJson)
                val conn = (URL("https://ntfy.sh/$topic").openConnection() as HttpsURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8000
                    readTimeout = 8000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                conn.outputStream.use { it.write(encryptedBody.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                conn.disconnect()

                val statusMsg = if (code in 200..299) {
                    "HTTPS $code (AES-256-GCM E2EE 🔒)"
                } else if (code == 429) {
                    "HTTPS 429 Rate Limit"
                } else {
                    "HTTPS $code Error"
                }
                cpLastHttpStatus = "${nowTimeStr()} -> $statusMsg"
                addCheckpointLog(ctx, "CP3-E2EE", statusMsg)
                onComplete?.invoke(code in 200..299, statusMsg)
            } catch (e: Exception) {
                val err = "TLS Fail: ${e.javaClass.simpleName}"
                cpLastHttpStatus = "${nowTimeStr()} -> $err"
                addCheckpointLog(ctx, "CP3-ERR", err)
                onComplete?.invoke(false, err)
            }
        }
    }

    fun pollChannelOnce(ctx: Context, since: String = "30m", onNewAlertForParent: ((JSONObject) -> Unit)? = null) {
        // Coalesce overlapping polls instead of queuing network threads behind the UI state lock.
        if (!pollInFlight.compareAndSet(false, true)) return
        var conn: HttpsURLConnection? = null
        try {
            val session = captureRelaySession(ctx)
            if (session.first == "unconfigured") return
            val topic = getChannelTopicForToken(session.second)
            conn = (URL("https://ntfy.sh/$topic/json?poll=1&since=$since").openConnection() as HttpsURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                cpLastHttpStatus = "${nowTimeStr()} -> HTTPS $code"
                return
            }
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    processIncomingRelayLine(ctx, line, session.first, onNewAlertForParent, session.second, session.third)
                }
            }
            cpLastHttpStatus = "${nowTimeStr()} -> HTTPS 200 (AES-256-GCM 🔒)"
        } catch (e: Exception) {
            cpLastHttpStatus = "${nowTimeStr()} -> TLS Err: ${e.javaClass.simpleName}"
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
            pollInFlight.set(false)
        }
    }

    fun processIncomingRelayLine(
        ctx: Context,
        line: String,
        role: String,
        onNewAlertForParent: ((JSONObject) -> Unit)?,
        expectedToken: String? = null,
        expectedGeneration: Int? = null
    ) = synchronized(pollLock) {
        if (role != getRole(ctx) || role == "unconfigured") return
        if (expectedGeneration != null && expectedGeneration != relayGeneration) return
        if (expectedToken != null && expectedToken != getOrCreateFamilyToken(ctx)) return
        val outer = try { JSONObject(line) } catch (_: Exception) { return }
        if (outer.optString("event") != "message") return
        val msgId = outer.optString("id")
        if (msgId.isEmpty()) return

        val existingSet = prefs(ctx).getStringSet("seen_msg_ids", emptySet()) ?: emptySet()
        if (existingSet.contains(msgId)) return

        val innerStr = outer.optString("message")
        val token = getOrCreateFamilyToken(ctx)
        val inner = decryptPayload(token, innerStr) ?: return
        val msgType = inner.optString("type")
        val msgTs = inner.optLong("ts_ms", 0L)

        val updatedIds = HashSet<String>(existingSet)
        updatedIds.add(msgId)
        val persistedSet = if (updatedIds.size > 300) {
            HashSet(updatedIds.toList().takeLast(300))
        } else {
            updatedIds
        }
        prefs(ctx).edit().putStringSet("seen_msg_ids", persistedSet).apply()

        if (msgType in setOf("sync_trusted", "key_rotated", "remote_reset_child") && msgTs > 0L) {
            val lastControlTs = prefs(ctx).getLong("last_control_ts_ms", 0L)
            if (msgTs < lastControlTs) return
            prefs(ctx).edit().putLong("last_control_ts_ms", msgTs).apply()
        }

        when (msgType) {
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
                    if (newPin.length >= 8) setPin(ctx, newPin)
                    addCheckpointLog(ctx, "CP4-KEY", "Auto-updated to new Parent Family Key ✅")
                    ctx.sendBroadcast(Intent(ACTION_UI_REFRESH).setPackage(ctx.packageName))
                }
            }
            "remote_reset_child" -> {
                val freshEnough = msgTs == 0L || (System.currentTimeMillis() - msgTs) <= 20 * 60_000L
                if (role == "child" && freshEnough) {
                    grantTemporaryUnlock(ctx, 10 * 60_000L)
                    setRole(ctx, "unconfigured")
                    addCheckpointLog(ctx, "CP4-RESET", "Parent remotely unlocked & reset Child Phone")
                    ctx.sendBroadcast(Intent(ACTION_UI_REFRESH).setPackage(ctx.packageName))
                }
            }
            "alert" -> {
                if (role == "parent") {
                    val childSelf = inner.optString("child_self_handle")
                    if (childSelf.isNotEmpty()) {
                        setChildSelfHandle(ctx, childSelf)
                    }
                    if (!recordParentAlert(ctx, inner, "relay:$msgId")) return
                    val reason = inner.optString("reason")
                    if (reason == "heartbeat_ok") {
                        recordChildHeartbeatOnParent(ctx, inner.optString("text"))
                    }
                    addCheckpointLog(ctx, "CP4-RECV", "Decrypted $reason from ${inner.optString("sender")}")
                    onNewAlertForParent?.invoke(inner)
                    ctx.sendBroadcast(Intent(ACTION_UI_REFRESH).setPackage(ctx.packageName))
                }
            }
        }
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

    fun recordParentAlert(ctx: Context, alertObj: JSONObject, eventId: String): Boolean = synchronized(pollLock) {
        if (!isParentMode(ctx)) return false
        val stats = loadParentStatistics(ctx)
        val accepted = stats.record(eventId, alertObj)
        val editor = prefs(ctx).edit().putString("weekly_stats_json", stats.state.toString())
        if (accepted) {
            val next = JSONArray().put(alertObj)
            for (old in getParentAlerts(ctx).take(59)) next.put(old)
            editor.putString("parent_alerts_json", next.toString())
        }
        editor.apply()
        accepted
    }

    fun getParentAlerts(ctx: Context): List<JSONObject> {
        val rawArr = prefs(ctx).getString("parent_alerts_json", "[]") ?: "[]"
        val arr = try { JSONArray(rawArr) } catch (_: Exception) { JSONArray() }
        val out = ArrayList<JSONObject>(arr.length())
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { out.add(it) }
        }
        return out
    }

    fun clearParentAlerts(ctx: Context) = synchronized(pollLock) {
        // Seed statistics before clearing an upgraded installation's original history.
        getParentStatistics(ctx)
        prefs(ctx).edit().putString("parent_alerts_json", "[]").apply()
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
                thread { GuardEngine.pollChannelOnce(applicationContext, since = "30m") }
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
        GuardEngine.checkAndSendChildHeartbeatIfDue(this)

        val pkg = sbn?.packageName ?: return
        if (pkg != GuardEngine.INSTAGRAM_PKG) return
        val notif = sbn.notification ?: return
        val extras = notif.extras ?: return

        GuardEngine.cpNotifConnected = true
        GuardEngine.cpLastNotifTime = GuardEngine.nowTimeStr()

        // Issue #1 Fix: Auto-detect child's own Instagram handle from MessagingStyle recipient metadata
        var selfDisplayName = extras.getCharSequence(Notification.EXTRA_SELF_DISPLAY_NAME)?.toString()?.trim() ?: ""
        if (selfDisplayName.isEmpty() && Build.VERSION.SDK_INT >= 28) {
            @Suppress("DEPRECATION")
            val selfPerson = extras.getParcelable<Parcelable>(Notification.EXTRA_MESSAGING_PERSON) as? Person
            selfDisplayName = selfPerson?.name?.toString()?.trim() ?: ""
        }
        if (selfDisplayName.isNotEmpty() && !selfDisplayName.contains(" ")) {
            GuardEngine.updateAutoDetectedChildHandle(this, selfDisplayName)
        }

        val convHeader = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim() ?: ""
        val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        val fallbackBody = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: "").toString().trim()

        // Strip "[child_handle] " recipient prefix if Instagram includes it and auto-sync to Parent
        var cleanTitle = rawTitle
        if (rawTitle.startsWith("[") && rawTitle.contains("]")) {
            val bracketed = rawTitle.substringAfter("[").substringBefore("]").trim()
            val remainder = rawTitle.substringAfter("]").trim()
            if (bracketed.isNotEmpty() && !bracketed.contains(" ")) {
                GuardEngine.updateAutoDetectedChildHandle(this, bracketed)
            }
            if (remainder.isNotEmpty()) cleanTitle = remainder
        }

        if (convHeader.isNotEmpty() && cleanTitle.isNotEmpty() &&
            !convHeader.equals(cleanTitle, ignoreCase = true) &&
            !convHeader.contains(" ")
        ) {
            GuardEngine.updateAutoDetectedChildHandle(this, convHeader)
        }

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

        if (resolvedMessage.contains(": ")) {
            val possibleSender = resolvedMessage.substringBefore(": ").trim()
            val possibleMsg = resolvedMessage.substringAfter(": ").trim()
            if (possibleSender.isNotEmpty() && !possibleSender.contains(" ") && possibleMsg.isNotEmpty()) {
                // Issue #2 Fix: If the prefix before ": " is the child's own handle, ignore it immediately!
                if (GuardEngine.isSelfSenderLabel(this, possibleSender) ||
                    (selfDisplayName.isNotEmpty() && possibleSender.equals(selfDisplayName, ignoreCase = true))
                ) {
                    return
                }
                resolvedSender = possibleSender
                resolvedMessage = possibleMsg
            }
        } else if (fallbackBody.contains(": ")) {
            val possibleSender = fallbackBody.substringBefore(": ").trim()
            val possibleMsg = fallbackBody.substringAfter(": ").trim()
            if (possibleSender.isNotEmpty() && !possibleSender.contains(" ") && possibleMsg == resolvedMessage) {
                if (GuardEngine.isSelfSenderLabel(this, possibleSender) ||
                    (selfDisplayName.isNotEmpty() && possibleSender.equals(selfDisplayName, ignoreCase = true))
                ) {
                    return
                }
                resolvedSender = possibleSender
            }
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
            fromNotification = true
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

    private val debounceHandler = Handler(Looper.getMainLooper())
    private var pendingReplyText: String = ""
    private var pendingReplyContextAuthor: String = ""
    private var lastSentCommentReply: String = ""

    private val flushPendingCommentReplyRunnable = Runnable {
        if (!GuardEngine.isChildMode(this@IgAccessibilityService)) {
            pendingReplyText = ""
            return@Runnable
        }
        val textToSend = pendingReplyText.trim()
        val targetAuthor = pendingReplyContextAuthor.ifEmpty { "IG_Post_Or_Story" }
        if (textToSend.length >= 2 && textToSend != lastSentCommentReply) {
            lastSentCommentReply = textToSend
            val isSexual = GuardEngine.SEXUAL_PATTERN.containsMatchIn(textToSend)
            GuardEngine.sendAlertFromChild(
                this@IgAccessibilityService,
                reason = if (isSexual) "sexual_or_grooming_content" else "stranger_message",
                sender = "子女留言/限時動態回覆 (@$targetAuthor)",
                text = textToSend
            )
            if (isSexual && !GuardEngine.isTemporarilyUnlocked(this@IgAccessibilityService)) {
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
        }
        pendingReplyText = ""
    }

    private data class ScreenNodeItem(
        val text: String,
        val hintText: String,
        val top: Int,
        val bottom: Int,
        val left: Int,
        val right: Int,
        val isEditable: Boolean,
        val isFocused: Boolean
    )

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

        val pkg = event?.packageName?.toString() ?: return
        if (pkg != GuardEngine.INSTAGRAM_PKG) return

        val now = System.currentTimeMillis()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && now - lastScanMs < 450L) {
            return
        }
        lastScanMs = now
        GuardEngine.cpAccConnected = true
        GuardEngine.cpLastAccTime = GuardEngine.nowTimeStr()

        GuardEngine.checkAndSendChildHeartbeatIfDue(this)
        if (now - lastRemoteSyncMs > 45_000L) {
            lastRemoteSyncMs = now
            thread { GuardEngine.pollChannelOnce(applicationContext, since = "15m") }
        }

        val root = rootInActiveWindow ?: return
        val dmUnlocked = GuardEngine.isTemporarilyUnlocked(this)

        val metrics = resources.displayMetrics
        val screenHeight = metrics.heightPixels.coerceAtLeast(1200)
        val screenWidth = metrics.widthPixels.coerceAtLeast(720)

        val items = ArrayList<ScreenNodeItem>(90)
        collectScreenNodes(root, items, depth = 0)
        if (items.isEmpty()) return

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

        if (!dmUnlocked && isExplicitMuteScreen) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            GuardEngine.sendAlertFromChild(
                this,
                "tamper_attempt",
                "IG Mute Settings",
                "Child tried to mute Instagram DM notifications."
            )
            return
        }

        // B. Issue #1 Fix: Auto-detect child's own Instagram handle when they are on their Profile page
        val isOwnProfileScreen = items.any { item ->
            val t = item.text.trim()
            t.equals("Edit profile", ignoreCase = true) ||
                t == "編輯個人檔案" ||
                t.equals("Share profile", ignoreCase = true) ||
                t == "分享個人檔案"
        }
        if (isOwnProfileScreen) {
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

        // Identify top header handle (top 20% of screen)
        val headerCandidates = items.filter { item ->
            val clean = item.text.trim().removePrefix("@")
            val low = clean.lowercase(Locale.US)
            !item.isEditable &&
                item.bottom <= (screenHeight * 0.20).toInt() &&
                clean.length in 2..30 &&
                !clean.contains(" ") &&
                !clean.all { it.isDigit() } &&
                low !in GuardEngine.RESERVED_UI_HEADERS &&
                !GuardEngine.isNonMessageUiString(clean) &&
                !GuardEngine.isChildSelfHandle(this, clean)
        }.sortedBy { it.top }

        val topHeaderSender = headerCandidates.firstOrNull()?.text?.trim()?.removePrefix("@") ?: ""

        // Check if bottom navigation bar (Home/Search/Reels/Profile tabs) is visible -> If visible, user is on Feed/Reels/Explore/Profile, NOT inside a DM chat!
        val hasBottomNavTabs = items.count { item ->
            item.top >= (screenHeight * 0.88).toInt() &&
                item.text.trim().lowercase(Locale.US) in setOf("home", "search", "explore", "reels", "profile", "首頁", "搜尋", "探索", "連續短片", "個人檔案")
        } >= 2

        // Check if DM Message Composer Bar is visible in the bottom 25% of the screen
        val dmComposerNode = items.firstOrNull { item ->
            item.top >= (screenHeight * 0.72).toInt() &&
                (item.isEditable ||
                    item.text.startsWith("Message...", ignoreCase = true) ||
                    item.text.startsWith("發送訊息") ||
                    item.text.startsWith("傳送訊息") ||
                    item.hintText.startsWith("Message...", ignoreCase = true) ||
                    item.hintText.startsWith("發送訊息") ||
                    item.hintText.startsWith("傳送訊息"))
        }

        // Check if Story / Post Comments sheet is open
        val isCommentsOrStoryReplySheet = items.any { item ->
            val t = item.text.trim()
            val h = item.hintText.trim()
            t.equals("Comments", ignoreCase = true) ||
                t == "留言" ||
                t.startsWith("Add a comment", ignoreCase = true) ||
                t.startsWith("新增留言") ||
                t.startsWith("Reply to ", ignoreCase = true) ||
                t.startsWith("回覆") ||
                h.startsWith("Add a comment", ignoreCase = true) ||
                h.startsWith("新增留言") ||
                h.startsWith("Reply", ignoreCase = true) ||
                h.startsWith("回覆")
        }

        // ================= CONTEXT 1: STORY / POST COMMENTS REPLY (ONLY READ CHILD'S TYPED REPLY!) =================
        if (isCommentsOrStoryReplySheet) {
            val activeReplyEdit = items.firstOrNull { item ->
                item.isEditable && item.text.isNotEmpty() &&
                    item.text != item.hintText &&
                    !GuardEngine.isNonMessageUiString(item.text)
            }
            if (activeReplyEdit != null) {
                val typed = activeReplyEdit.text.trim()
                if (typed.length >= 2) {
                    pendingReplyText = typed
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
        val isStrictlyInsideDmChat = !hasBottomNavTabs && dmComposerNode != null && topHeaderSender.isNotEmpty()
        if (!isStrictlyInsideDmChat) {
            // Issue #4 Fix: User is scrolling Home Feed, Reels, Explore, Stories, or DM Inbox list -> DO NOT read middle-of-screen texts!
            return
        }

        // Record anything the child is currently typing in the DM composer EditText so we never alert on the child's own sent messages!
        items.filter { it.isEditable && it.text.isNotEmpty() && it.text != it.hintText }.forEach { editNode ->
            GuardEngine.recordChildTypedDmText(editNode.text)
        }

        // Block child from staying in a DM session with any flagged sexual/grooming offender
        if (!dmUnlocked && GuardEngine.isFlaggedOffender(this, topHeaderSender)) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            return
        }

        // Issue #2 & #4 Fix: Strictly read ONLY incoming left-aligned DM bubbles in the lower chat zone (55%..82% height):
        // - Left edge must start near the left margin (<= 24% width)
        // - Right edge must NOT reach the right-aligned outgoing margin (<= 82% width)
        // - Horizontal midpoint must sit on the left half of the screen (centerX <= 50% width)
        // - Must NOT match any text recently typed/sent by the child
        val dmZoneTop = (screenHeight * 0.55).toInt()
        val dmZoneBottom = (dmComposerNode!!.top - 10).coerceAtMost((screenHeight * 0.84).toInt())
        val maxLeftEdge = (screenWidth * 0.24).toInt()
        val maxRightEdge = (screenWidth * 0.82).toInt()
        val maxCenterX = (screenWidth * 0.50).toInt()

        val incomingLeftBubbles = items.filter { item ->
            val centerX = (item.left + item.right) / 2
            !item.isEditable &&
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
                text = sexualHit.text
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
                    text = newestIncoming
                )
                if (reason == "sexual_or_grooming_content" && !dmUnlocked) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun collectScreenNodes(node: AccessibilityNodeInfo?, out: MutableList<ScreenNodeItem>, depth: Int) {
        if (node == null || out.size >= 90 || depth > 28) return
        try {
            if (!node.isVisibleToUser) return
            val rawText = (node.text ?: node.contentDescription)?.toString()?.trim() ?: ""
            val rawHint = if (Build.VERSION.SDK_INT >= 26) (node.hintText?.toString()?.trim() ?: "") else ""
            if (rawText.isNotEmpty() || node.isEditable) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                out.add(
                    ScreenNodeItem(
                        text = rawText,
                        hintText = rawHint,
                        top = rect.top,
                        bottom = rect.bottom,
                        left = rect.left,
                        right = rect.right,
                        isEditable = node.isEditable,
                        isFocused = node.isFocused
                    )
                )
            }
            val count = node.childCount
            for (i in 0 until count) {
                val child = node.getChild(i) ?: continue
                try {
                    collectScreenNodes(child, out, depth + 1)
                } finally {
                    if (Build.VERSION.SDK_INT < 33) {
                        try { child.recycle() } catch (_: Exception) {}
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    override fun onInterrupt() {
        debounceHandler.removeCallbacksAndMessages(null)
        pendingReplyText = ""
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
        @Volatile private var activeConnection: HttpsURLConnection? = null
        private val streamGeneration = AtomicInteger(0)

        fun reconnectActiveStream() {
            streamGeneration.incrementAndGet()
            try {
                activeConnection?.disconnect()
            } catch (_: Exception) {
            }
            activeConnection = null
        }
    }

    @Volatile private var running = false
    private var notifSeq = 100

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

        if (!running) {
            running = true
            thread {
                while (running && GuardEngine.isParentMode(applicationContext)) {
                    val myGen = streamGeneration.get()
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

                    val relaySession = GuardEngine.captureRelaySession(applicationContext)
                    val topic = GuardEngine.getChannelTopicForToken(relaySession.second)
                    var conn: HttpsURLConnection? = null
                    try {
                        GuardEngine.cpParentStreamState = "Connecting TLS stream..."
                        val url = URL("https://ntfy.sh/$topic/json?since=2h")
                        conn = (url.openConnection() as HttpsURLConnection).apply {
                            requestMethod = "GET"
                            connectTimeout = 10_000
                            readTimeout = 90_000
                        }
                        activeConnection = conn
                        val code = conn.responseCode
                        if (code in 200..299) {
                            GuardEngine.cpParentStreamState = "TLS + AES-256-GCM E2EE Active 🔒"
                            GuardEngine.addCheckpointLog(applicationContext, "CP4-STREAM", "Connected to encrypted TLS stream")
                            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                                var line: String? = null
                                while (running && streamGeneration.get() == myGen &&
                                    GuardEngine.isParentMode(applicationContext) &&
                                    reader.readLine().also { line = it } != null
                                ) {
                                    GuardEngine.processIncomingRelayLine(
                                        applicationContext,
                                        line!!,
                                        "parent",
                                        { alert -> fireNativeParentNotification(alert) },
                                        relaySession.second,
                                        relaySession.third
                                    )
                                }
                            }
                        } else {
                            GuardEngine.cpParentStreamState = "HTTPS $code (Retrying in 15s)"
                            GuardEngine.addCheckpointLog(applicationContext, "CP4-ERR", "Stream returned HTTPS $code")
                        }
                    } catch (e: Exception) {
                        if (running && streamGeneration.get() == myGen) {
                            GuardEngine.cpParentStreamState = "Reconnecting TLS (${e.javaClass.simpleName})..."
                        }
                    } finally {
                        activeConnection = null
                        try { conn?.disconnect() } catch (_: Exception) {}
                    }
                    if (running && streamGeneration.get() == myGen) {
                        try {
                            Thread.sleep(15_000L)
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        }
        return START_STICKY
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
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val reason = alert.optString("reason", "alert")
        val sender = alert.optString("sender", "unknown")
        val text = alert.optString("text", "")

        val title = when (reason) {
            "sexual_or_grooming_content" -> "🚨 IG 色情/誘導訊息警告: @$sender"
            "tamper_attempt" -> "⚠️ 防篡改/離線警告: $sender"
            "stranger_message" -> "💬 IG 陌生人私訊/留言回覆: @$sender"
            "heartbeat_ok" -> "💓 子女手機定時連線回報 (30–95m)"
            "pairing_verified" -> "✅ 子女手機已成功配對並啟動保護"
            else -> "🔔 IG Guard 通知: $sender"
        }

        val openIntent = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notif = Notification.Builder(this, "ig_guard_alerts")
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText("來源帳號: $sender\n訊息內容: $text"))
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .build()

        nm.notify(notifSeq++, notif)
    }

    override fun onDestroy() {
        running = false
        reconnectActiveStream()
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
        background = ripple(t.bgCanvas, 10)
        contentDescription = description
        setPadding(dp(12), dp(12), dp(12), dp(12))
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        setOnClickListener(sessionClick(onClick))
    }
    private fun ripple(fill: String, radius: Int = 10, border: String? = null): RippleDrawable {
        val accent = color(currentTheme().accentSeafoam)
        return RippleDrawable(ColorStateList.valueOf((accent and 0x00ffffff) or 0x18000000), background(fill, radius, border), background("#FFFFFF", radius))
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
            uiHandler.post {
                if (!activityVisible) return@post
                val changed = renderedSession != GuardEngine.captureRelaySession(this@MainActivity)
                if (changed) syncRoleServices()
                updateStatus()
                if (changed || (currentFocus !is EditText && section in setOf("overview", "alerts"))) renderUi()
            }
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
        savedInstanceState?.getBundle("drafts")?.let { b -> for (key in b.keySet()) inputDrafts[key] = b.getString(key) ?: "" }
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
        outState.putBundle("drafts", Bundle().apply { for ((key, value) in inputDrafts) putString(key, value) })
        super.onSaveInstanceState(outState)
    }
    @Deprecated("Activity back navigation")
    override fun onBackPressed() {
        when {
            section.endsWith("_setup") -> navigateTo("role")
            section == "role" -> super.onBackPressed()
            section != "overview" -> navigateTo("overview")
            else -> navigateTo("role")
        }
    }
    override fun onNewIntent(intent: Intent?) { super.onNewIntent(intent); handleDeepLinkIntent(intent) }
    private fun handleDeepLinkIntent(intent: Intent?) {
        val parsed = GuardEngine.parsePairingString(intent?.dataString ?: return) ?: return
        if (GuardEngine.isChildMode(this) && !GuardEngine.isTemporarilyUnlocked(this)) {
            GuardEngine.sendAlertFromChild(this, "tamper_attempt", "Deep-Link Guard", "Blocked unauthorized attempt to overwrite family pairing via igguard:// link while locked.", bypassDedupe = true)
            Toast.makeText(this, "子女保護鎖定中，請先由家長解鎖。", Toast.LENGTH_LONG).show(); return
        }
        dismissUiDialogs()
        GuardEngine.setFamilyToken(this, parsed.first); GuardEngine.setPin(this, parsed.second)
        GuardEngine.setRole(this, "child"); GuardEngine.markInitialSetupConfirmationSent(this, false)
        GuardEngine.scheduleNextRandomHeartbeat(this); section = "permissions"
        Toast.makeText(this, "已完成家庭配對。", Toast.LENGTH_LONG).show()
    }
    override fun onResume() {
        super.onResume(); activityVisible = true
        val filter = IntentFilter(GuardEngine.ACTION_UI_REFRESH)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(refreshReceiver, filter, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(refreshReceiver, filter)
        if (GuardEngine.isChildMode(this) && GuardEngine.isNotificationListenerEnabledStatic(this)) {
            try { NotificationListenerService.requestRebind(ComponentName(this, IgNotificationListener::class.java)) } catch (_: Exception) {}
        }
        syncRoleServices(); checkInitialPairingConfirmation(); renderUi()
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
                val ok = GuardEngine.constantTimeEquals(password.text.toString().trim(), GuardEngine.getPin(this@MainActivity))
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
                "$notification · $screen" + if (GuardEngine.isTemporarilyUnlocked(this)) " · 暫時解鎖中" else ""
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
                background = ripple(if (selected) t.bgChip else t.bgCard, 12, if (selected) t.accentSeafoam else t.borderSubtle)
                isClickable = true; isFocusable = true; isSelected = selected
                contentDescription = "$title。$description"
                tag = "entry_$role"; setOnClickListener { selectEntryRole(role) }
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
            GuardEngine.setPin(this, candidate); GuardEngine.getOrCreateFamilyToken(this); GuardEngine.setRole(this, "parent")
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
            GuardEngine.setFamilyToken(this, parsed.first); GuardEngine.setPin(this, parsed.second); GuardEngine.setRole(this, "child")
            GuardEngine.markInitialSetupConfirmationSent(this, false); GuardEngine.scheduleNextRandomHeartbeat(this)
            section = "permissions"; syncRoleServices(); renderUi(animate = true); openDirectNotificationToggle()
        }
        rootContainer.addView(actionButton("連結家庭", t.btnPrimaryBg, t.btnPrimaryText) { pair(invite.text.toString()) })
        rootContainer.addView(actionButton("從剪貼簿貼上並配對", t.btnSecondaryBg, t.btnSecondaryText) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            pair(cm?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: "")
        })
        rootContainer.addView(label("配對後，請與家長核對兩部手機上的 4 個安全圖案。", 13f, t.textSecondary, topPad = 18))
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
        val latest = GuardEngine.getParentAlerts(this).firstOrNull { it.optString("reason") !in setOf("heartbeat_ok", "pairing_verified", "test_alert") }
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
                background = ripple(if (chartMode == mode) t.bgChip else t.bgCard, 7)
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
                addView(label("解鎖碼已透過現有加密通道同步，有效時間依既有規則處理。", 13f, t.textSecondary, bottomPad = 18))
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
        "unlock_used" -> "子女使用了臨時解鎖碼"
        else -> "家庭守護通知"
    }
    private fun renderParentAlerts(t: ThemeColors) {
        heading("每一則提醒，都有脈絡。", "先了解，再與孩子好好聊聊。", t)
        val filters = LinearLayout(this)
        for ((key, title) in listOf("all" to "全部", "high" to "高危", "stranger" to "陌生人", "device" to "裝置")) {
            filters.addView(Button(this).apply {
                text = title; textSize = 12f; typeface = font(alertFilter == key); isAllCaps = false; minimumHeight = dp(48)
                setTextColor(color(if (alertFilter == key) t.btnPrimaryText else t.textSecondary))
                background = ripple(if (alertFilter == key) t.btnPrimaryBg else t.bgCanvas, 7)
                isSelected = alertFilter == key; setOnClickListener { alertFilter = key; renderUi() }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        rootContainer.addView(filters)
        val alerts = GuardEngine.getParentAlerts(this).filter { alert -> when (alertFilter) {
            "high" -> alert.optString("reason") == "sexual_or_grooming_content"
            "stranger" -> alert.optString("reason") == "stranger_message"
            "device" -> alert.optString("reason") in setOf("tamper_attempt", "heartbeat_ok", "pairing_verified", "unlock_used")
            else -> true
        } }
        if (alerts.isEmpty()) rootContainer.addView(label("暫時沒有提醒。\n需要你留意的訊息，會出現在這裡。", 15f, t.textSecondary, topPad = 28, bottomPad = 24))
        for (alert in alerts) {
            val reason = alert.optString("reason")
            val tint = if (reason == "sexual_or_grooming_content") t.warmCoral else if (reason == "tamper_attempt") t.warmHoney else t.textPrimary
            rootContainer.addView(label(alert.optString("time"), 12f, t.textMuted, topPad = 18, bottomPad = 7))
            rootContainer.addView(row(if (reason == "sexual_or_grooming_content") "shield-warning" else "chat-circle", alertTitle(reason), alert.optString("sender", "未識別來源"), t, tint) { showAlertDetails(alert, t) })
        }
        rootContainer.addView(textAction("立即刷新最新提醒") { thread { GuardEngine.pollChannelOnce(applicationContext, since = "6h") }; Toast.makeText(this, "正在同步加密提醒。", Toast.LENGTH_SHORT).show() })
        if (GuardEngine.getParentAlerts(this).isNotEmpty()) rootContainer.addView(textAction("清除收件匣記錄") {
            alert().setTitle("清除收件匣？").setMessage("圖表統計及去重記錄會保留。")
                .setNegativeButton("取消", null).setPositiveButton("清除") { _, _ -> GuardEngine.clearParentAlerts(this); renderUi() }.show()
        })
    }
    private fun showAlertDetails(alert: JSONObject, t: ThemeColors) {
        showSheet(alertTitle(alert.optString("reason"))) { dialog ->
            val reason = alert.optString("reason"); val sender = alert.optString("sender", "未識別來源")
            addView(label("$sender · ${alert.optString("time")}", 13f, t.textSecondary, bottomPad = 18))
            addView(label(alert.optString("text"), 16f, t.textPrimary, bottomPad = 22).apply { setTextIsSelectable(true) })
            val handle = sender.substringBefore(" ").trim().lowercase(Locale.US).removePrefix("@")
            if (reason in setOf("stranger_message", "sexual_or_grooming_content") && !sender.startsWith("子女留言") &&
                handle !in GuardEngine.getTrustedHandles(this@MainActivity) && !GuardEngine.isChildSelfHandle(this@MainActivity, handle) && !handle.contains(" ") && !handle.contains("instagram")) {
                addView(actionButton("加入信任好友並解除封鎖", t.btnSecondaryBg, t.btnSecondaryText) {
                    addTrustedSender(handle); GuardEngine.syncTrustedFromParent(this@MainActivity); dialog.dismiss()
                })
            }
            addView(actionButton("關閉", t.btnPrimaryBg, t.btnPrimaryText) { dialog.dismiss() })
        }
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
        rootContainer.addView(actionButton("查看保護狀態", t.btnPrimaryBg, t.btnPrimaryText) { navigateTo("permissions") })
        rootContainer.addView(row("key", "需要暫時解鎖？", "輸入家長提供的解鎖碼", t) { navigateTo("unlock") })
        rootContainer.addView(textAction("裝置及家庭詳情") {
            showSheet("裝置及家庭詳情") { dialog ->
                val handle = GuardEngine.getChildSelfHandle(this@MainActivity)
                val offenders = GuardEngine.getFlaggedOffenders(this@MainActivity)
                addView(label("家庭核對圖案：${GuardEngine.getSafetyEmojis(this@MainActivity)}\n\nInstagram 帳號：${if (handle.isEmpty()) "等待自動識別" else "@$handle"}\n\n高危帳號：${if (offenders.isEmpty()) "暫無記錄" else offenders.joinToString(", ") { "@$it" }}", 14f, t.textPrimary, bottomPad = 20))
                addView(actionButton("關閉", t.btnPrimaryBg, t.btnPrimaryText) { dialog.dismiss() })
            }
        })
    }
    private fun renderChildPermissions(t: ThemeColors) {
        heading("每個設定，都說清楚。", "了解每項權限的用途，再前往 Android 設定確認。", t)
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
        rootContainer.addView(textAction("立即發送測試及心跳回報") { GuardEngine.checkAndSendChildHeartbeatIfDue(this, force = true); Toast.makeText(this, "正在發送加密回報。", Toast.LENGTH_SHORT).show() })
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
                GuardEngine.constantTimeEquals(entered, GuardEngine.getPin(this)) -> {
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
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(0, 0, dp(8), 0) })
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
        renderCollapsibleDiagnostics(t)
        renderRoleSwitchFooter(t, GuardEngine.isChildMode(this))
    }
    private fun renderCollapsibleDiagnostics(t: ThemeColors) {
        rootContainer.addView(row("pulse", "連接與診斷", "查看最新裝置連接狀態", t) {
            showSheet("連接與診斷") { dialog ->
                addView(label("加密通道：${GuardEngine.cpParentStreamState}\n\n最新傳送：${GuardEngine.cpLastHttpStatus}", 14f, t.textPrimary, bottomPad = 18))
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
                    GuardEngine.rotateKeyAndNotifyChild(this@MainActivity, resetChildToUnconfigured = false); syncRoleServices()
                    Toast.makeText(this@MainActivity, "已更新金鑰並發送同步指令。", Toast.LENGTH_LONG).show(); dialog.dismiss(); renderUi()
                })
                addView(actionButton("遠端解除子女保護並重設身份", t.btnSecondaryBg, t.btnSecondaryText) {
                    alert().setTitle("遠端重設子女手機？").setMessage("發送加密救援指令，暫時解除子女手機保護並返回身份設定。")
                        .setNegativeButton("取消", null).setPositiveButton("發送救援") { _, _ ->
                            GuardEngine.rotateKeyAndNotifyChild(this@MainActivity, resetChildToUnconfigured = true)
                            Toast.makeText(this@MainActivity, "已發送遠端救援指令。", Toast.LENGTH_LONG).show(); dialog.dismiss()
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
        setTextColor(color(t.textPrimary)); setHintTextColor(color(t.textMuted)); background = background(t.bgInput, 8, t.borderSubtle)
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
        setTextColor(color(hexText)); background = ripple(hexBg, 8, if (hexBg == currentTheme().btnSecondaryBg) currentTheme().borderSubtle else null)
        minimumHeight = dp(52); setPadding(dp(14), dp(13), dp(14), dp(13)); setOnClickListener(sessionClick(onClick))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(5), 0, dp(8)) }
    }
    private fun textAction(title: String, onClick: () -> Unit): Button = Button(this).apply {
        val t = currentTheme(); text = title; textSize = 13f; typeface = font(true); isAllCaps = false
        setTextColor(color(t.accentSeafoam)); background = ripple(t.bgCanvas, 8); minimumHeight = dp(48)
        gravity = Gravity.CENTER_VERTICAL or Gravity.START; setPadding(0, dp(10), 0, dp(6)); setOnClickListener(sessionClick(onClick))
        layoutParams = LinearLayout.LayoutParams(-1, -2)
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
