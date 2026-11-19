package app.family.igguard

import android.app.Application
import android.app.Notification
import android.app.Person
import android.service.notification.StatusBarNotification
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

/** F8: diagnostic/checkpoint logs never hold contact identifiers and are purged with the evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class DiagnosticPrivacyTest {
    private val ctx:Application get()=RuntimeEnvironment.getApplication()
    private val prefs get()=LocalVault.preferences(ctx)
    @Before fun reset(){prefs.edit().clear().commit();ctx.deleteDatabase("family_evidence.db")}
    private fun receive(inner:JSONObject,role:String) {
        val body=GuardEngine.encryptPayload(GuardEngine.getOrCreateFamilyToken(ctx),inner.toString())
        val line=JSONObject().put("event","message").put("id",UUID.randomUUID().toString()).put("message",body)
        GuardEngine.processIncomingRelayLine(ctx,line.toString(),role,null)
    }
    @Test fun parentReceivingAnAlertDoesNotLogTheSenderName() {
        GuardEngine.setRole(ctx,"parent")
        receive(JSONObject().put("type","alert").put("event_id",UUID.randomUUID().toString()).put("reason","stranger_message")
            .put("sender","contact_marker_one").put("text","hello").put("ts_ms",System.currentTimeMillis()),"parent")
        assertTrue(GuardEngine.getParentAlerts(ctx).isNotEmpty())
        assertFalse(GuardEngine.getCheckpointLogs(ctx).contains("contact_marker_one"))
        assertTrue("the receipt step is still diagnosable",GuardEngine.getCheckpointLogs(ctx).contains("CP4-RECV"))
    }
    @Test fun childIgnoringATrustedSenderDoesNotLogTheirName() {
        GuardEngine.setRole(ctx,"child");GuardEngine.setTrustedHandles(ctx,listOf("trusted_marker_two"))
        val c=Robolectric.buildService(IgNotificationListener::class.java).create()
        try {
            val me=Person.Builder().setName("child_user").build();val sender=Person.Builder().setName("trusted_marker_two").build()
            val notification=Notification.Builder(ctx,"test").setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setStyle(Notification.MessagingStyle(me).addMessage("hello there",System.currentTimeMillis(),sender)).build()
            c.get().onNotificationPosted(StatusBarNotification("com.instagram.android","com.instagram.android",1,null,0,0,0,notification,android.os.UserHandle.getUserHandleForUid(0),System.currentTimeMillis()))
            assertTrue("test must reach the trusted-sender branch",GuardEngine.getCheckpointLogs(ctx).contains("CP2-TRUSTED"))
            assertFalse(GuardEngine.getCheckpointLogs(ctx).contains("trusted_marker_two"))
        } finally {c.destroy()}
    }
    @Test fun detectedChildHandleIsNotWrittenToLogs() {
        GuardEngine.setRole(ctx,"child")
        GuardEngine.updateAutoDetectedChildHandle(ctx,"child_marker_three")
        assertEquals("child_marker_three",GuardEngine.getChildSelfHandle(ctx))
        assertFalse(GuardEngine.getCheckpointLogs(ctx).contains("child_marker_three"))
    }
    @Test fun logsWrittenByEarlierVersionsAreScrubbedOnReadAndWrite() {
        prefs.edit().putString("checkpoint_logs","[10:00:00] CP4-RECV: Decrypted stranger_message from old_contact_marker\n[09:59:00] CP2-TRUSTED: Ignored trusted/self/status: @old_trusted_marker\n[09:58:00] CP4-ID: Auto-synced Child IG ID from child phone: @old_child_marker\n[09:57:00] CP3-RELAY: Encrypted packet accepted").commit()
        val shown=GuardEngine.getCheckpointLogs(ctx)
        for(name in listOf("old_contact_marker","old_trusted_marker","old_child_marker"))assertFalse(name,shown.contains(name))
        assertTrue(shown.contains("CP3-RELAY"))
        GuardEngine.addCheckpointLog(ctx,"CP3-RELAY","later entry")
        for(name in listOf("old_contact_marker","old_trusted_marker","old_child_marker"))assertFalse(name,prefs.getString("checkpoint_logs","")!!.contains(name))
    }
    @Test fun deletingAllEvidencePurgesDiagnosticLogs() {
        GuardEngine.setRole(ctx,"parent")
        GuardEngine.recordParentAlert(ctx,JSONObject().put("type","alert").put("event_id","e1").put("sender","someone").put("text","x").put("reason","stranger_message").put("ts_ms",System.currentTimeMillis()),"relay:e1")
        prefs.edit().putString("checkpoint_logs","[10:00:00] CP4-RECV: Decrypted stranger_message from someone").commit()
        GuardEngine.clearParentAlerts(ctx)
        assertTrue(GuardEngine.getParentAlerts(ctx).isEmpty())
        assertEquals("",prefs.getString("checkpoint_logs",""))
    }
}
