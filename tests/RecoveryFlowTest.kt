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
        ctx.getSharedPreferences("ig_guard_prefs",Context.MODE_PRIVATE).edit().clear().commit()
        GuardEngine.pauseTamperUntilMs=0
    }
    private fun all(v: View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList()
    private fun inviteIntent()=Intent(Intent.ACTION_VIEW,Uri.parse("igguard://pair?token=01234567-89ab-cdef-0123-456789abcdef&pw="+FamilySecurity.encode("ParentPassword928!")))
    @Test fun externalInviteRequiresExplicitAcceptanceAndIsConsumed() {
        GuardEngine.setRole(ctx,"parent")
        val c=Robolectric.buildActivity(MainActivity::class.java,inviteIntent()).create()
        val a=c.get();assertEquals("parent",GuardEngine.getRole(ctx))
        assertNull(a.intent.data)
        val d=ShadowDialog.getLatestDialog()
        all(d.window!!.decorView).filterIsInstance<Button>().first{it.text.toString()=="確認配對並切換為子女手機"}.performClick()
        assertEquals("child",GuardEngine.getRole(ctx));assertFalse(GuardEngine.isTemporarilyUnlocked(ctx))
        c.destroy()
    }
    @Test fun manualInviteRequiresBoundCodeConfirmationBeforeChangingFamily() {
        GuardEngine.setRole(ctx,"parent");GuardEngine.setPin(ctx,"ParentPrivate928!")
        val text=GuardEngine.buildPairingInviteText(ctx)
        val invitedToken=GuardEngine.getOrCreateFamilyToken(ctx)
        val parentCode=GuardEngine.getSafetyCode(ctx)
        GuardEngine.setRole(ctx,"unconfigured")
        GuardEngine.setFamilyToken(ctx,java.util.UUID.randomUUID().toString())
        val previous=GuardEngine.getOrCreateFamilyToken(ctx)
        val c=Robolectric.buildActivity(MainActivity::class.java).create();val a=c.get()
        MainActivity::class.java.getDeclaredMethod("navigateTo",String::class.java).apply{isAccessible=true}.invoke(a,"child_setup")
        all(a.window.decorView).filterIsInstance<android.widget.EditText>().first().setText(text)
        all(a.window.decorView).filterIsInstance<Button>().first{it.text.toString()=="連結家庭"}.performClick()
        assertEquals("unconfigured",GuardEngine.getRole(ctx))
        assertEquals(previous,GuardEngine.getOrCreateFamilyToken(ctx))
        val confirmation=ShadowDialog.getLatestDialog()
        assertTrue(all(confirmation.window!!.decorView).filterIsInstance<android.widget.TextView>().any{it.text.toString()==parentCode})
        all(confirmation.window!!.decorView).filterIsInstance<Button>().first{it.text.toString()=="確認配對並切換為子女手機"}.performClick()
        assertEquals("child",GuardEngine.getRole(ctx));assertEquals(invitedToken,GuardEngine.getOrCreateFamilyToken(ctx))
        c.destroy()
    }
    @Test fun unrelatedExternalUriCannotReplaceFamily() {
        GuardEngine.setRole(ctx,"parent");val token=GuardEngine.getOrCreateFamilyToken(ctx)
        val bad=inviteIntent().setData(Uri.parse(inviteIntent().dataString!!.replace("igguard://pair","https://other.invalid/pair")))
        val c=Robolectric.buildActivity(MainActivity::class.java,bad).create()
        assertEquals("parent",GuardEngine.getRole(ctx));assertEquals(token,GuardEngine.getOrCreateFamilyToken(ctx))
        c.destroy()
    }
    @Test fun keepaliveChecksOfflineDeadlineWhileStreamRemainsOpen() {
        GuardEngine.setRole(ctx,"parent")
        val now=System.currentTimeMillis()
        GuardEngine.recordChildHeartbeatOnParent(ctx,"old heartbeat",now-101*60_000L)
        val c=Robolectric.buildService(ParentAlertService::class.java).create()
        ParentAlertService::class.java.getDeclaredMethod("handleStreamLine",String::class.java,Triple::class.java).apply{isAccessible=true}
            .invoke(c.get(),"{\"event\":\"keepalive\"}",GuardEngine.captureRelaySession(ctx))
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
            assertTrue(GuardEngine.isFlaggedOffender(ctx,"outsider"))
            assertFalse(GuardEngine.isFlaggedOffender(ctx,"You"))
            assertTrue("The attempted alert must use only the local refusing proxy",selected.await(5,TimeUnit.SECONDS))
        } finally { c.destroy();ProxySelector.setDefault(original) }
    }
}
