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
    @Before fun reset(){ctx.deleteDatabase("family_evidence.db");ctx.getSharedPreferences("ig_guard_prefs",Context.MODE_PRIVATE).edit().clear().commit();GuardEngine.setRole(ctx,"parent")}
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
    @Test fun backgroundAcceptedRotationConvergesWithoutAnotherButtonPress(){
        val old=GuardEngine.getOrCreateFamilyToken(ctx);val scope=GuardEngine.getChannelTopic(ctx)
        val publicKey=GuardEngine.relayPublicKey(ctx);val next=rotateAndAwait()
        val frames=FamilyEvidence(ctx).use{it.due(scope,Long.MAX_VALUE,"parent")}
        for(frame in frames)GuardEngine.recordRelayAcceptance(ctx,scope,frame)
        assertEquals(next,GuardEngine.getOrCreateFamilyToken(ctx))
        FamilyEvidence(ctx).use{assertEquals(0,it.pendingCount(scope))}
        // The preserved original command also moves the child to that exact token.
        GuardEngine.setFamilyToken(ctx,old);GuardEngine.setRole(ctx,"child")
        ctx.getSharedPreferences("ig_guard_prefs",Context.MODE_PRIVATE).edit().putString("parent_command_public_key",publicKey).commit()
        for((index,frame) in frames.withIndex()){
            val line=org.json.JSONObject().put("event","message").put("id","rotation-child-$index").put("message",frame.wire).toString()
            assertEquals(RelayConsumption.CONSUMED,GuardEngine.processIncomingRelayLine(ctx,line,"child",null))
        }
        assertEquals(next,GuardEngine.getOrCreateFamilyToken(ctx))
    }
    @Test fun acceptedBackgroundRotationRecoversAfterTheFailedUiCallbackIsGone(){
        val old=GuardEngine.getOrCreateFamilyToken(ctx);val scope=GuardEngine.getChannelTopic(ctx)
        val next=rotateAndAwait()
        FamilyEvidence(ctx).use{db->
            val frames=db.due(scope,Long.MAX_VALUE,"parent")
            assertTrue(frames.isNotEmpty())
            for(frame in frames)db.relayAccepted(scope,frame.id,System.currentTimeMillis())
        }
        // Simulate process loss after relay acceptance, with no UI callback to complete the transition.
        GuardEngine.prepareRelayOutbox(ctx)
        assertEquals(next,GuardEngine.getOrCreateFamilyToken(ctx))
        FamilyEvidence(ctx).use{assertEquals(0,it.pendingCount(scope))}
        assertNotEquals(old,next)
    }
    @Test fun rotationRecoveryWaitsForEveryFrameAndFinishesAfterTokenCommit(){
        val old=GuardEngine.getOrCreateFamilyToken(ctx);val scope=GuardEngine.getChannelTopic(ctx)
        val next=rotateAndAwait()
        FamilyEvidence(ctx).use{db->
            val frame=db.due(scope,Long.MAX_VALUE,"parent").single()
            val extra=frame.id.substringBeforeLast(':')+":1"
            db.enqueue(scope,extra,frame.wire,"parent","control")
            db.relayAccepted(scope,frame.id,System.currentTimeMillis())
            GuardEngine.prepareRelayOutbox(ctx)
            assertEquals(old,GuardEngine.getOrCreateFamilyToken(ctx))
            db.relayAccepted(scope,extra,System.currentTimeMillis())
        }
        // Simulate the other crash boundary: committed new token, old accepted control still present.
        GuardEngine.setFamilyToken(ctx,next,preserveFamilyHistory=true)
        GuardEngine.prepareRelayOutbox(ctx)
        assertEquals(next,GuardEngine.getOrCreateFamilyToken(ctx))
        FamilyEvidence(ctx).use{assertEquals(0,it.pendingCount(scope))}
    }
    @Test fun repeatedRotationRetriesSamePendingTransition(){
        assertEquals(rotateAndAwait(),rotateAndAwait())
    }
}
