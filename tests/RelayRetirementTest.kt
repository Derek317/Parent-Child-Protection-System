package app.family.igguard

import android.app.Application
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class RelayRetirementTest {
    private val ctx:Application get()=RuntimeEnvironment.getApplication()
    @Before fun reset(){LocalVault.preferences(ctx).edit().clear().commit();GuardEngine.setRole(ctx,"parent")}
    private fun retire(url:String):Boolean {
        val method=CloudRelay::class.java.getDeclaredMethod("retireRoom",android.content.Context::class.java,String::class.java,kotlin.jvm.functions.Function2::class.java)
        val latch=CountDownLatch(1);var accepted=false
        val callback:(Boolean,String)->Unit={ok,_->accepted=ok;latch.countDown()}
        method.invoke(CloudRelay,ctx,url,callback)
        assertTrue("retirement callback",latch.await(15,TimeUnit.SECONDS))
        return accepted
    }
    @Test fun erasureUsesAuthenticatedDeleteAndDoesNotPublishReplacementSecrets(){
        MockWebServer().use{server->
            server.enqueue(MockResponse().setResponseCode(204));server.start()
            val token=GuardEngine.getOrCreateFamilyToken(ctx)
            assertTrue(retire(server.url("/").toString().trimEnd('/')))
            val request=server.takeRequest(3,TimeUnit.SECONDS)!!
            assertEquals("DELETE",request.method);assertTrue(request.path!!.startsWith("/v1/rooms/"))
            assertTrue(request.getHeader("Authorization")!!.startsWith("IGGuard "))
            assertEquals(0L,request.bodySize)
            assertFalse(request.headers.toString().contains(token))
        }
    }
    @Test fun failedErasureDoesNotPretendSuccessOrChangeFamily(){
        MockWebServer().use{server->server.enqueue(MockResponse().setResponseCode(503));server.start()
            val token=GuardEngine.getOrCreateFamilyToken(ctx)
            assertFalse(retire(server.url("/").toString().trimEnd('/')))
            assertEquals(token,GuardEngine.getOrCreateFamilyToken(ctx))
        }
    }
}
