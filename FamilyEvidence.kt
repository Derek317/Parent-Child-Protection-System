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
    val savedOnly: Boolean = false, val since: Long = 0, val page: Int = 0)
data class EvidenceContact(val sender: String, val count: Int, val important: Int, val latest: Long)
data class PendingEvidence(val id: String, val wire: String, val waitForReceipt: Boolean)

/** App-private, backup-excluded storage. Paging limits the view, never the retained evidence. */
class FamilyEvidence(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "family_evidence.db", null, 1), java.io.Closeable {
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
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun hasSeen(scope: String, id: String): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM seen WHERE scope=? AND id=?", arrayOf(scope,id)).use { it.moveToFirst() }

    fun append(scope: String, id: String, alert: JSONObject, legacy: Boolean = false): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val key = ContentValues().apply { put("scope",scope);put("id",id) }
            if (db.insertWithOnConflict("seen",null,key,SQLiteDatabase.CONFLICT_IGNORE) == -1L) return false
            val payload = JSONObject(alert.toString()).put("received_at_ms",System.currentTimeMillis())
            val row = ContentValues(key).apply {
                put("sender",alert.optString("sender","未識別來源").trim().removePrefix("@").lowercase(Locale.ROOT))
                put("reason",alert.optString("reason","other"))
                put("stamp",alert.optLong("ts_ms").takeIf { it > 0 } ?: System.currentTimeMillis())
                put("body",alert.optString("text"));put("payload",payload.toString());put("legacy",if(legacy)1 else 0);put("help_seen",if(legacy)1 else 0)
            }
            db.insertOrThrow("evidence",null,row)
            db.delete("fragments","scope=? AND id=?",arrayOf(scope,id))
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }
    private fun decode(c: Cursor): JSONObject = JSONObject(c.getString(c.getColumnIndexOrThrow("payload")))
        .put("_id",c.getString(c.getColumnIndexOrThrow("id")))
        .put("_saved",c.getInt(c.getColumnIndexOrThrow("saved"))==1)
        .put("_legacy_partial",c.getInt(c.getColumnIndexOrThrow("legacy"))==1)

    fun find(scope: String,id: String): JSONObject? = readableDatabase.rawQuery(
        "SELECT * FROM evidence WHERE scope=? AND id=?",arrayOf(scope,id)).use { if(it.moveToFirst())decode(it) else null }

    private fun where(scope: String,q: EvidenceQuery): Pair<String,Array<String>> {
        val clauses = mutableListOf("scope=?");val args=mutableListOf(scope)
        if(q.sender!=null){clauses.add("sender=?");args.add(q.sender)}
        if(q.savedOnly)clauses.add("saved=1")
        if(q.since>0){clauses.add("stamp>=?");args.add(q.since.toString())}
        when(q.filter){
            "all" -> clauses.add("reason NOT IN ('heartbeat_ok','pairing_verified','test_alert')")
            "high" -> clauses.add("reason='sexual_or_grooming_content'")
            "stranger" -> clauses.add("reason='stranger_message'")
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
        return readableDatabase.rawQuery("SELECT * FROM evidence WHERE $where ORDER BY stamp DESC,id DESC LIMIT ? OFFSET ?",
            args+arrayOf(limit.coerceIn(1,61).toString(),(q.page.coerceAtLeast(0)*30L).toString())).use { c ->
            buildList { while(c.moveToNext())add(decode(c)) }
        }
    }
    fun contacts(scope: String,q: EvidenceQuery): List<EvidenceContact> {
        val (where,args)=where(scope,q)
        return readableDatabase.rawQuery("SELECT sender,COUNT(*),SUM(CASE WHEN reason='sexual_or_grooming_content' THEN 1 ELSE 0 END),MAX(stamp) AS latest FROM evidence WHERE $where GROUP BY sender ORDER BY latest DESC,sender ASC LIMIT 31 OFFSET ?",
            args+arrayOf((q.page.coerceAtLeast(0)*30L).toString())).use { c ->
            buildList { while(c.moveToNext())add(EvidenceContact(c.getString(0),c.getInt(1),c.getInt(2),c.getLong(3))) }
        }
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
    fun enqueue(scope: String,id: String,wire: String) {
        val db=writableDatabase
        val retry=db.rawQuery("SELECT COALESCE(MIN(retry_at),0) FROM outbox WHERE scope=? AND accepted=0 AND attempts>0",arrayOf(scope)).use{it.moveToFirst();it.getLong(0)}
        db.insertWithOnConflict("outbox",null,ContentValues().apply{put("scope",scope);put("id",id);put("event_id",id.substringBeforeLast(':',id));put("wire",wire);put("retry_at",retry)},SQLiteDatabase.CONFLICT_IGNORE)
    }
    fun enqueueBatch(scope: String,frames: List<Pair<String,String>>,waitForReceipt: Boolean = false) {
        require(frames.isNotEmpty() && frames.all { it.first.isNotBlank() && it.second.toByteArray(Charsets.UTF_8).size < 4096 })
        val db=writableDatabase;db.beginTransaction()
        try {
            for((id,wire) in frames){
                enqueue(scope,id,wire)
                if(waitForReceipt)db.execSQL("UPDATE outbox SET receipt=1 WHERE scope=? AND id=?",arrayOf(scope,id))
            }
            db.setTransactionSuccessful()
        }
        finally { db.endTransaction() }
    }
    fun unacceptedEventCount(scope: String,id: String): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM outbox WHERE scope=? AND event_id=? AND accepted=0",arrayOf(scope,id)).use{it.moveToFirst();it.getInt(0)}
    fun due(scope: String,now: Long): List<PendingEvidence> = readableDatabase.rawQuery(
        "SELECT id,wire,receipt FROM outbox WHERE scope=? AND retry_at<=? ORDER BY rowid LIMIT 32",arrayOf(scope,now.toString())).use { c -> buildList { while(c.moveToNext())add(PendingEvidence(c.getString(0),c.getString(1),c.getInt(2)==1)) } }
    fun pendingCount(scope: String): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM outbox WHERE scope=?",arrayOf(scope)).use{it.moveToFirst();it.getInt(0)}
    fun acknowledge(scope: String,id: String) { writableDatabase.delete("outbox","scope=? AND id=?",arrayOf(scope,id)) }
    fun relayAccepted(scope: String,id: String,now: Long) {
        writableDatabase.execSQL("UPDATE outbox SET accepted=1,retry_at=?+MIN(3600000,30000*(1 << MIN(attempts,7))),attempts=attempts+1 WHERE scope=? AND id=?",arrayOf(now,scope,id))
    }
    fun acknowledgeEvent(scope: String,id: String) { writableDatabase.delete("outbox","scope=? AND event_id=?",arrayOf(scope,id)) }
    fun eventDue(scope: String,id: String,now: Long): Boolean = readableDatabase.rawQuery("SELECT 1 FROM outbox WHERE scope=? AND event_id=? AND accepted=0 AND retry_at<=? LIMIT 1",arrayOf(scope,id,now.toString())).use{it.moveToFirst()}
    fun failed(scope: String,id: String,now: Long) { writableDatabase.execSQL("UPDATE outbox SET retry_at=?+MIN(900000,30000*(1 << MIN(attempts,5))),attempts=attempts+1 WHERE scope=? AND retry_at<=? AND EXISTS(SELECT 1 FROM outbox WHERE scope=? AND id=?)",arrayOf(now,scope,now,scope,id)) }
    fun retryNow(scope: String) { writableDatabase.execSQL("UPDATE outbox SET retry_at=0 WHERE scope=?",arrayOf(scope)) }
    fun discardPending(scope: String) { writableDatabase.delete("outbox","scope=?",arrayOf(scope)) }
    fun rotate(old: String,new: String,rekey: (String)->String) {
        val db=writableDatabase;db.beginTransaction()
        try {
            val pending=db.rawQuery("SELECT id,wire,attempts,retry_at,receipt,accepted,event_id FROM outbox WHERE scope=?",arrayOf(old)).use { c -> buildList {
                while(c.moveToNext())add(ContentValues().apply{put("scope",new);put("id",c.getString(0));put("wire",rekey(c.getString(1)));put("attempts",c.getInt(2));put("retry_at",c.getLong(3));put("receipt",c.getInt(4));put("accepted",c.getInt(5));put("event_id",c.getString(6))})
            } }
            for(row in pending)db.insertWithOnConflict("outbox",null,row,SQLiteDatabase.CONFLICT_IGNORE)
            db.delete("outbox","scope=?",arrayOf(old))
            for(table in listOf("evidence","seen","fragments")){
                db.execSQL("UPDATE OR IGNORE $table SET scope=? WHERE scope=?",arrayOf(new,old))
                db.delete(table,"scope=?",arrayOf(old))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun incompleteCount(scope: String): Int = readableDatabase.rawQuery("SELECT COUNT(DISTINCT id) FROM fragments WHERE scope=?",arrayOf(scope)).use{it.moveToFirst();it.getInt(0)}

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
                        if(json?.optString("type")=="alert" && json.optString("event_id")==id)result=json
                    }
                }
            }
            db.setTransactionSuccessful();return result
        } finally { db.endTransaction() }
    }
}

/** Bound the final encrypted HTTP body, preserving Unicode by splitting serialized UTF-8 bytes. */
object AlertFrames {
    fun digest(bytes: ByteArray): String=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)}
    fun split(alert: JSONObject): List<JSONObject> {
        require(alert.optString("type")=="alert")
        val raw=alert.toString().toByteArray(Charsets.UTF_8)
        require(raw.size<=1024*1024){"Captured alert exceeds the 1 MiB transport safety bound"}
        if(raw.size<=1800)return listOf(alert)
        val count=(raw.size+1023)/1024;val digest=digest(raw)
        return (0 until count).map { index -> JSONObject().put("type","alert_fragment").put("event_id",alert.getString("event_id"))
            .put("part",index).put("parts",count).put("digest",digest).put("receipt_requested",alert.optBoolean("receipt_requested"))
            .put("data",Base64.encodeToString(raw.copyOfRange(index*1024,minOf(raw.size,(index+1)*1024)),Base64.NO_WRAP)) }
    }
}
