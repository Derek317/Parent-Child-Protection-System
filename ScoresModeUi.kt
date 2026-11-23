package app.family.igguard

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Wording and pure logic for the scores-only family mode screens. No views live here: MainActivity builds them from
 * these models so the private theme helpers stay private. Nothing in this file reads or shows message text.
 */

/** What the parent's score page shows. The stale banner is not a page: it is a flag that can sit on any page with a child state. */
internal enum class ScoresPage { OFF, WAITING, RECONSENT, MODEL_MISSING, MODEL_WRONG, MODEL_UNSUPPORTED, ENGINE_UNAVAILABLE, PAUSED, WITHDRAWN, NOT_RUNNING, EMPTY, LIST, ERROR }

internal data class ScoresPageModel(
    val page: ScoresPage, val rows: List<RiskScoreRow>, val needAttention: Int, val needReview: Int,
    val state: ScoresModeState?, val noReportYet: Boolean = false,
    val stale: Boolean = false,
    /** Set on PAUSED when the model is also not valid: both facts are shown. */
    val alsoModelProblem: String? = null,
    /** The child has not answered the current policy for more than a moment: offer to send it again. */
    val offerResend: Boolean = false
)

/**
 * Calls into the engine that the screens depend on. Production values call the engine; tests replace them to force a
 * refusal. Where the engine API is still being added the fallback is marked.
 */
internal object ScoresEngineBridge {
    var sendPolicy: (Context, Boolean, String) -> Boolean = { c, enabled, method -> GuardEngine.sendScoresModePolicy(c, enabled, method) }
    var resendPolicy: (Context) -> Boolean = { c -> GuardEngine.resendScoresModePolicy(c) }
    var accept: (Context, String, String) -> Boolean = { c, id, method -> RiskLocal.accept(c, id, method) }
    var pause: (Context) -> Boolean = { RiskLocal.pause(it) }
    var resume: (Context) -> Boolean = { RiskLocal.resume(it) }
    var withdraw: (Context) -> Boolean = { RiskLocal.withdraw(it) }
    var pendingNotice: (Context) -> String? = { RiskLocal.pendingChangeNotice(it) }
    var acknowledgeNotice: (Context, String) -> Unit = { c, _ -> RiskLocal.acknowledgeChangeNotice(c) }
    /** Whether the child's phone says analysis is actually running; null when the report does not say. */
    var activeOf: (ScoresModeState) -> Boolean? = { it.active }
    fun reset() {
        sendPolicy = { c, enabled, method -> GuardEngine.sendScoresModePolicy(c, enabled, method) }
        resendPolicy = { c -> GuardEngine.resendScoresModePolicy(c) }
        accept = { c, id, method -> RiskLocal.accept(c, id, method) }; pause = { RiskLocal.pause(it) }; resume = { RiskLocal.resume(it) }; withdraw = { RiskLocal.withdraw(it) }
        pendingNotice = { RiskLocal.pendingChangeNotice(it) }; acknowledgeNotice = { c, _ -> RiskLocal.acknowledgeChangeNotice(c) }
        activeOf = { it.active }
    }
}

internal object ScoresUi {
    const val NEEDS_ATTENTION_FROM = 50
    const val OPEN_SCORES_ACTION = "app.family.igguard.OPEN_SCORES"

    /** Whole number shown to the parent. Rounded down so a score of 49.6 never reads as 50 while still in the lower band. */
    fun whole(score: Double): Int = Math.floor(score).toInt().coerceIn(0, 100)

    /** Highest score first; "needs review" rows after scored rows; newest first within equal scores. */
    fun sort(rows: List<RiskScoreRow>): List<RiskScoreRow> = rows.sortedWith(
        compareBy<RiskScoreRow> { if (it.score == null) 1 else 0 }
            .thenByDescending { it.score?.let(::whole) ?: 0 }
            .thenByDescending { it.scoredAt }
            .thenBy { it.accountLabel.lowercase(Locale.ROOT) }
    )

    fun band(row: RiskScoreRow): RiskBand? = row.score?.let { RiskBand.forScore(it) }

    const val RESEND_AFTER_MS = 60_000L

    /**
     * Order of precedence once the parent has the mode on: no answer for the current policy, no consent, withdrawn,
     * paused (a pause is never hidden by a model problem), a model problem, agreed-but-not-running, then scores.
     * [active] is the child's own report of whether analysis is running; null means the report does not say.
     */
    fun classify(policy: ScoresModePolicy?, state: ScoresModeState?, rows: List<RiskScoreRow>, now: Long = System.currentTimeMillis(), active: Boolean? = null): ScoresPageModel {
        val sorted = sort(rows)
        val attention = sorted.count { it.score != null && whole(it.score) >= NEEDS_ATTENTION_FROM }
        val review = sorted.count { it.score == null }
        fun model(page: ScoresPage, noReport: Boolean = false, also: String? = null, resend: Boolean = false) =
            ScoresPageModel(page, sorted, attention, review, state, noReport, state != null && state.stale && page != ScoresPage.OFF, also, resend)
        if (policy == null || !policy.enabled) return model(ScoresPage.OFF)
        // A report about an older policy says nothing about the current one: the child has not answered yet.
        if (state == null || state.policyId != policy.policyId) return model(ScoresPage.WAITING, noReport = true, resend = now - policy.sentAt > RESEND_AFTER_MS)
        return when {
            state.consent == "none" -> model(ScoresPage.WAITING)
            state.consent == "reconsent" -> model(ScoresPage.RECONSENT)
            state.consent == "withdrawn" -> model(ScoresPage.WITHDRAWN)
            state.paused -> model(ScoresPage.PAUSED, also = state.model.takeIf { it != "valid" })
            state.model == "unsupported_device" -> model(ScoresPage.MODEL_UNSUPPORTED)
            state.model == "engine_unavailable" -> model(ScoresPage.ENGINE_UNAVAILABLE)
            state.model == "mismatch" -> model(ScoresPage.MODEL_WRONG)
            state.model != "valid" -> model(ScoresPage.MODEL_MISSING)
            active == false -> model(ScoresPage.NOT_RUNNING)
            sorted.isEmpty() -> model(ScoresPage.EMPTY)
            else -> model(ScoresPage.LIST)
        }
    }

    fun modelWords(wire: String): String = when (wire) {
        "missing" -> "尚未安裝模型檔"; "mismatch" -> "模型檔不正確"; "unsupported_device" -> "手機不支援這個功能"
        "engine_unavailable" -> "分析功能未能啟動"; else -> "模型有問題"
    }
    fun hoursSince(ms: Long, now: Long = System.currentTimeMillis()): Long = ((now - ms) / 3_600_000L).coerceAtLeast(0)
    /** Heading above the list of scores. Null when there is nothing to list. */
    fun listHeading(m: ScoresPageModel): String? = if (m.rows.isEmpty()) null else when (m.page) {
        ScoresPage.LIST -> "對話（分數由高至低）"
        ScoresPage.PAUSED -> "以下是暫停前收到的分數"
        ScoresPage.WITHDRAWN -> "以下分數已不再更新（子女已撤回同意）"
        else -> "以下分數已不再更新"
    }
    /** One line for the overview row: the state, not a count, whenever scores are not currently being produced. */
    fun overviewSummary(m: ScoresPageModel): String {
        val base = when (m.page) {
            ScoresPage.OFF -> "僅分數模式未開啟"
            ScoresPage.WAITING -> "等待子女同意"
            ScoresPage.RECONSENT -> "子女需要再次同意"
            ScoresPage.PAUSED -> "子女已暫停分析"
            ScoresPage.WITHDRAWN -> "子女已撤回同意"
            ScoresPage.NOT_RUNNING -> "子女已同意，但分析沒有在運行"
            ScoresPage.MODEL_MISSING, ScoresPage.MODEL_WRONG, ScoresPage.MODEL_UNSUPPORTED, ScoresPage.ENGINE_UNAVAILABLE -> copy(m).title
            ScoresPage.EMPTY -> "分析進行中，暫時還沒有分數"
            ScoresPage.ERROR -> "未能讀取分數"
            ScoresPage.LIST -> "${m.needAttention} 個對話需要留意 · ${m.needReview} 個需要檢視"
        }
        if (!m.stale) return base
        val old = "分數可能已過時 · 最後報告 ${m.state?.receivedAt?.let(::time) ?: ""}"
        return if (m.page == ScoresPage.LIST || m.page == ScoresPage.EMPTY) old else "$base · 分數可能已過時"
    }

    // ---------------- dates ----------------
    fun day(ms: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(ms))
    fun spokenDay(ms: Long): String = SimpleDateFormat("yyyy年M月d日", Locale.TRADITIONAL_CHINESE).format(Date(ms))
    fun time(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date(ms))
    fun spokenTime(ms: Long): String = SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.TRADITIONAL_CHINESE).format(Date(ms))

    // ---------------- band presentation (icon and text always accompany colour) ----------------
    const val REVIEW_TITLE = "需要檢視 / Needs review"
    fun bandTitle(band: RiskBand?): String = band?.title ?: REVIEW_TITLE
    fun bandIcon(band: RiskBand?): String = when (band) {
        RiskBand.LOW -> "check-circle"
        RiskBand.POTENTIAL -> "info"
        RiskBand.MODERATE -> "bell-simple"
        RiskBand.HIGH -> "shield-warning"
        null -> "clock"
    }
    /** Name of the ThemeColors field used to tint the icon. Existing tokens only. */
    fun bandToken(band: RiskBand?): String = when (band) {
        RiskBand.LOW -> "accentSeafoam"
        RiskBand.POTENTIAL -> "warmHoney"
        RiskBand.MODERATE, RiskBand.HIGH -> "warmCoral"
        null -> "textSecondary"
    }
    fun reviewWords(reason: String?): String = when (reason) {
        "context_too_long" -> "對話太長，模型未能處理，暫時沒有分數。"
        else -> "模型未能肯定，內容可能不足以判斷，暫時沒有分數。"
    }
    fun scoreText(row: RiskScoreRow): String = row.score?.let { "${whole(it)} / 100" } ?: reviewWords(row.reviewReason)
    fun rowDescription(row: RiskScoreRow): String {
        val where = "對話對象 ${row.accountLabel}，日期 ${spokenDay(row.scoredAt)}"
        return row.score?.let { "${bandTitle(band(row))}，分數 ${whole(it)}，滿分 100，$where" }
            ?: "需要檢視，${reviewWords(row.reviewReason)}$where"
    }

    fun leadSentence(m: ScoresPageModel): String {
        val a = if (m.needAttention == 0) "現時沒有對話的分數達 50 或以上。" else "現時有 ${m.needAttention} 個對話的分數在 50 或以上，值得留意。"
        val b = if (m.needReview == 0) "" else "另有 ${m.needReview} 個對話需要檢視（模型未能給出分數）。"
        return a + b
    }

    // ---------------- parent wording ----------------
    const val MODEL_FILE = RiskModelSpec.FILE_NAME
    val BYTES_TEXT: String = String.format(Locale.ROOT, "%,d", RiskModelSpec.FILE_BYTES)
    val MODEL_SIZE_TEXT: String = "$BYTES_TEXT 位元組（約 1.03 GB）"
    const val LIMITS_NOTICE = "分數來自一個用虛構對話、開發者提供的少量真實對話及公開研究資料集（PAN-12）訓練及測試的模型，可能高估，也可能漏掉真正的風險，尤其無法可靠分辨成年人之間的性對話與誘騙。沒有證據顯示它能可靠偵測真正的誘騙。它是一個訊號，不是結論。"
    const val NEXT_STEPS = "下一步：先和孩子好好談談。如果你認為孩子有即時危險，請致電 999（香港）。"
    const val NO_MESSAGES = "在這個模式下，你不會收到訊息內容。"
    fun unknownText(n: Int) = "被視為群組聊天而沒有被分析的對話：$n 個。"
    fun staleBody(receivedAt: Long, now: Long = System.currentTimeMillis()) = "已超過 ${hoursSince(receivedAt, now)} 小時沒有收到子女手機的報告。最後一次報告：${time(receivedAt)}。"

    data class StateCopy(val title: String, val body: String, val next: String)
    fun copy(m: ScoresPageModel): StateCopy = when (m.page) {
        ScoresPage.OFF -> StateCopy("僅分數模式未開啟",
            "開啟後，子女手機會在充電時於手機上分析一對一對話，你只會收到分數和不含文字的提醒，不會收到訊息內容。從畫面讀取的對話無法與群組聊天區分，所以孩子在這部手機上打開的群組聊天也可能被分析；已知是群組的對話不會被分析。",
            "下一步：到「家庭」頁開啟僅分數模式。")
        ScoresPage.WAITING -> StateCopy("等待子女同意",
            if (m.noReportYet) "已送出設定，但還沒有收到子女手機的回覆。子女需要在自己的手機閱讀說明並同意，分析才會開始。你不能代替子女同意。"
            else "子女需要在自己的手機閱讀說明並同意，分析才會開始。你不能代替子女同意，在子女同意之前，子女手機維持原有的運作方式。",
            "下一步：請向孩子說明這個功能，並請孩子打開 IG Guard 查看。")
        ScoresPage.RECONSENT -> StateCopy("子女需要再次同意",
            "說明文字已更新，子女需要在自己的手機上再次同意。在子女再次同意之前，子女手機不會儲存或分析任何內容，也不會把訊息文字傳給你，只會傳送不含文字的提醒。你不能代替子女同意。",
            "下一步：請向孩子說明，並請孩子打開 IG Guard 查看。")
        ScoresPage.MODEL_MISSING -> StateCopy("子女手機尚未安裝模型",
            "分析模型（$MODEL_FILE）隨 App 內附，子女手機第一次打開 App 時會自己安裝。如果這個版本的 App 沒有內附模型，AI 風險分析無法使用，請改裝內附模型的安裝包。在安裝之前不會有新的分數，而在這個模式下你也不會收到訊息內容。",
            "下一步：請子女打開 IG Guard，等安裝完成（手機需要約 1.1 GB 可用空間）。只有沒有內附模型的版本，才需要在子女手機的「權限」頁手動匯入（需要家長密碼）。")
        ScoresPage.MODEL_WRONG -> StateCopy("子女手機的模型檔不正確",
            "子女手機上的檔案不是指定的模型檔，所以不會被使用。只接受完全相同的檔案：$MODEL_FILE，$MODEL_SIZE_TEXT。",
            "下一步：在子女手機移除該檔案（需要家長密碼），再安裝 App 內附的模型。")
        ScoresPage.MODEL_UNSUPPORTED -> StateCopy("子女手機不支援這個功能",
            "這個模式需要約 6 GB 記憶體和 64 位元 ARM 處理器（arm64）的手機。子女手機的記憶體不足或處理器不支援，不能在手機上分析。",
            "下一步：如不打算使用，可以到「家庭」頁關閉僅分數模式。")
        ScoresPage.PAUSED -> StateCopy("子女已暫停分析",
            "子女在 ${m.state?.pausedAt?.let(::time) ?: "較早前"} 暫停了分析。暫停期間不會有新的分數；下面只顯示暫停前收到的分數。子女可以隨時恢復，不需要你操作。" +
                (m.alsoModelProblem?.let { "另外，子女手機的模型也有問題：${modelWords(it)}，恢復後也不會有分數，直至處理好。" } ?: ""),
            "下一步：如果想知道原因，可以用不責備的語氣問問孩子。")
        ScoresPage.ENGINE_UNAVAILABLE -> StateCopy("子女手機的分析功能未能啟動",
            "這個 App 的分析部分未能在子女手機上啟動，所以不會有分數。這和手機記憶體或處理器不足是不同的問題，在這個模式下你也不會收到訊息內容。",
            "下一步：請在子女手機更新或重新安裝 IG Guard，然後再看。")
        ScoresPage.NOT_RUNNING -> StateCopy("子女已同意，但分析沒有在子女手機上運行",
            "子女已同意，但子女手機目前沒有在分析（可能是雙向擷取尚未確認，或擷取已停止）。在恢復之前不會有新的分數；在這個模式下你也不會收到訊息內容。",
            "下一步：請確認子女手機已確認雙向擷取的範圍，而且手機有在充電。")
        ScoresPage.WITHDRAWN -> StateCopy("子女已撤回同意",
            "子女已撤回同意，分析已停止，子女手機上儲存的訊息已刪除。你之前收到的分數仍會留在這裡，但不再更新。",
            "下一步：如要再次使用，請先在「家庭」頁關閉僅分數模式，再重新開啟；子女需要重新同意。")
        ScoresPage.EMPTY -> StateCopy("分析進行中，暫時還沒有分數",
            "分析會在子女手機充電時進行，需要一點時間。第一批分數出現後會顯示在這裡。" +
                (m.state?.lastRun?.let { "最近一次分析：${time(it)}，共 ${m.state.scoredCount} 個對話。" } ?: ""),
            "下一步：不需要操作，請稍後再看。")
        ScoresPage.ERROR -> StateCopy("未能讀取分數", "讀取這部手機上的分數時出錯。已收到的資料沒有被刪除。", "下一步：請稍後重試。")
        ScoresPage.LIST -> StateCopy("對話風險分數", leadSentence(m), "")
    }

    /** Short state text for the family-tab switch. */
    fun switchStatus(policy: ScoresModePolicy?, state: ScoresModeState?, rows: List<RiskScoreRow>): String {
        val m = classify(policy, state, rows, active = state?.let(ScoresEngineBridge.activeOf))
        return when (m.page) {
            ScoresPage.OFF -> "未開啟"
            ScoresPage.WAITING -> "已開啟 · 等待子女同意"
            ScoresPage.RECONSENT -> "已開啟 · 子女需要再次同意"
            ScoresPage.WITHDRAWN -> "已開啟 · 子女已撤回同意"
            ScoresPage.PAUSED -> "已開啟 · 子女已暫停"
            ScoresPage.MODEL_MISSING, ScoresPage.MODEL_WRONG, ScoresPage.MODEL_UNSUPPORTED, ScoresPage.ENGINE_UNAVAILABLE -> "已開啟 · 子女手機的模型或分析功能有問題"
            ScoresPage.NOT_RUNNING -> "已開啟 · 子女已同意，但分析沒有在運行"
            else -> "已開啟 · 子女已同意"
        }
    }
    fun canEnable(state: ScoresModeState?) = state?.model == "valid"
    fun enableBlockedWhy(state: ScoresModeState?): String = when {
        state == null -> "還沒有收到子女手機的回報，暫時不能開啟。"
        state.model == "unsupported_device" -> "子女手機的記憶體不足或處理器不支援，不能使用這個模式。"
        state.model == "engine_unavailable" -> "子女手機的分析功能未能啟動。更新或重新安裝 IG Guard 可能有幫助。"
        state.model == "mismatch" -> "子女手機上的模型檔不正確。請先在子女手機移除它，再安裝 App 內附的模型。"
        else -> "分析模型隨 App 內附，子女手機第一次打開 IG Guard 時會自己安裝（需要約 1.1 GB 可用空間），回報模型有效後才能開啟。如果這個版本沒有內附模型，AI 風險分析無法使用，請改裝內附模型的安裝包。"
    }

    const val PARENT_ON_TITLE = "改用「僅分數模式」？ / Switch to scores-only mode?"
    val PARENT_ON_POINTS = listOf(
        "子女手機會在充電時，於手機上分析一對一對話。從畫面讀取的對話無法與群組聊天區分，所以孩子打開的群組聊天也可能被分析；已知是群組的對話不會被分析。你只會收到分數和不含文字的提醒。\nYour child's phone analyses one-to-one chats on the phone while charging. Chats read from the screen cannot be told apart from group chats, so a group chat the child opens may be analysed too; a chat known to be a group is not analysed. You receive scores and alerts without text.",
        "已經配對的家庭：新訊息的文字不會再傳到你的手機。\nFor an existing family, the text of new messages stops reaching your phone.",
        "你手機上現有的封存會保留，直到你自己刪除。\nThe existing archive stays on your phone until you delete it.",
        "在這個模式下，即使遇到緊急情況，你也不能閱讀訊息內容。\nIn this mode you cannot read messages, even in an emergency.",
        "子女要在自己的手機上同意才會開始，你不能代替子女同意；在此之前維持原有的運作方式。\nYour child must agree on their own phone. Until then nothing changes.",
        "本功能未獲 Meta 批准，也不代表已通過法律審核。\nNot approved by Meta, and not legally cleared."
    )
    const val PARENT_OFF_TITLE = "關閉「僅分數模式」？ / Turn off scores-only mode?"
    const val PARENT_OFF_BODY = "關閉後，子女手機會停止分析並刪除為分析儲存的訊息，原有的運作方式再次適用：新訊息會再傳到你的手機。已收到的分數仍保留。子女會看到這個改變。\nThe child's phone stops analysing and deletes the messages stored for it. The earlier mode applies again, so new messages reach your phone. Scores already received stay. Your child is told."
    /** The child's phone agreed to upload, so after a switch-off capture stays off until the child allows it: the earlier mode does not simply apply again. */
    private const val PARENT_OFF_BODY_UPLOAD = "關閉後，子女手機會停止分析並刪除為分析儲存的訊息。因為子女之前選擇了手動上載，擷取會保持關閉，直到子女在自己的手機上再次允許；在此之前你不會收到新訊息。已收到的分數仍保留。子女會看到這個改變。\nThe child's phone stops analysing and deletes the messages stored for it. Because your child had chosen manual upload, capture will stay off until your child allows it again on their phone, so no new messages reach you until then. Scores already received stay. Your child is told."
    fun parentOffBody(method: String) = if (method == ScoresMethod.UPLOAD) PARENT_OFF_BODY_UPLOAD else PARENT_OFF_BODY
    const val PARENT_CAPTURE_HELD = "子女手機的擷取已暫停，要等子女再次允許。\nCapture is paused until your child allows it again."
    const val SEND_FAILED ="未能開啟。子女手機需要先回報模型有效，也請確認這部手機的儲存空間正常，然後重試。"
    const val OFF_FAILED = "未能關閉僅分數模式，設定沒有改變。請稍後重試。"
    const val RESEND_FAILED = "未能再次送出設定，請稍後重試。"
    const val RESEND = "再次送出設定"
    const val RESEND_HINT = "子女手機一直沒有回覆這項設定。你可以再送出一次；子女手機收到相同的設定時不會要求重新同意。"
    const val NO_TEXT_PLAIN = "此提醒沒有附帶文字。"

    // ---------------- collection method (parent) ----------------
    const val METHOD_TITLE = "收集方式 / How chats reach the analysis"
    const val METHOD_UPLOAD = "手動上載 / Manual upload"
    const val METHOD_CAPTURE = "自動擷取 / Automatic capture"
    fun methodName(method: String) = if (method == ScoresMethod.UPLOAD) METHOD_UPLOAD else METHOD_CAPTURE
    const val METHOD_UPLOAD_BODY = "子女自己從 Instagram 下載資料（「下載你的資料」），再在自己的手機匯入。本 App 不讀取 Instagram 的通知或畫面，所以沒有即時的關鍵字提醒，也沒有陌生人提醒。你仍然只會收到分數。\nYour child downloads their own data from Instagram (\"Download your information\") and imports it on their own phone. This app reads nothing from Instagram notifications or screens, so there are no real-time keyword alerts and no unapproved-contact alerts. You still receive scores only."
    const val METHOD_CAPTURE_BODY = "本 App 讀取子女手機上的 Instagram 通知及對話畫面（現有方式）。你仍然只會收到分數。\nThis app reads Instagram notifications and conversation screens on your child's phone (the current way). You still receive scores only."
    /** The warning, word for word as Derek read it (docs/superpowers/specs/2026-11-20-collection-method-design.md). */
    const val WARNING_ZH_TITLE = "自動擷取可能違反 Instagram 使用條款"
    const val WARNING_ZH_BODY = "此方式會讀取子女手機上的 Instagram 通知及對話畫面。Instagram 的使用條款禁止未經其許可以自動方式取得資料。子女的 Instagram 帳戶可能因此被限制或停用。IG Guard 與 Meta 無關，亦未獲 Meta 批准。選擇此方式即表示你明白上述風險，並由你自行決定及承擔使用此方式的責任。如不接受，請改用「手動上載」。"
    const val WARNING_EN_TITLE = "Automatic capture may breach Instagram's Terms of Use"
    const val WARNING_EN_BODY = "This method reads Instagram notifications and conversation screens on your child's phone. Instagram's Terms of Use prohibit collecting information in an automated way without its permission. Your child's Instagram account could be restricted or disabled as a result. IG Guard is independent of Meta and has no Meta approval. By choosing this method you confirm that you understand this risk and that the decision to use it, and the responsibility for it, are yours. If you do not accept this, choose Manual upload."
    const val WARNING_ACK = "我明白上述風險，並自行決定及承擔選擇自動擷取的責任。\nI understand the risk above, and the decision and the responsibility are mine."
    const val METHOD_CHANGE = "更改收集方式 / Change method"
    const val METHOD_CHANGE_TITLE = "更改收集方式？ / Change the method?"
    const val METHOD_CHANGE_POINTS = "子女要在自己的手機上再次同意；在此之前維持原來的收集方式，你不能代替子女同意。子女同意後，手機上為分析儲存的訊息會被刪除。\nYour child must agree again on their own phone. Until then the earlier method stays in effect, and you cannot agree for them. When they agree, the messages stored for analysis on their phone are deleted."
    const val METHOD_SEND = "確認並送出 / Confirm and send"
    const val METHOD_FAILED = "未能更改收集方式，設定沒有改變。請稍後重試。"
    fun methodLine(method: String) = "收集方式：${methodName(method)}\nMethod: ${if (method == ScoresMethod.UPLOAD) "Manual upload" else "Automatic capture"}"
}

/** Child-side wording, bilingual in the style of the first-launch consent page. */
internal object ScoresChildCopy {
    const val TITLE = "AI 風險分析 / AI risk analysis"
    const val CONSENT_TITLE = "先了解，再決定\nAI risk analysis: read first, then decide"
    val CONSENT_POINTS = listOf(
        "分析什麼 / What is analysed" to "只分析本 App 已經讀取的 Instagram 一對一對話。從畫面讀取的對話無法與群組聊天區分，所以在這部手機上打開的群組聊天也可能被分析；已知是群組的對話不會被分析。每個對話只保留最新 60 則訊息，存放在這部手機上，最長 14 天。\nOne-to-one Instagram chats that this app already reads are analysed. Chats read from the screen cannot be told apart from group chats, so a group chat that is opened on this phone may be analysed too. A chat known to be a group is not analysed. The latest 60 messages per conversation are kept on this phone for at most 14 days.",
        "在哪裡、何時 / Where and when" to "分析在這部手機上進行，只在充電時運行。訊息不會為了分析而傳出這部手機。\nAnalysis runs on this phone, only while it is charging. Messages are not sent anywhere for analysis.",
        "家長會收到 / What your parent receives" to "你聊天對象的帳戶名稱、一個風險分數（或「需要檢視」）及時間；不含訊息文字的提醒；以及分析是開啟、暫停還是已撤回。\nThe account name of who you chat with, a risk score (or \"needs review\") and the time; alerts without message text; and whether analysis is on, paused or withdrawn.",
        "家長不會收到 / What your parent does not receive" to "家長不會收到你的訊息。在這個模式下，新訊息不再傳給家長。\nYour parent does not receive your messages. In this mode new messages are no longer sent to them.",
        "分數可能出錯 / Scores can be wrong" to "這個模型用虛構的對話、開發者提供的少量真實對話，以及公開研究資料集（PAN-12）的真實聊天來訓練及測試。它兩個方向都可能出錯：可能把普通對話評為有風險，也可能漏掉真正的問題，尤其無法可靠分辨成年人之間的性對話與誘騙。沒有證據顯示它能可靠偵測真正的誘騙。分數只是一個訊號。\nThe model was trained and tested on invented chats, a small number of real chats supplied by the developer, and real chats from a public research collection (PAN-12). It can be wrong in both directions: it can score an ordinary chat as risky and miss a real problem, and it cannot reliably tell sexual conversation between adults from grooming. It is not shown to detect real grooming reliably. A score is only a signal.",
        "你的控制 / Your controls" to "你可以隨時暫停，家長會被告知。你也可以撤回同意：這部手機上儲存的訊息會被刪除，家長會被告知；已經傳給家長的分數仍留在家長手機。\nYou can pause at any time and your parent is told. You can withdraw: the messages stored on this phone are deleted and your parent is told. Scores already sent stay on your parent's phone.",
        "重要 / Please note" to "本功能未獲 Meta 批准，也不代表已通過法律審核。選擇「暫時不要」不會儲存或分析任何內容，原有的運作方式繼續。\nNot approved by Meta and not legally cleared. Choosing \"Not now\" stores and analyses nothing, and the earlier behaviour continues."
    )
    /** On the page for a child who agreed to older wording, "Not now" must not promise the earlier mode: nothing is sent as text either way. */
    /** The page for [method], the one the parent asked for: its own first point says how chats reach the analysis; upload words replace the capture-only first point. */
    fun consentPoints(reconsent: Boolean, method: String = ScoresMethod.CAPTURE): List<Pair<String, String>> {
        val base = consentBasePoints(reconsent)
        return if (method == ScoresMethod.UPLOAD) listOf(UPLOAD_METHOD_POINT, UPLOAD_ANALYSED_POINT) + base.drop(1) else listOf(CAPTURE_METHOD_POINT) + base
    }
    private val UPLOAD_METHOD_POINT = "收集方式：手動上載 / Method: manual upload" to "你的家長選擇了手動上載。你自己從 Instagram 下載資料，再在這部手機匯入。本 App 不讀取 Instagram 的通知或畫面，所以你可以關閉「通知存取」和「無障礙」（本 App 不能代你關閉）。這個方式沒有即時的關鍵字提醒，也沒有陌生人提醒。\nYour parent chose manual upload. You download your own data from Instagram and import it on this phone. This app reads nothing from Instagram notifications or screens, so you may switch off Notification access and Accessibility (the app cannot switch them off for you). There are no real-time keyword alerts and no unapproved-contact alerts."
    private val UPLOAD_ANALYSED_POINT = "分析什麼 / What is analysed" to "只分析你匯入的 Instagram 資料檔裡的一對一對話；群組聊天不會被分析。每個對話只保留最新 60 則訊息，存放在這部手機上，最長 14 天。你選擇的檔案只會被讀取，不會被複製到本 App 的儲存空間。\nOnly one-to-one chats in the Instagram file you import are analysed; group chats are not. The latest 60 messages per conversation are kept on this phone for at most 14 days. The file you pick is only read, never copied into this app's storage."
    private val CAPTURE_METHOD_POINT = "收集方式：自動擷取 / Method: automatic capture" to "你的家長選擇了自動擷取：本 App 讀取你手機上 Instagram 的通知及對話畫面。這可能違反 Instagram 的使用條款，你的 Instagram 帳戶可能因此被限制或停用。本 App 與 Meta 無關，亦未獲 Meta 批准。\nYour parent chose automatic capture: this app reads Instagram notifications and conversation screens on your phone. This may breach Instagram's Terms of Use, and your Instagram account could be restricted or disabled. This app is independent of Meta and has no Meta approval."
    private fun consentBasePoints(reconsent: Boolean): List<Pair<String, String>> = if (!reconsent) CONSENT_POINTS else CONSENT_POINTS.dropLast(1) + (
        "重要 / Please note" to "本功能未獲 Meta 批准，也不代表已通過法律審核。選擇「暫時不要」不會儲存或分析任何內容，也不會傳送你的訊息文字給家長；不含文字的提醒仍會傳給家長。\nNot approved by Meta and not legally cleared. Choosing \"Not now\" stores and analyses nothing, and no message text is sent to your parent; alerts without text still go to your parent.")
    const val METHOD_CARD_TITLE = "家長更改了收集方式 / Your parent changed the method"
    const val METHOD_CARD_BODY = "你同意之前，原來的方式繼續。請閱讀並決定。\nUntil you agree, the earlier method stays in effect. Please read and decide."
    const val UPLOAD_ON_TITLE = "手動上載已開啟 / Manual upload is on"
    const val UPLOAD_ON_NOTE = "本 App 不讀取 Instagram 的通知或畫面。你現在可以到 Android 設定關閉「通知存取」和「無障礙」；本 App 不能代你關閉。\nThis app reads nothing from Instagram notifications or screens. You may now switch off Notification access and Accessibility in Android settings; the app cannot do it for you."
    const val IMPORT_EXPORT_ROW = "匯入 Instagram 資料 / Import Instagram export"
    const val IMPORT_EXPORT_DESC = "選取 Instagram「下載你的資料」得到的 .zip，或其中一個 message_N.html / message_N.json。檔案只會被讀取，不會被複製。\nPick the .zip from Instagram's \"Download your information\", or one message_N.html / message_N.json from it. The file is only read, never copied."
    const val EXPORT_RUNNING = "正在讀取檔案…\nReading the file…"
    const val EXPORT_PICKER_MISSING = "找不到檔案選擇器。\nNo file picker is available."
    /** Title and body for the outcome of an import; every failure says that nothing was stored. */
    fun exportResult(s: ImportSummary): Pair<String, String> = when (s.result) {
        ImportResult.OK -> if (s.conversations == 0) ("沒有可分析的對話 / No chats to analyse" to "這個檔案裡沒有一對一對話（群組聊天 ${s.groups} 個已略過）。\nThe file has no one-to-one chats (${s.groups} group chats were skipped).")
            else ("已匯入 / Imported" to "${s.conversations} 個一對一對話，新增 ${s.messages} 則訊息；略過群組聊天 ${s.groups} 個。分析會在手機充電時進行。\n${s.conversations} one-to-one chats, ${s.messages} new messages; ${s.groups} group chats skipped. Analysis runs while the phone is charging.")
        ImportResult.NOTHING_FOUND -> "找不到訊息 / No messages found" to "這個檔案裡沒有 Instagram 訊息檔（message_N.html 或 message_N.json）。沒有儲存任何內容。\nNo Instagram message files were found. Nothing was stored."
        ImportResult.NOT_ALLOWED -> "現在不能匯入 / Cannot import now" to "需要你已同意手動上載，而且分析沒有暫停、模型有效。沒有儲存任何內容。\nYou must have agreed to manual upload, analysis must not be paused, and the model must be valid. Nothing was stored."
        ImportResult.TOO_LARGE -> "檔案太大 / The file is too large" to "檔案或其內容超過上限。請只下載訊息（不含相片和影片）。沒有儲存任何內容。\nThe file or its contents are over the limit. Download messages only, without photos and videos. Nothing was stored."
        ImportResult.TOO_MANY_ENTRIES -> "檔案裡的項目太多 / Too many items in the file" to "請只下載訊息，或選取其中一個對話的檔案。沒有儲存任何內容。\nDownload messages only, or pick one conversation's file. Nothing was stored."
        ImportResult.UNSAFE_ENTRY -> "檔案不安全 / The file is not safe" to "壓縮檔裡有不安全的檔案路徑，所以整個檔案被拒絕。沒有儲存任何內容。\nThe archive contains an unsafe file path, so the whole file was refused. Nothing was stored."
        ImportResult.MALFORMED -> "不是有效的 Instagram 資料檔 / Not a valid Instagram file" to "檔案損壞，或不是 Instagram 的訊息檔。沒有儲存任何內容。\nThe file is damaged or is not an Instagram message file. Nothing was stored."
        ImportResult.TEEN_UNKNOWN -> "分不出哪一位是你 / Cannot tell which one is you" to "單一 HTML 檔不能判斷哪一位是你。請改選整個 .zip，或選 message_N.json。沒有儲存任何內容。\nA single HTML file cannot tell which person is you. Pick the whole .zip or a message_N.json instead. Nothing was stored."
        ImportResult.TIME_UNREADABLE -> "讀不到訊息的時間 / Cannot read the message times" to "這個檔案的訊息時間格式本 App 讀不懂，所以無法判斷訊息有多新。請再向 Instagram 要求下載資料，格式選「JSON」。沒有儲存任何內容。\nThis app cannot read the time format in this file, so it cannot tell how recent the messages are. Request your data from Instagram again and choose the JSON format. Nothing was stored."
        ImportResult.IO_ERROR -> "讀取檔案時出錯 / Could not read the file" to "檔案可能已移動，或儲存裝置中斷了。沒有儲存任何內容，請重試。\nThe file may have moved or the storage was interrupted. Nothing was stored. Try again."
    }
    const val RECONSENT_CARD_TITLE = "請再次決定 / Please decide again"
    const val RECONSENT_CARD_BODY = "說明文字已更新。你同意之前，不會儲存或分析任何內容，也不會傳送你的訊息文字給家長。\nThe explanation was updated. Until you agree, nothing is stored or analysed and no message text is sent to your parent."
    const val AGREE = "我同意 / I agree"
    const val NOT_NOW = "暫時不要 / Not now"
    const val CARD_TITLE = "需要你的決定 / Your decision is needed"
    const val CARD_BODY = "家長開啟了 AI 風險分析。你同意之前，不會儲存或分析任何內容。\nYour parent turned on AI risk analysis. Nothing is stored or analysed until you agree."
    const val CARD_ACTION = "閱讀並決定 / Read and decide"

    const val STATUS_RUNS = "在這部手機上、充電時運行。\nRuns on this phone while charging."
    const val STATUS_PARENT_SEES = "家長會看到你和誰聊天及風險分數，看不到你的訊息。\nYour parent sees who you chat with and a risk score. They do not see your messages."

    enum class StatusState(val label: String, val icon: String) {
        ON("開啟 / ON", "check-circle"), PAUSED("已暫停 / PAUSED", "clock"), WAITING("等待同意 / WAITING", "info"), RECONSENT("需要再次同意 / AGREE AGAIN", "info"),
        WITHDRAWN("已撤回 / WITHDRAWN", "x"), MODEL_MISSING("缺少模型 / MODEL MISSING", "shield-warning"),
        NOT_AVAILABLE("此手機不適用 / NOT AVAILABLE", "lock-key"), ENGINE_UNAVAILABLE("分析功能未能啟動 / ENGINE UNAVAILABLE", "shield-warning"),
        NOT_RUNNING("未運作 / NOT RUNNING", "info")
    }
    fun statusState(s: RiskChildStatus): StatusState = when {
        s.modelState == "unsupported_device" -> StatusState.NOT_AVAILABLE
        s.modelState == "engine_unavailable" -> StatusState.ENGINE_UNAVAILABLE
        s.consent == "withdrawn" -> StatusState.WITHDRAWN
        s.consent == "none" -> StatusState.WAITING
        s.consent == "reconsent" -> StatusState.RECONSENT
        s.paused -> StatusState.PAUSED
        s.modelState != "valid" -> StatusState.MODEL_MISSING
        s.active -> StatusState.ON
        else -> StatusState.NOT_RUNNING
    }
    const val UNTIL_AGREE_AGAIN = "說明文字已更新，需要你再次同意。你同意之前，不會儲存或分析任何內容，也不會把你的訊息文字傳給家長。你也可以撤回同意。\nThe explanation was updated, so we need you to agree again. Until you do, nothing is stored or analysed and no message text is sent to your parent. You can also withdraw."
    /** While capture is held after a switch-off that followed manual upload: for the ongoing notice and the child's status line. */
    const val HELD_STATUS = "IG Guard 暫時不讀取 Instagram：你之前選擇了手動上載，要等你在 App 內同意後才會再讀取，家長暫時不會收到你的訊息。\nIG Guard is not reading Instagram: you had chosen manual upload, so it waits for your agreement in the app. Your parent does not receive your messages meanwhile."
    private const val UNTIL_AGREE_HELD = "你同意之前，不會儲存或分析任何內容，IG Guard 也不會讀取 Instagram，因為你之前選擇了手動上載。\nUntil you agree, nothing is stored or analysed and capture stays off, because you had chosen manual upload."
    const val UNTIL_AGREE = "你同意之前，不會儲存或分析任何內容，原有的運作方式繼續：你的家長仍會像以往一樣收到訊息。\nUntil you agree, nothing is stored or analysed and the earlier mode continues, so your parent still receives messages as before."
    private const val PARENT_GETS_NO_TEXT = "家長不會收到你的訊息文字，只會收到不含文字的提醒。\nYour parent does not receive your message text, only alerts without text."
    /** What is and is not happening in each state. Never says the parent does not see messages unless that is true. */
    fun statusLines(st: StatusState, consent: String, held: Boolean = false): List<String> = when (st) {
        StatusState.ON -> listOf(STATUS_RUNS, STATUS_PARENT_SEES)
        StatusState.WAITING -> listOf(if (held) UNTIL_AGREE_HELD else UNTIL_AGREE)
        StatusState.RECONSENT -> listOf(UNTIL_AGREE_AGAIN, PARENT_GETS_NO_TEXT)
        StatusState.PAUSED -> listOf("暫停期間不會儲存或分析任何訊息，家長已被告知你暫停了。不含文字的提醒仍會傳給家長，家長不會收到你的訊息文字。\nWhile paused no messages are stored or analysed, and your parent is told you paused. Alerts without text still go to your parent, who does not receive your message text.")
        StatusState.WITHDRAWN -> listOf("你已撤回同意：分析已停止，這部手機上儲存的訊息已刪除，家長已被告知。在家長關閉僅分數模式之前，家長不會收到訊息文字，只會收到不含文字的提醒。\nYou withdrew: analysis stopped, the messages stored on this phone were deleted and your parent is told. Until your parent turns the mode off, they do not receive message text, only alerts without text.")
        StatusState.MODEL_MISSING -> listOf("這部手機沒有有效的模型檔，所以不會儲存或分析任何內容。模型隨 App 內附，會自動安裝；如果這個版本沒有內附模型，AI 風險分析無法使用。\nThere is no valid model file on this phone, so nothing is stored or analysed. The model comes with the app and installs itself; if this build does not include it, AI risk analysis is unavailable.", PARENT_GETS_NO_TEXT)
        StatusState.NOT_AVAILABLE -> listOf("這部手機的記憶體不足或處理器不支援，不能使用這個功能，所以不會儲存或分析任何內容。\nThis phone does not have enough memory or the right processor, so nothing is stored or analysed.") + noteForConsent(consent, held)
        StatusState.ENGINE_UNAVAILABLE -> listOf("這個 App 的分析部分未能在這部手機上啟動，所以不會儲存或分析任何內容。更新或重新安裝 App 可能有幫助。\nThe analysis part of this app could not start on this phone, so nothing is stored or analysed. Updating or reinstalling the app may help.") + noteForConsent(consent, held)
        StatusState.NOT_RUNNING -> listOf("目前沒有儲存或分析任何內容（擷取已停止，或雙向擷取尚未確認）。\nNothing is being stored or analysed right now (capture is stopped, or two-way capture is not confirmed).", PARENT_GETS_NO_TEXT)
    }
    private fun noteForConsent(consent: String, held: Boolean) = if (held) listOf(HELD_STATUS) else if (consent == "none") listOf("原有的運作方式繼續：你的家長仍會像以往一樣收到訊息。\nThe earlier mode continues, so your parent still receives messages as before.") else listOf(PARENT_GETS_NO_TEXT)
    fun lastRun(s: RiskChildStatus): String = s.lastRun?.let { "上次運行：${ScoresUi.time(it)} · ${s.scoredCount} 個對話\nLast run: ${ScoresUi.time(it)} · ${s.scoredCount} conversations" }
        ?: "尚未運行\nNot run yet"
    fun unknown(n: Int) = "被視為群組而未分析的聊天：$n 個\nGroup chats set aside, not analysed: $n"
    const val PAUSE_FAILED = "未能暫停，狀態沒有改變。請再試一次。\nCould not pause. Nothing changed. Please try again."
    const val RESUME_FAILED = "未能恢復，狀態沒有改變。請再試一次。\nCould not resume. Nothing changed. Please try again."
    const val WITHDRAW_FAILED = "未能撤回，狀態沒有改變，儲存的訊息仍在。請再試一次。\nCould not withdraw. Nothing changed and the stored messages are still here. Please try again."
    const val ACCEPT_FAILED = "未能儲存你的同意，沒有開始分析。請重試。\nCould not save your choice. Nothing was started. Please try again."
    const val ENGINE_CARD_TITLE = "分析功能未能啟動\nThe analysis part could not start"
    const val ENGINE_CARD_BODY = "這個 App 的分析部分未能在這部手機上啟動。這和記憶體或處理器不足是不同的問題。更新或重新安裝 App 可能有幫助。\nThe analysis part of this app could not start on this phone. This is different from too little memory or the wrong processor. Updating or reinstalling the app may help."

    // Mode-aware replacements for the older lines that say messages are sent to the parent (used only while the mode is content-free).
    const val MODE_DISCLAIMER = "僅供符合 Instagram 最低年齡要求（至少 13 歲或當地更高要求）的家庭使用。此版本不讀取未送出草稿。通知與可見 DM 擷取可能不完整。在僅分數模式下，訊息文字只留在這部手機，只有分數和不含文字的提醒會傳給家長。可在「擷取範圍與停止」停止擷取；Android 權限可另行關閉，已送達家長的分數和提醒需另行刪除。"
    const val MODE_CAPTURE_STATUS = "僅分數模式 · 訊息文字只留在這部手機，只傳送分數和不含文字的提醒。點按閱讀說明。"
    const val MODE_PRIVACY_INTRO = "停止會阻止新擷取與尚未送出的內容。在僅分數模式下，家長沒有收到你的訊息文字，只有分數和不含文字的提醒；已送達家長的分數和提醒需在家長手機另行刪除。重新開始不會自動恢復雙向擷取。"
    const val MODE_NOTICE_MAIN = "你的家長開啟了僅分數模式：訊息文字只留在這部手機，家長只收到分數和不含文字的提醒。雙向擷取的選項只決定這部手機擷取什麼，不會把訊息文字傳給家長。"
    const val MODE_NOTICE_TRANSPORT = "分數和不含文字的提醒會先在此手機加密，經本 App 的 Cloudflare 中繼傳送至配對家長手機。開發者及 Cloudflare 可處理密文及連線資料，中繼不會取得家庭解密金鑰。"
    const val MODE_PERMISSIONS_CLAUSE = "訊息文字只留在這部手機，只有分數和不含文字的提醒會加密傳至配對家長；待傳記錄最多 72 小時。"

    const val PAUSE = "暫停 / Pause"
    const val RESUME = "恢復 / Resume"
    const val WHAT = "分析什麼？ / What is analysed?"
    const val WITHDRAW = "撤回同意 / Withdraw"
    const val WITHDRAW_TITLE = "撤回並刪除？ / Withdraw and delete?"
    const val WITHDRAW_BODY = "分析會停止，這部手機上儲存的訊息會被刪除，家長會被告知。已經算出的分數（不含訊息文字）仍會傳給家長，並留在家長手機。之後如要再用，需要重新同意。\nAnalysis stops. The messages stored on this phone are deleted, and your parent is told. Scores already worked out (they contain no message text) are still sent to your parent and stay on your parent's phone. To use it again you must agree again."
    const val WITHDRAW_CONFIRM = "撤回並刪除 / Withdraw and delete"
    const val CANCEL = "取消 / Cancel"

    // change-of-mode page
    const val CHANGE_ON_TITLE = "模式已改變\nWhat changes now"
    const val CHANGE_ON_BODY = "在僅分數模式下，新訊息不再傳給家長；家長只收到分數、你聊天對象的帳戶名稱和不含文字的提醒。\nIn scores-only mode new messages no longer go to your parent. They receive scores, the account names and alerts without text.\n\n之前已傳到家長手機的訊息仍在家長手機，由家長自行刪除。\nMessages already on your parent's phone stay there until they delete them."
    const val CHANGE_OFF_TITLE = "家長已關閉分數模式\nScores-only mode was turned off"
    const val CHANGE_OFF_BODY = "原有的運作方式再次適用：本 App 會再次按你已確認的範圍，把訊息加密傳給家長。分析已停止，這部手機上為分析儲存的訊息已刪除。\nThe earlier behaviour applies again: this app sends messages to your parent within the scope you confirmed. Analysis has stopped and the messages stored here for it were deleted."
    const val CHANGE_CARD_TITLE = "有一項改變需要你知道 / A change you should know about"
    const val CHANGE_CARD_ACTION = "查看改變 / See what changes"
    const val CHANGE_DONE = "我知道了 / Got it"
    const val CHANGE_OFF_HELD_BODY = "分析已停止，這部手機上為分析儲存的訊息已刪除。因為你之前選擇手動上載，本 App 不會自行再讀取 Instagram，要先得到你的同意。\nAnalysis has stopped and the messages stored here for it were deleted. Because you had chosen manual upload, this app will not read Instagram again on its own: it needs your agreement first."
    // asked while capture is held after a switch-off that followed manual upload
    const val HOLD_TITLE = "IG Guard 暫時不會讀取 Instagram\nIG Guard is not reading Instagram"
    const val HOLD_BODY = "家長已關閉 AI 分析。你之前選擇手動上載，所以本 App 不會讀取 Instagram 的通知或對話畫面，除非你同意。你不作回應，就會維持不讀取。\nYour parent switched AI analysis off. You had chosen manual upload, so IG Guard reads no Instagram notifications or conversation screens unless you agree. If you do nothing, it stays off."
    const val HOLD_ASK = "是否允許 IG Guard 再次讀取 Instagram 的通知和對話畫面？\nMay IG Guard read Instagram notifications and conversation screens again?"
    const val HOLD_ALLOW = "允許讀取 / Allow reading"

    // model import
    const val IMPORT_TITLE = "分析模型檔 / Analysis model file"
    const val IMPORT_ROW = "匯入分析模型"
    const val IMPORT_ROW_DESC = "這個版本的 App 沒有內附模型，所以無法使用 AI 風險分析。請改裝內附模型的安裝包；否則需要家長的密碼，再手動匯入模型檔。\nThis copy of the app does not include the model, so AI risk analysis is unavailable. Install the package that includes it; otherwise, with a parent's password, import the model file by hand."
    /** Manual import when this app already includes the model. */
    const val IMPORT_ROW_DESC_BUNDLED = "也可以手動匯入模型檔，需要家長的密碼。這個 App 已內附模型，通常不需要。\nYou can also import a model file by hand with a parent's password. This app already includes the model, so this is usually not needed."
    const val BUNDLED_ROW = "安裝 App 內附的模型 / Install the model included in this app"
    const val BUNDLED_ROW_DESC = "把這個 App 內附的模型複製到這部手機，不需要選取檔案。\nCopies the model that came with this app onto this phone. No file needs to be picked."
    const val BUNDLED_ROW_DESC_PASSWORD = "需要家長的密碼。模型被移除後，不會自動重新安裝。\nA parent's password is needed. After the model was removed, it is not installed again automatically."
    const val PASSWORD_BUNDLED_TITLE = "安裝內附的模型，需要家長密碼。\nInstalling the included model needs a parent's password."
    const val BUNDLED_PROGRESS = "正在安裝 App 內附的模型…\nInstalling the model included in this app…"
    const val BUNDLED_CANCEL = "取消安裝 / Cancel install"
    const val RETRY = "重試 / Retry"
    const val REMOVE_BUNDLED_NOTE = "\n\n這個 App 內附模型，但移除後不會自動重新安裝；需要時可輸入家長密碼再安裝。\nThis app includes the model, but after removal it is not installed again automatically. A parent's password can install it again."
    const val PASSWORD_IMPORT_TITLE = "匯入模型，需要家長密碼。\nImporting the model needs a parent's password."
    const val PASSWORD_REMOVE_TITLE = "移除模型，需要家長密碼。\nRemoving the model needs a parent's password."
    const val IMPORT_CANCEL = "取消匯入 / Cancel import"
    const val REMOVE = "移除模型 / Remove model"
    const val REMOVE_TITLE = "移除模型？ / Remove the model?"
    const val REMOVE_BODY = "分析會停止，這部手機上為分析儲存的訊息會刪除，家長會被告知這部手機已沒有模型。之後可以再安裝或匯入。\nAnalysis stops, the messages stored for it are deleted, and your parent is told the model is no longer installed. You can install or import it again later.\n\n需要家長的密碼才能移除。\nA parent's password is needed to remove it."
    const val UNSUPPORTED_TITLE = "這部手機不能使用 AI 風險分析\nNot available on this phone"
    const val UNSUPPORTED_BODY = "這個功能需要約 6 GB 記憶體和 64 位元 ARM 處理器（arm64），這部手機不符合。其他保護不受影響。\nThis feature needs about 6 GB of memory and a 64-bit ARM (arm64) processor, and this phone does not meet that. Other protection is unaffected."
    const val INSTALLED = "模型已安裝（大小與校驗碼已核對）\nModel installed (size and checksum checked)"
    const val MISMATCH = "模型檔不正確，不會被使用。請移除後再安裝或匯入。\nThe model file is not correct and will not be used. Remove it, then install or import it again."
    const val OK_DISMISS = "知道了 / OK"

    private const val GIB = 1073741824.0
    fun progress(copied: Long): String = "已複製 %.2f GB / %.2f GB\nCopied %.2f GB of %.2f GB".format(copied / GIB, RiskModelSpec.FILE_BYTES / GIB, copied / GIB, RiskModelSpec.FILE_BYTES / GIB)
    private const val BUNDLED_FAILED = "App 內附的模型安裝失敗 / The included model could not be installed"
    /** [bundled] is true for the copy included in the app; [freeBytes] is the free space at that moment, or negative when unknown. */
    fun result(r: RiskImportResult?, cancelled: Boolean, bundled: Boolean = false, freeBytes: Long = -1L): Pair<String, String>? = when {
        bundled && cancelled -> "已取消安裝 / Install cancelled" to "沒有保存任何內容。可以隨時再安裝。\nNothing was saved. You can install it again at any time."
        // Free space can read higher than the need when the copy itself ran out of room (other apps filled the phone): then no figure for it is shown.
        bundled && r == RiskImportResult.NOT_ENOUGH_SPACE && freeBytes >= 0 && freeBytes < RiskModelStore.spaceNeeded() -> "手機儲存空間不足 / Not enough free space" to
            "安裝 App 內附的模型需要約 %.1f GB 可用空間，這部手機目前只有 %.1f GB。請先釋放空間，再按「重試」。沒有保存任何部分檔案。\nInstalling the model included in this app needs about %.1f GB of free space, and this phone has %.1f GB. Free some space, then tap Retry. No partial file was kept."
                .format(RiskModelStore.spaceNeeded() / GIB, freeBytes / GIB, RiskModelStore.spaceNeeded() / GIB, freeBytes / GIB)
        bundled && r == RiskImportResult.NOT_ENOUGH_SPACE -> "手機儲存空間不足 / Not enough free space" to
            "安裝 App 內附的模型需要約 %.1f GB 可用空間，但複製途中空間用完了。請先釋放空間，再按「重試」。沒有保存任何部分檔案。\nInstalling the model included in this app needs about %.1f GB of free space, and the space ran out while copying. Free some space, then tap Retry. No partial file was kept."
                .format(RiskModelStore.spaceNeeded() / GIB, RiskModelStore.spaceNeeded() / GIB)
        bundled && r == RiskImportResult.WRONG_FILE -> BUNDLED_FAILED to "App 內附的模型與指定的檔案不符（大小或校驗碼不同），所以沒有安裝，也不會被使用。請改用完整的安裝包，或用檔案匯入模型。\nThe model inside this app does not match the expected file (its size or checksum differs), so it was not installed and will not be used. Use a complete package, or import the file by hand."
        bundled && r == RiskImportResult.IO_ERROR -> BUNDLED_FAILED to "複製時出錯，沒有保存任何部分檔案。請重試。\nSomething went wrong while copying. No partial file was kept. Try again."
        cancelled -> "已取消匯入 / Import cancelled" to "沒有保存任何內容。\nNothing was saved."
        r == null -> null
        r == RiskImportResult.OK -> "模型已安裝 / Model installed" to "檔案大小與 SHA-256 校驗碼都核對正確。分析要等你同意、家長開啟模式後才會開始。\nSize and SHA-256 checksum match. Analysis starts only after you agree and your parent turns the mode on."
        r == RiskImportResult.WRONG_FILE -> "這不是指定的模型檔 / This is not the expected model" to "只接受完全相同的檔案：檔案大小和 SHA-256 校驗碼都必須完全一致。預期檔名：${RiskModelSpec.FILE_NAME}，大小 ${ScoresUi.MODEL_SIZE_TEXT}。其他檔案不會被保存。\nOnly the exact file is accepted: its size and SHA-256 checksum must match exactly. Expected file name: ${RiskModelSpec.FILE_NAME}, size ${ScoresUi.BYTES_TEXT} bytes (about 1.03 GB). Anything else is not kept."
        r == RiskImportResult.NOT_ENOUGH_SPACE -> "手機儲存空間不足 / Not enough storage" to "需要約 1.1 GB 可用空間。請先釋放空間再試。沒有保存任何部分檔案。\nAbout 1.1 GB of free space is needed. Free some space and try again. No partial file was kept."
        r == RiskImportResult.IO_ERROR -> "讀取檔案時出錯 / Could not read the file" to "檔案可能已移動，或儲存裝置中斷了。沒有保存任何部分檔案，請重試。\nThe file may have moved or the storage was interrupted. No partial file was kept. Try again."
        else -> "這部手機不能使用 / Not available on this phone" to UNSUPPORTED_BODY
    }
}

/** The model import runs in the process, not in the activity, so rotating the screen does not lose it. */
internal object ModelImportJob {
    @Volatile var running = false
    @Volatile var copied = 0L
    @Volatile var cancelled = false
    @Volatile var wasCancelled = false
    @Volatile var result: RiskImportResult? = null
    /** True while, and after, the run is the install of the model included in the app (so the screen words it that way). */
    @Volatile var bundled = false
    /** Test seams. Production never changes them. */
    @Volatile var importer: (Context, InputStream, (Long) -> Unit) -> RiskImportResult = { c, i, p -> RiskLocal.importModel(c, i, p) }
    @Volatile var bundledInstaller: (Context, () -> InputStream, (Long) -> Unit) -> RiskImportResult = { c, s, p -> RiskLocal.installBundledModel(c, s, p) }
    @Volatile var runner: (Runnable) -> Unit = { Thread(it, "Model import").start() }

    fun dismissResult() { result = null; wasCancelled = false }
    fun requestCancel() { cancelled = true }

    /** The screen showing the progress, if any. Set when an activity resumes and cleared when it pauses, so a destroyed activity is never held. */
    @Volatile var listener: (() -> Unit)? = null
    fun notifyChange() { listener?.invoke() }

    /** Cancellation surfaces as a read error: the engine deletes its partial file and keeps nothing. */
    private fun guard(stream: InputStream) = object : InputStream() {
        override fun read(): Int = stream.read().also { check() }
        override fun read(b: ByteArray, off: Int, len: Int): Int { check(); return stream.read(b, off, len) }
        override fun close() = stream.close()
        private fun check() { if (cancelled) throw IOException("cancelled") }
    }

    private fun launch(app: Context, isBundled: Boolean, work: ((Long) -> Unit) -> RiskImportResult): Boolean {
        synchronized(this) {
            if (running) return false
            running = true; copied = 0L; cancelled = false; wasCancelled = false; result = null; bundled = isBundled
        }
        runner(Runnable {
            var outcome: RiskImportResult
            try {
                var last = 0L
                outcome = work { n -> copied = n; if (n - last >= 8L * 1024 * 1024) { last = n; notifyChange() } }
            } catch (_: Throwable) { outcome = RiskImportResult.IO_ERROR }   // an Error too: `running` must never stay true (bug-review B-6)
            synchronized(this) { wasCancelled = cancelled && outcome != RiskImportResult.OK; result = outcome; running = false }
            // A wrong copy or a cancel is not retried on its own until the app is updated; the screen offers Retry.
            if (isBundled && (wasCancelled || outcome == RiskImportResult.WRONG_FILE)) RiskModelStore.blockAuto(app)
            notifyChange()
        })
        return true
    }

    /** Opens [uri] and imports on a background thread. [notifyChange] is called from that thread whenever the display should update. */
    fun start(ctx: Context, uri: Uri): Boolean {
        val app = ctx.applicationContext
        return launch(app, false) { progress ->
            val stream = app.contentResolver.openInputStream(uri) ?: throw IOException("no stream")
            guard(stream).use { importer(app, it, progress) }
        }
    }

    /** Copies the model included in the app on a background thread. Same checks and progress as [start]; the caller decides whether a password is needed. */
    fun startBundled(ctx: Context): Boolean {
        val app = ctx.applicationContext
        if (!BundledModel.available(app)) return false
        val started = launch(app, true) { progress ->
            bundledInstaller(app, { guard(RiskModelStore.bundledOpener(app) ?: throw IOException("no included model")) }, progress)
        }
        if (started) notifyChange()
        return started
    }
}

/** When the model included in the app is installed without a file picker. Never reads or stores message text, and never touches consent or mode state. */
internal object BundledModel {
    /** A child phone that can run the model, has none, and whose app includes it. */
    fun available(ctx: Context): Boolean =
        GuardEngine.isChildMode(ctx) && RiskModelStore.state(ctx) == RiskModelState.MISSING && RiskModelStore.bundledModelPresent(ctx)

    /** After a parent removed the model, installing the included copy again needs the parent's password. */
    fun needsPassword(ctx: Context): Boolean = RiskModelStore.removedByParent(ctx)

    /** Called when the child role is set and when the app starts. Does nothing after a removal, after a failed copy, or while a copy runs. */
    fun maybeAutoInstall(ctx: Context): Boolean {
        val app = ctx.applicationContext
        if (ModelImportJob.running || RiskModelStore.removedByParent(app) || RiskModelStore.autoBlocked(app)) return false
        return ModelImportJob.startBundled(app)
    }
}

/** The parent notification link for a new score at or above 50. */
internal object ScoresLink {
    fun intent(ctx: Context, scope: String): Intent = Intent(ctx, MainActivity::class.java).setAction(ScoresUi.OPEN_SCORES_ACTION)
        .setData(Uri.Builder().scheme("igguard").authority("scores").appendPath(scope).build())
    fun isScoresLink(i: Intent?, scope: String): Boolean {
        val uri = i?.data ?: return false
        return i.action == ScoresUi.OPEN_SCORES_ACTION && uri.scheme == "igguard" && uri.host == "scores" && uri.pathSegments == listOf(scope)
    }
}
