package com.hana.reader.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.util.concurrent.atomic.AtomicInteger

class NeuralTtsEngine {
    private val generation = AtomicInteger(0)
    @Volatile private var session: OfflineTts? = null
    @Volatile private var loadedLang: String? = null
    @Volatile private var track: AudioTrack? = null
    private val lock = Any()

    fun isLoaded(language: String): Boolean = session != null && loadedLang == language

    fun prepare(language: String, files: ModelFiles) {
        synchronized(lock) {
            if (isLoaded(language)) return
            session?.release()
            session = null
            loadedLang = null
            session = OfflineTts(config = configFor(files))
            loadedLang = language
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
        if (isSilent(audio.samples) && language == "en" && sid == 0) {
            // Legacy Blend/sid0 — one automatic retry with Bella.
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
            error("This voice produced silence — try Bella")
        }
        return PcmAudio(softNormalize(audio.samples), audio.sampleRate)
    }

    fun play(pcm: PcmAudio) {
        val gen = generation.incrementAndGet()
        val minBuf = AudioTrack.getMinBufferSize(
            pcm.sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        ).coerceAtLeast(pcm.sampleRate * 4)
        val created = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(pcm.sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            minBuf,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        track = created
        try {
            created.play()
            var offset = 0
            val samples = pcm.samples
            while (offset < samples.size && generation.get() == gen) {
                val n = (samples.size - offset).coerceAtMost(pcm.sampleRate / 2)
                val written = created.write(samples, offset, n, AudioTrack.WRITE_BLOCKING)
                if (written <= 0) break
                offset += written
            }
            if (generation.get() == gen) created.stop()
        } finally {
            runCatching { created.release() }
            if (track === created) track = null
        }
    }

    fun stop() {
        generation.incrementAndGet()
        track?.let {
            runCatching { it.pause() }
            runCatching { it.flush() }
            runCatching { it.stop() }
        }
    }

    fun release() {
        stop()
        synchronized(lock) {
            session?.release()
            session = null
            loadedLang = null
        }
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
                numThreads = 2,
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
                numThreads = 1,
                debug = false,
                provider = "cpu"
            )
        }
        // Match smaller speak chunks so the first generate stays light.
        val maxSentences = if (files.kind == NeuralKind.Kokoro) 2 else 2
        return OfflineTtsConfig(
            model = model,
            maxNumSentences = maxSentences,
            silenceScale = TtsPacks.SILENCE_SCALE
        )
    }
}
