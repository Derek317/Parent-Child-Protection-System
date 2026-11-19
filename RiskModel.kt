package app.family.igguard

import java.io.InputStream
import java.security.MessageDigest
import kotlin.math.exp

/** Pure-Kotlin half of local risk scoring. Must match research/igrisk/scorers.py and train_lora.render. */
internal object RiskModelSpec {
    const val MODEL_NAME = "igguard/qwen3-1.7b-lora-v4-Q4_K_M"
    const val FILE_NAME = "qwen3-1.7b-lora-v4-Q4_K_M.gguf"
    const val FILE_BYTES = 1_107_408_736L
    const val FILE_SHA256 = "833efa22ff726b0df4c6461fd61c12409b9dd7eddfee35417c7c342a6a435314"
    val ANSWERS = listOf("0", "1", "2", "3", "U")
    val ANSWER_TOKEN_IDS = intArrayOf(15, 16, 17, 18, 52)
    const val WINDOW = 30
    const val INSTRUCTION_SHA256 = "9bddccc8d74440a65e919469fa0d5cc8c7423b8f41e7b74d209f218c2d3d726b"
}

/** [speaker] is "child" or "other". */
internal data class RiskMessage(val speaker: String, val text: String)

internal object RiskPrompt {
    /** Python's str.split() whitespace, which differs from Char.isWhitespace (U+001C..1F, U+0085, U+00A0 ...). */
    private fun isPythonSpace(c: Char) = c.code.let {
        it in 0x09..0x0d || it in 0x1c..0x20 || it == 0x85 || it == 0xa0 || it == 0x1680 ||
            it in 0x2000..0x200a || it == 0x2028 || it == 0x2029 || it == 0x202f || it == 0x205f || it == 0x3000
    }

    /** Exactly Python's ' '.join(text.split()). */
    fun collapse(text: String): String = buildString {
        var pending = false
        for (c in text) {
            if (isPythonSpace(c)) {
                pending = length > 0
            } else {
                if (pending) append(' ')
                pending = false
                append(c)
            }
        }
    }

    fun user(messages: List<RiskMessage>): String =
        "Chat window:\n" + messages.takeLast(RiskModelSpec.WINDOW)
            .joinToString("\n") { "${it.speaker}: ${collapse(it.text)}" } + "\n\nAnswer (0, 1, 2, 3 or U):"

    /** The Qwen3 ChatML prompt with an empty think block, ending where the answer token goes. */
    fun render(instruction: String, messages: List<RiskMessage>): String =
        "<|im_start|>system\n$instruction<|im_end|>\n<|im_start|>user\n${user(messages)}<|im_end|>\n" +
            "<|im_start|>assistant\n<think>\n\n</think>\n\n"

    /** Chunks of [RiskModelSpec.WINDOW] counted back from the newest message; the newest chunk comes first. */
    fun windows(messages: List<RiskMessage>): List<List<RiskMessage>> =
        (messages.size downTo 1 step RiskModelSpec.WINDOW).map { end ->
            messages.subList(maxOf(0, end - RiskModelSpec.WINDOW), end)
        }
}

internal data class RiskWindowScore(val alert: Double, val flagged: Boolean, val answer: String, val reportScore: Double?)

internal data class RiskThreadScore(val score: Double?, val reviewReason: String?, val flagged: Boolean, val alert: Double)

internal object RiskScoring {
    /** [logits] are for the answers 0 1 2 3 U. */
    fun fromLogits(logits: FloatArray): RiskWindowScore {
        require(logits.size == 5)
        val top = logits.max().toDouble()
        require(top.isFinite())
        return fromProbabilities(DoubleArray(5) { exp(logits[it] - top) })
    }

    /** [p] is for the answers 0 1 2 3 U and is renormalised. */
    fun fromProbabilities(p: DoubleArray): RiskWindowScore {
        require(p.size == 5 && p.all { it.isFinite() && it >= 0 })
        val total = p.sum()
        require(total > 0 && total.isFinite())
        val q = DoubleArray(5) { p[it] / total }
        val best = q.indices.fold(0) { best, i -> if (q[i] > q[best]) i else best }
        val alert = q[2] + q[3]
        val levels = q[0] + q[1] + q[2] + q[3]
        val report = if (best != 4 && levels > 0) 100 * (q[1] + 2 * q[2] + 3 * q[3]) / (3 * levels) else null
        return RiskWindowScore(alert, alert >= 0.5, RiskModelSpec.ANSWERS[best], report)
    }

    /** [windows] holds one entry per window; null means the window could not be scored (context too long). */
    fun thread(windows: List<RiskWindowScore?>): RiskThreadScore {
        require(windows.isNotEmpty())
        val scored = windows.filterNotNull()
        val score = scored.mapNotNull { it.reportScore }.maxOrNull()
        val reason = when {
            score != null -> null
            scored.isEmpty() -> "context_too_long"
            else -> "insufficient_context"
        }
        return RiskThreadScore(score, reason, scored.any { it.flagged }, scored.maxOfOrNull { it.alert } ?: 0.0)
    }
}

internal object RiskModelFile {
    /** Returns the number of bytes read and their lowercase hex SHA-256. */
    fun sha256(stream: InputStream, onProgress: ((Long) -> Unit)? = null): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1 shl 16)
        var total = 0L
        while (true) {
            val size = stream.read(buffer)
            if (size < 0) break
            digest.update(buffer, 0, size)
            total += size
            onProgress?.invoke(total)
        }
        return total to digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun matches(bytes: Long, sha256: String) =
        bytes == RiskModelSpec.FILE_BYTES && sha256 == RiskModelSpec.FILE_SHA256
}
