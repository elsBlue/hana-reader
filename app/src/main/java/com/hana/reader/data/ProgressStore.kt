package com.hana.reader.data

import android.content.Context
import org.json.JSONObject

class ProgressStore(context: Context) {
    private val prefs = context.getSharedPreferences("hana", Context.MODE_PRIVATE)

    fun session(): Session {
        return Session(
            email = prefs.getString("email", null),
            photoUrl = prefs.getString("photo", null),
            localOnly = prefs.getBoolean("local", false)
        )
    }

    fun signedIn(): Boolean {
        val s = session()
        return !s.email.isNullOrBlank() || s.localOnly
    }

    fun saveGoogle(email: String, photoUrl: String?): Boolean {
        return prefs.edit()
            .putString("email", email)
            .putString("photo", photoUrl)
            .putBoolean("local", false)
            .commit()
    }

    fun saveLocal(): Boolean {
        return prefs.edit().putBoolean("local", true).commit()
    }

    fun signOut(): Boolean {
        return prefs.edit().remove("email").remove("photo").putBoolean("local", false).commit()
    }

    fun get(bookId: String): ReadingProgress? {
        val raw = prefs.getString("p_$bookId", null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            ReadingProgress(
                bookId = bookId,
                chapterIndex = o.getInt("c"),
                sentenceIndex = o.getInt("s"),
                updatedAt = o.getLong("t")
            )
        }.getOrNull()
    }

    fun latest(): ReadingProgress? {
        val keys = prefs.all.keys.filter { it.startsWith("p_") }
        return keys.mapNotNull { get(it.removePrefix("p_")) }.maxByOrNull { it.updatedAt }
    }

    fun save(progress: ReadingProgress) {
        val o = JSONObject()
            .put("c", progress.chapterIndex)
            .put("s", progress.sentenceIndex)
            .put("t", progress.updatedAt)
        prefs.edit().putString("p_${progress.bookId}", o.toString()).apply()
    }

    fun addImported(book: Book) {
        val ids = importedIds().toMutableList()
        if (!ids.contains(book.id)) ids.add(book.id)
        val o = JSONObject()
            .put("id", book.id)
            .put("title", book.title)
            .put("author", book.author)
            .put("language", book.language)
            .put("blurb", book.blurb)
            .put("paper", book.paper)
            .put("ink", book.ink)
        val chapters = org.json.JSONArray()
        book.chapters.forEach { ch ->
            chapters.put(
                JSONObject().put("id", ch.id).put("title", ch.title).put("body", ch.body)
            )
        }
        o.put("chapters", chapters)
        prefs.edit()
            .putString("b_${book.id}", o.toString())
            .putString("imported_ids", ids.joinToString(","))
            .apply()
    }

    fun imported(): List<Book> = importedIds().mapNotNull { importedBook(it) }

    fun importedBook(id: String): Book? {
        val raw = prefs.getString("b_$id", null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            val chapters = o.getJSONArray("chapters")
            val list = buildList {
                for (i in 0 until chapters.length()) {
                    val c = chapters.getJSONObject(i)
                    add(Chapter(c.getString("id"), c.getString("title"), c.getString("body")))
                }
            }
            Book(
                id = o.getString("id"),
                title = o.getString("title"),
                author = o.getString("author"),
                language = o.getString("language"),
                blurb = o.optString("blurb"),
                paper = o.optLong("paper", 0xFFD8CFC3),
                ink = o.optLong("ink", 0xFF2A241C),
                chapters = list
            )
        }.getOrNull()
    }

    fun allBooks(): List<Book> = imported() + Library.books

    fun book(id: String): Book? = importedBook(id) ?: Library.get(id)

    private fun importedIds(): List<String> =
        prefs.getString("imported_ids", "")
            .orEmpty()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
}
