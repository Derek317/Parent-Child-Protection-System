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
data class EvidenceContact(val sender: String, val count: Int, val important: Int, val latest: Long)
data class ConversationSummary(val key:String,val label:String,val account:String,val count:Int,val unreviewed:Int,val latest:Long,val preview:String,val categories:List<String> = emptyList(),val kind:String="unknown",val unidentified:Boolean=false)
data class PendingEvidence(val id: String, val wire: String, val waitForReceipt: Boolean, val kind:String="urgent")
data class CaptureWindow(val tokens:List<String> = emptyList(),val anchor:Long=0,val start:Long=0)

/** App-private, backup-excluded storage. Paging limits the view, never the retained evidence. */
class FamilyEvidence(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "family_evidence.db", null, 4), java.io.Closeable {
    companion object { private val openLock=Any() }
    // Short-lived helpers share a creation/migration gate, not the entire query lifetime.
    override fun getWritableDatabase():SQLiteDatabase = synchronized(openLock){super.getWritableDatabase()}
    override fun getReadableDatabase():SQLiteDatabase = synchronized(openLock){super.getReadableDatabase()}
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE evidence(scope TEXT NOT NULL,id TEXT NOT NULL,sender TEXT NOT NULL,reason TEXT NOT NULL,stamp INTEGER NOT NULL,body TEXT NOT NULL,payload TEXT NOT NULL,saved INTEGER NOT NULL DEFAULT 0,help_seen INTEGER NOT NULL DEFAULT 0,legacy INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(scope,id))")
        db.execSQL("CREATE INDEX evidence_time ON evidence(scope,stamp DESC,id DESC)")
        db.execSQL("CREATE INDEX evidence_sender ON evidence(scope,sender,stamp DESC)")
        db.execSQL("CREATE INDEX evidence_advice ON evidence(scope,reason,help_seen,stamp DESC,id DESC)")
        db.execSQL("CREATE INDEX evidence_saved ON evidence(scope,saved,stamp DESC)")
        // Content-free tombstones prevent deleted records being resurrected by a cached relay replay.
        db.execSQL("CREATE TABLE seen(scope TEXT NOT NULL,id TEXT NOT NULL,PRIMARY KEY(scope,id))")
        db.execSQL("CREATE TABLE outbox(scope TEXT NOT NULL,id TEXT NOT NULL,event_id TEXT NOT NULL,wire TEXT NOT NULL,receipt INTEGER NOT NULL DEFAULT 0,accepted INTEGER NOT NULL DEFAULT 0,attempts INTEGER NOT NULL DEFAULT 0,retry_at INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(scope,id))")
        db.execSQL("CREATE TABLE fragments(scope TEXT NOT NULL,id TEXT NOT NULL,part INTEGER NOT NULL,parts INTEGER NOT NULL,digest TEXT NOT NULL,data TEXT NOT NULL,PRIMARY KEY(scope,id,part))")
        upgradeConversationSchema(db)
        upgradeScreenOrder(db)
        upgradeRelayOutbox(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if(oldVersion<2)upgradeConversationSchema(db)
        if(oldVersion<3)upgradeScreenOrder(db)
        if(oldVersion<4)upgradeRelayOutbox(db)
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
            }
            db.insertOrThrow("evidence",null,row)
            db.delete("fragments","scope=? AND id=?",arrayOf(scope,id))
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }
    private fun decode(c: Cursor,threadOrder:Boolean=false): JSONObject = JSONObject(c.getString(c.getColumnIndexOrThrow("payload")))
        .put("_id",c.getString(c.getColumnIndexOrThrow("id")))
        .put("_saved",c.getInt(c.getColumnIndexOrThrow("saved"))==1)
        .put("_legacy_partial",c.getInt(c.getColumnIndexOrThrow("legacy"))==1)
        .put("_conversation_key",c.getString(c.getColumnIndexOrThrow("conversation_key")))
        .put("_conversation_label",c.getString(c.getColumnIndexOrThrow("conversation_label")))
        .put("_review_state",c.getString(c.getColumnIndexOrThrow("review_state")))
        .put("direction",c.getString(c.getColumnIndexOrThrow("direction")))
        .put("_sort_stamp",c.getLong(c.getColumnIndexOrThrow(if(threadOrder)"sort_stamp" else "stamp")))
        .put("_sort_order",c.getLong(c.getColumnIndexOrThrow(if(threadOrder)"sort_order" else "capture_order")))

    fun find(scope: String,id: String): JSONObject? = readableDatabase.rawQuery(
        "SELECT * FROM evidence WHERE scope=? AND id=?",arrayOf(scope,id)).use { if(it.moveToFirst())decode(it) else null }

    private fun where(scope: String,q: EvidenceQuery): Pair<String,Array<String>> {
        val clauses = mutableListOf("scope=?");val args=mutableListOf(scope)
        if(q.sender!=null){clauses.add("sender=?");args.add(q.sender)}
        if(q.conversationKey!=null){clauses.add("conversation_key=?");args.add(q.conversationKey)}
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
        if(q.text.isNotBlank()){
            clauses.add("(sender LIKE ? ESCAPE '\\' OR body LIKE ? ESCAPE '\\')")
            val escaped=q.text.trim().replace("\\","\\\\").replace("%","\\%").replace("_","\\_")
            args.add("%$escaped%");args.add("%$escaped%")
        }
        return clauses.joinToString(" AND ") to args.toTypedArray()
    }
    fun messages(scope: String,q: EvidenceQuery,limit: Int=31): List<JSONObject> {
        val (where,args)=where(scope,q)
        val order=if(q.conversationKey!=null)"sort_stamp DESC,sort_order DESC,id DESC" else "stamp DESC,capture_order DESC,id DESC"
        return readableDatabase.rawQuery("SELECT * FROM evidence WHERE $where ORDER BY $order LIMIT ? OFFSET ?",
            args+arrayOf(limit.coerceIn(1,61).toString(),(q.page.coerceAtLeast(0)*30L).toString())).use { c ->
            buildList { while(c.moveToNext())add(decode(c,q.conversationKey!=null)) }
        }
    }
    fun contacts(scope: String,q: EvidenceQuery): List<EvidenceContact> {
        val (where,args)=where(scope,q)
        return readableDatabase.rawQuery("SELECT sender,COUNT(*),SUM(CASE WHEN reason='sexual_or_grooming_content' THEN 1 ELSE 0 END),MAX(stamp) AS latest FROM evidence WHERE $where GROUP BY sender ORDER BY latest DESC,sender ASC LIMIT 31 OFFSET ?",
            args+arrayOf((q.page.coerceAtLeast(0)*30L).toString())).use { c ->
            buildList { while(c.moveToNext())add(EvidenceContact(c.getString(0),c.getInt(1),c.getInt(2),c.getLong(3))) }
        }
    }
    fun conversations(scope:String,q:EvidenceQuery):List<ConversationSummary> {
        val (where,args)=where(scope,q)
        val priority=if(q.section=="review")"MAX(CASE WHEN reason='sexual_or_grooming_content' THEN 2 ELSE 1 END) DESC," else ""
        return readableDatabase.rawQuery("SELECT conversation_key,conversation_label,account_label,COUNT(*),SUM(CASE WHEN actionable=1 AND review_state<>'reviewed' THEN 1 ELSE 0 END),MAX(stamp) AS latest,GROUP_CONCAT(DISTINCT CASE WHEN actionable=1 THEN reason END) FROM evidence WHERE $where GROUP BY conversation_key ORDER BY ${priority}latest DESC,conversation_key LIMIT 31 OFFSET ?",args+arrayOf((q.page.coerceAtLeast(0)*30L).toString())).use { c ->
            buildList { while(c.moveToNext()) {
                val key=c.getString(0)
                val last=readableDatabase.rawQuery("SELECT body,payload FROM evidence WHERE $where AND conversation_key=? ORDER BY sort_stamp DESC,sort_order DESC,id DESC LIMIT 1",args+arrayOf(key)).use {
                    if(it.moveToFirst())Triple(it.getString(0),JSONObject(it.getString(1)).optString("conversation_kind","unknown"),ConversationCapture.unidentifiedLegacyLabel(JSONObject(it.getString(1)))) else Triple("","unknown",false)
                }
                add(ConversationSummary(key,c.getString(1),c.getString(2),c.getInt(3),c.getInt(4),c.getLong(5),last.first,c.getString(6)?.split(",")?:emptyList(),last.second,last.third))
            } }
        }
    }
    fun timeline(scope:String,q:EvidenceQuery,limit:Int=31):List<JSONObject> = messages(scope,q,limit).asReversed()
    fun review(scope:String,id:String,state:String) {
        require(state in setOf("new","follow_up","reviewed"))
        writableDatabase.update("evidence",ContentValues().apply{put("review_state",state)},"scope=? AND id=? AND actionable=1",arrayOf(scope,id))
    }
    fun save(scope: String,id: String,value: Boolean) { writableDatabase.update("evidence",ContentValues().apply{put("saved",if(value)1 else 0)},"scope=? AND id=?",arrayOf(scope,id)) }
    fun nextAdvice(scope: String): JSONObject? = readableDatabase.rawQuery(
        "SELECT * FROM evidence WHERE scope=? AND reason='sexual_or_grooming_content' AND help_seen=0 ORDER BY stamp DESC,id DESC LIMIT 1",arrayOf(scope)).use { if(it.moveToFirst())decode(it) else null }
    fun acknowledgeAdvice(scope: String) { writableDatabase.execSQL("UPDATE evidence SET help_seen=1 WHERE scope=?",arrayOf(scope)) }
    fun deleteArchive(scope: String) {
        val db=writableDatabase;db.beginTransaction()
        try {
            db.execSQL("INSERT OR IGNORE INTO seen(scope,id) SELECT DISTINCT scope,id FROM fragments WHERE scope=?",arrayOf(scope))
            db.delete("evidence","scope=?",arrayOf(scope));db.delete("fragments","scope=?",arrayOf(scope));db.setTransactionSuccessful()
        }
        finally { db.endTransaction() }
    }
    fun deleteMessage(scope: String,id: String) { writableDatabase.delete("evidence","scope=? AND id=?",arrayOf(scope,id)) }
    fun otherArchiveCount(scope: String): Int = readableDatabase.rawQuery(
        "SELECT (SELECT COUNT(*) FROM evidence WHERE scope<>?)+(SELECT COUNT(DISTINCT scope||':'||id) FROM fragments WHERE scope<>?)",arrayOf(scope,scope)).use{it.moveToFirst();it.getInt(0)}
    fun deleteOtherArchives(scope: String) {
        val db=writableDatabase;db.beginTransaction()
        try {
            db.execSQL("INSERT OR IGNORE INTO seen(scope,id) SELECT DISTINCT scope,id FROM fragments WHERE scope<>?",arrayOf(scope))
            for(table in listOf("evidence","fragments"))db.delete(table,"scope<>?",arrayOf(scope));db.setTransactionSuccessful()
        }
        finally { db.endTransaction() }
    }
    fun enqueue(scope: String,id: String,wire: String,role:String="child",kind:String="urgent") {
        require(role in setOf("parent","child") && kind in setOf("data","urgent","receipt","control"))
        val db=writableDatabase
        val retry=db.rawQuery("SELECT retry_at,attempts FROM outbox WHERE scope=? AND role=? AND relay_kind=? AND relay_version=1 AND accepted=0 AND attempts>0 ORDER BY retry_at LIMIT 1",arrayOf(scope,role,kind)).use{if(it.moveToFirst())it.getLong(0) to it.getInt(1) else 0L to 0}
        db.insertWithOnConflict("outbox",null,ContentValues().apply{put("scope",scope);put("id",id);put("event_id",id.substringBeforeLast(':',id));put("wire",wire);put("retry_at",retry.first);put("attempts",retry.second);put("role",role);put("relay_kind",kind);put("relay_version",1)},SQLiteDatabase.CONFLICT_IGNORE)
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
            val inserted=db.insertWithOnConflict("capture_seen",null,ContentValues().apply{put("scope",scope);put("id",id)},SQLiteDatabase.CONFLICT_IGNORE)
            if(inserted == -1L){db.setTransactionSuccessful();return false}
            enqueueBatch(scope,frames,waitForReceipt)
            db.execSQL("UPDATE outbox SET priority=?,relay_kind=? WHERE scope=? AND event_id=?",arrayOf(priority.coerceIn(0,3),if(priority>0)"urgent" else "data",scope,id))
            db.setTransactionSuccessful();return true
        } finally {db.endTransaction()}
    }
    fun captureWindow(scope:String,thread:String):List<String> = captureWindowState(scope,thread).tokens
    fun captureWindowState(scope:String,thread:String):CaptureWindow = readableDatabase.rawQuery("SELECT tokens,anchor,start_order FROM capture_windows WHERE scope=? AND thread=?",arrayOf(scope,thread)).use {
        if(!it.moveToFirst())CaptureWindow() else CaptureWindow(org.json.JSONArray(it.getString(0)).let{a->(0 until a.length()).map{n->a.getString(n)}},it.getLong(1),it.getLong(2))
    }
    fun setCaptureWindow(scope:String,thread:String,tokens:List<String>,anchor:Long=0,start:Long=0) {
        require(tokens.size<=128)
        writableDatabase.insertWithOnConflict("capture_windows",null,ContentValues().apply{put("scope",scope);put("thread",thread);put("tokens",org.json.JSONArray(tokens).toString());put("updated",System.currentTimeMillis());put("anchor",anchor);put("start_order",start)},SQLiteDatabase.CONFLICT_REPLACE)
        writableDatabase.execSQL("DELETE FROM capture_windows WHERE scope=? AND thread NOT IN (SELECT thread FROM capture_windows WHERE scope=? ORDER BY updated DESC,thread LIMIT 32)",arrayOf(scope,scope))
    }
    fun unacceptedEventCount(scope: String,id: String): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM outbox WHERE scope=? AND event_id=? AND accepted=0",arrayOf(scope,id)).use{it.moveToFirst();it.getInt(0)}
    fun due(scope: String,now: Long,role:String="child"): List<PendingEvidence> {
        fun rows(condition:String,limit:Int)=readableDatabase.rawQuery("SELECT id,wire,receipt,relay_kind FROM outbox WHERE scope=? AND role=? AND retry_at<=? AND $condition ORDER BY accepted ASC,priority DESC,rowid LIMIT ?",arrayOf(scope,role,now.toString(),limit.toString())).use { c -> buildList { while(c.moveToNext())add(PendingEvidence(c.getString(0),c.getString(1),c.getInt(2)==1,c.getString(3))) } }
        val high=rows("priority>0",24);val context=rows("priority=0",32-high.size)
        return if(context.isEmpty())rows("priority>0",32) else high+context
    }
    fun pendingCount(scope: String): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM outbox WHERE scope=?",arrayOf(scope)).use{it.moveToFirst();it.getInt(0)}
    fun pendingEvent(scope:String,id:String):Boolean = readableDatabase.rawQuery("SELECT 1 FROM outbox WHERE scope=? AND id=?",arrayOf(scope,id)).use{it.moveToFirst()}
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
    fun eventDue(scope: String,id: String,now: Long): Boolean = readableDatabase.rawQuery("SELECT 1 FROM outbox WHERE scope=? AND event_id=? AND accepted=0 AND retry_at<=? LIMIT 1",arrayOf(scope,id,now.toString())).use{it.moveToFirst()}
    fun failed(scope: String,id: String,now: Long,role:String="child",kindOnly:String?=null) { writableDatabase.execSQL("UPDATE outbox SET retry_at=?+MIN(900000,30000*(1 << MIN(attempts,5))),attempts=attempts+1 WHERE scope=? AND role=? AND retry_at<=? AND (? IS NULL OR relay_kind=?) AND EXISTS(SELECT 1 FROM outbox WHERE scope=? AND id=?)",arrayOf(now,scope,role,now,kindOnly,kindOnly,scope,id)) }
    // A manual first attempt must never cancel an issued backoff or a parent-receipt wait.
    fun retryNow(scope: String) { writableDatabase.execSQL("UPDATE outbox SET retry_at=0 WHERE scope=? AND accepted=0 AND attempts=0",arrayOf(scope)) }
    fun discardPending(scope: String) { writableDatabase.delete("outbox","scope=?",arrayOf(scope)) }
    fun rotate(old: String,new: String,rekey: (String)->String) {
        val db=writableDatabase;db.beginTransaction()
        try {
            val pending=db.rawQuery("SELECT id,wire,receipt,event_id,priority,role,relay_kind,relay_version FROM outbox WHERE scope=? AND role='child'",arrayOf(old)).use { c -> buildList {
                while(c.moveToNext())add(ContentValues().apply{put("scope",new);put("id",c.getString(0));put("wire",rekey(c.getString(1)));put("attempts",0);put("retry_at",0);put("receipt",c.getInt(2));put("accepted",0);put("event_id",c.getString(3));put("priority",c.getInt(4));put("role",c.getString(5));put("relay_kind",c.getString(6));put("relay_version",c.getInt(7))})
            } }
            for(row in pending)db.insertWithOnConflict("outbox",null,row,SQLiteDatabase.CONFLICT_IGNORE)
            db.delete("outbox","scope=? AND role='child'",arrayOf(old))
            for(table in listOf("evidence","seen","fragments","capture_seen","capture_windows")){
                db.execSQL("UPDATE OR IGNORE $table SET scope=? WHERE scope=?",arrayOf(new,old))
                db.delete(table,"scope=?",arrayOf(old))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
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
            db.rawQuery("SELECT parts,digest FROM fragments WHERE scope=? AND id=? LIMIT 1",arrayOf(scope,id)).use {
                if(it.moveToFirst() && (it.getInt(0)!=count || it.getString(1)!=digest))return null
            }
            db.rawQuery("SELECT data FROM fragments WHERE scope=? AND id=? AND part=?",arrayOf(scope,id,index.toString())).use {
                if(it.moveToFirst() && it.getString(0)!=encoded)return null
            }
            // Bound incomplete authenticated input; completed evidence is not subject to this cap.
            val incomplete=incompleteCount(scope)
            if(incomplete>=100 && !db.rawQuery("SELECT 1 FROM fragments WHERE scope=? AND id=?",arrayOf(scope,id)).use{it.moveToFirst()})return null
            db.insertWithOnConflict("fragments",null,ContentValues().apply{put("scope",scope);put("id",id);put("part",index);put("parts",count);put("digest",digest);put("data",encoded)},SQLiteDatabase.CONFLICT_IGNORE)
            var result: JSONObject?=null
            db.rawQuery("SELECT data FROM fragments WHERE scope=? AND id=? ORDER BY part",arrayOf(scope,id)).use { c ->
                if(c.count==count){
                    val out=ByteArrayOutputStream()
                    while(c.moveToNext())out.write(Base64.decode(c.getString(0),Base64.NO_WRAP))
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
