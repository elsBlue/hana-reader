package com.hana.reader.data

data class Chapter(
    val id: String,
    val title: String,
    val body: String
)

data class Book(
    val id: String,
    val title: String,
    val author: String,
    val language: String,
    val blurb: String,
    val paper: Long,
    val ink: Long,
    val chapters: List<Chapter>,
    /** Absolute path to a cached cover image (Reader / mini-player only). Never used as Library list art. */
    val coverPath: String? = null
)

data class ReadingProgress(
    val bookId: String,
    val chapterIndex: Int,
    val sentenceIndex: Int,
    val updatedAt: Long
)

data class Session(
    val email: String?,
    val photoUrl: String?,
    val localOnly: Boolean
)
