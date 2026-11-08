package app.family.igguard

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class ConversationCaptureTest {
    @Test fun bodyWordsNeverDecideSenderAndExplicitDirectionOverridesBubbleGeometry(){
        val incoming=VisibleCaptureText("You: quoted text",200,270,20,600)
        val outgoing=VisibleCaptureText("wide child bubble",400,470,60,950,direction="outgoing")
        val uncertain=VisibleCaptureText("centred",600,670,100,900)
        val rows=ConversationCapture.visibleMessages(listOf(uncertain,outgoing,incoming),1000,2000,150,1700)
        assertEquals(listOf("incoming","outgoing","unknown"),rows.map{it.direction})
    }
    @Test fun exposedEdgesAreCapturedButControlsAndUnsentDraftsAreNot(){
        val rows=ConversationCapture.visibleMessages(listOf(
            VisibleCaptureText("partial top",120,210,20,400),VisibleCaptureText("partial bottom",1650,1750,20,400),
            VisibleCaptureText("call",500,560,20,400,button=true),VisibleCaptureText("draft",700,760,20,400,editable=true),
            VisibleCaptureText("hidden",1800,1850,20,400)),1000,2000,150,1700)
        assertEquals(listOf("partial top","partial bottom"),rows.map{it.text})
    }
    @Test fun callCardIsNotABackAnchorForAnUnreadableConversationTitle(){
        val actualBack=VisibleCaptureText("返回",70,140,16,80,button=true,viewId="action_bar_button_back")
        val callCard=VisibleCaptureText("Call",350,400,20,80,button=true)
        val cardText=VisibleCaptureText("Call again later",350,400,100,600,heading=true)
        assertNull(ConversationCapture.threadHeader(listOf(actualBack,callCard,cardText),2000))
    }
    @Test fun semanticHeadingCanBeAClickablePeerName(){
        val back=VisibleCaptureText("返回",70,140,16,80,button=true,viewId="action_bar_button_back")
        val title=VisibleCaptureText("Alice",85,150,100,600,heading=true,button=true)
        assertEquals("Alice",ConversationCapture.threadHeader(listOf(back,title),2000)?.text)
    }
    @Test fun ambiguousToolbarIsSkippedWhileAnExplicitTitleCanLiterallyBeNamedBack(){
        val back=VisibleCaptureText("返回",70,140,16,80,button=true,viewId="action_bar_button_back")
        assertNull(ConversationCapture.threadHeader(listOf(back),2000))
        assertNull(ConversationCapture.threadHeader(listOf(back,VisibleCaptureText("Alice",85,150,100,300),VisibleCaptureText("Bob",85,150,350,600)),2000))
        assertEquals("返回",ConversationCapture.threadHeader(listOf(back,VisibleCaptureText("返回",85,150,100,400,viewId="action_bar_title")),2000)?.text)
        assertEquals(listOf("返回"),ConversationCapture.visibleMessages(listOf(VisibleCaptureText("返回",400,470,20,400)),1000,2000,150,1700).map{it.text})
    }
    @Test fun explicitToolbarTitleOutranksAnUnrelatedMessageHeading(){
        val title=VisibleCaptureText("Alice",85,150,100,400,viewId="action_bar_title")
        val date=VisibleCaptureText("Earlier conversation",250,290,350,650,heading=true)
        assertEquals(title,ConversationCapture.threadHeader(listOf(title,date),2000))
    }
    @Test fun opaqueToolbarDoesNotMakeTheFirstHighMessageARoomName(){
        assertNull(ConversationCapture.threadHeader(listOf(VisibleCaptureText("first message",180,240,30,500)),2000))
    }
    @Test fun newNotificationContactNamedBackIsNotHistoricalScreenCorruption(){
        val r=ConversationCapture.record(CapturedText("Back","hello","incoming",source="notification",messageTime=1000),2000,true,false)
        assertFalse(ConversationCapture.unidentifiedLegacyLabel(r))
        assertFalse(ConversationCapture.unidentifiedLegacyLabel(org.json.JSONObject().put("sender","返回").put("capture_source","notification")))
    }
    @Test fun screenOrderCannotForgeSourceTimestampsOrApplyToNotifications(){
        val r=ConversationCapture.record(CapturedText("friend","text","incoming"),2000,true,false).put("screen_anchor_ms",1000).put("screen_order",-1)
        assertTrue(ConversationCapture.valid(r))
        assertFalse(ConversationCapture.valid(org.json.JSONObject(r.toString()).put("source_context","notification")))
        assertFalse(ConversationCapture.valid(org.json.JSONObject(r.toString()).put("screen_anchor_ms",3000)))
    }
    @Test fun historicalBackLabelIsMarkedUnidentifiedWithoutChangingEvidence(){
        val old=ConversationCapture.record(CapturedText("返回","original text","incoming"),2000,true,false)
        assertTrue(ConversationCapture.unidentifiedLegacyLabel(old))
        assertEquals("未識別對話（舊擷取：返回）",ConversationCapture.displayLabel("返回",true))
        assertEquals("original text",old.getString("text"));assertEquals("返回",old.getString("conversation_label"))
        val namedBack=ConversationCapture.record(CapturedText("返回","new text","incoming",labelFromToolbar=true),2000,true,false)
        assertFalse(ConversationCapture.unidentifiedLegacyLabel(namedBack))
    }
    @Test fun outgoingAuthorIsSeparateFromConversationAndKeywordDoesNotAccusePartner() {
        val r=ConversationCapture.record(CapturedText("friend","send nudes","outgoing",author="child",account="child",messageTime=1234),2000,trusted=true,flagged=false)
        assertEquals("captured_message",r.getString("type"))
        assertEquals("friend",r.getString("conversation_label"))
        assertEquals("child",r.getString("author"))
        assertEquals("outgoing",r.getString("direction"))
        assertEquals(1234,r.getLong("message_time_ms"))
        assertTrue(ConversationCapture.actionable(r))
        assertFalse(r.getBoolean("flag_partner"))
        assertTrue(r.getJSONArray("signals").toString().contains("image_request"))
    }
    @Test fun ordinaryTrustedIncomingAndOutgoingAreContextButUnapprovedIncomingStillAlerts() {
        for(direction in listOf("incoming","outgoing","unknown")) {
            val r=ConversationCapture.record(CapturedText("friend","ordinary greeting",direction),2000,true,false)
            assertFalse(ConversationCapture.actionable(r));assertEquals("conversation_context",r.getString("reason"))
        }
        assertEquals("stranger_message",ConversationCapture.record(CapturedText("new","hello","incoming"),2000,false,false).getString("reason"))
        val follow=ConversationCapture.record(CapturedText("new","hello","incoming"),2000,false,true)
        assertEquals("flagged_contact_follow_up",follow.getString("reason"))
        assertEquals(0,follow.getJSONArray("matches").length())
    }
    @Test fun conversationKeyUsesPartnerAndAccountRatherThanAuthor() {
        val one=ConversationCapture.record(CapturedText("friend","hello","incoming",author="friend",account="child"),1000,true,false)
        val two=ConversationCapture.record(CapturedText("friend","reply","outgoing",author="child",account="child"),1001,true,false)
        assertEquals(one.getString("conversation_key"),two.getString("conversation_key"))
        assertNotEquals(ConversationCapture.key("other_child","friend"),one.getString("conversation_key"))
        assertNotEquals(ConversationCapture.key("","friend"),one.getString("conversation_key"))
    }
    @Test fun snapshotOverlapPreservesRepeatedOccurrencesAndSuppressesOnlyKnownRefresh() {
        assertEquals(listOf(0,1),ConversationCapture.newIndices(emptyList(),listOf("a","a")))
        assertEquals(emptyList<Int>(),ConversationCapture.newIndices(listOf("a","a"),listOf("a","a")))
        assertEquals(listOf(2),ConversationCapture.newIndices(listOf("a","a"),listOf("a","a","a")))
        assertEquals(listOf(1,2),ConversationCapture.newIndices(listOf("a","b"),listOf("b","c","d")))
    }
    @Test fun newRecordValidationRejectsFalseDirectionsAndEmptyContent() {
        val r=ConversationCapture.record(CapturedText("friend","text","incoming"),1000,false,false)
        assertTrue(ConversationCapture.valid(r));assertFalse(ConversationCapture.valid(r.put("direction","from-anyone")))
        try { ConversationCapture.record(CapturedText("friend","","incoming"),1000,false,false);fail("Empty text must be rejected") } catch(_:IllegalArgumentException) {}
        assertFalse(ConversationCapture.valid(org.json.JSONObject().put("type","captured_message")))
    }
    @Test fun inconsistentThreadIdentityAndInventedKeywordEvidenceAreRejected(){
        val r=ConversationCapture.record(CapturedText("friend","ordinary","incoming"),1000,true,false)
        assertFalse(ConversationCapture.valid(org.json.JSONObject(r.toString()).put("conversation_key",ConversationCapture.key("other","person"))))
        assertFalse(ConversationCapture.valid(org.json.JSONObject(r.toString()).put("reason","sexual_or_grooming_content")))
    }
    @Test fun unknownAuthorAndGroupTitleCannotBeFlaggedAsTheIndividualAuthor(){
        val unknown=ConversationCapture.record(CapturedText("Study Group","send nudes","incoming"),1000,false,false)
        assertTrue(ConversationCapture.actionable(unknown));assertFalse(unknown.getBoolean("flag_partner"))
        val group=ConversationCapture.record(CapturedText("Study Group","send nudes","incoming",author="actual_sender"),1000,false,false)
        assertEquals("actual_sender",group.getString("author"));assertFalse(group.getBoolean("flag_partner"))
    }
}
