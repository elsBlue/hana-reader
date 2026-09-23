package com.hana.reader.tts

/**
 * Pure ORT / sherpa-onnx session policy for Piper VITS on Android.
 *
 * Stock sherpa 1.13.x Kotlin API only exposes [numThreads] + [provider].
 * Native [GetSessionOptionsImpl] sets both intra-op and inter-op to numThreads
 * and does not expose ORT_SEQUENTIAL for the CPU path — so we keep threads
 * conservative and prefer XNNPACK (own pool; use 1 thread) over NNAPI.
 */
object OrtRuntimePolicy {
    /** Prefer XNNPACK for VITS/Piper; never NNAPI on stock AAR (handoff risk). */
    data class Attempt(val provider: String, val numThreads: Int)

    /**
     * Ordered load attempts. XNNPACK first with 1 ORT thread (EP parallelizes);
     * CPU fallback with conservative 2–4 intra/inter via sherpa's coupled knob.
     */
    fun loadAttempts(availableProcessors: Int = Runtime.getRuntime().availableProcessors()): List<Attempt> =
        listOf(
            Attempt(provider = "xnnpack", numThreads = 1),
            Attempt(provider = "cpu", numThreads = cpuThreads(availableProcessors)),
        )

    /** Conservative CPU threads: 2–4, never 0 / all cores thrash. */
    fun cpuThreads(availableProcessors: Int): Int =
        availableProcessors.coerceIn(2, 4)

    fun isNnapi(provider: String): Boolean =
        provider.equals("nnapi", ignoreCase = true)
}
