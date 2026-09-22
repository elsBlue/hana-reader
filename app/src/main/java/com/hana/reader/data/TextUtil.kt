package com.hana.reader.data

object TextUtil {
    /** Hard cap for the first audible neural chunk (faster TTFA). */
    const val FIRST_UTTERANCE_MAX_CHARS = 48

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
     * Split [text] so the returned prefix is at most [maxChars].
     * Prefers clause punctuation (, ; : — –) or the last whitespace before the cap.
     * Second value is the remainder (null if fully consumed). Never drops text.
     */
    fun hardCapUtterance(text: String, maxChars: Int = FIRST_UTTERANCE_MAX_CHARS): Pair<String, String?> {
        val t = normalizeForTts(text)
        if (t.isEmpty()) return "" to null
        if (t.length <= maxChars) return t to null

        val window = t.substring(0, maxChars)
        val minKeep = (maxChars / 3).coerceAtLeast(1)

        var splitAt = -1
        for (i in window.indices.reversed()) {
            val c = window[i]
            if (c == ',' || c == ';' || c == ':' || c == '—' || c == '–') {
                if (i + 1 >= minKeep) {
                    splitAt = i + 1
                    break
                }
            }
        }
        if (splitAt < 0) {
            val ws = window.lastIndexOf(' ')
            splitAt = if (ws >= minKeep) ws else maxChars
        }

        var prefix = t.substring(0, splitAt).trim()
        var rest = t.substring(splitAt).trim()
        if (prefix.isEmpty()) {
            prefix = t.take(maxChars).trim()
            rest = t.drop(maxChars).trim()
        }
        return prefix to rest.ifEmpty { null }
    }

    data class SpeakChunkResult(
        val text: String,
        /** How many sentences from [start] were fully consumed (0 if only a prefix). */
        val consumed: Int,
        /** Leftover of the current sentence when hard-capped; feed back on the next call. */
        val remainder: String? = null
    )

    /**
     * Group sentences into a speaking unit so neural TTS keeps more natural prosody.
     * When the first sentence of a chunk exceeds [maxChars], hard-caps it and returns
     * [SpeakChunkResult.remainder] so the caller can continue without losing text.
     *
     * @param isFirst when true, use a single short utterance (caller should pass
     *   [FIRST_UTTERANCE_MAX_CHARS] as [maxChars]).
     */
    fun speakChunk(
        sentences: List<String>,
        start: Int,
        maxSentences: Int = 3,
        maxChars: Int = 500,
        isFirst: Boolean = false
    ): SpeakChunkResult {
        if (start !in sentences.indices) return SpeakChunkResult("", 0)

        val sentenceLimit = if (isFirst) 1 else maxSentences
        val charLimit = if (isFirst) {
            FIRST_UTTERANCE_MAX_CHARS.coerceAtMost(maxChars)
        } else {
            maxChars
        }

        val parts = ArrayList<String>(sentenceLimit)
        var chars = 0
        var i = start
        while (i < sentences.size && parts.size < sentenceLimit) {
            val s = normalizeForTts(sentences[i])
            if (s.isEmpty()) {
                i++
                continue
            }
            // Enforce char cap on the first unit of this chunk (TTFA / long sentences).
            if (parts.isEmpty() && s.length > charLimit) {
                val (prefix, rest) = hardCapUtterance(s, charLimit)
                return SpeakChunkResult(prefix, consumed = 0, remainder = rest)
            }
            if (parts.isNotEmpty() && chars + 1 + s.length > charLimit) break
            parts.add(s)
            chars += s.length + if (parts.size == 1) 0 else 1
            i++
        }
        return SpeakChunkResult(parts.joinToString(" "), parts.size, remainder = null)
    }
}
