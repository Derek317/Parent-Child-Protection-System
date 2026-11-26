package app.family.igguard

import java.io.InputStream
import java.security.MessageDigest
import kotlin.math.exp

/** Pure-Kotlin half of local risk scoring. Must match research/igrisk/scorers.py and train_lora.render. */
internal object RiskModelSpec {
    const val MODEL_NAME = "igguard/qwen3-1.7b-beacon-Q4_K_M"
    const val FILE_NAME = "qwen3-1.7b-beacon-Q4_K_M.gguf"
    const val FILE_BYTES = 1_107_408_736L
    const val FILE_SHA256 = "5b873dbfa4c36fff10372f8c1cf28ae84182d6d41604bf8f8b83f21966debb5c"
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
}

/**
 * What is fed to the model when the window overflows its context. The prompt, the instruction and the answer tokens are
 * unchanged; only the messages are cut, so a sender cannot pad a thread until it is never scored.
 */
internal object RiskFit {
    /** Oldest messages are dropped down to this many; below it the older messages are shortened instead. */
    const val MIN_KEPT = 10

    /** The first [cap] characters of the collapsed text, never splitting a surrogate pair. */
    fun shorten(m: RiskMessage, cap: Int): RiskMessage {
        val text = RiskPrompt.collapse(m.text)
        if (text.length <= cap) return m
        val end = if (cap > 0 && text[cap - 1].isHighSurrogate()) cap - 1 else cap
        return RiskMessage(m.speaker, text.substring(0, end))
    }

    /**
     * Scores the newest window: as is if it fits; else drops the oldest message one at a time down to [MIN_KEPT]; then
     * shortens every message but the newest (halving a character cap, finally leaving them out). The newest message is
     * never cut: [RiskContextTooLong] means it does not fit the context by itself. Overflow is found by tokenizing only,
     * so each failed try is cheap; the scorer runs the model once, on the window that fits.
     */
    fun score(scorer: RiskScorer, instruction: String, messages: List<RiskMessage>): FloatArray {
        fun attempt(w: List<RiskMessage>): FloatArray? =
            try { scorer.score(RiskPrompt.render(instruction, w)) } catch (_: RiskContextTooLong) { null }
        var window = messages.takeLast(RiskModelSpec.WINDOW)
        attempt(window)?.let { return it }
        while (window.size > MIN_KEPT) { window = window.drop(1); attempt(window)?.let { return it } }
        val older = window.dropLast(1); val newest = window.takeLast(1)
        var cap = (older.maxOfOrNull { RiskPrompt.collapse(it.text).length } ?: 0) / 2
        while (cap > 0) {
            attempt(older.map { shorten(it, cap) } + newest)?.let { return it }
            cap /= 2
        }
        return attempt(newest) ?: throw RiskContextTooLong()
    }
}

internal data class RiskWindowScore(val alert: Double, val flagged: Boolean, val answer: String, val reportScore: Double?)

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
