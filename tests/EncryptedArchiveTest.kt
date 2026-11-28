package app.family.igguard

import android.app.Application
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
class EncryptedArchiveTest {
    private val ctx:Application get()=RuntimeEnvironment.getApplication()
    private val partner="private_partner_4918"
    private val account="private_account_5829"
    private val body="private message identifier 100%_done 中文🙂"
    @Before fun reset(){ctx.deleteDatabase("family_evidence.db")}
    private fun record(i:Int,text:String=body)=ConversationCapture.record(
        CapturedText(partner,text,"incoming",account=account,messageTime=1000L+i),2000L+i,true,false)
        .put("event_id","opaque-$i")
    private fun legacyParts()=AlertFrames.split(record(2,body.repeat(150)))

    private fun legacy(version:Int) {
        val path=ctx.getDatabasePath("family_evidence.db");path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path,null).use { db ->
            db.rawQuery("PRAGMA secure_delete=OFF",null).use{it.moveToFirst()}
            db.execSQL("CREATE TABLE evidence(scope TEXT,id TEXT,sender TEXT,reason TEXT,stamp INTEGER,body TEXT,payload TEXT,saved INTEGER DEFAULT 0,help_seen INTEGER DEFAULT 0,legacy INTEGER DEFAULT 0,PRIMARY KEY(scope,id))")
            db.execSQL("CREATE TABLE seen(scope TEXT,id TEXT,PRIMARY KEY(scope,id))")
            db.execSQL("CREATE TABLE outbox(scope TEXT,id TEXT,event_id TEXT,wire TEXT,receipt INTEGER DEFAULT 0,accepted INTEGER DEFAULT 0,attempts INTEGER DEFAULT 0,retry_at INTEGER DEFAULT 0,PRIMARY KEY(scope,id))")
            db.execSQL("CREATE TABLE fragments(scope TEXT,id TEXT,part INTEGER,parts INTEGER,digest TEXT,data TEXT,PRIMARY KEY(scope,id,part))")
            if(version>=2) {
                for((name,type) in listOf("conversation_key" to "TEXT","conversation_label" to "TEXT","account_label" to "TEXT","direction" to "TEXT","review_state" to "TEXT","kind" to "TEXT","actionable" to "INTEGER","capture_order" to "INTEGER"))db.execSQL("ALTER TABLE evidence ADD COLUMN $name $type")
                db.execSQL("ALTER TABLE outbox ADD COLUMN priority INTEGER DEFAULT 1")
                db.execSQL("CREATE TABLE capture_seen(scope TEXT,id TEXT,PRIMARY KEY(scope,id))")
                db.execSQL("CREATE TABLE capture_windows(scope TEXT,thread TEXT,tokens TEXT,updated INTEGER,PRIMARY KEY(scope,thread))")
            }
            if(version>=3) {
                db.execSQL("ALTER TABLE evidence ADD COLUMN sort_stamp INTEGER DEFAULT 0")
                db.execSQL("ALTER TABLE evidence ADD COLUMN sort_order INTEGER DEFAULT 0")
                db.execSQL("ALTER TABLE capture_windows ADD COLUMN anchor INTEGER DEFAULT 0")
                db.execSQL("ALTER TABLE capture_windows ADD COLUMN start_order INTEGER DEFAULT 0")
            }
            if(version>=4) {
                db.execSQL("ALTER TABLE outbox ADD COLUMN role TEXT DEFAULT 'child'")
                db.execSQL("ALTER TABLE outbox ADD COLUMN relay_kind TEXT DEFAULT 'urgent'")
                db.execSQL("ALTER TABLE outbox ADD COLUMN relay_version INTEGER DEFAULT 0")
            }
            val payload=record(1)
            db.execSQL("INSERT INTO evidence(scope,id,sender,reason,stamp,body,payload,saved,help_seen,legacy) VALUES(?,?,?,?,?,?,?,?,?,?)",arrayOf("family","opaque-1",partner,"stranger_message",1001,body,payload.toString(),1,1,1))
            db.execSQL("INSERT INTO seen VALUES('family','opaque-1')")
            db.execSQL("INSERT INTO outbox(scope,id,event_id,wire,receipt,accepted,attempts,retry_at) VALUES('family','queued:0','queued','e2ee-wire',1,1,3,5000)")
            val part=legacyParts().first()
            db.execSQL("INSERT INTO fragments VALUES(?,?,?,?,?,?)",arrayOf("family","opaque-2",0,part.getInt("parts"),part.getString("digest"),part.getString("data")))
            if(version>=2) {
                db.execSQL("UPDATE evidence SET conversation_key=?,conversation_label=?,account_label=?,direction='incoming',review_state='reviewed',kind='message',actionable=1,capture_order=7",arrayOf(payload.getString("conversation_key"),partner,account))
                db.execSQL("INSERT INTO capture_windows(scope,thread,tokens,updated) VALUES(?,?,?,1000)",arrayOf("family","private window thread", "[\"private window content\"]"))
                db.execSQL("INSERT INTO capture_seen VALUES('family','capture-id')")
            }
            if(version>=3)db.execSQL("UPDATE evidence SET sort_stamp=1001,sort_order=7")
            db.execSQL("CREATE TABLE discarded(body TEXT)")
            db.execSQL("INSERT INTO discarded VALUES(?)",arrayOf(body.repeat(300)))
            db.execSQL("DROP TABLE discarded")
            db.version=version
        }
    }

    private fun assertNoPlaintext(vararg secrets:String) {
        val path=ctx.getDatabasePath("family_evidence.db")
        for(file in path.parentFile!!.listFiles()!!.filter{it.name.startsWith(path.name)}) {
            val bytes=file.readBytes().toString(Charsets.ISO_8859_1)
            for(secret in secrets)assertFalse("${file.name} exposes $secret",bytes.contains(secret.toByteArray(Charsets.UTF_8).toString(Charsets.ISO_8859_1)))
        }
    }

    @Test fun migrationRemovesPlaintextIncludingFreelistAndPreservesEverySupportedVersion() {
        for(version in 1..4) {
            ctx.deleteDatabase("family_evidence.db");legacy(version)
            FamilyEvidence(ctx).use { db ->
                val row=db.find("family","opaque-1")!!
                assertEquals(body,row.getString("text"));assertTrue(row.getBoolean("_saved"));assertTrue(row.getBoolean("_legacy_partial"))
                assertTrue(db.hasSeen("family","opaque-1"))
                assertEquals("reviewed".takeIf{version>=2}?:"new",row.getString("_review_state"))
                val summary=db.conversations("family",EvidenceQuery(section="conversations")).single()
                assertEquals(partner,summary.label)
                assertEquals(if(version>=2)record(1).getString("conversation_key") else "legacy:$partner",summary.key)
                assertEquals(body,db.messages("family",EvidenceQuery(text="100%_done")).single().getString("text"))
                assertEquals("e2ee-wire",db.due("family",Long.MAX_VALUE).single().wire)
                assertTrue(db.due("family",Long.MAX_VALUE).single().waitForReceipt)
                if(version>=2)assertEquals(listOf("private window content"),db.captureWindowState("family","private window thread").tokens)
                assertTrue(db.hasFragment("family","opaque-2",0,legacyParts().first().getString("digest")))
                assertEquals(1,db.incompleteCount("family"))
                db.readableDatabase.rawQuery("SELECT accepted,attempts,retry_at FROM outbox",null).use {
                    assertTrue(it.moveToFirst());assertEquals(1,it.getInt(0));assertEquals(3,it.getInt(1));assertEquals(5000L,it.getLong(2))
                }
            }
            assertNoPlaintext(body,partner,account,"private window thread","private window content",legacyParts().first().getString("data"))
            FamilyEvidence(ctx).use { db ->
                var assembled:JSONObject?=null
                for(part in legacyParts().drop(1))assembled=db.fragment("family",part)?:assembled
                assertEquals(body.repeat(150),assembled!!.getString("text"))
                if(version>=2)assertFalse(db.enqueueCaptured("family","capture-id",listOf("capture-id:0" to "e2ee-wire"),true,0))
            }
        }
    }

    @Test fun encryptedSearchPagesMatchesAndReturnsRawKeysAndLabelsAfterReopen() {
        val key=record(1).getString("conversation_key")
        FamilyEvidence(ctx).use { db ->
            for(i in 0..94)db.append("family","opaque-$i",record(i,if(i%3==0)body else "ordinary text"))
        }
        FamilyEvidence(ctx).use { db ->
            val query=EvidenceQuery(text="100%_done",conversationKey=key)
            val first=db.messages("family",query,30)
            val second=db.messages("family",query.copy(page=1),30)
            assertEquals((93 downTo 6 step 3).map{"opaque-$it"},first.map{it.getString("_id")})
            assertEquals(listOf("opaque-3","opaque-0"),second.map{it.getString("_id")})
            assertEquals(key,first.first().getString("_conversation_key"));assertEquals(partner,first.first().getString("_conversation_label"))
            val summary=db.conversations("family",query).single()
            assertEquals(32,summary.count);assertEquals(key,summary.key);assertEquals(partner,summary.label);assertEquals(account,summary.account);assertEquals(body,summary.preview)
            assertEquals(31,db.messages("family",EvidenceQuery(sender=partner,text=partner)).size)
        }
        assertNoPlaintext(body,partner,account,key)
    }

    @Test fun fragmentsAndCaptureWindowsStayEncryptedAcrossScopeRotation() {
        val payload=record(2,body.repeat(150))
        val parts=AlertFrames.split(payload)
        val thread="private raw capture thread"
        FamilyEvidence(ctx).use { db ->
            assertNull(db.fragment("old",parts.first()))
            db.setCaptureWindow("old",thread,listOf("private capture token"),2000,-7)
            db.append("old","opaque-1",record(1));db.save("old","opaque-1",true)
            db.rotate("old","new"){it}
            assertEquals(CaptureWindow(listOf("private capture token"),2000,-7),db.captureWindowState("new",thread))
            assertEquals(body,db.find("new","opaque-1")!!.getString("text"))
            assertTrue(db.find("new","opaque-1")!!.getBoolean("_saved"))
            assertNull(db.find("old","opaque-1"))
        }
        assertNoPlaintext(body,partner,account,thread,"private capture token",parts.first().getString("data"))
        FamilyEvidence(ctx).use { db ->
            var assembled:JSONObject?=null
            for(part in parts.drop(1))assembled=db.fragment("new",part)?:assembled
            assertEquals(payload.getString("text"),assembled!!.getString("text"))
            assertEquals(listOf("private capture token"),db.captureWindowState("new",thread).tokens)
        }
    }

    @Test fun tamperedCiphertextAndMovingRowsWithoutReencryptionFailClosed() {
        FamilyEvidence(ctx).use { db ->
            db.append("family","opaque-1",record(1))
            db.writableDatabase.execSQL("UPDATE evidence SET payload='igv1:invalid' WHERE id='opaque-1'")
            try {db.find("family","opaque-1");fail("Corrupt ciphertext must fail closed")}catch(_:SecurityException){}
            assertTrue(db.hasSeen("family","opaque-1"))
            db.append("family","opaque-2",record(2))
            db.writableDatabase.execSQL("UPDATE evidence SET scope='other' WHERE id='opaque-2'")
            try {db.find("other","opaque-2");fail("Scope is authenticated")}catch(_:SecurityException){}
            assertEquals(2,db.readableDatabase.rawQuery("SELECT COUNT(*) FROM evidence",null).use{it.moveToFirst();it.getInt(0)})
        }
    }

    @Test fun failedRotationRollsBackArchiveAndQueuedFramesTogether() {
        FamilyEvidence(ctx).use { db ->
            db.append("old","opaque-1",record(1));db.enqueue("old","queued:0","e2ee-wire")
            db.writableDatabase.execSQL("UPDATE evidence SET payload='igv1:invalid' WHERE id='opaque-1'")
            try {db.rotate("old","new"){"rekeyed-wire"};fail("Rotation must authenticate the archive")}catch(_:SecurityException){}
            assertEquals("e2ee-wire",db.due("old",Long.MAX_VALUE).single().wire)
            assertTrue(db.due("new",Long.MAX_VALUE).isEmpty())
            assertEquals(1,db.otherArchiveCount("new"));assertTrue(db.hasSeen("old","opaque-1"))
        }
    }

    @Test fun committedMigrationCleanupIsRetriedOnNextOpen() {
        legacy(4)
        FamilyEvidence(ctx).use {assertEquals(body,it.find("family","opaque-1")!!.getString("text"))}
        val path=ctx.getDatabasePath("family_evidence.db")
        SQLiteDatabase.openOrCreateDatabase(path,null).use { db ->
            db.rawQuery("PRAGMA journal_mode=WAL",null).use{it.moveToFirst()}
            db.rawQuery("PRAGMA secure_delete=OFF",null).use{it.moveToFirst()}
            db.execSQL("CREATE TABLE discarded(body TEXT)")
            db.execSQL("INSERT INTO discarded VALUES(?)",arrayOf("interrupted migration plaintext".repeat(500)))
            db.execSQL("DROP TABLE discarded")
            db.execSQL("INSERT INTO archive_maintenance VALUES(1)")
        }
        FamilyEvidence(ctx).use { db ->
            assertEquals(body,db.find("family","opaque-1")!!.getString("text"))
            assertEquals(0,db.readableDatabase.rawQuery("SELECT COUNT(*) FROM archive_maintenance",null).use{it.moveToFirst();it.getInt(0)})
        }
        assertNoPlaintext(body,partner,account,"interrupted migration plaintext")
    }

    @Test fun unavailableKeyRollsBackLegacyMigrationAndRetainsEncryptedRecords() {
        legacy(4)
        TestKeyStore.failReads=true
        try {
            try {FamilyEvidence(ctx).use{it.writableDatabase};fail("Migration requires its encryption key")}catch(_:SecurityException){}
        }finally{TestKeyStore.failReads=false}
        SQLiteDatabase.openOrCreateDatabase(ctx.getDatabasePath("family_evidence.db"),null).use { db ->
            assertEquals(4,db.version)
            db.rawQuery("SELECT body,saved,review_state FROM evidence",null).use {
                assertTrue(it.moveToFirst());assertEquals(body,it.getString(0));assertEquals(1,it.getInt(1));assertEquals("reviewed",it.getString(2))
            }
        }
        FamilyEvidence(ctx).use {assertEquals(body,it.find("family","opaque-1")!!.getString("text"))}
        TestKeyStore.failReads=true
        try {
            try {FamilyEvidence(ctx).use{it.find("family","opaque-1")};fail("Unreadable records must remain encrypted")}catch(_:SecurityException){}
        }finally{TestKeyStore.failReads=false}
        FamilyEvidence(ctx).use {assertEquals(body,it.find("family","opaque-1")!!.getString("text"))}
        assertNoPlaintext(body,partner,account)
    }

    @Test fun rotationDoesNotSkipRowsBeyondOneSqliteCursorWindow() {
        val large="wide visible text ".repeat(7500)
        FamilyEvidence(ctx).use { db ->
            for(i in 0..19)db.append("old","opaque-$i",record(i,large))
            db.rotate("old","new"){it}
            assertEquals(20,db.messages("new",EvidenceQuery()).size)
            for(i in 0..19)assertEquals(large,db.find("new","opaque-$i")!!.getString("text"))
            assertEquals(0,db.otherArchiveCount("new"))
        }
    }

    @Test fun missingIndexKeyDoesNotRegenerateIndexesOrHideExistingArchive() {
        FamilyEvidence(ctx).use {it.append("family","opaque-1",record(1))}
        val alias="ig_guard.local_storage.index.v1"
        val queries=listOf<(FamilyEvidence)->Any>(
            {it.messages("family",EvidenceQuery())},
            {it.messages("family",EvidenceQuery(sender=partner))},
            {it.messages("family",EvidenceQuery(conversationKey=record(1).getString("conversation_key")))},
            {it.captureWindowState("family","thread")})
        for(query in queries) {
            val key=TestKeyStore.keys.remove(alias)!!
            try {
                try {FamilyEvidence(ctx).use{query(it)};fail("Existing archive requires the original index key")}catch(_:SecurityException){}
                assertFalse(TestKeyStore.keys.containsKey(alias))
            }finally{TestKeyStore.keys[alias]=key}
        }
        FamilyEvidence(ctx).use {assertEquals(body,it.find("family","opaque-1")!!.getString("text"))}
    }

    @Test fun encryptionOverheadDoesNotMakeLargeValidMessagesUnreadable() {
        val large="x".repeat(800_000)
        FamilyEvidence(ctx).use { db ->
            db.append("old","opaque-1",record(1,large))
            assertEquals(large,db.find("old","opaque-1")!!.getString("text"))
            assertEquals(large,db.messages("old",EvidenceQuery(text="xxx")).single().getString("text"))
            db.rotate("old","new"){it}
            assertEquals(large,db.conversations("new",EvidenceQuery()).single().preview)
        }
    }
}
