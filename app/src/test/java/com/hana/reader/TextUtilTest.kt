package com.hana.reader

import com.hana.reader.data.TextUtil
import org.junit.Assert.assertEquals
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
        val (text, n) = TextUtil.speakChunk(sentences, 0, maxSentences = 3, maxChars = 500)
        assertEquals(3, n)
        assertEquals("One. Two. Three.", text)
    }

    @Test
    fun speakChunkFirstIsShort() {
        val sentences = listOf(
            "Hello there, this is a short opener.",
            "Then a longer second sentence keeps going for a while.",
            "And a third one as well."
        )
        val (text, n) = TextUtil.speakChunk(sentences, 0, maxSentences = 1, maxChars = 200)
        assertEquals(1, n)
        assertEquals("Hello there, this is a short opener.", text)
    }
}
