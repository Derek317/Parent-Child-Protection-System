package app.family.igguard

import android.app.Application
import android.content.Context
import android.os.Looper
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import java.util.concurrent.TimeUnit
import java.net.*
import java.io.IOException
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class ConversationUiTest {
    private val ctx:Application get()=RuntimeEnvironment.getApplication()
    private var original:ProxySelector?=null
    @Before fun reset(){
        ctx.deleteDatabase("family_evidence.db");ctx.getSharedPreferences("ig_guard_prefs",Context.MODE_PRIVATE).edit().clear().commit()
        GuardEngine.setFamilyToken(ctx,UUID.randomUUID().toString());GuardEngine.setRole(ctx,"parent")
        original=ProxySelector.getDefault();ProxySelector.setDefault(object:ProxySelector(){
            override fun select(u:URI)=if(u.host=="ntfy.sh")listOf(Proxy(Proxy.Type.HTTP,InetSocketAddress("127.0.0.1",9)))else original?.select(u)?:listOf(Proxy.NO_PROXY)
            override fun connectFailed(u:URI,a:SocketAddress,e:IOException){}
        })
    }
    @After fun cleanup(){ProxySelector.setDefault(original)}
    private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList()
    private fun views(a:MainActivity):List<View>{
        var result=all(a.window.decorView)
        for(i in 0..100){shadowOf(Looper.getMainLooper()).idleFor(50,TimeUnit.MILLISECONDS);Thread.sleep(10);result=all(a.window.decorView);if(result.none{it.tag=="evidence_loading"})break}
        return result
    }
    private fun nav(a:MainActivity,to:String="alerts")=MainActivity::class.java.getDeclaredMethod("navigateTo",String::class.java).apply{isAccessible=true}.invoke(a,to)
    private fun add(i:Int,partner:String="friend",direction:String="incoming",body:String="message $i") : JSONObject {
        val r=ConversationCapture.record(CapturedText(partner,body,direction,if(direction=="outgoing")"child" else partner,"child",messageTime=1000L+i),2000L+i,true,false).put("event_id","ui-$i")
        GuardEngine.recordParentAlert(ctx,r,"ui-$i");return r
    }
    @Test fun dmStyleListGroupsByPartnerAndThreadShowsBothAuthorsInTimeOrder(){
        add(3,direction="outgoing");add(1);add(2);add(4,"another")
        val c=Robolectric.buildActivity(MainActivity::class.java).create();val a=c.get();nav(a)
        val contactTags=views(a).mapNotNull{it.tag?.toString()}.filter{it.startsWith("evidence_contact_")}
        assertEquals(listOf("evidence_contact_another","evidence_contact_friend"),contactTags)
        views(a).single{it.tag=="evidence_contact_friend"}.performClick()
        assertEquals(listOf("evidence_ui-1","evidence_ui-2","evidence_ui-3"),views(a).mapNotNull{it.tag?.toString()}.filter{it.startsWith("evidence_ui-")})
        assertTrue(views(a).filterIsInstance<TextView>().any{it.text.toString().contains("子女")})
        assertTrue(views(a).filterIsInstance<TextView>().any{it.text.toString().contains("對方")})
        c.destroy()
    }
    @Test fun reviewQueueHidesOrdinaryContextButOpeningThreadRetainsIt(){
        add(1);add(2,body="send nudes")
        val c=Robolectric.buildActivity(MainActivity::class.java).create();val a=c.get();nav(a)
        views(a).filterIsInstance<Button>().single{it.text.toString()=="需要跟進"}.performClick()
        views(a).single{it.tag=="evidence_contact_friend"}.performClick()
        assertTrue(views(a).any{it.tag=="evidence_ui-1"});assertTrue(views(a).any{it.tag=="evidence_ui-2"})
        views(a).single{it.tag=="evidence_ui-2"}.performClick()
        val d=ShadowDialog.getLatestDialog()
        all(d.window!!.decorView).filterIsInstance<Button>().single{it.text.toString()=="標記已查看"}.performClick()
        FamilyEvidence(ctx).use{assertEquals("reviewed",it.find(GuardEngine.getChannelTopic(ctx),"ui-2")!!.getString("_review_state"))}
        c.destroy()
    }
    @Test fun childScopeNoticeExplainsBothDirectionsAndNeedsAnExplicitLocalAction(){
        GuardEngine.setRole(ctx,"child")
        val prefs=ctx.getSharedPreferences("ig_guard_prefs",Context.MODE_PRIVATE)
        prefs.edit().putString("parent_command_public_key",FamilySecurity.newSigningIdentity().publicKey).putString("capture_policy_id",UUID.randomUUID().toString()).commit()
        val c=Robolectric.buildActivity(MainActivity::class.java).create();val a=c.get();nav(a,"permissions")
        views(a).single{it.tag=="full_capture_setup"}.performClick()
        val d=ShadowDialog.getLatestDialog();val text=all(d.window!!.decorView).filterIsInstance<TextView>().joinToString(" "){it.text}
        assertTrue(text.contains("傳出"));assertTrue(text.contains("信任"));assertFalse(GuardEngine.fullCaptureEnabled(ctx))
        all(d.window!!.decorView).filterIsInstance<Button>().single{it.text.toString()=="我已了解，啟用雙向擷取"}.performClick()
        assertTrue(GuardEngine.fullCaptureEnabled(ctx));c.destroy()
    }
    @Test fun newArrivalsDoNotShiftOlderConversationPagesOrRepeatBoundaryMessages(){
        (1..65).forEach{add(it)}
        val c=Robolectric.buildActivity(MainActivity::class.java).create();val a=c.get();nav(a)
        try {
            fun rows()=views(a).mapNotNull{it.tag?.toString()}.filter{it.startsWith("evidence_ui-")}
            fun older()=views(a).filterIsInstance<Button>().single{it.text.toString()=="較早訊息"}.performClick()
            views(a).single{it.tag=="evidence_contact_friend"}.performClick()
            assertEquals((36..65).map{"evidence_ui-$it"},rows())
            older();assertEquals((6..35).map{"evidence_ui-$it"},rows())
            add(66)
            MainActivity::class.java.getDeclaredMethod("renderUi",Boolean::class.javaPrimitiveType).apply{isAccessible=true}.invoke(a,false)
            assertEquals((6..35).map{"evidence_ui-$it"},rows())
            older();assertEquals((1..5).map{"evidence_ui-$it"},rows())
        }finally{c.destroy()}
    }
    @Test fun burstRefreshesCoalesceInsteadOfStartingAQueryPerMessage(){
        add(1)
        val c=Robolectric.buildActivity(MainActivity::class.java).create();val a=c.get();nav(a);views(a)
        MainActivity::class.java.getDeclaredField("activityVisible").apply{isAccessible=true}.setBoolean(a,true)
        a.currentFocus?.clearFocus()
        val root=MainActivity::class.java.getDeclaredField("rootContainer").apply{isAccessible=true}
        val before=root.get(a)
        val receiver=MainActivity::class.java.getDeclaredField("refreshReceiver").apply{isAccessible=true}.get(a) as android.content.BroadcastReceiver
        repeat(100){receiver.onReceive(a,android.content.Intent(GuardEngine.ACTION_UI_REFRESH))}
        shadowOf(Looper.getMainLooper()).idleFor(100,TimeUnit.MILLISECONDS)
        assertSame(before,root.get(a))
        shadowOf(Looper.getMainLooper()).idleFor(200,TimeUnit.MILLISECONDS)
        val after=root.get(a);assertNotSame(before,after)
        shadowOf(Looper.getMainLooper()).idleFor(400,TimeUnit.MILLISECONDS);assertSame(after,root.get(a))
        c.destroy()
    }
    @Test fun olderThreadPagesKeepTheirBoundariesAfterActivityRecreation(){
        (1..65).forEach{add(it)}
        val c=Robolectric.buildActivity(MainActivity::class.java).create();val a=c.get();nav(a)
        views(a).single{it.tag=="evidence_contact_friend"}.performClick()
        repeat(2){views(a).filterIsInstance<Button>().single{it.text.toString()=="較早訊息"}.performClick();views(a)}
        val saved=Bundle();c.saveInstanceState(saved);c.destroy();add(66)
        val restored=Robolectric.buildActivity(MainActivity::class.java).create(saved);val b=restored.get();nav(b)
        try {
            fun rows()=views(b).mapNotNull{it.tag?.toString()}.filter{it.startsWith("evidence_ui-")}
            assertEquals((1..5).map{"evidence_ui-$it"},rows())
            views(b).filterIsInstance<Button>().single{it.text.toString()=="較新訊息"}.performClick()
            assertEquals((6..35).map{"evidence_ui-$it"},rows())
            views(b).filterIsInstance<Button>().single{it.text.toString()=="較新訊息"}.performClick()
            assertEquals((37..66).map{"evidence_ui-$it"},rows())
        }finally{restored.destroy()}
    }
    @Test fun displayNameCannotBeSilentlyTruncatedIntoATrustedUsername(){
        add(1,partner="Alex Chan",body="send nudes")
        val c=Robolectric.buildActivity(MainActivity::class.java).create();val a=c.get();nav(a)
        try {
            views(a).single{it.tag=="evidence_contact_alex chan"}.performClick()
            views(a).single{it.tag=="evidence_ui-1"}.performClick()
            val sheet=all(ShadowDialog.getLatestDialog().window!!.decorView)
            assertTrue(sheet.filterIsInstance<Button>().none{it.text.toString()=="加入信任好友並解除封鎖"})
            assertFalse(GuardEngine.getTrustedHandles(ctx).contains("alex"))
        }finally{c.destroy()}
    }
    @Test fun canonicalIncomingHandleRetainsTheExplicitTrustAction(){
        add(1,partner="actual_handle",body="send nudes")
        val c=Robolectric.buildActivity(MainActivity::class.java).create();val a=c.get();nav(a)
        try {
            views(a).single{it.tag=="evidence_contact_actual_handle"}.performClick()
            views(a).single{it.tag=="evidence_ui-1"}.performClick()
            all(ShadowDialog.getLatestDialog().window!!.decorView).filterIsInstance<Button>().single{it.text.toString()=="加入信任好友並解除封鎖"}.performClick()
            assertTrue(GuardEngine.getTrustedHandles(ctx).contains("actual_handle"))
        }finally{c.destroy()}
    }
}
