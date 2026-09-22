package com.hana.reader.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale

class SystemTtsEngine(context: Context) : TtsEngine, TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(context.applicationContext, this)
    @Volatile private var ready = false
    @Volatile private var onDone: (() -> Unit)? = null
    private var rate = TtsPacks.DEFAULT_RATE

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onError(utteranceId: String?) {
                onDone?.invoke()
            }
            override fun onDone(utteranceId: String?) {
                onDone?.invoke()
            }
        })
    }

    fun isReady(): Boolean = ready

    fun speak(
        text: String,
        language: String,
        utteranceId: String,
        hanaStyle: Boolean,
        onDone: () -> Unit
    ) {
        if (!ready) {
            onDone()
            return
        }
        this.onDone = onDone
        val loc = if (language == "id") Locale("id", "ID") else Locale.US
        tts.language = loc
        pickWarmFemale(loc)?.let { tts.voice = it }
        // Pitch boost made "Hana · device" worse than plain Device — keep pitch natural.
        tts.setPitch(1.0f)
        tts.setSpeechRate(if (hanaStyle) rate * 0.98f else rate)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), utteranceId)
    }

    override fun setRate(rate: Float) {
        this.rate = rate.coerceIn(0.7f, 1.4f)
    }

    override fun setPitch(pitch: Float) {
        // Ignored for system voice — pitch tweaks sounded worse than stock Google TTS.
    }

    override fun stop() {
        onDone = null
        if (ready) tts.stop()
    }

    override fun shutdown() {
        onDone = null
        tts.stop()
        tts.shutdown()
    }

    private fun pickWarmFemale(loc: Locale): Voice? {
        val voices = tts.voices ?: return null
        return voices
            .filter { it.locale.language == loc.language }
            .maxWithOrNull { a, b -> score(a).compareTo(score(b)) }
    }

    private fun score(v: Voice): Int {
        val n = v.name.lowercase()
        var s = 0
        if (n.contains("female") || n.contains("woman") || n.contains("samantha") ||
            n.contains("zira") || n.contains("neural")
        ) s += 30
        if (n.contains("male") || n.contains("man") || n.contains("david") || n.contains("daniel")) s -= 40
        if (!v.isNetworkConnectionRequired) s += 8
        if (n.contains("enhanced") || n.contains("premium") || n.contains("quality")) s += 10
        return s
    }
}
