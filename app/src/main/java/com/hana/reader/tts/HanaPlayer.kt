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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.ArrayDeque

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
    private val queueMutex = Mutex()
    private val _state = MutableStateFlow(PlayerSnapshot())
    val state: StateFlow<PlayerSnapshot> = _state.asStateFlow()
    private var speakJob: Job? = null
    private var fillJob: Job? = null
    /** Lookahead of already-synthesized PCM chunks (depth [QUEUE_DEPTH]). */
    private val readyQueue = ArrayDeque<ReadyChunk>()
    /** Where the next synth for the queue should start. */
    private var queueTail: QueueCursor? = null
    /** After play/restart, first EN neural chunk uses the continuous short budget. */
    private var firstChunkAfterRestart = true
    /** Leftover of a hard-capped sentence; spoken before advancing sentenceIndex. */
    private var sentenceRemainder: String? = null
    /** Pack that produced the current lookahead queue — drop queue on voice/pack change. */
    private var queuePackId: String? = null

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
        clearReadyQueue()
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
            clearReadyQueue()
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
            clearReadyQueue()
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
     * chunks into the lookahead queue while the user is still reading.
     */
    fun warmPrepare(language: String, book: Book? = null) {
        if (_state.value.profile != VoiceProfile.Hana) return
        if (TtsPacks.forLanguage(language) == null) return
        scope.launch {
            prepareNeuralIfNeeded(language)
            val target = book ?: _state.value.book
            if (target != null && target.language == language && neural.isLoadedPack(activePack(language).packId)) {
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
        // Preserve lookahead when Listen resumes at the same first-utterance key
        // (warmPrepare / Reader open prebuffer). Drop on seek / mismatch.
        // Intentionally stop audio here; normal chunk advance must NOT call stop().
        val bookNow = _state.value.book
        val keep = bookNow != null &&
            queueHeadMatchesFirst(bookNow) &&
            queuePackId == activePack(bookNow.language).packId
        if (!keep) {
            clearReadyQueue()
        } else {
            fillJob?.cancel()
            fillJob = null
        }
        sentenceRemainder = null
        firstChunkAfterRestart = true
        neural.stop()
        system.stop()
        speakJob = scope.launch {
            val book = _state.value.book ?: return@launch
            if (prepare) {
                if (!neural.isLoadedPack(activePack(book.language).packId) && _state.value.profile == VoiceProfile.Hana) {
                    if (_state.value.status == null || _state.value.status == "Starting…") {
                        _state.value = _state.value.copy(status = "Preparing voice…")
                    }
                }
                prepareNeuralIfNeeded(book.language)
            }
            if (_state.value.playing) speakOnce()
        }
    }

    private fun activePack(language: String): TtsPack {
        return TtsPacks.packForVoice(voicePrefs.selectedVoiceId(language))
            ?: TtsPacks.forLanguage(language)
            ?: TtsPacks.EN
    }

    private suspend fun prepareNeuralIfNeeded(language: String) {
        if (_state.value.profile != VoiceProfile.Hana) return
        if (TtsPacks.forLanguage(language) == null) return
        val pack = activePack(language)
        prepareMutex.withLock {
            if (neural.isLoadedPack(pack.packId)) {
                markNeuralReady()
                return
            }
            try {
                val key = pack.storageKey
                var files = models.files(key)
                if (files == null) {
                    _state.value = _state.value.copy(
                        downloadProgress = 0f,
                        status = "Downloading ${pack.displayName}…"
                    )
                    files = models.ensure(key) { p ->
                        _state.value = _state.value.copy(
                            downloadProgress = p,
                            status = "Downloading ${pack.displayName}… ${(p * 100).toInt()}%"
                        )
                    }
                } else {
                    _state.value = _state.value.copy(
                        status = "Preparing voice…",
                        downloadProgress = null
                    )
                }
                val modelFiles = files ?: error("Voice pack missing after ensure")
                if (!neural.isLoadedPack(pack.packId)) {
                    clearReadyQueue()
                    withContext(Dispatchers.Default) {
                        neural.prepare(language, modelFiles, pack.packId)
                    }
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
        val useNeural = snap.profile == VoiceProfile.Hana &&
            neural.isLoadedPack(activePack(book.language).packId)
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
            val isFirst = firstChunkAfterRestart && sentenceRemainder == null
            if (firstChunkAfterRestart) firstChunkAfterRestart = false

            val starved = queueMutex.withLock { readyQueue.isEmpty() }

            // First audible chunk as soon as one is ready — do not wait for a full queue.
            val head = ensureQueueHead(book, snap, sid, speed, isFirst) ?: run {
                advance(1)
                return
            }

            // Mid-listen starve: one wait, refill a couple of chunks, then keep streaming.
            if (!isFirst && starved) {
                val refillTicker = launchStatusTicker(isFirst = false)
                try {
                    while (readyQueue.size < (PLAY_RESUME_DEPTH - 1).coerceAtLeast(1) && _state.value.playing) {
                        val added = topUpOne(book, sid, speed) ?: break
                        if (!added) break
                    }
                } finally {
                    refillTicker.cancel()
                }
            }

            kickQueueFill(book, sid, speed)

            sentenceRemainder = head.remainder
            val text = head.text
            val consumed = head.consumed
            val generateMs = head.generateMs
            val audio = head.audio
            val audioMs = if (audio.sampleRate > 0) {
                (audio.samples.size * 1000L) / audio.sampleRate
            } else {
                0L
            }
            val rtf = if (audioMs > 0 && generateMs > 0) generateMs.toDouble() / audioMs.toDouble() else 0.0
            Log.i(
                TAG,
                "chars=${text.length} generateMs=$generateMs audioMs=$audioMs rtf=${"%.2f".format(rtf)} " +
                    "queueHit=${!head.synthesizedInline} queueSize=${readyQueue.size} first=$isFirst " +
                    "pack=${activePack(book.language).packId}"
            )
            _state.value = _state.value.copy(status = null)
            // Persistent stream — never stop/recreate the track between chunks (cuts mid-word).
            withContext(Dispatchers.IO) { neural.writeStreaming(audio) }
            if (_state.value.playing) {
                if (head.remainder != null) {
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
            clearReadyQueue()
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

    /**
     * Take queue head if it matches the current speak position; otherwise rebuild
     * and synthesize. Status is only shown when the listener would hear silence.
     */
    private suspend fun ensureQueueHead(
        book: Book,
        snap: PlayerSnapshot,
        sid: Int,
        speed: Float,
        isFirst: Boolean
    ): ReadyChunk? {
        queueMutex.withLock {
            val expectedKey = currentPositionKey(book, snap, isFirst)
            val peek = readyQueue.peekFirst()
            if (peek != null && expectedKey != null && peek.key == expectedKey) {
                return readyQueue.removeFirst()
            }
            if (peek != null && expectedKey == null) {
                // Blank / unplanned — drop stale queue.
                readyQueue.clear()
                queueTail = null
            } else if (peek != null) {
                // Key mismatch — discard stale lookahead and resynth from live position.
                Log.w(TAG, "queue key miss want=$expectedKey have=${peek.key}")
                readyQueue.clear()
                queueTail = null
            }
        }

        // Queue empty: synthesize current chunk inline (status visible).
        val planned = planAtCurrent(book, snap, isFirst) ?: return null
        val ticker = launchStatusTicker(isFirst)
        val t0 = SystemClock.elapsedRealtime()
        return try {
            val audio = withContext(Dispatchers.Default) {
                neural.synthesize(planned.text, book.language, sid, speed)
            }
            val chunk = ReadyChunk(
                key = planned.key,
                text = planned.text,
                chapterIndex = planned.chapterIndex,
                sentenceIndex = planned.sentenceIndex,
                consumed = planned.consumed,
                remainder = planned.remainder,
                audio = audio,
                generateMs = SystemClock.elapsedRealtime() - t0,
                synthesizedInline = true
            )
            queueMutex.withLock {
                // Tail starts after this chunk so fill can continue.
                queueTail = advanceCursor(book, planned)
            }
            chunk
        } finally {
            ticker.cancel()
        }
    }

    private fun kickQueueFill(book: Book, sid: Int, speed: Float) {
        if (fillJob?.isActive == true) return
        fillJob = scope.launch(Dispatchers.Default) {
            try {
                while (_state.value.playing || readyQueue.size < QUEUE_DEPTH) {
                    val added = topUpOne(book, sid, speed) ?: break
                    if (!added) break
                    if (!_state.value.playing && readyQueue.size >= QUEUE_DEPTH) break
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Log.w(TAG, "queue fill failed: ${t.message}")
            }
        }
    }

    /** @return true if a chunk was enqueued, false if nothing left / stopped, null on error skip */
    private suspend fun topUpOne(book: Book, sid: Int, speed: Float): Boolean? {
        val plan: PlannedChunk
        queueMutex.withLock {
            if (readyQueue.size >= QUEUE_DEPTH) return false
            val cursor = queueTail ?: QueueCursor(
                chapterIndex = _state.value.chapterIndex,
                sentenceIndex = _state.value.sentenceIndex,
                remainder = sentenceRemainder,
                isFirst = firstChunkAfterRestart && sentenceRemainder == null
            )
            val next = planFromCursor(book, cursor) ?: run {
                return false
            }
            // Reserve slot by advancing tail before synth so concurrent fills don't duplicate.
            if (readyQueue.any { it.key == next.key }) {
                queueTail = advanceCursor(book, next)
                return true
            }
            queueTail = advanceCursor(book, next)
            plan = next
        }
        val t0 = SystemClock.elapsedRealtime()
        val audio = runCatching {
            neural.synthesize(plan.text, book.language, sid, speed)
        }.getOrNull() ?: return null
        val chunk = ReadyChunk(
            key = plan.key,
            text = plan.text,
            chapterIndex = plan.chapterIndex,
            sentenceIndex = plan.sentenceIndex,
            consumed = plan.consumed,
            remainder = plan.remainder,
            audio = audio,
            generateMs = SystemClock.elapsedRealtime() - t0,
            synthesizedInline = false
        )
        queueMutex.withLock {
            // Only enqueue if still relevant (tail still past this plan).
            if (!_state.value.playing && readyQueue.size >= QUEUE_DEPTH) return false
            readyQueue.addLast(chunk)
            queuePackId = activePack(book.language).packId
        }
        return true
    }

    /**
     * Move forward after a finished chunk without restartSpeak — keeps the lookahead
     * queue alive and does not re-arm the first-utterance hard-cap.
     * Must not call neural.stop()/play() — that would cut the just-finished path and
     * any overlapping write; only stop on user pause / seek / profile change.
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
                speakJob = scope.launch {
                    withContext(Dispatchers.IO) { neural.waitUntilDrained() }
                    if (_state.value.playing) pause()
                }
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
     * While the user is still reading, synthesize the first continuous chunks into
     * the lookahead queue so Listen often starts with depth ≥ 2.
     */
    private suspend fun prebufferFirstUtterance(book: Book, chapterIndex: Int, sentenceIndex: Int) {
        if (_state.value.profile != VoiceProfile.Hana) return
        if (!neural.isLoadedPack(activePack(book.language).packId)) return
        if (_state.value.playing) return
        val ch = chapterIndex.coerceAtLeast(0)
        val se = sentenceIndex.coerceAtLeast(0)
        val list = sentences(book, ch)
        if (se !in list.indices) return

        queueMutex.withLock {
            val planned = planFirstUtterance(
                ch, se, list,
                language = book.language,
                kind = activePack(book.language).kind
            ) ?: return
            val head = readyQueue.peekFirst()
            if (head != null && head.key == planned.key && readyQueue.size >= QUEUE_DEPTH) return
            if (head == null || head.key != planned.key) {
                readyQueue.clear()
                queueTail = QueueCursor(ch, se, remainder = null, isFirst = true)
            }
        }

        val sid = TtsPacks.speakerId(
            book.language,
            _state.value.profile,
            voicePrefs.selectedSid(book.language)
        )
        val speed = _state.value.rate
        Log.i(TAG, "prebuffer queue depth=$QUEUE_DEPTH from ch=$ch se=$se")
        // Fill serially to depth (offline session is single-threaded anyway).
        repeat(QUEUE_DEPTH) {
            if (_state.value.playing) return
            val added = topUpOne(book, sid, speed) ?: return
            if (!added) return
        }
    }

    private fun clearReadyQueue() {
        fillJob?.cancel()
        fillJob = null
        readyQueue.clear()
        queueTail = null
        queuePackId = null
    }

    private fun queueHeadMatchesFirst(book: Book): Boolean {
        val planned = planFirstUtterance(
            _state.value.chapterIndex,
            _state.value.sentenceIndex,
            sentences(book, _state.value.chapterIndex),
            language = book.language,
            kind = activePack(book.language).kind
        ) ?: return false
        val head = readyQueue.peekFirst() ?: return false
        return head.key == planned.key
    }

    private fun currentPositionKey(book: Book, snap: PlayerSnapshot, isFirst: Boolean): String? {
        val planned = planAtCurrent(book, snap, isFirst) ?: return null
        return planned.key
    }

    private fun planAtCurrent(book: Book, snap: PlayerSnapshot, isFirst: Boolean): PlannedChunk? {
        return planFromCursor(
            book,
            QueueCursor(
                chapterIndex = snap.chapterIndex,
                sentenceIndex = snap.sentenceIndex,
                remainder = sentenceRemainder,
                isFirst = isFirst
            )
        )
    }

    private fun planFromCursor(book: Book, cursor: QueueCursor): PlannedChunk? {
        var ch = cursor.chapterIndex
        var se = cursor.sentenceIndex
        var list = sentences(book, ch)
        if (se !in list.indices && cursor.remainder == null) {
            if (ch >= book.chapters.lastIndex) return null
            ch += 1
            se = 0
            list = sentences(book, ch)
            if (se !in list.indices) return null
        }
        val effectiveList = if (cursor.remainder != null) {
            list.toMutableList().also { mutable ->
                if (se in mutable.indices) {
                    mutable[se] = cursor.remainder
                } else if (mutable.isEmpty()) {
                    return null
                } else {
                    // Remainder with invalid index — treat as text at se 0 of empty inject
                    return null
                }
            }
        } else {
            list
        }
        if (se !in effectiveList.indices) return null
        val (maxSentences, maxChars) = chunkLimits(
            book.language,
            cursor.isFirst,
            activePack(book.language).kind
        )
        val chunk = TextUtil.speakChunk(
            effectiveList,
            se,
            maxSentences = maxSentences,
            maxChars = maxChars,
            isFirst = cursor.isFirst
        )
        if (chunk.text.isBlank() && chunk.consumed <= 0 && chunk.remainder == null) return null
        if (chunk.text.isBlank()) return null
        return PlannedChunk(
            chapterIndex = ch,
            sentenceIndex = se,
            text = chunk.text,
            consumed = chunk.consumed,
            remainder = chunk.remainder,
            key = utterancePrefetchKey(
                ch,
                se,
                chunk.consumed,
                chunk.remainder != null,
                chunk.text.length
            )
        )
    }

    private fun advanceCursor(book: Book, planned: PlannedChunk): QueueCursor {
        return if (planned.remainder != null) {
            QueueCursor(
                chapterIndex = planned.chapterIndex,
                sentenceIndex = planned.sentenceIndex,
                remainder = planned.remainder,
                isFirst = false
            )
        } else {
            var ch = planned.chapterIndex
            var se = planned.sentenceIndex + planned.consumed.coerceAtLeast(1)
            val list = sentences(book, ch)
            if (se >= list.size) {
                if (ch < book.chapters.lastIndex) {
                    ch += 1
                    se = 0
                }
            }
            QueueCursor(ch, se, remainder = null, isFirst = false)
        }
    }

    private fun launchStatusTicker(isFirst: Boolean): Job {
        val t0 = SystemClock.elapsedRealtime()
        val base = if (isFirst) "Getting first line…" else "Preparing a few lines…"
        _state.value = _state.value.copy(status = base)
        return scope.launch {
            var lastSec = -1
            while (true) {
                delay(STATUS_TICK_MS)
                val sec = ((SystemClock.elapsedRealtime() - t0) / 1000L).toInt()
                if (sec != lastSec) {
                    lastSec = sec
                    val label = if (sec <= 0) base else "$base ${sec}s"
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

    private data class QueueCursor(
        val chapterIndex: Int,
        val sentenceIndex: Int,
        val remainder: String?,
        val isFirst: Boolean
    )

    private data class ReadyChunk(
        val key: String,
        val text: String,
        val chapterIndex: Int,
        val sentenceIndex: Int,
        val consumed: Int,
        val remainder: String?,
        val audio: PcmAudio,
        val generateMs: Long,
        val synthesizedInline: Boolean
    )

    companion object {
        private const val TAG = "HanaTts"
        private const val STATUS_TICK_MS = 500L
        /** Lookahead depth: keep this many synthesized chunks ready ahead of play. */
        const val QUEUE_DEPTH = 4
        /** After a starve, wait until this many chunks exist before resuming audio. */
        const val PLAY_RESUME_DEPTH = 2
        /** EN continuous: 1 sentence / ~64 chars for first and later (RTF keep-up). */
        const val EN_LATER_MAX_SENTENCES = 1
        const val EN_LATER_MAX_CHARS = TextUtil.EN_CHUNK_MAX_CHARS
        const val ID_LATER_MAX_SENTENCES = 2
        const val ID_LATER_MAX_CHARS = 280

        @Volatile private var instance: HanaPlayer? = null
        fun get(context: Context): HanaPlayer {
            return instance ?: synchronized(this) {
                instance ?: HanaPlayer(context.applicationContext).also { instance = it }
            }
        }

        fun chunkLimits(
            language: String,
            isFirst: Boolean,
            kind: NeuralKind = NeuralKind.Kokoro
        ): Pair<Int, Int> {
            if (kind == NeuralKind.Piper && language == "en") {
                return if (isFirst) 1 to 120 else 2 to 280
            }
            return when {
                isFirst -> 1 to TextUtil.FIRST_UTTERANCE_MAX_CHARS
                language == "en" -> EN_LATER_MAX_SENTENCES to EN_LATER_MAX_CHARS
                else -> ID_LATER_MAX_SENTENCES to ID_LATER_MAX_CHARS
            }
        }

        /** Same key scheme speakNeural / queue fill / prebuffer all use. */
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

        data class PlannedChunk(
            val chapterIndex: Int,
            val sentenceIndex: Int,
            val text: String,
            val consumed: Int,
            val remainder: String?,
            val key: String
        )

        data class PlannedUtterance(val key: String, val chunk: TextUtil.SpeakChunkResult)

        fun planFirstUtterance(
            chapterIndex: Int,
            sentenceIndex: Int,
            sentences: List<String>,
            language: String = "en",
            kind: NeuralKind = NeuralKind.Kokoro
        ): PlannedUtterance? {
            if (sentenceIndex !in sentences.indices) return null
            val (maxSentences, maxChars) = chunkLimits(language, isFirst = true, kind)
            val chunk = TextUtil.speakChunk(
                sentences,
                sentenceIndex,
                maxSentences = maxSentences,
                maxChars = maxChars,
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

        /**
         * Pure helper: given a list of chapter sentence-lists and a cursor, plan the
         * next [count] chunks (for unit tests of queue depth / key stability).
         */
        fun planLookahead(
            chapterSentences: List<List<String>>,
            chapterIndex: Int,
            sentenceIndex: Int,
            remainder: String?,
            language: String,
            isFirst: Boolean,
            count: Int,
            kind: NeuralKind = NeuralKind.Kokoro
        ): List<PlannedChunk> {
            val out = ArrayList<PlannedChunk>(count)
            var ch = chapterIndex
            var se = sentenceIndex
            var rem = remainder
            var first = isFirst
            repeat(count) {
                if (ch !in chapterSentences.indices) return out
                var list = chapterSentences[ch]
                if (se !in list.indices && rem == null) {
                    if (ch >= chapterSentences.lastIndex) return out
                    ch += 1
                    se = 0
                    list = chapterSentences[ch]
                    if (se !in list.indices) return out
                }
                val effective = if (rem != null) {
                    list.toMutableList().also {
                        if (se in it.indices) it[se] = rem!! else return out
                    }
                } else {
                    list
                }
                val (maxSentences, maxChars) = chunkLimits(language, first, kind)
                val chunk = TextUtil.speakChunk(
                    effective,
                    se,
                    maxSentences = maxSentences,
                    maxChars = maxChars,
                    isFirst = first
                )
                first = false
                if (chunk.text.isBlank()) return out
                val planned = PlannedChunk(
                    chapterIndex = ch,
                    sentenceIndex = se,
                    text = chunk.text,
                    consumed = chunk.consumed,
                    remainder = chunk.remainder,
                    key = utterancePrefetchKey(
                        ch, se, chunk.consumed, chunk.remainder != null, chunk.text.length
                    )
                )
                out.add(planned)
                if (chunk.remainder != null) {
                    rem = chunk.remainder
                } else {
                    rem = null
                    se += chunk.consumed.coerceAtLeast(1)
                    if (se >= list.size) {
                        if (ch >= chapterSentences.lastIndex) return out
                        ch += 1
                        se = 0
                    }
                }
            }
            return out
        }
    }
}
