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
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
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
            if (isLoaded(language) && loadedPackId == packId) return
            abortStreamLocked()
            session?.release()
            session = null
            loadedLang = null
            loadedPackId = null
            session = OfflineTts(config = configFor(files))
            loadedLang = language
            loadedPackId = packId
            runCatching {
                val sid = when (files.kind) {
                    NeuralKind.Kokoro -> TtsPacks.KOKORO_HANA_SID
                    NeuralKind.Piper -> TtsPacks.PIPER_SID
                }
                val warmText = if (language == "id") "Siap." else "Ready."
                val gen = GenerationConfig(
                    sid = sid,
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
        val tts = synchronized(lock) {
            val current = session
            if (current == null || loadedLang != language) {
                error("Neural voice is not loaded")
            }
            current
        }
        val gen = GenerationConfig(
            sid = sid,
            speed = speed.coerceIn(0.7f, 1.4f),
            silenceScale = TtsPacks.SILENCE_SCALE
        )
        var audio = synchronized(lock) {
            tts.generateWithConfig(text = text, config = gen)
        }
        if (isSilent(audio.samples) && language == "en" && sid == 0 && loadedPackId == TtsPacks.EN.packId) {
            val retry = GenerationConfig(
                sid = TtsPacks.KOKORO_HANA_SID,
                speed = speed.coerceIn(0.7f, 1.4f),
                silenceScale = TtsPacks.SILENCE_SCALE
            )
            audio = synchronized(lock) {
                tts.generateWithConfig(text = text, config = retry)
            }
        }
        if (isSilent(audio.samples)) {
            error("This voice produced silence — try Bella or Smooth")
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
        var offset = 0
        val samples = pcm.samples
        while (offset < samples.size && streamGen.get() == gen) {
            val n = (samples.size - offset).coerceAtMost((pcm.sampleRate / 4).coerceAtLeast(512))
            val written = created.write(samples, offset, n, AudioTrack.WRITE_BLOCKING)
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
            AudioFormat.ENCODING_PCM_FLOAT
        ).coerceAtLeast(sampleRate * 16)
        val created = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
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
        if (peak <= 1.0f || peak < 1e-4f) return samples
        val scale = 0.95f / peak
        val out = FloatArray(samples.size)
        for (i in samples.indices) out[i] = samples[i] * scale
        return out
    }

    private fun configFor(files: ModelFiles): OfflineTtsConfig {
        val model = when (files.kind) {
            NeuralKind.Kokoro -> OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = files.onnx.absolutePath,
                    voices = files.voices?.absolutePath.orEmpty(),
                    tokens = files.tokens.absolutePath,
                    dataDir = files.dataDir.absolutePath,
                    lengthScale = 1.0f
                ),
                numThreads = 4,
                debug = false,
                provider = "cpu"
            )
            NeuralKind.Piper -> OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = files.onnx.absolutePath,
                    tokens = files.tokens.absolutePath,
                    dataDir = files.dataDir.absolutePath,
                    lengthScale = 1.0f
                ),
                numThreads = 2,
                debug = false,
                provider = "cpu"
            )
        }
        val maxSentences = if (files.kind == NeuralKind.Kokoro) 2 else 4
        return OfflineTtsConfig(
            model = model,
            maxNumSentences = maxSentences,
            silenceScale = TtsPacks.SILENCE_SCALE
        )
    }

    companion object {
        private const val TAG = "HanaTts"
    }
}
