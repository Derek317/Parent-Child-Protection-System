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

    @Test fun windowsCountBackFromTheNewestMessage() {
        assertEquals(emptyList<List<RiskMessage>>(), RiskPrompt.windows(emptyList()))
        assertEquals(listOf(1), RiskPrompt.windows(fakes(1)).map { it.size })
        assertEquals(listOf(30), RiskPrompt.windows(fakes(30)).map { it.size })
        assertEquals(listOf(30, 1), RiskPrompt.windows(fakes(31)).map { it.size })
        val all = fakes(65)
        val windows = RiskPrompt.windows(all)
        assertEquals(listOf(30, 30, 5), windows.map { it.size })
        assertEquals(all.subList(35, 65), windows[0])
        assertEquals(all.subList(5, 35), windows[1])
        assertEquals(all.subList(0, 5), windows[2])
    }

    private fun window(alert: Double, report: Double?, flagged: Boolean = false) =
        RiskWindowScore(alert, flagged, if (report == null) "U" else "1", report)

    @Test fun threadRules() {
        val low = window(0.1, 10.0)
        val high = window(0.9, 80.0, flagged = true)
        val unresolved = window(0.2, null)
        val both = RiskScoring.thread(listOf(low, high, null))
        assertTrue(both.flagged)
        assertEquals(80.0, both.score!!, 0.0)
        assertEquals(0.9, both.alert, 0.0)
        assertNull(both.reviewReason)
        assertFalse(RiskScoring.thread(listOf(low, unresolved)).flagged)
        assertEquals("insufficient_context", RiskScoring.thread(listOf(unresolved, null)).reviewReason)
        assertNull(RiskScoring.thread(listOf(unresolved)).score)
        assertEquals("context_too_long", RiskScoring.thread(listOf(null, null)).reviewReason)
        assertThrows(IllegalArgumentException::class.java) { RiskScoring.thread(emptyList()) }
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
