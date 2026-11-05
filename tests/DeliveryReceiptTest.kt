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
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class DeliveryReceiptTest {
    private val ctx:Application get()=RuntimeEnvironment.getApplication()
    private lateinit var authority:FamilySecurity.SigningIdentity
    private val scope get()=GuardEngine.getChannelTopic(ctx)
    @Before fun reset(){
        ctx.deleteDatabase("family_evidence.db");ctx.getSharedPreferences("ig_guard_prefs",Context.MODE_PRIVATE).edit().clear().commit()
        GuardEngine.setFamilyToken(ctx,UUID.randomUUID().toString());GuardEngine.setRole(ctx,"child")
        authority=FamilySecurity.newSigningIdentity()
        ctx.getSharedPreferences("ig_guard_prefs",Context.MODE_PRIVATE).edit().putString("parent_command_public_key",authority.publicKey).commit()
    }
    private fun receipt(id:String,time:Long=System.currentTimeMillis(),signed:Boolean=true,channel:String=scope,outcome:String="stored"){
        val inner=JSONObject().put("type","alert_receipt").put("outcome",outcome).put("event_id",id).put("scope",channel).put("command_id",UUID.randomUUID().toString()).put("ts_ms",time)
        val payload=if(signed)FamilySecurity.signCommand(inner,authority.privateKey)else inner
        val line=JSONObject().put("event","message").put("id",UUID.randomUUID().toString()).put("message",GuardEngine.encryptPayload(GuardEngine.getOrCreateFamilyToken(ctx),payload.toString()))
        GuardEngine.processIncomingRelayLine(ctx,line.toString(),"child",null)
    }
    @Test fun relayAcceptanceKeepsCiphertextUntilAuthenticatedParentReceipt(){
        FamilyEvidence(ctx).use{db->db.enqueueBatch(scope,listOf("event-a:0" to "cipher"),waitForReceipt=true);db.relayAccepted(scope,"event-a:0",1000)}
        FamilyEvidence(ctx).use{assertEquals(1,it.pendingCount(scope));assertTrue(it.due(scope,1001).isEmpty())}
        receipt("event-a")
        FamilyEvidence(ctx).use{assertEquals(0,it.pendingCount(scope))}
    }
    @Test fun unsignedWrongFamilyAndWildcardReceiptsCannotErasePending(){
        FamilyEvidence(ctx).use{it.enqueue(scope,"event-a:0","cipher")}
        receipt("event-a",signed=false);receipt("event-a",channel="another-family");receipt("%")
        FamilyEvidence(ctx).use{assertEquals(1,it.pendingCount(scope))}
    }
    @Test fun validReceiptsMayArriveOutOfTimestampOrder(){
        FamilyEvidence(ctx).use{it.enqueue(scope,"event-a:0","a");it.enqueue(scope,"event-b:0","b")}
        val now=System.currentTimeMillis()
        receipt("event-b",now);receipt("event-a",now-1000)
        FamilyEvidence(ctx).use{assertEquals(0,it.pendingCount(scope))}
    }
    @Test fun discardedPartialEvidenceIsNotReportedAsSuccessfullyReceived(){
        FamilyEvidence(ctx).use{it.enqueue(scope,"event-a:0","cipher")}
        receipt("event-a",outcome="discarded")
        FamilyEvidence(ctx).use{assertEquals(0,it.pendingCount(scope))}
        assertFalse("A discarded partial record must not be reported as received",GuardEngine.cpLastHttpStatus.contains("確認接收"))
    }
    @Test fun malformedReceiptOutcomeCannotErasePendingEvidence(){
        FamilyEvidence(ctx).use{it.enqueue(scope,"event-a:0","cipher")}
        receipt("event-a",outcome="unknown")
        FamilyEvidence(ctx).use{assertEquals(1,it.pendingCount(scope))}
    }
    @Test fun rotationPreservesAwaitingReceiptStateAndEventIdentity(){
        val old=scope
        FamilyEvidence(ctx).use{it.enqueueBatch(old,listOf("event-a:0" to GuardEngine.encryptPayload(GuardEngine.getOrCreateFamilyToken(ctx),"{\"type\":\"alert\"}")),waitForReceipt=true)}
        GuardEngine.setFamilyToken(ctx,UUID.randomUUID().toString(),preserveFamilyHistory=true)
        FamilyEvidence(ctx).use{db->assertTrue(db.due(scope,Long.MAX_VALUE).single().waitForReceipt);assertEquals(0,db.pendingCount(old))}
        receipt("event-a")
        FamilyEvidence(ctx).use{assertEquals(0,it.pendingCount(scope))}
    }
}
