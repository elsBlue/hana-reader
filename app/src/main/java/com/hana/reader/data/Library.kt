package com.hana.reader.data

/**
 * Built-in catalog removed in v1.5.6 — library starts empty.
 * Users add books via + (EPUB / TXT / Markdown). Former sample IDs are
 * retained only so ProgressStore can purge leftover progress on upgrade.
 */
object Library {
    /** Former built-in book ids (no longer shown or openable). */
    val formerBuiltInIds: Set<String> = setOf(
        "listen-when-you-cannot-read",
        "membaca-yang-tidak-menarik",
        "alice-rabbit-hole",
        "senja-di-halte"
    )

    val books: List<Book> = emptyList()

    fun get(id: String): Book? = books.find { it.id == id }
}
