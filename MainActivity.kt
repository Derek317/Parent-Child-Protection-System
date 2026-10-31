package com.example.igguard

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
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
import kotlin.concurrent.thread

object GuardEngine {
    const val ACTION_UI_REFRESH = "com.example.igguard.ACTION_UI_REFRESH"
    private const val PREFS_NAME = "ig_guard_prefs"

    // Time-windowed deduplication (20 seconds) so repeated messages ("Hi" or "Sent a photo") are NOT permanently dropped
    private const val DEDUPE_WINDOW_MS = 20_000L
    private val recentAlerts = LinkedHashMap<String, Long>()
    private val pollLock = Any()

    // Live Checkpoint States for on-screen debugging
    @Volatile var cpNotifConnected: Boolean = false
    @Volatile var cpLastNotifTime: String = "Never"
    @Volatile var cpAccConnected: Boolean = false
    @Volatile var cpLastAccTime: String = "Never"
    @Volatile var cpLastHttpStatus: String = "No request yet"
    @Volatile var cpParentStreamState: String = "Idle"

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

    @Volatile
    var pauseTamperUntilMs: Long = 0L

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun nowTimeStr(): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

    // Appends a timestamped checkpoint log visible inside the app UI on both phones
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

    fun getPairCode(ctx: Context): String = prefs(ctx).getString("pair_code", "HK-8829") ?: "HK-8829"
    fun setPairCode(ctx: Context, code: String) = prefs(ctx).edit().putString("pair_code", code.trim().uppercase(Locale.US)).apply()

    fun getPin(ctx: Context): String = prefs(ctx).getString("parent_pin", "1234") ?: "1234"
    fun setPin(ctx: Context, pin: String) = prefs(ctx).edit().putString("parent_pin", pin.trim()).apply()

    fun getTrustedHandles(ctx: Context): Set<String> {
        val raw = prefs(ctx).getString("trusted_csv", "mum_hk,dad_hk,school_friend_1") ?: ""
        return raw.split(",").map { it.trim().lowercase().removePrefix("@") }.filter { it.isNotEmpty() }.toSet()
    }

    fun setTrustedHandles(ctx: Context, handles: Collection<String>) {
        val clean = handles.map { it.trim().lowercase().removePrefix("@") }.filter { it.isNotEmpty() }.distinct().sorted()
        prefs(ctx).edit().putString("trusted_csv", clean.joinToString(",")).apply()
    }

    // shortcut: uses public ntfy.sh JSON pub/sub topic derived via SHA-256 of pair code, upgrade to self-hosted relay for enterprise deployments
    fun getChannelTopic(ctx: Context): String {
        val code = getPairCode(ctx).trim().uppercase(Locale.US)
        val digest = MessageDigest.getInstance("SHA-256").digest("ig_guard_hk_v3:$code".toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }.take(24)
        return "ig_hk_$hex"
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
        val trustedSet = getTrustedHandles(ctx)
        val titleNorm = rawTitle.trim().lowercase().removePrefix("@")
        if (titleNorm.isEmpty() || titleNorm == "instagram") return false
        if (titleNorm in trustedSet) return true
        // Also check prefix before colon in title or body (e.g. "Instagram • sender_name" or "sender_name: hello")
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
        if (text.isEmpty() || IGNORED_NOTIFICATIONS.matches(text)) return null
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
        val dedupeKey = "$reason|$cleanSender|$cleanText"

        if (!shouldSendAlert(dedupeKey, bypassDedupe = bypassDedupe || reason == "test_alert")) {
            addCheckpointLog(ctx, "CP2-DEDUPE", "Skipped duplicate within 20s: $cleanSender")
            onComplete?.invoke(false, "Duplicate suppressed (20s window)")
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
                    "HTTP $code OK (Topic: ${topic.takeLast(6)})"
                } else if (code == 429) {
                    "HTTP 429 Rate Limit (ntfy.sh throttled IP)"
                } else {
                    "HTTP $code Error"
                }
                cpLastHttpStatus = "${nowTimeStr()} -> $statusMsg"
                addCheckpointLog(ctx, "CP3-NET", statusMsg)
                onComplete?.invoke(code in 200..299, statusMsg)
            } catch (e: Exception) {
                val err = "Net Fail: ${e.javaClass.simpleName} (${e.message?.take(35)})"
                cpLastHttpStatus = "${nowTimeStr()} -> $err"
                addCheckpointLog(ctx, "CP3-ERR", err)
                onComplete?.invoke(false, err)
            }
        }
    }

    // One-off catch-up poll (used on startup or manual refresh; respects rate limits)
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
                addCheckpointLog(ctx, "CP4-POLL", "Poll returned HTTP $code")
                conn.disconnect()
                return
            }

            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                var line: String?
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

// 1. CHILD ONLY: Reads all incoming Instagram DM notifications (including MessagingStyle bundles!)
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

        val convTitle = (extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)
            ?: "").toString().trim()

        // Extract every message from MessagingStyle EXTRA_MESSAGES array (fixes missed stacked/consecutive DMs!)
        val extractedPairs = ArrayList<Pair<String, String>>()
        val msgBundles = getParcelableArrayCompat(extras, Notification.EXTRA_MESSAGES)
        if (msgBundles != null) {
            for (p in msgBundles) {
                val b = p as? Bundle ?: continue
                val msgText = b.getCharSequence("text")?.toString()?.trim() ?: ""
                val msgSender = (b.getCharSequence("sender") ?: convTitle).toString().trim()
                if (msgText.isNotEmpty()) {
                    extractedPairs.add(msgSender.ifEmpty { convTitle } to msgText)
                }
            }
        }

        // Also check standard EXTRA_BIG_TEXT / EXTRA_TEXT / EXTRA_TEXT_LINES
        val fallbackBody = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: "").toString().trim()
        if (fallbackBody.isNotEmpty()) {
            var sender = convTitle
            var body = fallbackBody
            // Instagram often formats group/stacked DMs as "sender_handle: message"
            if (body.contains(": ")) {
                val possibleSender = body.substringBefore(": ").trim()
                val possibleMsg = body.substringAfter(": ").trim()
                if (possibleSender.isNotEmpty() && !possibleSender.contains(" ") && possibleMsg.isNotEmpty()) {
                    sender = possibleSender
                    body = possibleMsg
                }
            }
            extractedPairs.add(sender to body)
        }

        val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        if (textLines != null) {
            for (cs in textLines) {
                val line = cs?.toString()?.trim() ?: continue
                if (line.contains(": ")) {
                    extractedPairs.add(line.substringBefore(": ").trim() to line.substringAfter(": ").trim())
                } else if (line.isNotEmpty()) {
                    extractedPairs.add(convTitle to line)
                }
            }
        }

        if (extractedPairs.isEmpty()) return

        var wipeNotification = false
        for ((sender, body) in extractedPairs.distinct()) {
            val reason = GuardEngine.evaluateDm(this, sender, body)
            if (reason == null) {
                GuardEngine.addCheckpointLog(this, "CP2-TRUSTED", "Ignored trusted/system notif: @$sender")
                continue
            }
            GuardEngine.sendAlertFromChild(this, reason, sender, body)
            if (reason == "sexual_or_grooming_content") {
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

// 2. CHILD ONLY: Forces Instagram notifications ON + scans live open Instagram DM threads for BOTH strangers & sexual content
class IgAccessibilityService : AccessibilityService() {

    private var lastScanMs = 0L

    // Reserved UI strings inside Instagram DM screens that are not the peer's username or message bubble
    private val igUiChrome = setOf(
        "message...", "訊息⋯⋯", "發送訊息...", "傳送訊息...", "send", "傳送", "search", "搜尋",
        "instagram", "chats", "聊天室", "requests", "陌生訊息", "general", "一般", "primary", "主要",
        "active now", "目前在線上", "online", "在線", "typing...", "輸入中...", "seen", "已讀",
        "today", "今天", "yesterday", "昨天", "reply", "回覆"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        GuardEngine.cpAccConnected = true
        // Grant a 60s setup grace window right when Accessibility is switched ON so the parent isn't kicked out of Settings immediately
        GuardEngine.pauseTamperUntilMs = maxOf(GuardEngine.pauseTamperUntilMs, System.currentTimeMillis() + 60_000L)
        GuardEngine.addCheckpointLog(this, "CP1-ACC", "Accessibility Connected ✅ (60s setup grace active)")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!GuardEngine.isChildMode(this)) return

        val pkg = event?.packageName?.toString() ?: return
        val now = System.currentTimeMillis()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && now - lastScanMs < 400L) {
            return
        }
        lastScanMs = now
        GuardEngine.cpAccConnected = true
        GuardEngine.cpLastAccTime = GuardEngine.nowTimeStr()

        val root = rootInActiveWindow ?: return
        val texts = ArrayList<String>(80)
        collectTexts(root, texts)
        if (texts.isEmpty()) return
        val joined = texts.joinToString(" ")

        val tamperPaused = now < GuardEngine.pauseTamperUntilMs

        // A. Prevent teen from turning off Instagram notifications or disabling IG Guard in Android Settings
        if (!tamperPaused && isSettingsPackage(pkg)) {
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
                    "Child tried to open Settings for Instagram / IG Guard: ${joined.take(100)}"
                )
                return
            }
        }

        // B. Inside native Instagram app:
        if (pkg == "com.instagram.android") {
            // 1. Block muting notifications or muting a DM thread inside Instagram
            val isMutingInApp = joined.contains("Pause all", ignoreCase = true) ||
                joined.contains("暫停所有") ||
                joined.contains("Mute messages", ignoreCase = true) ||
                joined.contains("Mute chat", ignoreCase = true) ||
                joined.contains("將訊息設為靜音") ||
                joined.contains("靜音訊息") ||
                ((joined.contains("Notifications", ignoreCase = true) || joined.contains("通知")) &&
                    (joined.contains("Messages", ignoreCase = true) || joined.contains("訊息")) &&
                    (joined.contains("Off", ignoreCase = true) || joined.contains("關閉")))

            if (!tamperPaused && isMutingInApp) {
                performGlobalAction(GLOBAL_ACTION_HOME)
                GuardEngine.sendAlertFromChild(
                    this,
                    "tamper_attempt",
                    "Instagram In-App Settings",
                    "Child tried to mute notifications or a DM chat inside Instagram."
                )
                return
            }

            // 2. Scan all visible lines for Sexual / Grooming content first
            for (line in texts) {
                if (GuardEngine.SEXUAL_PATTERN.containsMatchIn(line)) {
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    GuardEngine.sendAlertFromChild(this, "sexual_or_grooming_content", "Instagram Live Screen", line)
                    return
                }
            }

            // 3. Live Open-Chat Stranger Detection (catches messages when child is already inside an open DM thread so OS notifications are suppressed!)
            val isInboxList = (joined.contains("Chats", ignoreCase = true) && joined.contains("Requests", ignoreCase = true)) ||
                (joined.contains("聊天室") && joined.contains("陌生訊息"))
            val isInsideDmThread = !isInboxList && texts.any {
                val low = it.lowercase()
                low.contains("message...") || low.contains("發送訊息") || low.contains("傳送訊息") || low.contains("訊息...") || low.contains("訊息⋯")
            }
            if (isInsideDmThread) {
                val candidateHeader = texts.firstOrNull { line ->
                    val low = line.lowercase()
                    line.length in 2..35 &&
                        low !in igUiChrome &&
                        !low.matches(Regex("^\\d{1,2}:\\d{2}.*"))
                } ?: "unknown_open_chat"

                if (!GuardEngine.isTrustedSender(this, candidateHeader, "")) {
                    val sampleMsg = texts.lastOrNull { line ->
                        val low = line.lowercase()
                        line != candidateHeader &&
                            low !in igUiChrome &&
                            !low.contains("message...") &&
                            !low.contains("訊息") &&
                            line.length >= 2
                    } ?: "Active open chat with untrusted account"

                    GuardEngine.sendAlertFromChild(this, "stranger_message", candidateHeader, sampleMsg)
                }
            }
        }
    }

    private fun isSettingsPackage(pkg: String): Boolean {
        return pkg.contains("settings", ignoreCase = true) ||
            pkg == "com.miui.securitycenter" ||
            pkg == "com.coloros.safecenter"
    }

    private fun collectTexts(node: AccessibilityNodeInfo?, out: MutableList<String>) {
        if (node == null || out.size >= 100) return
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        for (i in 0 until node.childCount) {
            collectTexts(node.getChild(i), out)
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

// 3. PARENT ONLY: Persistent Real-Time Stream Receiver (Avoids HTTP 429 rate limits completely!)
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
            .setContentTitle("IG Guard Parent Monitor Active")
            .setContentText("Pair Code: ${GuardEngine.getPairCode(this)} • Live stream connected")
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
                // Single persistent streaming connection with 2h catch-up (1 request total -> avoids HTTP 429 rate limit!)
                while (running && GuardEngine.isParentMode(applicationContext)) {
                    val topic = GuardEngine.getChannelTopic(applicationContext)
                    var conn: HttpURLConnection? = null
                    try {
                        GuardEngine.cpParentStreamState = "Connecting stream..."
                        val url = URL("https://ntfy.sh/$topic/json?since=2h")
                        conn = (url.openConnection() as HttpURLConnection).apply {
                            requestMethod = "GET"
                            connectTimeout = 10_000
                            readTimeout = 90_000 // ntfy sends keepalive newlines every ~45s
                        }
                        val code = conn.responseCode
                        if (code in 200..299) {
                            GuardEngine.cpParentStreamState = "Live Stream CONNECTED ✅ (HTTP 200)"
                            GuardEngine.addCheckpointLog(applicationContext, "CP4-STREAM", "Connected to live stream ($topic)")
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
            "sexual_or_grooming_content" -> "🚨 SEXUAL / GROOMING ALERT: $sender"
            "tamper_attempt" -> "⚠️ SETTINGS TAMPER ATTEMPT"
            "stranger_message" -> "💬 Stranger DM from: $sender"
            else -> "🔔 IG Guard Alert: $sender"
        }

        val openIntent = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notif = Notification.Builder(this, "ig_guard_alerts")
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText("From: $sender\nMessage: $text"))
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

// 4. All-in-One Parent & Child UI Dashboard + Live Checkpoint Debugger
class MainActivity : Activity() {

    private lateinit var rootContainer: LinearLayout
    private val uiHandler = Handler(Looper.getMainLooper())

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
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(GuardEngine.ACTION_UI_REFRESH)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(refreshReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(refreshReceiver, filter)
        }
        // If Notification Listener is enabled in OS settings, ensure Android binds it immediately
        if (GuardEngine.isChildMode(this) && isNotificationListenerEnabled(this) && Build.VERSION.SDK_INT >= 24) {
            try {
                NotificationListenerService.requestRebind(ComponentName(this, IgNotificationListener::class.java))
            } catch (_: Exception) {
            }
        }
        syncRoleServices()
        renderUi()
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(refreshReceiver)
        } catch (_: Exception) {
        }
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

        rootContainer.addView(label("IG Teen Guard v3.1 • 診斷及守護系統", 21f, "#F8FAFC", bold = true))
        val badgeText = when (role) {
            "parent" -> "Current Mode: PARENT PHONE (家長手機) • Topic: ${GuardEngine.getChannelTopic(this).takeLast(6)}"
            "child" -> "Current Mode: CHILD PHONE 🔒 (子女手機) • Topic: ${GuardEngine.getChannelTopic(this).takeLast(6)}"
            else -> "Current Mode: NOT SET (請先選擇此手機屬於家長定子女)"
        }
        val badgeColor = when (role) {
            "parent" -> "#38BDF8"
            "child" -> "#4ADE80"
            else -> "#FBBF24"
        }
        rootContainer.addView(label(badgeText, 13f, badgeColor, bold = true, bottomPad = 24))

        when (role) {
            "unconfigured" -> renderRoleSelectorCard()
            "parent" -> {
                renderParentDashboard()
                renderDiagnosticsCard()
                renderRoleSwitchFooter(requirePin = false)
            }
            "child" -> {
                renderChildDashboard()
                renderDiagnosticsCard()
                renderRoleSwitchFooter(requirePin = true)
            }
        }
    }

    private fun renderRoleSelectorCard() {
        val card = makeCard()
        card.addView(label("Step 1: Set Family Pairing Code & PIN", 18f, "#FFFFFF", bold = true, bottomPad = 12))
        card.addView(
            label(
                "Both Parent and Child phones MUST enter the exact same Pairing Code (check the 6-char Topic ID in the header after saving to confirm they match).",
                13f,
                "#94A3B8",
                bottomPad = 20
            )
        )

        val pairInput = inputBox("Family Pairing Code (e.g. HK-8829)", GuardEngine.getPairCode(this))
        card.addView(pairInput)

        val pinInput = inputBox("4-Digit Parent PIN (Default: 1234)", GuardEngine.getPin(this), isPassword = false)
        card.addView(pinInput)

        card.addView(label("Step 2: Whose phone is this?", 16f, "#FFFFFF", bold = true, topPad = 20, bottomPad = 12))

        card.addView(
            actionButton("👨‍👩‍👧 Set as PARENT Phone (家長手機)", "#0284C7") {
                savePairAndPin(pairInput.text.toString(), pinInput.text.toString())
                GuardEngine.setRole(this, "parent")
                GuardEngine.addCheckpointLog(this, "SETUP", "Configured as PARENT phone (Code: ${GuardEngine.getPairCode(this)})")
                syncRoleServices()
                renderUi()
            }
        )

        card.addView(
            actionButton("🧒 Set as CHILD Phone (子女手機)", "#16A34A") {
                savePairAndPin(pairInput.text.toString(), pinInput.text.toString())
                GuardEngine.setRole(this, "child")
                GuardEngine.addCheckpointLog(this, "SETUP", "Configured as CHILD phone (Code: ${GuardEngine.getPairCode(this)})")
                syncRoleServices()
                renderUi()
            }
        )

        rootContainer.addView(card)
    }

    private fun savePairAndPin(pair: String, pin: String) {
        val cleanPair = pair.trim().ifEmpty { "HK-8829" }
        val cleanPin = pin.trim().ifEmpty { "1234" }
        GuardEngine.setPairCode(this, cleanPair)
        GuardEngine.setPin(this, cleanPin)
    }

    // ================= PARENT MODE UI =================
    private fun renderParentDashboard() {
        val statusCard = makeCard()
        statusCard.addView(label("📡 Live Connection to Child Phone", 17f, "#FFFFFF", bold = true, bottomPad = 8))
        statusCard.addView(
            label(
                "• Pairing Code: ${GuardEngine.getPairCode(this)} (Channel ID: ${GuardEngine.getChannelTopic(this).takeLast(6)})\n" +
                    "• Stream Status: ${GuardEngine.cpParentStreamState}\n" +
                    "• Last HTTP Check: ${GuardEngine.cpLastHttpStatus}",
                13f,
                "#CBD5E1",
                bottomPad = 16
            )
        )
        statusCard.addView(
            actionButton("🔄 Manual Catch-Up Sync (立即拉取最新警報)", "#334155") {
                Toast.makeText(this, "Pulling latest alerts...", Toast.LENGTH_SHORT).show()
                thread {
                    GuardEngine.pollChannelOnce(applicationContext, since = "6h")
                }
            }
        )
        rootContainer.addView(statusCard)

        val trustCard = makeCard()
        trustCard.addView(label("✅ Approved IG Friends (信任名單)", 17f, "#FFFFFF", bold = true, bottomPad = 8))
        val currentHandles = GuardEngine.getTrustedHandles(this)
        trustCard.addView(
            label(
                "Current list: " + if (currentHandles.isEmpty()) "(None)" else currentHandles.joinToString(", ") { "@$it" },
                14f,
                "#38BDF8",
                bold = true,
                bottomPad = 12
            )
        )

        val handlesInput = inputBox(
            "Comma-separated handles (e.g. mum_hk, dad_hk, bestie)",
            currentHandles.joinToString(", ")
        )
        trustCard.addView(handlesInput)

        trustCard.addView(
            actionButton("💾 Save & Sync Trusted List to Child Phone", "#0284C7") {
                val updated = handlesInput.text.toString().split(",")
                GuardEngine.setTrustedHandles(this, updated)
                GuardEngine.syncTrustedFromParent(this)
                Toast.makeText(this, "Saved & synced to Child Phone!", Toast.LENGTH_SHORT).show()
                renderUi()
            }
        )
        rootContainer.addView(trustCard)

        val inboxCard = makeCard()
        inboxCard.addView(label("🚨 Child Alert Inbox (即時警報記錄)", 17f, "#FFFFFF", bold = true, bottomPad = 12))

        val alerts = GuardEngine.getParentAlerts(this)
        if (alerts.isEmpty()) {
            inboxCard.addView(
                label(
                    "No alerts received yet. Verify both phones show the same 6-character Topic ID at the top of the screen, then tap 'Send Test Alert' on the Child Phone.",
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
                    "sexual_or_grooming_content" -> "🔴 SEXUAL / GROOMING CONTENT"
                    "tamper_attempt" -> "🟠 SETTINGS TAMPER ATTEMPT"
                    "stranger_message" -> "🟡 STRANGER DM"
                    else -> "🔔 TEST / SYSTEM ALERT"
                }
                itemBox.addView(label("$badge  •  $time", 12f, "#F8FAFC", bold = true, bottomPad = 4))
                itemBox.addView(label("Sender: $sender", 14f, "#38BDF8", bold = true, bottomPad = 4))
                itemBox.addView(label("Message: $text", 14f, "#E2E8F0", bottomPad = 8))

                val cleanHandle = sender.trim().lowercase().removePrefix("@")
                if (reason == "stranger_message" && cleanHandle !in currentHandles && !cleanHandle.contains(" ")) {
                    itemBox.addView(
                        actionButton("+ Approve @$cleanHandle (加入信任名單)", "#334155") {
                            val next = GuardEngine.getTrustedHandles(this).toMutableSet()
                            next.add(cleanHandle)
                            GuardEngine.setTrustedHandles(this, next)
                            GuardEngine.syncTrustedFromParent(this)
                            Toast.makeText(this, "Added @$cleanHandle & synced", Toast.LENGTH_SHORT).show()
                            renderUi()
                        }
                    )
                }
                inboxCard.addView(itemBox)
            }

            inboxCard.addView(
                actionButton("🗑️ Clear Alert History", "#475569") {
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
        val graceRemainingSec = maxOf(0L, (GuardEngine.pauseTamperUntilMs - System.currentTimeMillis()) / 1000L)

        val statusCard = makeCard()
        statusCard.addView(label("🛡️ Child Protection & Checkpoints (子女保護狀態)", 17f, "#FFFFFF", bold = true, bottomPad = 10))
        statusCard.addView(
            label(
                "• Pairing Code: ${GuardEngine.getPairCode(this)} (Topic ID: ${GuardEngine.getChannelTopic(this).takeLast(6)})\n" +
                    "• CP1 Notification Permission: ${if (notifOn) "ENABLED ✅" else "OFF ❌"} (Service Bound: ${if (GuardEngine.cpNotifConnected) "YES ✅" else "Waiting..."} | Last IG Notif: ${GuardEngine.cpLastNotifTime})\n" +
                    "• CP1 Anti-Tamper Accessibility: ${if (accOn) "ENABLED ✅" else "OFF ❌"} (Last Screen Scan: ${GuardEngine.cpLastAccTime})\n" +
                    "• CP3 Last Network Send: ${GuardEngine.cpLastHttpStatus}\n" +
                    "• Settings Lock State: ${if (graceRemainingSec > 0) "PAUSED (${graceRemainingSec}s left)" else "ARMED 🔒"}\n" +
                    "• Trusted Friends: ${GuardEngine.getTrustedHandles(this).joinToString(", ") { "@$it" }}",
                13f,
                "#E2E8F0",
                bottomPad = 16
            )
        )

        statusCard.addView(
            actionButton("0. Open IG Guard App Info (解除「限制設定」用)", "#475569") {
                GuardEngine.pauseTamperUntilMs = System.currentTimeMillis() + 180_000L
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            }
        )

        statusCard.addView(
            actionButton("1. Enable DM Notification Reader (讀取 IG DM 通知)", if (notifOn) "#334155" else "#0284C7") {
                GuardEngine.pauseTamperUntilMs = System.currentTimeMillis() + 180_000L
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        )

        statusCard.addView(
            actionButton("2. Enable Anti-Tamper Lock (強制開啟通知及防篡改)", if (accOn) "#334155" else "#16A34A") {
                GuardEngine.pauseTamperUntilMs = System.currentTimeMillis() + 180_000L
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        )

        statusCard.addView(
            actionButton("3. Send Test Alert to Parent Phone (發送測試警報)", "#4F46E5") {
                Toast.makeText(this, "Sending test alert...", Toast.LENGTH_SHORT).show()
                GuardEngine.sendAlertFromChild(
                    this,
                    "test_alert",
                    "@child_phone_test",
                    "Test Alert at ${GuardEngine.nowTimeStr()} — Connection verified!",
                    bypassDedupe = true
                ) { ok, status ->
                    uiHandler.post {
                        Toast.makeText(
                            this,
                            if (ok) "✅ Sent! ($status)" else "❌ Failed: $status",
                            Toast.LENGTH_LONG
                        ).show()
                        renderUi()
                    }
                }
            }
        )
        rootContainer.addView(statusCard)

        val pinCard = makeCard()
        pinCard.addView(label("🔐 Parent Controls on Child Phone (家長專用解鎖)", 16f, "#FFFFFF", bold = true, bottomPad = 8))
        val pinInput = inputBox("Enter Parent PIN to unlock Settings for 3 mins", "", isPassword = true)
        pinCard.addView(pinInput)

        pinCard.addView(
            actionButton("🔓 Pause Settings Lock for 3 Minutes", "#D97706") {
                if (pinInput.text.toString() == GuardEngine.getPin(this)) {
                    GuardEngine.pauseTamperUntilMs = System.currentTimeMillis() + 180_000L
                    pinInput.setText("")
                    GuardEngine.addCheckpointLog(this, "PIN", "Parent unlocked Settings for 3 mins")
                    Toast.makeText(this, "Settings unlocked for 3 minutes", Toast.LENGTH_LONG).show()
                    renderUi()
                } else {
                    Toast.makeText(this, "Incorrect Parent PIN", Toast.LENGTH_SHORT).show()
                }
            }
        )
        rootContainer.addView(pinCard)
    }

    // ================= CHECKPOINT DIAGNOSTIC LOG CARD =================
    private fun renderDiagnosticsCard() {
        val diagCard = makeCard()
        diagCard.addView(label("🔍 Live Checkpoint Log (故障排查紀錄)", 16f, "#38BDF8", bold = true, bottomPad = 6))
        diagCard.addView(
            label(
                "Shows every step: [CP1] Service Hook -> [CP2] DM Parse/Filter -> [CP3] HTTP Send -> [CP4] Parent Receive",
                12f,
                "#94A3B8",
                bottomPad = 10
            )
        )
        diagCard.addView(
            label(
                GuardEngine.getCheckpointLogs(this),
                12f,
                "#E2E8F0",
                bottomPad = 14
            )
        )
        diagCard.addView(
            actionButton("🧹 Clear Checkpoint Log", "#334155") {
                GuardEngine.clearCheckpointLogs(this)
            }
        )
        rootContainer.addView(diagCard)
    }

    private fun renderRoleSwitchFooter(requirePin: Boolean) {
        val card = makeCard()
        card.addView(label("⚙️ Change Device Role / Pairing Code", 15f, "#94A3B8", bold = true, bottomPad = 8))
        if (requirePin) {
            val pinInput = inputBox("Enter Parent PIN to switch role or edit pairing", "", isPassword = true)
            card.addView(pinInput)
            card.addView(
                actionButton("Switch Role / Reconfigure (需要家長密碼)", "#475569") {
                    if (pinInput.text.toString() == GuardEngine.getPin(this)) {
                        GuardEngine.setRole(this, "unconfigured")
                        syncRoleServices()
                        renderUi()
                    } else {
                        Toast.makeText(this, "Incorrect Parent PIN!", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        } else {
            card.addView(
                actionButton("Switch Role / Change Pairing Code (切換角色或配對碼)", "#475569") {
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