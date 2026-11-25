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
import java.util.Calendar
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
internal enum class ImportResult { OK, NOTHING_FOUND, NOT_ALLOWED, TOO_LARGE, TOO_MANY_ENTRIES, UNSAFE_ENTRY, MALFORMED, TEEN_UNKNOWN, TIME_UNREADABLE, TOO_OLD, IO_ERROR }

/** What an import did. [messages] counts rows newly stored (a repeat import stores none); [groups] counts conversations skipped as groups. */
internal data class ImportSummary(val result: ImportResult, val conversations: Int = 0, val groups: Int = 0, val messages: Int = 0)

/** Every cap is checked while reading, so a lying zip header cannot get past it. */
internal data class ExportLimits(
    /** Entries of any kind a zip may hold. */
    val maxEntries: Int = 20_000,
    /** Uncompressed bytes of one message file. */
    val maxEntryBytes: Long = 10L shl 20,
    /** Uncompressed bytes of all message files together, counting only what is kept: a file whose messages are all older than the window counts its names alone. */
    val maxMessageBytes: Long = 64L shl 20,
    /** Uncompressed bytes of everything the zip holds, media included (which is skipped, not kept): the bound on a zip bomb. */
    val maxInflatedBytes: Long = 1L shl 30,
    /** Characters kept of one message. */
    val maxTextChars: Int = 4_000
)

internal class ExportMessage(val outgoing: Boolean, val text: String, /** Milliseconds, or null when the file gave no usable time. */ val ts: Long?, /** The time as the file wrote it: only to find rows stored by 6.0.1 and earlier. */ val timeKey: String)
/** A one-to-one conversation. [messages] are oldest first. [old]: messages older than the window were dropped while reading. */
internal class ExportConversation(val label: String, val key: String, val messages: List<ExportMessage>, val old: Boolean = false)
/** [groupKeys] name each skipped group conversation (stable across imports, so a repeat does not count it twice). */
internal class ParsedExport(val result: ImportResult, val conversations: List<ExportConversation> = emptyList(), val groupKeys: List<String> = emptyList()) {
    val groups: Int get() = groupKeys.size
}

internal object InstagramExport {
    private class RawMessage(val sender: String, val ts: Long?, val timeKey: String, val text: String)
    /** One message file. [messages] are in file order (newest first); [senders] name everyone who wrote, kept or not; [participants] is only known from JSON. */
    private class RawFile(val number: Int, val participants: List<String>, val senders: Collection<String>, val messages: List<RawMessage>, val json: Boolean, val old: Boolean)
    private class Failure(val result: ImportResult) : Exception()

    private val FILE_NAME = Regex("(^|/)message_(\\d{1,6})\\.(html|json)$", RegexOption.IGNORE_CASE)
    private val DRIVE = Regex("(^|[/\\\\])[A-Za-z]:")

    /**
     * Parses [input]. [fileName] is only a hint (the content decides). [childName] is the account name stored for the child, or null.
     * Messages dated before [since] are dropped while reading (their senders still count). No Android UI and no storage: the result is plain objects.
     */
    fun parse(input: InputStream, fileName: String?, childName: String?, limits: ExportLimits = ExportLimits(), since: Long? = null): ParsedExport = try {
        val files = LinkedHashMap<String, MutableList<RawFile>>()
        val head = PushbackInputStream(input, 4)
        val magic = ByteArray(2)
        val got = readFully(head, magic)
        if (got > 0) head.unread(magic, 0, got)
        if (got == 2 && magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte()) readZip(head, limits, since, files)
        else {
            val bytes = readBounded(head, limits.maxEntryBytes)
            files.getOrPut("") { mutableListOf() }.add(parseFile(bytes, fileName.orEmpty(), 0, limits, since))
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

    private fun readZip(input: InputStream, limits: ExportLimits, since: Long?, files: MutableMap<String, MutableList<RawFile>>) {
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
                    if (size > limits.maxEntryBytes) throw Failure(ImportResult.TOO_LARGE)
                    out.write(buffer, 0, n)
                }
            }
            if (out != null) {
                val match = FILE_NAME.find(name)!!
                val file = parseFile(out.toByteArray(), name, match.groupValues[2].toInt(), limits, since)
                // What is kept counts: the whole file if it keeps a message, else only the names it keeps.
                messageBytes += if (file.messages.isNotEmpty()) size else (file.senders + file.participants).sumOf { it.length.toLong() }
                if (messageBytes > limits.maxMessageBytes) throw Failure(ImportResult.TOO_LARGE)
                files.getOrPut(name.substringBeforeLast('/', "")) { mutableListOf() }.add(file)
            }
        }
    }

    private fun parseFile(bytes: ByteArray, name: String, number: Int, limits: ExportLimits, since: Long?): RawFile {
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) { throw Failure(ImportResult.MALFORMED) }
        val body = text.removePrefix("\uFEFF").trimStart()
        return when {
            body.startsWith("{") -> parseJson(body, number, limits, since)
            body.startsWith("<") -> parseHtml(body, number, limits, since)
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

    private fun parseJson(body: String, number: Int, limits: ExportLimits, since: Long?): RawFile {
        val root = try { JSONObject(body) } catch (_: Exception) { throw Failure(ImportResult.MALFORMED) }
        val participants = ArrayList<String>()
        val messages = ArrayList<RawMessage>()
        val senders = LinkedHashSet<String>()
        var old = false
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
                if (text.isEmpty()) continue
                senders.add(sender)
                if (since != null && ts < since) old = true else messages.add(RawMessage(sender, ts, ts.toString(), text))
            }
        } catch (_: Exception) { throw Failure(ImportResult.MALFORMED) }
        return RawFile(number, participants, senders, messages, true, old)
    }
    private fun listed(m: JSONObject, name: String) = (m.optJSONArray(name)?.length() ?: 0) > 0

    // ---------------- HTML ----------------
    private const val H2 = "<h2 class=\"_3-95 _2pim _a6-h _a6-i\">"
    private const val BODY = "<div class=\"_3-95 _a6-p\">"
    private const val TIME = "<div class=\"_3-94 _a6-o\">"
    private val TIMES = listOf("MMM dd, yyyy, h:mm a", "MMM d, yyyy, h:mm a", "MMM dd, yyyy h:mm a", "MMM d, yyyy h:mm a", "MMM d, yyyy 'at' h:mm a", "MMM dd, yyyy 'at' h:mm a", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm")
    private val TAG = Regex("<[^<>]*>")   // stops at the next "<": a run of unclosed "<" is linear, not quadratic (security-audit L6)
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
    /** A Chinese-language account writes "11 月 13, 2026 9:05 下午": month 1-12, 12-hour clock, 上午 = AM, 下午 = PM. Device time zone, like the other formats. */
    private val ZH_TIME = Regex("^(\\d{1,2}) 月 (\\d{1,2}), (\\d{4}) (\\d{1,2}):(\\d{2}) (上午|下午)$")
    private fun zhTime(text: String): Long? {
        val m = ZH_TIME.matchEntire(text)?.groupValues ?: return null
        val hour = m[4].toInt()
        if (hour !in 1..12) return null
        return try {
            Calendar.getInstance().apply { isLenient = false; clear(); set(m[3].toInt(), m[1].toInt() - 1, m[2].toInt(), hour % 12 + if (m[6] == "下午") 12 else 0, m[5].toInt(), 0) }.timeInMillis
        } catch (_: IllegalArgumentException) { null }
    }
    private fun parseTime(raw: String, formats: List<SimpleDateFormat>): Long? {
        val text = raw.replace(' ', ' ').replace(' ', ' ').trim().replace(SPACES, " ")
        zhTime(text)?.let { return it }
        for (format in formats) {
            val pos = ParsePosition(0)
            val date = format.parse(text, pos)
            if (date != null && pos.index == text.length) return date.time
        }
        return null
    }

    private fun parseHtml(body: String, number: Int, limits: ExportLimits, since: Long?): RawFile {
        val messages = ArrayList<RawMessage>()
        val senders = LinkedHashSet<String>()
        var old = false
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
            if (text.isEmpty()) continue
            senders.add(sender)
            val ts = if (rawTime.isEmpty()) null else parseTime(rawTime, formats)
            if (since != null && ts != null && ts < since) old = true else messages.add(RawMessage(sender, ts, rawTime, text))
        }
        return RawFile(number, emptyList(), senders, messages, false, old)
    }

    // ---------------- assembling ----------------
    private fun key(name: String) = name.trim().removePrefix("@").lowercase(Locale.ROOT)

    private val THREAD_NUMBER = Regex("^(.*)_(\\d+)$")
    /** The folder's last segment without its "_" and thread number. */
    private fun folderName(folder: String) = folder.substringAfterLast('/').let { THREAD_NUMBER.matchEntire(it)?.groupValues?.get(1) ?: it }
    private fun letters(s: String) = s.lowercase(Locale.ROOT).filter { it in 'a'..'z' || it in '0'..'9' }

    private class Conversation(val folder: String, val people: LinkedHashMap<String, String>, val listed: List<String>, val json: Boolean, val ordered: List<RawMessage>, val old: Boolean)

    private fun assemble(files: Map<String, List<RawFile>>, childName: String?, limits: ExportLimits): ParsedExport {
        val conversations = files.map { (folder, group) ->
            val sorted = group.sortedBy { it.number }
            val people = LinkedHashMap<String, String>()
            val listed = sorted.firstOrNull { it.participants.isNotEmpty() }?.participants.orEmpty()
            for (name in listed + sorted.flatMap { it.senders }) people.putIfAbsent(key(name), name.trim())
            // Files are numbered newest first and each lists its messages newest first, so reversing the whole gives oldest first.
            val reversed = sorted.flatMap { it.messages }.asReversed()
            val ordered = if (reversed.all { it.ts != null }) reversed.sortedBy { it.ts } else reversed   // sortedBy is stable
            Conversation(folder, people, listed.map(::key), sorted.any { it.json }, ordered, sorted.any { it.old })
        }
        // The teen is in every thread they wrote in, but HTML names only who wrote, so a group the teen never wrote in lacks them.
        // So the teen is the one person in more threads than anyone else (and in two at least): threads of two or more people first,
        // then all threads (a lone thread may be the teen's own). A tie is no guess.
        fun mostShared(list: List<Conversation>): String? {
            val counts = list.flatMap { it.people.keys }.groupingBy { it }.eachCount()
            val top = counts.values.maxOrNull() ?: return null
            return if (top < 2) null else counts.filterValues { it == top }.keys.singleOrNull()
        }
        // Instagram names a conversation's folder after the other person (or the group), then "_" and the thread number. In a two-person
        // thread the one whose name matches the folder is the other person, so the remaining one is the teen. Letters and digits only:
        // a name the folder cannot show (Chinese, say) matches nothing. Threads that disagree are no answer.
        val byFolder = conversations.mapNotNull { c ->
            val name = letters(folderName(c.folder))
            val other = if (name.isEmpty() || c.people.size != 2) null else c.people.keys.singleOrNull { letters(it) == name }
            other?.let { o -> c.people.keys.first { it != o } }
        }.distinct().singleOrNull()
        // Else a stored name found in the file is the teen. It may be a handle that is not the export's display name: then the file decides.
        val child = childName?.let(::key)?.takeIf { n -> n.isNotEmpty() && conversations.any { n in it.people } }
        val global = byFolder ?: child ?: mostShared(conversations.filter { it.people.size >= 2 }) ?: mostShared(conversations)
        val groupKeys = ArrayList<String>()
        val result = ArrayList<ExportConversation>()
        for (c in conversations) {
            val teen = when {
                global != null && global in c.people -> global
                c.json && c.listed.size == 2 && c.listed.all { it in c.people } -> c.listed[1]   // the first-listed participant is the other person
                c.people.size == 2 && global == null -> throw Failure(ImportResult.TEEN_UNKNOWN)
                else -> null   // with the teen known, a thread without them is one they never wrote in
            }
            // One other person: a one-to-one chat, all incoming if the teen never answered. A lone sender with the teen unknown may be the teen.
            val others = c.people.keys.filter { it != teen }
            if (others.size != 1 || (teen == null && global == null)) { groupKeys.add("group:" + c.people.keys.sorted().joinToString("|")); continue }
            val other = others.single()
            val label = c.people.getValue(other)
            val messages = c.ordered.map { ExportMessage(key(it.sender) == teen, it.text, it.ts, it.timeKey) }
            // A zip folder ends in the conversation's own thread number: it keys the conversation, so a new display name, another
            // language or two accounts with one name change nothing. A folder without one is keyed by its name; a lone file has no
            // folder and is keyed by the other person's name, as capture keys threads.
            val segment = c.folder.substringAfterLast('/')
            val thread = when {
                c.folder.isEmpty() -> key(label)
                else -> THREAD_NUMBER.matchEntire(segment)?.let { "thread:" + it.groupValues[2] } ?: ("folder:" + segment.lowercase(Locale.ROOT))
            }
            result.add(ExportConversation(label, thread, messages, c.old))
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
    /** Test seam: runs inside the import's transaction, just before it commits. */
    @Volatile internal var beforeCommit: () -> Unit = {}
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
            } catch (_: Throwable) { ImportSummary(ImportResult.IO_ERROR) }   // an Error too: `running` must never stay true (bug-review B-6)
            synchronized(this) { last = summary; running = false }
            listener?.invoke()
        })
        listener?.invoke()
        return true
    }

    /** One id per message of a thread keyed [key]; the same text, direction and [time] again is told apart by its occurrence. */
    private fun ids(key: String, messages: List<ExportMessage>, time: (ExportMessage) -> String): List<String> {
        val seen = HashMap<String, Int>()
        return messages.map { m ->
            val basis = "$key|${time(m)}|${if (m.outgoing) "outgoing" else "incoming"}|${m.text}"
            "import:" + MessageDigest.getInstance("SHA-256").digest("$basis|${seen.merge(basis, 1, Int::plus)}".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(40)
        }
    }

    /** Parses and stores one file. Nothing is stored unless the whole file parsed. */
    fun run(ctx: Context, input: InputStream, fileName: String?, limits: ExportLimits = ExportLimits(), now: Long = System.currentTimeMillis()): ImportSummary {
        val app = ctx.applicationContext
        if (!allowed(app)) return ImportSummary(ImportResult.NOT_ALLOWED)
        val cutoff = now - RiskLimits.DAYS * 86_400_000L
        // Only the 14-day window is kept, so older messages are dropped while reading: an export too large in full can still be read.
        val parsed = InstagramExport.parse(input, fileName, GuardEngine.getChildSelfHandle(app).ifBlank { null }, limits, cutoff)
        if (parsed.result != ImportResult.OK) return ImportSummary(parsed.result)
        // A time that cannot be read cannot be held to the 14-day limit: such messages are dropped, and a file with nothing but those is not a good export.
        val all = parsed.conversations.flatMap { it.messages }
        if (all.isNotEmpty() && all.all { it.ts == null } && parsed.conversations.none { it.old }) return ImportSummary(ImportResult.TIME_UNREADABLE)
        return try {
            val scope = GuardEngine.getChannelTopic(app)
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
                        // The id is the time read to the minute, so JSON, HTML and any language give one id (undated messages were dropped above).
                        val ids = ids(c.key, recent) { Math.floorDiv(it.ts!!, 60_000L).toString() }
                        // 6.0.1 and earlier keyed the id by the time as the file wrote it, and the thread by the name: such a row is replaced, not kept beside the new one.
                        val legacyKey = c.label.trim().removePrefix("@").lowercase(Locale.ROOT)
                        val before = ids(legacyKey, recent) { it.timeKey }
                        val replaced = before.map { sql.delete("risk_store", "scope=? AND id=?", arrayOf(scope, it)) > 0 }
                        // A 6.0.x thread of this conversation (found by an old id) continues as this one: its other messages and its score move here.
                        if (replaced.any { it }) db.moveRiskThread(scope, legacyKey, c.key)
                        var added = false
                        for ((i, m) in recent.withIndex()) {
                            val direction = if (m.outgoing) "outgoing" else "incoming"
                            val stamp = minOf(m.ts!!, now)
                            if (db.storeRiskMessage(scope, ids[i], c.key, c.label, direction, m.text, stamp, stamp, i.toLong(), now)) { added = true; if (!replaced[i]) stored++ }
                        }
                        // The thread's next score replaces its earlier ones; until it is written, the earlier score stands.
                        if (added) db.rescoreRiskThread(scope, c.key)
                    }
                    for (g in parsed.groupKeys) db.noteRiskUnknown(scope, g, now)
                    beforeCommit()
                    sql.setTransactionSuccessful()
                } finally { sql.endTransaction() }
            }
            // Under the same lock as the store, so a withdrawal or switch-off cannot leave the time behind its wipe.
            if (conversations > 0) RiskLocal.recordImport(app, now)
            true } }
            if (!stillAllowed) return ImportSummary(ImportResult.NOT_ALLOWED)
            try { RiskScoringSchedule.sync(app) } catch (_: RuntimeException) {}
            // One-to-one chats with readable times, none recent: the file is too old, not empty (bug-review B-2).
            if (conversations == 0 && parsed.conversations.any { c -> c.old || c.messages.any { it.ts != null } }) return ImportSummary(ImportResult.TOO_OLD, 0, parsed.groups, stored)
            if (conversations > 0) try { RiskScoringSchedule.afterImport(app) } catch (_: RuntimeException) {}
            ImportSummary(ImportResult.OK, conversations, parsed.groups, stored)
        } catch (_: Exception) {
            ImportSummary(ImportResult.IO_ERROR)
        }
    }
}
