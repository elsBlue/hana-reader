package com.hana.reader.tts

import android.content.Context
import com.hana.reader.data.Book
import com.hana.reader.data.ProgressStore
import com.hana.reader.data.ReadingProgress
import com.hana.reader.data.TextUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class VoiceProfile { Hana, Clear }

data class PlayerSnapshot(
    val book: Book? = null,
    val chapterIndex: Int = 0,
    val sentenceIndex: Int = 0,
    val playing: Boolean = false,
    val profile: VoiceProfile = VoiceProfile.Hana,
    val rate: Float = 0.9f,
    val usingNeural: Boolean = false,
    val downloadProgress: Float? = null,
    val status: String? = null
)

class HanaPlayer(context: Context) {
    private val appContext = context.applicationContext
    private val store = ProgressStore(appContext)
    private val models = TtsModelManager(appContext)
    private val system = SystemTtsEngine(appContext)
    private val neural = NeuralTtsEngine()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(PlayerSnapshot())
    val state: StateFlow<PlayerSnapshot> = _state.asStateFlow()
    private var speakJob: Job? = null
    private var prefetch: kotlinx.coroutines.Deferred<PcmAudio?>? = null
    private var prefetchKey: String? = null

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
        ensureNeural(book.language)
        speakCurrent()
        ReadingService.start(appContext)
    }

    fun toggle() {
        val snap = _state.value
        if (snap.book == null) return
        if (snap.playing) pause() else play(snap.book, snap.chapterIndex, snap.sentenceIndex)
    }

    fun pause() {
        speakJob?.cancel()
        prefetch?.cancel()
        neural.stop()
        system.stop()
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
        val lang = _state.value.book?.language ?: "en"
        if (profile == VoiceProfile.Hana) ensureNeural(lang)
        else {
            neural.stop()
            _state.value = _state.value.copy(usingNeural = false, downloadProgress = null)
        }
        if (_state.value.playing) speakCurrent()
    }

    fun setRate(rate: Float) {
        val r = rate.coerceIn(0.7f, 1.4f)
        _state.value = _state.value.copy(rate = r)
        system.setRate(r)
    }

    fun shutdown() {
        pause()
        neural.release()
        system.shutdown()
    }

    private fun ensureNeural(language: String) {
        if (_state.value.profile != VoiceProfile.Hana) return
        if (TtsPacks.forLanguage(language) == null) return
        if (neural.isLoaded(language)) {
            _state.value = _state.value.copy(usingNeural = true, downloadProgress = null)
            return
        }
        val files = models.files(language)
        if (files != null) {
            scope.launch(Dispatchers.Default) {
                runCatching { neural.prepare(language, files) }
                    .onSuccess {
                        withContext(Dispatchers.Main) {
                            _state.value = _state.value.copy(usingNeural = true, status = null)
                        }
                    }
            }
            return
        }
        scope.launch {
            try {
                _state.value = _state.value.copy(downloadProgress = 0f, status = "Downloading Hana voice…")
                val downloaded = models.ensure(language) { p ->
                    _state.value = _state.value.copy(
                        downloadProgress = p,
                        status = "Downloading Hana voice… ${(p * 100).toInt()}%"
                    )
                }
                withContext(Dispatchers.Default) { neural.prepare(language, downloaded) }
                _state.value = _state.value.copy(
                    usingNeural = true,
                    downloadProgress = null,
                    status = null
                )
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    usingNeural = false,
                    downloadProgress = null,
                    status = "Using device voice"
                )
            }
        }
    }

    private fun speakCurrent() {
        speakJob?.cancel()
        prefetch?.cancel()
        prefetch = null
        prefetchKey = null
        neural.stop()
        system.stop()
        val snap = _state.value
        val book = snap.book ?: return
        if (!snap.playing) return
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
        val utteranceId = "hana-${snap.chapterIndex}-${snap.sentenceIndex}"
        val useNeural = snap.profile == VoiceProfile.Hana && neural.isLoaded(book.language)
        _state.value = snap.copy(usingNeural = useNeural)
        speakJob = scope.launch {
            if (useNeural) {
                speakNeural(book, text, utteranceId, snap)
            } else {
                val hanaStyle = snap.profile == VoiceProfile.Hana
                system.setRate(snap.rate)
                system.speak(text, book.language, utteranceId, hanaStyle) {
                    scope.launch { if (_state.value.playing) advance() }
                }
            }
        }
    }

    private suspend fun speakNeural(book: Book, text: String, key: String, snap: PlayerSnapshot) {
        try {
            val speed = if (snap.profile == VoiceProfile.Hana) snap.rate * 0.96f else snap.rate
            val sid = TtsPacks.speakerId(book.language, snap.profile)
            val pcm = if (prefetchKey == key) {
                prefetch?.await()
            } else null
            prefetch?.cancel()
            prefetch = null
            prefetchKey = null
            val audio = pcm ?: withContext(Dispatchers.Default) {
                neural.synthesize(text, book.language, sid, speed)
            }
            prefetchNext(book, snap, sid, speed)
            withContext(Dispatchers.IO) { neural.play(audio) }
            if (_state.value.playing) withContext(Dispatchers.Main) { advance() }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            _state.value = _state.value.copy(usingNeural = false, status = "Using device voice")
            withContext(Dispatchers.Main) {
                system.speak(text, book.language, key, true) {
                    scope.launch { if (_state.value.playing) advance() }
                }
            }
        }
    }

    private fun prefetchNext(book: Book, snap: PlayerSnapshot, sid: Int, speed: Float) {
        var ch = snap.chapterIndex
        var se = snap.sentenceIndex + 1
        var list = sentences(book, ch)
        if (se >= list.size) {
            if (ch >= book.chapters.lastIndex) return
            ch += 1
            se = 0
            list = sentences(book, ch)
        }
        val next = list.getOrNull(se) ?: return
        val key = "hana-$ch-$se"
        prefetchKey = key
        prefetch = scope.async(Dispatchers.Default) {
            runCatching { neural.synthesize(next, book.language, sid, speed) }.getOrNull()
        }
    }

    private fun advance() {
        if (!_state.value.playing) return
        skipSentence(1)
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
