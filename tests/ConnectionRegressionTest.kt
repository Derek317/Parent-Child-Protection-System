package app.family.igguard

import android.app.Application
import android.app.Notification
import android.app.Person
import android.content.Context
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.*
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class ConnectionRegressionTest {
    private val ctx:Application get()=RuntimeEnvironment.getApplication()
    private val prefs get()=ctx.getSharedPreferences("ig_guard_prefs",Context.MODE_PRIVATE)
    private var original:ProxySelector?=null
    @Before fun reset(){
        ctx.deleteDatabase("family_evidence.db");prefs.edit().clear().commit()
        GuardEngine.setFamilyToken(ctx,UUID.randomUUID().toString());GuardEngine.setRole(ctx,"child")
        prefs.edit().putLong("next_heartbeat_at_ms",System.currentTimeMillis()+3_600_000L).commit()
        original=ProxySelector.getDefault()
        ProxySelector.setDefault(object:ProxySelector(){
            override fun select(u:URI)=if(u.host=="ntfy.sh")listOf(Proxy(Proxy.Type.HTTP,InetSocketAddress("127.0.0.1",9)))else original?.select(u)?:listOf(Proxy.NO_PROXY)
            override fun connectFailed(u:URI,a:SocketAddress,e:IOException){}
        })
    }
    @After fun cleanup(){ProxySelector.setDefault(original)}
    private fun posted(sender:String="outsider",title:String="Alex Chan",conversation:String="outsider"):StatusBarNotification {
        val style=Notification.MessagingStyle(Person.Builder().setName("You").build())
            .addMessage("hello from the other person",System.currentTimeMillis(),Person.Builder().setName(sender).build())
        val n=Notification.Builder(ctx,"test").setSmallIcon(android.R.drawable.ic_lock_idle_lock).setStyle(style).build()
        n.extras.putCharSequence(Notification.EXTRA_TITLE,title)
        n.extras.putCharSequence(Notification.EXTRA_CONVERSATION_TITLE,conversation)
        return StatusBarNotification("com.instagram.android","com.instagram.android",1,null,0,0,0,n,android.os.UserHandle.getUserHandleForUid(0),System.currentTimeMillis())
    }
    private fun queued():List<JSONObject> = FamilyEvidence(ctx).use{db->
        db.due(GuardEngine.getChannelTopic(ctx),Long.MAX_VALUE).mapNotNull{GuardEngine.decryptPayload(GuardEngine.getOrCreateFamilyToken(ctx),it.wire)}
    }
    private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList()
    @Test fun conversationHeaderCannotPoisonChildIdentityAndSuppressIncomingMessage(){
        val c=Robolectric.buildService(IgNotificationListener::class.java).create()
        try {
            c.get().onNotificationPosted(posted())
            assertFalse("A conversation title is the peer, not the child",GuardEngine.isChildSelfHandle(ctx,"outsider"))
            assertTrue(queued().any{it.optString("sender")=="outsider" && it.optString("reason")=="stranger_message"})
        }finally{c.destroy()}
    }
    @Test fun oldUnverifiedAutoDetectedIdentityDoesNotKeepSuppressingPeerAfterUpgrade(){
        prefs.edit().putString("child_self_handle","outsider").commit()
        val c=Robolectric.buildService(IgNotificationListener::class.java).create()
        try {
            c.get().onNotificationPosted(posted(conversation="Alex Chan"))
            assertFalse(GuardEngine.isChildSelfHandle(ctx,"outsider"))
            assertTrue(queued().any{it.optString("sender")=="outsider"})
        }finally{c.destroy()}
    }
    @Test fun fullCaptureAlsoUsesRecipientPrefixWithoutTreatingPeerAsSelf(){
        val id=UUID.randomUUID().toString()
        prefs.edit().putString("parent_command_public_key",FamilySecurity.newSigningIdentity().publicKey)
            .putString("capture_policy_id",id).putString("capture_local_ack_id",id).commit()
        val c=Robolectric.buildService(IgNotificationListener::class.java).create()
        try {
            c.get().onNotificationPosted(posted(title="[child_account] Alex Chan"))
            assertEquals("child_account",GuardEngine.getChildSelfHandle(ctx))
            assertEquals("incoming",queued().single{it.optString("type")=="captured_message"}.getString("direction"))
        }finally{c.destroy()}
    }
    @Test fun manualConnectionButtonQueuesExplicitTestInsteadOfOnlyRoutineHeartbeat(){
        val c=Robolectric.buildActivity(MainActivity::class.java).create();val a=c.get()
        try {
            MainActivity::class.java.getDeclaredMethod("navigateTo",String::class.java).apply{isAccessible=true}.invoke(a,"permissions")
            all(a.window.decorView).filterIsInstance<Button>().single{it.text.toString()=="立即發送測試及心跳回報"}.performClick()
            assertTrue("The parent needs a distinct test confirmation",queued().any{it.optString("reason")=="test_alert"})
        }finally{c.destroy()}
    }
    @Test fun encryptedManualTestUpdatesParentConnectionWithoutIncreasingRiskCounts(){
        GuardEngine.setRole(ctx,"parent")
        val record=JSONObject().put("type","alert").put("event_id",UUID.randomUUID().toString())
            .put("reason","test_alert").put("sender","子女手機連線測試").put("text","synthetic test confirmation").put("ts_ms",System.currentTimeMillis())
        val line=JSONObject().put("event","message").put("id",UUID.randomUUID().toString())
            .put("message",GuardEngine.encryptPayload(GuardEngine.getOrCreateFamilyToken(ctx),record.toString()))
        var confirmations=0
        GuardEngine.processIncomingRelayLine(ctx,line.toString(),"parent",{confirmations++})
        assertTrue("A received test is also proof the child can reach this parent",GuardEngine.hasChildHeartbeat(ctx))
        assertEquals(1,confirmations)
        assertEquals(0,GuardEngine.getParentStatistics(ctx).dailyTotals().sum())
        FamilyEvidence(ctx).use { db ->
            assertEquals(1,db.messages(GuardEngine.getChannelTopic(ctx),EvidenceQuery(filter="any",section="device")).size)
            assertTrue(db.conversations(GuardEngine.getChannelTopic(ctx),EvidenceQuery(section="review")).isEmpty())
        }
    }
    @Test fun backgroundControlPollCannotOverwriteTheLastUploadResult(){
        GuardEngine.cpLastHttpStatus="中繼站已接收 · 等待家長手機確認"
        GuardEngine.pollChannelOnce(ctx)
        assertEquals("中繼站已接收 · 等待家長手機確認",GuardEngine.cpLastHttpStatus)
    }
    @Test fun failedUploadRecordsADiagnosticWithoutExposingMessageText(){
        val done=CountDownLatch(1)
        GuardEngine.sendAlertFromChild(ctx,"test_alert","test device","private synthetic body",onComplete={_,_->done.countDown()})
        assertTrue(done.await(5,TimeUnit.SECONDS))
        val diagnostics=GuardEngine.getCheckpointLogs(ctx)
        assertTrue("A failed publish needs its own transport checkpoint",diagnostics.contains("CP3-ERR"))
        assertFalse(diagnostics.contains("private synthetic body"))
    }
}
