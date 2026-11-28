package app.family.igguard

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale

data class EvidenceQuery(val text: String = "", val filter: String = "all", val sender: String? = null,
    val savedOnly: Boolean = false, val since: Long = 0, val page: Int = 0,
    val conversationKey:String?=null,val section:String="all",val review:String="all",val direction:String="all",val before:EvidenceCursor?=null)
data class EvidenceCursor(val stamp:Long,val order:Long,val id:String)
data class ConversationSummary(val key:String,val label:String,val account:String,val count:Int,val unreviewed:Int,val latest:Long,val preview:String,val categories:List<String> = emptyList(),val kind:String="unknown",val unidentified:Boolean=false,val contentFree:Boolean=false)
data class PendingEvidence(val id: String, val wire: String, val waitForReceipt: Boolean, val kind:String="urgent")
data class CaptureWindow(val tokens:List<String> = emptyList(),val anchor:Long=0,val start:Long=0)
data class RetentionPreview(val retentionDays:Int,val legacyCount:Int,val legacyExpiredCount:Int,
    val expiredCount:Int,val pendingExpiredCount:Int,val preservedCount:Int)

/** Content is encrypted; scopes, opaque IDs, timestamps, reasons and workflow flags remain clear. */
class FamilyEvidence(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "family_evidence.db", null, 11), java.io.Closeable {
    companion object {
        private val openLock=Any()
        private const val DAY=86_400_000L
        private const val PENDING_TTL=3*DAY
        @Volatile internal var contentLookups=0
        private const val SEEN_KEEP=2*FamilySecurity.CONTENT_RECORD_WINDOW_MS
    }
    private val app=ctx.applicationContext
    private val privateColumns=listOf("sender","conversation_key","conversation_label","account_label","body","payload")
    // Fetch the duplicate body separately so encrypted 1 MiB events still fit a CursorWindow.
    private val evidenceColumns="scope,id,sender,reason,stamp,payload,saved,help_seen,legacy,conversation_key,conversation_label,account_label,direction,review_state,kind,actionable,capture_order,sort_stamp,sort_order,sender_index,conversation_index"
    private val searchColumns=evidenceColumns.replace("payload","body")
    private fun purpose(scope:String,table:String,id:String,column:String):String = org.json.JSONArray(listOf("archive",scope,table,id,column)).toString()
    private fun seal(scope:String,table:String,id:String,column:String,value:String):String = LocalVault.seal(app,purpose(scope,table,id,column),value)
    private fun open(scope:String,table:String,id:String,column:String,value:String):String = LocalVault.open(app,purpose(scope,table,id,column),value)
    private fun index(scope:String,column:String,value:String):String {
        LocalVault.requireArchiveKeys(app)
        return LocalVault.blindIndex(app,purpose(scope,"index","",column),value)
    }
    private fun Cursor.string(column:String):String = getString(getColumnIndexOrThrow(column))
    private fun Cursor.number(column:String):Long = getLong(getColumnIndexOrThrow(column))
    private fun Cursor.content(column:String):String {
        val value=if(column in setOf("body","payload") && getColumnIndex(column)<0)readableDatabase.rawQuery(
            "SELECT $column FROM evidence WHERE scope=? AND id=?",arrayOf(string("scope"),string("id"))).use {
            contentLookups++;check(it.moveToFirst());it.getString(0)
        } else string(column)
        return open(string("scope"),"evidence",string("id"),column,value)
    }
    private fun protect(scope:String,id:String,values:ContentValues):ContentValues = values.apply {
        put("sender_index",index(scope,"sender",getAsString("sender")))
        put("conversation_index",index(scope,"conversation",getAsString("conversation_key")))
        for(column in privateColumns)put(column,seal(scope,"evidence",id,column,getAsString(column)))
    }
    // Short-lived helpers share a creation/migration gate, not the entire query lifetime.
    override fun getWritableDatabase():SQLiteDatabase = synchronized(openLock){super.getWritableDatabase()}
    override fun getReadableDatabase():SQLiteDatabase = synchronized(openLock){super.getReadableDatabase()}
    override fun onConfigure(db:SQLiteDatabase) {
        super.onConfigure(db)
        db.rawQuery("PRAGMA secure_delete=ON",null).use{it.moveToFirst()}
    }
    override fun onOpen(db:SQLiteDatabase) {
        super.onOpen(db)
        LocalVault.requireArchiveKeys(app)
        if(db.rawQuery("SELECT 1 FROM archive_maintenance WHERE pending=1",null).use{it.moveToFirst()}) {
            // The committed marker retries cleanup if migration or compaction is interrupted.
            checkpoint(db)
            db.execSQL("VACUUM")
            checkpoint(db)
            db.delete("archive_maintenance","pending=1",null)
        }
    }
    private fun checkpoint(db:SQLiteDatabase) {
        db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)",null).use {
            if(it.moveToFirst() && it.getInt(0)!=0)throw android.database.sqlite.SQLiteException("Archive cleanup is busy")
        }
    }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE evidence(scope TEXT NOT NULL,id TEXT NOT NULL,sender TEXT NOT NULL,reason TEXT NOT NULL,stamp INTEGER NOT NULL,body TEXT NOT NULL,payload TEXT NOT NULL,saved INTEGER NOT NULL DEFAULT 0,help_seen INTEGER NOT NULL DEFAULT 0,legacy INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(scope,id))")
        db.execSQL("CREATE INDEX evidence_time ON evidence(scope,stamp DESC,id DESC)")
        db.execSQL("CREATE INDEX evidence_sender ON evidence(scope,sender,stamp DESC)")
        db.execSQL("CREATE INDEX evidence_advice ON evidence(scope,reason,help_seen,stamp DESC,id DESC)")
        db.execSQL("CREATE INDEX evidence_saved ON evidence(scope,saved,stamp DESC)")
        // Content-free tombstones prevent deleted records being resurrected by a cached relay replay.
        db.execSQL("CREATE TABLE seen(scope TEXT NOT NULL,id TEXT NOT NULL,stored_at INTEGER,PRIMARY KEY(scope,id))")
        db.execSQL("CREATE TABLE outbox(scope TEXT NOT NULL,id TEXT NOT NULL,event_id TEXT NOT NULL,wire TEXT NOT NULL,receipt INTEGER NOT NULL DEFAULT 0,accepted INTEGER NOT NULL DEFAULT 0,attempts INTEGER NOT NULL DEFAULT 0,retry_at INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(scope,id))")
        db.execSQL("CREATE TABLE fragments(scope TEXT NOT NULL,id TEXT NOT NULL,part INTEGER NOT NULL,parts INTEGER NOT NULL,digest TEXT NOT NULL,data TEXT NOT NULL,PRIMARY KEY(scope,id,part))")
        upgradeConversationSchema(db)
        upgradeScreenOrder(db)
        upgradeRelayOutbox(db)
        encryptedSchema(db)
        retentionSchema(db)
        riskSchema(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if(oldVersion<2)upgradeConversationSchema(db)
        if(oldVersion<3)upgradeScreenOrder(db)
        if(oldVersion<4)upgradeRelayOutbox(db)
        if(oldVersion<5) {
            encryptedSchema(db)
            transformRows(db,"evidence") { c ->
                protect(c.string("scope"),c.string("id"),ContentValues().apply{for(column in privateColumns)put(column,c.string(column))})
            }
            transformRows(db,"fragments") { c ->
                ContentValues().apply{
                    put("data",seal(c.string("scope"),"fragments",c.string("id"),c.number("part").toString(),c.string("data")))
                }
            }
            transformRows(db,"capture_windows") { c ->
                protectWindow(c.string("scope"),c.string("thread"),c.string("tokens"))
            }
            db.execSQL("INSERT INTO archive_maintenance VALUES(1)")
        }
        if(oldVersion<6)retentionSchema(db)
        if(oldVersion<10)riskSchema(db)   // 10: the risk_store(scope,stamp) index for the 14-day cleanup
        if(oldVersion<11)addColumn(db,"seen","stored_at","INTEGER")
        if(oldVersion<9) {
            // Before schema 9 a thread was keyed by account, label and kind and risk_unknown meant "counted, not stored".
            // From 9 a thread is keyed by its label alone and risk_unknown means "set aside as a group", so nothing carries over.
            if(oldVersion in 7..8) {
                for(table in riskChildTables)db.delete(table,null,null)
                // Counts and results are gone: say so to the parent at the next heartbeat or start.
                LocalVault.preferences(app).edit().remove("scores_last_scored").remove("scores_last_review").putBoolean("scores_report_due",true).commit()
            }
        }
    }
    private fun retentionSchema(db:SQLiteDatabase) {
        // NULL marks existing archives for preview and explicit adoption, never first-open erasure.
        db.execSQL("ALTER TABLE evidence ADD COLUMN retention_base INTEGER")
        db.execSQL("ALTER TABLE evidence ADD COLUMN expires INTEGER")
        db.execSQL("ALTER TABLE evidence ADD COLUMN preserved_until INTEGER")
        db.execSQL("ALTER TABLE fragments ADD COLUMN expires INTEGER")
        db.execSQL("CREATE TABLE retention_settings(scope TEXT PRIMARY KEY,days INTEGER NOT NULL)")
        // Content-free event deadlines survive acknowledgments, retries and scope rotation.
        db.execSQL("CREATE TABLE outbox_lifetimes(scope TEXT NOT NULL,id TEXT NOT NULL,expires INTEGER NOT NULL,blocked INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(scope,id))")
        val expiry=System.currentTimeMillis()+PENDING_TTL
        db.execSQL("INSERT INTO outbox_lifetimes(scope,id,expires) SELECT scope,event_id,? FROM outbox GROUP BY scope,event_id",arrayOf(expiry))
        db.execSQL("UPDATE fragments SET expires=?",arrayOf(expiry))
        db.execSQL("CREATE INDEX evidence_expiry ON evidence(scope,expires)")
    }
    private fun retentionDays(scope:String):Int = readableDatabase.rawQuery("SELECT days FROM retention_settings WHERE scope=?",arrayOf(scope)).use{if(it.moveToFirst())it.getInt(0) else 30}
    fun retentionPreview(scope:String,now:Long=System.currentTimeMillis()):RetentionPreview {
        val days=retentionDays(scope)
        return readableDatabase.rawQuery("SELECT COUNT(CASE WHEN expires IS NULL THEN 1 END),COUNT(CASE WHEN expires IS NULL AND stamp<=? THEN 1 END),COUNT(CASE WHEN expires<=? THEN 1 END),COUNT(CASE WHEN preserved_until>? THEN 1 END) FROM evidence WHERE scope=?",
            arrayOf((now-days*DAY).toString(),now.toString(),now.toString(),scope)).use { c ->
            c.moveToFirst()
            val pending=readableDatabase.rawQuery("SELECT COUNT(*) FROM outbox o JOIN outbox_lifetimes l ON l.scope=o.scope AND l.id=o.event_id WHERE o.scope=? AND l.expires<=?",arrayOf(scope,now.toString())).use{it.moveToFirst();it.getInt(0)}
            RetentionPreview(days,c.getInt(0),c.getInt(1),c.getInt(2),pending,c.getInt(3))
        }
    }
    fun applyRetentionToLegacy(scope:String,now:Long=System.currentTimeMillis()):Int {
        val db=writableDatabase
        db.beginTransaction()
        try {
            val count=retentionPreview(scope,now).legacyCount
            db.execSQL("UPDATE evidence SET retention_base=MIN(stamp,?),expires=MIN(stamp,?)+? WHERE scope=? AND expires IS NULL",arrayOf(now,now,retentionDays(scope)*DAY,scope))
            db.setTransactionSuccessful();return count
        }finally{db.endTransaction()}
    }
    fun setRetentionDays(scope:String,days:Int) {
        require(days in 1..30)
        val db=writableDatabase;db.beginTransaction()
        try {
            db.execSQL("INSERT OR REPLACE INTO retention_settings(scope,days) VALUES(?,?)",arrayOf(scope,days))
            db.execSQL("UPDATE evidence SET expires=MIN(expires,retention_base+?) WHERE scope=? AND preserved_until IS NULL",arrayOf(days*DAY,scope))
            db.setTransactionSuccessful()
        }finally{db.endTransaction()}
    }
    fun preserveForReview(scope:String,id:String,now:Long=System.currentTimeMillis()):Boolean {
        val db=writableDatabase
        return db.update("evidence",ContentValues().apply{put("preserved_until",now+90*DAY);put("expires",now+90*DAY)},
            "scope=? AND id=? AND preserved_until IS NULL AND (expires IS NULL OR expires>?)",arrayOf(scope,id,now.toString()))>0
    }
    /** Child: distinct events still waiting for a parent receipt, without [excluded] (heartbeats), and the oldest one's enqueue time. */
    fun receiptWaiting(scope:String,excluded:Set<String>):Pair<Int,Long?> {
        val rows=readableDatabase.rawQuery("SELECT o.event_id,MIN(l.expires) FROM outbox o JOIN outbox_lifetimes l ON l.scope=o.scope AND l.id=o.event_id WHERE o.scope=? AND o.role='child' AND o.receipt=1 GROUP BY o.event_id",arrayOf(scope)).use{c->
            buildList{while(c.moveToNext())add(c.getString(0) to c.getLong(1)-PENDING_TTL)}
        }.filter{it.first !in excluded}
        return rows.size to rows.minOfOrNull{it.second}
    }
    /** When [id] was first queued here (its lifetime started then), or null. */
    fun enqueuedAt(scope:String,id:String):Long? = readableDatabase.rawQuery("SELECT expires FROM outbox_lifetimes WHERE scope=? AND id=?",arrayOf(scope,id))
        .use{if(it.moveToFirst())it.getLong(0)-PENDING_TTL else null}
    fun pruneExpired(scope:String,now:Long=System.currentTimeMillis(),pruneSeen:Boolean=false):Int {
        val db=writableDatabase;db.beginTransaction()
        try {
            // Child: a record that waited for a parent receipt and leaves by its deadline was never confirmed delivered (heartbeats aside).
            val heartbeats=LocalVault.preferences(app).getStringSet(DeliveryHealth.HEARTBEAT_IDS,emptySet()).orEmpty()
            val undelivered=db.rawQuery("SELECT DISTINCT o.event_id FROM outbox o JOIN outbox_lifetimes l ON l.scope=o.scope AND l.id=o.event_id WHERE o.scope=? AND o.role='child' AND o.receipt=1 AND l.expires<=?",arrayOf(scope,now.toString()))
                .use{c->buildList{while(c.moveToNext())add(c.getString(0))}}.any{it !in heartbeats}
            if(undelivered)check(LocalVault.preferences(app).edit().putLong(DeliveryHealth.EXPIRED_AT_MS,now).commit())
            db.execSQL("INSERT OR IGNORE INTO seen(scope,id) SELECT scope,id FROM evidence WHERE scope=? AND expires<=?",arrayOf(scope,now))
            db.execSQL("INSERT OR IGNORE INTO seen(scope,id) SELECT scope,id FROM fragments WHERE scope=? AND expires<=?",arrayOf(scope,now))
            db.execSQL("INSERT OR IGNORE INTO capture_seen SELECT scope,id FROM outbox_lifetimes WHERE scope=? AND expires<=?",arrayOf(scope,now))
            db.execSQL("INSERT OR IGNORE INTO seen(scope,id) SELECT l.scope,l.id FROM outbox_lifetimes l WHERE l.scope=? AND l.expires<=? AND NOT EXISTS(SELECT 1 FROM evidence e WHERE e.scope=l.scope AND e.id=l.id)",arrayOf(scope,now))
            db.execSQL("UPDATE outbox_lifetimes SET expires=MIN(expires,?) WHERE scope=? AND id IN (SELECT id FROM evidence WHERE scope=? AND expires<=? UNION SELECT id FROM fragments WHERE scope=? AND expires<=?)",arrayOf(now,scope,scope,now,scope,now))
            db.execSQL("UPDATE outbox_lifetimes SET blocked=1 WHERE scope=? AND expires<=?",arrayOf(scope,now))
            val evidence=db.delete("evidence","scope=? AND expires<=?",arrayOf(scope,now.toString()))
            val fragments=db.delete("fragments","scope=? AND (expires<=? OR id IN (SELECT id FROM seen WHERE scope=? AND id NOT IN (SELECT id FROM evidence WHERE scope=?)))",arrayOf(scope,now.toString(),scope,scope))
            val outbox=db.delete("outbox","scope=? AND event_id IN (SELECT id FROM outbox_lifetimes WHERE scope=? AND expires<=?)",arrayOf(scope,scope,now.toString()))
            // An expired lifetime row is only a guard while its outbox row exists; the seen tombstone written above keeps replay blocked.
            db.execSQL("DELETE FROM outbox_lifetimes WHERE scope=? AND expires<=? AND NOT EXISTS(SELECT 1 FROM outbox o WHERE o.scope=outbox_lifetimes.scope AND o.event_id=outbox_lifetimes.id) AND EXISTS(SELECT 1 FROM capture_seen c WHERE c.scope=outbox_lifetimes.scope AND c.id=outbox_lifetimes.id)",arrayOf(scope,now))
            val windows=db.delete("capture_windows",if(evidence+fragments+outbox>0)"scope=?" else "scope=? AND updated<=?",
                if(evidence+fragments+outbox>0)arrayOf(scope) else arrayOf(scope,(now-PENDING_TTL).toString()))
            if(pruneSeen) {
                db.execSQL("UPDATE seen SET stored_at=? WHERE scope=? AND stored_at IS NULL",arrayOf(now,scope))
                db.execSQL("DELETE FROM seen WHERE scope=? AND stored_at<? AND NOT EXISTS(SELECT 1 FROM evidence e WHERE e.scope=seen.scope AND e.id=seen.id)",arrayOf(scope,now-SEEN_KEEP))
            }
            pruneRisk(db,scope,now)
            db.setTransactionSuccessful();return evidence+fragments+outbox+windows
        }finally{db.endTransaction()}
    }
    private fun transformRows(db:SQLiteDatabase,table:String,scope:String?=null,transform:(Cursor)->ContentValues) {
        var after=Long.MIN_VALUE
        while(true) {
            // Close each bounded cursor before changing indexed values or its scope predicate.
            val columns=if(table=="evidence" && scope!=null)evidenceColumns else "*"
            val changes=db.rawQuery("SELECT rowid AS archive_rowid,$columns FROM $table WHERE rowid>?"+
                (if(scope==null)"" else " AND scope=?")+" ORDER BY rowid LIMIT 8",
                if(scope==null)arrayOf(after.toString()) else arrayOf(after.toString(),scope)).use { c ->
                buildList {while(c.moveToNext())add(c.number("archive_rowid") to transform(c))}
            }
            if(changes.isEmpty())return
            for((rowid,values) in changes)db.updateWithOnConflict(table,values,"rowid=?",arrayOf(rowid.toString()),
                if(scope==null)SQLiteDatabase.CONFLICT_ABORT else SQLiteDatabase.CONFLICT_IGNORE)
            after=changes.last().first
        }
    }
    private fun encryptedSchema(db:SQLiteDatabase) {
        LocalVault.initializeArchiveKeys(app)
        db.execSQL("ALTER TABLE evidence ADD COLUMN sender_index TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE evidence ADD COLUMN conversation_index TEXT NOT NULL DEFAULT ''")
        for(name in listOf("evidence_sender","evidence_conversation","evidence_screen_order"))db.execSQL("DROP INDEX IF EXISTS $name")
        db.execSQL("CREATE INDEX evidence_sender ON evidence(scope,sender_index,stamp DESC)")
        db.execSQL("CREATE INDEX evidence_conversation ON evidence(scope,conversation_index,stamp DESC,capture_order DESC,id DESC)")
        db.execSQL("CREATE INDEX evidence_screen_order ON evidence(scope,conversation_index,sort_stamp DESC,sort_order DESC,id DESC)")
        db.execSQL("ALTER TABLE capture_windows ADD COLUMN thread_value TEXT NOT NULL DEFAULT ''")
        db.execSQL("CREATE TABLE archive_maintenance(pending INTEGER PRIMARY KEY)")
    }
    private fun protectWindow(scope:String,thread:String,tokens:String):ContentValues {
        val key=index(scope,"thread",thread)
        return ContentValues().apply {
            put("thread",key);put("thread_value",seal(scope,"capture_windows",key,"thread",thread))
            put("tokens",seal(scope,"capture_windows",key,"tokens",tokens))
        }
    }
    private fun upgradeRelayOutbox(db:SQLiteDatabase) {
        db.execSQL("ALTER TABLE outbox ADD COLUMN role TEXT NOT NULL DEFAULT 'child'")
        db.execSQL("ALTER TABLE outbox ADD COLUMN relay_kind TEXT NOT NULL DEFAULT 'urgent'")
        db.execSQL("ALTER TABLE outbox ADD COLUMN relay_version INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE outbox SET relay_kind='data' WHERE priority=0")
    }
    private fun upgradeScreenOrder(db:SQLiteDatabase) {
        db.execSQL("ALTER TABLE evidence ADD COLUMN sort_stamp INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE evidence ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE evidence SET sort_stamp=stamp,sort_order=capture_order")
        db.execSQL("CREATE INDEX evidence_screen_order ON evidence(scope,conversation_key,sort_stamp DESC,sort_order DESC,id DESC)")
        db.execSQL("ALTER TABLE capture_windows ADD COLUMN anchor INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE capture_windows ADD COLUMN start_order INTEGER NOT NULL DEFAULT 0")
    }
    private fun upgradeConversationSchema(db:SQLiteDatabase) {
        for((column,definition) in listOf("conversation_key" to "TEXT NOT NULL DEFAULT ''","conversation_label" to "TEXT NOT NULL DEFAULT ''",
            "account_label" to "TEXT NOT NULL DEFAULT ''","direction" to "TEXT NOT NULL DEFAULT 'unknown'",
            "review_state" to "TEXT NOT NULL DEFAULT 'new'","kind" to "TEXT NOT NULL DEFAULT 'device'","actionable" to "INTEGER NOT NULL DEFAULT 1","capture_order" to "INTEGER NOT NULL DEFAULT 0"))
            db.execSQL("ALTER TABLE evidence ADD COLUMN $column $definition")
        db.execSQL("UPDATE evidence SET conversation_key='legacy:'||sender,conversation_label=sender,kind=CASE WHEN reason IN ('stranger_message','sexual_or_grooming_content') THEN 'message' ELSE 'device' END,actionable=CASE WHEN reason IN ('heartbeat_ok','pairing_verified','test_alert','archive_recovery') THEN 0 ELSE 1 END")
        db.execSQL("CREATE INDEX evidence_conversation ON evidence(scope,conversation_key,stamp DESC,capture_order DESC,id DESC)")
        db.execSQL("CREATE INDEX evidence_review ON evidence(scope,kind,actionable,review_state,stamp DESC)")
        db.execSQL("ALTER TABLE outbox ADD COLUMN priority INTEGER NOT NULL DEFAULT 1")
        db.execSQL("CREATE TABLE capture_seen(scope TEXT NOT NULL,id TEXT NOT NULL,PRIMARY KEY(scope,id))")
        db.execSQL("CREATE TABLE capture_windows(scope TEXT NOT NULL,thread TEXT NOT NULL,tokens TEXT NOT NULL,updated INTEGER NOT NULL,PRIMARY KEY(scope,thread))")
    }

    fun hasSeen(scope: String, id: String): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM seen WHERE scope=? AND id=?", arrayOf(scope,id)).use { it.moveToFirst() }

    fun append(scope: String, id: String, alert: JSONObject, legacy: Boolean = false): Boolean {
        require(alert.optString("type")!="captured_message" || ConversationCapture.valid(alert))
        val db = writableDatabase
        db.beginTransaction()
        try {
            val key = ContentValues().apply { put("scope",scope);put("id",id) }
            if (db.insertWithOnConflict("seen",null,key,SQLiteDatabase.CONFLICT_IGNORE) == -1L) return false
            val payload = JSONObject(alert.toString()).put("received_at_ms",System.currentTimeMillis())
            val captured=alert.optString("type")=="captured_message"
            val label=if(captured)alert.optString("conversation_label") else alert.optString("sender","未識別來源")
            val row = ContentValues(key).apply {
                put("sender",label.trim().removePrefix("@").lowercase(Locale.ROOT))
                put("conversation_key",if(captured)alert.getString("conversation_key") else "legacy:"+label.trim().removePrefix("@").lowercase(Locale.ROOT))
                put("conversation_label",label);put("account_label",alert.optString("account_label"))
                put("direction",if(captured)alert.optString("direction","unknown") else "unknown")
                put("kind",if(captured || alert.optString("reason") in setOf("stranger_message","sexual_or_grooming_content"))"message" else "device")
                put("actionable",if(ConversationCapture.actionable(alert))1 else 0)
                put("capture_order",if(captured)alert.optLong("capture_order") else 0)
                put("reason",alert.optString("reason","other"))
                put("stamp",alert.optLong("ts_ms").takeIf { it > 0 } ?: System.currentTimeMillis())
                put("sort_stamp",if(captured && alert.has("screen_anchor_ms"))alert.getLong("screen_anchor_ms") else getAsLong("stamp"))
                put("sort_order",if(captured && alert.has("screen_order"))alert.getLong("screen_order") else getAsLong("capture_order"))
                put("body",alert.optString("text"));put("payload",payload.toString());put("legacy",if(legacy)1 else 0);put("help_seen",if(legacy)1 else 0)
                if(!legacy) {
                    // Retention runs from the parent's receipt time; the child-supplied stamp (its clock) is for display only.
                    val base=System.currentTimeMillis()
                    put("retention_base",base);put("expires",base+retentionDays(scope)*DAY)
                }
            }
            db.insertOrThrow("evidence",null,protect(scope,id,row))
            db.delete("fragments","scope=? AND id=?",arrayOf(scope,id))
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }
    private fun decode(c: Cursor,threadOrder:Boolean=false): JSONObject = JSONObject(c.content("payload"))
        .put("_id",c.getString(c.getColumnIndexOrThrow("id")))
        .put("_saved",c.getInt(c.getColumnIndexOrThrow("saved"))==1)
        .put("_legacy_partial",c.getInt(c.getColumnIndexOrThrow("legacy"))==1)
        .put("_conversation_key",c.content("conversation_key"))
        .put("_conversation_label",c.content("conversation_label"))
        .put("_review_state",c.getString(c.getColumnIndexOrThrow("review_state")))
        .put("direction",c.getString(c.getColumnIndexOrThrow("direction")))
        .put("_sort_stamp",c.getLong(c.getColumnIndexOrThrow(if(threadOrder)"sort_stamp" else "stamp")))
        .put("_sort_order",c.getLong(c.getColumnIndexOrThrow(if(threadOrder)"sort_order" else "capture_order")))

    fun find(scope: String,id: String): JSONObject? = readableDatabase.rawQuery(
        "SELECT $evidenceColumns FROM evidence WHERE scope=? AND id=?",arrayOf(scope,id)).use { if(it.moveToFirst())decode(it) else null }

    private fun where(scope: String,q: EvidenceQuery): Pair<String,Array<String>> {
        readableDatabase
        val clauses = mutableListOf("scope=?");val args=mutableListOf(scope)
        if(q.sender!=null){clauses.add("sender_index=?");args.add(index(scope,"sender",q.sender))}
        if(q.conversationKey!=null){clauses.add("conversation_index=?");args.add(index(scope,"conversation",q.conversationKey))}
        q.before?.let { cursor ->
            val stamp=if(q.conversationKey!=null)"sort_stamp" else "stamp"
            val order=if(q.conversationKey!=null)"sort_order" else "capture_order"
            clauses.add("($stamp<? OR ($stamp=? AND ($order<? OR ($order=? AND id<?))))")
            args.addAll(listOf(cursor.stamp.toString(),cursor.stamp.toString(),cursor.order.toString(),cursor.order.toString(),cursor.id))
        }
        when(q.section){
            "conversations"->clauses.add("kind='message'")
            "review"->clauses.add("kind='message' AND actionable=1")
            "device"->clauses.add("kind='device'")
        }
        if(q.review in setOf("new","follow_up","reviewed")){clauses.add("review_state=?");args.add(q.review)}
        if(q.direction in ConversationCapture.directions){clauses.add("direction=?");args.add(q.direction)}
        if(q.savedOnly)clauses.add("saved=1")
        if(q.since>0){clauses.add("stamp>=?");args.add(q.since.toString())}
        when(q.filter){
            "all" -> clauses.add("reason NOT IN ('heartbeat_ok','pairing_verified','test_alert')")
            "high" -> clauses.add("reason='sexual_or_grooming_content'")
            "stranger" -> clauses.add("reason='stranger_message'")
            "followup" -> clauses.add("reason='flagged_contact_follow_up'")
            "device" -> clauses.add("reason IN ('tamper_attempt','heartbeat_ok','pairing_verified','unlock_used','test_alert')")
        }
        return clauses.joinToString(" AND ") to args.toTypedArray()
    }
    private fun matches(c:Cursor,q:EvidenceQuery):Boolean = q.text.isBlank() ||
        c.content("sender").contains(q.text.trim(),ignoreCase=true) || c.content("body").contains(q.text.trim(),ignoreCase=true)
    fun messages(scope: String,q: EvidenceQuery,limit: Int=31): List<JSONObject> {
        val (where,args)=where(scope,q)
        val order=if(q.conversationKey!=null)"sort_stamp DESC,sort_order DESC,id DESC" else "stamp DESC,capture_order DESC,id DESC"
        val offset=q.page.coerceAtLeast(0)*30L;val size=limit.coerceIn(1,61)
        val paging=if(q.text.isBlank())" LIMIT ? OFFSET ?" else ""
        return readableDatabase.rawQuery("SELECT ${if(q.text.isBlank())evidenceColumns else searchColumns} FROM evidence WHERE $where ORDER BY $order$paging",
            if(q.text.isBlank())args+arrayOf(size.toString(),offset.toString()) else args).use { c ->
            buildList {
                var skipped=0L
                while(c.moveToNext())if(matches(c,q)) {
                    if(q.text.isNotBlank() && skipped++<offset)continue
                    add(decode(c,q.conversationKey!=null));if(this.size==size)break
                }
            }
        }
    }
    private data class ConversationGroup(var summary:ConversationSummary,var cursor:EvidenceCursor,var priority:Int)
    fun conversations(scope:String,q:EvidenceQuery):List<ConversationSummary> {
        val (where,args)=where(scope,q)
        if(q.text.isBlank())return readableDatabase.rawQuery("SELECT scope,id,conversation_index,conversation_key,conversation_label,account_label,COUNT(*),SUM(CASE WHEN actionable=1 AND review_state<>'reviewed' THEN 1 ELSE 0 END),MAX(stamp),GROUP_CONCAT(DISTINCT CASE WHEN actionable=1 THEN reason END),MAX(CASE WHEN reason='sexual_or_grooming_content' THEN 2 ELSE 1 END) FROM evidence WHERE $where GROUP BY conversation_index",args).use { c ->
            val ranked=buildList{while(c.moveToNext())add(Triple(c.position,if(q.section=="review")c.getInt(10) else 0,c.getLong(8)))}
                .sortedWith(compareByDescending<Triple<Int,Int,Long>>{it.second}.thenByDescending{it.third})
            fun tied(a:Int,b:Int)=ranked[a].second==ranked[b].second && ranked[a].third==ranked[b].third
            val offset=q.page.coerceAtLeast(0)*30
            var from=minOf(offset,ranked.size);var to=minOf(offset+31,ranked.size)
            if(from<to){while(from>0 && tied(from-1,from))from--;while(to<ranked.size && tied(to,to-1))to++}
            ranked.subList(from,to).map { (position) -> c.moveToPosition(position)
                val last=readableDatabase.rawQuery("SELECT $evidenceColumns FROM evidence WHERE $where AND conversation_index=? ORDER BY sort_stamp DESC,sort_order DESC,id DESC LIMIT 1",args+arrayOf(c.string("conversation_index"))).use {
                    check(it.moveToFirst());val payload=JSONObject(it.content("payload"))
                    Triple(it.content("body"),payload.optString("conversation_kind","unknown"),ConversationCapture.unidentifiedLegacyLabel(payload)) to payload.optBoolean("content_free")
                }
                c.getInt(10) to ConversationSummary(c.content("conversation_key"),c.content("conversation_label"),c.content("account_label"),
                    c.getInt(6),c.getInt(7),c.getLong(8),last.first.first,c.getString(9)?.split(",")?:emptyList(),last.first.second,last.first.third,last.second)
            }.sortedWith(compareByDescending<Pair<Int,ConversationSummary>>{if(q.section=="review")it.first else 0}
                .thenByDescending{it.second.latest}.thenBy{it.second.key}).drop(offset-from).take(31).map{it.second}
        }
        val groups=mutableMapOf<String,ConversationGroup>()
        val comparator=compareBy<EvidenceCursor>{it.stamp}.thenBy{it.order}.thenBy{it.id}
        readableDatabase.rawQuery("SELECT $searchColumns FROM evidence WHERE $where ORDER BY sort_stamp DESC,sort_order DESC,id DESC",args).use { c ->
            while(c.moveToNext())if(matches(c,q)) {
                val key=c.string("conversation_index");val previous=groups[key]
                val position=EvidenceCursor(c.number("sort_stamp"),c.number("sort_order"),c.string("id"))
                val latest=previous==null || comparator.compare(position,previous.cursor)>0
                val payload=if(latest)JSONObject(c.content("payload")) else null
                val actionable=c.number("actionable")==1L;val reason=c.string("reason")
                val old=previous?.summary
                val summary=ConversationSummary(old?.key?:c.content("conversation_key"),old?.label?:c.content("conversation_label"),old?.account?:c.content("account_label"),
                    (old?.count?:0)+1,(old?.unreviewed?:0)+if(actionable && c.string("review_state")!="reviewed")1 else 0,
                    maxOf(old?.latest?:0,c.number("stamp")),if(latest)c.content("body") else old!!.preview,
                    ((old?.categories?:emptyList())+if(actionable)listOf(reason) else emptyList()).distinct(),
                    payload?.optString("conversation_kind","unknown")?:old!!.kind,if(payload!=null)ConversationCapture.unidentifiedLegacyLabel(payload) else old!!.unidentified,
                    payload?.optBoolean("content_free") ?: old!!.contentFree)
                groups[key]=ConversationGroup(summary,if(latest)position else previous!!.cursor,maxOf(previous?.priority?:0,if(reason=="sexual_or_grooming_content")2 else 1))
            }
        }
        return groups.values.sortedWith(compareByDescending<ConversationGroup>{if(q.section=="review")it.priority else 0}
            .thenByDescending{it.summary.latest}.thenBy{it.summary.key}).drop(q.page.coerceAtLeast(0)*30).take(31).map{it.summary}
    }
    fun timeline(scope:String,q:EvidenceQuery,limit:Int=31):List<JSONObject> = messages(scope,q,limit).asReversed()
    fun review(scope:String,id:String,state:String) {
        require(state in setOf("new","follow_up","reviewed"))
        writableDatabase.update("evidence",ContentValues().apply{put("review_state",state)},"scope=? AND id=? AND actionable=1",arrayOf(scope,id))
    }
    fun save(scope: String,id: String,value: Boolean) { writableDatabase.update("evidence",ContentValues().apply{put("saved",if(value)1 else 0)},"scope=? AND id=?",arrayOf(scope,id)) }
    fun nextAdvice(scope: String): JSONObject? = readableDatabase.rawQuery(
        "SELECT $evidenceColumns FROM evidence WHERE scope=? AND reason='sexual_or_grooming_content' AND help_seen=0 ORDER BY stamp DESC,id DESC LIMIT 1",arrayOf(scope)).use { if(it.moveToFirst())decode(it) else null }
    fun acknowledgeAdvice(scope: String) { writableDatabase.execSQL("UPDATE evidence SET help_seen=1 WHERE scope=?",arrayOf(scope)) }
    fun deleteArchive(scope: String) {
        val db=writableDatabase;db.beginTransaction()
        try {
            db.execSQL("INSERT OR IGNORE INTO seen(scope,id) SELECT DISTINCT scope,id FROM fragments WHERE scope=?",arrayOf(scope))
            db.execSQL("INSERT OR IGNORE INTO seen(scope,id) SELECT scope,id FROM evidence WHERE scope=?",arrayOf(scope))
            discardPending(scope,"child","data");discardPending(scope,"child","urgent")
            db.delete("evidence","scope=?",arrayOf(scope));db.delete("fragments","scope=?",arrayOf(scope))
            db.delete("capture_windows","scope=?",arrayOf(scope))
            tombstoneScores(db,scope,System.currentTimeMillis())
            for(table in riskTables)db.delete(table,"scope=?",arrayOf(scope))
            db.setTransactionSuccessful()
        }
        finally { db.endTransaction() }
    }
    fun deleteMessage(scope: String,id: String) {
        val db=writableDatabase;db.beginTransaction()
        try {
            db.execSQL("INSERT OR IGNORE INTO seen(scope,id) VALUES(?,?)",arrayOf(scope,id))
            db.execSQL("INSERT OR IGNORE INTO capture_seen(scope,id) VALUES(?,?)",arrayOf(scope,id))
            db.execSQL("UPDATE outbox_lifetimes SET blocked=1 WHERE scope=? AND id=?",arrayOf(scope,id))
            for(table in listOf("evidence","fragments"))db.delete(table,"scope=? AND id=?",arrayOf(scope,id))
            db.delete("outbox","scope=? AND event_id=?",arrayOf(scope,id))
            db.delete("capture_windows","scope=?",arrayOf(scope));db.setTransactionSuccessful()
        }finally{db.endTransaction()}
    }
    fun otherArchiveCount(scope: String): Int = readableDatabase.rawQuery(
        "SELECT (SELECT COUNT(*) FROM evidence WHERE scope<>?)+(SELECT COUNT(DISTINCT scope||':'||id) FROM fragments WHERE scope<>?)+(SELECT COUNT(*) FROM risk_store WHERE scope<>?)+(SELECT COUNT(*) FROM risk_scores WHERE scope<>?)",arrayOf(scope,scope,scope,scope)).use{it.moveToFirst();it.getInt(0)}
    fun deleteOtherArchives(scope: String) {
        val db=writableDatabase;db.beginTransaction()
        try {
            val scopes=db.rawQuery("SELECT scope FROM evidence WHERE scope<>? UNION SELECT scope FROM fragments WHERE scope<>? UNION SELECT scope FROM capture_windows WHERE scope<>? UNION SELECT scope FROM outbox WHERE scope<>? AND role='child' AND relay_kind IN ('data','urgent') UNION SELECT scope FROM risk_store WHERE scope<>? UNION SELECT scope FROM risk_threads WHERE scope<>? UNION SELECT scope FROM risk_results WHERE scope<>? UNION SELECT scope FROM risk_unknown WHERE scope<>? UNION SELECT scope FROM risk_authors WHERE scope<>? UNION SELECT scope FROM risk_scores WHERE scope<>? UNION SELECT scope FROM risk_state WHERE scope<>?",arrayOf(scope,scope,scope,scope,scope,scope,scope,scope,scope,scope,scope)).use{c->buildList{while(c.moveToNext())add(c.getString(0))}}
            for(old in scopes)deleteArchive(old)
            db.setTransactionSuccessful()
        }
        finally { db.endTransaction() }
    }

    // ---- Scores-only mode (schema 9). Child: risk_store, risk_threads, risk_results, risk_unknown (set-aside group chats), risk_authors. Parent: risk_scores, risk_state, risk_tombstones, risk_replay. ----
    private val riskChildTables=listOf("risk_store","risk_threads","risk_results","risk_unknown","risk_authors")
    private val riskParentTables=listOf("risk_scores","risk_state")
    private val riskTables=riskChildTables+riskParentTables
    private fun riskSchema(db:SQLiteDatabase) {
        // Text and labels are sealed like the evidence table; thread identity is a blind index.
        db.execSQL("CREATE TABLE IF NOT EXISTS risk_store(seq INTEGER PRIMARY KEY AUTOINCREMENT,scope TEXT NOT NULL,id TEXT NOT NULL,thread_index TEXT NOT NULL,label TEXT NOT NULL,direction TEXT NOT NULL,body TEXT NOT NULL,stamp INTEGER NOT NULL,sort_stamp INTEGER NOT NULL,sort_order INTEGER NOT NULL,UNIQUE(scope,id))")
        db.execSQL("CREATE INDEX IF NOT EXISTS risk_store_thread ON risk_store(scope,thread_index,sort_stamp DESC,sort_order DESC,id DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS risk_store_stamp ON risk_store(scope,stamp)")
        db.execSQL("CREATE TABLE IF NOT EXISTS risk_threads(scope TEXT NOT NULL,thread_index TEXT NOT NULL,label TEXT NOT NULL,scored_seq INTEGER NOT NULL DEFAULT 0,sent_sig TEXT NOT NULL DEFAULT '',PRIMARY KEY(scope,thread_index))")
        db.execSQL("CREATE TABLE IF NOT EXISTS risk_results(seq INTEGER PRIMARY KEY AUTOINCREMENT,scope TEXT NOT NULL,thread_index TEXT NOT NULL,score REAL,reason TEXT,scored_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS risk_results_thread ON risk_results(scope,thread_index,scored_at)")
        // Content-free: which distinct incoming authors an unknown-kind thread has shown (blind indexes only).
        db.execSQL("CREATE TABLE IF NOT EXISTS risk_authors(scope TEXT NOT NULL,thread_index TEXT NOT NULL,author_index TEXT NOT NULL,seen_at INTEGER NOT NULL,PRIMARY KEY(scope,thread_index,author_index))")
        addColumn(db,"risk_store","body_hash","TEXT NOT NULL DEFAULT ''")
        addColumn(db,"risk_store","source","TEXT NOT NULL DEFAULT ''")
        addColumn(db,"risk_store","matched","INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE TABLE IF NOT EXISTS risk_unknown(scope TEXT NOT NULL,thread_index TEXT NOT NULL,seen_at INTEGER NOT NULL,PRIMARY KEY(scope,thread_index))")
        db.execSQL("CREATE TABLE IF NOT EXISTS risk_scores(scope TEXT NOT NULL,label_index TEXT NOT NULL,label TEXT NOT NULL,result TEXT NOT NULL,scored_at INTEGER NOT NULL,first_reached_50 INTEGER,PRIMARY KEY(scope,label_index))")
        // Content-free replay guard: the last 64 report ids per scope survive deleteArchive, like the seen tombstones.
        db.execSQL("CREATE TABLE IF NOT EXISTS risk_replay(seq INTEGER PRIMARY KEY AUTOINCREMENT,scope TEXT NOT NULL,command_id TEXT NOT NULL,UNIQUE(scope,command_id))")
        addColumn(db,"risk_replay","stored_at","INTEGER NOT NULL DEFAULT 0")
        // Per-label deletion tombstones: a deleted score is not resurrected by a resend, and a notified one does not notify again.
        db.execSQL("CREATE TABLE IF NOT EXISTS risk_tombstones(scope TEXT NOT NULL,label_index TEXT NOT NULL,label TEXT NOT NULL,deleted_at INTEGER NOT NULL,notified_at INTEGER,PRIMARY KEY(scope,label_index))")
        db.execSQL("CREATE TABLE IF NOT EXISTS risk_state(scope TEXT PRIMARY KEY,policy_id TEXT,model TEXT NOT NULL,consent TEXT NOT NULL,paused INTEGER NOT NULL,paused_at INTEGER,last_run INTEGER,scored_count INTEGER NOT NULL,unknown_threads INTEGER NOT NULL,sent_at INTEGER NOT NULL,received_at INTEGER NOT NULL)")
        addColumn(db,"risk_state","active","INTEGER NOT NULL DEFAULT 0")
    }
    private fun addColumn(db:SQLiteDatabase,table:String,column:String,definition:String) {
        val has=db.rawQuery("PRAGMA table_info($table)",null).use{c->var found=false;while(c.moveToNext())if(c.getString(1)==column)found=true;found}
        if(!has)db.execSQL("ALTER TABLE $table ADD COLUMN $column $definition")
    }
    /** Remembers the deletion of score rows (all of a scope's, or those scored at or before [upTo]). */
    private fun tombstoneScores(db:SQLiteDatabase,scope:String,now:Long,upTo:Long?=null) {
        db.execSQL("INSERT OR REPLACE INTO risk_tombstones(scope,label_index,label,deleted_at,notified_at) SELECT scope,label_index,label,?,first_reached_50 FROM risk_scores WHERE scope=?"+(if(upTo!=null)" AND scored_at<=?" else ""),
            if(upTo!=null)arrayOf(now,scope,upTo) else arrayOf(now,scope))
    }
    private fun scoreLabelKey(scope:String,label:String):String = index(scope,"score_label",label.trim().removePrefix("@").lowercase(Locale.ROOT))
    private fun protectScore(scope:String,label:String,result:String):ContentValues {
        val key=scoreLabelKey(scope,label)
        return ContentValues().apply{put("label_index",key);put("label",seal(scope,"risk_scores",key,"label",label));put("result",seal(scope,"risk_scores",key,"result",result))}
    }
    private fun pruneRisk(db:SQLiteDatabase,scope:String,now:Long) {
        val cutoff=(now-RiskLimits.DAYS*DAY).toString()
        db.execSQL("DELETE FROM risk_store WHERE scope=? AND stamp<?",arrayOf(scope,cutoff))
        db.execSQL("DELETE FROM risk_results WHERE scope=? AND scored_at<?",arrayOf(scope,cutoff))
        db.execSQL("DELETE FROM risk_unknown WHERE scope=? AND seen_at<?",arrayOf(scope,cutoff))
        db.execSQL("DELETE FROM risk_authors WHERE scope=? AND seen_at<?",arrayOf(scope,cutoff))
        db.execSQL("DELETE FROM risk_threads WHERE scope=? AND thread_index NOT IN (SELECT thread_index FROM risk_store WHERE scope=?) AND thread_index NOT IN (SELECT thread_index FROM risk_results WHERE scope=?)",arrayOf(scope,scope,scope))
        // Parent copies expire on the archive's clock, counted from the time they were scored.
        val retention=(now-retentionDays(scope)*DAY).toString()
        tombstoneScores(db,scope,now,now-retentionDays(scope)*DAY)
        db.execSQL("DELETE FROM risk_scores WHERE scope=? AND scored_at<=?",arrayOf(scope,retention))
        db.execSQL("DELETE FROM risk_tombstones WHERE scope=? AND deleted_at<?",arrayOf(scope,(now-30*DAY).toString()))
        db.execSQL("DELETE FROM risk_replay WHERE scope=? AND stored_at<?",arrayOf(scope,(now-RiskLimits.REPLAY_KEEP_MS).toString()))
        db.execSQL("DELETE FROM risk_state WHERE scope=? AND received_at<=?",arrayOf(scope,retention))
    }
    /** Child: content-free marker so a re-read message is neither stored nor alerted twice. True when [id] is new. */
    fun captured(scope:String,id:String):Boolean = readableDatabase.rawQuery("SELECT 1 FROM capture_seen WHERE scope=? AND id=?",arrayOf(scope,id)).use{it.moveToFirst()}
    fun markCaptured(scope:String,id:String):Boolean =
        writableDatabase.insertWithOnConflict("capture_seen",null,ContentValues().apply{put("scope",scope);put("id",id)},SQLiteDatabase.CONFLICT_IGNORE)!=-1L
    /**
     * Child: stores one message of a one-to-one thread (caller's transaction) and enforces 60 messages per thread and 14 days.
     * [threadKey] is the normalised conversation label. The same message read through two routes is kept once, one to one:
     * a screen row claims at most one notification row with the same direction and text that is not newer than the
     * capture (the newest such row, so an older, distinct "ok" stays) (the notification row is dropped, the screen row's position is kept), and a notification arriving after the
     * screen read is dropped if an unclaimed screen row matches. So "ok","ok" stays two messages.
     */
    class SealedRiskMessage internal constructor(val thread:String,val bodyHash:String,val label:String,val body:String)
    fun sealRiskMessage(scope:String,id:String,threadKey:String,label:String,text:String):SealedRiskMessage = SealedRiskMessage(index(scope,"conversation",threadKey),
        index(scope,"risk_body",text),seal(scope,"risk_store",id,"label",label),seal(scope,"risk_store",id,"body",text))
    fun storeRiskMessage(scope:String,id:String,threadKey:String,label:String,direction:String,text:String,stamp:Long,sortStamp:Long,sortOrder:Long,now:Long,source:String=""):Boolean =
        storeRiskMessage(scope,id,direction,sealRiskMessage(scope,id,threadKey,label,text),stamp,sortStamp,sortOrder,now,source)
    fun storeRiskMessage(scope:String,id:String,direction:String,sealed:SealedRiskMessage,stamp:Long,sortStamp:Long,sortOrder:Long,now:Long,source:String=""):Boolean {
        require(direction in setOf("incoming","outgoing"))
        val db=writableDatabase;val thread=sealed.thread
        val bodyHash=sealed.bodyHash
        var matched=0
        if(source=="visible_dm") {
            val claim=db.rawQuery("SELECT seq FROM risk_store WHERE scope=? AND thread_index=? AND direction=? AND body_hash=? AND source='notification' AND stamp<=? ORDER BY stamp DESC LIMIT 1",
                arrayOf(scope,thread,direction,bodyHash,stamp.toString())).use{if(it.moveToFirst())it.getLong(0) else null}
            if(claim!=null){db.delete("risk_store","seq=?",arrayOf(claim.toString()));matched=1}
        } else if(source=="notification") {
            val screenRow=db.rawQuery("SELECT seq FROM risk_store WHERE scope=? AND thread_index=? AND direction=? AND body_hash=? AND source='visible_dm' AND matched=0 AND stamp>=? ORDER BY stamp LIMIT 1",
                arrayOf(scope,thread,direction,bodyHash,stamp.toString())).use{if(it.moveToFirst())it.getLong(0) else null}
            if(screenRow!=null){db.execSQL("UPDATE risk_store SET matched=1 WHERE seq=?",arrayOf(screenRow));return false}
        }
        val row=ContentValues().apply {
            put("scope",scope);put("id",id);put("thread_index",thread);put("direction",direction)
            put("label",sealed.label);put("body",sealed.body)
            put("stamp",stamp);put("sort_stamp",sortStamp);put("sort_order",sortOrder);put("body_hash",bodyHash);put("source",source);put("matched",matched)
        }
        val inserted=db.insertWithOnConflict("risk_store",null,row,SQLiteDatabase.CONFLICT_IGNORE)!=-1L
        if(inserted) {
            db.execSQL("DELETE FROM risk_store WHERE scope=? AND stamp<?",arrayOf(scope,(now-RiskLimits.DAYS*DAY).toString()))
            db.execSQL("DELETE FROM risk_store WHERE scope=? AND thread_index=? AND seq NOT IN (SELECT seq FROM risk_store WHERE scope=? AND thread_index=? ORDER BY sort_stamp DESC,sort_order DESC,id DESC LIMIT ?)",
                arrayOf(scope,thread,scope,thread,RiskLimits.MESSAGES))
        }
        return inserted
    }
    /** Child: true while [threadKey] is marked as looking like a group chat (excluded, within the 14-day window). */
    fun riskExcluded(scope:String,threadKey:String,now:Long):Boolean = readableDatabase.rawQuery("SELECT 1 FROM risk_unknown WHERE scope=? AND thread_index=? AND seen_at>=?",
        arrayOf(scope,index(scope,"conversation",threadKey),(now-RiskLimits.DAYS*DAY).toString())).use{it.moveToFirst()}
    /** Child: records an incoming author of an unknown-kind thread (blind index only) and returns how many distinct ones it has shown. */
    fun noteRiskAuthor(scope:String,threadKey:String,author:String,now:Long):Int {
        val db=writableDatabase;val thread=index(scope,"conversation",threadKey)
        db.insertWithOnConflict("risk_authors",null,ContentValues().apply{put("scope",scope);put("thread_index",thread);put("author_index",index(scope,"risk_author",author));put("seen_at",now)},SQLiteDatabase.CONFLICT_REPLACE)
        return db.rawQuery("SELECT COUNT(*) FROM risk_authors WHERE scope=? AND thread_index=? AND seen_at>=?",arrayOf(scope,thread,(now-RiskLimits.DAYS*DAY).toString())).use{it.moveToFirst();it.getInt(0)}
    }
    /**
     * Child: sets a thread aside as a group chat (a group record, or a second incoming author): marks it first, then deletes
     * what was kept. True if it was not already marked. The marker is a blind index only and expires 14 days after last seen.
     */
    fun excludeRiskThread(scope:String,threadKey:String,now:Long):Boolean {
        val db=writableDatabase;val thread=index(scope,"conversation",threadKey)
        val fresh=!riskExcluded(scope,threadKey,now)
        noteRiskUnknown(scope,threadKey,now)
        for(table in listOf("risk_store","risk_results","risk_threads","risk_authors"))db.delete(table,"scope=? AND thread_index=?",arrayOf(scope,thread))
        return fresh
    }
    /**
     * Child, import (caller's transaction): the conversation stored under [fromKey] continues as [toKey], as one thread. Its messages
     * and results move to [toKey] (message ids, and so their sealing, are unchanged); its scoring state moves too unless [toKey] has
     * its own; its author markers go. Nothing is deleted that is not moved, so the conversation keeps the score it had.
     */
    fun moveRiskThread(scope:String,fromKey:String,toKey:String) {
        val db=writableDatabase;val from=index(scope,"conversation",fromKey);val to=index(scope,"conversation",toKey)
        if(from==to)return
        for(table in listOf("risk_store","risk_results"))db.execSQL("UPDATE $table SET thread_index=? WHERE scope=? AND thread_index=?",arrayOf(to,scope,from))
        val hasOwn=db.rawQuery("SELECT 1 FROM risk_threads WHERE scope=? AND thread_index=?",arrayOf(scope,to)).use{it.moveToFirst()}
        if(!hasOwn)db.rawQuery("SELECT label,scored_seq,sent_sig FROM risk_threads WHERE scope=? AND thread_index=?",arrayOf(scope,from)).use {
            // The label is sealed to its thread, so it is opened and sealed again for the new one.
            if(it.moveToFirst())db.insertOrThrow("risk_threads",null,ContentValues().apply{put("scope",scope);put("thread_index",to)
                put("label",seal(scope,"risk_threads",to,"label",open(scope,"risk_threads",from,"label",it.getString(0))));put("scored_seq",it.getLong(1));put("sent_sig",it.getString(2))})
        }
        for(table in listOf("risk_threads","risk_authors"))db.delete(table,"scope=? AND thread_index=?",arrayOf(scope,from))
    }
    /**
     * Child, import (caller's transaction): [threadKey] got messages from an overlapping import, so its next score replaces its
     * earlier results if it is not lower than them (see [saveRiskResult]). Until then the earlier score stands. The mark is a negative
     * scored_seq (no schema change): -1 here, so the thread is due (it is anyway, it has new messages); due-ness reads its absolute value.
     */
    fun rescoreRiskThread(scope:String,threadKey:String) {
        writableDatabase.execSQL("UPDATE risk_threads SET scored_seq=-1 WHERE scope=? AND thread_index=?",arrayOf(scope,index(scope,"conversation",threadKey)))
    }
    /** Child: marks a thread set aside as a group chat (or refreshes the marker); keeps no text. */
    fun noteRiskUnknown(scope:String,threadKey:String,now:Long) {
        writableDatabase.insertWithOnConflict("risk_unknown",null,ContentValues().apply{put("scope",scope);put("thread_index",index(scope,"conversation",threadKey));put("seen_at",now)},SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun riskUnknownCount(scope:String,now:Long):Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM risk_unknown WHERE scope=? AND seen_at>=?",arrayOf(scope,(now-RiskLimits.DAYS*DAY).toString())).use{it.moveToFirst();it.getInt(0)}
    /** Child: threads whose newest stored message is newer than the last scored one. */
    fun riskDueThreads(scope:String):List<Pair<String,Long>> = readableDatabase.rawQuery(
        "SELECT s.thread_index,MAX(s.seq) FROM risk_store s WHERE s.scope=? GROUP BY s.thread_index HAVING MAX(s.seq)>COALESCE((SELECT ABS(t.scored_seq) FROM risk_threads t WHERE t.scope=s.scope AND t.thread_index=s.thread_index),0) ORDER BY MAX(s.seq)",arrayOf(scope)).use { c ->
        buildList{while(c.moveToNext())add(c.getString(0) to c.getLong(1))}
    }
    /** Child: the newest [limit] messages of a thread, oldest first. */
    internal fun riskThreadMessages(scope:String,thread:String,limit:Int=RiskModelSpec.WINDOW,now:Long=System.currentTimeMillis()):List<RiskMessage> = readableDatabase.rawQuery(
        "SELECT id,direction,body FROM risk_store WHERE scope=? AND thread_index=? AND stamp>=? ORDER BY sort_stamp DESC,sort_order DESC,id DESC LIMIT ?",arrayOf(scope,thread,(now-RiskLimits.DAYS*DAY).toString(),limit.toString())).use { c ->
        buildList{while(c.moveToNext())add(RiskMessage(if(c.getString(1)=="outgoing")"child" else "other",RiskMessages.sanitize(open(scope,"risk_store",c.getString(0),"body",c.getString(2)))))}
    }.asReversed()
    /** Child: records one scoring result. False (nothing written) if the thread's messages are gone, e.g. after a wipe. */
    fun saveRiskResult(scope:String,thread:String,seq:Long,score:Double?,reason:String?,now:Long):Boolean {
        require((score==null)==(reason!=null))
        val db=writableDatabase;db.beginTransaction()
        try {
            val label=db.rawQuery("SELECT id,label FROM risk_store WHERE scope=? AND thread_index=? ORDER BY seq DESC LIMIT 1",arrayOf(scope,thread)).use {
                if(!it.moveToFirst()){return false}
                open(scope,"risk_store",it.getString(0),"label",it.getString(1))
            }
            // After an overlapping import the next score replaces the earlier results (in this same transaction) if it is equal to or
            // higher than the highest of them: the higher score is kept. A lower score joins them, as any result does, and clears the
            // mark like any score: the conversation now has its current score beside the older higher one, which ages out at 14 days.
            // A result without a score erases nothing: it joins them, and the mark stays (as -seq: not due until new messages).
            val rescore=db.rawQuery("SELECT scored_seq FROM risk_threads WHERE scope=? AND thread_index=?",arrayOf(scope,thread)).use{it.moveToFirst() && it.getLong(0)<0}
            val highest=if(rescore && score!=null)db.rawQuery("SELECT MAX(score) FROM risk_results WHERE scope=? AND thread_index=?",arrayOf(scope,thread)).use{if(it.moveToFirst() && !it.isNull(0))it.getDouble(0) else null} else null
            if(rescore && score!=null && (highest==null || score>=highest))db.delete("risk_results","scope=? AND thread_index=?",arrayOf(scope,thread))
            val mark=if(rescore && score==null)-seq else seq
            db.insertOrThrow("risk_results",null,ContentValues().apply{put("scope",scope);put("thread_index",thread);if(score!=null)put("score",score) else putNull("score");if(reason!=null)put("reason",reason) else putNull("reason");put("scored_at",now)})
            db.execSQL("DELETE FROM risk_results WHERE scope=? AND thread_index=? AND scored_at<?",arrayOf(scope,thread,(now-RiskLimits.DAYS*DAY).toString()))
            db.insertWithOnConflict("risk_threads",null,ContentValues().apply{put("scope",scope);put("thread_index",thread);put("label",seal(scope,"risk_threads",thread,"label",label));put("scored_seq",mark)},SQLiteDatabase.CONFLICT_IGNORE)
            db.execSQL("UPDATE risk_threads SET scored_seq=?,label=? WHERE scope=? AND thread_index=?",arrayOf(mark,seal(scope,"risk_threads",thread,"label",label),scope,thread))
            db.setTransactionSuccessful();return true
        }finally{db.endTransaction()}
    }
    /** Child: one report row per thread with a result in the last 14 days: highest score seen, time of the latest run. */
    internal fun riskReportRows(scope:String,now:Long):List<RiskReportRow> {
        val cutoff=(now-RiskLimits.DAYS*DAY).toString()
        val byThread=linkedMapOf<String,MutableList<Triple<Double?,String?,Long>>>()
        readableDatabase.rawQuery("SELECT thread_index,score,reason,scored_at FROM risk_results WHERE scope=? AND scored_at>=? ORDER BY thread_index,scored_at,seq",arrayOf(scope,cutoff)).use { c ->
            while(c.moveToNext())byThread.getOrPut(c.getString(0)){mutableListOf()}.add(Triple(if(c.isNull(1))null else c.getDouble(1),c.getString(2),c.getLong(3)))
        }
        return readableDatabase.rawQuery("SELECT thread_index,label,sent_sig FROM risk_threads WHERE scope=?",arrayOf(scope)).use { c ->
            buildList {
                while(c.moveToNext()) {
                    val results=byThread[c.getString(0)]?:continue
                    val best=results.mapNotNull{it.first}.maxOrNull();val latest=results.last()
                    val reason=if(best!=null)null else latest.second
                    val row=RiskReportRow(c.getString(0),open(scope,"risk_threads",c.getString(0),"label",c.getString(1)),best,reason,latest.third)
                    add(row.copy(changed=row.signature()!=c.getString(2)))
                }
            }
        }.sortedBy{it.scoredAt}
    }
    /** Child: forgets what was sent, so the next report carries every row again. */
    internal fun resetRiskSent(scope:String) { writableDatabase.execSQL("UPDATE risk_threads SET sent_sig='' WHERE scope=?",arrayOf(scope)) }
    /** Child: [rows] are (thread, signature as sent); a row changed since keeps a different signature, so it stays due. */
    internal fun markRiskSent(scope:String,rows:List<Pair<String,String>>) {
        val db=writableDatabase;db.beginTransaction()
        try{for((thread,sig) in rows)db.execSQL("UPDATE risk_threads SET sent_sig=? WHERE scope=? AND thread_index=?",arrayOf(sig,scope,thread));db.setTransactionSuccessful()}finally{db.endTransaction()}
    }
    /** Child: removes every stored message, result and unknown-thread marker in every scope. */
    fun wipeRisk() {
        val db=writableDatabase;db.beginTransaction()
        try{for(table in riskChildTables)db.delete(table,null,null);db.setTransactionSuccessful()}finally{db.endTransaction()}
    }

    /**
     * Parent: stores a validated report. Returns how many conversations reached a score of 50 for the first time in 14 days,
     * or -1 if the report id was already stored (a replay: nothing changed).
     */
    internal fun storeScoresReport(scope:String,report:ParsedScoresReport,receivedAt:Long):Int {
        val db=writableDatabase;db.beginTransaction()
        try {
            // A report id already stored is a replay (relay redelivery or a stale queued copy): change nothing.
            val fresh=db.insertWithOnConflict("risk_replay",null,ContentValues().apply{put("scope",scope);put("command_id",report.commandId);put("stored_at",receivedAt)},SQLiteDatabase.CONFLICT_IGNORE)!=-1L
            if(!fresh){db.setTransactionSuccessful();return -1}
            // Ids are kept 30 days (see pruneRisk), capped per scope.
            db.execSQL("DELETE FROM risk_replay WHERE scope=? AND seq NOT IN (SELECT seq FROM risk_replay WHERE scope=? ORDER BY seq DESC LIMIT 5000)",arrayOf(scope,scope))
            var reached=0
            for(row in report.conversations) {
                val key=scoreLabelKey(scope,row.label)
                val old=db.rawQuery("SELECT scored_at,first_reached_50 FROM risk_scores WHERE scope=? AND label_index=?",arrayOf(scope,key)).use{if(it.moveToFirst())it.getLong(0) to (if(it.isNull(1))null else it.getLong(1)) else null}
                if(old!=null && row.scoredAt<old.first)continue
                val tomb=db.rawQuery("SELECT deleted_at,notified_at FROM risk_tombstones WHERE scope=? AND label_index=?",arrayOf(scope,key)).use{if(it.moveToFirst())it.getLong(0) to (if(it.isNull(1))null else it.getLong(1)) else null}
                if(tomb!=null && row.scoredAt<=tomb.first)continue
                var first=old?.second ?: tomb?.second
                if(row.score!=null && row.score>=50.0 && (first==null || first<row.scoredAt-RiskLimits.DAYS*DAY)){first=row.scoredAt;reached++}
                val result=JSONObject().put("score",row.score ?: JSONObject.NULL).put("review_reason",row.reviewReason ?: JSONObject.NULL).toString()
                val values=protectScore(scope,row.label,result).apply{put("scope",scope);put("scored_at",row.scoredAt);if(first!=null)put("first_reached_50",first) else putNull("first_reached_50")}
                db.insertWithOnConflict("risk_scores",null,values,SQLiteDatabase.CONFLICT_REPLACE)
            }
            // A bounded table: an authenticated but misbehaving child cannot grow the parent database without limit.
            db.execSQL("DELETE FROM risk_scores WHERE scope=? AND label_index NOT IN (SELECT label_index FROM risk_scores WHERE scope=? ORDER BY scored_at DESC LIMIT 2000)",arrayOf(scope,scope))
            val s=report.state
            // State follows the child's send time, not arrival order, so a relay cannot hand over an older state after a newer one.
            // The stored time is clamped to arrival, so a clock that was ahead does not block later states; a report sent less than
            // STATE_ORDER_SLACK_MS before the stored one still applies (small clock corrections, skew between the phones). The stored
            // time never goes down, so the slack cannot be chained to bring back an older state step by step.
            val storedSent=db.rawQuery("SELECT sent_at FROM risk_state WHERE scope=?",arrayOf(scope)).use{if(it.moveToFirst())it.getLong(0) else null}
            if(storedSent==null || report.sentAt>storedSent-RiskLimits.STATE_ORDER_SLACK_MS) db.insertWithOnConflict("risk_state",null,ContentValues().apply {
                put("scope",scope);if(s.policyId!=null)put("policy_id",s.policyId) else putNull("policy_id")
                put("model",s.model);put("consent",s.consent);put("paused",if(s.paused)1 else 0)
                if(s.pausedAt!=null)put("paused_at",s.pausedAt) else putNull("paused_at")
                if(s.lastRun!=null)put("last_run",s.lastRun) else putNull("last_run")
                put("scored_count",s.scoredCount);put("unknown_threads",s.unknownThreads);put("active",if(s.active)1 else 0);put("sent_at",maxOf(storedSent ?: 0L,minOf(report.sentAt,receivedAt)));put("received_at",receivedAt)
            },SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful();return reached
        }finally{db.endTransaction()}
    }
    /** Parent: true if the scores report [commandId] was stored here (its id is kept in risk_replay). */
    fun scoresReportStored(scope:String,commandId:String):Boolean = readableDatabase.rawQuery("SELECT 1 FROM risk_replay WHERE scope=? AND command_id=?",arrayOf(scope,commandId)).use{it.moveToFirst()}
    /** Parent: conversation scores for [scope], newest first, hiding rows older than the retention setting. */
    fun riskScores(scope:String,now:Long=System.currentTimeMillis()):List<RiskScoreRow> = readableDatabase.rawQuery(
        "SELECT label_index,label,result,scored_at FROM risk_scores WHERE scope=? AND scored_at>? ORDER BY scored_at DESC,label_index",arrayOf(scope,(now-retentionDays(scope)*DAY).toString())).use { c ->
        buildList {
            while(c.moveToNext()) {
                val key=c.getString(0);val result=JSONObject(open(scope,"risk_scores",key,"result",c.getString(2)))
                add(RiskScoreRow(open(scope,"risk_scores",key,"label",c.getString(1)),if(result.isNull("score"))null else result.getDouble("score"),
                    if(result.isNull("review_reason"))null else result.getString("review_reason"),c.getLong(3)))
            }
        }
    }
    /** Parent: the child's last reported state, or null if none has arrived or it has expired. */
    fun scoresModeState(scope:String,now:Long=System.currentTimeMillis()):ScoresModeState? = readableDatabase.rawQuery(
        "SELECT policy_id,model,consent,paused,paused_at,last_run,scored_count,unknown_threads,sent_at,received_at,active FROM risk_state WHERE scope=? AND received_at>?",
        arrayOf(scope,(now-retentionDays(scope)*DAY).toString())).use { c ->
        if(!c.moveToFirst())null else ScoresModeState(if(c.isNull(0))null else c.getString(0),c.getString(1),c.getString(2),c.getInt(3)==1,
            if(c.isNull(4))null else c.getLong(4),if(c.isNull(5))null else c.getLong(5),c.getInt(6),c.getInt(7),c.getLong(8),c.getLong(9),now-c.getLong(9)>RiskLimits.STALE_REPORT_MS,c.getInt(10)==1)
    }
    /**
     * Child: discards queued data and urgent events for which [carriesText] is true of any of their frames; every other
     * pending record (tamper, unlock, pairing, heartbeat, control) is kept. Same tombstoning as [discardPending].
     */
    fun discardPendingMatching(scope:String,carriesText:(String)->Boolean):Int {
        val db=writableDatabase;db.beginTransaction()
        try {
            val events=db.rawQuery("SELECT event_id,wire FROM outbox WHERE scope=? AND role='child' AND relay_kind IN ('data','urgent')",arrayOf(scope)).use { c ->
                buildSet{while(c.moveToNext())if(carriesText(c.getString(1)))add(c.getString(0))}
            }
            for(event in events) {
                db.execSQL("INSERT OR IGNORE INTO capture_seen(scope,id) VALUES(?,?)",arrayOf(scope,event))
                db.execSQL("INSERT OR IGNORE INTO seen(scope,id) VALUES(?,?)",arrayOf(scope,event))
                db.execSQL("UPDATE outbox_lifetimes SET blocked=1 WHERE scope=? AND id=?",arrayOf(scope,event))
                db.execSQL("DELETE FROM fragments WHERE scope=? AND id=?",arrayOf(scope,event))
                db.delete("outbox","scope=? AND event_id=?",arrayOf(scope,event))
            }
            if(events.isNotEmpty())db.delete("capture_windows","scope=?",arrayOf(scope))
            db.setTransactionSuccessful();return events.size
        }finally{db.endTransaction()}
    }
    fun enqueue(scope: String,id: String,wire: String,role:String="child",kind:String="urgent") {
        require(role in setOf("parent","child") && kind in setOf("data","urgent","receipt","control"))
        val db=writableDatabase
        db.beginTransaction()
        try {
        val event=id.substringBeforeLast(':',id)
        if(kind in setOf("data","urgent") && hasSeen(scope,event) && find(scope,event)==null){db.setTransactionSuccessful();return}
        val now=System.currentTimeMillis()
        db.execSQL("INSERT OR IGNORE INTO outbox_lifetimes(scope,id,expires) VALUES(?,?,?)",arrayOf(scope,event,now+PENDING_TTL))
        val expired=db.rawQuery("SELECT 1 FROM outbox_lifetimes WHERE scope=? AND id=? AND (expires<=? OR blocked=1)",arrayOf(scope,event,now.toString())).use{it.moveToFirst()}
        if(expired){db.setTransactionSuccessful();return}
        val retry=db.rawQuery("SELECT retry_at,attempts FROM outbox WHERE scope=? AND role=? AND relay_kind=? AND relay_version=1 AND accepted=0 AND attempts>0 ORDER BY retry_at LIMIT 1",arrayOf(scope,role,kind)).use{if(it.moveToFirst())it.getLong(0) to it.getInt(1) else 0L to 0}
        db.insertWithOnConflict("outbox",null,ContentValues().apply{put("scope",scope);put("id",id);put("event_id",id.substringBeforeLast(':',id));put("wire",wire);put("retry_at",retry.first);put("attempts",retry.second);put("role",role);put("relay_kind",kind);put("relay_version",1)},SQLiteDatabase.CONFLICT_IGNORE)
        db.setTransactionSuccessful()
        }finally{db.endTransaction()}
    }
    fun enqueueBatch(scope: String,frames: List<Pair<String,String>>,waitForReceipt: Boolean = false,role:String="child",kind:String="urgent") {
        require(frames.isNotEmpty() && frames.all { it.first.isNotBlank() && it.second.toByteArray(Charsets.UTF_8).size < 4096 })
        val db=writableDatabase;db.beginTransaction()
        try {
            for((id,wire) in frames){
                enqueue(scope,id,wire,role,kind)
                if(waitForReceipt)db.execSQL("UPDATE outbox SET receipt=1 WHERE scope=? AND id=?",arrayOf(scope,id))
            }
            db.setTransactionSuccessful()
        }
        finally { db.endTransaction() }
    }
    fun enqueueCaptured(scope:String,id:String,frames:List<Pair<String,String>>,waitForReceipt:Boolean,priority:Int):Boolean {
        val db=writableDatabase;db.beginTransaction()
        try {
            if(hasSeen(scope,id) && find(scope,id)==null){db.setTransactionSuccessful();return false}
            val inserted=db.insertWithOnConflict("capture_seen",null,ContentValues().apply{put("scope",scope);put("id",id)},SQLiteDatabase.CONFLICT_IGNORE)
            if(inserted == -1L){db.setTransactionSuccessful();return false}
            enqueueBatch(scope,frames,waitForReceipt,kind=if(priority>0)"urgent" else "data")
            db.execSQL("UPDATE outbox SET priority=? WHERE scope=? AND event_id=?",arrayOf(priority.coerceIn(0,3),scope,id))
            db.setTransactionSuccessful();return true
        } finally {db.endTransaction()}
    }
    fun captureWindowState(scope:String,thread:String):CaptureWindow {
        val db=readableDatabase
        val key=index(scope,"thread",thread)
        return db.rawQuery("SELECT tokens,anchor,start_order FROM capture_windows WHERE scope=? AND thread=?",arrayOf(scope,key)).use {
            if(!it.moveToFirst())CaptureWindow() else CaptureWindow(org.json.JSONArray(open(scope,"capture_windows",key,"tokens",it.getString(0))).let{a->(0 until a.length()).map{n->a.getString(n)}},it.getLong(1),it.getLong(2))
        }
    }
    fun setCaptureWindow(scope:String,thread:String,tokens:List<String>,anchor:Long=0,start:Long=0) {
        require(tokens.size<=128)
        writableDatabase.insertWithOnConflict("capture_windows",null,protectWindow(scope,thread,org.json.JSONArray(tokens).toString()).apply{put("scope",scope);put("updated",System.currentTimeMillis());put("anchor",anchor);put("start_order",start)},SQLiteDatabase.CONFLICT_REPLACE)
        writableDatabase.execSQL("DELETE FROM capture_windows WHERE scope=? AND thread NOT IN (SELECT thread FROM capture_windows WHERE scope=? ORDER BY updated DESC,thread LIMIT 32)",arrayOf(scope,scope))
    }
    fun unacceptedEventCount(scope: String,id: String): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM outbox WHERE scope=? AND event_id=? AND accepted=0",arrayOf(scope,id)).use{it.moveToFirst();it.getInt(0)}
    fun eventAccepted(scope:String,id:String):Boolean = readableDatabase.rawQuery(
        "SELECT COUNT(*),SUM(CASE WHEN accepted=0 THEN 1 ELSE 0 END) FROM outbox WHERE scope=? AND event_id=?",arrayOf(scope,id)).use{it.moveToFirst();it.getInt(0)>0 && it.getInt(1)==0}
    fun hasQueuedEvent(scope:String,id:String):Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM outbox WHERE scope=? AND event_id=?",arrayOf(scope,id)).use{it.moveToFirst()}
    fun due(scope: String,now: Long,role:String="child"): List<PendingEvidence> {
        // now is the caller's retry horizon (including Long.MAX_VALUE in archive/export callers).
        val expiryNow=System.currentTimeMillis()
        fun rows(condition:String,limit:Int)=readableDatabase.rawQuery("SELECT id,wire,receipt,relay_kind FROM outbox WHERE scope=? AND role=? AND retry_at<=? AND EXISTS(SELECT 1 FROM outbox_lifetimes l WHERE l.scope=outbox.scope AND l.id=outbox.event_id AND l.expires>? AND l.blocked=0) AND $condition ORDER BY accepted ASC,priority DESC,rowid LIMIT ?",arrayOf(scope,role,now.toString(),expiryNow.toString(),limit.toString())).use { c -> buildList { while(c.moveToNext())add(PendingEvidence(c.getString(0),c.getString(1),c.getInt(2)==1,c.getString(3))) } }
        val high=rows("priority>0",24);val context=rows("priority=0",32-high.size)
        return if(context.isEmpty())rows("priority>0",32) else high+context
    }
    fun pendingCount(scope: String): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM outbox WHERE scope=?",arrayOf(scope)).use{it.moveToFirst();it.getInt(0)}
    fun pendingEvent(scope:String,id:String):Boolean = readableDatabase.rawQuery("SELECT 1 FROM outbox WHERE scope=? AND id=? AND EXISTS(SELECT 1 FROM outbox_lifetimes l WHERE l.scope=outbox.scope AND l.id=outbox.event_id AND l.expires>? AND l.blocked=0)",arrayOf(scope,id,System.currentTimeMillis().toString())).use{it.moveToFirst()}
    fun controlConsumed(scope:String,id:String) {
        val db=writableDatabase;db.beginTransaction()
        try{db.execSQL("INSERT OR IGNORE INTO seen(scope,id) VALUES(?,?)",arrayOf(scope,id));db.delete("fragments","scope=? AND id=?",arrayOf(scope,id));db.setTransactionSuccessful()}
        finally{db.endTransaction()}
    }
    fun migrateRelay(scope:String,rewrite:(String)->String) {
        val db=writableDatabase;db.beginTransaction()
        try {
            val rows=db.rawQuery("SELECT id,wire FROM outbox WHERE scope=? AND role='child' AND (relay_version=0 OR (receipt=0 AND relay_kind IN ('urgent','data')))",arrayOf(scope)).use{c->buildList{while(c.moveToNext())add(c.getString(0) to c.getString(1))}}
            for((id,wire) in rows)db.execSQL("UPDATE outbox SET wire=?,receipt=1,accepted=0,attempts=0,retry_at=0,relay_version=1 WHERE scope=? AND id=?",arrayOf(rewrite(wire),scope,id))
            db.setTransactionSuccessful()
        }finally{db.endTransaction()}
    }
    fun acknowledge(scope: String,id: String) { writableDatabase.delete("outbox","scope=? AND id=?",arrayOf(scope,id)) }
    fun relayAccepted(scope: String,id: String,now: Long) {
        writableDatabase.execSQL("UPDATE outbox SET accepted=1,retry_at=?+MIN(3600000,30000*(1 << MIN(attempts,7))),attempts=attempts+1 WHERE scope=? AND id=?",arrayOf(now,scope,id))
    }
    fun acknowledgeEvent(scope: String,id: String) { writableDatabase.delete("outbox","scope=? AND event_id=?",arrayOf(scope,id)) }
    fun eventDue(scope: String,id: String,now: Long): Boolean = readableDatabase.rawQuery("SELECT 1 FROM outbox WHERE scope=? AND event_id=? AND accepted=0 AND retry_at<=? AND EXISTS(SELECT 1 FROM outbox_lifetimes l WHERE l.scope=outbox.scope AND l.id=outbox.event_id AND l.expires>? AND l.blocked=0) LIMIT 1",arrayOf(scope,id,now.toString(),System.currentTimeMillis().toString())).use{it.moveToFirst()}
    fun failed(scope: String,id: String,now: Long,role:String="child",kindOnly:String?=null) { writableDatabase.execSQL("UPDATE outbox SET retry_at=?+MIN(900000,30000*(1 << MIN(attempts,5))),attempts=attempts+1 WHERE scope=? AND role=? AND retry_at<=? AND (? IS NULL OR relay_kind=?) AND EXISTS(SELECT 1 FROM outbox WHERE scope=? AND id=?)",arrayOf(now,scope,role,now,kindOnly,kindOnly,scope,id)) }
    /** The relay refused this one record for good: back off it alone, never the alerts queued behind it. */
    fun refusedAsExpired(scope: String,id: String,now: Long) { writableDatabase.execSQL("UPDATE outbox SET retry_at=?+900000,attempts=attempts+1 WHERE scope=? AND id=?",arrayOf(now,scope,id)) }
    // A manual first attempt must never cancel an issued backoff or a parent-receipt wait.
    fun retryNow(scope: String) { writableDatabase.execSQL("UPDATE outbox SET retry_at=0 WHERE scope=? AND accepted=0 AND attempts=0",arrayOf(scope)) }
    fun discardPending(scope:String) { discardPendingRows(scope,null,null) }
    /** Like [discardPending], but the events in [keep] (queued score reports, which hold no message text) stay queued. */
    fun discardPendingExcept(scope:String,keep:Collection<String>) { discardPendingRows(scope,null,null,keep) }
    fun discardPending(scope:String,role:String) { discardPendingRows(scope,role,null) }
    fun discardPending(scope:String,role:String,kind:String) { discardPendingRows(scope,role,kind) }
    private fun discardPendingRows(scope:String,role:String?,kind:String?,keep:Collection<String> = emptyList()) {
        require(role==null || role in setOf("parent","child"))
        require(kind==null || kind in setOf("data","urgent","receipt","control"))
        val db=writableDatabase;db.beginTransaction()
        try {
            val where="scope=?"+(if(role==null)"" else " AND role=?")+(if(kind==null)"" else " AND relay_kind=?")+(if(keep.isEmpty())"" else " AND event_id NOT IN (${keep.joinToString(","){"?"}})")
            val args=(listOf(scope)+listOfNotNull(role,kind)+keep).toTypedArray()
            db.execSQL("INSERT OR IGNORE INTO capture_seen(scope,id) SELECT scope,event_id FROM outbox WHERE $where",args)
            db.execSQL("INSERT OR IGNORE INTO seen(scope,id) SELECT scope,event_id FROM outbox WHERE $where AND relay_kind IN ('data','urgent')",args)
            db.execSQL("UPDATE outbox_lifetimes SET blocked=1 WHERE scope=? AND id IN (SELECT event_id FROM outbox WHERE $where)",arrayOf(scope)+args)
            db.execSQL("DELETE FROM fragments WHERE scope=? AND id IN (SELECT event_id FROM outbox WHERE $where)",arrayOf(scope)+args)
            db.delete("outbox",where,args)
            db.delete("capture_windows","scope=?",arrayOf(scope));db.setTransactionSuccessful()
        }finally{db.endTransaction()}
    }
    fun rotate(old: String,new: String,rekey: (String)->String) {
        if(old==new)return
        val db=writableDatabase;db.beginTransaction()
        try {
            val pending=db.rawQuery("SELECT id,wire,receipt,event_id,priority,role,relay_kind,relay_version FROM outbox WHERE scope=? AND role='child'",arrayOf(old)).use { c -> buildList {
                while(c.moveToNext())add(ContentValues().apply{put("scope",new);put("id",c.getString(0));put("wire",rekey(c.getString(1)));put("attempts",0);put("retry_at",0);put("receipt",c.getInt(2));put("accepted",0);put("event_id",c.getString(3));put("priority",c.getInt(4));put("role",c.getString(5));put("relay_kind",c.getString(6));put("relay_version",c.getInt(7))})
            } }
            for(row in pending)db.insertWithOnConflict("outbox",null,row,SQLiteDatabase.CONFLICT_IGNORE)
            db.execSQL("INSERT OR IGNORE INTO outbox_lifetimes(scope,id,expires,blocked) SELECT ?,id,expires,blocked FROM outbox_lifetimes WHERE scope=?",arrayOf(new,old))
            db.execSQL("UPDATE outbox_lifetimes SET expires=MIN(expires,COALESCE((SELECT expires FROM outbox_lifetimes old WHERE old.scope=? AND old.id=outbox_lifetimes.id),expires)),blocked=MAX(blocked,COALESCE((SELECT blocked FROM outbox_lifetimes old WHERE old.scope=? AND old.id=outbox_lifetimes.id),blocked)) WHERE scope=?",arrayOf(old,old,new))
            db.execSQL("INSERT OR IGNORE INTO retention_settings(scope,days) SELECT ?,days FROM retention_settings WHERE scope=?",arrayOf(new,old))
            db.delete("outbox","scope=? AND role='child'",arrayOf(old))
            // Authenticate with the old scope before moving any row; failures roll back the move.
            transformRows(db,"evidence",old) { c ->
                protect(new,c.string("id"),ContentValues().apply{for(column in privateColumns)put(column,c.content(column))}).apply{put("scope",new)}
            }
            transformRows(db,"fragments",old) { c ->
                val id=c.string("id");val part=c.number("part").toString()
                val data=open(old,"fragments",id,part,c.string("data"))
                ContentValues().apply{put("scope",new);put("data",seal(new,"fragments",id,part,data))}
            }
            transformRows(db,"capture_windows",old) { c ->
                val key=c.string("thread")
                val thread=open(old,"capture_windows",key,"thread",c.string("thread_value"))
                val tokens=open(old,"capture_windows",key,"tokens",c.string("tokens"))
                protectWindow(new,thread,tokens).apply{put("scope",new)}
            }
            // The child-side scoring store is wiped, never moved, when the family key changes.
            for(table in riskChildTables)db.delete(table,"scope=?",arrayOf(old))
            transformRows(db,"risk_scores",old) { c ->
                val key=c.string("label_index")
                val label=open(old,"risk_scores",key,"label",c.string("label"))
                val result=open(old,"risk_scores",key,"result",c.string("result"))
                protectScore(new,label,result).apply{put("scope",new)}
            }
            transformRows(db,"risk_tombstones",old) { c ->
                val key=c.string("label_index")
                val label=open(old,"risk_scores",key,"label",c.string("label"))
                val moved=protectScore(new,label,"{}")
                ContentValues().apply{put("scope",new);put("label_index",moved.getAsString("label_index"));put("label",moved.getAsString("label"))}
            }
            db.delete("risk_tombstones","scope=?",arrayOf(old))
            db.execSQL("UPDATE OR IGNORE risk_state SET scope=? WHERE scope=?",arrayOf(new,old))
            db.execSQL("UPDATE OR IGNORE risk_replay SET scope=? WHERE scope=?",arrayOf(new,old))
            db.delete("risk_replay","scope=?",arrayOf(old))
            for(table in riskParentTables)db.delete(table,"scope=?",arrayOf(old))
            for(table in listOf("seen","capture_seen")){
                db.execSQL("UPDATE OR IGNORE $table SET scope=? WHERE scope=?",arrayOf(new,old))
            }
            for(table in listOf("evidence","seen","fragments","capture_seen","capture_windows"))db.delete(table,"scope=?",arrayOf(old))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    /** The stored parts of [id], once their completed record was refused. The id is not marked seen. */
    fun discardFragments(scope: String, id: String) { writableDatabase.delete("fragments","scope=? AND id=?",arrayOf(scope,id)) }
    fun incompleteCount(scope: String): Int = readableDatabase.rawQuery("SELECT COUNT(DISTINCT id) FROM fragments WHERE scope=?",arrayOf(scope)).use{it.moveToFirst();it.getInt(0)}
    fun hasFragment(scope:String,id:String,part:Int,digest:String):Boolean = readableDatabase.rawQuery("SELECT 1 FROM fragments WHERE scope=? AND id=? AND part=? AND digest=?",arrayOf(scope,id,part.toString(),digest)).use{it.moveToFirst()}

    /** Parts remain durable until append() atomically stores the completed event and tombstone. */
    fun fragment(scope: String,part: JSONObject): JSONObject? {
        val id=part.optString("event_id");val index=part.optInt("part",-1);val count=part.optInt("parts")
        val digest=part.optString("digest");val encoded=part.optString("data")
        if(!id.matches(Regex("[A-Za-z0-9:_-]{1,160}")) || count !in 2..1024 || index !in 0 until count ||
            !digest.matches(Regex("[a-f0-9]{64}")) || encoded.length !in 1..1368)return null
        if(hasSeen(scope,id))return find(scope,id)
        val bytes=try{Base64.decode(encoded,Base64.NO_WRAP)}catch(_:Exception){return null}
        if(bytes.isEmpty() || bytes.size>1024 || (index<count-1 && bytes.size!=1024))return null
        val db=writableDatabase;db.beginTransaction()
        try {
            if(hasSeen(scope,id)){db.setTransactionSuccessful();return find(scope,id)}
            db.rawQuery("SELECT parts,digest FROM fragments WHERE scope=? AND id=? LIMIT 1",arrayOf(scope,id)).use {
                if(it.moveToFirst() && (it.getInt(0)!=count || it.getString(1)!=digest))return null
            }
            db.rawQuery("SELECT data FROM fragments WHERE scope=? AND id=? AND part=?",arrayOf(scope,id,index.toString())).use {
                if(it.moveToFirst() && open(scope,"fragments",id,index.toString(),it.getString(0))!=encoded)return null
            }
            // Bound incomplete authenticated input; completed evidence is not subject to this cap.
            val incomplete=incompleteCount(scope)
            if(incomplete>=100 && !db.rawQuery("SELECT 1 FROM fragments WHERE scope=? AND id=?",arrayOf(scope,id)).use{it.moveToFirst()})return null
            val expiry=db.rawQuery("SELECT MIN(expires) FROM fragments WHERE scope=? AND id=?",arrayOf(scope,id)).use{it.moveToFirst();if(it.isNull(0))System.currentTimeMillis()+PENDING_TTL else it.getLong(0)}
            if(expiry<=System.currentTimeMillis()) {
                db.execSQL("INSERT OR IGNORE INTO seen(scope,id) VALUES(?,?)",arrayOf(scope,id))
                db.delete("fragments","scope=? AND id=?",arrayOf(scope,id));db.setTransactionSuccessful();return null
            }
            db.insertWithOnConflict("fragments",null,ContentValues().apply{put("scope",scope);put("id",id);put("part",index);put("parts",count);put("digest",digest);put("data",seal(scope,"fragments",id,index.toString(),encoded));put("expires",expiry)},SQLiteDatabase.CONFLICT_IGNORE)
            var result: JSONObject?=null
            db.rawQuery("SELECT part,data FROM fragments WHERE scope=? AND id=? ORDER BY part",arrayOf(scope,id)).use { c ->
                if(c.count==count){
                    val out=ByteArrayOutputStream()
                    while(c.moveToNext())out.write(Base64.decode(open(scope,"fragments",id,c.getInt(0).toString(),c.getString(1)),Base64.NO_WRAP))
                    val all=out.toByteArray()
                    if(AlertFrames.digest(all)==digest) {
                        val json=try{JSONObject(Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(all)).toString())}catch(_:Exception){null}
                        if(json!=null && json.optString("type") in ConversationCapture.contentTypes && json.optString("event_id")==id &&
                            (json.optString("type")!="captured_message" || ConversationCapture.valid(json)))result=json
                    }
                }
            }
            db.setTransactionSuccessful();return result
        } finally { db.endTransaction() }
    }
}

/** Bound encrypted relay frames, preserving Unicode by splitting serialized UTF-8 bytes. */
object AlertFrames {
    fun digest(bytes: ByteArray): String=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)}
    fun split(alert: JSONObject): List<JSONObject> {
        require(alert.optString("type") in ConversationCapture.contentTypes)
        val raw=alert.toString().toByteArray(Charsets.UTF_8)
        require(raw.size<=1024*1024){"Captured alert exceeds the 1 MiB transport safety bound"}
        if(raw.size<=1800)return listOf(alert)
        val count=(raw.size+1023)/1024;val digest=digest(raw)
        return (0 until count).map { index -> JSONObject().put("type","alert_fragment").put("event_id",alert.getString("event_id"))
            .put("part",index).put("parts",count).put("digest",digest).put("receipt_requested",alert.optBoolean("receipt_requested"))
            .put("data",Base64.encodeToString(raw.copyOfRange(index*1024,minOf(raw.size,(index+1)*1024)),Base64.NO_WRAP)) }
    }
}
