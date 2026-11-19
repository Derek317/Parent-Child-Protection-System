package app.family.igguard

import java.io.Closeable
import java.io.File

/** Scores one prompt: the raw next-token logits of the answers 0 1 2 3 U (in that order). */
internal interface RiskScorer : Closeable {
    /** Returns five logits. Throws [RiskContextTooLong] when the prompt does not fit the model context. */
    fun score(prompt: String): FloatArray
}

internal class RiskContextTooLong : Exception("prompt does not fit the model context")

/**
 * llama.cpp-backed scorer (native/risk). The shared library loads on the first [open], never at
 * class-init time, so tests that never open a model do not need it.
 *
 * Error convention of the native layer: nativeLoad throws IllegalStateException; nativeScore returns
 * null and the reason is read with nativeLastCode (2 = context too long) and nativeLastError.
 * One instance is not safe for parallel scoring, so [score] is synchronized.
 */
internal class RiskNative private constructor(private var handle: Long) : RiskScorer {
    external fun nativeScore(handle: Long, prompt: ByteArray): FloatArray?
    external fun nativeLastCode(handle: Long): Int
    external fun nativeLastError(handle: Long): String
    external fun nativeFree(handle: Long)

    @Synchronized
    override fun score(prompt: String): FloatArray {
        check(handle != 0L) { "scorer is closed" }
        val out = nativeScore(handle, prompt.toByteArray(Charsets.UTF_8))
        if (out != null) return out
        if (nativeLastCode(handle) == CONTEXT_TOO_LONG) throw RiskContextTooLong()
        throw IllegalStateException(nativeLastError(handle))
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) {
            nativeFree(handle)
            handle = 0L
        }
    }

    companion object {
        private const val CONTEXT_TOO_LONG = 2

        @Volatile private var libraryLoaded = false

        /** Loads the model CPU-only. Throws IllegalStateException with a clear message if it cannot. */
        fun open(modelFile: File, threads: Int): RiskNative {
            if (!libraryLoaded) {
                synchronized(this) {
                    if (!libraryLoaded) {
                        System.loadLibrary("igguard_risk")
                        libraryLoaded = true
                    }
                }
            }
            return RiskNative(nativeLoad(modelFile.absolutePath, threads))
        }

        @JvmStatic external fun nativeLoad(path: String, threads: Int): Long
    }
}
