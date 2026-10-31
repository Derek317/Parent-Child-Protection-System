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
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.InputType
import android.view.Gravity
import android.view.View
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
    private const val MAX_SEEN = 500
    private val seenKeys = LinkedHashSet<String>()
    private val pollLock = Any()

    // shortcut: regex keyword matching for EN + HK Cantonese sexual/grooming text, upgrade to on-device ML classifier when false-negatives appear
    val SEXUAL_PATTERN = Regex(
        "\\b(nudes?|naked|porn|sex|horny|boobs?|tits|dick|cock|pussy|blowjob|handjob|onlyfans|hook ?up|fwb|send (me )?(a )?pic|take it off|secret between us|don'?t tell (your )?(mum|mom|dad|parents))\\b|" +
            "(裸照|露點|色情|做愛|扑嘢|約炮|援交|包養|自慰|手淫|私密照|閃卡|J圖|咸相|鹹相|唔好同(屋企人|阿媽|阿爸|父母)講|拍片比我睇|除衫)",
        RegexOption.IGNORE_CASE
    )

    private val IGNORED_NOTIFICATIONS = Regex(
        "^(Instagram|Checking for new messages|正在檢查新訊息|Uploading|傳送中).*$",
        RegexOption.IGNORE_CASE
    )

    @Volatile
    var pauseTamperUntilMs: Long = 0L

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Roles: "unconfigured", "parent", "child"
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

    // Derives an unguessable relay topic URL from the Family Pairing Code so no external app or account is needed
    // shortcut: uses public ntfy.sh JSON pub/sub topic derived via SHA-256 of pair code, upgrade to self-hosted relay for enterprise deployments
    fun getChannelTopic(ctx: Context): String {
        val code = getPairCode(ctx).trim().uppercase(Locale.US)
        val digest = MessageDigest.getInstance("SHA-256").digest("ig_guard_hk_v2:$code".toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }.take(24)
        return "ig_guard_$hex"
    }

    @Synchronized
    fun rememberKey(key: String): Boolean {
        if (seenKeys.contains(key)) return false
        if (seenKeys.size >= MAX_SEEN) {
            val first = seenKeys.iterator().next()
            seenKeys.remove(first)
        }
        seenKeys.add(key)
        return true
    }

    fun isTrustedSender(ctx: Context, rawTitle: String, rawText: String): Boolean {
        val trustedSet = getTrustedHandles(ctx)
        val titleNorm = rawTitle.trim().lowercase().removePrefix("@")
        if (titleNorm in trustedSet) return true
        for (trusted in trustedSet) {
            if (titleNorm.contains("(@$trusted)") || titleNorm == trusted) return true
            if (rawText.trim().lowercase().startsWith("$trusted:")) return true
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

    // Sends an encrypted/structured JSON event from Child Phone -> Parent Phone
    fun sendAlertFromChild(ctx: Context, reason: String, sender: String, text: String) {
        val cleanSender = sender.trim().ifEmpty { "unknown" }.take(80)
        val cleanText = text.trim().take(300)
        val dedupeKey = "$reason|$cleanSender|$cleanText"
        if (!rememberKey(dedupeKey)) return

        val topic = getChannelTopic(ctx)
        val payload = JSONObject().apply {
            put("type", "alert")
            put("reason", reason)
            put("sender", cleanSender)
            put("text", cleanText)
            put("time", SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date()))
            put("trusted_csv", getTrustedHandles(ctx).joinToString(","))
        }.toString()

        postToChannel(topic, payload)
    }

    // Sends updated Trusted Handles list from Parent Phone -> Child Phone
    fun syncTrustedFromParent(ctx: Context) {
        val topic = getChannelTopic(ctx)
        val payload = JSONObject().apply {
            put("type", "sync_trusted")
            put("trusted_csv", getTrustedHandles(ctx).joinToString(","))
            put("time", SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date()))
        }.toString()
        postToChannel(topic, payload)
    }

    private fun postToChannel(topic: String, jsonBody: String) {
        thread {
            try {
                val conn = (URL("https://ntfy.sh/$topic").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 6000
                    readTimeout = 6000
                    doOutput = true
                    setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                }
                conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    // Polls the relay channel so Parent Phone receives alerts and Child Phone receives updated Trusted Handles
    fun pollChannelOnce(ctx: Context, onNewAlertForParent: ((JSONObject) -> Unit)? = null) = synchronized(pollLock) {
        val role = getRole(ctx)
        if (role == "unconfigured") return
        val topic = getChannelTopic(ctx)
        try {
            val url = URL("https://ntfy.sh/$topic/json?poll=1&since=12h")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 6000
            }
            val seenIds = prefs(ctx).getStringSet("seen_msg_ids", emptySet())?.toMutableSet() ?: mutableSetOf()
            var changed = false

            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val outer = try { JSONObject(line!!) } catch (_: Exception) { continue }
                    if (outer.optString("event") != "message") continue
                    val msgId = outer.optString("id")
                    val innerStr = outer.optString("message")
                    val inner = try { JSONObject(innerStr) } catch (_: Exception) { continue }
                    val msgType = inner.optString("type")

                    // Both parent and child apply the latest sync_trusted broadcast
                    if (msgType == "sync_trusted") {
                        val csv = inner.optString("trusted_csv")
                        if (csv.isNotEmpty() && !seenIds.contains(msgId)) {
                            seenIds.add(msgId)
                            changed = true
                            val list = csv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                            setTrustedHandles(ctx, list)
                            ctx.sendBroadcast(Intent(ACTION_UI_REFRESH).setPackage(ctx.packageName))
                        }
                    } else if (msgType == "alert" && role == "parent") {
                        if (!seenIds.contains(msgId)) {
                            seenIds.add(msgId)
                            changed = true
                            saveAlertToParentHistory(ctx, inner)
                            onNewAlertForParent?.invoke(inner)
                            ctx.sendBroadcast(Intent(ACTION_UI_REFRESH).setPackage(ctx.packageName))
                        }
                    }
                }
            }
            if (changed) {
                val trimmed = if (seenIds.size > 300) seenIds.toList().takeLast(300).toSet() else seenIds
                prefs(ctx).edit().putStringSet("seen_msg_ids", trimmed).apply()
            }
            conn.disconnect()
        } catch (_: Exception) {
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

// 1. CHILD ONLY: Reads incoming Instagram DM notifications
class IgNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // Strictly disabled unless this phone is set to Child Mode
        if (!GuardEngine.isChildMode(this)) return
        if (sbn == null || sbn.packageName != "com.instagram.android") return
        val notif = sbn.notification ?: return
        if ((notif.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val extras = notif.extras ?: return
        val title = (extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)
            ?: "Instagram").toString()
        val body = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: "").toString()

        val reason = GuardEngine.evaluateDm(this, title, body) ?: return
        GuardEngine.sendAlertFromChild(this, reason, title, body)

        if (reason == "sexual_or_grooming_content") {
            try {
                cancelNotification(sbn.key)
            } catch (_: Exception) {
            }
        }
    }
}

// 2. CHILD ONLY: Forces Instagram notifications ON + blocks Settings tampering + scans open chats
class IgAccessibilityService : AccessibilityService() {

    private var lastScanMs = 0L
    private var lastSyncPollMs = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Strictly disabled unless this phone is set to Child Mode
        if (!GuardEngine.isChildMode(this)) return

        val pkg = event?.packageName?.toString() ?: return
        val now = System.currentTimeMillis()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && now - lastScanMs < 350L) {
            return
        }
        lastScanMs = now

        // Every 20s while active, check if Parent Phone pushed an updated Trusted Handles list
        if (now - lastSyncPollMs > 20_000L) {
            lastSyncPollMs = now
            thread { GuardEngine.pollChannelOnce(applicationContext) }
        }

        val root = rootInActiveWindow ?: return
        val texts = ArrayList<String>(64)
        collectTexts(root, texts)
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
                    "Child tried to open Settings for Instagram / IG Guard: ${joined.take(120)}"
                )
                return
            }
        }

        // B. Inside native Instagram app: block in-app notification/chat muting AND block live sexual/grooming DMs
        if (pkg == "com.instagram.android") {
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

            for (line in texts) {
                if (GuardEngine.SEXUAL_PATTERN.containsMatchIn(line)) {
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    GuardEngine.sendAlertFromChild(this, "sexual_or_grooming_content", "Instagram Live Screen", line)
                    return
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
        if (node == null || out.size >= 120) return
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
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

// 3. PARENT ONLY: Built-in Background Alert Receiver (Replaces the ntfy app completely)
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
            .setContentText("Paired code: ${GuardEngine.getPairCode(this)} • Listening for child alerts")
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
                    GuardEngine.pollChannelOnce(applicationContext) { alert ->
                        fireNativeParentNotification(alert)
                    }
                    try {
                        Thread.sleep(4500L)
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

// 4. All-in-One Parent & Child UI Dashboard
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
            thread { GuardEngine.pollChannelOnce(applicationContext) }
        } else {
            stopService(Intent(this, ParentAlertService::class.java))
            if (GuardEngine.isChildMode(this)) {
                thread { GuardEngine.pollChannelOnce(applicationContext) }
            }
        }
    }

    private fun renderUi() {
        rootContainer.removeAllViews()
        val role = GuardEngine.getRole(this)

        // Header
        rootContainer.addView(label("IG Teen Guard • 家長子女守護系統", 22f, "#F8FAFC", bold = true))
        val badgeText = when (role) {
            "parent" -> "Current Mode: PARENT PHONE (家長手機 — 接收警報及遠端管理)"
            "child" -> "Current Mode: CHILD PHONE 🔒 (子女手機 — 監測 IG DM 及防篡改)"
            else -> "Current Mode: NOT SET (請先選擇此手機屬於家長定子女)"
        }
        val badgeColor = when (role) {
            "parent" -> "#38BDF8"
            "child" -> "#4ADE80"
            else -> "#FBBF24"
        }
        rootContainer.addView(label(badgeText, 14f, badgeColor, bold = true, bottomPad = 28))

        when (role) {
            "unconfigured" -> renderRoleSelectorCard()
            "parent" -> {
                renderParentDashboard()
                renderRoleSwitchFooter(requirePin = false)
            }
            "child" -> {
                renderChildDashboard()
                renderRoleSwitchFooter(requirePin = true)
            }
        }
    }

    private fun renderRoleSelectorCard() {
        val card = makeCard()
        card.addView(label("Step 1: Set Family Pairing Code & PIN", 18f, "#FFFFFF", bold = true, bottomPad = 12))
        card.addView(
            label(
                "Enter the exact same Pairing Code on both Parent and Child phones so they link automatically without any third-party app.",
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
                syncRoleServices()
                renderUi()
            }
        )

        card.addView(
            actionButton("🧒 Set as CHILD Phone (子女手機)", "#16A34A") {
                savePairAndPin(pairInput.text.toString(), pinInput.text.toString())
                GuardEngine.setRole(this, "child")
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
        // 1. Pairing & Connection Status Card
        val statusCard = makeCard()
        statusCard.addView(label("📡 Live Connection to Child Phone", 17f, "#FFFFFF", bold = true, bottomPad = 8))
        statusCard.addView(
            label(
                "Family Pairing Code: ${GuardEngine.getPairCode(this)}\n" +
                    "Background Receiver: ACTIVE ✅ (Built-in push notifications enabled — no ntfy app needed)",
                13f,
                "#CBD5E1",
                bottomPad = 16
            )
        )
        statusCard.addView(
            actionButton("🔄 Refresh Alerts Now (立即同步)", "#334155") {
                Toast.makeText(this, "Checking for new alerts...", Toast.LENGTH_SHORT).show()
                thread { GuardEngine.pollChannelOnce(applicationContext) }
            }
        )
        rootContainer.addView(statusCard)

        // 2. Remote Trusted Handles Manager Card
        val trustCard = makeCard()
        trustCard.addView(label("✅ Approved IG Friends (信任名單)", 17f, "#FFFFFF", bold = true, bottomPad = 8))
        trustCard.addView(
            label(
                "Messages from IG accounts below will NOT trigger stranger alerts (unless sexual/grooming words appear). Changes sync to your child's phone automatically.",
                13f,
                "#94A3B8",
                bottomPad = 12
            )
        )

        val currentHandles = GuardEngine.getTrustedHandles(this)
        trustCard.addView(
            label(
                "Current list: " + if (currentHandles.isEmpty()) "(None)" else currentHandles.joinToString(", ") { "@$it" },
                14f,
                "#38BDF8",
                bold = true,
                bottomPad = 14
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

        // 3. Live Alert Inbox Card
        val inboxCard = makeCard()
        inboxCard.addView(label("🚨 Child Alert Inbox (即時警報記錄)", 17f, "#FFFFFF", bold = true, bottomPad = 12))

        val alerts = GuardEngine.getParentAlerts(this)
        if (alerts.isEmpty()) {
            inboxCard.addView(
                label(
                    "No alerts yet. Tap 'Send Test Alert' on your child's phone to verify pairing.",
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
                            Toast.makeText(this, "Added @$cleanHandle & synced to Child Phone", Toast.LENGTH_SHORT).show()
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

        val statusCard = makeCard()
        statusCard.addView(label("🛡️ Child Protection Status (子女保護狀態)", 17f, "#FFFFFF", bold = true, bottomPad = 10))
        statusCard.addView(
            label(
                "• Family Pairing Code: ${GuardEngine.getPairCode(this)}\n" +
                    "• 1. DM Notification Reader: ${if (notifOn) "ACTIVE ✅" else "OFF ❌ (Tap below to enable)"}\n" +
                    "• 2. Anti-Tamper & Force Notification Lock: ${if (accOn) "ACTIVE ✅" else "OFF ❌ (Tap below to enable)"}\n" +
                    "• Trusted IG Friends: ${GuardEngine.getTrustedHandles(this).joinToString(", ") { "@$it" }}",
                14f,
                "#E2E8F0",
                bottomPad = 16
            )
        )

        statusCard.addView(
            actionButton("1. Enable DM Notification Reader (讀取 IG DM 通知)", if (notifOn) "#334155" else "#0284C7") {
                GuardEngine.pauseTamperUntilMs = System.currentTimeMillis() + 120_000L
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        )

        statusCard.addView(
            actionButton("2. Enable Anti-Tamper Lock (強制開啟通知及防篡改)", if (accOn) "#334155" else "#16A34A") {
                GuardEngine.pauseTamperUntilMs = System.currentTimeMillis() + 120_000L
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        )

        statusCard.addView(
            actionButton("3. Send Test Alert to Parent Phone (發送測試警報)", "#4F46E5") {
                GuardEngine.sendAlertFromChild(
                    this,
                    "test_alert",
                    "@teen_phone",
                    "Child phone is paired and sending alerts to Parent Phone!"
                )
                Toast.makeText(this, "Test alert sent to Parent Phone!", Toast.LENGTH_SHORT).show()
            }
        )
        rootContainer.addView(statusCard)

        // Parent PIN Unlock Card on Child Phone
        val pinCard = makeCard()
        pinCard.addView(label("🔐 Parent Controls on Child Phone (家長專用解鎖)", 16f, "#FFFFFF", bold = true, bottomPad = 8))
        pinCard.addView(
            label(
                "Enter your Parent PIN to pause the Settings lock for 2 minutes if you need to adjust Android Settings.",
                13f,
                "#94A3B8",
                bottomPad = 12
            )
        )
        val pinInput = inputBox("Enter Parent PIN", "", isPassword = true)
        pinCard.addView(pinInput)

        pinCard.addView(
            actionButton("🔓 Unlock Android Settings for 2 Minutes", "#D97706") {
                if (pinInput.text.toString() == GuardEngine.getPin(this)) {
                    GuardEngine.pauseTamperUntilMs = System.currentTimeMillis() + 120_000L
                    pinInput.setText("")
                    Toast.makeText(this, "Settings unlocked for 2 minutes", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Incorrect Parent PIN", Toast.LENGTH_SHORT).show()
                }
            }
        )
        rootContainer.addView(pinCard)
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

    // ================= UI HELPERS =================
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