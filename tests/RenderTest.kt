package app.family.igguard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RenderTest {
    @Test fun realEmptyStateRendersBothThemesAndChildPermissions() {
        val ctx=RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("ig_guard_prefs",Context.MODE_PRIVATE).edit().clear().commit()
        GuardEngine.setRole(ctx,"unconfigured")
        render("role","native-role",390,844)
        for (bright in listOf(true,false)) {
            GuardEngine.setRole(ctx,"parent"); GuardEngine.setBrightTheme(ctx,bright)
            render("overview",if(bright) "native-parent-light" else "native-parent-dark",390,844)
        }
        GuardEngine.setBrightTheme(ctx,true);GuardEngine.setRole(ctx,"child")
        render("permissions","native-child-permissions",390,844)
        render("unlock","native-child-unlock",390,844)
    }
    private fun render(section: String, name: String, width: Int, height: Int) {
        val c=Robolectric.buildActivity(MainActivity::class.java).create().start().visible();val a=c.get()
        MainActivity::class.java.getDeclaredMethod("navigateTo",String::class.java).apply{isAccessible=true}.invoke(a,section)
        val shell=MainActivity::class.java.getDeclaredField("screenShell").apply{isAccessible=true}.get(a) as View
        shell.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));shell.layout(0,0,width,height)
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);shell.draw(Canvas(bitmap))
        assertEquals(width,bitmap.width)
        val out=File("build/test-screenshots/$name.png");out.parentFile?.mkdirs();out.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle();c.stop().destroy()
    }
}
