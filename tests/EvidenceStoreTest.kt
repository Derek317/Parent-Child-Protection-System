package app.family.igguard

import android.app.Application
import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class EvidenceStoreTest {
    private val ctx: Application get()=RuntimeEnvironment.getApplication()
    @Before fun reset(){ ctx.deleteDatabase("family_evidence.db");ctx.getSharedPreferences("ig_guard_prefs",Context.MODE_PRIVATE).edit().clear().commit() }
    private fun event(i:Int,body:String="message $i",sender:String="contact",reason:String="stranger_message")=JSONObject().put("event_id","event-$i").put("type","alert").put("sender",sender).put("reason",reason).put("text",body).put("ts_ms",1_700_000_000_000L+i)
    @Test fun archiveRetainsOldMessagesBeyondInboxPageAndFullUnicodeText(){
        val long="中文🙂\n\"quoted\" ".repeat(800)
        FamilyEvidence(ctx).use { db->
            for(i in 0..100) assertTrue(db.append("family","event-$i",event(i,if(i==0)long else "message $i")))
            assertEquals(long,db.find("family","event-0")!!.getString("text"))
            assertEquals(31,db.messages("family",EvidenceQuery()).size)
            assertEquals(101,db.contacts("family",EvidenceQuery()).single().count)
        }
    }
    @Test fun filtersSearchOldRecordsAndPaginationHasNoTieDuplicates(){
        FamilyEvidence(ctx).use { db->
            for(i in 0..65) db.append("family","event-$i",event(i,if(i==0)"needle 100%_done" else "ordinary","contact",if(i==0)"sexual_or_grooming_content" else "stranger_message").put("ts_ms",1234L))
            db.save("family","event-0",true)
            assertEquals("event-0",db.messages("family",EvidenceQuery(text="100%_done",filter="high",savedOnly=true)).single().getString("_id"))
            assertTrue(db.messages("family",EvidenceQuery(since=2000)).isEmpty())
            val first=db.messages("family",EvidenceQuery()).take(30).map{it.getString("_id")}
            val second=db.messages("family",EvidenceQuery(page=1)).take(30).map{it.getString("_id")}
            assertEquals(30,first.size);assertEquals(30,second.size);assertTrue(first.intersect(second.toSet()).isEmpty())
        }
    }
    @Test fun deletionDoesNotResurrectEvidenceOnReplayAndScopesStayIsolated(){
        FamilyEvidence(ctx).use { db->
            db.append("old","event-1",event(1));db.deleteArchive("old")
            assertFalse(db.append("old","event-1",event(1)))
            assertNull(db.find("old","event-1"));assertTrue(db.hasSeen("old","event-1"))
            db.append("old","event-2",event(2))
            assertTrue(db.messages("new",EvidenceQuery()).isEmpty())
            assertNotNull(db.find("old","event-2"))
        }
    }
    @Test fun pendingCiphertextSurvivesReopenAndFailureWaitsForRetry(){
        FamilyEvidence(ctx).use { it.enqueue("scope","frame","ciphertext");it.failed("scope","frame",1000) }
        FamilyEvidence(ctx).use { db->
            assertEquals(1,db.pendingCount("scope"));assertTrue(db.due("scope",1001).isEmpty())
            assertEquals("ciphertext",db.due("scope",1_000_000).single().wire)
            db.acknowledge("different","frame");assertEquals(1,db.pendingCount("scope"))
            db.acknowledge("scope","frame");assertEquals(0,db.pendingCount("scope"))
        }
    }
    @Test fun enqueueBatchIsAtomicWhenOneFrameIsInvalid(){
        FamilyEvidence(ctx).use { db->
            try { db.enqueueBatch("f",listOf("ok:0" to "ciphertext","" to "bad"));fail("Invalid frame must be rejected") }catch(_:IllegalArgumentException){}
            assertEquals(0,db.pendingCount("f"))
        }
    }
    @Test fun anOutageBacksOffTheBatchAndNewlyQueuedFrames(){
        FamilyEvidence(ctx).use { db->
            db.enqueue("f","a","cipher a");db.enqueue("f","b","cipher b")
            db.failed("f","a",1000)
            assertTrue(db.due("f",1001).isEmpty())
            db.enqueue("f","c","cipher c")
            assertTrue(db.due("f",1001).isEmpty())
            assertEquals(3,db.pendingCount("f"))
        }
    }
    @Test fun manualDeletionCanRemoveOldIsolatedArchivesWithoutDeletingCurrentFamily(){
        FamilyEvidence(ctx).use { db->
            db.append("old","event-1",event(1));db.append("current","event-2",event(2))
            assertEquals(1,db.otherArchiveCount("current"))
            db.deleteOtherArchives("current")
            assertNull(db.find("old","event-1"));assertTrue(db.hasSeen("old","event-1"))
            assertNotNull(db.find("current","event-2"))
        }
    }
    @Test fun deletingOneMessageKeepsOtherEvidenceAndPreventsReplay(){
        FamilyEvidence(ctx).use { db->
            db.append("f","event-1",event(1));db.append("f","event-2",event(2))
            db.deleteMessage("f","event-1")
            assertFalse(db.append("f","event-1",event(1)))
            assertNull(db.find("f","event-1"));assertNotNull(db.find("f","event-2"))
        }
    }
    @Test fun newAlertDoesNotInheritAnotherEventsReceiptWait(){
        FamilyEvidence(ctx).use { db->
            db.enqueueBatch("f",listOf("old:0" to "cipher"),waitForReceipt=true);db.relayAccepted("f","old:0",1000)
            db.enqueue("f","new:0","new cipher")
            assertEquals(listOf("new:0"),db.due("f",1001).map{it.id})
        }
    }
    @Test fun receiptEventMatchingIsExactForWildcardsAndPrefixIds(){
        FamilyEvidence(ctx).use { db->
            db.enqueue("f","event-a:0","one");db.enqueue("f","a:0","two");db.enqueue("f","a:b:0","three")
            assertEquals(0,db.unacceptedEventCount("f","event_a"))
            db.acknowledgeEvent("f","event_a");assertEquals(3,db.pendingCount("f"))
            db.acknowledgeEvent("f","a")
            assertEquals(setOf("event-a:0","a:b:0"),db.due("f",Long.MAX_VALUE).map{it.id}.toSet())
        }
    }
    @Test fun replacingFamilyPreservesLegacyJsonAndMalformedRawUnderOldScope(){
        GuardEngine.setRole(ctx,"parent")
        val original=GuardEngine.getChannelTopic(ctx)
        ctx.getSharedPreferences("ig_guard_prefs",Context.MODE_PRIVATE).edit().putString("parent_alerts_json","[${event(1)}]").commit()
        GuardEngine.setFamilyToken(ctx,java.util.UUID.randomUUID().toString())
        FamilyEvidence(ctx).use{assertEquals("message 1",it.messages(original,EvidenceQuery(filter="any")).single().getString("text"))}
        val second=GuardEngine.getChannelTopic(ctx)
        ctx.getSharedPreferences("ig_guard_prefs",Context.MODE_PRIVATE).edit().putString("parent_alerts_json","malformed old evidence").commit()
        GuardEngine.setFamilyToken(ctx,java.util.UUID.randomUUID().toString())
        FamilyEvidence(ctx).use{assertEquals("malformed old evidence",it.messages(second,EvidenceQuery(filter="any")).single().getString("text"))}
        assertTrue(GuardEngine.getParentAlerts(ctx).isEmpty())
    }
    @Test fun deletingUpgradedInboxPreservesPreviouslyUninitializedStatistics(){
        GuardEngine.setRole(ctx,"parent")
        val row=event(1).put("ts_ms",System.currentTimeMillis())
        ctx.getSharedPreferences("ig_guard_prefs",Context.MODE_PRIVATE).edit().putString("parent_alerts_json","[$row]").commit()
        GuardEngine.clearParentAlerts(ctx)
        assertTrue(GuardEngine.getParentAlerts(ctx).isEmpty())
        assertEquals(1L,GuardEngine.getParentStatistics(ctx).dailyTotals().sum())
    }
    private fun recoverRotation(afterMove:Boolean){
        GuardEngine.setRole(ctx,"parent")
        val oldToken=GuardEngine.getOrCreateFamilyToken(ctx);val oldScope=GuardEngine.getChannelTopic(ctx)
        val newToken=java.util.UUID.randomUUID().toString();val newScope=GuardEngine.getChannelTopicForToken(newToken)
        FamilyEvidence(ctx).use{db->db.append(oldScope,"event-1",event(1));db.enqueue(oldScope,"event-1:0",GuardEngine.encryptPayload(oldToken,"{\"type\":\"alert\"}"))}
        ctx.getSharedPreferences("ig_guard_prefs",Context.MODE_PRIVATE).edit().putString("evidence_transition_json",JSONObject().put("old_token",oldToken).put("new_token",newToken).toString()).commit()
        if(afterMove)FamilyEvidence(ctx).use{it.rotate(oldScope,newScope){wire->GuardEngine.encryptPayload(newToken,GuardEngine.decryptPayload(oldToken,wire)!!.toString())}}
        assertEquals(newToken,GuardEngine.getOrCreateFamilyToken(ctx))
        FamilyEvidence(ctx).use{db->
            assertNotNull(db.find(newScope,"event-1"));assertNull(db.find(oldScope,"event-1"))
            assertNotNull(GuardEngine.decryptPayload(newToken,db.due(newScope,Long.MAX_VALUE).single().wire))
        }
    }
    @Test fun startupRecoversRotationBeforeDatabaseMove(){recoverRotation(false)}
    @Test fun startupRecoversRotationAfterDatabaseMoveBeforeTokenCommit(){recoverRotation(true)}
    @Test fun rotationKeepsEvidenceAndRekeysPendingWithoutOldAckDeletingIt(){
        FamilyEvidence(ctx).use { db->
            db.append("old","event-1",event(1));db.enqueue("old","frame","old cipher")
            db.rotate("old","new"){"new cipher"}
            db.acknowledge("old","frame")
            assertEquals("new cipher",db.due("new",Long.MAX_VALUE).single().wire)
            assertNotNull(db.find("new","event-1"));assertNull(db.find("old","event-1"))
        }
    }
    @Test fun adviceOnlyForNewKeywordEventsAndAcknowledgmentPersists(){
        FamilyEvidence(ctx).use { db->
            db.append("f","event-0",event(0));assertNull(db.nextAdvice("f"))
            db.append("f","event-1",event(1,reason="sexual_or_grooming_content"))
            assertNotNull(db.nextAdvice("f"));db.acknowledgeAdvice("f");assertNull(db.nextAdvice("f"))
            db.append("f","event-2",event(2,reason="sexual_or_grooming_content"),legacy=true);assertNull(db.nextAdvice("f"))
        }
    }
    @Test fun longFramesReassembleOutOfOrderAndRejectConflicts(){
        val payload=event(7,"廣東話🙂\n\"text\"".repeat(800))
        val parts=AlertFrames.split(payload)
        assertTrue(parts.size>1)
        val token="12345678-1234-1234-1234-123456789abc"
        parts.forEach{assertTrue(GuardEngine.encryptPayload(token,it.toString()).toByteArray().size<4096)}
        FamilyEvidence(ctx).use { db->
            assertNull(db.fragment("f",parts.last()))
            val conflict=JSONObject(parts.last().toString()).put("data","YQ==")
            assertNull(db.fragment("f",conflict))
            var complete:JSONObject?=null
            for(p in parts.dropLast(1).reversed()) complete=db.fragment("f",p)?:complete
            assertEquals(payload.getString("text"),complete!!.getString("text"))
            assertTrue(db.append("f","event-7",complete))
            assertEquals(0,db.incompleteCount("f"))
        }
    }
    @Test fun searchDoesNotMatchHiddenProtocolMetadata(){
        FamilyEvidence(ctx).use { db->
            db.append("f","event-1",event(1,"ordinary greeting",reason="sexual_or_grooming_content"))
            assertTrue(db.messages("f",EvidenceQuery(text="sexual_or_grooming_content")).isEmpty())
        }
    }
    @Test fun replayOfStoredFragmentCanRepairInterruptedStatisticsWithoutRecreatingDeletedContent(){
        val payload=event(8,"long text ".repeat(1000))
        val parts=AlertFrames.split(payload)
        FamilyEvidence(ctx).use { db->
            db.append("f","event-8",payload)
            assertNotNull(db.fragment("f",parts.first()))
            db.deleteArchive("f")
            assertNull(db.fragment("f",parts.first()))
        }
    }
    @Test fun deletingIncompleteEvidencePreventsItsLaterReassembly(){
        val parts=AlertFrames.split(event(9,"large text ".repeat(1000)))
        FamilyEvidence(ctx).use { db->
            assertNull(db.fragment("f",parts.first()));db.deleteArchive("f")
            assertTrue(db.hasSeen("f","event-9"))
            assertTrue(parts.all{db.fragment("f",it)==null})
            assertEquals(0,db.incompleteCount("f"))
        }
    }
    @Test fun fragmentsCannotSmuggleUnsignedCommandsOrOversizedCounts(){
        val fake=JSONObject().put("type","alert_fragment").put("event_id","x").put("parts",Int.MAX_VALUE).put("part",0).put("data","YQ==").put("digest","a".repeat(64))
        FamilyEvidence(ctx).use { db->assertNull(db.fragment("f",fake));assertEquals(0,db.incompleteCount("f")) }
    }
    @Test fun engineMigrationKeepsLegacyTextAndDoesNotDuplicateOnRepeatedRead(){
        GuardEngine.setRole(ctx,"parent")
        ctx.getSharedPreferences("ig_guard_prefs",Context.MODE_PRIVATE).edit().putString("parent_alerts_json","[${event(1)}]").commit()
        assertEquals(1,GuardEngine.getParentAlerts(ctx).size);assertEquals(1,GuardEngine.getParentAlerts(ctx).size)
        assertTrue(GuardEngine.getParentAlerts(ctx).single().getBoolean("_legacy_partial"))
    }
    @Test fun malformedLegacyRecordIsPreservedForRecovery(){
        GuardEngine.setRole(ctx,"parent")
        ctx.getSharedPreferences("ig_guard_prefs",Context.MODE_PRIVATE).edit().putString("parent_alerts_json","corrupt-but-retain").commit()
        val retained=GuardEngine.getParentAlerts(ctx)
        assertEquals("corrupt-but-retain",retained.single().getString("text"))
        assertEquals("archive_recovery",retained.single().getString("reason"))
        assertEquals("corrupt-but-retain",GuardEngine.getParentAlerts(ctx).single().getString("text"))
    }
    @Test fun migratedStableIdsDoNotDuplicateEvidenceOrExistingStatistics(){
        GuardEngine.setRole(ctx,"parent")
        val alert=event(1).put("ts_ms",System.currentTimeMillis())
        val stats=AlertStatistics(JSONObject(),System.currentTimeMillis());stats.record("relay:old",alert);stats.state.put("initialized",true)
        ctx.getSharedPreferences("ig_guard_prefs",Context.MODE_PRIVATE).edit().putString("weekly_stats_json",stats.state.toString()).putString("parent_alerts_json","[$alert]").commit()
        assertEquals(1,GuardEngine.getParentAlerts(ctx).size)
        assertFalse(GuardEngine.recordParentAlert(ctx,alert,"relay:new"))
        assertEquals(1L,GuardEngine.getParentStatistics(ctx).dailyTotals().sum())
        assertEquals(1,GuardEngine.getParentAlerts(ctx).size)
    }
    @Test fun archiveStillReceivesMoreThanSixtyEngineAlerts(){
        GuardEngine.setRole(ctx,"parent")
        for(i in 0..75) assertTrue(GuardEngine.recordParentAlert(ctx,event(i),"event-$i"))
        FamilyEvidence(ctx).use { assertEquals(76,it.contacts(GuardEngine.getChannelTopic(ctx),EvidenceQuery()).single().count) }
    }
}
