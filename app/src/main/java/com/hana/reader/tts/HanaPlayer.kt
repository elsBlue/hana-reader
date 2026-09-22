package com.hana.reader.tts

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.hana.reader.data.Book
import com.hana.reader.data.ProgressStore
import com.hana.reader.data.ReadingProgress
import com.hana.reader.data.TextUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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
    private val voicePrefs = VoicePrefs(appContext)
    private val system = SystemTtsEngine(appContext)
    private val neural = NeuralTtsEngine()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prepareMutex = Mutex()
    private val _state = MutableStateFlow(PlayerSnapshot())
    val state: StateFlow<PlayerSnapshot> = _state.asStateFlow()
    private var speakJob: Job? = null
    private var prefetch: kotlinx.coroutines.Deferred<PcmAudio?>? = null
    private var prefetchKey: String? = null
    /** After play/restart, first EN neural chunk is short for faster time-to-first-audio. */
    private var firstChunkAfterRestart = true
    /** Leftover of a hard-capped sentence; spoken before advancing sentenceIndex. */
    private var sentenceRemainder: String? = null

    fun play(book: Book, chapterIndex: Int? = null, sentenceIndex: Int? = null) {
        val saved = store.get(book.id)
        val ch = chapterIndex ?: saved?.chapterIndex ?: 0
        val se = sentenceIndex ?: saved?.sentenceIndex ?: 0
        // Instant tap feedback — never look like a no-op while pre-buffer/synth catches up.
        _state.value = _state.value.copy(
            book = book,
            chapterIndex = ch.coerceAtLeast(0),
            sentenceIndex = se.coerceAtLeast(0),
            playing = true,
            status = "Starting…"
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
        sentenceRemainder = null
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
        sentenceRemainder = null
        _state.value = snap.copy(chapterIndex = ch, sentenceIndex = se)
        persist()
        if (snap.playing) {
            restartSpeak(prepare = true)
        } else {
            cancelMismatchedPrebuffer(book, ch, se)
            scope.launch { prebufferFirstUtterance(book, ch, se) }
        }
    }

    fun skipChapter(delta: Int) {
        val snap = _state.value
        val book = snap.book ?: return
        val ch = (snap.chapterIndex + delta).coerceIn(0, book.chapters.lastIndex)
        sentenceRemainder = null
        _state.value = snap.copy(chapterIndex = ch, sentenceIndex = 0)
        persist()
        if (snap.playing) {
            restartSpeak(prepare = true)
        } else {
            cancelMismatchedPrebuffer(book, ch, 0)
            scope.launch { prebufferFirstUtterance(book, ch, 0) }
        }
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

    fun voicePreferences(): VoicePrefs = voicePrefs
    fun modelManager(): TtsModelManager = models
    fun neuralEngine(): NeuralTtsEngine = neural

    /**
     * Kick neural prepare early (Voices ready / Reader open) so Listen is warm.
     * When [book] is provided (or already in state), also pre-synthesize the first
     * hard-capped utterance into the prefetch slot while the user is still reading.
     */
    fun warmPrepare(language: String, book: Book? = null) {
        if (_state.value.profile != VoiceProfile.Hana) return
        if (TtsPacks.forLanguage(language) == null) return
        scope.launch {
            prepareNeuralIfNeeded(language)
            val target = book ?: _state.value.book
            if (target != null && target.language == language && neural.isLoaded(language)) {
                val saved = store.get(target.id)
                val snap = _state.value
                val ch = if (snap.book?.id == target.id) {
                    snap.chapterIndex
                } else {
                    saved?.chapterIndex ?: 0
                }
                val se = if (snap.book?.id == target.id) {
                    snap.sentenceIndex
                } else {
                    saved?.sentenceIndex ?: 0
                }
                prebufferFirstUtterance(target, ch, se)
            }
        }
    }

    fun shutdown() {
        pause()
        neural.release()
        system.shutdown()
    }

    private fun restartSpeak(prepare: Boolean) {
        speakJob?.cancel()
        // Preserve in-flight / ready first-utterance pre-buffer when position matches.
        val bookNow = _state.value.book
        val keepKey = if (bookNow != null) {
            firstUtterancePrefetchKey(
                bookNow,
                _state.value.chapterIndex,
                _state.value.sentenceIndex
            )
        } else {
            null
        }
        if (keepKey == null || prefetchKey != keepKey) {
            prefetch?.cancel()
            prefetch = null
            prefetchKey = null
        }
        sentenceRemainder = null
        firstChunkAfterRestart = true
        neural.stop()
        system.stop()
        speakJob = scope.launch {
            val book = _state.value.book ?: return@launch
            if (prepare) {
                if (!neural.isLoaded(book.language) && _state.value.profile == VoiceProfile.Hana) {
                    if (_state.value.status == null || _state.value.status == "Starting…") {
                        _state.value = _state.value.copy(status = "Preparing voice…")
                    }
                }
                prepareNeuralIfNeeded(book.language)
            }
            if (_state.value.playing) speakOnce()
        }
    }

    private suspend fun prepareNeuralIfNeeded(language: String) {
        if (_state.value.profile != VoiceProfile.Hana) return
        if (TtsPacks.forLanguage(language) == null) return
        prepareMutex.withLock {
            if (neural.isLoaded(language)) {
                markNeuralReady()
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
                } else if (!neural.isLoaded(language)) {
                    _state.value = _state.value.copy(
                        status = "Preparing voice…",
                        downloadProgress = null
                    )
                }
                val modelFiles = files ?: error("Voice pack missing after ensure")
                if (!neural.isLoaded(language)) {
                    withContext(Dispatchers.Default) { neural.prepare(language, modelFiles) }
                }
                markNeuralReady()
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
            val sid = TtsPacks.speakerId(book.language, snap.profile, voicePrefs.selectedSid(book.language))
            // First audible chunk: hard-capped ~48 chars for faster TTFA.
            // Later EN chunks: 1 sentence / ~160 so a prefetch miss stays short.
            val isFirst = firstChunkAfterRestart && sentenceRemainder == null
            if (firstChunkAfterRestart) firstChunkAfterRestart = false

            val effectiveList = if (sentenceRemainder != null) {
                list.toMutableList().also { mutable ->
                    if (snap.sentenceIndex in mutable.indices) {
                        mutable[snap.sentenceIndex] = sentenceRemainder!!
                    }
                }
            } else {
                list
            }

            val (maxSentences, maxChars) = chunkLimits(book.language, isFirst)
            val chunk = TextUtil.speakChunk(
                effectiveList,
                snap.sentenceIndex,
                maxSentences = maxSentences,
                maxChars = maxChars,
                isFirst = isFirst
            )
            val text = chunk.text
            val consumed = chunk.consumed
            sentenceRemainder = chunk.remainder
            if (text.isBlank() && consumed <= 0 && chunk.remainder == null) {
                advance(1)
                return
            }
            if (text.isBlank()) {
                // Empty prefix with remainder — continue without playing.
                if (_state.value.playing) continueSpeaking()
                return
            }
            val key = utterancePrefetchKey(
                snap.chapterIndex,
                snap.sentenceIndex,
                consumed,
                chunk.remainder != null,
                text.length
            )
            val started = SystemClock.elapsedRealtime()
            val cached = awaitPrefetchOrNull(key, waitingFirst = isFirst)
            prefetch?.cancel()
            prefetch = null
            prefetchKey = null
            val audio = if (cached != null) {
                cached
            } else {
                val ticker = launchStatusTicker(isFirst)
                try {
                    withContext(Dispatchers.Default) {
                        neural.synthesize(text, book.language, sid, speed)
                    }
                } finally {
                    ticker.cancel()
                }
            }
            val generateMs = SystemClock.elapsedRealtime() - started
            val audioMs = if (audio.sampleRate > 0) {
                (audio.samples.size * 1000L) / audio.sampleRate
            } else {
                0L
            }
            val rtf = if (audioMs > 0) generateMs.toDouble() / audioMs.toDouble() else 0.0
            Log.i(
                TAG,
                "chars=${text.length} generateMs=$generateMs audioMs=$audioMs rtf=${"%.2f".format(rtf)} " +
                    "prefetchHit=${cached != null} first=$isFirst"
            )
            // Prefetch next while current plays (engine lock serializes generate).
            prefetchNext(book, snap, consumed, chunk.remainder, sid, speed)
            _state.value = _state.value.copy(status = null)
            withContext(Dispatchers.IO) { neural.play(audio) }
            if (_state.value.playing) {
                if (chunk.remainder != null) {
                    // Same sentenceIndex; remainder already stored.
                    continueSpeaking()
                } else if (consumed > 0) {
                    advance(consumed)
                } else {
                    advance(1)
                }
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            sentenceRemainder = null
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
        remainder: String?,
        sid: Int,
        speed: Float
    ) {
        var ch = snap.chapterIndex
        var se = snap.sentenceIndex
        var list = sentences(book, ch)
        val nextText: String
        val nextConsumed: Int
        val nextRemainder: String?
        if (remainder != null) {
            val injected = list.toMutableList().also {
                if (se in it.indices) it[se] = remainder
            }
            val (maxSentences, maxChars) = chunkLimits(book.language, isFirst = false)
            val next = TextUtil.speakChunk(injected, se, maxSentences, maxChars, isFirst = false)
            nextText = next.text
            nextConsumed = next.consumed
            nextRemainder = next.remainder
        } else {
            se = snap.sentenceIndex + consumed
            if (se >= list.size) {
                if (ch >= book.chapters.lastIndex) return
                ch += 1
                se = 0
                list = sentences(book, ch)
            }
            val (maxSentences, maxChars) = chunkLimits(book.language, isFirst = false)
            val next = TextUtil.speakChunk(list, se, maxSentences, maxChars, isFirst = false)
            nextText = next.text
            nextConsumed = next.consumed
            nextRemainder = next.remainder
        }
        if (nextText.isBlank() || (nextConsumed <= 0 && nextRemainder == null)) return
        val key = utterancePrefetchKey(ch, se, nextConsumed, nextRemainder != null, nextText.length)
        prefetchKey = key
        prefetch = scope.async(Dispatchers.Default) {
            runCatching { neural.synthesize(nextText, book.language, sid, speed) }.getOrNull()
        }
    }

    /**
     * Move forward after a finished chunk without restartSpeak — keeps prefetch alive
     * and does not re-arm the first-utterance hard-cap.
     */
    private fun advance(by: Int) {
        if (!_state.value.playing) return
        val snap = _state.value
        val book = snap.book ?: return
        var ch = snap.chapterIndex
        var se = snap.sentenceIndex + by.coerceAtLeast(1)
        val list = sentences(book, ch)
        if (se >= list.size) {
            if (ch < book.chapters.lastIndex) {
                ch += 1
                se = 0
            } else {
                pause()
                return
            }
        }
        sentenceRemainder = null
        _state.value = snap.copy(chapterIndex = ch, sentenceIndex = se)
        persist()
        continueSpeaking()
    }

    private fun continueSpeaking() {
        speakJob = scope.launch {
            if (_state.value.playing) speakOnce()
        }
    }


    private fun markNeuralReady() {
        val playing = _state.value.playing
        val keepStatus = playing && (
            _state.value.status?.contains("Starting", true) == true ||
                _state.value.status?.contains("Getting first", true) == true ||
                _state.value.status?.contains("Synthesizing", true) == true ||
                _state.value.status?.contains("Preparing", true) == true
            )
        _state.value = _state.value.copy(
            usingNeural = true,
            downloadProgress = null,
            status = if (keepStatus) _state.value.status else "Neural voice ready"
        )
    }

    /**
     * While the user is still reading, synthesize the first hard-capped utterance
     * into the shared prefetch slot so Listen often hits cache.
     */
    private suspend fun prebufferFirstUtterance(book: Book, chapterIndex: Int, sentenceIndex: Int) {
        if (_state.value.profile != VoiceProfile.Hana) return
        if (!neural.isLoaded(book.language)) return
        if (_state.value.playing) return
        val ch = chapterIndex.coerceAtLeast(0)
        val se = sentenceIndex.coerceAtLeast(0)
        val list = sentences(book, ch)
        if (se !in list.indices) return
        val planned = planFirstUtterance(ch, se, list) ?: return
        if (prefetchKey == planned.key && prefetch?.isActive == true) return
        if (prefetchKey == planned.key && prefetch?.isCompleted == true) return
        prefetch?.cancel()
        prefetchKey = planned.key
        val sid = TtsPacks.speakerId(
            book.language,
            _state.value.profile,
            voicePrefs.selectedSid(book.language)
        )
        val speed = _state.value.rate
        val text = planned.chunk.text
        Log.i(TAG, "prebuffer first chars=${text.length} key=${planned.key}")
        prefetch = scope.async(Dispatchers.Default) {
            runCatching { neural.synthesize(text, book.language, sid, speed) }.getOrNull()
        }
    }

    private fun cancelMismatchedPrebuffer(book: Book, chapterIndex: Int, sentenceIndex: Int) {
        val expected = firstUtterancePrefetchKey(book, chapterIndex, sentenceIndex)
        if (expected == null || prefetchKey != expected) {
            prefetch?.cancel()
            prefetch = null
            prefetchKey = null
        }
    }

    private fun firstUtterancePrefetchKey(book: Book, chapterIndex: Int, sentenceIndex: Int): String? {
        val list = sentences(book, chapterIndex)
        return planFirstUtterance(chapterIndex, sentenceIndex, list)?.key
    }

    private suspend fun awaitPrefetchOrNull(key: String, waitingFirst: Boolean): PcmAudio? {
        if (prefetchKey != key) return null
        val deferred = prefetch ?: return null
        if (deferred.isCompleted) return deferred.await()
        // Prefetch still running (common after warmPrepare). Tick status after ~300ms.
        if (_state.value.status.isNullOrBlank() || _state.value.status == "Neural voice ready") {
            _state.value = _state.value.copy(status = "Starting…")
        }
        val ticker = scope.launch {
            delay(PREBUFFER_FEEDBACK_MS)
            var lastSec = -1
            val t0 = SystemClock.elapsedRealtime()
            while (true) {
                val sec = ((SystemClock.elapsedRealtime() - t0) / 1000L).toInt()
                if (sec != lastSec) {
                    lastSec = sec
                    val label = if (waitingFirst) {
                        if (sec <= 0) "Getting first line…" else "Getting first line… ${sec}s"
                    } else {
                        if (sec <= 0) "Synthesizing…" else "Synthesizing… ${sec}s"
                    }
                    _state.value = _state.value.copy(status = label)
                }
                delay(STATUS_TICK_MS)
            }
        }
        return try {
            deferred.await()
        } finally {
            ticker.cancel()
        }
    }

    private fun launchStatusTicker(isFirst: Boolean): Job {
        val t0 = SystemClock.elapsedRealtime()
        _state.value = _state.value.copy(
            status = if (isFirst) "Getting first line…" else "Synthesizing…"
        )
        return scope.launch {
            var lastSec = -1
            while (true) {
                delay(STATUS_TICK_MS)
                val sec = ((SystemClock.elapsedRealtime() - t0) / 1000L).toInt()
                if (sec != lastSec) {
                    lastSec = sec
                    val label = if (isFirst) {
                        if (sec <= 0) "Getting first line…" else "Getting first line… ${sec}s"
                    } else {
                        if (sec <= 0) "Synthesizing…" else "Synthesizing… ${sec}s"
                    }
                    _state.value = _state.value.copy(status = label)
                }
            }
        }
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
        private const val TAG = "HanaTts"
        private const val STATUS_TICK_MS = 500L
        private const val PREBUFFER_FEEDBACK_MS = 300L
        /** EN later chunks: short so a prefetch miss is not a long stall. */
        const val EN_LATER_MAX_SENTENCES = 1
        const val EN_LATER_MAX_CHARS = 160
        const val ID_LATER_MAX_SENTENCES = 2
        const val ID_LATER_MAX_CHARS = 280

        @Volatile private var instance: HanaPlayer? = null
        fun get(context: Context): HanaPlayer {
            return instance ?: synchronized(this) {
                instance ?: HanaPlayer(context.applicationContext).also { instance = it }
            }
        }

        fun chunkLimits(language: String, isFirst: Boolean): Pair<Int, Int> {
            return when {
                isFirst -> 1 to TextUtil.FIRST_UTTERANCE_MAX_CHARS
                language == "en" -> EN_LATER_MAX_SENTENCES to EN_LATER_MAX_CHARS
                else -> ID_LATER_MAX_SENTENCES to ID_LATER_MAX_CHARS
            }
        }

        /** Same key scheme speakNeural / prefetchNext / prebuffer all use. */
        fun utterancePrefetchKey(
            chapterIndex: Int,
            sentenceIndex: Int,
            consumed: Int,
            hasRemainder: Boolean,
            textLength: Int
        ): String {
            val remTag = if (hasRemainder) "r" else "f"
            return "hana-$chapterIndex-$sentenceIndex-c$consumed-$remTag-$textLength"
        }

        data class PlannedUtterance(val key: String, val chunk: TextUtil.SpeakChunkResult)

        fun planFirstUtterance(
            chapterIndex: Int,
            sentenceIndex: Int,
            sentences: List<String>
        ): PlannedUtterance? {
            if (sentenceIndex !in sentences.indices) return null
            val chunk = TextUtil.speakChunk(
                sentences,
                sentenceIndex,
                maxSentences = 1,
                maxChars = TextUtil.FIRST_UTTERANCE_MAX_CHARS,
                isFirst = true
            )
            if (chunk.text.isBlank()) return null
            val key = utterancePrefetchKey(
                chapterIndex,
                sentenceIndex,
                chunk.consumed,
                chunk.remainder != null,
                chunk.text.length
            )
            return PlannedUtterance(key, chunk)
        }
    }
}
