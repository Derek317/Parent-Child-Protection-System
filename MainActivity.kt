package app.family.igguard

import android.accessibilityservice.AccessibilityService
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import android.util.Base64
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
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
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.HttpsURLConnection
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * IG Guard v4.5 (Refined & Play-Protect-Clean Release Edition)
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
    private val pollLock = Any()

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
            setFamilyToken(ctx, newToken)
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
    fun setRole(ctx: Context, role: String) = prefs(ctx).edit().putString("role", role).apply()
    fun isChildMode(ctx: Context): Boolean = getRole(ctx) == "child"
    fun isParentMode(ctx: Context): Boolean = getRole(ctx) == "parent"

    fun getOrCreateFamilyToken(ctx: Context): String {
        val existing = prefs(ctx).getString("family_uuid_token", null)
        if (!existing.isNullOrBlank()) return existing
        val generated = UUID.randomUUID().toString()
        prefs(ctx).edit().putString("family_uuid_token", generated).apply()
        return generated
    }

    fun setFamilyToken(ctx: Context, token: String) {
        val clean = token.trim().lowercase(Locale.US)
        if (clean.isNotEmpty()) {
            prefs(ctx).edit().putString("family_uuid_token", clean).apply()
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
        val raw = prefs(ctx).getString("trusted_csv", "mum_hk,dad_hk,school_friend_1") ?: ""
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

    fun pollChannelOnce(ctx: Context, since: String = "30m", onNewAlertForParent: ((JSONObject) -> Unit)? = null) = synchronized(pollLock) {
        val role = getRole(ctx)
        if (role == "unconfigured") return
        val topic = getChannelTopic(ctx)
        try {
            val url = URL("https://ntfy.sh/$topic/json?poll=1&since=$since")
            val conn = (url.openConnection() as HttpsURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                cpLastHttpStatus = "${nowTimeStr()} -> HTTPS $code"
                conn.disconnect()
                return
            }

            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                var line: String? = null
                while (reader.readLine().also { line = it } != null) {
                    processIncomingRelayLine(ctx, line!!, role, onNewAlertForParent)
                }
            }
            cpLastHttpStatus = "${nowTimeStr()} -> HTTPS 200 (AES-256-GCM 🔒)"
            conn.disconnect()
        } catch (e: Exception) {
            cpLastHttpStatus = "${nowTimeStr()} -> TLS Err: ${e.javaClass.simpleName}"
        }
    }

    fun processIncomingRelayLine(
        ctx: Context,
        line: String,
        role: String,
        onNewAlertForParent: ((JSONObject) -> Unit)?
    ) = synchronized(pollLock) {
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
                    setFamilyToken(ctx, newToken)
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
                    val reason = inner.optString("reason")
                    if (reason == "heartbeat_ok") {
                        recordChildHeartbeatOnParent(ctx, inner.optString("text"))
                    }
                    saveAlertToParentHistory(ctx, inner)
                    addCheckpointLog(ctx, "CP4-RECV", "Decrypted $reason from ${inner.optString("sender")}")
                    onNewAlertForParent?.invoke(inner)
                    ctx.sendBroadcast(Intent(ACTION_UI_REFRESH).setPackage(ctx.packageName))
                }
            }
        }
    }

    private fun saveAlertToParentHistory(ctx: Context, alertObj: JSONObject) {
        val rawArr = prefs(ctx).getString("parent_alerts_json", "[]") ?: "[]"
        val arr = try { JSONArray(rawArr) } catch (_: Exception) { JSONArray() }
        val next = JSONArray()
        next.put(alertObj)
        for (i in 0 until minOf(arr.length(), 59)) {
            next.put(arr.getJSONObject(i))
        }
        prefs(ctx).edit().putString("parent_alerts_json", next.toString()).apply()
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

    fun clearParentAlerts(ctx: Context) {
        prefs(ctx).edit().putString("parent_alerts_json", "[]").apply()
    }

    fun isNotificationListenerEnabledStatic(ctx: Context): Boolean {
        val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
        val cn = ComponentName(ctx, IgNotificationListener::class.java).flattenToString()
        return flat.contains(cn)
    }

    fun isAccessibilityEnabledStatic(ctx: Context): Boolean {
        val flat = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val cn = ComponentName(ctx, IgAccessibilityService::class.java).flattenToString()
        return flat.contains(cn)
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
            heartbeatHandler.postDelayed(this, 60_000L)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        GuardEngine.cpNotifConnected = true
        GuardEngine.addCheckpointLog(this, "CP1-NOTIF", "IG NotificationListener CONNECTED ✅")
        heartbeatHandler.removeCallbacks(periodicCheckRunnable)
        heartbeatHandler.postDelayed(periodicCheckRunnable, 15_000L)
    }

    override fun onListenerDisconnected() {
        GuardEngine.cpNotifConnected = false
        heartbeatHandler.removeCallbacks(periodicCheckRunnable)
        GuardEngine.addCheckpointLog(this, "CP1-NOTIF", "NotificationListener DISCONNECTED ❌")
        if (Build.VERSION.SDK_INT >= 24) {
            try {
                requestRebind(ComponentName(this, IgNotificationListener::class.java))
            } catch (_: Exception) {
            }
        }
        super.onListenerDisconnected()
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
        GuardEngine.cpAccConnected = true
        GuardEngine.grantTemporaryUnlock(this, 60_000L)
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

    override fun onInterrupt() {}
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
                            put("sender", "🚨 子女手機離線/解除安裝警告")
                            put("text", "已超過 100 分鐘未收到子女手機嘅定時心跳回報！子女可能已熄機、斷網或解除安裝 IG Guard。")
                            put("time", GuardEngine.nowDateTimeStr())
                        }
                        fireNativeParentNotification(missingAlert)
                    }

                    val topic = GuardEngine.getChannelTopic(applicationContext)
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
                                        "parent"
                                    ) { alert ->
                                        fireNativeParentNotification(alert)
                                    }
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

// 4. Warm, Family-Friendly UI with Dual Palettes from palette.pdf (#000100, #A1A6B4, #94C5CC, #B4D2E7, #F8F8F8) & Streamlined 1-Tap Setup
class MainActivity : Activity() {

    private lateinit var scrollView: ScrollView
    private lateinit var rootContainer: LinearLayout
    private val uiHandler = Handler(Looper.getMainLooper())
    private var showAdvancedLogs: Boolean = false
    private var displayed15MinCode: String = ""

    private data class ThemeColors(
        val bgCanvas: String,
        val bgCard: String,
        val bgHero: String,
        val bgInput: String,
        val bgChip: String,
        val borderSubtle: String,
        val textPrimary: String,
        val textSecondary: String,
        val textMuted: String,
        val accentSeafoam: String,
        val accentPowderSky: String,
        val btnPrimaryBg: String,
        val btnPrimaryText: String,
        val btnSecondaryBg: String,
        val btnSecondaryText: String,
        val warmHoney: String,
        val warmCoral: String
    )

    private fun currentTheme(): ThemeColors {
        return if (GuardEngine.isBrightTheme(this)) {
            ThemeColors(
                bgCanvas = "#F8F8F8",
                bgCard = "#FFFDF9",
                bgHero = "#EAF4F7",
                bgInput = "#F4F1EA",
                bgChip = "#EDF6F9",
                borderSubtle = "#B4D2E7",
                textPrimary = "#000100",
                textSecondary = "#525A65",
                textMuted = "#A1A6B4",
                accentSeafoam = "#2E7782",
                accentPowderSky = "#B4D2E7",
                btnPrimaryBg = "#94C5CC",
                btnPrimaryText = "#000100",
                btnSecondaryBg = "#B4D2E7",
                btnSecondaryText = "#000100",
                warmHoney = "#E08E45",
                warmCoral = "#D95D39"
            )
        } else {
            ThemeColors(
                bgCanvas = "#121518",
                bgCard = "#1D2227",
                bgHero = "#232B32",
                bgInput = "#000100",
                bgChip = "#26313A",
                borderSubtle = "#36404A",
                textPrimary = "#F8F8F8",
                textSecondary = "#B4D2E7",
                textMuted = "#A1A6B4",
                accentSeafoam = "#94C5CC",
                accentPowderSky = "#B4D2E7",
                btnPrimaryBg = "#94C5CC",
                btnPrimaryText = "#000100",
                btnSecondaryBg = "#2B343D",
                btnSecondaryText = "#F8F8F8",
                warmHoney = "#F2A65A",
                warmCoral = "#E76F51"
            )
        }
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            uiHandler.post {
                if (currentFocus is EditText) return@post
                renderUi(animate = false)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        scrollView = ScrollView(this).apply {
            isFillViewport = true
        }
        rootContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(44, 52, 44, 84)
        }
        scrollView.addView(rootContainer)
        setContentView(scrollView)

        handleDeepLinkIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleDeepLinkIntent(intent)
    }

    private fun handleDeepLinkIntent(intent: Intent?) {
        val dataStr = intent?.dataString ?: return
        val parsed = GuardEngine.parsePairingString(dataStr) ?: return
        if (GuardEngine.isChildMode(this) && !GuardEngine.isTemporarilyUnlocked(this)) {
            GuardEngine.sendAlertFromChild(
                this,
                "tamper_attempt",
                "Deep-Link Guard",
                "Blocked unauthorized attempt to overwrite family pairing via igguard:// link while locked.",
                bypassDedupe = true
            )
            Toast.makeText(this, "⛔ 子女保護鎖定中，無法覆寫家庭配對！請先解鎖。", Toast.LENGTH_LONG).show()
            return
        }
        GuardEngine.setFamilyToken(this, parsed.first)
        GuardEngine.setPin(this, parsed.second)
        GuardEngine.setRole(this, "child")
        GuardEngine.markInitialSetupConfirmationSent(this, false)
        GuardEngine.scheduleNextRandomHeartbeat(this)
        Toast.makeText(this, "✅ 已透過專屬加密連結完成家庭配對！", Toast.LENGTH_LONG).show()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(GuardEngine.ACTION_UI_REFRESH)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(refreshReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(refreshReceiver, filter)
        }
        if (GuardEngine.isChildMode(this) && GuardEngine.isNotificationListenerEnabledStatic(this) && Build.VERSION.SDK_INT >= 24) {
            try {
                NotificationListenerService.requestRebind(ComponentName(this, IgNotificationListener::class.java))
            } catch (_: Exception) {
            }
        }
        syncRoleServices()
        checkInitialPairingConfirmation()
        renderUi(animate = true)
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(refreshReceiver)
        } catch (_: Exception) {
        }
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
            Toast.makeText(this, "🎉 IG Guard 私訊保護已啟動並通知家長手機！", Toast.LENGTH_LONG).show()
        }
    }

    // Issue #3 Fix: Opens IG Guard's single ON/OFF toggle page directly on Android 11+ (1 click instead of scrolling through 20 apps!)
    private fun openDirectNotificationToggle() {
        GuardEngine.grantTemporaryUnlock(this, 180_000L)
        val cn = ComponentName(this, IgNotificationListener::class.java)
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val directIntent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).apply {
                    putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, cn.flattenToString())
                }
                startActivity(directIntent)
                return
            } catch (_: Exception) {
            }
        }
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    private fun openAccessibilityToggle() {
        GuardEngine.grantTemporaryUnlock(this, 180_000L)
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun openAppDetailsSettings() {
        GuardEngine.grantTemporaryUnlock(this, 180_000L)
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        Toast.makeText(this, "💡 點選右上角「⋮」→「允許受限設定」即可", Toast.LENGTH_LONG).show()
        startActivity(intent)
    }

    private fun syncRoleServices() {
        if (GuardEngine.isParentMode(this)) {
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 101)
            }
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

    private fun renderUi(animate: Boolean = false) {
        val savedScrollY = scrollView.scrollY
        val t = currentTheme()
        scrollView.setBackgroundColor(Color.parseColor(t.bgCanvas))
        rootContainer.removeAllViews()
        val role = GuardEngine.getRole(this)
        val isBright = GuardEngine.isBrightTheme(this)

        val heroHeader = makeCard(t, isHero = true)
        heroHeader.addView(label("🛡️ IG Guard v4.5", 23f, t.textPrimary, bold = true, bottomPad = 4))
        val badgeText = when (role) {
            "parent" -> "🏡 家長守護中心 • 核對圖案: ${GuardEngine.getSafetyEmojis(this)}"
            "child" -> "🌱 子女守護模式 🔒 • 核對圖案: ${GuardEngine.getSafetyEmojis(this)}"
            else -> "溫暖家庭 Instagram 守護系統 • 請選擇此部手機嘅身份"
        }
        heroHeader.addView(label(badgeText, 13.5f, t.accentSeafoam, bold = true, bottomPad = 4))
        heroHeader.addView(label("Co-designed with @citreae_313 • Palette: Morning Harbor / Cozy Lantern", 11.5f, t.textMuted, bottomPad = 12))

        heroHeader.addView(
            actionButton(
                if (isBright) "🌙 切換至「夜間護眼暖色模式」(Cozy Lantern)" else "☀️ 切換至「日光溫暖模式」(Morning Harbor)",
                t.btnSecondaryBg,
                t.btnSecondaryText
            ) {
                GuardEngine.setBrightTheme(this, !isBright)
                renderUi(animate = true)
            }
        )
        rootContainer.addView(heroHeader)

        when (role) {
            "unconfigured" -> renderEasyOnboardingScreen(t)
            "parent" -> {
                renderParentDashboard(t)
                renderCollapsibleDiagnostics(t)
                renderRoleSwitchFooter(t, requirePin = false)
            }
            "child" -> {
                renderChildDashboard(t)
                renderCollapsibleDiagnostics(t)
                renderRoleSwitchFooter(t, requirePin = true)
            }
        }

        if (animate) {
            rootContainer.alpha = 0.2f
            rootContainer.translationY = 24f
            AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(rootContainer, View.ALPHA, 0.2f, 1f),
                    ObjectAnimator.ofFloat(rootContainer, View.TRANSLATION_Y, 24f, 0f)
                )
                duration = 260L
                interpolator = DecelerateInterpolator()
                start()
            }
        } else if (savedScrollY > 0) {
            scrollView.post { scrollView.scrollTo(0, savedScrollY) }
        }
    }

    private fun renderEasyOnboardingScreen(t: ThemeColors) {
        val parentCard = makeCard(t)
        parentCard.addView(label("🏡 我是家長 (Parent Phone)", 19f, t.accentSeafoam, bold = true, bottomPad = 8))
        parentCard.addView(
            label(
                "全天候加密接收子女 Instagram 嘅陌生人私訊、留言回覆及色情誘導警告；子女 IG 帳號會由子女手機自動偵測並同步，無需手動輸入。",
                13f,
                t.textSecondary,
                bottomPad = 14
            )
        )
        val pwInput = inputBox(t, "設定高強度家長主密碼 (最少 8 位英文字母+數字)", GuardEngine.getPin(this), isPassword = false)
        parentCard.addView(pwInput)

        parentCard.addView(
            actionButton("🏡 設定為「家長手機」並產生配對邀請", t.btnPrimaryBg, t.btnPrimaryText) {
                val candidate = pwInput.text.toString().trim()
                if (!GuardEngine.isStrongPasswordValid(candidate)) {
                    Toast.makeText(this, "⚠️ 主密碼必須最少 8 個字元，並同時包含英文字母及數字！", Toast.LENGTH_LONG).show()
                    return@actionButton
                }
                GuardEngine.setPin(this, candidate)
                GuardEngine.getOrCreateFamilyToken(this)
                GuardEngine.setRole(this, "parent")
                syncRoleServices()
                renderUi(animate = true)
            }
        )
        rootContainer.addView(parentCard)

        val childCard = makeCard(t)
        childCard.addView(label("🌱 這是子女手機 (Child Phone)", 19f, t.warmHoney, bold = true, bottomPad = 8))
        childCard.addView(
            label(
                "先喺家長手機按「傳送配對邀請」去子女 WhatsApp / SMS，喺子女手機【複製】該段訊息，然後按下方按鈕即可一鍵完成配對！",
                13f,
                t.textSecondary,
                bottomPad = 14
            )
        )

        childCard.addView(
            actionButton("📋 一鍵貼上家長配對邀請並啟動保護", t.btnPrimaryBg, t.btnPrimaryText) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clipText = cm?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: ""
                val parsed = GuardEngine.parsePairingString(clipText)
                if (parsed != null) {
                    GuardEngine.setFamilyToken(this, parsed.first)
                    GuardEngine.setPin(this, parsed.second)
                    GuardEngine.setRole(this, "child")
                    GuardEngine.markInitialSetupConfirmationSent(this, false)
                    GuardEngine.scheduleNextRandomHeartbeat(this)
                    syncRoleServices()
                    renderUi(animate = true)
                    openDirectNotificationToggle()
                } else {
                    Toast.makeText(
                        this,
                        "⚠️ 剪貼簿未搵到配對碼！請先喺 WhatsApp/SMS 複製家長傳來嘅配對訊息，或在下方手動貼上。",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )

        val manualInput = inputBox(t, "或在此貼上家長傳來嘅配對訊息 / 專屬金鑰", "")
        childCard.addView(manualInput)
        childCard.addView(
            actionButton("🔗 使用上方貼上嘅內容配對子女手機", t.btnSecondaryBg, t.btnSecondaryText) {
                val parsed = GuardEngine.parsePairingString(manualInput.text.toString())
                if (parsed != null) {
                    GuardEngine.setFamilyToken(this, parsed.first)
                    GuardEngine.setPin(this, parsed.second)
                    GuardEngine.setRole(this, "child")
                    GuardEngine.markInitialSetupConfirmationSent(this, false)
                    GuardEngine.scheduleNextRandomHeartbeat(this)
                    syncRoleServices()
                    renderUi(animate = true)
                    openDirectNotificationToggle()
                } else {
                    Toast.makeText(this, "格式不正確，請複製家長手機發出嘅完整配對邀請訊息", Toast.LENGTH_LONG).show()
                }
            }
        )
        rootContainer.addView(childCard)
    }

    // ================= PARENT MODE UI =================
    private fun renderParentDashboard(t: ThemeColors) {
        val hbOverdue = GuardEngine.isChildHeartbeatOverdue(this)
        val hbCard = makeCard(t)
        hbCard.addView(
            label(
                if (hbOverdue) "🚨 警告：超過 100 分鐘未收到子女手機心跳！" else "💓 子女手機連線及防移除監測 (30–95 分鐘隨機心跳)",
                17f,
                if (hbOverdue) t.warmCoral else t.accentSeafoam,
                bold = true,
                bottomPad = 8
            )
        )
        hbCard.addView(
            label(
                "• 最後心跳回報：${GuardEngine.getLastChildHeartbeatInfo(this)}\n" +
                    "• 加密通道狀態：${GuardEngine.cpParentStreamState}\n" +
                    "• 家長主密碼 (請妥善保存)：${GuardEngine.getPin(this)}",
                13f,
                t.textSecondary,
                bottomPad = 8
            )
        )
        rootContainer.addView(hbCard)

        val unlockGenCard = makeCard(t)
        unlockGenCard.addView(label("🔑 產生 15 分鐘子女臨時解鎖碼 (6-Char Unlock Code)", 17f, t.warmHoney, bold = true, bottomPad = 8))
        unlockGenCard.addView(
            label(
                "無需透露您嘅家長主密碼！按下方按鈕即可產生一個 6 位英數「15 分鐘臨時解鎖碼」，讓子女暫時解鎖 15 分鐘。",
                13f,
                t.textSecondary,
                bottomPad = 12
            )
        )
        if (displayed15MinCode.isNotEmpty()) {
            unlockGenCard.addView(
                label(
                    "👉 當前 15 分鐘臨時解鎖碼：$displayed15MinCode",
                    20f,
                    t.warmHoney,
                    bold = true,
                    bottomPad = 14
                )
            )
        }
        unlockGenCard.addView(
            actionButton("✨ 立即產生 6 位數 15 分鐘解鎖碼", t.warmHoney, "#FFFDF9") {
                val code = GuardEngine.generate15MinUnlockCode(this)
                displayed15MinCode = code
                GuardEngine.push15MinUnlockCodeFromParent(this, code)
                Toast.makeText(this, "✅ 已產生並加密同步 15 分鐘解鎖碼：$code", Toast.LENGTH_LONG).show()
                renderUi(animate = true)
            }
        )
        rootContainer.addView(unlockGenCard)

        val pairCard = makeCard(t)
        pairCard.addView(label("🔗 配對子女手機 (AES-256-GCM 端對端加密)", 17f, t.textPrimary, bold = true, bottomPad = 8))
        pairCard.addView(
            label(
                "• 家庭安全核對圖案：${GuardEngine.getSafetyEmojis(this)}",
                14f,
                t.textSecondary,
                bottomPad = 14
            )
        )

        pairCard.addView(
            actionButton("📲 傳送專屬配對邀請去子女手機 (WhatsApp / SMS)", t.btnPrimaryBg, t.btnPrimaryText) {
                val invite = GuardEngine.buildPairingInviteText(this)
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, invite)
                }
                startActivity(Intent.createChooser(sendIntent, "傳送配對邀請去子女手機"))
            }
        )

        pairCard.addView(
            actionButton("📋 複製專屬配對訊息 (Copy Pairing Invite)", t.btnSecondaryBg, t.btnSecondaryText) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("IGGuardInvite", GuardEngine.buildPairingInviteText(this)))
                Toast.makeText(this, "已複製！請透過 WhatsApp/SMS 發送給子女手機", Toast.LENGTH_LONG).show()
            }
        )
        rootContainer.addView(pairCard)

        // Issue #1 Fix: Removed redundant manual Child IG ID input box! Displays auto-detected child handle from Child Phone.
        val trustCard = makeCard(t)
        trustCard.addView(label("🤝 信任朋友白名單 & 自動偵測子女帳號", 17f, t.textPrimary, bold = true, bottomPad = 8))

        val autoDetectedChild = GuardEngine.getChildSelfHandle(this)
        trustCard.addView(
            label(
                "🧒 子女 Instagram 帳號 (由子女手機自動識別並同步)：" +
                    if (autoDetectedChild.isEmpty()) "等待子女手機收到首則 IG 通知或打開個人檔案自動同步 (例如 @zuck)" else "@$autoDetectedChild ✅",
                13.5f,
                t.accentSeafoam,
                bold = true,
                bottomPad = 12
            )
        )

        val currentHandles = GuardEngine.getTrustedHandles(this)
        trustCard.addView(
            label(
                "已批准嘅信任朋友名單 (點選下方標籤可快速移除)：",
                13f,
                t.textSecondary,
                bold = true,
                bottomPad = 8
            )
        )

        if (currentHandles.isNotEmpty()) {
            val chipScroll = HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
            }
            val chipRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, 16)
            }
            for (h in currentHandles) {
                val chipBg = GradientDrawable().apply {
                    setColor(Color.parseColor(t.bgChip))
                    cornerRadius = 999f
                    setStroke(2, Color.parseColor(t.borderSubtle))
                }
                val chipBtn = Button(this).apply {
                    text = "@$h  ✕"
                    textSize = 12f
                    isAllCaps = false
                    setTextColor(Color.parseColor(t.textPrimary))
                    background = chipBg
                    setPadding(28, 12, 28, 12)
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    lp.setMargins(0, 0, 16, 0)
                    layoutParams = lp
                    setOnClickListener {
                        val next = GuardEngine.getTrustedHandles(this@MainActivity).toMutableSet()
                        next.remove(h)
                        GuardEngine.setTrustedHandles(this@MainActivity, next)
                        GuardEngine.syncTrustedFromParent(this@MainActivity)
                        Toast.makeText(this@MainActivity, "已從白名單移除 @$h", Toast.LENGTH_SHORT).show()
                        renderUi(animate = true)
                    }
                }
                chipRow.addView(chipBtn)
            }
            chipScroll.addView(chipRow)
            trustCard.addView(chipScroll)
        }

        val handlesInput = inputBox(
            t,
            "以逗號分隔信任朋友帳號 (例如 mum_hk, dad_hk, school_friend_1)",
            currentHandles.joinToString(", ")
        )
        trustCard.addView(handlesInput)

        trustCard.addView(
            actionButton("💾 儲存並加密同步信任朋友名單", t.btnPrimaryBg, t.btnPrimaryText) {
                val updated = handlesInput.text.toString().split(",")
                GuardEngine.setTrustedHandles(this, updated)
                GuardEngine.syncTrustedFromParent(this)
                Toast.makeText(this, "✅ 已儲存並加密同步去子女手機！", Toast.LENGTH_SHORT).show()
                renderUi(animate = true)
            }
        )
        rootContainer.addView(trustCard)

        val inboxCard = makeCard(t)
        inboxCard.addView(label("📬 子女 IG 私訊及留言回覆警報收件匣", 17f, t.textPrimary, bold = true, bottomPad = 12))

        inboxCard.addView(
            actionButton("🔄 立即刷新最新警報 (Refresh Now)", t.btnSecondaryBg, t.btnSecondaryText) {
                Toast.makeText(this, "正在同步最新加密警報...", Toast.LENGTH_SHORT).show()
                thread { GuardEngine.pollChannelOnce(applicationContext, since = "6h") }
            }
        )

        val alerts = GuardEngine.getParentAlerts(this)
        if (alerts.isEmpty()) {
            inboxCard.addView(
                label(
                    "🌱 暫時未有警報。當子女收到陌生人私訊、色情誘導字眼，或在貼文輸入留言回覆時，系統會即時加密顯示喺呢度。",
                    14f,
                    t.textSecondary,
                    bottomPad = 8
                )
            )
        } else {
            for (alert in alerts) {
                val reason = alert.optString("reason", "alert")
                val sender = alert.optString("sender", "unknown")
                val text = alert.optString("text", "")
                val time = alert.optString("time", "")

                val itemBox = makeAlertItemBox(t, reason)
                val badge = when (reason) {
                    "sexual_or_grooming_content" -> "🔴 高危色情 / 誘導訊息 (已封鎖子女對話，家長持續監測中)"
                    "tamper_attempt" -> "🟠 防篡改 / 離線警告"
                    "stranger_message" -> "🟡 陌生人 IG 私訊 / 子女留言回覆"
                    "heartbeat_ok" -> "💓 子女手機定時連線回報 (30–95m)"
                    "pairing_verified" -> "🟢 子女手機配對成功"
                    else -> "🔔 系統通知"
                }
                itemBox.addView(label("$badge  •  $time", 12f, t.textPrimary, bold = true, bottomPad = 4))
                itemBox.addView(label("發訊者 / 來源: @$sender", 14f, t.accentSeafoam, bold = true, bottomPad = 4))
                itemBox.addView(label("訊息內容: $text", 14f, t.textPrimary, bottomPad = 10))

                val cleanHandle = sender.substringBefore(" ").trim().lowercase(Locale.US).removePrefix("@")
                if ((reason == "stranger_message" || reason == "sexual_or_grooming_content") &&
                    !sender.startsWith("子女留言") &&
                    cleanHandle !in currentHandles &&
                    !GuardEngine.isChildSelfHandle(this, cleanHandle) &&
                    !cleanHandle.contains(" ") &&
                    !cleanHandle.contains("instagram")
                ) {
                    itemBox.addView(
                        actionButton("＋ 將 @$cleanHandle 加入信任好友並解除封鎖", t.btnSecondaryBg, t.btnSecondaryText) {
                            val next = GuardEngine.getTrustedHandles(this).toMutableSet()
                            next.add(cleanHandle)
                            GuardEngine.setTrustedHandles(this, next)
                            GuardEngine.syncTrustedFromParent(this)
                            Toast.makeText(this, "已將 @$cleanHandle 加入信任名單並同步", Toast.LENGTH_SHORT).show()
                            renderUi(animate = true)
                        }
                    )
                }
                inboxCard.addView(itemBox)
            }

            inboxCard.addView(
                actionButton("🗑️ 清除警報記錄 (Clear History)", t.btnSecondaryBg, t.btnSecondaryText) {
                    GuardEngine.clearParentAlerts(this)
                    renderUi(animate = true)
                }
            )
        }
        rootContainer.addView(inboxCard)
    }

    // ================= CHILD MODE UI (Streamlined <= 2 Click Setup) =================
    private fun renderChildDashboard(t: ThemeColors) {
        val notifOn = GuardEngine.isNotificationListenerEnabledStatic(this)
        val accOn = GuardEngine.isAccessibilityEnabledStatic(this)
        val unlockRemainingSec = GuardEngine.getUnlockRemainingSeconds(this)
        val lockoutRemainingSec = GuardEngine.checkPasswordLockoutRemainingSec(this)
        val offenders = GuardEngine.getFlaggedOffenders(this)
        val selfHandle = GuardEngine.getChildSelfHandle(this)

        val statusCard = makeCard(t)
        statusCard.addView(
            label(
                if (notifOn) "✅ IG 私訊保護運作中 🔒" else "⚡ 請點選下方按鈕一鍵開啟 IG 私訊保護",
                18f,
                if (notifOn) t.accentSeafoam else t.warmHoney,
                bold = true,
                bottomPad = 10
            )
        )
        statusCard.addView(
            label(
                "• 家庭安全核對圖案：${GuardEngine.getSafetyEmojis(this)}\n" +
                    "• 核心私訊通知保護 (1-Tap)：${if (notifOn) "已開啟 ✅ (無需受限設定)" else "未開啟 ❌"}\n" +
                    "• 進階畫面保護鎖 (攔截聊天室 & 留言回覆)：${if (accOn) "已開啟 ✅" else "選用 (未開啟)"}\n" +
                    "• 保護鎖狀態：${if (unlockRemainingSec > 0) "⏳ 已臨時解鎖（剩餘 ${unlockRemainingSec / 60} 分 ${unlockRemainingSec % 60} 秒）" else "已鎖定 🔒"}\n" +
                    "• 自動偵測子女 IG 帳號：${if (selfHandle.isEmpty()) "自動識別中 (收到通知或開啟個人檔案即自動同步)" else "@$selfHandle ✅"}\n" +
                    "• 已攔截高危帳號：${if (offenders.isEmpty()) "(無)" else offenders.joinToString(", ") { "@$it" }}",
                14f,
                t.textPrimary,
                bottomPad = 16
            )
        )

        if (!notifOn) {
            statusCard.addView(
                actionButton("🔔 一鍵開啟「IG 私訊通知保護」(只需點一下開關)", t.btnPrimaryBg, t.btnPrimaryText) {
                    openDirectNotificationToggle()
                }
            )
        }

        if (!accOn) {
            statusCard.addView(
                actionButton("🛡️ 開啟進階「聊天室攔截及留言回覆保護」", t.btnSecondaryBg, t.btnSecondaryText) {
                    openAccessibilityToggle()
                }
            )
            if (Build.VERSION.SDK_INT >= 33) {
                statusCard.addView(
                    actionButton("🔓 如進階開關顯示灰色：點此開啟「允許受限設定」", t.btnSecondaryBg, t.btnSecondaryText) {
                        openAppDetailsSettings()
                    }
                )
            }
        }

        statusCard.addView(
            actionButton("💓 立即發送測試及心跳回報去家長手機", t.btnPrimaryBg, t.btnPrimaryText) {
                Toast.makeText(this, "正在加密發送心跳及測試通知...", Toast.LENGTH_SHORT).show()
                GuardEngine.checkAndSendChildHeartbeatIfDue(this, force = true)
                uiHandler.postDelayed({ renderUi(animate = false) }, 1200L)
            }
        )
        rootContainer.addView(statusCard)

        val unlockCard = makeCard(t)
        unlockCard.addView(label("🔑 輸入家長 15 分鐘臨時解鎖碼 (6-Char Unlock Code)", 16f, t.warmHoney, bold = true, bottomPad = 8))
        if (lockoutRemainingSec > 0) {
            unlockCard.addView(
                label(
                    "⛔ 密碼錯誤次數過多！已鎖定輸入（剩餘 ${lockoutRemainingSec / 60} 分 ${lockoutRemainingSec % 60} 秒）",
                    14f,
                    t.warmCoral,
                    bold = true,
                    bottomPad = 12
                )
            )
        } else {
            unlockCard.addView(
                label(
                    "請輸入家長手機產生嘅 6 位英數「15 分鐘臨時解鎖碼」（或輸入 8+ 位家長主密碼解鎖 3 分鐘）：",
                    13f,
                    t.textSecondary,
                    bottomPad = 12
                )
            )
        }
        val codeInput = inputBox(t, "輸入 6 位臨時解鎖碼 或 家長主密碼", "", isPassword = true)
        unlockCard.addView(codeInput)

        unlockCard.addView(
            actionButton("🔓 驗證並臨時解鎖 (Verify & Unlock)", t.warmHoney, "#FFFDF9") {
                val lockRem = GuardEngine.checkPasswordLockoutRemainingSec(this)
                if (lockRem > 0) {
                    Toast.makeText(this, "⛔ 已鎖定，請等 ${lockRem} 秒後再試", Toast.LENGTH_LONG).show()
                    renderUi(animate = true)
                    return@actionButton
                }
                val entered = codeInput.text.toString().trim()
                if (GuardEngine.verify15MinUnlockCode(this, entered)) {
                    GuardEngine.recordPasswordAttempt(this, true)
                    GuardEngine.grantTemporaryUnlock(this, 15 * 60 * 1000L)
                    codeInput.setText("")
                    GuardEngine.sendAlertFromChild(
                        this,
                        "unlock_used",
                        "@${selfHandle.ifEmpty { "child" }}",
                        "子女手機已使用 6 位臨時解鎖碼解鎖 15 分鐘",
                        bypassDedupe = true
                    )
                    Toast.makeText(this, "✅ 已成功解鎖 15 分鐘！", Toast.LENGTH_LONG).show()
                    renderUi(animate = true)
                } else if (GuardEngine.constantTimeEquals(entered, GuardEngine.getPin(this))) {
                    GuardEngine.recordPasswordAttempt(this, true)
                    GuardEngine.grantTemporaryUnlock(this, 3 * 60 * 1000L)
                    codeInput.setText("")
                    Toast.makeText(this, "✅ 家長主密碼正確：已解鎖 3 分鐘", Toast.LENGTH_LONG).show()
                    renderUi(animate = true)
                } else {
                    GuardEngine.recordPasswordAttempt(this, false)
                    Toast.makeText(this, "❌ 解鎖碼或主密碼不正確！", Toast.LENGTH_SHORT).show()
                    renderUi(animate = true)
                }
            }
        )

        if (unlockRemainingSec > 0) {
            unlockCard.addView(
                actionButton("🔒 立即重新上鎖 (Re-Lock Now)", t.btnSecondaryBg, t.btnSecondaryText) {
                    GuardEngine.grantTemporaryUnlock(this, 0L)
                    Toast.makeText(this, "🔒 已重新啟動保護鎖", Toast.LENGTH_SHORT).show()
                    renderUi(animate = true)
                }
            )
        }
        rootContainer.addView(unlockCard)
    }

    private fun renderCollapsibleDiagnostics(t: ThemeColors) {
        val diagCard = makeCard(t)
        diagCard.addView(
            actionButton(
                if (showAdvancedLogs) "🔽 隱藏進階系統診斷紀錄" else "🔧 顯示進階系統診斷紀錄 (Checkpoints)",
                t.btnSecondaryBg,
                t.btnSecondaryText
            ) {
                showAdvancedLogs = !showAdvancedLogs
                renderUi(animate = true)
            }
        )
        if (showAdvancedLogs) {
            diagCard.addView(
                label(
                    "Last TLS/E2EE Status: ${GuardEngine.cpLastHttpStatus}\n\n" + GuardEngine.getCheckpointLogs(this),
                    12f,
                    t.textSecondary,
                    topPad = 8,
                    bottomPad = 12
                )
            )
            diagCard.addView(
                actionButton("🧹 清除診斷紀錄 (Clear Logs)", t.btnSecondaryBg, t.btnSecondaryText) {
                    GuardEngine.clearCheckpointLogs(this)
                }
            )
        }
        rootContainer.addView(diagCard)
    }

    private fun renderRoleSwitchFooter(t: ThemeColors, requirePin: Boolean) {
        val card = makeCard(t)
        card.addView(label("⚙️ 重設手機身份及遠端金鑰管理", 15f, t.textSecondary, bold = true, bottomPad = 8))
        if (requirePin) {
            card.addView(
                label(
                    "💡 如忘記家長主密碼，可在【家長手機】點選「🔓 遠端解除子女手機保護並重設」，子女手機會自動解鎖並返回初始畫面。",
                    12f,
                    t.accentSeafoam,
                    bottomPad = 10
                )
            )
            val pinInput = inputBox(t, "輸入 8+ 位家長主密碼以切換角色", "", isPassword = true)
            card.addView(pinInput)
            card.addView(
                actionButton("切換手機角色 / 重新配對 (需要家長主密碼)", t.btnSecondaryBg, t.btnSecondaryText) {
                    if (GuardEngine.checkPasswordLockoutRemainingSec(this) > 0) {
                        Toast.makeText(this, "⛔ 密碼輸入已鎖定，請從家長手機遠端重設", Toast.LENGTH_LONG).show()
                        return@actionButton
                    }
                    if (GuardEngine.constantTimeEquals(pinInput.text.toString().trim(), GuardEngine.getPin(this))) {
                        GuardEngine.recordPasswordAttempt(this, true)
                        GuardEngine.setRole(this, "unconfigured")
                        syncRoleServices()
                        renderUi(animate = true)
                    } else {
                        GuardEngine.recordPasswordAttempt(this, false)
                        Toast.makeText(this, "家長主密碼錯誤！", Toast.LENGTH_SHORT).show()
                        renderUi(animate = true)
                    }
                }
            )
        } else {
            card.addView(
                actionButton("🔄 更換全新家庭金鑰（自動同步更新子女手機金鑰）", t.btnPrimaryBg, t.btnPrimaryText) {
                    GuardEngine.rotateKeyAndNotifyChild(this, resetChildToUnconfigured = false)
                    syncRoleServices()
                    Toast.makeText(this, "✅ 已更換金鑰並透過加密通道自動更新子女手機！", Toast.LENGTH_LONG).show()
                    renderUi(animate = true)
                }
            )
            card.addView(
                actionButton("🔓 遠端解除子女手機保護並重設角色 (忘記密碼救援)", t.btnSecondaryBg, t.btnSecondaryText) {
                    GuardEngine.rotateKeyAndNotifyChild(this, resetChildToUnconfigured = true)
                    Toast.makeText(this, "✅ 已發送遠端解鎖及重設指令去子女手機！", Toast.LENGTH_LONG).show()
                }
            )
            card.addView(
                actionButton("切換此部家長手機角色 (Switch Role)", t.btnSecondaryBg, t.btnSecondaryText) {
                    GuardEngine.setRole(this, "unconfigured")
                    syncRoleServices()
                    renderUi(animate = true)
                }
            )
        }
        rootContainer.addView(card)
    }

    private fun makeCard(t: ThemeColors, isHero: Boolean = false): LinearLayout {
        val bg = GradientDrawable().apply {
            setColor(Color.parseColor(if (isHero) t.bgHero else t.bgCard))
            cornerRadius = 36f
            setStroke(if (isHero) 4 else 3, Color.parseColor(t.borderSubtle))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg
            setPadding(42, 40, 42, 40)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(0, 0, 0, 32)
            layoutParams = lp
        }
    }

    private fun makeAlertItemBox(t: ThemeColors, reason: String): LinearLayout {
        val borderColor = when (reason) {
            "sexual_or_grooming_content" -> t.warmCoral
            "tamper_attempt" -> t.warmHoney
            "stranger_message" -> t.accentSeafoam
            else -> t.accentPowderSky
        }
        val bg = GradientDrawable().apply {
            setColor(Color.parseColor(t.bgInput))
            cornerRadius = 26f
            setStroke(4, Color.parseColor(borderColor))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg
            setPadding(32, 28, 32, 28)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(0, 0, 0, 22)
            layoutParams = lp
        }
    }

    private fun label(
        text: String,
        sizeSp: Float,
        hexColor: String,
        bold: Boolean = false,
        topPad: Int = 0,
        bottomPad: Int = 0
    ): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(Color.parseColor(hexColor))
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, topPad, 0, bottomPad)
        }
    }

    private fun inputBox(t: ThemeColors, hintText: String, initialValue: String, isPassword: Boolean = false): EditText {
        val bg = GradientDrawable().apply {
            setColor(Color.parseColor(t.bgInput))
            cornerRadius = 22f
            setStroke(3, Color.parseColor(t.borderSubtle))
        }
        return EditText(this).apply {
            hint = hintText
            setText(initialValue)
            setHintTextColor(Color.parseColor(t.textMuted))
            setTextColor(Color.parseColor(t.textPrimary))
            background = bg
            setPadding(32, 26, 32, 26)
            inputType = if (isPassword) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(0, 0, 0, 20)
            layoutParams = lp
        }
    }

    private fun actionButton(title: String, hexBg: String, hexText: String, onClick: () -> Unit): Button {
        val bg = GradientDrawable().apply {
            setColor(Color.parseColor(hexBg))
            cornerRadius = 26f
        }
        return Button(this).apply {
            text = title
            setTextColor(Color.parseColor(hexText))
            setTypeface(typeface, Typeface.BOLD)
            isAllCaps = false
            textSize = 14f
            background = bg
            setPadding(28, 26, 28, 26)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(0, 0, 0, 18)
            layoutParams = lp
            setOnClickListener { v ->
                v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(75L).withEndAction {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(115L).start()
                    onClick()
                }.start()
            }
        }
    }
}