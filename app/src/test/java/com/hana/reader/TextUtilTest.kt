package com.hana.reader

import com.hana.reader.data.TextUtil
import org.junit.Assert.assertEquals
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
        // Comma must sit after minKeep (~cap/3) and before the 48-char cap.
        val text = "Short words grow here, then this long tail continues well past the forty eight character hard cap for sure."
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
    fun firstUtteranceMaxCharsIsFortyEight() {
        assertEquals(48, TextUtil.FIRST_UTTERANCE_MAX_CHARS)
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
            "first utterance must stay near 48-char cap, was ${chunk.text.length}",
            chunk.text.length <= TextUtil.FIRST_UTTERANCE_MAX_CHARS + 5
        )
        assertNotNull("long first sentence should leave a remainder", chunk.remainder)
    }
}
