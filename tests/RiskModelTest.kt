package app.family.igguard

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import kotlin.math.ln

class RiskModelTest {
    private val root: File = run {
        val vector = File(requireNotNull(System.getProperty("igguard.protocol.vector")) { "igguard.protocol.vector is not set" })
        File(vector.parentFile, "../..").canonicalFile
    }
    private fun file(path: String) = File(root, path).also { assertTrue("Missing ${it.path}", it.isFile) }
    private val fixture = JSONObject(file("tests/fixtures/risk-prompt-fixture.json").readText())
    private val instructionBytes = file("research/prompts/instruction-v1.txt").readBytes()
    private val messages = fixture.getJSONArray("messages").let { rows ->
        List(rows.length()) { RiskMessage(rows.getJSONObject(it).getString("speaker"), rows.getJSONObject(it).getString("text")) }
    }
    private fun fakes(n: Int) = List(n) { RiskMessage("child", "m$it") }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun promptMatchesTheHarnessByteForByte() {
        val prompt = RiskPrompt.render(instructionBytes.decodeToString(), messages)
        assertArrayEquals(fixture.getString("prompt").toByteArray(), prompt.toByteArray())
    }

    /** Fits when the rendered prompt is at most [limit] characters; records every prompt it was given. */
    private class LengthScorer(val limit: Int) : RiskScorer {
        val prompts = mutableListOf<String>()
        override fun score(prompt: String): FloatArray { prompts += prompt; if (prompt.length > limit) throw RiskContextTooLong(); return FloatArray(5) }
        override fun close() {}
    }
    private fun windowOf(prompt: String) = prompt.substringAfterLast("Chat window:\n").substringBefore("\n\nAnswer").lines()

    @Test fun anOverflowingWindowDropsTheOldestThenShortensButNeverCutsTheNewest() {
        val instruction = instructionBytes.decodeToString()
        val base = RiskPrompt.render(instruction, emptyList()).length
        // Fits as is: the prompt is unchanged and scored once.
        LengthScorer(Int.MAX_VALUE).also { RiskFit.score(it, instruction, fakes(40)); assertEquals(listOf(RiskPrompt.render(instruction, fakes(40))), it.prompts) }
        // Padding: 30 long messages. Oldest are dropped first, down to MIN_KEPT, then the older ones are shortened.
        val padded = List(30) { RiskMessage(if (it % 2 == 0) "other" else "child", "第${it}句" + "長".repeat(400)) }
        val limit = base + 1500
        val s = LengthScorer(limit)
        RiskFit.score(s, instruction, padded)
        val fed = windowOf(s.prompts.last())
        assertTrue(s.prompts.last().length <= limit)
        assertEquals(RiskFit.MIN_KEPT, fed.size)
        assertEquals("the newest message is kept whole", "child: ${padded.last().text}", fed.last())
        assertTrue("older messages are shortened", fed.dropLast(1).all { it.length < padded[0].text.length })
        assertTrue("the oldest were dropped", fed.first().startsWith("other: 第20句"))
        assertEquals("only the messages differ: same instruction, template and answer cue",
            RiskPrompt.render(instruction, fed.map { RiskMessage(it.substringBefore(": "), it.substringAfter(": ")) }), s.prompts.last())
        // A single message too long by itself is still context_too_long.
        try { RiskFit.score(LengthScorer(base + 100), instruction, fakes(3) + RiskMessage("other", "x".repeat(500))); fail("expected context too long") }
        catch (_: RiskContextTooLong) { }
        // Shortening never splits a surrogate pair.
        assertEquals("a", RiskFit.shorten(RiskMessage("other", "a😀b"), 2).text)
    }

    @Test fun collapseUsesPythonWhitespace() {
        val spaces = fixture.getJSONArray("python_whitespace_codepoints")
        for (i in 0 until spaces.length()) {
            val c = String(Character.toChars(spaces.getString(i).toInt(16)))
            assertEquals("U+${spaces.getString(i)}", "a b", RiskPrompt.collapse("a$c${c}b"))
            assertEquals("", RiskPrompt.collapse(c))
        }
        for (c in listOf("​", "᠎", "﻿")) assertEquals("a${c}b", RiskPrompt.collapse("a${c}b"))
        assertEquals("a b c", RiskPrompt.collapse("  a \t b\n\nc  "))
    }

    @Test fun instructionIsPinnedAndShippedUnchanged() {
        assertEquals(RiskModelSpec.INSTRUCTION_SHA256, sha(instructionBytes))
        assertEquals(fixture.getString("instruction_sha256"), RiskModelSpec.INSTRUCTION_SHA256)
        assertArrayEquals(instructionBytes, file("ui-res/raw/risk_instruction_v1.txt").readBytes())
    }

    @Test fun fixtureVectorsReproduceTheHarnessScores() {
        val vectors = fixture.getJSONArray("vectors")
        for (i in 0 until vectors.length()) {
            val v = vectors.getJSONObject(i)
            val p = DoubleArray(5) { v.getJSONArray("raw").getDouble(it) }
            // Float logits carry about 1e-6 relative error, so that path gets a looser tolerance.
            for ((score, tolerance) in listOf(
                RiskScoring.fromProbabilities(p) to 1e-9,
                RiskScoring.fromLogits(FloatArray(5) { ln(p[it]).toFloat() }) to 1e-4
            )) {
                assertEquals("alert $i", v.getDouble("alert_score"), score.alert, tolerance)
                assertEquals("answer $i", v.getString("answer"), score.answer)
                if (v.isNull("report_score")) assertNull("report $i", score.reportScore)
                else assertEquals("report $i", v.getDouble("report_score"), score.reportScore!!, tolerance * 100)
                // A flag decided on an exact 0.5 tie is not stable through float logits.
                if (tolerance < 1e-6) assertEquals("flagged $i", v.getBoolean("flagged"), score.flagged)
            }
        }
    }

    @Test fun allZeroOrNonFiniteProbabilitiesAreErrors() {
        assertThrows(IllegalArgumentException::class.java) { RiskScoring.fromProbabilities(DoubleArray(5)) }
        assertThrows(IllegalArgumentException::class.java) { RiskScoring.fromProbabilities(doubleArrayOf(1.0, Double.NaN, 0.0, 0.0, 0.0)) }
        assertThrows(IllegalArgumentException::class.java) { RiskScoring.fromLogits(FloatArray(5) { Float.NEGATIVE_INFINITY }) }
    }

    @Test fun modelFileDigestAndMatch() {
        val bytes = "abc".toByteArray()
        val seen = mutableListOf<Long>()
        val (size, hash) = RiskModelFile.sha256(bytes.inputStream()) { seen.add(it) }
        assertEquals(3L, size)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hash)
        assertEquals(listOf(3L), seen)
        assertTrue(RiskModelFile.matches(RiskModelSpec.FILE_BYTES, RiskModelSpec.FILE_SHA256))
        assertFalse(RiskModelFile.matches(RiskModelSpec.FILE_BYTES - 1, RiskModelSpec.FILE_SHA256))
        assertFalse(RiskModelFile.matches(RiskModelSpec.FILE_BYTES, RiskModelSpec.FILE_SHA256.replace('a', 'b')))
        // The pinned file is Beacon; the earlier v3, v4 and v7 files (same size, different hash) are refused.
        assertEquals("igguard/qwen3-1.7b-beacon-Q4_K_M", RiskModelSpec.MODEL_NAME)
        assertEquals("qwen3-1.7b-beacon-Q4_K_M.gguf", RiskModelSpec.FILE_NAME)
        assertEquals(1_107_408_736L, RiskModelSpec.FILE_BYTES)
        assertEquals("5b873dbfa4c36fff10372f8c1cf28ae84182d6d41604bf8f8b83f21966debb5c", RiskModelSpec.FILE_SHA256)
        assertFalse(RiskModelFile.matches(RiskModelSpec.FILE_BYTES, "32c05d65e380072e01ab194021419ec5bb8026a07fdcea5290e773a0995dff60"))
        assertFalse(RiskModelFile.matches(RiskModelSpec.FILE_BYTES, "14f2176d569772bcc8e0e0dcd97e59f7ac60d8c4f6e1cb5f6a45159b1892c293"))
        assertFalse(RiskModelFile.matches(RiskModelSpec.FILE_BYTES, "833efa22ff726b0df4c6461fd61c12409b9dd7eddfee35417c7c342a6a435314"))
    }
}
