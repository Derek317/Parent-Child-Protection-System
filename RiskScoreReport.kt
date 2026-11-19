package app.family.igguard

import android.util.JsonReader
import android.util.JsonToken
import java.io.ByteArrayOutputStream
import java.io.InputStream

internal enum class RiskBand(val minimum: Int, val title: String, val range: String) {
    LOW(0, "低風險", "0–<25"),
    POTENTIAL(25, "潛在風險", "25–<50"),
    MODERATE(50, "中度風險", "50–<75"),
    HIGH(75, "高風險", "75–100");

    companion object {
        fun forScore(score: Double): RiskBand = entries.last { score >= it.minimum }
    }
}

/** Score-only input for the parent research preview. The file never enters the message archive. */
internal class RiskScoreReport private constructor(
    val model: String,
    val weightPatch: String?,
    val scores: List<Double>,
    val reviewCount: Int
) {
    val mean = scores.takeIf { it.isNotEmpty() }?.average()
    val highest = scores.maxOrNull()
    val counts = scores.groupingBy { RiskBand.forScore(it) }.eachCount()

    companion object {
        private const val MAX_BYTES = 128 * 1024
        private const val MAX_CONVERSATIONS = 500
        private val conversationId = Regex("C[0-9]{3}")
        private val pinnedRevisions = mapOf(
            "LiquidAI/d1-omni-600M" to "02b55d7076f15129e59ab3f94783f32c4b088674",
            // Reading a report is harmless, so earlier research reports stay readable; only the v4 file is accepted by the model import.
            "igguard/qwen3-1.7b-lora-v3-Q4_K_M" to "14f2176d569772bcc8e0e0dcd97e59f7ac60d8c4f6e1cb5f6a45159b1892c293",
            "igguard/qwen3-1.7b-lora-v4-Q4_K_M" to "833efa22ff726b0df4c6461fd61c12409b9dd7eddfee35417c7c342a6a435314"
        )
        private val reviewReasons = setOf("insufficient_context", "uncertain_score", "context_too_long")

        fun read(stream: InputStream): RiskScoreReport {
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val size = stream.read(buffer)
                if (size < 0) break
                require(size > 0 && bytes.size() + size <= MAX_BYTES)
                bytes.write(buffer, 0, size)
            }
            val text = bytes.toByteArray().decodeToString(throwOnInvalidSequence = true)
            return JsonReader(text.reader()).use { reader ->
                var source: String? = null
                var model: String? = null
                var revision: String? = null
                var weightPatch: String? = null
                var scores = emptyList<Double?>()
                val fields = reader.objectFields { name ->
                    when (name) {
                        "schema" -> require(reader.string() == "igguard-d1-risk-v1")
                        "model" -> model = reader.string()
                        "revision" -> revision = reader.string()
                        "source" -> source = reader.string()
                        "weight_patch_sha256" -> weightPatch = reader.string().also {
                            require(it.matches(Regex("[a-f0-9]{64}")))
                        }
                        "conversations" -> scores = reader.scores()
                        else -> error("Unknown score report field")
                    }
                }
                require(fields.containsAll(setOf("schema", "model", "revision", "source", "conversations")))
                require(pinnedRevisions[model] == revision)
                require(source == "published_case_demo" || source == "local_research")
                require(reader.peek() == JsonToken.END_DOCUMENT)
                RiskScoreReport(model!!, weightPatch, scores.filterNotNull(), scores.count { it == null })
            }
        }

        private fun JsonReader.scores(): List<Double?> {
            val seen = mutableSetOf<String>()
            val scores = mutableListOf<Double?>()
            beginArray()
            while (hasNext()) {
                require(scores.size < MAX_CONVERSATIONS)
                var score: Double? = null
                var reason: String? = null
                val fields = objectFields { name ->
                    when (name) {
                        "conversation" -> {
                            val id = string()
                            require(conversationId.matches(id) && id.substring(1).toInt() in 1..MAX_CONVERSATIONS && seen.add(id))
                        }
                        "score" -> score = nullable {
                            require(peek() == JsonToken.NUMBER)
                            nextDouble().also { require(it.isFinite() && it in 0.0..100.0) }
                        }
                        "review_reason" -> reason = nullable { string() }
                        else -> error("Unknown score row field")
                    }
                }
                require(fields.size == 3)
                require(if (score == null) reason in reviewReasons else reason == null)
                scores.add(score)
            }
            endArray()
            require(scores.isNotEmpty())
            return scores
        }

        private fun JsonReader.objectFields(readField: (String) -> Unit): Set<String> {
            val seen = mutableSetOf<String>()
            beginObject()
            while (hasNext()) {
                val name = nextName()
                require(seen.add(name))
                readField(name)
            }
            endObject()
            return seen
        }

        private fun JsonReader.string(): String {
            require(peek() == JsonToken.STRING)
            return nextString()
        }

        private fun <T> JsonReader.nullable(read: () -> T): T? {
            if (peek() != JsonToken.NULL) return read()
            nextNull()
            return null
        }
    }
}
