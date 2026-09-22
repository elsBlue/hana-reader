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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class VoiceProfile { Hana, Clear }

data class PlayerSnapshot(
    val book: Book? = null,
    val chapterIndex: Int = 0,
    val sentenceIndex: Int = 0,
    val playing: Boolean = false,
    val profile: VoiceProfile = VoiceProfile.Hana,
    val rate: Float = TtsPacks.DEFAULT_RATE,
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
    private val prepareMutex = Mutex()
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
        ReadingService.start(appContext)
        restartSpeak(prepare = true)
    }

    fun toggle() {
        val snap = _state.value
        if (snap.book == null) return
        if (snap.playing) pause() else play(snap.book, snap.chapterIndex, snap.sentenceIndex)
    }

    fun pause() {
        speakJob?.cancel()
        speakJob = null
        prefetch?.cancel()
        prefetch = null
        prefetchKey = null
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
        val list = sentences(book, ch)
        if (se >= list.size) {
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
        if (snap.playing) restartSpeak(prepare = true)
    }

    fun skipChapter(delta: Int) {
        val snap = _state.value
        val book = snap.book ?: return
        val ch = (snap.chapterIndex + delta).coerceIn(0, book.chapters.lastIndex)
        _state.value = snap.copy(chapterIndex = ch, sentenceIndex = 0)
        persist()
        if (snap.playing) restartSpeak(prepare = true)
    }

    fun setProfile(profile: VoiceProfile) {
        _state.value = _state.value.copy(profile = profile)
        val book = _state.value.book
        val lang = book?.language ?: "en"
        if (profile != VoiceProfile.Hana) {
            neural.stop()
            _state.value = _state.value.copy(usingNeural = false, downloadProgress = null, status = null)
        }
        if (_state.value.playing && book != null) {
            restartSpeak(prepare = true)
        } else if (profile == VoiceProfile.Hana) {
            scope.launch { prepareNeuralIfNeeded(lang) }
        }
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

    private fun restartSpeak(prepare: Boolean) {
        speakJob?.cancel()
        prefetch?.cancel()
        prefetch = null
        prefetchKey = null
        neural.stop()
        system.stop()
        speakJob = scope.launch {
            val book = _state.value.book ?: return@launch
            if (prepare) prepareNeuralIfNeeded(book.language)
            if (_state.value.playing) speakOnce()
        }
    }

    private suspend fun prepareNeuralIfNeeded(language: String) {
        if (_state.value.profile != VoiceProfile.Hana) return
        if (TtsPacks.forLanguage(language) == null) return
        prepareMutex.withLock {
            if (neural.isLoaded(language)) {
                _state.value = _state.value.copy(
                    usingNeural = true,
                    downloadProgress = null,
                    status = "Neural voice ready"
                )
                return
            }
            try {
                var files = models.files(language)
                if (files == null) {
                    val packName = TtsPacks.forLanguage(language)?.displayName ?: "voice"
                    _state.value = _state.value.copy(
                        downloadProgress = 0f,
                        status = "Downloading $packName…"
                    )
                    files = models.ensure(language) { p ->
                        _state.value = _state.value.copy(
                            downloadProgress = p,
                            status = "Downloading $packName… ${(p * 100).toInt()}%"
                        )
                    }
                } else {
                    _state.value = _state.value.copy(
                        status = "Loading neural voice…",
                        downloadProgress = null
                    )
                }
                withContext(Dispatchers.Default) { neural.prepare(language, files) }
                _state.value = _state.value.copy(
                    usingNeural = true,
                    downloadProgress = null,
                    status = "Neural voice ready"
                )
            } catch (t: Throwable) {
                val why = models.lastError ?: t.message?.take(48) ?: "load failed"
                _state.value = _state.value.copy(
                    usingNeural = false,
                    downloadProgress = null,
                    status = why
                )
            }
        }
    }

    private suspend fun speakOnce() {
        val snap = _state.value
        val book = snap.book ?: return
        if (!snap.playing) return
        val list = sentences(book, snap.chapterIndex)
        if (snap.sentenceIndex !in list.indices) {
            if (snap.chapterIndex < book.chapters.lastIndex) {
                _state.value = snap.copy(chapterIndex = snap.chapterIndex + 1, sentenceIndex = 0)
                persist()
                speakOnce()
            } else {
                pause()
            }
            return
        }
        val useNeural = snap.profile == VoiceProfile.Hana && neural.isLoaded(book.language)
        _state.value = snap.copy(usingNeural = useNeural)
        if (useNeural) {
            speakNeural(book, list, snap)
        } else {
            val text = list[snap.sentenceIndex]
            val utteranceId = "hana-${snap.chapterIndex}-${snap.sentenceIndex}"
            val hanaStyle = snap.profile == VoiceProfile.Hana
            system.setRate(snap.rate)
            system.speak(text, book.language, utteranceId, hanaStyle) {
                scope.launch {
                    if (_state.value.playing) advance(1)
                }
            }
        }
    }

    private suspend fun speakNeural(book: Book, list: List<String>, snap: PlayerSnapshot) {
        try {
            val speed = snap.rate
            val sid = TtsPacks.speakerId(book.language, snap.profile)
            val maxSentences = if (book.language == "en") 3 else 2
            val maxChars = if (book.language == "en") 500 else 320
            val (text, consumed) = TextUtil.speakChunk(
                list,
                snap.sentenceIndex,
                maxSentences = maxSentences,
                maxChars = maxChars
            )
            if (text.isBlank() || consumed <= 0) {
                advance(1)
                return
            }
            val key = "hana-${snap.chapterIndex}-${snap.sentenceIndex}-c$consumed"
            val pcm = if (prefetchKey == key) prefetch?.await() else null
            prefetch?.cancel()
            prefetch = null
            prefetchKey = null
            val audio = pcm ?: withContext(Dispatchers.Default) {
                neural.synthesize(text, book.language, sid, speed)
            }
            prefetchNext(book, snap, consumed, sid, speed)
            withContext(Dispatchers.IO) { neural.play(audio) }
            if (_state.value.playing) advance(consumed)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            _state.value = _state.value.copy(usingNeural = false, status = t.message?.take(48) ?: "synth failed")
            val text = list.getOrNull(snap.sentenceIndex).orEmpty()
            val key = "hana-${snap.chapterIndex}-${snap.sentenceIndex}"
            system.speak(text, book.language, key, true) {
                scope.launch {
                    if (_state.value.playing) advance(1)
                }
            }
        }
    }

    private fun prefetchNext(
        book: Book,
        snap: PlayerSnapshot,
        consumed: Int,
        sid: Int,
        speed: Float
    ) {
        var ch = snap.chapterIndex
        var se = snap.sentenceIndex + consumed
        var list = sentences(book, ch)
        if (se >= list.size) {
            if (ch >= book.chapters.lastIndex) return
            ch += 1
            se = 0
            list = sentences(book, ch)
        }
        val maxSentences = if (book.language == "en") 3 else 2
        val maxChars = if (book.language == "en") 500 else 320
        val (next, nextConsumed) = TextUtil.speakChunk(list, se, maxSentences, maxChars)
        if (next.isBlank() || nextConsumed <= 0) return
        val key = "hana-$ch-$se-c$nextConsumed"
        prefetchKey = key
        prefetch = scope.async(Dispatchers.Default) {
            runCatching { neural.synthesize(next, book.language, sid, speed) }.getOrNull()
        }
    }

    private fun advance(by: Int) {
        if (!_state.value.playing) return
        skipSentence(by.coerceAtLeast(1))
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
