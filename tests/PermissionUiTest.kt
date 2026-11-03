package app.family.igguard

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.widget.EditText
import android.widget.ScrollView
import android.view.View
import android.view.ViewGroup
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PermissionUiTest {
    private val ctx: Application get() = RuntimeEnvironment.getApplication()
    @Before fun reset() {
        ctx.getSharedPreferences("ig_guard_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        GuardEngine.pauseTamperUntilMs = 0
    }
    private fun invoke(a: MainActivity, name: String, value: String) = MainActivity::class.java.getDeclaredMethod(name,String::class.java).apply { isAccessible=true }.invoke(a,value)
    private fun field(a: MainActivity, name: String): Any = MainActivity::class.java.getDeclaredField(name).apply { isAccessible=true }.get(a)!!
    private fun all(v: View): List<View> = listOf(v) + if(v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()

    @Test fun childComponentsAreDisabledOutsideChildRole() {
        for (role in listOf("child","parent","child","unconfigured")) {
            GuardEngine.setRole(ctx,role)
            for (type in listOf(IgAccessibilityService::class.java,IgNotificationListener::class.java)) {
                assertEquals(if(role=="child") PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    ctx.packageManager.getComponentEnabledSetting(ComponentName(ctx,type)))
            }
        }
    }
    @Test fun settingsHelpDoesNotUnlockChild() {
        GuardEngine.setRole(ctx,"child")
        val controller=Robolectric.buildActivity(MainActivity::class.java).create()
        for (method in listOf("openDirectNotificationToggle","openAccessibilityToggle","openAppDetailsSettings")) {
            MainActivity::class.java.getDeclaredMethod(method).apply { isAccessible=true }.invoke(controller.get())
            assertFalse(GuardEngine.isTemporarilyUnlocked(ctx))
        }
        controller.destroy()
    }
    @Test fun navigationStartsAtTopAndKeepsDrafts() {
        GuardEngine.setRole(ctx,"parent")
        val controller=Robolectric.buildActivity(MainActivity::class.java).create().start().visible()
        val a=controller.get()
        invoke(a,"navigateTo","family")
        val root=field(a,"rootContainer") as ViewGroup
        all(root).filterIsInstance<EditText>().first().setText("friend_one, friend_two")
        val scroll=field(a,"scrollView") as ScrollView
        scroll.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(700,View.MeasureSpec.EXACTLY))
        scroll.layout(0,0,1080,700)
        scroll.scrollTo(0,300)
        invoke(a,"navigateTo","settings")
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(0,scroll.scrollY)
        invoke(a,"navigateTo","family")
        assertEquals("friend_one, friend_two",all(root).filterIsInstance<EditText>().first().text.toString())
        assertNotSame(scroll,(field(a,"fixedHeader") as View).parent)
        controller.stop().destroy()
    }
    @Test fun delayedChildResetCannotResetParentRole() {
        GuardEngine.setRole(ctx,"child")
        val token=GuardEngine.getOrCreateFamilyToken(ctx)
        val inner=JSONObject().put("type","remote_reset_child").put("ts_ms",System.currentTimeMillis())
        val line=JSONObject().put("event","message").put("id","stale-reset").put("message",GuardEngine.encryptPayload(token,inner.toString())).toString()
        GuardEngine.setRole(ctx,"parent")
        GuardEngine.processIncomingRelayLine(ctx,line,"child",null)
        assertEquals("parent",GuardEngine.getRole(ctx))
    }

    @Test fun familyChangeClearsCountersButKeyRotationPreservesThem() {
        GuardEngine.setRole(ctx,"parent")
        GuardEngine.setFamilyToken(ctx,"family-a")
        val alert=JSONObject().put("reason","tamper_attempt").put("ts_ms",System.currentTimeMillis())
        assertTrue(GuardEngine.recordParentAlert(ctx,alert,"offline-test"))
        GuardEngine.setFamilyToken(ctx,"family-a-rotated",preserveFamilyHistory=true)
        assertEquals(1L,GuardEngine.getParentStatistics(ctx).dailyTotals().sum())
        GuardEngine.clearParentAlerts(ctx)
        assertEquals(1L,GuardEngine.getParentStatistics(ctx).dailyTotals().sum())
        GuardEngine.setFamilyToken(ctx,"family-b")
        assertEquals(0L,GuardEngine.getParentStatistics(ctx).dailyTotals().sum())
        assertTrue(GuardEngine.getParentAlerts(ctx).isEmpty())
    }

    @Test fun oldSessionCannotModifyNewFamily() {
        GuardEngine.setRole(ctx,"child")
        GuardEngine.setFamilyToken(ctx,"family-a")
        val session=GuardEngine.captureRelaySession(ctx)
        val inner=JSONObject().put("type","remote_reset_child").put("ts_ms",System.currentTimeMillis())
        val line=JSONObject().put("event","message").put("id","old-family").put("message",GuardEngine.encryptPayload(session.second,inner.toString())).toString()
        GuardEngine.setFamilyToken(ctx,"family-b")
        GuardEngine.processIncomingRelayLine(ctx,line,"child",null,session.second,session.third)
        assertEquals("child",GuardEngine.getRole(ctx))
    }

    @Test fun acceptingSenderInvalidatesStaleAllowlistDraft() {
        GuardEngine.setRole(ctx,"parent")
        val controller=Robolectric.buildActivity(MainActivity::class.java).create()
        val a=controller.get()
        invoke(a,"navigateTo","family")
        val root=field(a,"rootContainer") as ViewGroup
        all(root).filterIsInstance<EditText>().first().setText("stale_unsaved")
        invoke(a,"addTrustedSender","new_friend")
        val displayed=all(root).filterIsInstance<EditText>().first().text.toString().split(", ").toSet()
        assertEquals(GuardEngine.getTrustedHandles(ctx),displayed)
        assertTrue("new_friend" in displayed)
        assertFalse("stale_unsaved" in displayed)
        controller.destroy()
    }

    @Test fun staleReportingDayRefreshesOverview() {
        GuardEngine.setRole(ctx,"parent")
        val controller=Robolectric.buildActivity(MainActivity::class.java).create()
        val a=controller.get()
        invoke(a,"navigateTo","overview")
        MainActivity::class.java.getDeclaredField("displayedChartDate").apply { isAccessible=true }.set(a,"1900-01-01")
        MainActivity::class.java.getDeclaredField("activityVisible").apply { isAccessible=true }.set(a,true)
        (field(a,"statusTicker") as Runnable).run()
        assertEquals(GuardEngine.getParentStatistics(ctx).dates.last(),field(a,"displayedChartDate"))
        controller.destroy()
    }

    @Test @Config(sdk = [34]) fun android14ShowsConditionalRestrictedSettingsHelp() {
        GuardEngine.setRole(ctx,"child")
        val controller=Robolectric.buildActivity(MainActivity::class.java).create()
        val a=controller.get()
        invoke(a,"navigateTo","permissions")
        val text=all(field(a,"rootContainer") as View).filterIsInstance<android.widget.TextView>().map { it.text.toString() }
        assertTrue(text.any { it.contains("受限設定協助") })
        assertTrue(text.any { it.contains("未開啟") })
        assertFalse(GuardEngine.isTemporarilyUnlocked(ctx))
        controller.destroy()
    }
}
