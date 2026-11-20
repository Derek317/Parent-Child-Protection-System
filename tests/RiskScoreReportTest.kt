package app.family.igguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RiskScoreReportTest {
    private fun parse(rows: String, patch: String? = null) = RiskScoreReport.read(
        """
        {"schema":"igguard-d1-risk-v1","model":"LiquidAI/d1-omni-600M",
         "revision":"02b55d7076f15129e59ab3f94783f32c4b088674",
         "source":"published_case_demo",${patch?.let { "\"weight_patch_sha256\":\"$it\"," }.orEmpty()} "conversations":[$rows]}
        """.trimIndent().byteInputStream()
    )

    @Test fun realZeroCountsAndUnscoredConversationsDoNotDiluteTheAverage() {
        val digest = "a".repeat(64)
        val report = parse("""
            {"conversation":"C001","score":0,"review_reason":null},
            {"conversation":"C002","score":100,"review_reason":null},
            {"conversation":"C003","score":null,"review_reason":"insufficient_context"}
        """, digest)
        assertEquals(digest, report.weightPatch)
        assertEquals(2, report.scores.size)
        assertEquals(1, report.reviewCount)
        assertEquals(50.0, report.mean)
        assertEquals(100.0, report.highest)
        assertEquals(mapOf(RiskBand.LOW to 1, RiskBand.HIGH to 1), report.counts)
    }

    @Test fun allUnscoredConversationsYieldNoAverageOrHighest() {
        val report = parse("""{"conversation":"C001","score":null,"review_reason":"uncertain_score"}""")
        assertNull(report.mean)
        assertNull(report.highest)
        assertEquals(1, report.reviewCount)
    }

    @Test fun exactCutoffsBelongToTheHigherBand() {
        listOf(24.999 to RiskBand.LOW, 25.0 to RiskBand.POTENTIAL, 49.999 to RiskBand.POTENTIAL,
            50.0 to RiskBand.MODERATE, 74.999 to RiskBand.MODERATE, 75.0 to RiskBand.HIGH)
            .forEach { (value, expected) -> assertEquals(expected, RiskBand.forScore(value)) }
    }

    @Test fun rejectsInvalidScoresDuplicateIdsAndTranscriptFields() {
        val valid = """{"conversation":"C001","score":50,"review_reason":null}"""
        for (score in listOf("-0.1", "101", "\"50\"", "true", "null")) {
            assertThrows(Exception::class.java) { parse(valid.replace("50", score)) }
        }
        assertThrows(Exception::class.java) { parse("$valid,$valid") }
        assertThrows(Exception::class.java) { parse(valid.replace("\"score\":50", "\"score\":50,\"score\":50")) }
        assertThrows(Exception::class.java) { parse(valid.dropLast(1) + ""","text":"private chat"}""") }
    }

    private val d1 = "LiquidAI/d1-omni-600M" to "02b55d7076f15129e59ab3f94783f32c4b088674"
    private val qwen = "igguard/qwen3-1.7b-lora-v7-Q4_K_M" to "32c05d65e380072e01ab194021419ec5bb8026a07fdcea5290e773a0995dff60"
    private val qwenV4 = "igguard/qwen3-1.7b-lora-v4-Q4_K_M" to "833efa22ff726b0df4c6461fd61c12409b9dd7eddfee35417c7c342a6a435314"
    private val qwenV3 = "igguard/qwen3-1.7b-lora-v3-Q4_K_M" to "14f2176d569772bcc8e0e0dcd97e59f7ac60d8c4f6e1cb5f6a45159b1892c293"
    private fun withModel(model: String, revision: String) = RiskScoreReport.read(
        """{"revision":"$revision","schema":"igguard-d1-risk-v1","source":"local_research","model":"$model",
            "conversations":[{"conversation":"C001","score":50,"review_reason":null}]}""".byteInputStream()
    )

    @Test fun parsesTheQwenResearchReport() {
        val vector = File(requireNotNull(System.getProperty("igguard.protocol.vector")))
        val file = File(vector.parentFile, "../../research/results/style3-test/qwen3-1.7b-lora-v3-q4/score-report.json")
        val report = file.inputStream().use { RiskScoreReport.read(it) }
        assertEquals(270, report.scores.size + report.reviewCount)
        assertEquals(qwenV3.first, report.model)   // the checked-in report was produced with the earlier v3 file
    }

    @Test fun bothPinnedModelsParseRegardlessOfFieldOrder() {
        assertEquals(d1.first, parse("""{"conversation":"C001","score":1,"review_reason":null}""").model)
        assertEquals(qwen.first, withModel(qwen.first, qwen.second).model)
        // Reports from the earlier v3 and v4 files stay readable; a mixed-up name and revision does not.
        assertEquals(qwenV3.first, withModel(qwenV3.first, qwenV3.second).model)
        assertEquals(qwenV4.first, withModel(qwenV4.first, qwenV4.second).model)
        assertThrows(Exception::class.java) { withModel(qwen.first, qwenV3.second) }
        assertThrows(Exception::class.java) { withModel(qwen.first, qwenV4.second) }
        assertThrows(Exception::class.java) { withModel(qwenV4.first, qwen.second) }
        assertThrows(Exception::class.java) { withModel(qwenV3.first, qwen.second) }
        assertThrows(Exception::class.java) { withModel(qwenV3.first, qwenV4.second) }
    }

    @Test fun modelAndRevisionMustMatchAsAPair() {
        assertThrows(Exception::class.java) { withModel(qwen.first, d1.second) }
        assertThrows(Exception::class.java) { withModel(d1.first, qwen.second) }
        assertThrows(Exception::class.java) { withModel(qwen.first, "b".repeat(64)) }
        assertThrows(Exception::class.java) { withModel("other/model", qwen.second) }
    }
}
