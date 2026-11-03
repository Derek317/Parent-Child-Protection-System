package app.family.igguard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RenderTest {
    @Test fun dashboardRendersBothThemesAndChildPermissions() {
        val ctx=RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("ig_guard_prefs",Context.MODE_PRIVATE).edit().clear().commit()
        for (bright in listOf(true,false)) {
            GuardEngine.setRole(ctx,"parent")
            GuardEngine.setBrightTheme(ctx,bright)
            val now=System.currentTimeMillis()
            for(i in 0..6) repeat(i+1) { n ->
                GuardEngine.recordParentAlert(ctx,JSONObject().put("reason",AlertStatistics.reasons[(i+n)%5]).put("ts_ms",now-i*86400000L),"render:$i:$n")
            }
            render("overview",if(bright) "parent-light" else "parent-dark",1200)
        }
        GuardEngine.setRole(ctx,"child")
        render("permissions","child-permissions",1000)
    }
    private fun render(section: String, name: String, height: Int) {
        val controller=Robolectric.buildActivity(MainActivity::class.java).create().start().visible()
        val a=controller.get()
        MainActivity::class.java.getDeclaredMethod("navigateTo",String::class.java).apply { isAccessible=true }.invoke(a,section)
        val shell=MainActivity::class.java.getDeclaredField("screenShell").apply { isAccessible=true }.get(a) as View
        shell.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
        shell.layout(0,0,360,height)
        val bitmap=Bitmap.createBitmap(360,height,Bitmap.Config.ARGB_8888)
        shell.draw(Canvas(bitmap))
        assertEquals(360,bitmap.width)
        val output=File("build/test-screenshots/$name.png")
        output.parentFile.mkdirs()
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
        controller.stop().destroy()
    }
}
