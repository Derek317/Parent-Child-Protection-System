package app.family.igguard

import android.app.Application
import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.*
import java.io.IOException
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class ConversationProtocolTest {
    private val ctx:Application get()=RuntimeEnvironment.getApplication()
    private lateinit var authority:FamilySecurity.SigningIdentity
    private var original:ProxySelector?=null
    private val prefs get()=ctx.getSharedPreferences("ig_guard_prefs",Context.MODE_PRIVATE)
    @Before fun reset(){
        ctx.deleteDatabase("family_evidence.db");prefs.edit().clear().commit()
        GuardEngine.setFamilyToken(ctx,UUID.randomUUID().toString());GuardEngine.setRole(ctx,"child")
        authority=FamilySecurity.newSigningIdentity();prefs.edit().putString("parent_command_public_key",authority.publicKey).commit()
        original=ProxySelector.getDefault()
        ProxySelector.setDefault(object:ProxySelector(){
            override fun select(u:URI)=if(u.host=="ntfy.sh")listOf(Proxy(Proxy.Type.HTTP,InetSocketAddress("127.0.0.1",9))) else original?.select(u)?:listOf(Proxy.NO_PROXY)
            override fun connectFailed(u:URI,a:SocketAddress,e:IOException){}
        })
    }
    @After fun cleanup(){ProxySelector.setDefault(original)}
    private fun receive(inner:JSONObject,signed:Boolean=true,callback:((JSONObject)->Unit)?=null){
        val payload=if(signed)FamilySecurity.signCommand(inner,authority.privateKey)else inner
        val line=JSONObject().put("event","message").put("id",UUID.randomUUID().toString()).put("message",GuardEngine.encryptPayload(GuardEngine.getOrCreateFamilyToken(ctx),payload.toString()))
        GuardEngine.processIncomingRelayLine(ctx,line.toString(),GuardEngine.getRole(ctx),callback)
    }
    private fun policy(id:String=UUID.randomUUID().toString())=JSONObject().put("type","capture_policy").put("schema_version",2).put("mode","full_visible_conversation").put("policy_id",id)
        .put("scope",GuardEngine.getChannelTopic(ctx)).put("command_id",UUID.randomUUID().toString()).put("ts_ms",System.currentTimeMillis())
    @Test fun realQueuedPolicyAndChildAcknowledgmentCompleteTheTwoWayHandshake(){
        GuardEngine.setRole(ctx,"parent")
        val publicKey=GuardEngine.relayPublicKey(ctx)
        val privateKey=prefs.getString("parent_signing_private","")!!
        GuardEngine.requestFullCapture(ctx)
        val id=GuardEngine.pendingCapturePolicy(ctx);val scope=GuardEngine.getChannelTopic(ctx)
        val token=GuardEngine.getOrCreateFamilyToken(ctx)
        val commands=FamilyEvidence(ctx).use{it.due(scope,Long.MAX_VALUE,"parent")}
        assertTrue(commands.isNotEmpty())
        GuardEngine.setRole(ctx,"child")
        prefs.edit().putString("parent_command_public_key",publicKey).remove("capture_policy_id").commit()
        fun deliver(frames:List<PendingEvidence>,role:String){for(frame in frames){
            val line=JSONObject().put("event","message").put("id",UUID.randomUUID().toString()).put("message",frame.wire)
            assertEquals(RelayConsumption.CONSUMED,GuardEngine.processIncomingRelayLine(ctx,line.toString(),role,null))
        }}
        deliver(commands,"child")
        assertEquals(id,GuardEngine.pendingCapturePolicy(ctx));assertFalse(GuardEngine.fullCaptureEnabled(ctx))
        assertTrue(GuardEngine.confirmFullCapture(ctx));assertTrue(GuardEngine.fullCaptureEnabled(ctx))
        val acks=FamilyEvidence(ctx).use{it.due(scope,Long.MAX_VALUE,"child")}
        assertTrue(acks.isNotEmpty())
        GuardEngine.setRole(ctx,"parent")
        prefs.edit().putString("parent_signing_public",publicKey).putString("parent_signing_private",privateKey).commit()
        deliver(acks,"parent")
        assertTrue(GuardEngine.parentFullCaptureConfirmed(ctx));assertEquals(token,GuardEngine.getOrCreateFamilyToken(ctx))
    }
    @Test fun signedPolicyRequiresLocalAcknowledgmentAndNewFamilyClearsIt(){
        assertFalse(GuardEngine.fullCaptureEnabled(ctx));assertFalse(GuardEngine.confirmFullCapture(ctx))
        val p=policy();receive(p)
        assertEquals(p.getString("policy_id"),GuardEngine.pendingCapturePolicy(ctx));assertFalse(GuardEngine.fullCaptureEnabled(ctx))
        assertTrue(GuardEngine.confirmFullCapture(ctx));assertTrue(GuardEngine.fullCaptureEnabled(ctx))
        GuardEngine.setFamilyToken(ctx,UUID.randomUUID().toString())
        assertFalse(GuardEngine.fullCaptureEnabled(ctx));assertEquals("",GuardEngine.pendingCapturePolicy(ctx))
    }
    @Test fun unsignedWrongScopeAndMalformedPolicyCannotBroadenCapture(){
        receive(policy(),signed=false);receive(policy().put("scope","other"));receive(policy().put("mode","anything"));receive(policy().put("schema_version",999))
        assertEquals("",GuardEngine.pendingCapturePolicy(ctx));assertFalse(GuardEngine.confirmFullCapture(ctx))
    }
    @Test fun ordinaryCapturedRecordsAreArchivedWithoutAlertCallbackOrChartCount(){
        GuardEngine.setRole(ctx,"parent")
        val id=UUID.randomUUID().toString();prefs.edit().putString("capture_policy_id",id).commit()
        fun msg(body:String)=ConversationCapture.record(CapturedText("friend",body,"incoming",author="friend",account="child"),System.currentTimeMillis(),true,false).put("policy_id",id)
        var notifications=0
        receive(msg("ordinary greeting"),signed=false,callback={notifications++})
        assertEquals(0,notifications);assertEquals(1,GuardEngine.getParentAlerts(ctx).size)
        assertEquals(0L,GuardEngine.getParentStatistics(ctx).dailyTotals().sum())
        receive(msg("send nudes"),signed=false,callback={notifications++})
        assertEquals(1,notifications);assertEquals(1L,GuardEngine.getParentStatistics(ctx).dailyTotals().sum())
        assertEquals(id,prefs.getString("capture_child_ack_id",""))
        receive(msg("forged expanded capture").put("policy_id",UUID.randomUUID().toString()),signed=false,callback={notifications++})
        assertEquals(2,GuardEngine.getParentAlerts(ctx).size)
    }
    @Test fun capturePreservesBothSidesAndSourceIdentityAcrossReplay(){
        receive(policy());assertTrue(GuardEngine.confirmFullCapture(ctx))
        val rows=listOf(CapturedText("friend","same words","incoming",author="friend",source="notification",messageTime=1000,sourceKey="one"),
            CapturedText("friend","same words","outgoing",author="child",source="notification",messageTime=1001,sourceKey="two"))
        assertEquals(2,GuardEngine.captureMessages(ctx,rows).size)
        assertTrue(GuardEngine.captureMessages(ctx,rows).isEmpty())
        val risk=GuardEngine.captureMessages(ctx,listOf(CapturedText("friend","send nudes","outgoing",author="child",sourceKey="three")))
        assertEquals(1,risk.size);assertFalse(GuardEngine.isFlaggedOffender(ctx,"friend"))
    }
    @Test fun realNotificationTextMatchingAStatusWordIsNotDiscarded(){
        receive(policy());assertTrue(GuardEngine.confirmFullCapture(ctx))
        val rows=GuardEngine.captureMessages(ctx,listOf(CapturedText("friend","Seen","incoming",author="friend",source="notification",sourceKey="real-message")))
        assertEquals(1,rows.size);assertEquals("Seen",rows.single().getString("text"))
    }
    @Test fun scrollingOlderMessagesUsesObservedThreadOrderWithoutChangingTimestamps(){
        receive(policy());assertTrue(GuardEngine.confirmFullCapture(ctx))
        val key=ConversationCapture.key("","friend")
        fun snapshot(vararg text:String)=GuardEngine.captureMessages(ctx,text.map{CapturedText("friend",it,"incoming")},key)
        val initial=snapshot("b","c")
        val older=snapshot("a","b")
        assertTrue(snapshot("a","b").isEmpty())
        val newer=snapshot("b","c","d")
        val all=initial+older+newer
        FamilyEvidence(ctx).use{db->
            for(r in all.reversed())db.append("f",r.getString("event_id"),r)
            assertEquals(listOf("a","b","c","d"),db.timeline("f",EvidenceQuery(conversationKey=key)).map{it.getString("text")})
            for(r in all)assertEquals(r.getLong("captured_at_ms"),db.find("f",r.getString("event_id"))!!.getLong("ts_ms"))
        }
    }
    @Test fun rollingNotificationBundleCommitsNewMessageAlongsidePreviouslySeenMessage(){
        receive(policy());assertTrue(GuardEngine.confirmFullCapture(ctx))
        val a=CapturedText("friend","earlier message","incoming",author="friend",source="notification",messageTime=1000,sourceKey="event-a")
        val b=a.copy(text="newer message",messageTime=1001,sourceKey="event-b")
        GuardEngine.captureMessages(ctx,listOf(a))
        val added=GuardEngine.captureMessages(ctx,listOf(a,b))
        assertEquals(listOf("newer message"),added.map{it.getString("text")})
        FamilyEvidence(ctx).use{db->
            val durable=db.due(GuardEngine.getChannelTopic(ctx),Long.MAX_VALUE).mapNotNull{
                GuardEngine.decryptPayload(GuardEngine.getOrCreateFamilyToken(ctx),it.wire)?.takeIf{it.optString("type")=="captured_message"}?.optString("text")
            }
            assertEquals(setOf("earlier message","newer message"),durable.toSet())
        }
    }
    @Test fun keyRotationKeepsAcknowledgedScopeAndDoesNotRequeueAnObservedSourceMessage(){
        receive(policy());assertTrue(GuardEngine.confirmFullCapture(ctx))
        val c=CapturedText("friend","same source","incoming",author="friend",source="notification",sourceKey="fixed-source",messageTime=1000)
        assertEquals(1,GuardEngine.captureMessages(ctx,listOf(c)).size)
        GuardEngine.setFamilyToken(ctx,UUID.randomUUID().toString(),preserveFamilyHistory=true)
        assertTrue(GuardEngine.fullCaptureEnabled(ctx))
        assertTrue(GuardEngine.captureMessages(ctx,listOf(c)).isEmpty())
    }
}
