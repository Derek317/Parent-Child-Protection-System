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
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.math.abs

object GuardEngine {
    const val ACTION_UI_REFRESH = "com.example.igguard.ACTION_UI_REFRESH"
    private const val PREFS_NAME = "ig_guard_prefs"

    // 10-minute cross-source deduplication window for identical (reason|sender|message)
    private const val DEDUPE_WINDOW_MS = 600_000L
    private val recentAlerts = LinkedHashMap<String, Long>()
    private val seenNotifMsgTimestamps = LinkedHashSet<String>()
    private val pollLock = Any()

    // Live Checkpoint States
    @Volatile var cpNotifConnected: Boolean = false
    @Volatile var cpLastNotifTime: String = "Never"
    @Volatile var cpAccConnected: Boolean = false
    @Volatile var cpLastAccTime: String = "Never"
    @Volatile var cpLastHttpStatus: String = "Ready"
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

    // Timestamp until which DM sexual-block & Settings anti-tamper are unlocked (via 15-min code or setup grace)
    @Volatile
    var pauseTamperUntilMs: Long = 0L

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun nowTimeStr(): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

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

    // Generates a deterministic 4-digit 15-minute unlock code from the Family Token + 15-min time bucket
    // Works both offline (time-bucket math) AND online (pushed over the relay channel)
    fun generate15MinUnlockCode(ctx: Context, bucketOffset: Long = 0L): String {
        val token = getOrCreateFamilyToken(ctx)
        val bucket = (System.currentTimeMillis() / 900_000L) + bucketOffset
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("unlock15:$token:$bucket".toByteArray(Charsets.UTF_8))
        val num = ((digest[0].toInt() and 0xFF) shl 24) or
            ((digest[1].toInt() and 0xFF) shl 16) or
            ((digest[2].toInt() and 0xFF) shl 8) or
            (digest[3].toInt() and 0xFF)
        val fourDigit = abs(num) % 10000
        return "%04d".format(Locale.US, fourDigit)
    }

    // Verifies a 4-digit 15-minute code entered on the Child Phone (checks current/adjacent 15-min buckets + synced code)
    fun verify15MinUnlockCode(ctx: Context, inputCode: String): Boolean {
        val clean = inputCode.trim()
        if (clean.length != 4) return false
        for (offset in -1L..1L) {
            if (clean == generate15MinUnlockCode(ctx, offset)) return true
        }
        val syncedCode = prefs(ctx).getString("synced_15m_code", "") ?: ""
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
        val topic = getChannelTopic(ctx)
        val payload = JSONObject().apply {
            put("type", "unlock_code")
            put("code", code)
            put("exp", exp)
            put("time", SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date()))
        }.toString()
        postToChannel(ctx, topic, payload, null)
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

    fun regenerateFamilyToken(ctx: Context): String {
        val fresh = UUID.randomUUID().toString()
        prefs(ctx).edit().putString("family_uuid_token", fresh).apply()
        return fresh
    }

    fun getPin(ctx: Context): String = prefs(ctx).getString("parent_pin", "1234") ?: "1234"
    fun setPin(ctx: Context, pin: String) = prefs(ctx).edit().putString("parent_pin", pin.trim().ifEmpty { "1234" }).apply()

    fun hasSentInitialSetupConfirmation(ctx: Context): Boolean = prefs(ctx).getBoolean("sent_setup_ok", false)
    fun markInitialSetupConfirmationSent(ctx: Context, sent: Boolean) = prefs(ctx).edit().putBoolean("sent_setup_ok", sent).apply()

    // shortcut: uses public ntfy.sh JSON pub/sub topic derived via SHA-256 of 128-bit UUID token, upgrade to self-hosted relay for enterprise scale
    fun getChannelTopic(ctx: Context): String {
        val token = getOrCreateFamilyToken(ctx)
        val digest = MessageDigest.getInstance("SHA-256").digest("ig_guard_hk_v4:$token".toByteArray(Charsets.UTF_8))
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
        return buildString {
            append("🛡️ 【IG Teen Guard 家庭專屬配對邀請】\n")
            append("核對圖案 (Safety Emojis): $emojis\n\n")
            append("步驟 1：在子女手機複製此整段訊息，然後打開 IG Guard 即可自動完成配對！\n")
            append("或直接點選專屬配對連結：\n")
            append("igguard://pair?token=$token&pin=$pin\n\n")
            append("[IGGUARD-PAIR:$token:$pin]")
        }
    }

    fun parsePairingString(raw: String): Pair<String, String>? {
        val text = raw.trim()
        val tagMatch = Regex("IGGUARD-PAIR:([a-zA-Z0-9\\-]{8,64}):(\\d{4,8})").find(text)
        if (tagMatch != null) {
            return tagMatch.groupValues[1] to tagMatch.groupValues[2]
        }
        if (text.contains("token=")) {
            val token = Regex("token=([a-zA-Z0-9\\-]{8,64})").find(text)?.groupValues?.get(1)
            val pin = Regex("pin=(\\d{4,8})").find(text)?.groupValues?.get(1) ?: "1234"
            if (!token.isNullOrEmpty()) return token to pin
        }
        val uuidMatch = Regex("^[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}$").find(text)
        if (uuidMatch != null) {
            return uuidMatch.value to "1234"
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

    fun evaluateDm(ctx: Context, senderTitle: String, messageBody: String): String? {
        val text = messageBody.trim()
        val senderNorm = senderTitle.trim().lowercase().removePrefix("@")
        if (text.isEmpty() || IGNORED_NOTIFICATIONS.matches(text)) return null
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

        if (!shouldSendAlert(dedupeKey, bypassDedupe = bypassDedupe || reason == "test_alert")) {
            addCheckpointLog(ctx, "CP2-DEDUPE", "Skipped duplicate: @$cleanSender ($cleanText)")
            onComplete?.invoke(false, "Duplicate suppressed")
            return
        }

        addCheckpointLog(ctx, "CP2-MATCH", "[$reason] @$cleanSender: ${cleanText.take(45)}")

        val topic = getChannelTopic(ctx)
        val payload = JSONObject().apply {
            put("type", "alert")
            put("reason", reason)
            put("sender", cleanSender)
            put("text", cleanText)
            put("time", SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date()))
            put("trusted_csv", getTrustedHandles(ctx).joinToString(","))
        }.toString()

        postToChannel(ctx, topic, payload, onComplete)
    }

    fun syncTrustedFromParent(ctx: Context) {
        val topic = getChannelTopic(ctx)
        val payload = JSONObject().apply {
            put("type", "sync_trusted")
            put("trusted_csv", getTrustedHandles(ctx).joinToString(","))
            put("time", SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date()))
        }.toString()
        postToChannel(ctx, topic, payload, null)
    }

    private fun postToChannel(
        ctx: Context,
        topic: String,
        jsonBody: String,
        onComplete: ((Boolean, String) -> Unit)?
    ) {
        thread {
            try {
                val conn = (URL("https://ntfy.sh/$topic").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8000
                    readTimeout = 8000
                    doOutput = true
                    setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                }
                conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                conn.disconnect()

                val statusMsg = if (code in 200..299) {
                    "HTTP $code OK"
                } else if (code == 429) {
                    "HTTP 429 Rate Limit"
                } else {
                    "HTTP $code Error"
                }
                cpLastHttpStatus = "${nowTimeStr()} -> $statusMsg"
                addCheckpointLog(ctx, "CP3-NET", statusMsg)
                onComplete?.invoke(code in 200..299, statusMsg)
            } catch (e: Exception) {
                val err = "Net Fail: ${e.javaClass.simpleName}"
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
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                cpLastHttpStatus = "${nowTimeStr()} -> Poll HTTP $code"
                conn.disconnect()
                return
            }

            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                var line: String? = null
                while (reader.readLine().also { line = it } != null) {
                    processIncomingRelayLine(ctx, line!!, role, onNewAlertForParent)
                }
            }
            cpLastHttpStatus = "${nowTimeStr()} -> Poll HTTP 200 OK"
            conn.disconnect()
        } catch (e: Exception) {
            cpLastHttpStatus = "${nowTimeStr()} -> Poll Err: ${e.javaClass.simpleName}"
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

        val innerStr = outer.optString("message")
        val inner = try { JSONObject(innerStr) } catch (_: Exception) { return }
        val msgType = inner.optString("type")

        val seenIds = prefs(ctx).getStringSet("seen_msg_ids", emptySet())?.toMutableSet() ?: mutableSetOf()
        if (seenIds.contains(msgId)) return

        seenIds.add(msgId)
        val trimmed = if (seenIds.size > 300) seenIds.toList().takeLast(300).toSet() else seenIds
        prefs(ctx).edit().putStringSet("seen_msg_ids", trimmed).apply()

        if (msgType == "sync_trusted") {
            val csv = inner.optString("trusted_csv")
            if (csv.isNotEmpty()) {
                val list = csv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                setTrustedHandles(ctx, list)
                addCheckpointLog(ctx, "CP4-SYNC", "Updated trusted list (${list.size} handles)")
            }
        } else if (msgType == "unlock_code") {
            val code = inner.optString("code")
            val exp = inner.optLong("exp", 0L)
            if (code.isNotEmpty() && exp > 0L) {
                prefs(ctx).edit()
                    .putString("synced_15m_code", code)
                    .putLong("synced_15m_exp", exp)
                    .apply()
                addCheckpointLog(ctx, "CP4-SYNC", "Received 15-min unlock code from Parent")
            }
        } else if (msgType == "alert" && role == "parent") {
            saveAlertToParentHistory(ctx, inner)
            addCheckpointLog(ctx, "CP4-RECV", "Received ${inner.optString("reason")} from ${inner.optString("sender")}")
            onNewAlertForParent?.invoke(inner)
            ctx.sendBroadcast(Intent(ACTION_UI_REFRESH).setPackage(ctx.packageName))
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
}

// 1. CHILD ONLY: Reads all incoming Instagram DM notifications in background
class IgNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        GuardEngine.cpNotifConnected = true
        GuardEngine.addCheckpointLog(this, "CP1-NOTIF", "NotificationListener CONNECTED ✅")
    }

    override fun onListenerDisconnected() {
        GuardEngine.cpNotifConnected = false
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
            for (p in msgBundles) {
                val b = p as? Bundle ?: continue
                val msgText = b.getCharSequence("text")?.toString()?.trim() ?: ""
                val msgSender = b.getCharSequence("sender")?.toString()?.trim() ?: ""
                val msgTime = b.getLong("time", 0L)

                if (msgText.isEmpty()) continue

                if (convHeader.isNotEmpty() && msgSender.isNotEmpty() &&
                    !convHeader.equals(msgSender, ignoreCase = true) && !convHeader.contains(" ")
                ) {
                    GuardEngine.rememberChildOwnHandle(this, convHeader)
                }

                val effectiveSender = msgSender.ifEmpty { rawTitle.ifEmpty { convHeader } }
                val tsSig = "$effectiveSender|$msgTime|$msgText"
                if (msgTime > 0L && !GuardEngine.isNewNotificationMessageTimestamp(tsSig)) {
                    continue
                }
                extractedPairs.add(effectiveSender to msgText)
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
                GuardEngine.addCheckpointLog(this, "CP2-TRUSTED", "Ignored trusted/self/system: @$sender")
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

// 2. CHILD ONLY: Reads active Instagram DMs in real time WITHOUT kicking child out of the DM Inbox!
class IgAccessibilityService : AccessibilityService() {

    private var lastScanMs = 0L

    private data class ScreenTextItem(
        val text: String,
        val top: Int,
        val bottom: Int,
        val left: Int,
        val right: Int,
        val isEditable: Boolean
    )

    private val igUiChrome = setOf(
        "message...", "訊息⋯⋯", "訊息...", "發送訊息...", "傳送訊息...", "send", "傳送", "search", "搜尋",
        "instagram", "chats", "聊天室", "requests", "陌生訊息", "general", "一般", "primary", "主要",
        "active now", "目前在線上", "online", "在線", "typing...", "輸入中...", "seen", "已讀",
        "today", "今天", "yesterday", "昨天", "reply", "回覆", "messages", "訊息", "back", "返回",
        "audio call", "語音通話", "video call", "視訊通話", "new message", "新訊息"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        GuardEngine.cpAccConnected = true
        GuardEngine.grantTemporaryUnlock(this, 60_000L)
        GuardEngine.addCheckpointLog(this, "CP1-ACC", "Accessibility Connected ✅ (60s setup grace active)")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!GuardEngine.isChildMode(this)) return

        val pkg = event?.packageName?.toString() ?: return
        val now = System.currentTimeMillis()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && now - lastScanMs < 450L) {
            return
        }
        lastScanMs = now
        GuardEngine.cpAccConnected = true
        GuardEngine.cpLastAccTime = GuardEngine.nowTimeStr()

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

        // B. Inside native Instagram app: allow child to enter DM Inbox & chat freely while intercepting active DMs!
        if (pkg == "com.instagram.android") {
            // 1. Only block explicit in-app mute screens (Fixed: removed the broad "通知+訊息+關閉" rule that falsely kicked child out of DM Inbox!)
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

            // 2. Detect if child is inside an ACTIVE 1-on-1 DM Conversation (vs. the DM Inbox list or Reels/Feed)
            // An active DM chat has a message input bar in the bottom 35% of the screen
            val hasBottomComposer = items.any { item ->
                item.top > (screenHeight * 0.60).toInt() && (
                    item.isEditable ||
                        item.text.contains("Message...", ignoreCase = true) ||
                        item.text.contains("發送訊息") ||
                        item.text.contains("傳送訊息") ||
                        item.text.contains("訊息...") ||
                        item.text.contains("訊息⋯")
                    )
            }

            if (!hasBottomComposer) {
                // Child is on the DM Inbox list or Feed -> DO NOT kick them out or scan inbox preview snippets!
                return
            }

            // 3. Extract the conversation partner's name/handle from the top header bar (top 22% of screen)
            val ownHandles = GuardEngine.getChildOwnHandles(this)
            val headerCandidates = items.filter { item ->
                val clean = item.text.trim()
                val low = clean.lowercase().removePrefix("@")
                item.bottom <= (screenHeight * 0.24).toInt() &&
                    !item.isEditable &&
                    clean.length in 2..35 &&
                    low !in igUiChrome &&
                    low !in ownHandles &&
                    !low.matches(Regex("^\\d{1,2}:\\d{2}.*"))
            }

            // Prefer the handle (@username) or display name in the header
            val peerHeader = headerCandidates.lastOrNull()?.text?.trim() ?: "Instagram_Open_Chat"
            val isPeerTrusted = GuardEngine.isTrustedSender(this, peerHeader, "")

            // 4. Extract visible message bubbles in the middle chat area (between 18% and 82% of screen height)
            val chatBubbles = items.filter { item ->
                val clean = item.text.trim()
                val low = clean.lowercase().removePrefix("@")
                item.top >= (screenHeight * 0.18).toInt() &&
                    item.bottom <= (screenHeight * 0.84).toInt() &&
                    !item.isEditable &&
                    clean != peerHeader &&
                    clean.length >= 2 &&
                    low !in igUiChrome &&
                    low !in ownHandles &&
                    !low.matches(Regex("^\\d{1,2}:\\d{2}.*"))
            }

            // 5. Inspect active chat bubbles in real time while child is chatting
            for (bubble in chatBubbles) {
                val msgText = bubble.text.trim()
                if (GuardEngine.SEXUAL_PATTERN.containsMatchIn(msgText)) {
                    // Always alert parent when sexual/grooming text appears in an active chat
                    GuardEngine.sendAlertFromChild(this, "sexual_or_grooming_content", peerHeader, msgText)
                    // Only back out of the thread if NOT unlocked by Parent's 15-minute code
                    if (!dmUnlocked) {
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        return
                    }
                } else if (!isPeerTrusted) {
                    // Actively intercept stranger DM messages while child is chatting (without kicking child out!)
                    GuardEngine.sendAlertFromChild(this, "stranger_message", peerHeader, msgText)
                }
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

// 3. PARENT ONLY: Persistent Real-Time Stream Receiver
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
            .setContentTitle("IG Guard 家長守護連線中")
            .setContentText("核對圖案: ${GuardEngine.getSafetyEmojis(this)} • 實時監測子女 IG 警報")
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
                    val topic = GuardEngine.getChannelTopic(applicationContext)
                    var conn: HttpURLConnection? = null
                    try {
                        GuardEngine.cpParentStreamState = "Connecting stream..."
                        val url = URL("https://ntfy.sh/$topic/json?since=2h")
                        conn = (url.openConnection() as HttpURLConnection).apply {
                            requestMethod = "GET"
                            connectTimeout = 10_000
                            readTimeout = 90_000
                        }
                        val code = conn.responseCode
                        if (code in 200..299) {
                            GuardEngine.cpParentStreamState = "Live Stream CONNECTED ✅"
                            GuardEngine.addCheckpointLog(applicationContext, "CP4-STREAM", "Connected to live stream")
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
                            GuardEngine.cpParentStreamState = "Stream HTTP $code (Retrying in 15s)"
                            GuardEngine.addCheckpointLog(applicationContext, "CP4-ERR", "Stream returned HTTP $code")
                        }
                    } catch (e: Exception) {
                        GuardEngine.cpParentStreamState = "Reconnecting (${e.javaClass.simpleName})..."
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
            "tamper_attempt" -> "⚠️ 子女嘗試更改保護設定"
            "stranger_message" -> "💬 陌生人私訊: $sender"
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
            .setStyle(Notification.BigTextStyle().bigText("帳號: $sender\n內容: $text"))
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

// 4. All-in-One Parent & Child UI with 15-Min Unlock Code Generator & Active DM Interception
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
        Toast.makeText(this, "✅ 已透過專屬連結完成家庭配對！", Toast.LENGTH_LONG).show()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(GuardEngine.ACTION_UI_REFRESH)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(refreshReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(refreshReceiver, filter)
        }
        if (GuardEngine.isChildMode(this) && isNotificationListenerEnabled(this) && Build.VERSION.SDK_INT >= 24) {
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

        val notifOn = isNotificationListenerEnabled(this)
        val accOn = isAccessibilityEnabled(this)

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
            GuardEngine.sendAlertFromChild(
                this,
                "pairing_verified",
                Build.MODEL ?: "Child_Phone",
                "子女手機 (${Build.MODEL}) 已完成所有權限設定並啟動保護！核對圖案: ${GuardEngine.getSafetyEmojis(this)}",
                bypassDedupe = true
            )
            Toast.makeText(this, "🎉 所有保護已成功啟動！已通知家長手機", Toast.LENGTH_LONG).show()
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
                thread { GuardEngine.pollChannelOnce(applicationContext, since = "2h") }
            }
        }
    }

    private fun renderUi() {
        rootContainer.removeAllViews()
        val role = GuardEngine.getRole(this)

        rootContainer.addView(label("IG Teen Guard v4.1 • 家庭守護系統", 22f, "#F8FAFC", bold = true))
        val badgeText = when (role) {
            "parent" -> "👨‍👩‍👧 家長手機模式 (Parent Phone) • 核對圖案: ${GuardEngine.getSafetyEmojis(this)}"
            "child" -> "🧒 子女保護模式 🔒 (Child Phone) • 核對圖案: ${GuardEngine.getSafetyEmojis(this)}"
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
                "系統會自動產生全球唯一嘅 128-bit 加密家庭金鑰（絕不會同其他家庭撞碼）。請設定 4 位數家長主密碼，然後按下方按鈕。",
                13f,
                "#CBD5E1",
                bottomPad = 14
            )
        )
        val pinInput = inputBox("設定 4 位數家長主密碼 (Master PIN, 預設 1234)", GuardEngine.getPin(this), isPassword = false)
        parentCard.addView(pinInput)

        parentCard.addView(
            actionButton("👨‍👩‍👧 設定為「家長手機」並產生專屬配對邀請", "#0284C7") {
                GuardEngine.setPin(this, pinInput.text.toString())
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
                "先喺家長手機按「傳送配對邀請」去子女 WhatsApp / SMS，喺子女手機【複製】該段訊息，然後按下方綠色按鈕即可一鍵完成配對！",
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
        // 1. 15-Minute Temporary Unlock Code Generator Card (New!)
        val unlockGenCard = makeCard()
        unlockGenCard.addView(label("🔑 產生 15 分鐘子女臨時解鎖碼 (15-Min Unlock Code)", 17f, "#4ADE80", bold = true, bottomPad = 8))
        unlockGenCard.addView(
            label(
                "無需透露您嘅家長主密碼！按下方按鈕即可產生一個 4 位數「15 分鐘臨時解鎖碼」，讓子女在手機輸入後暫時解鎖 15 分鐘（離線及連線均有效）。",
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
            actionButton("🔑 立即產生 4 位數 15 分鐘解鎖碼 (Generate 4-Digit Code)", "#16A34A") {
                val code = GuardEngine.generate15MinUnlockCode(this)
                displayed15MinCode = code
                GuardEngine.push15MinUnlockCodeFromParent(this, code)
                Toast.makeText(this, "✅ 已產生 15 分鐘解鎖碼：$code", Toast.LENGTH_LONG).show()
                renderUi()
            }
        )
        rootContainer.addView(unlockGenCard)

        // 2. Exclusive Family Pairing & Invite Card
        val pairCard = makeCard()
        pairCard.addView(label("🔗 配對子女手機 (防撞碼專屬金鑰)", 17f, "#FFFFFF", bold = true, bottomPad = 8))
        pairCard.addView(
            label(
                "• 家庭安全核對圖案：${GuardEngine.getSafetyEmojis(this)}\n" +
                    "• 實時連線狀態：${GuardEngine.cpParentStreamState}",
                13f,
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

        // 3. Trusted Friends Card
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
            actionButton("💾 儲存並同步信任名單去子女手機", "#0284C7") {
                val updated = handlesInput.text.toString().split(",")
                GuardEngine.setTrustedHandles(this, updated)
                GuardEngine.syncTrustedFromParent(this)
                Toast.makeText(this, "✅ 已儲存並自動同步去子女手機！", Toast.LENGTH_SHORT).show()
                renderUi()
            }
        )
        rootContainer.addView(trustCard)

        // 4. Live Alert Inbox Card
        val inboxCard = makeCard()
        inboxCard.addView(label("🚨 子女 IG 即時警報記錄 (Child Alert Inbox)", 17f, "#FFFFFF", bold = true, bottomPad = 12))

        inboxCard.addView(
            actionButton("🔄 立即刷新最新警報 (Refresh Now)", "#334155") {
                Toast.makeText(this, "正在同步最新警報...", Toast.LENGTH_SHORT).show()
                thread { GuardEngine.pollChannelOnce(applicationContext, since = "6h") }
            }
        )

        val alerts = GuardEngine.getParentAlerts(this)
        if (alerts.isEmpty()) {
            inboxCard.addView(
                label(
                    "暫時未有警報。子女可以正常進入 IG DM 傾偈，背景通知與畫面實時掃描均已生效。",
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
                    "tamper_attempt" -> "🟠 嘗試更改設定警告"
                    "stranger_message" -> "🟡 陌生人私訊 (Stranger DM)"
                    "pairing_verified" -> "🟢 子女手機配對成功"
                    else -> "🔔 系統通知"
                }
                itemBox.addView(label("$badge  •  $time", 12f, "#F8FAFC", bold = true, bottomPad = 4))
                itemBox.addView(label("發送者 (Sender): $sender", 14f, "#38BDF8", bold = true, bottomPad = 4))
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
        val notifOn = isNotificationListenerEnabled(this)
        val accOn = isAccessibilityEnabled(this)
        val allReady = notifOn && accOn
        val unlockRemainingSec = GuardEngine.getUnlockRemainingSeconds(this)
        val ownHandles = GuardEngine.getChildOwnHandles(this)

        val statusCard = makeCard()
        statusCard.addView(
            label(
                if (allReady) "✅ 所有保護已全面啟動 (可正常使用 IG DM)" else "⚠️ 尚有保護權限未開啟 (Setup Incomplete)",
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
                    "• 保護鎖狀態：${if (unlockRemainingSec > 0) "⏳ 已臨時解鎖（剩餘 ${unlockRemainingSec / 60} 分 ${unlockRemainingSec % 60} 秒）" else "已鎖定 🔒（子女可正常使用 IG DM，陌生人/色情訊息會即時通知家長）"}\n" +
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
            actionButton("🔔 發送測試通知去家長手機 (Send Test Alert)", "#4F46E5") {
                Toast.makeText(this, "正在發送測試警報...", Toast.LENGTH_SHORT).show()
                GuardEngine.sendAlertFromChild(
                    this,
                    "test_alert",
                    "@child_phone_test",
                    "測試成功！子女手機 (${GuardEngine.getSafetyEmojis(this)}) 連線正常 (${GuardEngine.nowTimeStr()})",
                    bypassDedupe = true
                ) { ok, status ->
                    uiHandler.post {
                        Toast.makeText(
                            this,
                            if (ok) "✅ 已送達家長手機！($status)" else "❌ 發送失敗: $status",
                            Toast.LENGTH_LONG
                        ).show()
                        renderUi()
                    }
                }
            }
        )
        rootContainer.addView(statusCard)

        // 15-Minute Temporary Unlock Card on Child Phone
        val unlockCard = makeCard()
        unlockCard.addView(label("🔑 輸入家長 15 分鐘臨時解鎖碼 (15-Min Unlock)", 16f, "#4ADE80", bold = true, bottomPad = 8))
        unlockCard.addView(
            label(
                "請在家長手機點選「產生 4 位數 15 分鐘解鎖碼」，並在下方輸入該 4 位數號碼以暫時解鎖 15 分鐘（或輸入家長主密碼解鎖 3 分鐘）：",
                13f,
                "#CBD5E1",
                bottomPad = 12
            )
        )
        val codeInput = inputBox("輸入 4 位數 15 分鐘解鎖碼 (4-Digit Code)", "", isPassword = false)
        unlockCard.addView(codeInput)

        unlockCard.addView(
            actionButton("🔓 驗證解鎖碼並解鎖 15 分鐘 (Unlock for 15 Mins)", "#16A34A") {
                val entered = codeInput.text.toString().trim()
                if (GuardEngine.verify15MinUnlockCode(this, entered)) {
                    GuardEngine.grantTemporaryUnlock(this, 15 * 60 * 1000L)
                    codeInput.setText("")
                    GuardEngine.sendAlertFromChild(
                        this,
                        "unlock_used",
                        "@child_phone",
                        "子女手機已使用 4 位數臨時解鎖碼解鎖 15 分鐘",
                        bypassDedupe = true
                    )
                    Toast.makeText(this, "✅ 已成功解鎖 15 分鐘！", Toast.LENGTH_LONG).show()
                    renderUi()
                } else if (entered == GuardEngine.getPin(this)) {
                    GuardEngine.grantTemporaryUnlock(this, 3 * 60 * 1000L)
                    codeInput.setText("")
                    Toast.makeText(this, "✅ 家長主密碼正確：已解鎖 3 分鐘", Toast.LENGTH_LONG).show()
                    renderUi()
                } else {
                    Toast.makeText(this, "❌ 解鎖碼不正確或已過期", Toast.LENGTH_SHORT).show()
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
                    "Last HTTP: ${GuardEngine.cpLastHttpStatus}\n\n" + GuardEngine.getCheckpointLogs(this),
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
        card.addView(label("⚙️ 重設手機身份或更換配對 (Reset Role / Pairing)", 15f, "#94A3B8", bold = true, bottomPad = 8))
        if (requirePin) {
            val pinInput = inputBox("輸入家長主密碼以切換手機角色", "", isPassword = true)
            card.addView(pinInput)
            card.addView(
                actionButton("切換手機角色 / 重新配對 (需要家長主密碼)", "#475569") {
                    if (pinInput.text.toString() == GuardEngine.getPin(this)) {
                        GuardEngine.setRole(this, "unconfigured")
                        syncRoleServices()
                        renderUi()
                    } else {
                        Toast.makeText(this, "家長主密碼錯誤！", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        } else {
            card.addView(
                actionButton("切換手機角色 (Switch Role)", "#475569") {
                    GuardEngine.setRole(this, "unconfigured")
                    syncRoleServices()
                    renderUi()
                }
            )
            card.addView(
                actionButton("🔄 產生全新家庭配對金鑰 (Regenerate Family Key)", "#475569") {
                    GuardEngine.regenerateFamilyToken(this)
                    syncRoleServices()
                    Toast.makeText(this, "已產生全新專屬金鑰，請重新配對子女手機", Toast.LENGTH_LONG).show()
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
            "pairing_verified" -> "#4ADE80"
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
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
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

    private fun isNotificationListenerEnabled(ctx: Context): Boolean {
        val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
        val cn = ComponentName(ctx, IgNotificationListener::class.java).flattenToString()
        return flat.contains(cn)
    }

    private fun isAccessibilityEnabled(ctx: Context): Boolean {
        val flat = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val cn = ComponentName(ctx, IgAccessibilityService::class.java).flattenToString()
        return flat.contains(cn)
    }
}