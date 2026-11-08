package app.family.igguard

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class ConversationCaptureTest {
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
