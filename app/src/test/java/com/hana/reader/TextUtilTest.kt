package com.hana.reader

import com.hana.reader.data.TextUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextUtilTest {
    @Test
    fun splitsEnglishSentences() {
        val parts = TextUtil.splitSentences("Hello there. How are you? Fine.")
        assertEquals(listOf("Hello there.", "How are you?", "Fine."), parts)
    }

    @Test
    fun normalizeExpandsTitles() {
        assertEquals("Mister Smith went home.", TextUtil.normalizeForTts("Mr. Smith went home."))
    }

    @Test
    fun speakChunkGroupsSentences() {
        val sentences = listOf("One.", "Two.", "Three.", "Four.")
        val chunk = TextUtil.speakChunk(sentences, 0, maxSentences = 3, maxChars = 500)
        assertEquals(3, chunk.consumed)
        assertEquals("One. Two. Three.", chunk.text)
        assertNull(chunk.remainder)
    }

    @Test
    fun speakChunkFirstIsShort() {
        val sentences = listOf(
            "Hello there, this is a short opener.",
            "Then a longer second sentence keeps going for a while.",
            "And a third one as well."
        )
        val chunk = TextUtil.speakChunk(
            sentences,
            0,
            maxSentences = 1,
            maxChars = TextUtil.FIRST_UTTERANCE_MAX_CHARS,
            isFirst = true
        )
        assertEquals(1, chunk.consumed)
        assertEquals("Hello there, this is a short opener.", chunk.text)
        assertNull(chunk.remainder)
    }

    @Test
    fun firstUtteranceHardCapsLongSentenceWithoutLoss() {
        // Build a 400+ char first sentence with clause breaks.
        val clauseA = "In the beginning of this rather lengthy chapter the narrator explains the setting with unusual care and patience"
        val clauseB = "while the hero waits beside the old oak tree near the quiet river bank at dawn watching mist rise slowly"
        val clauseC = "and every detail of the valley below is described at a pace that refuses to hurry toward any period mark for a very long time indeed until the reader nearly forgets there will be an ending"
        val clauseD = "so the sentence keeps accumulating clauses about weather stones birds and distant bells ringing across fields"
        val longSentence = "$clauseA, $clauseB; $clauseC: $clauseD."
        assertTrue("fixture should exceed 400 chars", longSentence.length > 400)

        val sentences = listOf(longSentence, "Short closer.")
        val pieces = ArrayList<String>()
        var remainder: String? = null
        var start = 0
        var isFirst = true
        var guard = 0
        while (start < sentences.size && guard++ < 20) {
            val effective = if (remainder != null) {
                sentences.toMutableList().also { it[start] = remainder!! }
            } else {
                sentences
            }
            val maxChars = if (isFirst) TextUtil.FIRST_UTTERANCE_MAX_CHARS else com.hana.reader.tts.HanaPlayer.EN_LATER_MAX_CHARS
            val chunk = TextUtil.speakChunk(
                effective,
                start,
                maxSentences = if (isFirst) 1 else com.hana.reader.tts.HanaPlayer.EN_LATER_MAX_SENTENCES,
                maxChars = maxChars,
                isFirst = isFirst
            )
            isFirst = false
            assertTrue(chunk.text.isNotBlank())
            assertTrue(
                "chunk must not end mid-word: '${chunk.text.takeLast(12)}'",
                TextUtil.endsAtWordBoundary(chunk.text, effective[start])
            )
            if (chunk.remainder != null) {
                assertTrue(
                    "first/hard-capped piece must stay near cap",
                    chunk.text.length <= TextUtil.FIRST_UTTERANCE_MAX_CHARS + 5 || !pieces.isEmpty()
                )
            }
            pieces.add(chunk.text)
            remainder = chunk.remainder
            if (remainder == null) {
                start += chunk.consumed.coerceAtLeast(1)
            }
        }

        val first = pieces.first()
        assertTrue(
            "first chunk must hard-cap near ${TextUtil.FIRST_UTTERANCE_MAX_CHARS}, was ${first.length}",
            first.length <= TextUtil.FIRST_UTTERANCE_MAX_CHARS + 5
        )
        assertTrue("expected multiple chunks for long sentence", pieces.size >= 2)

        val rejoined = pieces.joinToString(" ")
        val normalizedOriginal = TextUtil.normalizeForTts(longSentence + " Short closer.")
        val compactRejoined = rejoined.replace(Regex("\\s+"), " ").trim()
        val compactOriginal = normalizedOriginal.replace(Regex("\\s+"), " ").trim()
        // All original words must appear in order (allowing split-boundary whitespace).
        assertEquals(compactOriginal, compactRejoined)
    }

    @Test
    fun hardCapPrefersClausePunctuation() {
        // Comma must sit after minKeep (~cap/3) and before the 64-char cap.
        val text = "Short words grow here, then this long tail continues well past the sixty four character hard cap for sure and more."
        assertTrue(text.length > TextUtil.FIRST_UTTERANCE_MAX_CHARS)
        val commaAt = text.indexOf(',')
        assertTrue("comma should be after minKeep", commaAt >= TextUtil.FIRST_UTTERANCE_MAX_CHARS / 3)
        assertTrue("comma should be before hard cap", commaAt < TextUtil.FIRST_UTTERANCE_MAX_CHARS)
        val (prefix, rest) = TextUtil.hardCapUtterance(text, TextUtil.FIRST_UTTERANCE_MAX_CHARS)
        assertTrue(prefix.length <= TextUtil.FIRST_UTTERANCE_MAX_CHARS)
        assertNotNull(rest)
        assertTrue("expected clause split at comma", prefix.trimEnd().endsWith(","))
        assertEquals(
            TextUtil.normalizeForTts(text),
            TextUtil.normalizeForTts("$prefix ${rest!!}")
        )
    }

    @Test
    fun hardCapNeverSplitsMidWordForEbookSentences() {
        val samples = listOf(
            "The boat began to drift along the quiet canal at sunrise.",
            "She could only listen while the distant bells kept ringing.",
            "A purple haze settled over the fields beyond the village.",
            "In the beginning of this rather lengthy chapter the narrator explains the setting with unusual care and patience while the hero waits.",
            "Words without commas or breaks just keep going and going past sixty four characters into the next clause of the tale."
        )
        for (sentence in samples) {
            assertTrue(sentence.length > 20)
            val (prefix, rest) = TextUtil.hardCapUtterance(sentence, TextUtil.EN_CHUNK_MAX_CHARS)
            assertTrue(prefix.isNotBlank())
            assertTrue(
                "must not end mid-word like 'drif'/'li'/'pur': '$prefix'",
                TextUtil.endsAtWordBoundary(prefix, sentence)
            )
            // Classic mid-word truncations from the screen recording
            assertFalse("must not end with truncated 'drif'", prefix.endsWith("drif"))
            assertFalse("must not end with truncated 'pur'", Regex("\\bpur$").containsMatchIn(prefix))
            if (rest != null) {
                assertEquals(
                    TextUtil.normalizeForTts(sentence),
                    TextUtil.normalizeForTts("$prefix $rest")
                )
                // Rest continuing a letter after a letter ⇒ mid-word (should not happen)
                if (prefix.last().isLetter() && rest.first().isLetter()) {
                    val orig = TextUtil.normalizeForTts(sentence)
                    val idx = prefix.length
                    assertTrue(
                        "gap between prefix and rest should be whitespace in original",
                        idx < orig.length && orig[idx].isWhitespace()
                    )
                }
            }
        }
    }

    @Test
    fun hardCapLastResortOnlyForSingleLongToken() {
        val token = "a".repeat(80)
        val (prefix, rest) = TextUtil.hardCapUtterance(token, 64)
        assertEquals(64, prefix.length)
        assertNotNull(rest)
        assertEquals(token, prefix + rest)
    }

    @Test
    fun enChunkMaxCharsIsSixtyFour() {
        assertEquals(64, TextUtil.EN_CHUNK_MAX_CHARS)
        assertEquals(64, TextUtil.FIRST_UTTERANCE_MAX_CHARS)
        assertEquals(64, com.hana.reader.tts.HanaPlayer.EN_LATER_MAX_CHARS)
        assertEquals(1, com.hana.reader.tts.HanaPlayer.EN_LATER_MAX_SENTENCES)
        assertEquals(4, com.hana.reader.tts.HanaPlayer.QUEUE_DEPTH)
        assertEquals(2, com.hana.reader.tts.HanaPlayer.PLAY_RESUME_DEPTH)
    }

    @Test
    fun firstUtterancePrefetchKeyMatchesSpeakChunkPlan() {
        val sentences = listOf(
            "In the quiet valley the mist rose slowly over stones and distant bells,",
            "Then a short closer."
        )
        // Plan at chapter 0 / sentence 0 — same ingredients speakNeural looks up.
        val planned = com.hana.reader.tts.HanaPlayer.planFirstUtterance(0, 0, sentences)
        assertNotNull(planned)
        val chunk = TextUtil.speakChunk(
            sentences,
            0,
            maxSentences = 1,
            maxChars = TextUtil.FIRST_UTTERANCE_MAX_CHARS,
            isFirst = true
        )
        assertEquals(chunk.text, planned!!.chunk.text)
        assertEquals(chunk.consumed, planned.chunk.consumed)
        assertEquals(chunk.remainder, planned.chunk.remainder)
        val expectedKey = com.hana.reader.tts.HanaPlayer.utterancePrefetchKey(
            chapterIndex = 0,
            sentenceIndex = 0,
            consumed = chunk.consumed,
            hasRemainder = chunk.remainder != null,
            textLength = chunk.text.length
        )
        assertEquals(expectedKey, planned.key)
        assertTrue(
            "first utterance must stay near ${TextUtil.FIRST_UTTERANCE_MAX_CHARS}-char cap, was ${chunk.text.length}",
            chunk.text.length <= TextUtil.FIRST_UTTERANCE_MAX_CHARS + 5
        )
        assertNotNull("long first sentence should leave a remainder", chunk.remainder)
    }

    @Test
    fun lookaheadPlanKeepsDepthAndStableKeys() {
        val chapter = listOf(
            "The boat began to drift along the quiet canal at sunrise while birds called.",
            "She could only listen while the distant bells kept ringing across the valley.",
            "A purple haze settled over the fields beyond the village green."
        )
        val planned = com.hana.reader.tts.HanaPlayer.planLookahead(
            chapterSentences = listOf(chapter),
            chapterIndex = 0,
            sentenceIndex = 0,
            remainder = null,
            language = "en",
            isFirst = true,
            count = 3
        )
        assertTrue("expected depth >= 2, got ${planned.size}", planned.size >= 2)
        // Keys must be unique and rebuild-stable
        val keys = planned.map { it.key }
        assertEquals(keys.toSet().size, keys.size)
        for (p in planned) {
            assertTrue(p.text.length <= TextUtil.EN_CHUNK_MAX_CHARS + 5)
            assertTrue(
                "lookahead chunk must not end mid-word: '${p.text.takeLast(16)}'",
                p.text.last().isWhitespace() ||
                    p.text.last() in ".,;:!?—–" ||
                    !p.text.last().isLetterOrDigit() ||
                    p.remainder == null ||
                    TextUtil.endsAtWordBoundary(p.text, p.text + " " + (p.remainder ?: ""))
            )
        }
        // Re-plan from same cursor → identical first key (prefetch stability)
        val again = com.hana.reader.tts.HanaPlayer.planLookahead(
            chapterSentences = listOf(chapter),
            chapterIndex = 0,
            sentenceIndex = 0,
            remainder = null,
            language = "en",
            isFirst = true,
            count = 1
        )
        assertEquals(planned.first().key, again.first().key)
        assertEquals(planned.first().text, again.first().text)
    }

    @Test
    fun firstAndLaterEnChunksShareBudget() {
        val long = "Words without commas just keep going past the continuous english budget into another phrase of the story here."
        val first = TextUtil.speakChunk(listOf(long), 0, maxSentences = 1, maxChars = TextUtil.EN_CHUNK_MAX_CHARS, isFirst = true)
        val later = TextUtil.speakChunk(listOf(long), 0, maxSentences = 1, maxChars = TextUtil.EN_CHUNK_MAX_CHARS, isFirst = false)
        assertTrue(first.text.length <= TextUtil.EN_CHUNK_MAX_CHARS + 5)
        assertTrue(later.text.length <= TextUtil.EN_CHUNK_MAX_CHARS + 5)
        // Same budget → same hard-cap for a single long sentence
        assertEquals(first.text, later.text)
    }

    @Test
    fun piperEnChunksStayAheadOfPlayback() {
        val first = com.hana.reader.tts.HanaPlayer.chunkLimits(
            "en", true, com.hana.reader.tts.NeuralKind.Piper
        )
        val later = com.hana.reader.tts.HanaPlayer.chunkLimits(
            "en", false, com.hana.reader.tts.NeuralKind.Piper
        )
        assertEquals(1, first.first)
        assertEquals(120, first.second)
        assertEquals(2, later.first)
        assertEquals(280, later.second)
    }

    @Test
    fun preserveLookaheadWhenFirstLineIsQueuedOrInFlight() {
        val key = com.hana.reader.tts.HanaPlayer.utterancePrefetchKey(0, 0, 1, false, 40)
        assertTrue(
            com.hana.reader.tts.HanaPlayer.shouldPreserveLookahead(
                packMatches = true,
                firstKey = key,
                queueHeadKey = key,
                reservedKeys = emptySet()
            )
        )
        assertTrue(
            "Listen must not discard a first line already synthesizing",
            com.hana.reader.tts.HanaPlayer.shouldPreserveLookahead(
                packMatches = true,
                firstKey = key,
                queueHeadKey = null,
                reservedKeys = setOf(key)
            )
        )
        assertFalse(
            com.hana.reader.tts.HanaPlayer.shouldPreserveLookahead(
                packMatches = true,
                firstKey = key,
                queueHeadKey = null,
                reservedKeys = emptySet()
            )
        )
        assertFalse(
            com.hana.reader.tts.HanaPlayer.shouldPreserveLookahead(
                packMatches = false,
                firstKey = key,
                queueHeadKey = key,
                reservedKeys = setOf(key)
            )
        )
    }
}
