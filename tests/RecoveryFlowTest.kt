package app.family.igguard

import android.app.Application
import android.app.Notification
import android.app.Person
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.service.notification.StatusBarNotification
import android.widget.Button
import android.view.View
import android.view.ViewGroup
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.net.*
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class RecoveryFlowTest {
    private val ctx: Application get()=RuntimeEnvironment.getApplication()
    @Before fun reset() {
        LocalVault.preferences(ctx).edit().clear().commit()
        GuardEngine.pauseTamperUntilMs=0
    }
    private fun all(v: View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList()
    private val inviteKey by lazy { FamilySecurity.newSigningIdentity().publicKey }
    private fun inviteIntent()=Intent(Intent.ACTION_VIEW,Uri.parse("igguard://pair?version=43&token=01234567-89ab-cdef-0123-456789abcdef&pw="+
        FamilySecurity.encode(FamilySecurity.passwordVerifier("ParentPassword928!"))+"&parent="+inviteKey))
    @Test fun externalInviteIsRefusedAndConsumed() {
        GuardEngine.setRole(ctx,"unconfigured")
        val c=Robolectric.buildActivity(MainActivity::class.java,inviteIntent()).setup()
        val a=c.get();assertEquals("unconfigured",GuardEngine.getRole(ctx))
        assertNull(a.intent.data)
        val d=ShadowDialog.getLatestDialog()
        assertTrue(all(d.window!!.decorView).any{it.tag=="invite_refused"})
        assertTrue(all(d.window!!.decorView).filterIsInstance<Button>().none{it.text.toString()=="確認配對並切換為子女手機"})
        assertEquals("unconfigured",GuardEngine.getRole(ctx));assertFalse(GuardEngine.isTemporarilyUnlocked(ctx))
        c.destroy()
    }
    /** The child setup page has no paste box any more: the only way in is the QR scan, which asks for the camera there and only there. */
    @Test fun childSetupOffersOnlyTheQrScanAndAsksForTheCameraThere() {
        GuardEngine.setRole(ctx,"unconfigured")
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        MainActivity::class.java.getDeclaredMethod("navigateTo",String::class.java).apply{isAccessible=true}.invoke(a,"child_setup")
        assertTrue("no paste field",all(a.window.decorView).filterIsInstance<android.widget.EditText>().isEmpty())
        assertTrue(all(a.window.decorView).filterIsInstance<Button>().none{it.text.toString()=="連結家庭" || it.text.toString().contains("剪貼簿")})
        assertNull("not asked before the scan screen",org.robolectric.Shadows.shadowOf(a).lastRequestedPermission)
        all(a.window.decorView).first{it.tag=="scan_qr"}.performClick()
        assertEquals(android.Manifest.permission.CAMERA,org.robolectric.Shadows.shadowOf(a).lastRequestedPermission.requestedPermissions.single())
        assertEquals("unconfigured",GuardEngine.getRole(ctx))
        c.destroy()
    }
    @Test fun unrelatedExternalUriCannotReplaceFamily() {
        GuardEngine.setRole(ctx,"parent");val token=GuardEngine.getOrCreateFamilyToken(ctx)
        val bad=inviteIntent().setData(Uri.parse(inviteIntent().dataString!!.replace("igguard://pair","https://other.invalid/pair")))
        val c=Robolectric.buildActivity(MainActivity::class.java,bad).setup()
        assertEquals("parent",GuardEngine.getRole(ctx));assertEquals(token,GuardEngine.getOrCreateFamilyToken(ctx))
        c.destroy()
    }
    @Test fun watchdogChecksOfflineDeadlineWithoutNeedingIncomingTraffic() {
        GuardEngine.setRole(ctx,"parent")
        val now=System.currentTimeMillis()
        GuardEngine.recordChildHeartbeatOnParent(ctx,"old heartbeat",now-101*60_000L)
        val c=Robolectric.buildService(ParentAlertService::class.java).create()
        c.get().onStartCommand(null,0,1)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(GuardEngine.getParentAlerts(ctx).any{it.optString("reason")=="tamper_attempt"})
        c.destroy()
    }
    @Test fun structuredIncomingSenderCannotBeReplacedByBodyPrefix() {
        GuardEngine.setRole(ctx,"child")
        val original=ProxySelector.getDefault()
        val selected=CountDownLatch(1)
        ProxySelector.setDefault(object:ProxySelector(){
            override fun select(uri:URI):List<Proxy> {
                if(uri.host=="ntfy.sh") { selected.countDown();return listOf(Proxy(Proxy.Type.HTTP,InetSocketAddress("127.0.0.1",9))) }
                return original?.select(uri)?:listOf(Proxy.NO_PROXY)
            }
            override fun connectFailed(uri:URI,sa:SocketAddress,e:IOException) {}
        })
        val c=Robolectric.buildService(IgNotificationListener::class.java).create()
        try {
            val me=Person.Builder().setName("child_user").build()
            val sender=Person.Builder().setName("outsider").build()
            val notification=Notification.Builder(ctx,"test").setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setStyle(Notification.MessagingStyle(me).addMessage("You: send nudes",System.currentTimeMillis(),sender)).build()
            val posted=StatusBarNotification("com.instagram.android","com.instagram.android",1,null,0,0,0,notification,android.os.UserHandle.getUserHandleForUid(0),System.currentTimeMillis())
            c.get().onNotificationPosted(posted)
            val queued=FamilyEvidence(ctx).use{db->db.due(GuardEngine.getChannelTopic(ctx),Long.MAX_VALUE).mapNotNull{GuardEngine.decryptPayload(GuardEngine.getOrCreateFamilyToken(ctx),it.wire)}}
            assertEquals("outsider",queued.single{it.optString("reason")=="sexual_or_grooming_content"}.getString("sender"))
            assertTrue("a keyword match alone flags no one",GuardEngine.getFlaggedOffenders(ctx).isEmpty())
        } finally { c.destroy();ProxySelector.setDefault(original) }
    }
}
