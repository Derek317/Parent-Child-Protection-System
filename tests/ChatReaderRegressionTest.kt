package app.family.igguard

import android.accessibilityservice.AccessibilityService
import android.app.Application
import android.content.Context
import android.graphics.Rect
import android.os.Looper
import java.util.concurrent.TimeUnit
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],shadows=[ChatReaderRegressionTest.RootShadow::class])
class ChatReaderRegressionTest {
    @Implements(AccessibilityService::class)
    class RootShadow:ShadowAccessibilityService(){
        @Implementation fun getRootInActiveWindow():AccessibilityNodeInfo? = root
        companion object { var root:AccessibilityNodeInfo?=null }
    }
    private val ctx:Application get()=RuntimeEnvironment.getApplication()
    private var original:ProxySelector?=null
    @Before fun reset(){
        ctx.deleteDatabase("family_evidence.db")
        val p=ctx.getSharedPreferences("ig_guard_prefs",Context.MODE_PRIVATE);p.edit().clear().commit()
        GuardEngine.setFamilyToken(ctx,UUID.randomUUID().toString());GuardEngine.setRole(ctx,"child")
        val id=UUID.randomUUID().toString()
        p.edit().putLong("next_heartbeat_at_ms",System.currentTimeMillis()+3_600_000L)
            .putString("parent_command_public_key",FamilySecurity.newSigningIdentity().publicKey)
            .putString("capture_policy_id",id).putString("capture_local_ack_id",id).commit()
        ctx.resources.displayMetrics.apply{widthPixels=1000;heightPixels=2000}
        original=ProxySelector.getDefault();ProxySelector.setDefault(object:ProxySelector(){
            override fun select(u:URI)=if(u.host=="ntfy.sh")listOf(Proxy(Proxy.Type.HTTP,InetSocketAddress("127.0.0.1",9)))else original?.select(u)?:listOf(Proxy.NO_PROXY)
            override fun connectFailed(u:URI,a:SocketAddress,e:IOException){}
        })
    }
    @After fun cleanup(){RootShadow.root=null;ProxySelector.setDefault(original)}
    private fun node(text:String="",x:Int=30,y:Int=400,r:Int=450,b:Int=y+60,id:String="",description:String="",editable:Boolean=false):AccessibilityNodeInfo = AccessibilityNodeInfo.obtain().apply {
        this.text=text;contentDescription=description;setBoundsInScreen(Rect(x,y,r,b));isVisibleToUser=true
        isEditable=editable;viewIdResourceName="com.instagram.android:id/$id"
        className=if(editable)"android.widget.EditText" else "android.widget.TextView"
    }
    private fun screen(peer:String="Alice",hint:String="Message...",offset:Boolean=false,body:List<AccessibilityNodeInfo> = listOf(node("hello")),composerY:Int=1700,composerId:String="message_composer",composerEditable:Boolean=true,headerButton:Boolean=false,draftChild:Boolean=false):AccessibilityNodeInfo {
        val root=node(x=0,y=0,r=1000,b=2000).apply{packageName="com.instagram.android"}
        fun add(n:AccessibilityNodeInfo){if(offset)funShift(n,100,200);shadowOf(root).addChild(n)}
        if(offset)root.setBoundsInScreen(Rect(100,200,1100,2200))
        add(node("返回",16,70,80,140,"action_bar_button_back").apply{className="android.widget.ImageButton";isClickable=true})
        if(peer.isNotEmpty())add(node(peer,100,85,650,150,"action_bar_title").apply{if(headerButton)className="android.widget.Button"})
        body.forEach(::add)
        add(node(x=30,y=composerY,r=970,b=composerY+120,id=composerId,editable=composerEditable).apply{hintText=hint;if(draftChild)shadowOf(this).addChild(node("private unsent draft",y=composerY+20))})
        return root
    }
    private fun funShift(n:AccessibilityNodeInfo,dx:Int,dy:Int){
        val bounds=Rect();n.getBoundsInScreen(bounds);bounds.offset(dx,dy);n.setBoundsInScreen(bounds)
        for(i in 0 until n.childCount)funShift(n.getChild(i),dx,dy)
    }
    private fun scan(root:AccessibilityNodeInfo){
        RootShadow.root=root
        val c=Robolectric.buildService(IgAccessibilityService::class.java).create()
        try{c.get().onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply{packageName="com.instagram.android"})}finally{c.destroy()}
    }
    private fun rows():List<JSONObject> = FamilyEvidence(ctx).use { db->db.due(GuardEngine.getChannelTopic(ctx),Long.MAX_VALUE).mapNotNull {
        GuardEngine.decryptPayload(GuardEngine.getOrCreateFamilyToken(ctx),it.wire)?.takeIf{it.optString("type") in ConversationCapture.contentTypes}
    }}
    @Test fun accountHeaderCannotReplaceThePeerInTheBackButtonRow(){
        GuardEngine.setChildSelfHandle(ctx,"child_account")
        val root=screen(peer="")
        shadowOf(root).addChild(node("child_account",100,0,600,55,"action_bar_title"))
        shadowOf(root).addChild(node("Alice",100,85,650,150,"peer_label").apply{isHeading=true})
        scan(root)
        assertEquals(listOf("Alice"),rows().map{it.optString("conversation_label")})
    }
    @Test fun accountHeaderAndUnknownSelfStillPreferTheActualChatToolbar(){
        val root=screen("Alice")
        shadowOf(root).addChild(node("child_account",100,0,600,55,"action_bar_title"))
        scan(root)
        assertEquals(listOf("Alice"),rows().map{it.optString("conversation_label")})
    }
    @Test fun clickablePeerTitleAndRaisedComposerStillCaptureBothDirections(){
        val root=screen(body=listOf(node("incoming",y=220),node("outgoing",600,400,970,460,"outgoing_message")),composerY=600,headerButton=true,hint="發送訊息..")
        scan(root)
        assertEquals(listOf("incoming","outgoing"),rows().map{it.optString("direction")})
    }
    @Test fun traditionalChineseComposerWithoutAResourceIdIsRecognized(){
        val root=screen(hint="訊息⋯",composerId="")
        scan(root)
        assertEquals(listOf("hello"),rows().map{it.optString("text")})
    }
    @Test fun semanticComposerWithoutEditableFlagDoesNotExposeItsDraftChildren(){
        val root=screen(composerEditable=false,draftChild=true)
        scan(root)
        assertEquals(listOf("hello"),rows().map{it.optString("text")})
    }
    @Test fun coalescedContentChangeEventuallyCapturesTheNewLoadedMessage(){
        RootShadow.root=screen(body=emptyList())
        val c=Robolectric.buildService(IgAccessibilityService::class.java).create()
        try{
            fun event(type:Int)=c.get().onAccessibilityEvent(AccessibilityEvent.obtain(type).apply{packageName="com.instagram.android"})
            event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
            RootShadow.root=screen(body=listOf(node("last update in the burst")))
            event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
            shadowOf(Looper.getMainLooper()).idleFor(600,TimeUnit.MILLISECONDS)
            assertEquals(listOf("last update in the burst"),rows().map{it.optString("text")})
        }finally{c.destroy()}
    }
    @Test fun aScrollEventCapturesNewlyExposedMessagesAfterTheBurst(){
        RootShadow.root=screen(body=listOf(node("newer",y=600)))
        val c=Robolectric.buildService(IgAccessibilityService::class.java).create()
        try{
            fun event(type:Int)=c.get().onAccessibilityEvent(AccessibilityEvent.obtain(type).apply{packageName="com.instagram.android"})
            event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
            RootShadow.root=screen(body=listOf(node("older",y=400),node("newer",y=600)))
            event(AccessibilityEvent.TYPE_VIEW_SCROLLED)
            shadowOf(Looper.getMainLooper()).idleFor(600,TimeUnit.MILLISECONDS)
            val captured=rows();assertEquals(setOf("older","newer"),captured.map{it.optString("text")}.toSet())
            FamilyEvidence(ctx).use{db->
                for(row in captured)db.append("parent-test",row.getString("event_id"),row)
                val key=captured.first().getString("conversation_key")
                assertEquals(listOf("older","newer"),db.timeline("parent-test",EvidenceQuery(conversationKey=key)).map{it.getString("text")})
            }
        }finally{c.destroy()}
    }
    @Test fun delayedScreenScanCannotCrossIntoANewConsentingFamily(){
        RootShadow.root=screen(body=emptyList())
        val c=Robolectric.buildService(IgAccessibilityService::class.java).create()
        try{
            c.get().onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply{packageName="com.instagram.android"})
            RootShadow.root=screen(body=listOf(node("old family screen")))
            c.get().onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply{packageName="com.instagram.android"})
            GuardEngine.setFamilyToken(ctx,UUID.randomUUID().toString())
            val id=UUID.randomUUID().toString()
            ctx.getSharedPreferences("ig_guard_prefs",Context.MODE_PRIVATE).edit().putString("parent_command_public_key",FamilySecurity.newSigningIdentity().publicKey)
                .putString("capture_policy_id",id).putString("capture_local_ack_id",id).commit()
            shadowOf(Looper.getMainLooper()).idleFor(600,TimeUnit.MILLISECONDS)
            assertTrue(rows().isEmpty())
        }finally{c.destroy()}
    }
    @Test fun muteSettingWordsInAMessageDoNotCancelCapture(){
        scan(screen(body=listOf(node("Please do not use Quiet mode tonight"))))
        assertEquals(listOf("Please do not use Quiet mode tonight"),rows().map{it.optString("text")})
    }
    @Test fun backControlNeverBecomesRoomNameAndRoomsStaySeparate(){
        scan(screen("Alice"));scan(screen("Bob"))
        assertEquals(setOf("Alice","Bob"),rows().map{it.optString("conversation_label")}.toSet())
        assertEquals(2,rows().map{it.optString("conversation_key")}.toSet().size)
    }
    @Test fun noRealRoomTitleDoesNotCreateABackConversation(){scan(screen(peer=""));assertTrue(rows().isEmpty())}
    @Test fun descriptionOnlyMessageAndNestedWideOutgoingBubbleAreCaptured(){
        val bubble=node(x=100,y=800,r=950,b=910,id="outgoing_message")
        shadowOf(bubble).addChild(node("child reply",130,825,500,880,"message_text"))
        val described=node(x=30,y=500,r=500,b=560,id="message_text",description="hello in description")
        scan(screen(body=listOf(described,bubble)))
        assertEquals(listOf("hello in description","child reply"),rows().map{it.optString("text")})
        assertEquals(listOf("incoming","outgoing"),rows().map{it.optString("direction")})
    }
    @Test fun unicodeComposerAndWholeExposedAreaIncludingOffsetWindow(){
        scan(screen(hint="Message…",offset=true,body=listOf(node("top",y=155),node("middle",y=800),node("bottom",y=1610))))
        assertEquals(listOf("top","middle","bottom"),rows().map{it.optString("text")})
    }
    @Test fun decorativeDescriptionsCannotUseUpTheReaderBeforeMessages(){
        val decorations=(1..190).map{node(x=0,y=300,r=20,b=310,description="decorative image $it").apply{className="android.widget.ImageView"}}
        scan(screen(body=decorations+node("real message",y=1200)))
        assertEquals(listOf("real message"),rows().map{it.optString("text")})
    }
    @Test fun messageContainerTextSurvivesDecorativeChildrenWithoutDuplicatingNestedText(){
        val described=node(y=400,id="row_thread_message",description="container message")
        shadowOf(described).addChild(node(x=20,y=400,r=40,b=430,description="avatar").apply{className="android.widget.ImageView"})
        val nested=node("nested message",y=700,id="row_thread_message")
        shadowOf(nested).addChild(node("nested message",y=710,id="row_thread_textview"))
        scan(screen(body=listOf(described,nested)))
        assertEquals(listOf("container message","nested message"),rows().map{it.optString("text")})
    }
    @Test fun editableAncestorsAndWrongActiveWindowCannotLeakText(){
        val root=screen();val composer=root.getChild(root.childCount-1)
        shadowOf(composer).addChild(node("unsent draft",y=1550))
        scan(root)
        assertEquals(listOf("hello"),rows().map{it.optString("text")})
        scan(screen("Other",body=listOf(node("private other app"))).apply{packageName="com.other.app"})
        assertEquals(listOf("hello"),rows().map{it.optString("text")})
    }
    @Test fun incomingOnlyModeUsesRealRoomWithoutSilentlyEnablingOutgoing(){
        ctx.getSharedPreferences("ig_guard_prefs",Context.MODE_PRIVATE).edit().remove("capture_local_ack_id").commit()
        scan(screen(body=listOf(node("incoming"),node("child outgoing",600,900,950,960,"outgoing_message"))))
        assertEquals(listOf("incoming"),rows().map{it.optString("text")})
        assertEquals("Alice",rows().single().optString("sender"))
        assertFalse(GuardEngine.fullCaptureEnabled(ctx))
    }
    @Test fun incomingOnlyModeNeverMislabelsTextInsideAnOutgoingBubble(){
        ctx.getSharedPreferences("ig_guard_prefs",Context.MODE_PRIVATE).edit().remove("capture_local_ack_id").commit()
        val explicit=node(x=100,y=800,r=950,b=910,id="outgoing_message")
        shadowOf(explicit).addChild(node("private sent text",130,825,500,880,"message_text"))
        val geometric=node(x=100,y=1100,r=950,b=1210,id="message_container")
        shadowOf(geometric).addChild(node("another child reply",130,1125,500,1180,"message_text"))
        scan(screen(body=listOf(node("incoming"),explicit,geometric)))
        assertEquals(listOf("incoming"),rows().map{it.optString("text")})
        assertFalse(GuardEngine.fullCaptureEnabled(ctx))
    }
}
