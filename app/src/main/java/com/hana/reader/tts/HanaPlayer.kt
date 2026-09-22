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
import java.util.concurrent.atomic.AtomicInteger

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
    /** Keys currently being synthesized (prebuffer / fill / inline). */
    private val reservedKeys = mutableSetOf<String>()
    @Volatile private var reservedSnapshot: Set<String> = emptySet()
    @Volatile private var headKeySnapshot: String? = null
    /** Bumped when the queue is discarded so in-flight synth results are dropped. */
    private val synthEpoch = AtomicInteger(0)
    /** True after the first samples of this Listen session were written. */
    @Volatile private var storyStarted = false

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
        storyStarted = false
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
        storyStarted = false
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
        // Preserve in-flight first line (prebuffer / fill) when Listen is tapped
        // at the same position. Clearing it forces a second 10s synth.
        val bookNow = _state.value.book
        val plannedFirst = bookNow?.let {
            planFirstUtterance(
                _state.value.chapterIndex,
                _state.value.sentenceIndex,
                sentences(it, _state.value.chapterIndex),
                language = it.language,
                kind = activePack(it.language).kind
            )
        }
        val packMatches = bookNow != null &&
            (queuePackId == null || queuePackId == activePack(bookNow.language).packId)
        val keep = shouldPreserveLookahead(
            packMatches = packMatches,
            firstKey = plannedFirst?.key,
            queueHeadKey = headKeySnapshot,
            reservedKeys = reservedSnapshot
        )
        if (!keep) {
            clearReadyQueue()
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

            // One synth pipeline: fill (or prebuffer) owns generate; play waits for it.
            kickQueueFill(book, sid, speed)
            val head = awaitPlayableHead(book, snap, sid, speed, isFirst) ?: run {
                advance(1)
                return
            }

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
            val pack = activePack(book.language)
            val hint = if (rtf >= 1.15 && pack.kind == NeuralKind.Kokoro) {
                "Slow on this phone — Voices → Smooth"
            } else {
                null
            }
            _state.value = _state.value.copy(status = hint)
            storyStarted = true
            kickQueueFill(book, sid, speed)
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
     * Take the current chunk if it is already queued; otherwise wait for an
     * in-flight synth (prebuffer / fill) instead of generating the same text again.
     */
    private suspend fun awaitPlayableHead(
        book: Book,
        snap: PlayerSnapshot,
        sid: Int,
        speed: Float,
        isFirst: Boolean
    ): ReadyChunk? {
        val expectedKey = currentPositionKey(book, snap, isFirst) ?: return null
        takeMatchingHead(expectedKey)?.let { return it }

        val ticker = launchStatusTicker(isFirst)
        try {
            val deadline = SystemClock.elapsedRealtime() + 45_000L
            var launchedInline = false
            while (_state.value.playing && SystemClock.elapsedRealtime() < deadline) {
                takeMatchingHead(expectedKey)?.let { return it }

                val someoneElseOwns = queueMutex.withLock { expectedKey in reservedKeys }
                if (!someoneElseOwns && !launchedInline) {
                    val planned = planAtCurrent(book, snap, isFirst) ?: return null
                    val claimed = queueMutex.withLock {
                        if (expectedKey in reservedKeys) {
                            false
                        } else {
                            reservedKeys.add(expectedKey)
                            if (queueTail == null) queueTail = advanceCursor(book, planned)
                            publishQueueSnapshots()
                            true
                        }
                    }
                    if (claimed) {
                        launchedInline = true
                        val epoch = synthEpoch.get()
                        val t0 = SystemClock.elapsedRealtime()
                        val audio = withContext(Dispatchers.Default) {
                            neural.synthesize(planned.text, book.language, sid, speed)
                        }
                        queueMutex.withLock {
                            reservedKeys.remove(expectedKey)
                            publishQueueSnapshots()
                        }
                        if (epoch != synthEpoch.get() || !_state.value.playing) return null
                        return ReadyChunk(
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
                    }
                }
                delay(40)
            }
            return takeMatchingHead(expectedKey)
        } finally {
            ticker.cancel()
        }
    }

    private suspend fun takeMatchingHead(expectedKey: String): ReadyChunk? {
        return queueMutex.withLock {
            val peek = readyQueue.peekFirst() ?: return@withLock null
            if (peek.key != expectedKey) {
                // Current line may still be in-flight; don't throw away later chunks.
                if (expectedKey in reservedKeys) return@withLock null
                Log.w(TAG, "queue key miss want=$expectedKey have=${peek.key}")
                readyQueue.clear()
                queueTail = null
                reservedKeys.clear()
                publishQueueSnapshots()
                return@withLock null
            }
            readyQueue.removeFirst().also { publishQueueSnapshots() }
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
        val epoch = synthEpoch.get()
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
            if (readyQueue.any { it.key == next.key }) {
                queueTail = advanceCursor(book, next)
                publishQueueSnapshots()
                return true
            }
            if (next.key in reservedKeys) {
                return true
            }
            reservedKeys.add(next.key)
            queueTail = advanceCursor(book, next)
            queuePackId = activePack(book.language).packId
            publishQueueSnapshots()
            plan = next
        }
        val t0 = SystemClock.elapsedRealtime()
        val audio = runCatching {
            neural.synthesize(plan.text, book.language, sid, speed)
        }.getOrNull()
        queueMutex.withLock {
            reservedKeys.remove(plan.key)
            publishQueueSnapshots()
        }
        if (audio == null) return null
        if (epoch != synthEpoch.get()) return false
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
            if (epoch != synthEpoch.get()) return false
            readyQueue.addLast(chunk)
            queuePackId = activePack(book.language).packId
            publishQueueSnapshots()
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
        if (playing) {
            val status = when {
                _state.value.status?.startsWith("Downloading") == true -> "Starting…"
                _state.value.status.isNullOrBlank() -> "Starting…"
                else -> _state.value.status
            }
            _state.value = _state.value.copy(
                usingNeural = true,
                downloadProgress = null,
                status = status
            )
        } else {
            _state.value = _state.value.copy(
                usingNeural = true,
                downloadProgress = null,
                status = "Neural voice ready"
            )
        }
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
            if (reservedKeys.contains(planned.key) && (head == null || head.key == planned.key)) {
                return
            }
            if (head == null || head.key != planned.key) {
                synthEpoch.incrementAndGet()
                readyQueue.clear()
                reservedKeys.clear()
                queueTail = QueueCursor(ch, se, remainder = null, isFirst = true)
                queuePackId = activePack(book.language).packId
                publishQueueSnapshots()
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
        synthEpoch.incrementAndGet()
        fillJob?.cancel()
        fillJob = null
        readyQueue.clear()
        queueTail = null
        queuePackId = null
        reservedKeys.clear()
        publishQueueSnapshots()
    }

    private fun publishQueueSnapshots() {
        reservedSnapshot = reservedKeys.toSet()
        headKeySnapshot = readyQueue.peekFirst()?.key
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

    private fun launchStatusTicker(@Suppress("UNUSED_PARAMETER") isFirst: Boolean): Job {
        // After speech has started, never flash another wait. Before that,
        // keep the single "Starting…" from Listen tap — no second label, no timer.
        if (!storyStarted && _state.value.status.isNullOrBlank()) {
            _state.value = _state.value.copy(status = "Starting…")
        }
        return Job().apply { complete() }
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
        /** Lookahead depth: keep this many synthesized chunks ready ahead of play. */
        const val QUEUE_DEPTH = 4
        /** After a starve, fill aims to have this many extra chunks ready. */
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

        fun shouldPreserveLookahead(
            packMatches: Boolean,
            firstKey: String?,
            queueHeadKey: String?,
            reservedKeys: Set<String>
        ): Boolean {
            if (!packMatches || firstKey.isNullOrEmpty()) return false
            if (queueHeadKey == firstKey) return true
            return firstKey in reservedKeys
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
