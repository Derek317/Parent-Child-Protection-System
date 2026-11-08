package app.family.igguard

import android.accessibilityservice.AccessibilityService
import android.app.Application
import android.content.Context
import android.graphics.Rect
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAccessibilityService
import java.net.*
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],shadows=[ScreenCaptureConnectionTest.RootShadow::class])
class ScreenCaptureConnectionTest {
    @Implements(AccessibilityService::class)
    class RootShadow:ShadowAccessibilityService(){
        @Implementation fun getRootInActiveWindow():AccessibilityNodeInfo? = root
        companion object { var root:AccessibilityNodeInfo?=null }
    }
    private val ctx:Application get()=RuntimeEnvironment.getApplication()
    private var original:ProxySelector?=null
    @Before fun reset(){
        ctx.deleteDatabase("family_evidence.db");val p=ctx.getSharedPreferences("ig_guard_prefs",Context.MODE_PRIVATE);p.edit().clear().commit()
        GuardEngine.setFamilyToken(ctx,UUID.randomUUID().toString());GuardEngine.setRole(ctx,"child")
        p.edit().putLong("next_heartbeat_at_ms",System.currentTimeMillis()+3_600_000L).commit()
        ctx.resources.displayMetrics.apply{widthPixels=1000;heightPixels=2000}
        original=ProxySelector.getDefault();ProxySelector.setDefault(object:ProxySelector(){
            override fun select(u:URI)=if(u.host=="ntfy.sh")listOf(Proxy(Proxy.Type.HTTP,InetSocketAddress("127.0.0.1",9)))else original?.select(u)?:listOf(Proxy.NO_PROXY)
            override fun connectFailed(u:URI,a:SocketAddress,e:IOException){}
        })
    }
    @After fun cleanup(){RootShadow.root=null;ProxySelector.setDefault(original)}
    private fun node(text:String,rect:Rect,editable:Boolean=false,hint:String=""):AccessibilityNodeInfo = AccessibilityNodeInfo.obtain().apply{
        this.text=text;setBoundsInScreen(rect);isVisibleToUser=true;isEditable=editable;hintText=hint
    }
    private fun captureRows(header:String,composerY:Int,feed:Boolean=false,body:String="hello from the other person",draft:String="",full:Boolean=false,hint:String="Message...",beforeFlush:(()->Unit)?=null):List<JSONObject>{
        if(full) {
            val id=UUID.randomUUID().toString()
            ctx.getSharedPreferences("ig_guard_prefs",Context.MODE_PRIVATE).edit()
                .putString("parent_command_public_key",FamilySecurity.newSigningIdentity().publicKey)
                .putString("capture_policy_id",id).putString("capture_local_ack_id",id).commit()
        }
        val root=node("",Rect(0,0,1000,2000))
        val nodes=mutableListOf(node(header,Rect(80,80,400,160)).apply{viewIdResourceName="com.instagram.android:id/action_bar_title"},
            node(body,Rect(30,composerY-160,420,composerY-100)),
            node(draft,Rect(30,composerY,970,composerY+80),editable=true,hint=hint))
        if(feed)nodes.addAll(listOf(node("Home",Rect(20,1800,100,1900)),node("Search",Rect(250,1800,350,1900))))
        nodes.forEach{shadowOf(root).addChild(it)};RootShadow.root=root
        val c=Robolectric.buildService(IgAccessibilityService::class.java).create()
        try {
            c.get().onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply{packageName="com.instagram.android"})
            beforeFlush?.invoke()
            shadowOf(Looper.getMainLooper()).idleFor(1500,TimeUnit.MILLISECONDS)
            return FamilyEvidence(ctx).use{db->db.due(GuardEngine.getChannelTopic(ctx),Long.MAX_VALUE).mapNotNull{
                GuardEngine.decryptPayload(GuardEngine.getOrCreateFamilyToken(ctx),it.wire)?.takeIf{it.optString("type") in ConversationCapture.contentTypes}
            }}
        }finally{c.destroy()}
    }
    private fun capture(header:String,composerY:Int,feed:Boolean=false)=captureRows(header,composerY,feed).map{it.getString("text")}
    @Test fun directMessageDisplayNameWithSpacesDoesNotDisableCapture(){
        assertEquals(listOf("hello from the other person"),capture("Alex Chan",1700))
    }
    @Test fun namedComposerAboveKeyboardDoesNotDisableDirectMessageCapture(){
        assertEquals(listOf("hello from the other person"),capture("outsider",1000))
    }
    @Test fun feedWithBottomNavigationStillDoesNotCaptureMessageLikeText(){
        assertTrue(capture("outsider",1700,feed=true).isEmpty())
    }
    @Test fun messageContainingReplyWordsDoesNotTurnUnsentDmDraftIntoStoryReply(){
        val rows=captureRows("friend",1700,body="回覆我",draft="private unsent DM draft",full=true)
        assertEquals(listOf("回覆我"),rows.map{it.getString("text")})
        assertTrue(rows.all{it.optString("source_context")=="visible_dm"})
    }
    @Test fun messageContainingProfileControlWordsDoesNotChangeChildIdentityOrStopCapture(){
        val rows=captureRows("friend",1700,body="Edit profile",full=true)
        assertEquals(listOf("Edit profile"),rows.map{it.getString("text")})
        assertFalse(GuardEngine.isChildSelfHandle(ctx,"friend"))
    }
    @Test fun actualStoryReplyComposerRetainsExistingDraftCheckWithExplicitLabel(){
        val rows=captureRows("Story",1700,body="",draft="actual story reply",full=true,hint="Reply...")
        assertEquals(listOf("actual story reply"),rows.map{it.getString("text")})
        assertEquals("comment_story_draft",rows.single().optString("source_context"))
    }
    @Test fun unnamedEditableComposerCannotMakeQuotedProfileTextEstablishChildIdentity(){
        captureRows("friend",1700,body="Edit profile",full=true,hint="")
        assertFalse(GuardEngine.isChildSelfHandle(ctx,"friend"))
    }
    @Test fun delayedReplyCannotCrossIntoAnotherAcknowledgedFamily(){
        val rows=captureRows("Story",1700,body="",draft="family A private reply",full=true,hint="Reply...",beforeFlush={
            GuardEngine.setFamilyToken(ctx,UUID.randomUUID().toString())
            val id=UUID.randomUUID().toString()
            ctx.getSharedPreferences("ig_guard_prefs",Context.MODE_PRIVATE).edit()
                .putString("parent_command_public_key",FamilySecurity.newSigningIdentity().publicKey)
                .putString("capture_policy_id",id).putString("capture_local_ack_id",id).commit()
        })
        assertTrue(rows.none{it.optString("text")=="family A private reply"})
    }
    @Test fun delayedReplyCannotFallBackToAnotherFamilysLegacyCapture(){
        val rows=captureRows("Story",1700,body="",draft="old family private reply",full=true,hint="Reply...",beforeFlush={
            GuardEngine.setFamilyToken(ctx,UUID.randomUUID().toString())
        })
        assertTrue(rows.none{it.optString("text")=="old family private reply"})
    }
}
