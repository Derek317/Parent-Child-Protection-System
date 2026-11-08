package app.family.igguard

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
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
class ConversationArchiveTest {
    private val ctx:Application get()=RuntimeEnvironment.getApplication()
    @Before fun reset(){ctx.deleteDatabase("family_evidence.db");ctx.getSharedPreferences("ig_guard_prefs",Context.MODE_PRIVATE).edit().clear().commit()}
    private fun record(i:Int,partner:String="friend",direction:String="incoming",text:String="text $i",account:String="child")=
        ConversationCapture.record(CapturedText(partner,text,direction,author=if(direction=="outgoing")"child" else partner,account=account,messageTime=1000L+i),2000L+i,true,false).put("event_id","message-$i")
    @Test fun versionTwoUpgradeKeepsPayloadReviewBookmarksAndEncryptedQueue(){
        val path=ctx.getDatabasePath("family_evidence.db");path.parentFile!!.mkdirs()
        val original=record(1)
        SQLiteDatabase.openOrCreateDatabase(path,null).use { old ->
            old.execSQL("CREATE TABLE evidence(scope TEXT,id TEXT,sender TEXT,reason TEXT,stamp INTEGER,body TEXT,payload TEXT,saved INTEGER DEFAULT 0,help_seen INTEGER DEFAULT 0,legacy INTEGER DEFAULT 0,conversation_key TEXT,conversation_label TEXT,account_label TEXT,direction TEXT,review_state TEXT,kind TEXT,actionable INTEGER,capture_order INTEGER,PRIMARY KEY(scope,id))")
            old.execSQL("CREATE TABLE seen(scope TEXT,id TEXT,PRIMARY KEY(scope,id))")
            old.execSQL("CREATE TABLE capture_seen(scope TEXT,id TEXT,PRIMARY KEY(scope,id))")
            old.execSQL("CREATE TABLE capture_windows(scope TEXT,thread TEXT,tokens TEXT,updated INTEGER,PRIMARY KEY(scope,thread))")
            old.execSQL("CREATE TABLE fragments(scope TEXT,id TEXT,part INTEGER,parts INTEGER,digest TEXT,data TEXT,PRIMARY KEY(scope,id,part))")
            old.execSQL("CREATE TABLE outbox(scope TEXT,id TEXT,event_id TEXT,wire TEXT,receipt INTEGER DEFAULT 0,accepted INTEGER DEFAULT 0,attempts INTEGER DEFAULT 0,retry_at INTEGER DEFAULT 0,priority INTEGER DEFAULT 1,PRIMARY KEY(scope,id))")
            old.execSQL("INSERT INTO evidence VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",arrayOf("f","old","friend","conversation_context",1001,"text 1",original.toString(),1,1,0,original.getString("conversation_key"),"friend","child","incoming","reviewed","message",0,7))
            old.execSQL("INSERT INTO outbox(scope,id,event_id,wire,receipt) VALUES('f','pending:0','pending','ciphertext',1)")
            old.execSQL("INSERT INTO capture_windows VALUES('f','thread','[\"hash\"]',1000)")
            old.version=2
        }
        FamilyEvidence(ctx).use { db ->
            val row=db.timeline("f",EvidenceQuery(conversationKey=original.getString("conversation_key"))).single()
            assertEquals("text 1",row.getString("text"));assertTrue(row.getBoolean("_saved"))
            assertEquals("reviewed",row.getString("_review_state"));assertEquals(1001,row.getLong("_sort_stamp"));assertEquals(7,row.getLong("_sort_order"))
            assertEquals("ciphertext",db.due("f",Long.MAX_VALUE).single().wire)
            assertEquals(listOf("hash"),db.captureWindow("f","thread"));assertEquals(0,db.captureWindowState("f","thread").anchor)
        }
    }
    @Test fun negativeScreenPositionsPageStablyAndRotationPreservesWindowAnchors(){
        val key=ConversationCapture.key("child","friend")
        FamilyEvidence(ctx).use{db->
            for(i in -40..24) {
                val r=ConversationCapture.record(CapturedText("friend","position $i","incoming",account="child"),4000,true,false)
                    .put("screen_anchor_ms",2000).put("screen_order",i)
                db.append("f","position-$i",r)
            }
            val first=db.timeline("f",EvidenceQuery(conversationKey=key),30)
            val oldest=first.first()
            val before=EvidenceCursor(oldest.getLong("_sort_stamp"),oldest.getLong("_sort_order"),oldest.getString("_id"))
            val older=db.timeline("f",EvidenceQuery(conversationKey=key,before=before),30)
            assertEquals((-5..24).map{"position $it"},first.map{it.getString("text")})
            assertEquals((-35..-6).map{"position $it"},older.map{it.getString("text")})
            db.setCaptureWindow("f",key,listOf("a","b"),2000,-1)
            db.rotate("f","new"){it}
            assertEquals(CaptureWindow(listOf("a","b"),2000,-1),db.captureWindowState("new",key))
            assertEquals(65,db.conversations("new",EvidenceQuery(section="conversations")).single().count)
        }
    }
    @Test fun bothAuthorsShareAConversationAndTimelineUsesSourceTimeWithStablePaging() {
        FamilyEvidence(ctx).use { db ->
            for(i in (0..64).reversed())db.append("family","message-$i",record(i,direction=if(i%2==0)"incoming" else "outgoing"))
            val list=db.conversations("family",EvidenceQuery(section="conversations"))
            assertEquals(1,list.size);assertEquals(65,list[0].count)
            val first=db.timeline("family",EvidenceQuery(conversationKey=list[0].key),30)
            val older=db.timeline("family",EvidenceQuery(conversationKey=list[0].key,page=1),30)
            assertEquals((35..64).map{"message-$it"},first.map{it.getString("_id")})
            assertEquals((5..34).map{"message-$it"},older.map{it.getString("_id")})
            assertEquals(60,(first+older).map{it.getString("_id")}.distinct().size)
        }
    }
    @Test fun latestConversationFirstAndAccountsDoNotMerge() {
        FamilyEvidence(ctx).use { db ->
            db.append("f","message-1",record(1));db.append("f","message-2",record(2,"another"))
            db.append("f","message-3",record(3,account="other_child"))
            val rows=db.conversations("f",EvidenceQuery(section="conversations"))
            assertEquals(3,rows.size);assertEquals("other_child",rows.first().account)
            assertEquals("another",rows[1].label)
            assertTrue(db.conversations("other-family",EvidenceQuery()).isEmpty())
        }
    }
    @Test fun reviewIsPerActionableEventAndNewArrivalsRemainNew() {
        FamilyEvidence(ctx).use { db ->
            db.append("f","message-1",record(1,text="send nudes"));db.review("f","message-1","reviewed")
            assertEquals("reviewed",db.find("f","message-1")!!.getString("_review_state"))
            assertTrue(db.conversations("f",EvidenceQuery(section="review",review="new")).isEmpty())
            db.append("f","message-2",record(2,text="don't tell your parents"))
            assertEquals(1,db.conversations("f",EvidenceQuery(section="review",review="new")).first().unreviewed)
            db.append("f","message-3",record(3));db.review("f","message-3","follow_up")
            assertEquals("new",db.find("f","message-3")!!.getString("_review_state"))
        }
    }
    @Test fun ordinaryCaptureNeverCountsAsAnAlertAndLongCapturedMessagesStillReassemble() {
        val ordinary=record(1);val state=JSONObject();val stats=AlertStatistics(state,5000)
        stats.record("one",ordinary);assertEquals(0L,stats.dailyTotals().sum())
        val long=record(2,text="廣東話🙂\n".repeat(700))
        val frames=AlertFrames.split(long);assertTrue(frames.size>1)
        FamilyEvidence(ctx).use { db ->
            var assembled:JSONObject?=null
            for(frame in frames.reversed())assembled=db.fragment("f",frame)?:assembled
            assertEquals(long.getString("text"),assembled!!.getString("text"))
        }
    }
    @Test fun captureIdentityAndSnapshotAreDurableWithoutPlaintextCaptureStorage() {
        FamilyEvidence(ctx).use { db ->
            assertTrue(db.enqueueCaptured("f","id",listOf("id:0" to "encrypted-wire"),true,0))
            db.acknowledgeEvent("f","id")
            assertFalse(db.enqueueCaptured("f","id",listOf("id:0" to "encrypted-wire"),true,0))
            db.setCaptureWindow("f","thread",listOf("hash-a","hash-b"))
        }
        FamilyEvidence(ctx).use { assertEquals(listOf("hash-a","hash-b"),it.captureWindow("f","thread")) }
    }
    @Test fun upgradeFromVersionOneKeepsOriginalPayloadBookmarkAndPendingCiphertext() {
        val path=ctx.getDatabasePath("family_evidence.db");path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path,null).use { old ->
            old.execSQL("CREATE TABLE evidence(scope TEXT NOT NULL,id TEXT NOT NULL,sender TEXT NOT NULL,reason TEXT NOT NULL,stamp INTEGER NOT NULL,body TEXT NOT NULL,payload TEXT NOT NULL,saved INTEGER NOT NULL DEFAULT 0,help_seen INTEGER NOT NULL DEFAULT 0,legacy INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(scope,id))")
            old.execSQL("CREATE TABLE seen(scope TEXT NOT NULL,id TEXT NOT NULL,PRIMARY KEY(scope,id))")
            old.execSQL("CREATE TABLE outbox(scope TEXT NOT NULL,id TEXT NOT NULL,event_id TEXT NOT NULL,wire TEXT NOT NULL,receipt INTEGER NOT NULL DEFAULT 0,accepted INTEGER NOT NULL DEFAULT 0,attempts INTEGER NOT NULL DEFAULT 0,retry_at INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(scope,id))")
            old.execSQL("CREATE TABLE fragments(scope TEXT NOT NULL,id TEXT NOT NULL,part INTEGER NOT NULL,parts INTEGER NOT NULL,digest TEXT NOT NULL,data TEXT NOT NULL,PRIMARY KEY(scope,id,part))")
            val payload=JSONObject().put("type","alert").put("reason","stranger_message").put("sender","friend").put("text","Original text").toString()
            old.execSQL("INSERT INTO evidence VALUES(?,?,?,?,?,?,?,?,?,?)",arrayOf("f","old-id","friend","stranger_message",1000,"Original text",payload,1,1,0))
            old.execSQL("INSERT INTO outbox(scope,id,event_id,wire,receipt) VALUES('f','pending:0','pending','ciphertext',1)")
            old.version=1
        }
        FamilyEvidence(ctx).use { db ->
            val old=db.find("f","old-id")!!
            assertEquals("Original text",old.getString("text"));assertTrue(old.getBoolean("_saved"))
            assertEquals("unknown",old.getString("direction"))
            assertEquals("friend",db.conversations("f",EvidenceQuery(section="conversations")).single().label)
            assertEquals("ciphertext",db.due("f",Long.MAX_VALUE).single().wire)
            assertTrue(db.due("f",Long.MAX_VALUE).single().waitForReceipt)
        }
    }
    @Test fun equalTimestampsUseObservedCaptureOrderRatherThanRandomEventIds(){
        FamilyEvidence(ctx).use { db ->
            val first=record(1).put("event_id","z-first").put("message_time_ms",1000).put("ts_ms",1000).put("capture_order",1)
            val second=record(2,direction="outgoing").put("event_id","a-second").put("message_time_ms",1000).put("ts_ms",1000).put("capture_order",2)
            db.append("f","a-second",second);db.append("f","z-first",first)
            assertEquals(listOf("z-first","a-second"),db.timeline("f",EvidenceQuery(conversationKey=first.getString("conversation_key"))).map{it.getString("_id")})
        }
    }
}
