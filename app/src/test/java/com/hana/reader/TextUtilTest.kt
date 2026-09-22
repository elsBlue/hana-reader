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
}
