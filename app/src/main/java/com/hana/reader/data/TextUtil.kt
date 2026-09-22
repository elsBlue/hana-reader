package com.hana.reader.data

object TextUtil {
    fun normalizeForTts(text: String): String {
        var t = text
        t = t.replace('\u00a0', ' ')
        t = t.replace(Regex("[\\u201c\\u201d]"), "\"")
        t = t.replace(Regex("[\\u2018\\u2019]"), "'")
        t = t.replace("…", "...")
        t = t.replace(Regex("\\s+"), " ").trim()
        // Light expansions that help espeak/Kokoro cadence on ebook text
        t = t.replace(Regex("\\bMr\\."), "Mister")
        t = t.replace(Regex("\\bMrs\\."), "Missus")
        t = t.replace(Regex("\\bMs\\."), "Miss")
        t = t.replace(Regex("\\bDr\\."), "Doctor")
        t = t.replace(Regex("\\bSt\\."), "Saint")
        return t
    }

    fun splitSentences(text: String): List<String> {
        val cleaned = normalizeForTts(text)
        if (cleaned.isEmpty()) return emptyList()
        return cleaned
            .split(Regex("(?<=[.!?…])\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /**
     * Group sentences into a speaking unit so neural TTS keeps more natural prosody.
     * Prefers staying inside a soft paragraph window.
     */
    fun speakChunk(
        sentences: List<String>,
        start: Int,
        maxSentences: Int = 3,
        maxChars: Int = 500
    ): Pair<String, Int> {
        if (start !in sentences.indices) return "" to 0
        val parts = ArrayList<String>(maxSentences)
        var chars = 0
        var i = start
        while (i < sentences.size && parts.size < maxSentences) {
            val s = normalizeForTts(sentences[i])
            if (s.isEmpty()) {
                i++
                continue
            }
            if (parts.isNotEmpty() && chars + 1 + s.length > maxChars) break
            parts.add(s)
            chars += s.length + if (parts.size == 1) 0 else 1
            i++
        }
        return parts.joinToString(" ") to parts.size
    }
}
