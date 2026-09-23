package com.hana.reader.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.util.concurrent.atomic.AtomicInteger

class NeuralTtsEngine {
    private val streamGen = AtomicInteger(0)
    @Volatile private var session: OfflineTts? = null
    @Volatile private var loadedLang: String? = null
    @Volatile private var loadedPackId: String? = null
    @Volatile private var track: AudioTrack? = null
    @Volatile private var trackRate: Int = 0
    private val lock = Any()

    fun isLoaded(language: String): Boolean = session != null && loadedLang == language

    fun isLoadedPack(packId: String): Boolean = session != null && loadedPackId == packId

    fun prepare(language: String, files: ModelFiles, packId: String = files.kind.name) {
        synchronized(lock) {
            // No-op only when the same pack is already live — never reuse a freed pointer.
            if (session != null && loadedPackId == packId && loadedLang == language) return
            abortStreamLocked()
            session?.release()
            // Null before construct so a failed OfflineTts() cannot leave a dangling pointer.
            session = null
            loadedLang = null
            loadedPackId = null
            session = OfflineTts(config = configFor(files, packId))
            loadedLang = language
            loadedPackId = packId
            // Soft-fail warm-up under the same lock (shrinks release/generate race window).
            runCatching {
                val warmText = "Ready."
                val gen = GenerationConfig(
                    sid = TtsPacks.PIPER_SID,
                    speed = 1f,
                    silenceScale = TtsPacks.SILENCE_SCALE
                )
                session?.generateWithConfig(text = warmText, config = gen)
            }.onFailure { e ->
                Log.w(TAG, "Warm-up synth failed (session still loaded): ${e.message}")
            }
        }
    }

    fun synthesize(text: String, language: String, sid: Int, speed: Float): PcmAudio {
        val gen = GenerationConfig(
            sid = sid,
            speed = speed.coerceIn(0.7f, 1.15f),
            silenceScale = TtsPacks.SILENCE_SCALE
        )
        // Hold ONE lock for session check + generate — never copy OfflineTts then unlock.
        val audio = synchronized(lock) {
            val current = session
            if (current == null || loadedLang != language) {
                error("Neural voice is not loaded")
            }
            current.generateWithConfig(text = text, config = gen)
        }
        if (isSilent(audio.samples)) {
            error("This voice produced silence — try Smooth or Warm")
        }
        return PcmAudio(softNormalize(audio.samples), audio.sampleRate)
    }

    /**
     * Write PCM onto a persistent stream. Does **not** stop the track — the next
     * chunk should follow immediately so words are not cut at chunk boundaries.
     */
    fun writeStreaming(pcm: PcmAudio) {
        val gen = streamGen.get()
        val created = synchronized(lock) { ensureTrack(pcm.sampleRate) }
        val samples = toPcm16(pcm.samples, pcm.sampleRate)
        var offset = 0
        while (offset < samples.size && streamGen.get() == gen) {
            val n = (samples.size - offset).coerceAtMost((pcm.sampleRate / 4).coerceAtLeast(512))
            val written = created.write(samples, offset, n, AudioTrack.WRITE_BLOCKING)
            if (written <= 0) break
            offset += written
        }
    }

    /** Insert a short pause so commas and sentence endings are actually heard. */
    fun writeSilence(sampleRate: Int, durationMs: Int) {
        if (durationMs <= 0 || sampleRate <= 0) return
        val gen = streamGen.get()
        val created = synchronized(lock) { ensureTrack(sampleRate) }
        val n = ((sampleRate.toLong() * durationMs) / 1000L).toInt().coerceAtLeast(1)
        val zeros = ShortArray(n)
        var offset = 0
        while (offset < zeros.size && streamGen.get() == gen) {
            val chunk = (zeros.size - offset).coerceAtMost((sampleRate / 4).coerceAtLeast(512))
            val written = created.write(zeros, offset, chunk, AudioTrack.WRITE_BLOCKING)
            if (written <= 0) break
            offset += written
        }
    }

    /** One-shot preview: stream, drain the tail, then release. */
    fun play(pcm: PcmAudio) {
        abortStream()
        val gen = streamGen.get()
        writeStreaming(pcm)
        drain(pcm, gen)
        if (streamGen.get() == gen) abortStream()
    }

    fun stop() = abortStream()

    /**
     * Wait until the persistent stream has played out (head stops advancing).
     * Used only at natural end-of-book — never between chunks.
     */
    fun waitUntilDrained() {
        val created = track ?: return
        val gen = streamGen.get()
        var last = Int.MIN_VALUE
        var stable = 0
        val deadline = SystemClock.elapsedRealtime() + 20_000L
        while (streamGen.get() == gen && SystemClock.elapsedRealtime() < deadline) {
            val head = runCatching { created.playbackHeadPosition }.getOrDefault(0)
            if (head == last) {
                if (++stable >= 10) break
            } else {
                stable = 0
                last = head
            }
            try {
                Thread.sleep(20)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    fun abortStream() {
        streamGen.incrementAndGet()
        synchronized(lock) { abortStreamLocked() }
    }

    fun release() {
        abortStream()
        synchronized(lock) {
            session?.release()
            session = null
            loadedLang = null
            loadedPackId = null
        }
    }

    private fun ensureTrack(sampleRate: Int): AudioTrack {
        val existing = track
        if (existing != null && trackRate == sampleRate) {
            if (existing.playState != AudioTrack.PLAYSTATE_PLAYING) {
                runCatching { existing.play() }
            }
            return existing
        }
        abortStreamLocked()
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(sampleRate * 4)
        val created = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            minBuf,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        track = created
        trackRate = sampleRate
        created.play()
        return created
    }

    private fun drain(pcm: PcmAudio, gen: Int) {
        val created = track ?: return
        val end = pcm.samples.size
        val timeoutMs = if (pcm.sampleRate > 0) {
            (end * 1000L) / pcm.sampleRate + 500L
        } else {
            500L
        }
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (streamGen.get() == gen && SystemClock.elapsedRealtime() < deadline) {
            val head = runCatching { created.playbackHeadPosition }.getOrDefault(0)
            if (head >= (end - 16).coerceAtLeast(0)) break
            try {
                Thread.sleep(10)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun abortStreamLocked() {
        track?.let {
            runCatching { it.pause() }
            runCatching { it.flush() }
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        track = null
        trackRate = 0
    }

    private fun isSilent(samples: FloatArray): Boolean {
        if (samples.isEmpty()) return true
        var peak = 0f
        for (s in samples) {
            val a = kotlin.math.abs(s)
            if (a > peak) peak = a
        }
        return peak < 1e-3f
    }

    private fun softNormalize(samples: FloatArray): FloatArray {
        var peak = 0f
        for (s in samples) {
            val a = kotlin.math.abs(s)
            if (a > peak) peak = a
        }
        if (peak < 1e-4f) return samples
        // Leave headroom so Warm / Amy peaks don't grit in PCM16.
        val target = 0.85f
        if (peak <= target) return samples
        val scale = target / peak
        val out = FloatArray(samples.size)
        for (i in samples.indices) out[i] = samples[i] * scale
        return out
    }

    /** 16-bit with edge fade + mild soft-clip so peaks don't buzz. */
    private fun toPcm16(samples: FloatArray, sampleRate: Int): ShortArray {
        val n = samples.size
        val out = ShortArray(n)
        val fade = ((sampleRate * 5) / 1000).coerceIn(48, n / 8).coerceAtLeast(1)
        for (i in 0 until n) {
            var s = samples[i]
            // Mild soft knee above ~0.92 instead of hard rail at ±1.
            val a = kotlin.math.abs(s)
            if (a > 0.92f) {
                val sign = if (s >= 0f) 1f else -1f
                val over = a - 0.92f
                s = sign * (0.92f + over / (1f + over * 4f))
            }
            s = s.coerceIn(-1f, 1f)
            if (i < fade) s *= i.toFloat() / fade
            val tail = n - 1 - i
            if (tail < fade) s *= tail.toFloat() / fade
            // 31200 leaves a little headroom vs full-scale 32767 grit.
            out[i] = (s * 31200f).toInt().coerceIn(-32767, 32767).toShort()
        }
        return out
    }

    private fun configFor(files: ModelFiles, packId: String): OfflineTtsConfig {
        val acoustic = TtsPacks.acousticFor(packId)
        return OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = files.onnx.absolutePath,
                    tokens = files.tokens.absolutePath,
                    dataDir = files.dataDir.absolutePath,
                    noiseScale = acoustic.noiseScale,
                    noiseScaleW = acoustic.noiseScaleW,
                    lengthScale = acoustic.lengthScale
                ),
                numThreads = 1,
                debug = false,
                provider = "cpu"
            ),
            maxNumSentences = 2,
            silenceScale = TtsPacks.SILENCE_SCALE
        )
    }

    companion object {
        private const val TAG = "HanaTts"
    }
}
