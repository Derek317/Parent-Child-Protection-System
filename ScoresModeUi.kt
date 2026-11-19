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

/** What the parent's score page shows. */
internal enum class ScoresPage { OFF, WAITING, MODEL_MISSING, MODEL_WRONG, MODEL_UNSUPPORTED, PAUSED, WITHDRAWN, STALE, EMPTY, LIST, ERROR }

internal data class ScoresPageModel(
    val page: ScoresPage, val rows: List<RiskScoreRow>, val needAttention: Int, val needReview: Int,
    val state: ScoresModeState?, val noReportYet: Boolean = false
)

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

    fun classify(policy: ScoresModePolicy?, state: ScoresModeState?, rows: List<RiskScoreRow>): ScoresPageModel {
        val sorted = sort(rows)
        val attention = sorted.count { it.score != null && whole(it.score) >= NEEDS_ATTENTION_FROM }
        val review = sorted.count { it.score == null }
        fun model(page: ScoresPage, noReport: Boolean = false) = ScoresPageModel(page, sorted, attention, review, state, noReport)
        if (policy == null || !policy.enabled) return model(ScoresPage.OFF)
        // A report about an older policy says nothing about the current one: the child has not answered yet.
        if (state == null || state.policyId != policy.policyId) return model(ScoresPage.WAITING, noReport = true)
        return when {
            state.consent == "none" -> model(ScoresPage.WAITING)
            state.consent == "withdrawn" -> model(ScoresPage.WITHDRAWN)
            state.model == "unsupported_device" -> model(ScoresPage.MODEL_UNSUPPORTED)
            state.model == "mismatch" -> model(ScoresPage.MODEL_WRONG)
            state.model != "valid" -> model(ScoresPage.MODEL_MISSING)
            state.paused -> model(ScoresPage.PAUSED)
            state.stale -> model(ScoresPage.STALE)
            sorted.isEmpty() -> model(ScoresPage.EMPTY)
            else -> model(ScoresPage.LIST)
        }
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
    const val MODEL_SIZE_TEXT = "1,107,408,736 位元組（約 1.03 GB）"
    const val LIMITS_NOTICE = "分數來自一個只用虛構對話測試過的模型，可能高估，也可能漏掉真正的風險。它是一個訊號，不是結論。"
    const val NEXT_STEPS = "下一步：先和孩子好好談談。如果你認為孩子有即時危險，請致電 999（香港）。"
    const val NO_MESSAGES = "在這個模式下，你不會收到訊息內容。"
    fun unknownText(n: Int) = "因對話類型未能確認而沒有被分析的對話：$n 個。"
    fun staleBanner(receivedAt: Long) = "分數可能已過時：超過 72 小時沒有收到子女手機的報告。最後一次報告：${time(receivedAt)}。"

    data class StateCopy(val title: String, val body: String, val next: String)
    fun copy(m: ScoresPageModel): StateCopy = when (m.page) {
        ScoresPage.OFF -> StateCopy("僅分數模式未開啟",
            "開啟後，子女手機會在充電時於手機上分析一對一對話，你只會收到分數和不含文字的提醒，不會收到訊息內容。",
            "下一步：到「家庭」頁開啟僅分數模式。")
        ScoresPage.WAITING -> StateCopy("等待子女同意",
            if (m.noReportYet) "已送出設定，但還沒有收到子女手機的回覆。子女需要在自己的手機閱讀說明並同意，分析才會開始。你不能代替子女同意。"
            else "子女需要在自己的手機閱讀說明並同意，分析才會開始。你不能代替子女同意，在子女同意之前，子女手機維持原有的運作方式。",
            "下一步：請向孩子說明這個功能，並請孩子打開 IG Guard 查看。")
        ScoresPage.MODEL_MISSING -> StateCopy("子女手機尚未安裝模型",
            "分析需要一個模型檔（$MODEL_FILE），要在子女手機手動匯入。在安裝之前不會有新的分數，而在這個模式下你也不會收到訊息內容。",
            "下一步：在子女手機的「權限」頁匯入模型檔。")
        ScoresPage.MODEL_WRONG -> StateCopy("子女手機的模型檔不正確",
            "子女手機上的檔案不是指定的模型檔，所以不會被使用。只接受完全相同的檔案：$MODEL_FILE，$MODEL_SIZE_TEXT。",
            "下一步：在子女手機移除該檔案，再匯入正確的模型檔。")
        ScoresPage.MODEL_UNSUPPORTED -> StateCopy("子女手機不支援這個功能",
            "這個模式需要約 8 GB 記憶體的手機。子女手機的記憶體不足，不能在手機上分析。",
            "下一步：如不打算使用，可以到「家庭」頁關閉僅分數模式。")
        ScoresPage.PAUSED -> StateCopy("子女已暫停分析",
            "子女在 ${m.state?.pausedAt?.let(::time) ?: "較早前"} 暫停了分析。暫停期間不會有新的分數；下面只顯示暫停前收到的分數。子女可以隨時恢復，不需要你操作。",
            "下一步：如果想知道原因，可以用不責備的語氣問問孩子。")
        ScoresPage.WITHDRAWN -> StateCopy("子女已撤回同意",
            "子女已撤回同意，分析已停止，子女手機上儲存的訊息已刪除。你之前收到的分數仍會留在這裡，但不再更新。",
            "下一步：如要再次使用，請先在「家庭」頁關閉僅分數模式，再重新開啟；子女需要重新同意。")
        ScoresPage.EMPTY -> StateCopy("分析進行中，暫時還沒有分數",
            "分析會在子女手機充電時進行，需要一點時間。第一批分數出現後會顯示在這裡。" +
                (m.state?.lastRun?.let { "最近一次分析：${time(it)}，共 ${m.state.scoredCount} 個對話。" } ?: ""),
            "下一步：不需要操作，請稍後再看。")
        ScoresPage.ERROR -> StateCopy("未能讀取分數", "讀取這部手機上的分數時出錯。已收到的資料沒有被刪除。", "下一步：請稍後重試。")
        ScoresPage.STALE, ScoresPage.LIST -> StateCopy("對話風險分數", leadSentence(m), "")
    }

    /** Short state text for the family-tab switch. */
    fun switchStatus(policy: ScoresModePolicy?, state: ScoresModeState?, rows: List<RiskScoreRow>): String {
        val m = classify(policy, state, rows)
        return when (m.page) {
            ScoresPage.OFF -> "未開啟"
            ScoresPage.WAITING -> "已開啟 · 等待子女同意"
            ScoresPage.WITHDRAWN -> "已開啟 · 子女已撤回同意"
            ScoresPage.PAUSED -> "已開啟 · 子女已暫停"
            ScoresPage.MODEL_MISSING, ScoresPage.MODEL_WRONG, ScoresPage.MODEL_UNSUPPORTED -> "已開啟 · 子女手機的模型有問題"
            else -> "已開啟 · 子女已同意"
        }
    }
    fun canEnable(state: ScoresModeState?) = state?.model == "valid"
    fun enableBlockedWhy(state: ScoresModeState?): String = when {
        state == null -> "還沒有收到子女手機的回報，暫時不能開啟。"
        state.model == "unsupported_device" -> "子女手機的記憶體不足，不支援這個模式。"
        state.model == "mismatch" -> "子女手機上的模型檔不正確。請先在子女手機匯入模型檔 $MODEL_FILE。"
        else -> "請先在子女手機匯入模型檔 $MODEL_FILE，子女手機回報模型有效後才能開啟。"
    }

    const val PARENT_ON_TITLE = "改用「僅分數模式」？ / Switch to scores-only mode?"
    val PARENT_ON_POINTS = listOf(
        "子女手機會在充電時，於手機上分析一對一對話。你只會收到分數和不含文字的提醒。\nYour child's phone analyses one-to-one chats on the phone while charging. You receive scores and alerts without text.",
        "已經配對的家庭：新訊息的文字不會再傳到你的手機。\nFor an existing family, the text of new messages stops reaching your phone.",
        "你手機上現有的封存會保留，直到你自己刪除。\nThe existing archive stays on your phone until you delete it.",
        "在這個模式下，即使遇到緊急情況，你也不能閱讀訊息內容。\nIn this mode you cannot read messages, even in an emergency.",
        "子女要在自己的手機上同意才會開始，你不能代替子女同意；在此之前維持原有的運作方式。\nYour child must agree on their own phone. Until then nothing changes.",
        "本功能未獲 Meta 批准，也不代表已通過法律審核。\nNot approved by Meta, and not legally cleared."
    )
    const val PARENT_OFF_TITLE = "關閉「僅分數模式」？ / Turn off scores-only mode?"
    const val PARENT_OFF_BODY = "關閉後，子女手機會停止分析並刪除為分析儲存的訊息，原有的運作方式再次適用：新訊息會再傳到你的手機。已收到的分數仍保留。子女會看到這個改變。\nThe child's phone stops analysing and deletes the messages stored for it. The earlier mode applies again, so new messages reach your phone. Scores already received stay. Your child is told."
    const val SEND_FAILED = "未能送出設定。子女手機需要先回報模型有效，也請確認這部手機的儲存空間正常，然後重試。"
}

/** Child-side wording, bilingual in the style of the first-launch consent page. */
internal object ScoresChildCopy {
    const val TITLE = "AI 風險分析 / AI risk analysis"
    const val CONSENT_TITLE = "先了解，再決定\nAI risk analysis: read first, then decide"
    val CONSENT_POINTS = listOf(
        "分析什麼 / What is analysed" to "只分析本 App 已經讀取的 Instagram 一對一對話。每個對話只保留最新 60 則訊息，存放在這部手機上，最長 14 天。\nOnly one-to-one Instagram conversations this app already reads. The latest 60 messages per conversation are kept on this phone for at most 14 days.",
        "在哪裡、何時 / Where and when" to "分析在這部手機上進行，只在充電時運行。訊息不會為了分析而傳出這部手機。\nAnalysis runs on this phone, only while it is charging. Messages are not sent anywhere for analysis.",
        "家長會收到 / What your parent receives" to "你聊天對象的帳戶名稱、一個風險分數（或「需要檢視」）及時間；不含訊息文字的提醒；以及分析是開啟、暫停還是已撤回。\nThe account name of who you chat with, a risk score (or \"needs review\") and the time; alerts without message text; and whether analysis is on, paused or withdrawn.",
        "家長不會收到 / What your parent does not receive" to "家長不會收到你的訊息。在這個模式下，新訊息不再傳給家長。\nYour parent does not receive your messages. In this mode new messages are no longer sent to them.",
        "分數可能出錯 / Scores can be wrong" to "這個模型只用虛構的對話測試過，兩個方向都可能出錯：可能把普通對話評為有風險，也可能漏掉真正的問題。分數只是一個訊號。\nThe model was tested only on invented chats and can be wrong in both directions: it can score an ordinary chat as risky and miss a real problem. A score is only a signal.",
        "你的控制 / Your controls" to "你可以隨時暫停，家長會被告知。你也可以撤回同意：這部手機上儲存的訊息會被刪除，家長會被告知；已經傳給家長的分數仍留在家長手機。\nYou can pause at any time and your parent is told. You can withdraw: the messages stored on this phone are deleted and your parent is told. Scores already sent stay on your parent's phone.",
        "重要 / Please note" to "本功能未獲 Meta 批准，也不代表已通過法律審核。選擇「暫時不要」不會儲存或分析任何內容，原有的運作方式繼續。\nNot approved by Meta and not legally cleared. Choosing \"Not now\" stores and analyses nothing, and the earlier behaviour continues."
    )
    const val AGREE = "我同意 / I agree"
    const val NOT_NOW = "暫時不要 / Not now"
    const val CARD_TITLE = "需要你的決定 / Your decision is needed"
    const val CARD_BODY = "家長開啟了 AI 風險分析。你同意之前，不會儲存或分析任何內容。\nYour parent turned on AI risk analysis. Nothing is stored or analysed until you agree."
    const val CARD_ACTION = "閱讀並決定 / Read and decide"

    const val STATUS_RUNS = "在這部手機上、充電時運行。\nRuns on this phone while charging."
    const val STATUS_PARENT_SEES = "家長會看到你和誰聊天及風險分數，看不到你的訊息。\nYour parent sees who you chat with and a risk score. They do not see your messages."

    enum class StatusState(val label: String, val icon: String) {
        ON("開啟 / ON", "check-circle"), PAUSED("已暫停 / PAUSED", "clock"), WAITING("等待同意 / WAITING", "info"),
        WITHDRAWN("已撤回 / WITHDRAWN", "x"), MODEL_MISSING("缺少模型 / MODEL MISSING", "shield-warning"),
        NOT_AVAILABLE("此手機不適用 / NOT AVAILABLE", "lock-key"), NOT_RUNNING("未運作 / NOT RUNNING", "info")
    }
    fun statusState(s: RiskChildStatus): StatusState = when {
        s.modelState == "unsupported_device" -> StatusState.NOT_AVAILABLE
        s.consent == "withdrawn" -> StatusState.WITHDRAWN
        s.consent == "none" -> StatusState.WAITING
        s.paused -> StatusState.PAUSED
        s.modelState != "valid" -> StatusState.MODEL_MISSING
        s.active -> StatusState.ON
        else -> StatusState.NOT_RUNNING
    }
    fun lastRun(s: RiskChildStatus): String = s.lastRun?.let { "上次運行：${ScoresUi.time(it)} · ${s.scoredCount} 個對話\nLast run: ${ScoresUi.time(it)} · ${s.scoredCount} conversations" }
        ?: "尚未運行\nNot run yet"
    fun unknown(n: Int) = "未分析的對話（類型未明）：$n 個\nNot analysed (type unknown): $n"
    fun stateNote(st: StatusState): String? = when (st) {
        StatusState.WAITING -> "在你同意之前，不會儲存或分析任何內容。\nNothing is stored or analysed until you agree."
        StatusState.PAUSED -> "已暫停：不會儲存或分析新的訊息，家長已被告知。\nPaused: nothing new is stored or analysed. Your parent is told."
        StatusState.WITHDRAWN -> "你已撤回同意。這部手機上儲存的訊息已刪除，家長已被告知。\nYou withdrew. The messages stored on this phone were deleted and your parent is told."
        StatusState.MODEL_MISSING -> "需要匯入模型檔才會開始分析。請到「權限」頁匯入。\nImport the model file in Permissions before analysis can start."
        StatusState.NOT_AVAILABLE -> "這部手機記憶體不足，不能使用這個功能。\nThis phone does not have enough memory for this feature."
        StatusState.NOT_RUNNING -> "目前沒有運行，因為擷取已停止或尚未完成設定。\nNot running because capture is stopped or setup is incomplete."
        StatusState.ON -> null
    }
    const val PAUSE = "暫停 / Pause"
    const val RESUME = "恢復 / Resume"
    const val WHAT = "分析什麼？ / What is analysed?"
    const val WITHDRAW = "撤回同意 / Withdraw"
    const val WITHDRAW_TITLE = "撤回並刪除？ / Withdraw and delete?"
    const val WITHDRAW_BODY = "分析會停止，這部手機上儲存的訊息和尚未送出的分數會被刪除，家長會被告知。已傳給家長的分數仍留在家長手機。之後如要再用，需要重新同意。\nAnalysis stops. The messages stored on this phone and unsent scores are deleted, and your parent is told. Scores already sent stay on your parent's phone. To use it again you must agree again."
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

    // model import
    const val IMPORT_TITLE = "分析模型檔 / Analysis model file"
    const val IMPORT_ROW = "匯入分析模型"
    const val IMPORT_ROW_DESC = "由家長把模型檔放到這部手機，再在這裡選取。"
    const val IMPORT_CANCEL = "取消匯入 / Cancel import"
    const val REMOVE = "移除模型 / Remove model"
    const val REMOVE_TITLE = "移除模型？ / Remove the model?"
    const val REMOVE_BODY = "分析會停止，這部手機上為分析儲存的訊息會刪除。之後可以再匯入。\nAnalysis stops and the messages stored for it are deleted. You can import the file again later."
    const val UNSUPPORTED_TITLE = "這部手機不能使用 AI 風險分析\nNot available on this phone"
    const val UNSUPPORTED_BODY = "這個功能需要約 8 GB 記憶體，這部手機不夠。其他保護不受影響。\nThis feature needs about 8 GB of memory and this phone has less. Other protection is unaffected."
    const val INSTALLED = "模型已安裝（大小與簽名已核對）\nModel installed (size and signature checked)"
    const val MISMATCH = "模型檔不正確，不會被使用。請移除後再匯入。\nThe model file is not correct and will not be used. Remove it and import again."
    const val OK_DISMISS = "知道了 / OK"

    private const val GIB = 1073741824.0
    fun progress(copied: Long): String = "已複製 %.2f GB / %.2f GB\nCopied %.2f GB of %.2f GB".format(copied / GIB, RiskModelSpec.FILE_BYTES / GIB, copied / GIB, RiskModelSpec.FILE_BYTES / GIB)
    fun result(r: RiskImportResult?, cancelled: Boolean): Pair<String, String>? = when {
        cancelled -> "已取消匯入 / Import cancelled" to "沒有保存任何內容。\nNothing was saved."
        r == null -> null
        r == RiskImportResult.OK -> "模型已安裝 / Model installed" to "檔案大小與簽名都核對正確。分析要等你同意、家長開啟模式後才會開始。\nSize and signature match. Analysis starts only after you agree and your parent turns the mode on."
        r == RiskImportResult.WRONG_FILE -> "這不是指定的模型檔 / This is not the expected model" to "只接受完全相同的檔案：檔名 ${RiskModelSpec.FILE_NAME}，大小 ${ScoresUi.MODEL_SIZE_TEXT}。其他檔案（包括改名或不完整的檔案）會被拒絕，不會被保存。\nOnly the exact file is accepted: name ${RiskModelSpec.FILE_NAME}, size ${RiskModelSpec.FILE_BYTES} bytes (about 1.03 GB). Anything else, including renamed or incomplete copies, is rejected and not kept."
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
    /** Test seams. Production never changes them. */
    @Volatile var importer: (Context, InputStream, (Long) -> Unit) -> RiskImportResult = { c, i, p -> RiskLocal.importModel(c, i, p) }
    @Volatile var runner: (Runnable) -> Unit = { Thread(it, "Model import").start() }

    fun dismissResult() { result = null; wasCancelled = false }
    fun requestCancel() { cancelled = true }

    /** Opens [uri] and imports on a background thread. [onChange] is called from that thread whenever the display should update. */
    fun start(ctx: Context, uri: Uri, onChange: () -> Unit): Boolean {
        synchronized(this) {
            if (running) return false
            running = true; copied = 0L; cancelled = false; wasCancelled = false; result = null
        }
        val app = ctx.applicationContext
        runner(Runnable {
            var outcome: RiskImportResult
            try {
                val stream = app.contentResolver.openInputStream(uri) ?: throw IOException("no stream")
                // Cancellation surfaces as a read error: the engine deletes its partial file and keeps nothing.
                val guarded = object : InputStream() {
                    override fun read(): Int = stream.read().also { check() }
                    override fun read(b: ByteArray, off: Int, len: Int): Int { check(); return stream.read(b, off, len) }
                    override fun close() = stream.close()
                    private fun check() { if (cancelled) throw IOException("cancelled") }
                }
                var last = 0L
                outcome = guarded.use {
                    importer(app, it) { n -> copied = n; if (n - last >= 8L * 1024 * 1024) { last = n; onChange() } }
                }
            } catch (_: Exception) { outcome = RiskImportResult.IO_ERROR }
            synchronized(this) { wasCancelled = cancelled && outcome != RiskImportResult.OK; result = outcome; running = false }
            onChange()
        })
        return true
    }
}

/** One-time change-of-mode pages on the child's phone. Markers live in the device vault and are tied to the family. */
internal object ScoresNotices {
    private const val ON = "scores_ui_notice_on"
    private const val OFF = "scores_ui_notice_off"
    private fun prefs(ctx: Context) = LocalVault.preferences(ctx)
    private fun key(ctx: Context, policy: String) = GuardEngine.getChannelTopic(ctx) + "|" + policy

    /** "on" when the child agreed and has not seen the page, "off" when the parent switched the mode off after it was on, else null. */
    fun pending(ctx: Context): String? {
        if (!GuardEngine.isChildMode(ctx)) return null
        val id = RiskLocal.policyId(ctx)
        if (id.isEmpty()) return null
        if (RiskLocal.policyEnabled(ctx)) return if (RiskLocal.consentState(ctx) == "accepted" && prefs(ctx).getString(ON, "") != key(ctx, id)) "on" else null
        val on = prefs(ctx).getString(ON, "") ?: ""
        return if (on.startsWith(GuardEngine.getChannelTopic(ctx) + "|") && prefs(ctx).getString(OFF, "") != key(ctx, id)) "off" else null
    }
    fun markSeen(ctx: Context, which: String) {
        val id = RiskLocal.policyId(ctx)
        if (which == "on") prefs(ctx).edit().putString(ON, key(ctx, id)).commit()
        else prefs(ctx).edit().putString(OFF, key(ctx, id)).remove(ON).commit()
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
