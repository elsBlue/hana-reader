package com.hana.reader.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.hana.reader.data.Book
import com.hana.reader.data.ProgressStore
import com.hana.reader.data.ReadingProgress
import com.hana.reader.data.TextUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class VoiceProfile { Hana, Clear }

data class PlayerSnapshot(
    val book: Book? = null,
    val chapterIndex: Int = 0,
    val sentenceIndex: Int = 0,
    val playing: Boolean = false,
    val profile: VoiceProfile = VoiceProfile.Hana,
    val rate: Float = 0.9f
)

class HanaPlayer(context: Context) : TextToSpeech.OnInitListener {
    private val appContext = context.applicationContext
    private val store = ProgressStore(appContext)
    private val tts = TextToSpeech(appContext, this)
    private val _state = MutableStateFlow(PlayerSnapshot())
    val state: StateFlow<PlayerSnapshot> = _state.asStateFlow()
    private var ready = false

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) applyVoice(_state.value.profile, _state.value.book?.language ?: "en")
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onError(utteranceId: String?) {
                advance()
            }
            override fun onDone(utteranceId: String?) {
                advance()
            }
        })
    }

    fun play(book: Book, chapterIndex: Int? = null, sentenceIndex: Int? = null) {
        val saved = store.get(book.id)
        val ch = chapterIndex ?: saved?.chapterIndex ?: 0
        val se = sentenceIndex ?: saved?.sentenceIndex ?: 0
        _state.value = _state.value.copy(
            book = book,
            chapterIndex = ch.coerceAtLeast(0),
            sentenceIndex = se.coerceAtLeast(0),
            playing = true
        )
        persist()
        speakCurrent()
        ReadingService.start(appContext)
    }

    fun toggle() {
        val snap = _state.value
        if (snap.book == null) return
        if (snap.playing) pause() else play(snap.book, snap.chapterIndex, snap.sentenceIndex)
    }

    fun pause() {
        tts.stop()
        _state.value = _state.value.copy(playing = false)
        persist()
    }

    fun skipSentence(delta: Int) {
        val snap = _state.value
        val book = snap.book ?: return
        var ch = snap.chapterIndex
        var se = snap.sentenceIndex + delta
        val sentences = sentences(book, ch)
        if (se >= sentences.size) {
            if (ch < book.chapters.lastIndex) {
                ch += 1
                se = 0
            } else {
                pause()
                return
            }
        } else if (se < 0) {
            if (ch > 0) {
                ch -= 1
                se = (sentences(book, ch).size - 1).coerceAtLeast(0)
            } else se = 0
        }
        _state.value = snap.copy(chapterIndex = ch, sentenceIndex = se)
        persist()
        if (snap.playing) speakCurrent()
    }

    fun skipChapter(delta: Int) {
        val snap = _state.value
        val book = snap.book ?: return
        val ch = (snap.chapterIndex + delta).coerceIn(0, book.chapters.lastIndex)
        _state.value = snap.copy(chapterIndex = ch, sentenceIndex = 0)
        persist()
        if (snap.playing) speakCurrent()
    }

    fun setProfile(profile: VoiceProfile) {
        _state.value = _state.value.copy(profile = profile)
        applyVoice(profile, _state.value.book?.language ?: "en")
        if (_state.value.playing) speakCurrent()
    }

    fun setRate(rate: Float) {
        val r = rate.coerceIn(0.7f, 1.4f)
        _state.value = _state.value.copy(rate = r)
        tts.setSpeechRate(if (_state.value.profile == VoiceProfile.Hana) r * 0.96f else r)
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }

    private fun advance() {
        if (!_state.value.playing) return
        skipSentence(1)
    }

    private fun speakCurrent() {
        if (!ready) return
        val snap = _state.value
        val book = snap.book ?: return
        val sentences = sentences(book, snap.chapterIndex)
        val text = sentences.getOrNull(snap.sentenceIndex)
        if (text.isNullOrBlank()) {
            if (snap.chapterIndex < book.chapters.lastIndex) {
                _state.value = snap.copy(chapterIndex = snap.chapterIndex + 1, sentenceIndex = 0)
                persist()
                speakCurrent()
            } else {
                pause()
            }
            return
        }
        applyVoice(snap.profile, book.language)
        tts.setSpeechRate(if (snap.profile == VoiceProfile.Hana) snap.rate * 0.96f else snap.rate)
        tts.setPitch(if (snap.profile == VoiceProfile.Hana) 1.05f else 1.0f)
        val params = Bundle()
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "hana-${snap.chapterIndex}-${snap.sentenceIndex}")
    }

    private fun applyVoice(profile: VoiceProfile, language: String) {
        if (!ready) return
        val loc = if (language == "id") Locale("id", "ID") else Locale.US
        tts.language = loc
        val voice = pickWarmFemale(loc)
        if (voice != null) tts.voice = voice
        tts.setPitch(if (profile == VoiceProfile.Hana) 1.05f else 1.0f)
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
        if (n.contains("female") || n.contains("woman") || n.contains("samantha") || n.contains("zira") || n.contains("neural")) s += 30
        if (n.contains("male") || n.contains("man") || n.contains("david") || n.contains("daniel")) s -= 40
        if (!v.isNetworkConnectionRequired) s += 8
        if (n.contains("enhanced") || n.contains("premium") || n.contains("quality")) s += 10
        return s
    }

    private fun sentences(book: Book, chapterIndex: Int): List<String> {
        val body = book.chapters.getOrNull(chapterIndex)?.body.orEmpty()
        return TextUtil.splitSentences(body)
    }

    private fun persist() {
        val snap = _state.value
        val book = snap.book ?: return
        store.save(
            ReadingProgress(
                bookId = book.id,
                chapterIndex = snap.chapterIndex,
                sentenceIndex = snap.sentenceIndex,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    companion object {
        @Volatile private var instance: HanaPlayer? = null
        fun get(context: Context): HanaPlayer {
            return instance ?: synchronized(this) {
                instance ?: HanaPlayer(context.applicationContext).also { instance = it }
            }
        }
    }
}
