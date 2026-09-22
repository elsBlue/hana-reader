package com.hana.reader.data

object TextUtil {
    fun splitSentences(text: String): List<String> {
        val cleaned = text.replace(Regex("\\s+"), " ").trim()
        if (cleaned.isEmpty()) return emptyList()
        return cleaned
            .split(Regex("(?<=[.!?\u2026])\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }
}
