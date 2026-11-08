package app.family.igguard

import android.app.Application
import android.app.Notification
import android.app.Person
import android.content.Context
import android.service.notification.StatusBarNotification
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
import java.net.*
import java.io.IOException
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class ConversationIngestionTest {
    private val ctx:Application get()=RuntimeEnvironment.getApplication()
    private var original:ProxySelector?=null
    @Before fun reset(){
        ctx.deleteDatabase("family_evidence.db");val p=ctx.getSharedPreferences("ig_guard_prefs",Context.MODE_PRIVATE);p.edit().clear().commit()
        GuardEngine.setFamilyToken(ctx,UUID.randomUUID().toString());GuardEngine.setRole(ctx,"child")
        val key=FamilySecurity.newSigningIdentity();val id=UUID.randomUUID().toString()
        p.edit().putString("parent_command_public_key",key.publicKey).putString("capture_policy_id",id).putString("capture_local_ack_id",id).commit()
        original=ProxySelector.getDefault();ProxySelector.setDefault(object:ProxySelector(){
            override fun select(u:URI)=if(u.host=="ntfy.sh")listOf(Proxy(Proxy.Type.HTTP,InetSocketAddress("127.0.0.1",9)))else original?.select(u)?:listOf(Proxy.NO_PROXY)
            override fun connectFailed(u:URI,a:SocketAddress,e:IOException){}
        })
    }
    @After fun cleanup(){ProxySelector.setDefault(original)}
    @Test fun ownNotificationTitleUsesTheStructuredPeerAndRecognizesTheChildPerson(){
        val me=Person.Builder().setName("Child Display Name").build()
        for((index,peer) in listOf("Alice","Bob").withIndex()){
            val style=Notification.MessagingStyle(me).setConversationTitle("Child Display Name").setGroupConversation(false)
                .addMessage("incoming from $peer",1000L+index,Person.Builder().setName(peer).build())
                .addMessage("reply to $peer",1100L+index,me)
            val n=Notification.Builder(ctx,"test").setSmallIcon(android.R.drawable.ic_lock_idle_lock).setStyle(style).build()
            n.extras.remove(Notification.EXTRA_SELF_DISPLAY_NAME)
            val posted=StatusBarNotification("com.instagram.android","com.instagram.android",70+index,null,0,0,0,n,android.os.UserHandle.getUserHandleForUid(0),2000L+index)
            val c=Robolectric.buildService(IgNotificationListener::class.java).create()
            try{c.get().onNotificationPosted(posted)}finally{c.destroy()}
        }
        val rows=capturedRows()
        assertEquals(setOf("Alice","Bob"),rows.map{it.getString("conversation_label")}.toSet())
        assertEquals(4,rows.size)
        assertTrue(rows.filter{it.getString("text").startsWith("reply")}.all{it.getString("direction")=="outgoing"})
        assertTrue(rows.filter{it.getString("text").startsWith("incoming")}.all{it.getString("direction")=="incoming"})
        assertEquals(2,rows.map{it.getString("conversation_key")}.toSet().size)
    }
    @Test fun explicitGroupKeepsItsTitleEvenWhenItMatchesTheChildName(){
        val me=Person.Builder().setName("child").build()
        val style=Notification.MessagingStyle(me).setConversationTitle("child").setGroupConversation(true)
            .addMessage("group message",1000,Person.Builder().setName("Alice").build())
        val n=Notification.Builder(ctx,"test").setSmallIcon(android.R.drawable.ic_lock_idle_lock).setStyle(style).build()
        val c=Robolectric.buildService(IgNotificationListener::class.java).create()
        try{c.get().onNotificationPosted(StatusBarNotification("com.instagram.android","com.instagram.android",76,null,0,0,0,n,android.os.UserHandle.getUserHandleForUid(0),2000))}finally{c.destroy()}
        assertEquals("child",capturedRows().single().getString("conversation_label"))
        assertEquals("group",capturedRows().single().getString("conversation_kind"))
    }
    @Test fun plainNotificationWithOwnTitleDoesNotInventIncomingAuthorship(){
        val n=Notification.Builder(ctx,"test").setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("Child Display Name").setContentText("sender not exposed").build()
        n.extras.putCharSequence(Notification.EXTRA_CONVERSATION_TITLE,"Alice")
        n.extras.putParcelable(Notification.EXTRA_MESSAGING_PERSON,Person.Builder().setName("Child Display Name").build())
        val c=Robolectric.buildService(IgNotificationListener::class.java).create()
        try{c.get().onNotificationPosted(StatusBarNotification("com.instagram.android","com.instagram.android",77,null,0,0,0,n,android.os.UserHandle.getUserHandleForUid(0),2000))}finally{c.destroy()}
        val row=capturedRows().single()
        assertEquals("Alice",row.getString("conversation_label"));assertEquals("unknown",row.getString("direction"));assertEquals("",row.getString("author"))
    }
    @Test fun selfTitleWithSeveralUnknownPeersDoesNotInventOneDirectConversation(){
        val me=Person.Builder().setName("child").build()
        val style=Notification.MessagingStyle(me).setConversationTitle("child").setGroupConversation(false)
            .addMessage("one",1000,Person.Builder().setName("Alice").build()).addMessage("two",1001,Person.Builder().setName("Bob").build())
        val n=Notification.Builder(ctx,"test").setSmallIcon(android.R.drawable.ic_lock_idle_lock).setStyle(style).build()
        val c=Robolectric.buildService(IgNotificationListener::class.java).create()
        try{c.get().onNotificationPosted(StatusBarNotification("com.instagram.android","com.instagram.android",75,null,0,0,0,n,android.os.UserHandle.getUserHandleForUid(0),2000))}finally{c.destroy()}
        assertTrue(capturedRows().isEmpty())
    }
    @Test fun notificationBundleRetainsEarlierTrustedMessagesAndNullSenderOutgoing(){
        GuardEngine.setTrustedHandles(ctx,listOf("friend"));GuardEngine.setChildSelfHandle(ctx,"child")
        val me=Person.Builder().setName("child").build();val other=Person.Builder().setName("friend").build()
        val style=Notification.MessagingStyle(me).setConversationTitle("friend")
            .addMessage("k",1000,other).addMessage("hello",1001,other)
            .addMessage(Notification.MessagingStyle.Message("my reply",1002,null as Person?))
        val n=Notification.Builder(ctx,"test").setSmallIcon(android.R.drawable.ic_lock_idle_lock).setStyle(style).build()
        val posted=StatusBarNotification("com.instagram.android","com.instagram.android",1,null,0,0,0,n,android.os.UserHandle.getUserHandleForUid(0),System.currentTimeMillis())
        val c=Robolectric.buildService(IgNotificationListener::class.java).create()
        try {
            c.get().onNotificationPosted(posted)
            FamilyEvidence(ctx).use { db ->
                val rows=db.due(GuardEngine.getChannelTopic(ctx),Long.MAX_VALUE).mapNotNull{GuardEngine.decryptPayload(GuardEngine.getOrCreateFamilyToken(ctx),it.wire)}.filter{it.optString("type")=="captured_message"}
                assertEquals(3,rows.size);assertEquals(setOf("k","hello","my reply"),rows.map{it.getString("text")}.toSet())
                assertEquals("outgoing",rows.single{it.getString("text")=="my reply"}.getString("direction"))
                assertTrue(rows.all{!ConversationCapture.actionable(it)})
                assertEquals(setOf(1000L,1001L,1002L),rows.map{it.getLong("message_time_ms")}.toSet())
            }
        } finally {c.destroy()}
    }
    @Test fun visibleBubbleSelectionExcludesComposerAndRetainsBothSidesAboveOldStrip(){
        val rows=listOf(VisibleCaptureText("incoming",100,150,20,450),VisibleCaptureText("outgoing",200,250,550,980),
            VisibleCaptureText("composer draft",800,850,20,980,editable=true),VisibleCaptureText("Seen",300,330,20,100),
            VisibleCaptureText("wide uncertain",400,450,20,980))
        val accepted=ConversationCapture.visibleMessages(rows,1000,900,70,780)
        assertEquals(listOf("incoming","outgoing","wide uncertain"),accepted.map{it.text})
        assertEquals(listOf("incoming","outgoing","unknown"),accepted.map{it.direction})
    }
    @Test fun oversizedNotificationBundleIsProcessedInBatchesWithoutLosingLaterMessages(){
        GuardEngine.setTrustedHandles(ctx,listOf("friend"))
        val bundles=(0 until 130).map{i->android.os.Bundle().apply{putCharSequence("text","entry $i");putCharSequence("sender","friend");putLong("time",1000L+i)}}.toTypedArray()
        val n=Notification.Builder(ctx,"test").setSmallIcon(android.R.drawable.ic_lock_idle_lock).setContentTitle("friend").setContentText("batch").build()
        n.extras.putParcelableArray(Notification.EXTRA_MESSAGES,bundles)
        val posted=StatusBarNotification("com.instagram.android","com.instagram.android",2,null,0,0,0,n,android.os.UserHandle.getUserHandleForUid(0),System.currentTimeMillis())
        val c=Robolectric.buildService(IgNotificationListener::class.java).create()
        try {
            c.get().onNotificationPosted(posted)
            FamilyEvidence(ctx).use { db ->
                val captured=db.readableDatabase.rawQuery("SELECT wire FROM outbox WHERE scope=?",arrayOf(GuardEngine.getChannelTopic(ctx))).use { cursor ->
                    buildList {while(cursor.moveToNext()){val r=GuardEngine.decryptPayload(GuardEngine.getOrCreateFamilyToken(ctx),cursor.getString(0))!!;if(r.optString("type")=="captured_message")add(r.getString("text"))}}
                }
                assertEquals(130,captured.size);assertTrue(captured.contains("entry 129"))
            }
        } finally {c.destroy()}
    }
    @Test fun absentSourceTimestampDoesNotPermanentlySuppressIdenticalLaterPosts(){
        val bundle=android.os.Bundle().apply{putCharSequence("text","identical text");putCharSequence("sender","friend")}
        val n=Notification.Builder(ctx,"test").setSmallIcon(android.R.drawable.ic_lock_idle_lock).setContentTitle("friend").build()
        n.extras.putParcelableArray(Notification.EXTRA_MESSAGES,arrayOf(bundle))
        val c=Robolectric.buildService(IgNotificationListener::class.java).create()
        try {
            for(time in listOf(1000L,2000L))c.get().onNotificationPosted(StatusBarNotification("com.instagram.android","com.instagram.android",3,null,0,0,0,n,android.os.UserHandle.getUserHandleForUid(0),time))
            FamilyEvidence(ctx).use { db ->
                val rows=db.due(GuardEngine.getChannelTopic(ctx),Long.MAX_VALUE).mapNotNull{GuardEngine.decryptPayload(GuardEngine.getOrCreateFamilyToken(ctx),it.wire)}.filter{it.optString("type")=="captured_message"}
                assertEquals(2,rows.size);assertTrue(rows.all{it.optBoolean("possible_duplicate") && it.optLong("message_time_ms")==0L})
            }
        } finally {c.destroy()}
    }
    @Test @Config(sdk=[26]) fun android26CapturesStringSenderMetadataWithoutPersonApi(){
        val b=android.os.Bundle().apply{putCharSequence("text","Android 8 notification");putCharSequence("sender","friend");putLong("time",1000)}
        val n=Notification.Builder(ctx,"test").setSmallIcon(android.R.drawable.ic_lock_idle_lock).setContentTitle("friend").build()
        n.extras.putParcelableArray(Notification.EXTRA_MESSAGES,arrayOf(b))
        val c=Robolectric.buildService(IgNotificationListener::class.java).create()
        try {
            c.get().onNotificationPosted(StatusBarNotification("com.instagram.android","com.instagram.android",4,null,0,0,0,n,android.os.UserHandle.getUserHandleForUid(0),1000))
            FamilyEvidence(ctx).use { db ->
                val rows=db.due(GuardEngine.getChannelTopic(ctx),Long.MAX_VALUE).mapNotNull{GuardEngine.decryptPayload(GuardEngine.getOrCreateFamilyToken(ctx),it.wire)}.filter{it.optString("type")=="captured_message"}
                assertEquals("Android 8 notification",rows.single().getString("text"));assertEquals("incoming",rows.single().getString("direction"))
            }
        } finally {c.destroy()}
    }
    @Test fun unprefixedNotificationDoesNotInheritAccountFromEarlierNotification(){
        val c=Robolectric.buildService(IgNotificationListener::class.java).create()
        try {
            for((i,title) in listOf("[child_a] friend","friend").withIndex()) {
                val b=android.os.Bundle().apply{putCharSequence("text","account observation $i");putCharSequence("sender","friend");putLong("time",1000L+i)}
                val n=Notification.Builder(ctx,"test").setSmallIcon(android.R.drawable.ic_lock_idle_lock).setContentTitle(title).build()
                n.extras.putParcelableArray(Notification.EXTRA_MESSAGES,arrayOf(b))
                c.get().onNotificationPosted(StatusBarNotification("com.instagram.android","com.instagram.android",10+i,null,0,0,0,n,android.os.UserHandle.getUserHandleForUid(0),1000L+i))
            }
            FamilyEvidence(ctx).use{db->
                val rows=db.due(GuardEngine.getChannelTopic(ctx),Long.MAX_VALUE).mapNotNull{GuardEngine.decryptPayload(GuardEngine.getOrCreateFamilyToken(ctx),it.wire)}.filter{it.optString("type")=="captured_message"}
                assertEquals("child_a",rows.single{it.optString("text")=="account observation 0"}.getString("account_label"))
                assertEquals("",rows.single{it.optString("text")=="account observation 1"}.getString("account_label"))
                assertEquals(2,rows.map{it.getString("conversation_key")}.toSet().size)
            }
        }finally{c.destroy()}
    }
    @Test fun flaggedIncomingFollowUpNotificationIsRemovedEvenOnDeduplicatedRepost(){
        GuardEngine.markSenderAsFlaggedOffender(ctx,"flagged_contact")
        val b=android.os.Bundle().apply{putCharSequence("text","ordinary follow-up");putCharSequence("sender","flagged_contact");putLong("time",1000)}
        val n=Notification.Builder(ctx,"test").setSmallIcon(android.R.drawable.ic_lock_idle_lock).setContentTitle("flagged_contact").build()
        n.extras.putParcelableArray(Notification.EXTRA_MESSAGES,arrayOf(b))
        val posted=StatusBarNotification("com.instagram.android","com.instagram.android",20,null,0,0,0,n,android.os.UserHandle.getUserHandleForUid(0),1000)
        val c=Robolectric.buildService(IgNotificationListener::class.java).create()
        try {
            repeat(2){
                shadowOf(c.get()).addActiveNotification(posted)
                c.get().onNotificationPosted(posted)
                assertTrue(c.get().activeNotifications.isEmpty())
            }
        }finally{c.destroy()}
    }
    private fun deliverGroupFixture(group:Boolean,text:String,author:String="friend",id:Int=30,label:String="friend"){
        val b=android.os.Bundle().apply{putCharSequence("text",text);putCharSequence("sender",author);putLong("time",1000L+id)}
        val n=Notification.Builder(ctx,"test").setSmallIcon(android.R.drawable.ic_lock_idle_lock).setContentTitle("[child] $label").build()
        n.extras.putCharSequence(Notification.EXTRA_CONVERSATION_TITLE,label)
        n.extras.putBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION,group)
        n.extras.putParcelableArray(Notification.EXTRA_MESSAGES,arrayOf(b))
        val c=Robolectric.buildService(IgNotificationListener::class.java).create()
        try{c.get().onNotificationPosted(StatusBarNotification("com.instagram.android","com.instagram.android",id,null,0,0,0,n,android.os.UserHandle.getUserHandleForUid(0),1000L+id))}
        finally{c.destroy()}
    }
    private fun capturedRows():List<JSONObject> = FamilyEvidence(ctx).use{db->
        db.due(GuardEngine.getChannelTopic(ctx),Long.MAX_VALUE).mapNotNull{GuardEngine.decryptPayload(GuardEngine.getOrCreateFamilyToken(ctx),it.wire)}.filter{it.optString("type")=="captured_message"}
    }
    @Test fun explicitGroupAndDirectConversationWithSameLabelStaySeparateInParentArchive(){
        deliverGroupFixture(false,"direct observation",id=30)
        deliverGroupFixture(true,"group observation",author="another_person",id=31)
        val rows=capturedRows()
        assertEquals(2,rows.map{it.getString("conversation_key")}.toSet().size)
        GuardEngine.setRole(ctx,"parent")
        rows.forEach{GuardEngine.recordParentAlert(ctx,it,it.getString("event_id"))}
        FamilyEvidence(ctx).use{db->assertEquals(2,db.conversations(GuardEngine.getChannelTopic(ctx),EvidenceQuery(section="conversations")).size)}
    }
    @Test fun groupTitleCannotGiveAnUnapprovedAuthorAnotherContactsTrust(){
        GuardEngine.setTrustedHandles(ctx,listOf("friend"))
        deliverGroupFixture(true,"ordinary group text",author="another_person")
        assertEquals("stranger_message",capturedRows().single().getString("reason"))
    }
    @Test fun groupKeywordDoesNotFlagTheIndividualWhoseNameMatchesTheGroupTitle(){
        deliverGroupFixture(true,"send nudes",author="friend")
        assertFalse(GuardEngine.isFlaggedOffender(ctx,"friend"))
        assertFalse(capturedRows().single().getBoolean("flag_partner"))
    }
    @Test fun explicitGroupNamedAfterTheChildStillRetainsOtherMembersMessages(){
        deliverGroupFixture(true,"another member's message",author="another_person",label="child")
        assertEquals(listOf("another member's message"),capturedRows().map{it.getString("text")})
    }
}
