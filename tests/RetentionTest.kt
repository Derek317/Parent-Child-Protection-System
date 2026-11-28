package app.family.igguard

import android.app.Application
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
class RetentionTest {
    private val ctx:Application get()=RuntimeEnvironment.getApplication()
    private val day=86_400_000L
    private fun event(id:String,now:Long,text:String="private retention content")=JSONObject()
        .put("type","alert").put("event_id",id).put("ts_ms",now).put("reason","stranger_message").put("sender","person").put("text",text)
    @Before fun reset(){ctx.deleteDatabase("family_evidence.db")}

    @Test fun newArchivesExpireAtThirtyDaysAndCannotBeReplayedOrRequeued() {
        val now=System.currentTimeMillis()
        FamilyEvidence(ctx).use { db ->
            assertTrue(db.append("f","a",event("a",now)))
            db.enqueue("f","a:0","cipher")
            db.save("f","a",true)
            db.pruneExpired("f",now+30*day-1)
            assertNotNull(db.find("f","a"))
            db.pruneExpired("f",now+30*day+5_000)   // retention runs from receipt time, a few ms after `now`
            assertNull(db.find("f","a"));assertTrue(db.hasSeen("f","a"))
            assertFalse(db.append("f","a",event("a",now+30*day)))
            db.enqueue("f","a:0","replayed cipher")
            assertEquals(0,db.pendingCount("f"))
        }
    }

    @Test fun outboxExpirySurvivesAcknowledgementResubmissionAndRotation() {
        val now=System.currentTimeMillis()
        FamilyEvidence(ctx).use { db ->
            db.enqueue("old","queued:0","cipher")
            db.writableDatabase.execSQL("UPDATE outbox_lifetimes SET expires=?",arrayOf(now+3*day))
            db.acknowledge("old","queued:0")
            db.enqueue("old","queued:0","same event later")
            db.rotate("old","new"){it}
            db.pruneExpired("new",now+3*day)
            db.enqueue("new","queued:1","late part")
            assertEquals(0,db.pendingCount("new"))
            assertFalse(db.enqueueCaptured("new","queued",listOf("queued:0" to "cipher"),true,0))
        }
    }

    @Test fun preservationIsExplicitBoundedAndDoesNotRenewOnRepeatedClicks() {
        val now=System.currentTimeMillis()
        FamilyEvidence(ctx).use { db ->
            db.append("f","a",event("a",now));db.enqueue("f","a:0","cipher")
            assertTrue(db.preserveForReview("f","a",now))
            db.pruneExpired("f",now+31*day)
            assertNotNull(db.find("f","a"));assertEquals(0,db.pendingCount("f"))
            db.preserveForReview("f","a",now+31*day)
            db.pruneExpired("f",now+90*day)
            assertNull(db.find("f","a"));assertFalse(db.preserveForReview("f","missing",now))
        }
    }

    @Test fun preservationCannotReviveExpiredContentBeforeScheduledPruning() {
        val now=System.currentTimeMillis()
        FamilyEvidence(ctx).use { db ->
            db.append("f","a",event("a",now))
            db.writableDatabase.execSQL("UPDATE evidence SET expires=? WHERE id='a'",arrayOf(now))
            assertFalse(db.preserveForReview("f","a",now))
            db.pruneExpired("f",now)
            assertNull(db.find("f","a"));assertTrue(db.hasSeen("f","a"))
        }
    }

    @Test fun legacyPreviewNeverDeletesUntilExplicitAdoption() {
        val now=System.currentTimeMillis()
        FamilyEvidence(ctx).use { db ->
            db.append("f","old",event("old",now-100*day))
            db.writableDatabase.execSQL("UPDATE evidence SET retention_base=NULL,expires=NULL")
        }
        FamilyEvidence(ctx).use { db ->
            val preview=db.retentionPreview("f",now)
            assertEquals(30,preview.retentionDays);assertEquals(1,preview.legacyCount);assertEquals(1,preview.legacyExpiredCount)
            db.pruneExpired("f",now);assertNotNull(db.find("f","old"))
            assertEquals(1,db.applyRetentionToLegacy("f",now));db.pruneExpired("f",now)
            assertNull(db.find("f","old"));assertTrue(db.hasSeen("f","old"))
        }
    }

    @Test fun expiryAndDeletionClearFragmentsWindowsAndPendingWithoutLosingSeenProtection() {
        val now=System.currentTimeMillis();val parts=AlertFrames.split(event("partial",now,"secret".repeat(1000)))
        FamilyEvidence(ctx).use { db ->
            db.fragment("f",parts.first());db.setCaptureWindow("f","thread",listOf("secret"))
            db.enqueue("f","partial:0","cipher");db.deleteMessage("f","partial")
            assertEquals(0,db.pendingCount("f"));assertEquals(0,db.incompleteCount("f"))
            assertTrue(db.captureWindowState("f","thread").tokens.isEmpty());assertTrue(db.hasSeen("f","partial"))
            assertTrue(parts.all{db.fragment("f",it)==null})
            db.fragment("f",AlertFrames.split(event("expires",now,"secret".repeat(1000))).first())
            db.setCaptureWindow("f","thread",listOf("secret"))
            db.pruneExpired("f",now+3*day+1000)
            assertEquals(0,db.incompleteCount("f"));assertTrue(db.hasSeen("f","expires"))
            assertTrue(db.captureWindowState("f","thread").tokens.isEmpty())
        }
    }

    @Test fun changingRetentionIsScopedAndCannotExtendAlreadyAssignedDeadlines() {
        val now=System.currentTimeMillis()
        FamilyEvidence(ctx).use { db ->
            db.append("f","a",event("a",now));db.append("other","a",event("a",now))
            db.setRetentionDays("f",7);db.setRetentionDays("f",30)
            db.pruneExpired("f",now+7*day+5_000)
            assertNull(db.find("f","a"));assertNotNull(db.find("other","a"))
            for(invalid in listOf(0,31,Int.MAX_VALUE))try {db.setRetentionDays("f",invalid);fail("Invalid retention accepted")}catch(_:IllegalArgumentException){}
        }
    }

    @Test fun discardingExpandedPendingKeepsUrgentControlsAndDeliveredArchives() {
        val now=System.currentTimeMillis()
        FamilyEvidence(ctx).use { db ->
            db.append("f","delivered",event("delivered",now))
            db.enqueue("f","data:0","context",kind="data")
            db.enqueue("f","urgent:0","urgent")
            db.enqueue("f","control:0","control",role="parent",kind="control")
            db.discardPending("f","child","data")
            assertNotNull(db.find("f","delivered"));assertEquals(2,db.pendingCount("f"))
            assertFalse(db.enqueueCaptured("f","data",listOf("data:0" to "context"),true,0))
            db.discardPending("f","child")
            assertEquals(listOf("control:0"),db.due("f",Long.MAX_VALUE,"parent").map{it.id})
            db.discardPending("f");assertEquals(0,db.pendingCount("f"));assertNotNull(db.find("f","delivered"))
        }
    }

    @Test fun expiredPendingCannotPassTheFinalSendCheckBeforeScheduledPruning() {
        FamilyEvidence(ctx).use { db ->
            db.enqueue("f","old:0","cipher")
            db.writableDatabase.execSQL("UPDATE outbox_lifetimes SET expires=1")
            assertTrue(db.due("f",Long.MAX_VALUE).isEmpty())
            assertFalse(db.pendingEvent("f","old:0"))
            assertFalse(db.eventDue("f","old",System.currentTimeMillis()))
        }
    }

    @Test fun versionFiveUpgradeOffersPreviewAndKeepsEncryptedArchivesAndQueue() {
        val now=System.currentTimeMillis()
        FamilyEvidence(ctx).use { db ->
            db.append("f","old",event("old",now-100*day));db.enqueue("f","queued:0","cipher")
            val sql=db.writableDatabase
            // Rebuild just the two extended tables to reproduce the encrypted version-five layout.
            for((table,excluded) in listOf("evidence" to setOf("retention_base","expires","preserved_until"),"fragments" to setOf("expires"))) {
                val columns=sql.rawQuery("PRAGMA table_info($table)",null).use{c->buildList{while(c.moveToNext()){val name=c.getString(1);if(name !in excluded)add(name)}}}
                sql.execSQL("CREATE TABLE retention_v5 AS SELECT ${columns.joinToString(",")} FROM $table")
                sql.execSQL("DROP TABLE $table");sql.execSQL("ALTER TABLE retention_v5 RENAME TO $table")
            }
            sql.execSQL("DROP TABLE outbox_lifetimes");sql.execSQL("DROP TABLE retention_settings");sql.version=5
        }
        FamilyEvidence(ctx).use { db ->
            assertEquals(1,db.retentionPreview("f",now).legacyCount)
            db.pruneExpired("f",now)
            assertEquals("private retention content",db.find("f","old")!!.getString("text"))
            assertEquals("cipher",db.due("f",Long.MAX_VALUE).single().wire)
            db.applyRetentionToLegacy("f",now);db.pruneExpired("f",now)
            assertNull(db.find("f","old"));assertTrue(db.hasSeen("f","old"))
        }
    }

    @Test fun expiredLifetimeRowsAreRemovedOnceTheirOutboxRowsAreGoneButReplayStaysBlocked() {
        val now=System.currentTimeMillis()
        FamilyEvidence(ctx).use { db ->
            db.enqueue("f","gone:0","cipher")
            fun lifetimes()=db.readableDatabase.rawQuery("SELECT COUNT(*) FROM outbox_lifetimes WHERE scope='f'",null).use{it.moveToFirst();it.getInt(0)}
            assertEquals(1,lifetimes())
            db.pruneExpired("f",now+4*day)
            assertEquals(0,db.pendingCount("f"))
            assertEquals("no lifetime row outlives its outbox row after expiry",0,lifetimes())
            db.enqueue("f","gone:0","replayed cipher")
            assertEquals("the same event cannot be requeued",0,db.pendingCount("f"))
        }
    }
}
