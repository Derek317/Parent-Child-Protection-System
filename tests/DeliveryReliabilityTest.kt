package app.family.igguard

import android.app.Application
import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class DeliveryReliabilityTest {
    private val ctx:Application get()=RuntimeEnvironment.getApplication()
    @Before fun reset(){ctx.deleteDatabase("family_evidence.db");ctx.getSharedPreferences("ig_guard_prefs",Context.MODE_PRIVATE).edit().clear().commit();GuardEngine.setFamilyToken(ctx,java.util.UUID.randomUUID().toString());GuardEngine.setRole(ctx,"child")}
    @Test fun failedSendLeavesCompleteEncryptedTextDurableForRetry(){
        val original=ProxySelector.getDefault();val attempted=CountDownLatch(1);val completed=CountDownLatch(1);var delivered=true
        ProxySelector.setDefault(object:ProxySelector(){
            override fun select(uri:URI):List<Proxy>{
                if(uri.host=="ntfy.sh"){attempted.countDown();return listOf(Proxy(Proxy.Type.HTTP,InetSocketAddress("127.0.0.1",9)))}
                return original?.select(uri)?:listOf(Proxy.NO_PROXY)
            }
            override fun connectFailed(uri:URI,address:SocketAddress,error:IOException){}
        })
        try {
            val text="廣東話🙂長訊息\n".repeat(180)
            GuardEngine.sendAlertFromChild(ctx,"stranger_message","unapproved",text,onComplete={success,_ -> delivered=success;completed.countDown()})
            assertTrue("Only a local refusing proxy may receive this attempt",attempted.await(5,TimeUnit.SECONDS))
            assertTrue("The failed delivery callback must complete",completed.await(5,TimeUnit.SECONDS));assertFalse(delivered)
            val scope=GuardEngine.getChannelTopic(ctx);val token=GuardEngine.getOrCreateFamilyToken(ctx)
            FamilyEvidence(ctx).use{db->
                assertTrue("Upload failure must not discard the captured alert",db.pendingCount(scope)>0)
                val frames=db.due(scope,Long.MAX_VALUE).map{GuardEngine.decryptPayload(token,it.wire)!!}
                var received:JSONObject?=null
                for(frame in frames)received=if(frame.optString("type")=="alert")frame else db.fragment(scope,frame)?:received
                assertEquals(text,received!!.getString("text"))
            }
        } finally {ProxySelector.setDefault(original)}
    }
    @Test fun secondAlertCompletionWaitsForActiveWorkersAttempt(){
        val original=ProxySelector.getDefault();val entered=CountDownLatch(1);val release=CountDownLatch(1);val done=CountDownLatch(1)
        val calls=java.util.concurrent.atomic.AtomicInteger(0)
        ProxySelector.setDefault(object:ProxySelector(){
            override fun select(uri:URI):List<Proxy>{
                if(uri.host=="ntfy.sh"){entered.countDown();release.await(5,TimeUnit.SECONDS);return listOf(Proxy(Proxy.Type.HTTP,InetSocketAddress("127.0.0.1",9)))}
                return original?.select(uri)?:listOf(Proxy.NO_PROXY)
            }
            override fun connectFailed(uri:URI,address:SocketAddress,error:IOException){}
        })
        try {
            GuardEngine.sendAlertFromChild(ctx,"stranger_message","one","first")
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            GuardEngine.sendAlertFromChild(ctx,"stranger_message","two","second",onComplete={_,_->calls.incrementAndGet();done.countDown()})
            assertFalse("No outcome exists while the active attempt is blocked",done.await(100,TimeUnit.MILLISECONDS))
            release.countDown();assertTrue(done.await(5,TimeUnit.SECONDS));assertEquals(1,calls.get())
        } finally {release.countDown();ProxySelector.setDefault(original)}
    }
    @Test fun childPrivacyAndUnapprovedContactPolicyRemainIntact(){
        GuardEngine.recordChildTypedDmText("private outgoing conversation")
        assertNull(GuardEngine.evaluateMessage(ctx,"outsider","private outgoing conversation"))
        assertNull(GuardEngine.evaluateMessage(ctx,"You","hello"))
        assertEquals("stranger_message",GuardEngine.evaluateMessage(ctx,"unapproved","ordinary greeting"))
        GuardEngine.setTrustedHandles(ctx,listOf("trusted_friend"))
        assertEquals("sexual_or_grooming_content",GuardEngine.evaluateMessage(ctx,"trusted_friend","send nudes"))
    }
}
