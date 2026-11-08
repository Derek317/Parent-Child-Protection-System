package app.family.igguard

import android.app.Application
import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.*
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class RotationTest {
    private val ctx:Application get()=RuntimeEnvironment.getApplication()
    @Before fun reset(){ctx.getSharedPreferences("ig_guard_prefs",Context.MODE_PRIVATE).edit().clear().commit();GuardEngine.setRole(ctx,"parent")}
    private fun rotateAndAwait():String {
        val done=CountDownLatch(1)
        val next=GuardEngine.rotateKeyAndNotifyChild(ctx,false){success->assertFalse(success);done.countDown()}
        assertTrue(done.await(5,TimeUnit.SECONDS))
        return next
    }
    @Test fun failedRotationPublishKeepsOriginalFamily(){
        val old=GuardEngine.getOrCreateFamilyToken(ctx)
        val proposed=rotateAndAwait()
        assertNotEquals(old,proposed);assertEquals(old,GuardEngine.getOrCreateFamilyToken(ctx))
    }
    @Test fun oldChannelReportDoesNotErasePendingRotationRetry(){
        val old=GuardEngine.getOrCreateFamilyToken(ctx)
        val proposed=rotateAndAwait()
        val alert=org.json.JSONObject().put("type","alert").put("reason","heartbeat_ok").put("ts_ms",System.currentTimeMillis())
        val line=org.json.JSONObject().put("event","message").put("id","old-channel-report")
            .put("message",GuardEngine.encryptPayload(old,alert.toString())).toString()
        GuardEngine.processIncomingRelayLine(ctx,line,"parent",null)
        assertEquals(proposed,rotateAndAwait())
    }
    @Test fun repeatedRotationRetriesSamePendingTransition(){
        assertEquals(rotateAndAwait(),rotateAndAwait())
    }
}
