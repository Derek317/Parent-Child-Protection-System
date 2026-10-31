package com.example.igguard

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import java.util.Locale
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.HttpsURLConnection
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.random.Random

object GuardEngine {
    const val ACTION_UI_REFRESH = "com.example.igguard.ACTION_UI_REFRESH"
    private const val PREFS_NAME = "ig_guard_prefs"

    // 10-minute cross-source deduplication window for identical (reason|sender|message)
    private const val DEDUPE_WINDOW_MS = 600_000L
    private val recentAlerts = LinkedHashMap<String, Long>()
    private val seenNotifMsgTimestamps = LinkedHashSet<String>()
    private val pollLock = Any()

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

    // shortcut: regex keyword matching for EN + HK Cantonese sexual/grooming text, upgrade to on-device ML classifier when false-negatives appear
    val SEXUAL_PATTERN = Regex(
        "\\b(nudes?|naked|porn|sex|horny|boobs?|tits|dick|cock|pussy|blowjob|handjob|onlyfans|hook ?up|fwb|send (me )?(a )?pic|take it off|secret between us|don'?t tell (your )?(mum|mom|dad|parents))\\b|" +
            "(裸照|露點|色情|做愛|扑嘢|約炮|援交|包養|自慰|手淫|私密照|閃卡|J圖|咸相|鹹相|唔好同(屋企人|阿媽|阿爸|父母)講|拍片比我睇|除衫)",
        RegexOption.IGNORE_CASE
    )

    private val IGNORED_NOTIFICATIONS = Regex(
        "^(Instagram|Checking for new messages|正在檢查新訊息|Uploading|傳送中|\\d+ new messages|\\d+ 則新訊息).*$",
        RegexOption.IGNORE_CASE
    )

    // Filters out Instagram non-message status labels ("Recently seen", "Seen 5m ago", "Active now", timestamps, profile buttons)
    val NON_MESSAGE_UI_PATTERN = Regex(
        "^(" +
            "recently seen(\\s+.*)?|seen(\\s+(just now|\\d+.*|yesterday|today|mon.*|tue.*|wed.*|thu.*|fri.*|sat.*|sun.*))?|" +
            "delivered(\\s+.*)?|sent(\\s+(just now|\\d+.*|yesterday|today))?|sending\\.{0,3}|" +
            "active(\\s+(now|today|yesterday|\\d+.*))?|online|last seen.*|typing\\.{0,3}|" +
            "已讀|剛剛已讀|\\d+\\s*(秒|分鐘|小時|天|日|週|周)前已讀|已傳送|已送達|傳送中\\.{0,3}|" +
            "最近上線|目前在線上|在線|\\d+\\s*(秒|分鐘|小時|天|日|週|周)前上線|(今日|今天|昨天|昨日)上線|輸入中\\.{0,3}|" +
            "message\\.{0,3}|訊息(\\.{2,3}|⋯+)?|發送訊息(\\.{0,3}|⋯+)?|傳送訊息(\\.{0,3}|⋯+)?|send|傳送|search|搜尋|" +
            "chats|聊天室|requests|陌生訊息|general|一般|primary|主要|" +
            "view profile|查看個人檔案|follow|追蹤|following|追蹤中|mute|靜音|" +
            "double tap to like|點按兩下即可讚好|tap and hold to react|reply|回覆|" +
            "(today|yesterday|今天|昨天|今日|昨日)(\\s+\\d{1,2}:\\d{2}(\\s?[ap]m)?)?|" +
            "(mon|tue|wed|thu|fri|sat|sun)(,.*|\\s+\\d{1,2}:\\d{2}.*)?|" +
            "\\d{1,2}:\\d{2}(\\s?[ap]m)?|\\d+\\s?(s|m|h|d|w|秒|分鐘|小時|天|週|周)(前| ago)?" +
            ")$",
        RegexOption.IGNORE_CASE
    )

    @Volatile
    var pauseTamperUntilMs: Long = 0L

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun nowTimeStr(): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
    fun nowDateTimeStr(): String = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())

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

        val statusSummary = "裝置 ${Build.MODEL} 保護運作正常 (通知讀取: ${if (notifOn) "ON ✅" else "OFF ❌"} | 防篡改鎖: ${if (accOn) "ON ✅" else "OFF ❌"}) • 下次隨機回報約 ${nextMins} 分鐘後"
        val reason = if (notifOn && accOn) "heartbeat_ok" else "tamper_attempt"

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
        // Overdue if > 100 minutes since last heartbeat (since max random interval is 95 mins)
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
            append(rnd.nextInt(10, 99))
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

    // Generates a 6-character alphanumeric 15-minute unlock code (over 1 billion combinations + 5-try lockout)
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
            if (clean == generate15MinUnlockCode(ctx, offset)) return true
        }
        val syncedCode = (prefs(ctx).getString("synced_15m_code", "") ?: "").uppercase(Locale.US)
        val syncedExp = prefs(ctx).getLong("synced_15m_exp", 0L)
        if (clean == syncedCode && System.currentTimeMillis() < syncedExp) return true
        return false
    }

    fun push15MinUnlockCodeFromParent(ctx: Context, code: String) {
        val exp = System.currentTimeMillis() + 15 * 60 * 1000L
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
            put("time", nowDateTimeStr())
        }.toString()
        postEncryptedToChannel(ctx, topic, token, plain, null)
    }

    // ================= 4. REMOTE KEY ROTATION & REMOTE CHILD UNLOCK/RESET =================
    // Broadcasts a remote reset or key update on the OLD token before switching the Parent Phone to a new token!
    fun rotateKeyAndNotifyChild(ctx: Context, resetChildToUnconfigured: Boolean): String {
        val oldToken = getOrCreateFamilyToken(ctx)
        val oldTopic = getChannelTopicForToken(oldToken)
        val newToken = UUID.randomUUID().toString()
        val newPin = getPin(ctx)

        val plain = JSONObject().apply {
            put("type", if (resetChildToUnconfigured) "remote_reset_child" else "key_rotated")
            put("new_token", newToken)
            put("new_pin", newPin)
            put("time", nowDateTimeStr())
        }.toString()

        // Send encrypted command to the OLD channel so the Child Phone receives it and updates/unlocks immediately!
        postEncryptedToChannel(ctx, oldTopic, oldToken, plain) { _, _ ->
            if (!resetChildToUnconfigured) {
                setFamilyToken(ctx, newToken)
            }
            ctx.sendBroadcast(Intent(ACTION_UI_REFRESH).setPackage(ctx.packageName))
        }

        if (!resetChildToUnconfigured) {
            setFamilyToken(ctx, newToken)
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
        ctx.sendBroadcast(Intent(ACTION_UI_REFRESH).setPackage(ctx.packageName))
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
            append("🛡️ 【IG Teen Guard 家庭專屬加密配對邀請】\n")
            append("核對圖案 (Safety Emojis): $emojis\n\n")
            append("步驟 1：在子女手機複製此整段訊息，然後打開 IG Guard 即可一鍵完成配對！\n")
            append("或直接點選專屬配對連結：\n")
            append("igguard://pair?token=$token&pw=$pinB64\n\n")
            append("[IGGUARD-PAIR-V42:$token:$pinB64]")
        }
    }

    fun parsePairingString(raw: String): Pair<String, String>? {
        val text = raw.trim()
        val v42Match = Regex("IGGUARD-PAIR-V42:([a-zA-Z0-9\\-]{8,64}):([a-zA-Z0-9_\\-=]+)").find(text)
        if (v42Match != null) {
            val token = v42Match.groupValues[1]
            val pw = try {
                String(Base64.decode(v42Match.groupValues[2], Base64.NO_WRAP or Base64.URL_SAFE), Charsets.UTF_8)
            } catch (_: Exception) {
                "HkGuard2026"
            }
            return token to pw
        }
        val legacyMatch = Regex("IGGUARD-PAIR:([a-zA-Z0-9\\-]{8,64}):([^\\]\\s]+)").find(text)
        if (legacyMatch != null) {
            return legacyMatch.groupValues[1] to legacyMatch.groupValues[2]
        }
        if (text.contains("token=")) {
            val token = Regex("token=([a-zA-Z0-9\\-]{8,64})").find(text)?.groupValues?.get(1)
            val pwB64 = Regex("pw=([a-zA-Z0-9_\\-=]+)").find(text)?.groupValues?.get(1)
            val pw = if (!pwB64.isNullOrEmpty()) {
                try {
                    String(Base64.decode(pwB64, Base64.NO_WRAP or Base64.URL_SAFE), Charsets.UTF_8)
                } catch (_: Exception) {
                    "HkGuard2026"
                }
            } else {
                Regex("pin=([^&\\s]+)").find(text)?.groupValues?.get(1) ?: "HkGuard2026"
            }
            if (!token.isNullOrEmpty()) return token to pw
        }
        return null
    }

    fun getChildOwnHandles(ctx: Context): Set<String> {
        val raw = prefs(ctx).getString("child_own_handles", "") ?: ""
        return raw.split(",").map { it.trim().lowercase().removePrefix("@") }.filter { it.isNotEmpty() }.toSet()
    }

    fun rememberChildOwnHandle(ctx: Context, handle: String) {
        val clean = handle.trim().lowercase().removePrefix("@")
        if (clean.isEmpty() || clean == "instagram") return
        val next = getChildOwnHandles(ctx).toMutableSet()
        if (next.add(clean)) {
            prefs(ctx).edit().putString("child_own_handles", next.joinToString(",")).apply()
        }
    }

    fun getTrustedHandles(ctx: Context): Set<String> {
        val raw = prefs(ctx).getString("trusted_csv", "mum_hk,dad_hk,school_friend_1") ?: ""
        return raw.split(",").map { it.trim().lowercase().removePrefix("@") }.filter { it.isNotEmpty() }.toSet()
    }

    fun setTrustedHandles(ctx: Context, handles: Collection<String>) {
        val clean = handles.map { it.trim().lowercase().removePrefix("@") }.filter { it.isNotEmpty() }.distinct().sorted()
        prefs(ctx).edit().putString("trusted_csv", clean.joinToString(",")).apply()
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
    fun shouldSendAlert(key: String, bypassDedupe: Boolean = false): Boolean {
        if (bypassDedupe) return true
        val now = System.currentTimeMillis()
        val iter = recentAlerts.entries.iterator()
        while (iter.hasNext()) {
            if (now - iter.next().value > DEDUPE_WINDOW_MS) iter.remove()
        }
        val prev = recentAlerts[key]
        if (prev != null && now - prev <= DEDUPE_WINDOW_MS) {
            return false
        }
        recentAlerts[key] = now
        return true
    }

    fun isTrustedSender(ctx: Context, rawTitle: String, rawText: String): Boolean {
        val trustedSet = getTrustedHandles(ctx) + getChildOwnHandles(ctx)
        val titleNorm = rawTitle.trim().lowercase().removePrefix("@")
        if (titleNorm.isEmpty() || titleNorm == "instagram") return false
        if (titleNorm in trustedSet) return true
        val bodyPrefix = rawText.substringBefore(":", "").trim().lowercase().removePrefix("@")
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

    // Filters out status/receipt strings like "Recently seen" / "Seen 5m ago" / "已讀"
    fun isNonMessageUiString(raw: String): Boolean {
        val clean = raw.trim()
        if (clean.length < 2) return true
        return NON_MESSAGE_UI_PATTERN.matches(clean)
    }

    fun evaluateDm(ctx: Context, senderTitle: String, messageBody: String): String? {
        val text = messageBody.trim()
        val senderNorm = senderTitle.trim().lowercase().removePrefix("@")
        if (text.isEmpty() || IGNORED_NOTIFICATIONS.matches(text) || isNonMessageUiString(text)) return null
        if (senderNorm in getChildOwnHandles(ctx)) return null
        if (SEXUAL_PATTERN.containsMatchIn(text) || SEXUAL_PATTERN.containsMatchIn(senderTitle)) {
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
        onComplete: ((Boolean, String) -> Unit)? = null
    ) {
        val cleanSender = sender.trim().ifEmpty { "unknown" }.take(80)
        val cleanText = text.trim().take(300)
        val dedupeKey = "${reason.lowercase()}|${cleanSender.lowercase().removePrefix("@")}|${cleanText.lowercase()}"

        if (!shouldSendAlert(dedupeKey, bypassDedupe = bypassDedupe || reason == "test_alert" || reason == "heartbeat_ok")) {
            addCheckpointLog(ctx, "CP2-DEDUPE", "Skipped duplicate: @$cleanSender ($cleanText)")
            onComplete?.invoke(false, "Duplicate suppressed")
            return
        }

        addCheckpointLog(ctx, "CP2-MATCH", "[$reason] @$cleanSender: ${cleanText.take(45)}")

        val token = getOrCreateFamilyToken(ctx)
        val topic = getChannelTopicForToken(token)
        val plain = JSONObject().apply {
            put("type", "alert")
            put("reason", reason)
            put("sender", cleanSender)
            put("text", cleanText)
            put("time", nowDateTimeStr())
            put("trusted_csv", getTrustedHandles(ctx).joinToString(","))
        }.toString()

        postEncryptedToChannel(ctx, topic, token, plain, onComplete)
    }

    fun syncTrustedFromParent(ctx: Context) {
        val token = getOrCreateFamilyToken(ctx)
        val topic = getChannelTopicForToken(token)
        val plain = JSONObject().apply {
            put("type", "sync_trusted")
            put("trusted_csv", getTrustedHandles(ctx).joinToString(","))
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
                // Strictly enforce HttpsURLConnection (TLS 1.2/1.3) + AES-256-GCM E2EE
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
    ) {
        val outer = try { JSONObject(line) } catch (_: Exception) { return }
        if (outer.optString("event") != "message") return
        val msgId = outer.optString("id")
        if (msgId.isEmpty()) return

        val seenIds = prefs(ctx).getStringSet("seen_msg_ids", emptySet())?.toMutableSet() ?: mutableSetOf()
        if (seenIds.contains(msgId)) return

        val innerStr = outer.optString("message")
        val token = getOrCreateFamilyToken(ctx)
        val inner = decryptPayload(token, innerStr) ?: return
        val msgType = inner.optString("type")

        seenIds.add(msgId)
        val trimmed = if (seenIds.size > 300) seenIds.toList().takeLast(300).toSet() else seenIds
        prefs(ctx).edit().putStringSet("seen_msg_ids", trimmed).apply()

        when (msgType) {
            "sync_trusted" -> {
                val csv = inner.optString("trusted_csv")
                if (csv.isNotEmpty()) {
                    val list = csv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    setTrustedHandles(ctx, list)
                    addCheckpointLog(ctx, "CP4-SYNC", "Updated trusted list (${list.size} handles)")
                }
            }
            "unlock_code" -> {
                val code = inner.optString("code")
                val exp = inner.optLong("exp", 0L)
                if (code.isNotEmpty() && exp > 0L) {
                    prefs(ctx).edit()
                        .putString("synced_15m_code", code)
                        .putLong("synced_15m_exp", exp)
                        .apply()
                    addCheckpointLog(ctx, "CP4-SYNC", "Received encrypted 15-min unlock code")
                }
            }
            "key_rotated" -> {
                // Parent rotated the Family Key -> Child Phone automatically updates to the new Family Key!
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
                // Parent remotely unlocked & reset Child Phone (solves forgotten password lock!)
                if (role == "child") {
                    grantTemporaryUnlock(ctx, 10 * 60_000L)
                    setRole(ctx, "unconfigured")
                    addCheckpointLog(ctx, "CP4-RESET", "Parent remotely unlocked & reset Child Phone")
                    ctx.sendBroadcast(Intent(ACTION_UI_REFRESH).setPackage(ctx.packageName))
                }
            }
            "alert" -> {
                if (role == "parent") {
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
        for (i in 0 until minOf(arr.length(), 79)) {
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

// 1. CHILD ONLY: Reads incoming Instagram DM notifications + runs random 30-95 min heartbeat & remote-command sync
class IgNotificationListener : NotificationListenerService() {

    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private val periodicCheckRunnable = object : Runnable {
        override fun run() {
            if (GuardEngine.isChildMode(this@IgNotificationListener)) {
                GuardEngine.checkAndSendChildHeartbeatIfDue(this@IgNotificationListener)
                thread { GuardEngine.pollChannelOnce(applicationContext, since = "30m") }
            }
            heartbeatHandler.postDelayed(this, 60_000L) // Checks once per minute if random 30-95m timer is due
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        GuardEngine.cpNotifConnected = true
        GuardEngine.addCheckpointLog(this, "CP1-NOTIF", "NotificationListener CONNECTED ✅")
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

        if (sbn == null || sbn.packageName != "com.instagram.android") return
        val notif = sbn.notification ?: return
        val extras = notif.extras ?: return

        GuardEngine.cpNotifConnected = true
        GuardEngine.cpLastNotifTime = GuardEngine.nowTimeStr()

        val convHeader = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim() ?: ""
        val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim() ?: ""

        if (subText.isNotEmpty() && !subText.contains(" ")) {
            GuardEngine.rememberChildOwnHandle(this, subText)
        }

        val extractedPairs = ArrayList<Pair<String, String>>()
        val msgBundles = getParcelableArrayCompat(extras, Notification.EXTRA_MESSAGES)

        if (!msgBundles.isNullOrEmpty()) {
            // Only inspect the LATEST message in the bundle so older historical messages in the bundle aren't re-sent
            val latestBundle = msgBundles.lastOrNull() as? Bundle
            if (latestBundle != null) {
                val msgText = latestBundle.getCharSequence("text")?.toString()?.trim() ?: ""
                val msgSender = latestBundle.getCharSequence("sender")?.toString()?.trim() ?: ""
                val msgTime = latestBundle.getLong("time", 0L)

                if (convHeader.isNotEmpty() && msgSender.isNotEmpty() &&
                    !convHeader.equals(msgSender, ignoreCase = true) && !convHeader.contains(" ")
                ) {
                    GuardEngine.rememberChildOwnHandle(this, convHeader)
                }

                val effectiveSender = msgSender.ifEmpty { rawTitle.ifEmpty { convHeader } }
                val tsSig = "$effectiveSender|$msgTime|$msgText"
                if (msgText.isNotEmpty() && (msgTime == 0L || GuardEngine.isNewNotificationMessageTimestamp(tsSig))) {
                    extractedPairs.add(effectiveSender to msgText)
                }
            }
        } else {
            val fallbackBody = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)
                ?: "").toString().trim()

            if (fallbackBody.isNotEmpty()) {
                var sender = rawTitle.ifEmpty { convHeader }
                if (sender.startsWith("[") && sender.contains("]")) {
                    val insideBracket = sender.substringAfter("[").substringBefore("]").trim()
                    val afterBracket = sender.substringAfter("]").trim()
                    if (insideBracket.isNotEmpty() && afterBracket.isNotEmpty()) {
                        GuardEngine.rememberChildOwnHandle(this, insideBracket)
                        sender = afterBracket
                    }
                }
                var body = fallbackBody
                if (body.contains(": ")) {
                    val possibleSender = body.substringBefore(": ").trim()
                    val possibleMsg = body.substringAfter(": ").trim()
                    if (possibleSender.isNotEmpty() && !possibleSender.contains(" ") && possibleMsg.isNotEmpty()) {
                        if (sender.isNotEmpty() && !sender.equals(possibleSender, ignoreCase = true) && !sender.contains(" ")) {
                            GuardEngine.rememberChildOwnHandle(this, sender)
                        }
                        sender = possibleSender
                        body = possibleMsg
                    }
                }
                extractedPairs.add(sender to body)
            }
        }

        if (extractedPairs.isEmpty()) return

        var wipeNotification = false
        for ((sender, body) in extractedPairs.distinct()) {
            val reason = GuardEngine.evaluateDm(this, sender, body)
            if (reason == null) {
                GuardEngine.addCheckpointLog(this, "CP2-TRUSTED", "Ignored trusted/self/status: @$sender")
                continue
            }
            GuardEngine.sendAlertFromChild(this, reason, sender, body)
            if (reason == "sexual_or_grooming_content" && !GuardEngine.isTemporarilyUnlocked(this)) {
                wipeNotification = true
            }
        }

        if (wipeNotification) {
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

// 2. CHILD ONLY: Intercepts ONLY the latest bottom DM bubble in active chats (ignores upper history & "Recently seen")
class IgAccessibilityService : AccessibilityService() {

    private var lastScanMs = 0L
    private var lastRemoteSyncMs = 0L
    // Tracks the peer & initial bottom bubble when a chat first opens so old history isn't dumped on thread open
    private var currentOpenPeer: String = ""
    private var lastReportedBottomBubble: String = ""

    private data class ScreenTextItem(
        val text: String,
        val top: Int,
        val bottom: Int,
        val left: Int,
        val right: Int,
        val isEditable: Boolean
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        GuardEngine.cpAccConnected = true
        GuardEngine.grantTemporaryUnlock(this, 60_000L)
        GuardEngine.scheduleNextRandomHeartbeat(this)
        GuardEngine.addCheckpointLog(this, "CP1-ACC", "Accessibility Connected ✅ (60s setup grace active)")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!GuardEngine.isChildMode(this)) return

        val pkg = event?.packageName?.toString() ?: return
        val now = System.currentTimeMillis()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && now - lastScanMs < 500L) {
            return
        }
        lastScanMs = now
        GuardEngine.cpAccConnected = true
        GuardEngine.cpLastAccTime = GuardEngine.nowTimeStr()

        // Check if random 30-95 min heartbeat is due, and check for remote key rotation/reset every 45s
        GuardEngine.checkAndSendChildHeartbeatIfDue(this)
        if (now - lastRemoteSyncMs > 45_000L) {
            lastRemoteSyncMs = now
            thread { GuardEngine.pollChannelOnce(applicationContext, since = "15m") }
        }

        val root = rootInActiveWindow ?: return
        val dmUnlocked = GuardEngine.isTemporarilyUnlocked(this)

        val screenHeight = resources.displayMetrics.heightPixels.coerceAtLeast(1200)
        val items = ArrayList<ScreenTextItem>(90)
        collectScreenItems(root, items)
        if (items.isEmpty()) return

        val joined = items.joinToString(" ") { it.text }

        // A. Prevent teen from turning off Instagram notifications or disabling IG Guard in Android OS Settings
        if (!dmUnlocked && isSettingsPackage(pkg)) {
            val isIgNotificationPage = joined.contains("Instagram", ignoreCase = true) &&
                (joined.contains("Allow notifications", ignoreCase = true) ||
                    joined.contains("Show notifications", ignoreCase = true) ||
                    joined.contains("All Instagram notifications", ignoreCase = true) ||
                    joined.contains("顯示通知") ||
                    joined.contains("允許通知"))

            if (isIgNotificationPage) {
                // shortcut: auto-click master notification switch back ON before kicking to Home, upgrade to Family Link device owner policy on managed devices
                forceEnableUncheckedSwitches(root)
            }

            val touchesProtectedApp = joined.contains("Instagram", ignoreCase = true) ||
                joined.contains("IG Guard", ignoreCase = true) ||
                joined.contains("Notification access", ignoreCase = true) ||
                joined.contains("通知存取", ignoreCase = true)

            if (touchesProtectedApp) {
                performGlobalAction(GLOBAL_ACTION_HOME)
                GuardEngine.sendAlertFromChild(
                    this,
                    "tamper_attempt",
                    "Android Settings",
                    "Child tried to open Android Settings for Instagram / IG Guard: ${joined.take(100)}"
                )
                return
            }
        }

        // B. Inside native Instagram app
        if (pkg == "com.instagram.android") {
            val isExplicitMuteScreen = joined.contains("Pause all", ignoreCase = true) ||
                joined.contains("暫停所有") ||
                joined.contains("Mute messages", ignoreCase = true) ||
                joined.contains("Mute chat", ignoreCase = true) ||
                joined.contains("將訊息設為靜音") ||
                joined.contains("靜音訊息")

            if (!dmUnlocked && isExplicitMuteScreen) {
                performGlobalAction(GLOBAL_ACTION_BACK)
                GuardEngine.sendAlertFromChild(
                    this,
                    "tamper_attempt",
                    "Instagram Mute Settings",
                    "Child tried to mute Instagram notifications or a DM chat."
                )
                return
            }

            // Detect if child is inside an ACTIVE 1-on-1 DM Conversation (message composer at bottom of screen)
            val composerNode = items.firstOrNull { item ->
                item.top > (screenHeight * 0.55).toInt() && (
                    item.isEditable ||
                        item.text.contains("Message...", ignoreCase = true) ||
                        item.text.contains("發送訊息") ||
                        item.text.contains("傳送訊息") ||
                        item.text.contains("訊息...") ||
                        item.text.contains("訊息⋯")
                    )
            }

            if (composerNode == null) {
                currentOpenPeer = ""
                return
            }

            val composerTop = composerNode.top
            val ownHandles = GuardEngine.getChildOwnHandles(this)

            // 1. Extract peer's handle/name from the top header bar (top 20% of screen), skipping status strings like "Active now" / "Recently seen"
            val headerCandidates = items.filter { item ->
                val clean = item.text.trim()
                val low = clean.lowercase().removePrefix("@")
                item.bottom <= (screenHeight * 0.21).toInt() &&
                    !item.isEditable &&
                    clean.length in 2..35 &&
                    !GuardEngine.isNonMessageUiString(clean) &&
                    low !in ownHandles
            }.sortedBy { it.top }

            // The first text in the DM header is the peer's Display Name / Handle (the line below it is usually "Active 5m ago" / "Recently seen")
            val peerHeader = headerCandidates.firstOrNull()?.text?.trim() ?: "Instagram_Open_Chat"
            val isPeerTrusted = GuardEngine.isTrustedSender(this, peerHeader, "")

            // 2. ONLY look at the LOWER message region just above the composer box (between 42% of screen and composerTop)
            // This ignores upper historical messages and filters out "Recently seen" / "Seen" / timestamps!
            val recentBottomBubbles = items.filter { item ->
                val clean = item.text.trim()
                val low = clean.lowercase().removePrefix("@")
                item.top >= (screenHeight * 0.42).toInt() &&
                    item.bottom <= composerTop + 10 &&
                    !item.isEditable &&
                    clean != peerHeader &&
                    !GuardEngine.isNonMessageUiString(clean) &&
                    low !in ownHandles
            }.sortedBy { it.bottom }

            // Pick ONLY the single newest message bubble at the very bottom of the conversation!
            val latestBubble = recentBottomBubbles.lastOrNull() ?: return
            val latestText = latestBubble.text.trim()
            val bubbleSig = "${peerHeader.lowercase()}|${latestText.lowercase()}"

            // Check sexual / grooming content on the latest bottom bubble first
            if (GuardEngine.SEXUAL_PATTERN.containsMatchIn(latestText)) {
                lastReportedBottomBubble = bubbleSig
                GuardEngine.sendAlertFromChild(this, "sexual_or_grooming_content", peerHeader, latestText)
                if (!dmUnlocked) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }
                return
            }

            // When switching into a chat thread, record the existing bottom bubble once; only alert if it's a new stranger message
            if (peerHeader != currentOpenPeer) {
                currentOpenPeer = peerHeader
            }

            if (!isPeerTrusted && bubbleSig != lastReportedBottomBubble) {
                lastReportedBottomBubble = bubbleSig
                GuardEngine.sendAlertFromChild(this, "stranger_message", peerHeader, latestText)
            }
        }
    }

    private fun isSettingsPackage(pkg: String): Boolean {
        return pkg.contains("settings", ignoreCase = true) ||
            pkg == "com.miui.securitycenter" ||
            pkg == "com.coloros.safecenter"
    }

    private fun collectScreenItems(node: AccessibilityNodeInfo?, out: MutableList<ScreenTextItem>) {
        if (node == null || out.size >= 95) return
        val rawText = node.text?.toString()?.trim() ?: ""
        if (rawText.isNotEmpty() || node.isEditable) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            out.add(
                ScreenTextItem(
                    text = rawText,
                    top = rect.top,
                    bottom = rect.bottom,
                    left = rect.left,
                    right = rect.right,
                    isEditable = node.isEditable
                )
            )
        }
        for (i in 0 until node.childCount) {
            collectScreenItems(node.getChild(i), out)
        }
    }

    private fun forceEnableUncheckedSwitches(node: AccessibilityNodeInfo?) {
        if (node == null) return
        val cls = node.className?.toString() ?: ""
        if ((cls.contains("Switch") || cls.contains("CheckBox")) && !node.isChecked && node.isClickable) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        for (i in 0 until node.childCount) {
            forceEnableUncheckedSwitches(node.getChild(i))
        }
    }

    override fun onInterrupt() {}
}

// 3. PARENT ONLY: Persistent Encrypted TLS Stream Receiver + Child Offline Watchdog
class ParentAlertService : Service() {

    @Volatile
    private var running = false
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
            .setContentText("核對圖案: ${GuardEngine.getSafetyEmojis(this)} • AES-256-GCM 實時保護")
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
                    // Check if Child Phone missed its 30-95 min random heartbeat (>100 mins silent)
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
                        val code = conn.responseCode
                        if (code in 200..299) {
                            GuardEngine.cpParentStreamState = "TLS + AES-256-GCM E2EE Active 🔒"
                            GuardEngine.addCheckpointLog(applicationContext, "CP4-STREAM", "Connected to encrypted TLS stream")
                            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                                var line: String? = null
                                while (running && GuardEngine.isParentMode(applicationContext) &&
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
                        GuardEngine.cpParentStreamState = "Reconnecting TLS (${e.javaClass.simpleName})..."
                    } finally {
                        try { conn?.disconnect() } catch (_: Exception) {}
                    }
                    try {
                        Thread.sleep(15_000L)
                    } catch (_: Exception) {
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
            "sexual_or_grooming_content" -> "🚨 色情/誘拐高危警報: $sender"
            "tamper_attempt" -> "⚠️ 防篡改/離線警告: $sender"
            "stranger_message" -> "💬 陌生人私訊: $sender"
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
            .setStyle(Notification.BigTextStyle().bigText("來源: $sender\n內容: $text"))
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .build()

        nm.notify(notifSeq++, notif)
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

// 4. All-in-One Parent & Child UI (v4.2)
class MainActivity : Activity() {

    private lateinit var rootContainer: LinearLayout
    private val uiHandler = Handler(Looper.getMainLooper())
    private var activePermissionDialog: AlertDialog? = null
    private var showAdvancedLogs: Boolean = false
    private var displayed15MinCode: String = ""

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            uiHandler.post { renderUi() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0F172A"))
            isFillViewport = true
        }
        rootContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(42, 56, 42, 80)
        }
        scroll.addView(rootContainer)
        setContentView(scroll)

        handleDeepLinkIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleDeepLinkIntent(intent)
    }

    private fun handleDeepLinkIntent(intent: Intent?) {
        val dataStr = intent?.dataString ?: return
        val parsed = GuardEngine.parsePairingString(dataStr) ?: return
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
        renderUi()
        checkAndShowGuidedPermissionPopup()
    }

    override fun onPause() {
        super.onPause()
        activePermissionDialog?.dismiss()
        activePermissionDialog = null
        try {
            unregisterReceiver(refreshReceiver)
        } catch (_: Exception) {
        }
    }

    private fun checkAndShowGuidedPermissionPopup() {
        activePermissionDialog?.dismiss()
        val role = GuardEngine.getRole(this)
        if (role != "child") return

        val notifOn = GuardEngine.isNotificationListenerEnabledStatic(this)
        val accOn = GuardEngine.isAccessibilityEnabledStatic(this)

        if (!notifOn) {
            activePermissionDialog = AlertDialog.Builder(this)
                .setTitle("🔔 第 1 步（共 2 步）：開啟 IG 訊息保護")
                .setMessage(
                    "請點選下方「立即開啟」，然後在清單中點選「IG Guard DM Reader」並打開開關。\n\n" +
                        "💡 如畫面彈出「限制設定 (Restricted setting)」：\n" +
                        "請先點選左下方「解除限制設定」，在右上角「⋮」選單點選「允許受限設定」。"
                )
                .setCancelable(true)
                .setPositiveButton("立即開啟 (Enable Now) →") { _, _ ->
                    openNotificationListenerSettingsHighlighted()
                }
                .setNeutralButton("先解除「限制設定」") { _, _ ->
                    openAppDetailsSettings()
                }
                .show()
            return
        }

        if (!accOn) {
            activePermissionDialog = AlertDialog.Builder(this)
                .setTitle("🛡️ 第 2 步（最後一步）：開啟防篡改及實時保護鎖")
                .setMessage(
                    "最後一步！請點選下方「完成設定」，進入「已安裝的應用程式 (Installed apps)」→「IG Guard Anti-Tamper」並打開開關。\n\n" +
                        "💡 如出現「限制設定」灰色無法點選，請先按左下方「解除限制設定」→ 右上角「⋮」→「允許受限設定」。"
                )
                .setCancelable(true)
                .setPositiveButton("完成設定 (Enable Lock) →") { _, _ ->
                    openAccessibilitySettingsHighlighted()
                }
                .setNeutralButton("解除「限制設定」") { _, _ ->
                    openAppDetailsSettings()
                }
                .show()
            return
        }

        if (!GuardEngine.hasSentInitialSetupConfirmation(this)) {
            GuardEngine.markInitialSetupConfirmationSent(this, true)
            GuardEngine.scheduleNextRandomHeartbeat(this)
            GuardEngine.sendAlertFromChild(
                this,
                "pairing_verified",
                Build.MODEL ?: "Child_Phone",
                "子女手機 (${Build.MODEL}) 已完成所有權限設定並啟動保護！每 30–95 分鐘將隨機發送加密心跳回報。",
                bypassDedupe = true
            )
            Toast.makeText(this, "🎉 所有保護已成功啟動！已加密通知家長手機", Toast.LENGTH_LONG).show()
        }
    }

    private fun openAppDetailsSettings() {
        GuardEngine.grantTemporaryUnlock(this, 180_000L)
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }

    private fun openNotificationListenerSettingsHighlighted() {
        GuardEngine.grantTemporaryUnlock(this, 180_000L)
        val cn = ComponentName(this, IgNotificationListener::class.java).flattenToString()
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
            putExtra(":settings:fragment_args_key", cn)
            putExtra(":settings:show_fragment_args", Bundle().apply {
                putString(":settings:fragment_args_key", cn)
            })
        }
        startActivity(intent)
    }

    private fun openAccessibilitySettingsHighlighted() {
        GuardEngine.grantTemporaryUnlock(this, 180_000L)
        val cn = ComponentName(this, IgAccessibilityService::class.java).flattenToString()
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            putExtra(":settings:fragment_args_key", cn)
            putExtra(":settings:show_fragment_args", Bundle().apply {
                putString(":settings:fragment_args_key", cn)
            })
        }
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

    private fun renderUi() {
        rootContainer.removeAllViews()
        val role = GuardEngine.getRole(this)

        rootContainer.addView(label("IG Teen Guard v4.2 • 端對端加密守護系統 🔒", 21f, "#F8FAFC", bold = true))
        val badgeText = when (role) {
            "parent" -> "👨‍👩‍👧 家長手機模式 (Parent) • 核對圖案: ${GuardEngine.getSafetyEmojis(this)}"
            "child" -> "🧒 子女保護模式 🔒 (Child) • 核對圖案: ${GuardEngine.getSafetyEmojis(this)}"
            else -> "請選擇此部手機嘅身份 (Choose This Phone's Role)"
        }
        val badgeColor = when (role) {
            "parent" -> "#38BDF8"
            "child" -> "#4ADE80"
            else -> "#FBBF24"
        }
        rootContainer.addView(label(badgeText, 14f, badgeColor, bold = true, bottomPad = 24))

        when (role) {
            "unconfigured" -> renderEasyOnboardingScreen()
            "parent" -> {
                renderParentDashboard()
                renderCollapsibleDiagnostics()
                renderRoleSwitchFooter(requirePin = false)
            }
            "child" -> {
                renderChildDashboard()
                renderCollapsibleDiagnostics()
                renderRoleSwitchFooter(requirePin = true)
            }
        }
    }

    private fun renderEasyOnboardingScreen() {
        val parentCard = makeCard()
        parentCard.addView(label("👨‍👩‍👧 我是家長 (I am the Parent)", 19f, "#38BDF8", bold = true, bottomPad = 8))
        parentCard.addView(
            label(
                "系統已預設產生高強度 10 位數混合主密碼（亦可自訂最少 8 位英數密碼），並以 AES-256-GCM 端對端加密所有通訊，防止任何中間人攔截。",
                13f,
                "#CBD5E1",
                bottomPad = 14
            )
        )
        val pwInput = inputBox("設定高強度家長主密碼 (最少 8 位英文字母+數字)", GuardEngine.getPin(this), isPassword = false)
        parentCard.addView(pwInput)

        parentCard.addView(
            actionButton("👨‍👩‍👧 設定為「家長手機」並產生加密配對邀請", "#0284C7") {
                val candidate = pwInput.text.toString().trim()
                if (!GuardEngine.isStrongPasswordValid(candidate)) {
                    Toast.makeText(this, "⚠️ 主密碼必須最少 8 個字元，並同時包含英文字母及數字！", Toast.LENGTH_LONG).show()
                    return@actionButton
                }
                GuardEngine.setPin(this, candidate)
                GuardEngine.getOrCreateFamilyToken(this)
                GuardEngine.setRole(this, "parent")
                syncRoleServices()
                renderUi()
            }
        )
        rootContainer.addView(parentCard)

        val childCard = makeCard()
        childCard.addView(label("🧒 這是子女手機 (This is the Child's Phone)", 19f, "#4ADE80", bold = true, bottomPad = 8))
        childCard.addView(
            label(
                "先喺家長手機按「傳送配對邀請」去子女 WhatsApp / SMS，喺子女手機【複製】該段訊息，然後按下方綠色按鈕即可一鍵完成加密配對！",
                13f,
                "#CBD5E1",
                bottomPad = 14
            )
        )

        childCard.addView(
            actionButton("📋 一鍵貼上家長配對邀請並啟動保護 (Paste & Pair)", "#16A34A") {
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
                    renderUi()
                    checkAndShowGuidedPermissionPopup()
                } else {
                    Toast.makeText(
                        this,
                        "⚠️ 剪貼簿未搵到配對碼！請先喺 WhatsApp/SMS 複製家長傳來嘅配對訊息，或在下方手動貼上。",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )

        val manualInput = inputBox("或在此貼上家長傳來嘅配對訊息 / 專屬金鑰", "")
        childCard.addView(manualInput)
        childCard.addView(
            actionButton("🔗 使用上方貼上嘅內容配對子女手機", "#334155") {
                val parsed = GuardEngine.parsePairingString(manualInput.text.toString())
                if (parsed != null) {
                    GuardEngine.setFamilyToken(this, parsed.first)
                    GuardEngine.setPin(this, parsed.second)
                    GuardEngine.setRole(this, "child")
                    GuardEngine.markInitialSetupConfirmationSent(this, false)
                    GuardEngine.scheduleNextRandomHeartbeat(this)
                    syncRoleServices()
                    renderUi()
                    checkAndShowGuidedPermissionPopup()
                } else {
                    Toast.makeText(this, "格式不正確，請複製家長手機發出嘅完整配對邀請訊息", Toast.LENGTH_LONG).show()
                }
            }
        )
        rootContainer.addView(childCard)
    }

    // ================= PARENT MODE UI =================
    private fun renderParentDashboard() {
        // 1. Active Heartbeat Monitor & E2EE Security Status Card
        val hbOverdue = GuardEngine.isChildHeartbeatOverdue(this)
        val hbCard = makeCard()
        hbCard.addView(
            label(
                if (hbOverdue) "🚨 警告：超過 100 分鐘未收到子女手機心跳！" else "💓 子女手機連線及防移除監測 (30–95 分鐘隨機心跳)",
                17f,
                if (hbOverdue) "#EF4444" else "#4ADE80",
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
                "#CBD5E1",
                bottomPad = 8
            )
        )
        rootContainer.addView(hbCard)

        // 2. 15-Minute Temporary Unlock Code Generator Card
        val unlockGenCard = makeCard()
        unlockGenCard.addView(label("🔑 產生 15 分鐘子女臨時解鎖碼 (6-Char Unlock Code)", 17f, "#38BDF8", bold = true, bottomPad = 8))
        unlockGenCard.addView(
            label(
                "無需透露您嘅家長主密碼！按下方按鈕即可產生一個 6 位英數「15 分鐘臨時解鎖碼」，讓子女暫時解鎖 15 分鐘（具備 5 次防暴力破解鎖定）。",
                13f,
                "#CBD5E1",
                bottomPad = 12
            )
        )
        if (displayed15MinCode.isNotEmpty()) {
            unlockGenCard.addView(
                label(
                    "👉 當前 15 分鐘臨時解鎖碼：$displayed15MinCode",
                    20f,
                    "#FBBF24",
                    bold = true,
                    bottomPad = 14
                )
            )
        }
        unlockGenCard.addView(
            actionButton("🔑 立即產生 6 位數 15 分鐘解鎖碼 (Generate 15-Min Code)", "#16A34A") {
                val code = GuardEngine.generate15MinUnlockCode(this)
                displayed15MinCode = code
                GuardEngine.push15MinUnlockCodeFromParent(this, code)
                Toast.makeText(this, "✅ 已產生並加密同步 15 分鐘解鎖碼：$code", Toast.LENGTH_LONG).show()
                renderUi()
            }
        )
        rootContainer.addView(unlockGenCard)

        // 3. Exclusive Family Pairing & Invite Card
        val pairCard = makeCard()
        pairCard.addView(label("🔗 配對子女手機 (AES-256-GCM 端對端加密)", 17f, "#FFFFFF", bold = true, bottomPad = 8))
        pairCard.addView(
            label(
                "• 家庭安全核對圖案：${GuardEngine.getSafetyEmojis(this)}",
                14f,
                "#CBD5E1",
                bottomPad = 14
            )
        )

        pairCard.addView(
            actionButton("📲 傳送專屬配對邀請去子女手機 (WhatsApp / SMS)", "#0284C7") {
                val invite = GuardEngine.buildPairingInviteText(this)
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, invite)
                }
                startActivity(Intent.createChooser(sendIntent, "傳送配對邀請去子女手機"))
            }
        )

        pairCard.addView(
            actionButton("📋 複製專屬配對訊息 (Copy Pairing Invite)", "#334155") {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("IGGuardInvite", GuardEngine.buildPairingInviteText(this)))
                Toast.makeText(this, "已複製！請透過 WhatsApp/SMS 發送給子女手機", Toast.LENGTH_LONG).show()
            }
        )
        rootContainer.addView(pairCard)

        // 4. Trusted Friends Card
        val trustCard = makeCard()
        trustCard.addView(label("✅ 子女信任名單 (Approved IG Friends)", 17f, "#FFFFFF", bold = true, bottomPad = 8))
        val currentHandles = GuardEngine.getTrustedHandles(this)
        trustCard.addView(
            label(
                "名單內嘅朋友與子女正常傾偈唔會發送陌生人警報（但出現色情/誘拐字眼仍會攔截並通知家長）：\n" +
                    if (currentHandles.isEmpty()) "(未設定)" else currentHandles.joinToString(", ") { "@$it" },
                13f,
                "#38BDF8",
                bold = true,
                bottomPad = 12
            )
        )

        val handlesInput = inputBox(
            "以逗號分隔 IG 帳號 (例如 mum_hk, dad_hk, bestie)",
            currentHandles.joinToString(", ")
        )
        trustCard.addView(handlesInput)

        trustCard.addView(
            actionButton("💾 儲存並加密同步信任名單去子女手機", "#0284C7") {
                val updated = handlesInput.text.toString().split(",")
                GuardEngine.setTrustedHandles(this, updated)
                GuardEngine.syncTrustedFromParent(this)
                Toast.makeText(this, "✅ 已加密儲存並同步去子女手機！", Toast.LENGTH_SHORT).show()
                renderUi()
            }
        )
        rootContainer.addView(trustCard)

        // 5. Live Alert Inbox Card
        val inboxCard = makeCard()
        inboxCard.addView(label("🚨 子女 IG 即時警報記錄 (Child Alert Inbox)", 17f, "#FFFFFF", bold = true, bottomPad = 12))

        inboxCard.addView(
            actionButton("🔄 立即刷新最新警報 (Refresh Now)", "#334155") {
                Toast.makeText(this, "正在同步最新加密警報...", Toast.LENGTH_SHORT).show()
                thread { GuardEngine.pollChannelOnce(applicationContext, since = "6h") }
            }
        )

        val alerts = GuardEngine.getParentAlerts(this)
        if (alerts.isEmpty()) {
            inboxCard.addView(
                label(
                    "暫時未有警報。子女手機每 30–95 分鐘會隨機傳送一次連線心跳。",
                    14f,
                    "#94A3B8",
                    bottomPad = 8
                )
            )
        } else {
            for (alert in alerts) {
                val reason = alert.optString("reason", "alert")
                val sender = alert.optString("sender", "unknown")
                val text = alert.optString("text", "")
                val time = alert.optString("time", "")

                val itemBox = makeAlertItemBox(reason)
                val badge = when (reason) {
                    "sexual_or_grooming_content" -> "🔴 色情 / 誘拐高危訊息"
                    "tamper_attempt" -> "🟠 防篡改 / 離線警告"
                    "stranger_message" -> "🟡 陌生人私訊 (Stranger DM)"
                    "heartbeat_ok" -> "💓 子女手機定時連線回報 (30–95m)"
                    "pairing_verified" -> "🟢 子女手機配對成功"
                    else -> "🔔 系統通知"
                }
                itemBox.addView(label("$badge  •  $time", 12f, "#F8FAFC", bold = true, bottomPad = 4))
                itemBox.addView(label("來源 (Sender): $sender", 14f, "#38BDF8", bold = true, bottomPad = 4))
                itemBox.addView(label("內容 (Message): $text", 14f, "#E2E8F0", bottomPad = 8))

                val cleanHandle = sender.trim().lowercase().removePrefix("@")
                if (reason == "stranger_message" && cleanHandle !in currentHandles && !cleanHandle.contains(" ")) {
                    itemBox.addView(
                        actionButton("+ 將 @$cleanHandle 加入信任名單 (Approve)", "#334155") {
                            val next = GuardEngine.getTrustedHandles(this).toMutableSet()
                            next.add(cleanHandle)
                            GuardEngine.setTrustedHandles(this, next)
                            GuardEngine.syncTrustedFromParent(this)
                            Toast.makeText(this, "已將 @$cleanHandle 加入信任名單並同步", Toast.LENGTH_SHORT).show()
                            renderUi()
                        }
                    )
                }
                inboxCard.addView(itemBox)
            }

            inboxCard.addView(
                actionButton("🗑️ 清除警報記錄 (Clear History)", "#475569") {
                    GuardEngine.clearParentAlerts(this)
                    renderUi()
                }
            )
        }
        rootContainer.addView(inboxCard)
    }

    // ================= CHILD MODE UI =================
    private fun renderChildDashboard() {
        val notifOn = GuardEngine.isNotificationListenerEnabledStatic(this)
        val accOn = GuardEngine.isAccessibilityEnabledStatic(this)
        val allReady = notifOn && accOn
        val unlockRemainingSec = GuardEngine.getUnlockRemainingSeconds(this)
        val lockoutRemainingSec = GuardEngine.checkPasswordLockoutRemainingSec(this)
        val ownHandles = GuardEngine.getChildOwnHandles(this)

        val statusCard = makeCard()
        statusCard.addView(
            label(
                if (allReady) "✅ 端對端加密保護已全面啟動 🔒" else "⚠️ 尚有保護權限未開啟 (Setup Incomplete)",
                18f,
                if (allReady) "#4ADE80" else "#FBBF24",
                bold = true,
                bottomPad = 10
            )
        )
        statusCard.addView(
            label(
                "• 家庭安全核對圖案：${GuardEngine.getSafetyEmojis(this)}\n" +
                    "• 步驟 1 訊息通知讀取：${if (notifOn) "已開啟 ✅" else "未開啟 ❌"}\n" +
                    "• 步驟 2 實時對話掃描及防篡改鎖：${if (accOn) "已開啟 ✅" else "未開啟 ❌"}\n" +
                    "• 定時心跳回報：每 30–95 分鐘隨機向家長手機加密回報連線狀態\n" +
                    "• 保護鎖狀態：${if (unlockRemainingSec > 0) "⏳ 已臨時解鎖（剩餘 ${unlockRemainingSec / 60} 分 ${unlockRemainingSec % 60} 秒）" else "已鎖定 🔒（可正常使用 IG DM，最新陌生人/色情訊息會即時加密通知家長）"}\n" +
                    "• 自動識別子女 IG 帳號：${if (ownHandles.isEmpty()) "(收到首個訊息時自動識別)" else ownHandles.joinToString(", ") { "@$it" }}",
                14f,
                "#E2E8F0",
                bottomPad = 16
            )
        )

        if (!allReady) {
            statusCard.addView(
                actionButton("🪄 彈出權限設定精靈 (Open Setup Popup Wizard)", "#16A34A") {
                    checkAndShowGuidedPermissionPopup()
                }
            )
            statusCard.addView(
                actionButton("🔓 先去解除 Android「限制設定」(Allow Restricted Settings)", "#475569") {
                    openAppDetailsSettings()
                }
            )
        }

        statusCard.addView(
            actionButton("💓 立即發送測試及心跳回報去家長手機 (Send Heartbeat & Test)", "#4F46E5") {
                Toast.makeText(this, "正在加密發送心跳及測試通知...", Toast.LENGTH_SHORT).show()
                GuardEngine.checkAndSendChildHeartbeatIfDue(this, force = true)
                uiHandler.postDelayed({ renderUi() }, 1200L)
            }
        )
        rootContainer.addView(statusCard)

        // 15-Minute Temporary Unlock Card on Child Phone (with 5-attempt brute-force lockout)
        val unlockCard = makeCard()
        unlockCard.addView(label("🔑 輸入家長 15 分鐘臨時解鎖碼 (6-Char Unlock Code)", 16f, "#4ADE80", bold = true, bottomPad = 8))
        if (lockoutRemainingSec > 0) {
            unlockCard.addView(
                label(
                    "⛔ 密碼錯誤次數過多！已鎖定輸入（剩餘 ${lockoutRemainingSec / 60} 分 ${lockoutRemainingSec % 60} 秒）",
                    14f,
                    "#EF4444",
                    bold = true,
                    bottomPad = 12
                )
            )
        } else {
            unlockCard.addView(
                label(
                    "請輸入家長手機產生嘅 6 位英數「15 分鐘臨時解鎖碼」（或輸入 8+ 位家長主密碼解鎖 3 分鐘）。連續錯誤 5 次將自動鎖定 10 分鐘並通知家長：",
                    13f,
                    "#CBD5E1",
                    bottomPad = 12
                )
            )
        }
        val codeInput = inputBox("輸入 6 位臨時解鎖碼 或 家長主密碼", "", isPassword = true)
        unlockCard.addView(codeInput)

        unlockCard.addView(
            actionButton("🔓 驗證並臨時解鎖 (Verify & Unlock)", "#16A34A") {
                val lockRem = GuardEngine.checkPasswordLockoutRemainingSec(this)
                if (lockRem > 0) {
                    Toast.makeText(this, "⛔ 已鎖定，請等 ${lockRem} 秒後再試", Toast.LENGTH_LONG).show()
                    renderUi()
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
                        "@child_phone",
                        "子女手機已使用 6 位臨時解鎖碼解鎖 15 分鐘",
                        bypassDedupe = true
                    )
                    Toast.makeText(this, "✅ 已成功解鎖 15 分鐘！", Toast.LENGTH_LONG).show()
                    renderUi()
                } else if (entered == GuardEngine.getPin(this)) {
                    GuardEngine.recordPasswordAttempt(this, true)
                    GuardEngine.grantTemporaryUnlock(this, 3 * 60 * 1000L)
                    codeInput.setText("")
                    Toast.makeText(this, "✅ 家長主密碼正確：已解鎖 3 分鐘", Toast.LENGTH_LONG).show()
                    renderUi()
                } else {
                    GuardEngine.recordPasswordAttempt(this, false)
                    Toast.makeText(this, "❌ 解鎖碼或主密碼不正確！", Toast.LENGTH_SHORT).show()
                    renderUi()
                }
            }
        )

        if (unlockRemainingSec > 0) {
            unlockCard.addView(
                actionButton("🔒 立即重新上鎖 (Re-Lock Now)", "#D97706") {
                    GuardEngine.grantTemporaryUnlock(this, 0L)
                    Toast.makeText(this, "🔒 已重新啟動保護鎖", Toast.LENGTH_SHORT).show()
                    renderUi()
                }
            )
        }
        rootContainer.addView(unlockCard)
    }

    private fun renderCollapsibleDiagnostics() {
        val diagCard = makeCard()
        diagCard.addView(
            actionButton(
                if (showAdvancedLogs) "🔽 隱藏進階系統診斷紀錄 (Hide Technical Logs)" else "🔧 顯示進階系統診斷紀錄 (Show Technical Checkpoints)",
                "#334155"
            ) {
                showAdvancedLogs = !showAdvancedLogs
                renderUi()
            }
        )
        if (showAdvancedLogs) {
            diagCard.addView(
                label(
                    "Last TLS/E2EE Status: ${GuardEngine.cpLastHttpStatus}\n\n" + GuardEngine.getCheckpointLogs(this),
                    12f,
                    "#E2E8F0",
                    topPad = 8,
                    bottomPad = 12
                )
            )
            diagCard.addView(
                actionButton("🧹 清除診斷紀錄 (Clear Logs)", "#475569") {
                    GuardEngine.clearCheckpointLogs(this)
                }
            )
        }
        rootContainer.addView(diagCard)
    }

    private fun renderRoleSwitchFooter(requirePin: Boolean) {
        val card = makeCard()
        card.addView(label("⚙️ 重設手機身份及遠端金鑰管理 (Role & Key Management)", 15f, "#94A3B8", bold = true, bottomPad = 8))
        if (requirePin) {
            card.addView(
                label(
                    "💡 如忘記家長主密碼，可在【家長手機】點選「🔓 遠端解除子女手機保護並重設」，子女手機會自動解鎖並返回初始畫面。",
                    12f,
                    "#38BDF8",
                    bottomPad = 10
                )
            )
            val pinInput = inputBox("輸入 8+ 位家長主密碼以切換角色", "", isPassword = true)
            card.addView(pinInput)
            card.addView(
                actionButton("切換手機角色 / 重新配對 (需要家長主密碼)", "#475569") {
                    if (GuardEngine.checkPasswordLockoutRemainingSec(this) > 0) {
                        Toast.makeText(this, "⛔ 密碼輸入已鎖定，請從家長手機遠端重設", Toast.LENGTH_LONG).show()
                        return@actionButton
                    }
                    if (pinInput.text.toString().trim() == GuardEngine.getPin(this)) {
                        GuardEngine.recordPasswordAttempt(this, true)
                        GuardEngine.setRole(this, "unconfigured")
                        syncRoleServices()
                        renderUi()
                    } else {
                        GuardEngine.recordPasswordAttempt(this, false)
                        Toast.makeText(this, "家長主密碼錯誤！", Toast.LENGTH_SHORT).show()
                        renderUi()
                    }
                }
            )
        } else {
            card.addView(
                actionButton("🔄 更換全新家庭金鑰（自動同步更新子女手機金鑰）", "#0284C7") {
                    GuardEngine.rotateKeyAndNotifyChild(this, resetChildToUnconfigured = false)
                    stopService(Intent(this, ParentAlertService::class.java))
                    syncRoleServices()
                    Toast.makeText(this, "✅ 已更換金鑰並透過加密通道自動更新子女手機！", Toast.LENGTH_LONG).show()
                    renderUi()
                }
            )
            card.addView(
                actionButton("🔓 遠端解除子女手機保護並重設角色 (忘記密碼救援)", "#D97706") {
                    GuardEngine.rotateKeyAndNotifyChild(this, resetChildToUnconfigured = true)
                    Toast.makeText(this, "✅ 已發送遠端解鎖及重設指令去子女手機！", Toast.LENGTH_LONG).show()
                }
            )
            card.addView(
                actionButton("切換此部家長手機角色 (Switch Role)", "#475569") {
                    GuardEngine.setRole(this, "unconfigured")
                    syncRoleServices()
                    renderUi()
                }
            )
        }
        rootContainer.addView(card)
    }

    private fun makeCard(): LinearLayout {
        val bg = GradientDrawable().apply {
            setColor(Color.parseColor("#1E293B"))
            cornerRadius = 28f
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg
            setPadding(36, 36, 36, 36)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(0, 0, 0, 32)
            layoutParams = lp
        }
    }

    private fun makeAlertItemBox(reason: String): LinearLayout {
        val borderColor = when (reason) {
            "sexual_or_grooming_content" -> "#EF4444"
            "tamper_attempt" -> "#F97316"
            "stranger_message" -> "#EAB308"
            "heartbeat_ok", "pairing_verified" -> "#4ADE80"
            else -> "#38BDF8"
        }
        val bg = GradientDrawable().apply {
            setColor(Color.parseColor("#0F172A"))
            cornerRadius = 20f
            setStroke(3, Color.parseColor(borderColor))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg
            setPadding(28, 24, 28, 24)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(0, 0, 0, 20)
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

    private fun inputBox(hintText: String, initialValue: String, isPassword: Boolean = false): EditText {
        val bg = GradientDrawable().apply {
            setColor(Color.parseColor("#0F172A"))
            cornerRadius = 18f
            setStroke(2, Color.parseColor("#334155"))
        }
        return EditText(this).apply {
            hint = hintText
            setText(initialValue)
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            background = bg
            setPadding(28, 24, 28, 24)
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

    private fun actionButton(title: String, hexBg: String, onClick: () -> Unit): Button {
        val bg = GradientDrawable().apply {
            setColor(Color.parseColor(hexBg))
            cornerRadius = 20f
        }
        return Button(this).apply {
            text = title
            setTextColor(Color.WHITE)
            isAllCaps = false
            textSize = 14f
            background = bg
            setPadding(24, 24, 24, 24)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(0, 0, 0, 18)
            layoutParams = lp
            setOnClickListener { onClick() }
        }
    }
}