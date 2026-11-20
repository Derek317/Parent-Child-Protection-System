package app.family.igguard

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.text.util.Linkify
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant

/** Installation-level acceptance; capture-scope and Android permission grants remain separate. */
object AppConsent {
    const val VERSION = "2026-11-20-v4"
    const val TERMS_ASSET = "ig-guard-terms-of-service.md"
    internal val acknowledgments = setOf("consent_terms", "consent_transfer", "consent_family")
    private const val PURPOSE = "consent_receipt"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("ig_guard_consent", Context.MODE_PRIVATE)
    data class Receipt(val version: String, val sha256: String, val acceptedAt: Long, val appVersion: String)

    fun terms(ctx: Context): String = ctx.assets.open(TERMS_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
    private fun documentHash(ctx: Context): String = ctx.assets.open(TERMS_ASSET).use {
        MessageDigest.getInstance("SHA-256").digest(it.readBytes()).joinToString("") { b -> "%02x".format(b.toInt() and 255) }
    }
    @Synchronized fun receipt(ctx: Context): Receipt? {
        try {
            val sealed = prefs(ctx).getString("receipt", null) ?: return null
            val json = JSONObject(LocalVault.open(ctx, PURPOSE, sealed, remember = true))
            require(json.getBoolean("accepted"))
            require(json.getString("version").isNotBlank())
            require(json.getString("sha256").matches(Regex("[a-f0-9]{64}")))
            require(json.getLong("accepted_at") > 0)
            require(json.getString("app_version").isNotBlank())
            require(json.getJSONArray("acknowledgments").let { array ->
                array.length() == acknowledgments.size && (0 until array.length()).map { array.getString(it) }.toSet() == acknowledgments
            })
            return Receipt(json.getString("version"), json.getString("sha256"), json.getLong("accepted_at"), json.getString("app_version"))
        } catch (e: Exception) { throw SecurityException("Consent record is unreadable; existing data was retained", e) }
    }
    // Background entry points fail closed if the device-bound receipt cannot be read.
    /** True once per bundled revision/hash when the stored receipt records a different one. Never forces re-consent. */
    fun termsUpdateNoticePending(ctx: Context): Boolean = try {
        val saved = receipt(ctx)
        val current = VERSION + ":" + documentHash(ctx)
        saved != null && (saved.version != VERSION || saved.sha256 != documentHash(ctx)) && prefs(ctx).getString("terms_notice_seen", null) != current
    } catch (_: Exception) { false }
    fun markTermsUpdateNoticeSeen(ctx: Context) { prefs(ctx).edit().putString("terms_notice_seen", VERSION + ":" + documentHash(ctx)).apply() }
    fun hasAccepted(ctx: Context): Boolean = try { receipt(ctx) != null } catch (_: SecurityException) { false }

    @Synchronized internal fun accept(ctx: Context, checked: Set<String>) {
        require(checked == acknowledgments)
        if (receipt(ctx) != null) return // Preserve the original revision and time across ordinary updates.
        LocalVault.preferences(ctx)
        val record = JSONObject().put("accepted", true).put("version", VERSION)
            .put("sha256", documentHash(ctx)).put("accepted_at", System.currentTimeMillis()).put("app_version", BuildConfig.VERSION_NAME)
            .put("acknowledgments", org.json.JSONArray(acknowledgments.toList()))
        if (!prefs(ctx).edit().putString("receipt", LocalVault.seal(ctx, PURPOSE, record.toString())).commit()) {
            // commit() changes SharedPreferences memory even if its disk write fails. Receipt readers
            // share this lock and cannot observe acceptance before persistence or during rollback.
            prefs(ctx).edit().remove("receipt").commit()
            throw SecurityException("Consent record could not be saved")
        }
    }

    fun reviewText(ctx: Context): String {
        val saved = receipt(ctx)
        val record = if (saved == null) "尚未同意 / Not accepted" else
            "已同意版本 / Accepted revision: ${saved.version}\nApp: ${saved.appVersion}\nUTC: ${Instant.ofEpochMilli(saved.acceptedAt)}\nSHA-256: ${saved.sha256}"
        return "$record\n\n以下為此安裝版本附帶的條款；上方記錄保留最初同意的版本。\nCurrent bundled Terms appear below; the receipt identifies the originally accepted revision.\n\n${terms(ctx)}"
    }

    fun page(activity: Activity, onAccepted: () -> Unit): View {
        fun dp(n: Int) = (n * activity.resources.displayMetrics.density).toInt()
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(32), dp(24), dp(32))
            setBackgroundColor(Color.rgb(247, 247, 242))
            isSaveFromParentEnabled = false
        }
        fun text(value: String, size: Float = 16f, bold: Boolean = false) = TextView(activity).apply {
            this.text = value; textSize = size; setTextColor(Color.rgb(30, 43, 41))
            if (bold) setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, dp(18))
            setTextIsSelectable(true)
        }
        content.addView(text("先了解，再決定\nYour messages, your decision", 26f, true))
        content.addView(text("請與家長一起閱讀完整條款。你可以拒絕並返回主畫面。\nRead the full Terms with your parent. You may decline and return to Home."))
        content.addView(text("子女模式會將實際擷取的 Instagram 訊息及相關資料傳至指定、已核對配對的家長／法定監護人。一般模式處理傳入警報；傳出及信任對象的一般對話仍須另行確認雙向擷取。未送出草稿不會讀取。", 17f, true))
        content.addView(text("本機內容及偏好字串以 Android Keystore 金鑰及 AES-GCM 保護；家長控制簽署金鑰存入 Keystore 後不匯出；若移入失敗，會暫用原有加密保存的金鑰並於下次啟動重試。家長畫面須經手機鎖驗證，背景接收仍繼續；部分時間、分類及狀態資料仍可見。內容先以家庭金鑰加密，再經 TLS 及 Cloudflare 中繼傳送。開發者與 Cloudflare 可處理密文及連線中繼資料，中繼不持有家庭解密金鑰。加密不能保護已解鎖、遭入侵的手機或外部副本。"))
        content.addView(text("手機上新待傳記錄最多保留 72 小時；中繼站上未被取走的密文最多保留 7 天；中繼登記目前不會自動到期，亦不能刪除。新封存預設 30 天，可選 7／30 天。個別記錄可明確保留一次 90 天。舊封存須預覽及確認採用期限。到期內容在下次存取或排程清理時移除；供應商日誌／備份另有保留規則。只供私人家庭保障用途，禁止監視陌生人、伴侶、員工或學生。第 486 章第 52 條不是本服務、開發者或 Cloudflare 的全面豁免。"))
        content.addView(text("拒絕時不擷取或轉傳；既有佇列暫停，舊資料不會自動刪除。日後可在「擷取範圍與停止」同時停止新擷取與待傳送，選擇暫存或捨棄待傳內容；重新開始會捨棄舊待傳內容，雙向範圍須重新確認。已在傳送途中或送達的副本可能仍存在，需另行刪除。Android 兩項存取權限亦可另行關閉。下列條款說明刪除方法及使用限制。"))
        content.addView(text("完整使用條款 / Full Terms of Service", 21f, true))
        content.addView(text(terms(activity)).apply {
            tag = "consent_document"; Linkify.addLinks(this, Linkify.WEB_URLS)
            setLinkTextColor(Color.rgb(29, 95, 85))
        })
        val green = Color.rgb(36, 94, 80)
        val accept = ObscuredAwareButton(activity) { Toast.makeText(activity, OBSCURED_TOUCH_MESSAGE, Toast.LENGTH_LONG).show() }.apply {
            text = "同意並繼續 / Agree and continue"; tag = "consent_accept"; isEnabled = false; isAllCaps = false
            backgroundTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()), intArrayOf(green, Color.rgb(214, 220, 217)))
            setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()), intArrayOf(Color.WHITE, Color.rgb(60, 73, 67))))
        }
        val boxes = linkedMapOf<String, CheckBox>()
        for ((tag, caption) in listOf(
            "consent_terms" to "我已閱讀並同意上述完整條款。\nI have read and agree to the full Terms above.",
            "consent_transfer" to "我了解並同意：在子女模式中，按已確認範圍擷取的訊息會加密轉傳至指定、已核對配對的家長／法定監護人；本機、中繼保護與保留期限如上。\nI understand and agree to the disclosed message transfer to my verified paired parent/legal guardian when this phone is in child mode, with the protections and retention described above.",
            "consent_family" to "我只會在有合法權限的私人家庭保障情況下使用，並已與子女／家長了解用途；不作其他監視用途，也不把第 52 條當作自動合法授權。\nI will use this only for authorised private family safeguarding, with the child/parent informed, and will not treat section 52 as automatic legal permission."
        )) {
            boxes[tag] = CheckBox(activity).apply {
                this.tag = tag; text = caption; textSize = 16f; setTextColor(Color.rgb(30, 43, 41))
                isChecked = false; isSaveEnabled = false; setPadding(0, dp(10), 0, dp(10))
                buttonTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(green, Color.rgb(68, 84, 77)))
                setOnCheckedChangeListener { _, _ -> accept.isEnabled = boxes.size == acknowledgments.size && boxes.values.all { it.isChecked } }
                content.addView(this)
            }
        }
        val error = text("", 15f).apply { setTextColor(Color.rgb(163, 33, 33)); visibility = View.GONE }
        content.addView(error)
        accept.setOnClickListener {
            if (boxes.values.any { !it.isChecked }) return@setOnClickListener
            try { AppConsent.accept(activity, boxes.filterValues { it.isChecked }.keys); onAccepted() }
            catch (_: Exception) { error.text = "無法儲存同意記錄；尚未啟用。請解鎖手機並重試。\nCould not save consent. Collection remains off; unlock the phone and retry."; error.visibility = View.VISIBLE }
        }
        content.addView(accept)
        content.addView(Button(activity).apply {
            text = "不同意，返回主畫面 / Decline and return Home"; tag = "consent_decline"
            isAllCaps = false; backgroundTintList = ColorStateList.valueOf(Color.rgb(66, 76, 71)); setTextColor(Color.WHITE)
            setOnClickListener { leaveToHome(activity) }
        })
        return ScrollView(activity).apply { isFillViewport = true; tag = "first_launch_consent"; addView(content) }
    }

    fun leaveToHome(activity: Activity) {
        try { activity.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: ActivityNotFoundException) { activity.moveTaskToBack(true) }
        activity.finishAndRemoveTask()
    }
}
