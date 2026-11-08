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
class RelayConsumptionTest {
    private val ctx:Application get()=RuntimeEnvironment.getApplication()
    @Before fun reset(){ctx.deleteDatabase("family_evidence.db");ctx.getSharedPreferences("ig_guard_prefs",Context.MODE_PRIVATE).edit().clear().commit();GuardEngine.setFamilyToken(ctx,UUID.randomUUID().toString());GuardEngine.setRole(ctx,"parent")}
    private fun alert()=JSONObject().put("type","alert").put("event_id",UUID.randomUUID().toString()).put("reason","stranger_message").put("sender","contact").put("text","complete record").put("ts_ms",System.currentTimeMillis()).put("receipt_requested",true)
    private fun outer(payload:JSONObject)=JSONObject().put("event","message").put("id",UUID.randomUUID().toString()).put("message",GuardEngine.encryptPayload(GuardEngine.getOrCreateFamilyToken(ctx),payload.toString())).toString()

    @Test fun onlyDurablyStoredEvidenceIsConsumedAndGivenASignedReceipt() {
        val payload=alert()
        assertEquals(RelayConsumption.CONSUMED,GuardEngine.processIncomingRelayLine(ctx,outer(payload),"parent",null))
        FamilyEvidence(ctx).use { db ->
            assertNotNull(db.find(GuardEngine.getChannelTopic(ctx),payload.getString("event_id")))
            assertTrue(db.due(GuardEngine.getChannelTopic(ctx),Long.MAX_VALUE,"parent").any{it.kind=="receipt"})
        }
    }
    @Test fun aRealLocalStorageFailureLeavesCloudConsumptionRetryableWithoutReceipt() {
        FamilyEvidence(ctx).use{it.writableDatabase.execSQL("DROP TABLE evidence")}
        assertEquals(RelayConsumption.RETRY,GuardEngine.processIncomingRelayLine(ctx,outer(alert()),"parent",null))
        FamilyEvidence(ctx).use{assertTrue(it.due(GuardEngine.getChannelTopic(ctx),Long.MAX_VALUE,"parent").isEmpty())}
    }
    @Test fun wrongFamilyCiphertextIsRejectedAndDurablePartialFragmentsCanBeConsumed() {
        val bad=JSONObject().put("event","message").put("id","wrong-family").put("message",GuardEngine.encryptPayload(UUID.randomUUID().toString(),alert().toString()))
        assertEquals(RelayConsumption.REJECTED,GuardEngine.processIncomingRelayLine(ctx,bad.toString(),"parent",null))
        val long=alert().put("text","long message ".repeat(1000))
        val fragments=AlertFrames.split(long)
        assertTrue(fragments.size>1)
        assertEquals(RelayConsumption.CONSUMED,GuardEngine.processIncomingRelayLine(ctx,outer(fragments.first()),"parent",null))
        FamilyEvidence(ctx).use{assertEquals(1,it.incompleteCount(GuardEngine.getChannelTopic(ctx)))}
    }
    @Test fun longSignedParentCommandsReassembleWithoutBecomingEvidenceOrLosingAuthority() {
        val parent=FamilySecurity.newSigningIdentity()
        GuardEngine.setRole(ctx,"child")
        ctx.getSharedPreferences("ig_guard_prefs",Context.MODE_PRIVATE).edit().putString("parent_command_public_key",parent.publicKey).commit()
        val id=UUID.randomUUID().toString();val scope=GuardEngine.getChannelTopic(ctx)
        val contacts=(0..299).map{"friend_$it"}
        val command=JSONObject().put("type","sync_trusted").put("scope",scope).put("command_id",id).put("ts_ms",System.currentTimeMillis()).put("trusted_csv",contacts.joinToString(","))
        val wrapped=JSONObject().put("type","alert").put("event_id","control:$id").put("relay_control",true).put("envelope",FamilySecurity.signCommand(command,parent.privateKey))
        val parts=AlertFrames.split(wrapped);assertTrue(parts.size>1)
        for(part in parts.reversed())assertEquals(RelayConsumption.CONSUMED,GuardEngine.processIncomingRelayLine(ctx,outer(part),"child",null))
        assertEquals(contacts.toSet(),GuardEngine.getTrustedHandles(ctx))
        FamilyEvidence(ctx).use{assertEquals(0,it.incompleteCount(scope));assertTrue(it.messages(scope,EvidenceQuery(filter="any")).isEmpty())}
    }
}
