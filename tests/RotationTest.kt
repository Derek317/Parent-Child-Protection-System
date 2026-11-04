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
    private fun refusingRelay(requests:Int, test:(CountDownLatch)->Unit) {
        val original=ProxySelector.getDefault();val selected=CountDownLatch(requests)
        ProxySelector.setDefault(object:ProxySelector(){
            override fun select(uri:URI):List<Proxy> {
                if(uri.host=="ntfy.sh"){selected.countDown();return listOf(Proxy(Proxy.Type.HTTP,InetSocketAddress("127.0.0.1",9)))}
                return original?.select(uri)?:listOf(Proxy.NO_PROXY)
            }
            override fun connectFailed(uri:URI,sa:SocketAddress,e:IOException){}
        })
        try{test(selected)}finally{ProxySelector.setDefault(original)}
    }
    @Test fun failedRotationPublishKeepsOriginalFamily()=refusingRelay(1){selected->
        val old=GuardEngine.getOrCreateFamilyToken(ctx)
        val proposed=GuardEngine.rotateKeyAndNotifyChild(ctx,false)
        assertTrue(selected.await(5,TimeUnit.SECONDS))
        assertNotEquals(old,proposed)
        assertEquals(old,GuardEngine.getOrCreateFamilyToken(ctx))
    }
    @Test fun oldChannelReportDoesNotErasePendingRotationRetry()=refusingRelay(2){selected->
        val old=GuardEngine.getOrCreateFamilyToken(ctx)
        val proposed=GuardEngine.rotateKeyAndNotifyChild(ctx,false)
        val alert=org.json.JSONObject().put("type","alert").put("reason","heartbeat_ok").put("ts_ms",System.currentTimeMillis())
        val line=org.json.JSONObject().put("event","message").put("id","old-channel-report")
            .put("message",GuardEngine.encryptPayload(old,alert.toString())).toString()
        GuardEngine.processIncomingRelayLine(ctx,line,"parent",null)
        val retry=GuardEngine.rotateKeyAndNotifyChild(ctx,false)
        assertTrue(selected.await(5,TimeUnit.SECONDS))
        assertEquals(proposed,retry)
    }
    @Test fun repeatedRotationRetriesSamePendingTransition()=refusingRelay(2){selected->
        val proposed=GuardEngine.rotateKeyAndNotifyChild(ctx,false)
        val retry=GuardEngine.rotateKeyAndNotifyChild(ctx,false)
        assertTrue(selected.await(5,TimeUnit.SECONDS))
        assertEquals(proposed,retry)
    }
}
