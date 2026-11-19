package app.family.igguard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.widget.Button
import android.os.PersistableBundle
import java.util.UUID

/** UI authorization only. Background reception never consults this state. */
class ParentAccessSession {
    private var granted: Triple<String,String,Int>? = null
    fun authenticated(session: Triple<String,String,Int>) { require(session.first == "parent"); granted = session }
    fun isUnlocked(session: Triple<String,String,Int>): Boolean = session.first == "parent" && granted == session
    fun lock() { granted = null }
}

/** Clear only our own still-current copy; never overwrite a later clipboard item. */
object SensitiveClipboard {
    private const val MARKER = "igguard_clipboard_id"
    fun copy(ctx: Context, label: String, value: String) {
        val clipboard = ctx.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val id = UUID.randomUUID().toString()
        val clip = ClipData.newPlainText(label, value)
        clip.description.extras = PersistableBundle().apply {
            putString(MARKER, id)
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
        clipboard.setPrimaryClip(clip)
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                if (clipboard.primaryClipDescription?.extras?.getString(MARKER) == id) {
                    if (Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip()
                    else clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                }
            } catch (_: SecurityException) { /* Some Android versions forbid clipboard access in background. */ }
        }, 60_000L)
    }
}

const val OBSCURED_TOUCH_MESSAGE = "另一個 App 正在這個畫面上方顯示內容，為安全起見已忽略此次點按。請先關閉濾藍光、懸浮視窗或螢幕調色 App，再試一次。"

/** A security-critical confirmation: ignores touches while another window covers it and says why. */
class ObscuredAwareButton(ctx: Context, private val onFiltered: () -> Unit) : Button(ctx) {
    init { filterTouchesWhenObscured = true }
    override fun onFilterTouchEventForSecurity(event: MotionEvent): Boolean {
        val accepted = super.onFilterTouchEventForSecurity(event)
        if (!accepted && event.actionMasked == MotionEvent.ACTION_DOWN) onFiltered()
        return accepted
    }
}
