package com.hana.reader.data

object TextUtil {
    fun splitSentences(text: String): List<String> {
        val cleaned = text.replace(Regex("\\s+"), " ").trim()
        if (cleaned.isEmpty()) return emptyList()
        return cleaned
            .split(Regex("(?<=[.!?…])\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /**
     * Group sentences into a speaking unit so neural TTS keeps more natural prosody.
     * Returns the joined text and how many sentences were consumed from [start].
     */
    fun speakChunk(
        sentences: List<String>,
        start: Int,
        maxSentences: Int = 3,
        maxChars: Int = 480
    ): Pair<String, Int> {
        if (start !in sentences.indices) return "" to 0
        val parts = ArrayList<String>(maxSentences)
        var chars = 0
        var i = start
        while (i < sentences.size && parts.size < maxSentences) {
            val s = sentences[i]
            if (parts.isNotEmpty() && chars + 1 + s.length > maxChars) break
            parts.add(s)
            chars += s.length + if (parts.size == 1) 0 else 1
            i++
        }
        return parts.joinToString(" ") to parts.size
    }
}
