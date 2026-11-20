package app.family.igguard

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.PushbackInputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * Manual upload: reads the file Instagram's "Download your information" produces (a .zip, or one message_N.html / message_N.json)
 * and turns its one-to-one conversations into messages for the same child-side store that capture uses.
 *
 * The file is hostile input. It is read as a stream, never written to disk, never rendered or executed, and bounded by [ExportLimits].
 * Message text and names are never logged. Any failure stores nothing from the file.
 */
internal enum class ImportResult { OK, NOTHING_FOUND, NOT_ALLOWED, TOO_LARGE, TOO_MANY_ENTRIES, UNSAFE_ENTRY, MALFORMED, TEEN_UNKNOWN, TIME_UNREADABLE, IO_ERROR }

/** What an import did. [messages] counts rows newly stored (a repeat import stores none); [groups] counts conversations skipped as groups. */
internal data class ImportSummary(val result: ImportResult, val conversations: Int = 0, val groups: Int = 0, val messages: Int = 0)

/** Every cap is checked while reading, so a lying zip header cannot get past it. */
internal data class ExportLimits(
    /** Entries of any kind a zip may hold. */
    val maxEntries: Int = 20_000,
    /** Uncompressed bytes of one message file. */
    val maxEntryBytes: Long = 10L shl 20,
    /** Uncompressed bytes of all message files together. */
    val maxMessageBytes: Long = 64L shl 20,
    /** Uncompressed bytes of everything the zip holds, media included (which is skipped, not kept): the bound on a zip bomb. */
    val maxInflatedBytes: Long = 1L shl 30,
    /** Characters kept of one message. */
    val maxTextChars: Int = 4_000
)

internal class ExportMessage(val outgoing: Boolean, val text: String, /** Milliseconds, or null when the file gave no usable time. */ val ts: Long?, /** The time as the file wrote it, for stable ids. */ val timeKey: String)
/** A one-to-one conversation. [messages] are oldest first. */
internal class ExportConversation(val label: String, val key: String, val messages: List<ExportMessage>)
/** [groupKeys] name each skipped group conversation (stable across imports, so a repeat does not count it twice). */
internal class ParsedExport(val result: ImportResult, val conversations: List<ExportConversation> = emptyList(), val groupKeys: List<String> = emptyList()) {
    val groups: Int get() = groupKeys.size
}

internal object InstagramExport {
    private class RawMessage(val sender: String, val ts: Long?, val timeKey: String, val text: String)
    /** One message file. [messages] are in file order (newest first); [participants] is only known from JSON. */
    private class RawFile(val number: Int, val participants: List<String>, val messages: List<RawMessage>, val json: Boolean)
    private class Failure(val result: ImportResult) : Exception()

    private val FILE_NAME = Regex("(^|/)message_(\\d{1,6})\\.(html|json)$", RegexOption.IGNORE_CASE)
    private val DRIVE = Regex("(^|[/\\\\])[A-Za-z]:")

    /**
     * Parses [input]. [fileName] is only a hint (the content decides). [childName] is the account name stored for the child, or null.
     * No Android UI and no storage: the result is plain objects.
     */
    fun parse(input: InputStream, fileName: String?, childName: String?, limits: ExportLimits = ExportLimits()): ParsedExport = try {
        val files = LinkedHashMap<String, MutableList<RawFile>>()
        val head = PushbackInputStream(input, 4)
        val magic = ByteArray(2)
        val got = readFully(head, magic)
        if (got > 0) head.unread(magic, 0, got)
        if (got == 2 && magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte()) readZip(head, limits, files)
        else {
            val bytes = readBounded(head, limits.maxEntryBytes)
            files.getOrPut("") { mutableListOf() }.add(parseFile(bytes, fileName.orEmpty(), 0, limits))
        }
        if (files.isEmpty()) ParsedExport(ImportResult.NOTHING_FOUND) else assemble(files, childName, limits)
    } catch (f: Failure) {
        ParsedExport(f.result)
    } catch (_: ZipException) {
        ParsedExport(ImportResult.MALFORMED)
    } catch (_: IOException) {
        ParsedExport(ImportResult.IO_ERROR)
    } catch (_: OutOfMemoryError) {
        ParsedExport(ImportResult.TOO_LARGE)
    } catch (_: StackOverflowError) {
        ParsedExport(ImportResult.MALFORMED)   // org.json recurses: a deeply nested file is hostile input, not a crash
    }

    private fun readFully(input: InputStream, into: ByteArray): Int {
        var n = 0
        while (n < into.size) { val r = input.read(into, n, into.size - n); if (r < 0) break; n += r }
        return n
    }

    private fun readBounded(input: InputStream, max: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(1 shl 14)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > max) throw Failure(ImportResult.TOO_LARGE)
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun unsafeName(name: String) = name.contains("..") || name.startsWith("/") || name.startsWith("\\") || name.contains('\u0000') || DRIVE.containsMatchIn(name)

    private fun readZip(input: InputStream, limits: ExportLimits, files: MutableMap<String, MutableList<RawFile>>) {
        var entries = 0
        var messageBytes = 0L
        var inflated = 0L
        val zip = ZipInputStream(input)
        val buffer = ByteArray(1 shl 14)
        while (true) {
            val entry = zip.nextEntry ?: break
            if (++entries > limits.maxEntries) throw Failure(ImportResult.TOO_MANY_ENTRIES)
            val name = entry.name
            if (unsafeName(name)) throw Failure(ImportResult.UNSAFE_ENTRY)
            val wanted = !entry.isDirectory && FILE_NAME.containsMatchIn(name)
            val out = if (wanted) ByteArrayOutputStream() else null
            var size = 0L
            while (true) {
                val n = zip.read(buffer)
                if (n < 0) break
                size += n; inflated += n
                if (inflated > limits.maxInflatedBytes) throw Failure(ImportResult.TOO_LARGE)
                if (out != null) {
                    if (size > limits.maxEntryBytes || messageBytes + size > limits.maxMessageBytes) throw Failure(ImportResult.TOO_LARGE)
                    out.write(buffer, 0, n)
                }
            }
            if (out != null) {
                messageBytes += size
                val match = FILE_NAME.find(name)!!
                files.getOrPut(name.substringBeforeLast('/', "")) { mutableListOf() }.add(parseFile(out.toByteArray(), name, match.groupValues[2].toInt(), limits))
            }
        }
    }

    private fun parseFile(bytes: ByteArray, name: String, number: Int, limits: ExportLimits): RawFile {
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) { throw Failure(ImportResult.MALFORMED) }
        val body = text.removePrefix("﻿").trimStart()
        return when {
            body.startsWith("{") -> parseJson(body, number, limits)
            body.startsWith("<") -> parseHtml(body, number, limits)
            else -> throw Failure(ImportResult.MALFORMED)
        }
    }

    /** Instagram writes UTF-8 bytes as Latin-1 code points in JSON. Re-reads each char as a byte; keeps [s] when that is not valid UTF-8. */
    fun fixMojibake(s: String): String {
        if (s.all { it.code < 0x80 }) return s
        if (s.any { it.code > 0xFF }) return s
        val bytes = ByteArray(s.length) { s[it].code.toByte() }
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) { s }
    }

    private fun clean(s: String, limits: ExportLimits) = s.replace("\u0000", "").trim().take(limits.maxTextChars)

    private fun parseJson(body: String, number: Int, limits: ExportLimits): RawFile {
        val root = try { JSONObject(body) } catch (_: Exception) { throw Failure(ImportResult.MALFORMED) }
        val participants = ArrayList<String>()
        val messages = ArrayList<RawMessage>()
        try {
            root.optJSONArray("participants")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }?.let { participants.add(fixMojibake(it)) } }
            val array = root.getJSONArray("messages")
            for (i in 0 until array.length()) {
                val m = array.optJSONObject(i) ?: continue
                val sender = fixMojibake(m.optString("sender_name")).trim()
                val ts = if (m.has("timestamp_ms") && !m.isNull("timestamp_ms")) m.optLong("timestamp_ms", -1L).takeIf { it > 0 } else null
                if (sender.isEmpty() || ts == null) continue
                // A shared post or link carries someone else's caption and a URL: only a placeholder is kept.
                val text = when {
                    m.has("share") -> "[link]"
                    listed(m, "photos") -> "[photo]"
                    listed(m, "videos") -> "[video]"
                    listed(m, "audio_files") -> "[voice note]"
                    else -> clean(fixMojibake(m.optString("content")), limits)
                }
                if (text.isNotEmpty()) messages.add(RawMessage(sender, ts, ts.toString(), text))
            }
        } catch (_: Exception) { throw Failure(ImportResult.MALFORMED) }
        return RawFile(number, participants, messages, true)
    }
    private fun listed(m: JSONObject, name: String) = (m.optJSONArray(name)?.length() ?: 0) > 0

    // ---------------- HTML ----------------
    private const val H2 = "<h2 class=\"_3-95 _2pim _a6-h _a6-i\">"
    private const val BODY = "<div class=\"_3-95 _a6-p\">"
    private const val TIME = "<div class=\"_3-94 _a6-o\">"
    private val TIMES = listOf("MMM dd, yyyy, h:mm a", "MMM d, yyyy, h:mm a", "MMM dd, yyyy h:mm a", "MMM d, yyyy h:mm a", "MMM d, yyyy 'at' h:mm a", "MMM dd, yyyy 'at' h:mm a", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm")
    private val TAG = Regex("<[^>]*>")
    private val BR = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)

    /** Index just past the element that opens at [from] (an opening tag of [tag]), counting nested ones; -1 when it never closes. */
    private fun elementEnd(s: String, from: Int, tag: String): Int {
        val re = Regex("<(/?)$tag\\b", RegexOption.IGNORE_CASE)
        var depth = 0
        var pos = from
        while (true) {
            val m = re.find(s, pos) ?: return -1
            depth += if (m.groupValues[1] == "/") -1 else 1
            val close = s.indexOf('>', m.range.last)
            if (close < 0) return -1
            pos = close + 1
            if (depth == 0) return pos
        }
    }

    private val ENTITY = Regex("&(#x[0-9a-fA-F]{1,6}|#[0-9]{1,7}|[a-zA-Z]{2,6});")
    private fun entities(s: String): String = ENTITY.replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") -> codePoint(e.substring(2).toInt(16))
            e.startsWith("#") -> codePoint(e.substring(1).toInt())
            else -> when (e) { "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"; "nbsp" -> " "; else -> m.value }
        }
    }
    private fun codePoint(c: Int) = if (c in 1..0x10FFFF && c !in 0xD800..0xDFFF) String(Character.toChars(c)) else ""

    /** Epoch milliseconds, or null when none of the supported formats reads the whole string. */
    fun parseTime(raw: String): Long? = parseTime(raw, timeFormats())
    private fun timeFormats() = TIMES.map { SimpleDateFormat(it, Locale.ENGLISH).apply { isLenient = false } }
    private val SPACES = Regex("\\s+")
    private fun parseTime(raw: String, formats: List<SimpleDateFormat>): Long? {
        val text = raw.replace(' ', ' ').replace(' ', ' ').trim().replace(SPACES, " ")
        for (format in formats) {
            val pos = ParsePosition(0)
            val date = format.parse(text, pos)
            if (date != null && pos.index == text.length) return date.time
        }
        return null
    }

    private fun parseHtml(body: String, number: Int, limits: ExportLimits): RawFile {
        val messages = ArrayList<RawMessage>()
        val formats = timeFormats()
        var start = body.indexOf(H2)
        while (start >= 0) {
            val next = body.indexOf(H2, start + H2.length)
            val chunk = body.substring(start, if (next < 0) body.length else next)
            start = next
            val senderEnd = chunk.indexOf("</h2>")
            val bodyAt = chunk.indexOf(BODY)
            if (senderEnd < 0 || bodyAt < 0) continue
            val sender = entities(chunk.substring(H2.length, senderEnd).replace(TAG, "")).trim()
            val bodyEnd = elementEnd(chunk, bodyAt, "div")
            if (sender.isEmpty() || bodyEnd < 0) continue
            // Reactions sit in <ul class="_a6-q"> inside the body: they are not part of the message.
            var inner = chunk.substring(bodyAt + BODY.length, bodyEnd)
            while (true) {
                val ul = inner.indexOf("<ul class=\"_a6-q\"")
                if (ul < 0) break
                val end = elementEnd(inner, ul, "ul")
                inner = if (end < 0) inner.substring(0, ul) else inner.substring(0, ul) + inner.substring(end)
            }
            val lower = inner.lowercase(Locale.ROOT)
            val text = when {
                lower.contains("<a ") -> "[link]"
                lower.contains("<video") -> "[video]"
                lower.contains("<audio") -> "[voice note]"
                lower.contains("<img") -> "[photo]"
                else -> clean(entities(inner.replace(BR, "\n").replace(TAG, "")), limits)
            }
            val timeAt = chunk.indexOf(TIME, bodyEnd)
            val rawTime = if (timeAt < 0) "" else chunk.substring(timeAt + TIME.length).substringBefore("</div>").replace(TAG, "").let { entities(it).trim() }
            if (text.isNotEmpty()) messages.add(RawMessage(sender, if (rawTime.isEmpty()) null else parseTime(rawTime, formats), rawTime, text))
        }
        return RawFile(number, emptyList(), messages, false)
    }

    // ---------------- assembling ----------------
    private fun key(name: String) = name.trim().removePrefix("@").lowercase(Locale.ROOT)

    private class Conversation(val people: LinkedHashMap<String, String>, val listed: List<String>, val json: Boolean, val ordered: List<RawMessage>)

    private fun assemble(files: Map<String, List<RawFile>>, childName: String?, limits: ExportLimits): ParsedExport {
        val conversations = files.values.map { group ->
            val sorted = group.sortedBy { it.number }
            val people = LinkedHashMap<String, String>()
            val listed = sorted.firstOrNull { it.participants.isNotEmpty() }?.participants.orEmpty()
            for (name in listed + sorted.flatMap { f -> f.messages.map { it.sender } }) people.putIfAbsent(key(name), name.trim())
            // Files are numbered newest first and each lists its messages newest first, so reversing the whole gives oldest first.
            val reversed = sorted.flatMap { it.messages }.asReversed()
            val ordered = if (reversed.all { it.ts != null }) reversed.sortedBy { it.ts } else reversed   // sortedBy is stable
            Conversation(people, listed.map(::key), sorted.any { it.json }, ordered)
        }
        // The teen is the one participant every conversation has in common. A thread with one person (the teen never answered) must not empty that,
        // so the two-person threads are tried first; only if they settle nothing do all threads count, as before (a lone thread may be the teen's own).
        fun commonOf(list: List<Conversation>): Set<String> = if (list.size < 2) emptySet() else list.map<Conversation, Set<String>> { it.people.keys }.reduce { a, b -> a intersect b }
        val common = commonOf(conversations.filter { it.people.size >= 2 }).ifEmpty { commonOf(conversations) }
        val global = common.singleOrNull()
        val child = childName?.let(::key)?.takeIf { it.isNotEmpty() }
        val groupKeys = ArrayList<String>()
        val result = ArrayList<ExportConversation>()
        for (c in conversations) {
            if (c.people.size != 2) { groupKeys.add("group:" + c.people.keys.sorted().joinToString("|")); continue }
            val teen = when {
                global != null && global in c.people -> global
                child != null && child in c.people -> child   // a stored handle may differ from the export's display name: then the JSON list below decides
                c.json && c.listed.size == 2 && c.listed.all { it in c.people } -> c.listed[1]   // the first-listed participant is the other person
                else -> throw Failure(ImportResult.TEEN_UNKNOWN)
            }
            val other = c.people.keys.first { it != teen }
            val label = c.people.getValue(other)
            val messages = c.ordered.map { ExportMessage(key(it.sender) == teen, it.text, it.ts, it.timeKey) }
            result.add(ExportConversation(label, key(label), messages))
        }
        if (result.isEmpty() && groupKeys.isEmpty()) return ParsedExport(ImportResult.NOTHING_FOUND)
        return ParsedExport(ImportResult.OK, result, groupKeys.distinct())
    }
}

/** The thin caller: opens the picked file as a stream, parses it, and stores the result as one transaction. Runs off the main thread. */
internal object InstagramImport {
    /** Whether an import may run now: the child agreed to manual upload, nothing paused or stopped, and analysis can run. */
    fun allowed(ctx: Context): Boolean = RiskLocal.active(ctx) && RiskLocal.agreedMethod(ctx) == ScoresMethod.UPLOAD

    @Volatile var running = false
    @Volatile var last: ImportSummary? = null
    @Volatile var listener: (() -> Unit)? = null
    /** Test seam: how a picked file is run. Production starts a thread. */
    @Volatile var runner: (Runnable) -> Unit = { Thread(it, "Instagram import").start() }

    fun dismiss() { last = null }

    /** Starts an import of the file at [uri]. False if one is already running. */
    fun start(ctx: Context, uri: Uri): Boolean {
        val app = ctx.applicationContext
        synchronized(this) { if (running) return false; running = true; last = null }
        runner(Runnable {
            val summary = try {
                val stream = app.contentResolver.openInputStream(uri)
                if (stream == null) ImportSummary(ImportResult.IO_ERROR) else stream.use { run(app, it, uri.lastPathSegment) }
            } catch (_: Exception) { ImportSummary(ImportResult.IO_ERROR) }
            synchronized(this) { last = summary; running = false }
            listener?.invoke()
        })
        listener?.invoke()
        return true
    }

    /** Parses and stores one file. Nothing is stored unless the whole file parsed. */
    fun run(ctx: Context, input: InputStream, fileName: String?, limits: ExportLimits = ExportLimits(), now: Long = System.currentTimeMillis()): ImportSummary {
        val app = ctx.applicationContext
        if (!allowed(app)) return ImportSummary(ImportResult.NOT_ALLOWED)
        val parsed = InstagramExport.parse(input, fileName, GuardEngine.getChildSelfHandle(app).ifBlank { null }, limits)
        if (parsed.result != ImportResult.OK) return ImportSummary(parsed.result)
        // A time that cannot be read cannot be held to the 14-day limit: such messages are dropped, and a file with nothing but those is not a good export.
        val all = parsed.conversations.flatMap { it.messages }
        if (all.isNotEmpty() && all.all { it.ts == null }) return ImportSummary(ImportResult.TIME_UNREADABLE)
        return try {
            val scope = GuardEngine.getChannelTopic(app)
            val cutoff = now - RiskLimits.DAYS * 86_400_000L
            var stored = 0
            var conversations = 0
            // The parse can be long: a wipe, pause, withdrawal or method change meanwhile must win, so permission is checked again under the session lock.
            val stillAllowed = GuardEngine.withSessionLock { if (!allowed(app)) false else {
            FamilyEvidence(app).use { db ->
                val sql = db.writableDatabase
                sql.beginTransaction()
                try {
                    for (c in parsed.conversations) {
                        val recent = c.messages.filter { it.ts != null && it.ts >= cutoff }.takeLast(RiskLimits.MESSAGES)
                        if (recent.isEmpty()) continue
                        conversations++
                        val seen = HashMap<String, Int>()
                        for ((i, m) in recent.withIndex()) {
                            val direction = if (m.outgoing) "outgoing" else "incoming"
                            val basis = "${c.key}|${m.timeKey}|$direction|${m.text}"
                            val occurrence = seen.merge(basis, 1, Int::plus)!!
                            val id = "import:" + MessageDigest.getInstance("SHA-256").digest("$basis|$occurrence".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(40)
                            val stamp = minOf(m.ts!!, now)   // undated messages were dropped above
                            if (db.storeRiskMessage(scope, id, c.key, c.label, direction, m.text, stamp, stamp, i.toLong(), now)) stored++
                        }
                    }
                    for (g in parsed.groupKeys) db.noteRiskUnknown(scope, g, now)
                    sql.setTransactionSuccessful()
                } finally { sql.endTransaction() }
            }
            true } }
            if (!stillAllowed) return ImportSummary(ImportResult.NOT_ALLOWED)
            try { RiskScoringSchedule.sync(app) } catch (_: RuntimeException) {}
            ImportSummary(ImportResult.OK, conversations, parsed.groups, stored)
        } catch (_: Exception) {
            ImportSummary(ImportResult.IO_ERROR)
        }
    }
}
