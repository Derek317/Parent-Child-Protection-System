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
        val completed=CountDownLatch(1);var delivered=true
            val text="廣東話🙂長訊息\n".repeat(180)
            GuardEngine.sendAlertFromChild(ctx,"stranger_message","unapproved",text,onComplete={success,_ -> delivered=success;completed.countDown()})
            assertTrue("The failed delivery callback must complete",completed.await(5,TimeUnit.SECONDS));assertFalse(delivered)
            val scope=GuardEngine.getChannelTopic(ctx);val token=GuardEngine.getOrCreateFamilyToken(ctx)
            FamilyEvidence(ctx).use{db->
                assertTrue("Upload failure must not discard the captured alert",db.pendingCount(scope)>0)
                val frames=db.due(scope,Long.MAX_VALUE).map{GuardEngine.decryptPayload(token,it.wire)!!}
                var received:JSONObject?=null
                for(frame in frames)received=if(frame.optString("type")=="alert")frame else db.fragment(scope,frame)?:received
                assertEquals(text,received!!.getString("text"))
            }
    }
    @Test fun concurrentAlertsEachCompleteOnceAndRemainQueuedWithoutAConfiguredRelay(){
        val done=CountDownLatch(32);val calls=java.util.concurrent.atomic.AtomicIntegerArray(32)
        val workers=java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            for(index in 0 until 32)workers.submit{GuardEngine.sendAlertFromChild(ctx,"stranger_message","contact_$index","message $index",onComplete={success,_->assertFalse(success);calls.incrementAndGet(index);done.countDown()},bypassDedupe=true)}
            assertTrue("Each captured alert needs its own completion",done.await(10,TimeUnit.SECONDS))
            for(index in 0 until 32)assertEquals(1,calls.get(index))
            FamilyEvidence(ctx).use{assertEquals(32,it.pendingCount(GuardEngine.getChannelTopic(ctx)))}
        } finally {workers.shutdownNow()}
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
