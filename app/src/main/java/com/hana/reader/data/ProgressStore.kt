package com.hana.reader.data

import android.content.Context
import org.json.JSONObject
import java.io.File

class ProgressStore(context: Context) {
    private val prefs = context.getSharedPreferences("hana", Context.MODE_PRIVATE)
    private val coversDir = File(context.filesDir, "covers").also { it.mkdirs() }

    init {
        purgeFormerBuiltIns()
    }

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

    fun clearProgress(bookId: String) {
        prefs.edit().remove("p_$bookId").apply()
    }

    fun coverFileFor(bookId: String): File = File(coversDir, "$bookId.cover")

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
        if (!book.coverPath.isNullOrBlank()) o.put("coverPath", book.coverPath)
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

    /** True when the book was user-imported (deletable / renamable). */
    fun isImported(bookId: String): Boolean = importedIds().contains(bookId)

    /**
     * Updates the display title of an imported book in prefs.
     * Keeps the same book id, chapters, cover path, and reading progress.
     */
    fun renameImported(bookId: String, newTitle: String): Boolean {
        if (!isImported(bookId)) return false
        val trimmed = newTitle.trim()
        if (trimmed.isEmpty()) return false
        val existing = importedBook(bookId) ?: return false
        if (existing.title == trimmed) return true
        addImported(existing.copy(title = trimmed))
        return true
    }

    /**
     * Removes an imported book, its progress, and any cached cover file.
     */
    fun removeImported(bookId: String): Boolean {
        if (!isImported(bookId)) return false
        val ids = importedIds().toMutableList()
        ids.remove(bookId)
        coverFileFor(bookId).delete()
        val existing = importedBook(bookId)
        existing?.coverPath?.let { path ->
            runCatching { File(path).takeIf { it.exists() && it.absolutePath != coverFileFor(bookId).absolutePath }?.delete() }
        }
        prefs.edit()
            .remove("b_$bookId")
            .remove("p_$bookId")
            .putString("imported_ids", ids.joinToString(","))
            .apply()
        return true
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
            val cover = o.optString("coverPath", "").ifBlank { null }
                ?: coverFileFor(id).takeIf { it.exists() }?.absolutePath
            Book(
                id = o.getString("id"),
                title = o.getString("title"),
                author = o.getString("author"),
                language = o.getString("language"),
                blurb = o.optString("blurb"),
                paper = o.optLong("paper", 0xFFD8CFC3),
                ink = o.optLong("ink", 0xFF2A241C),
                chapters = list,
                coverPath = cover
            )
        }.getOrNull()
    }

    /** Catalog is imports only — no built-in samples. */
    fun allBooks(): List<Book> = imported()

    fun book(id: String): Book? = importedBook(id)

    /**
     * One-shot: drop leftover progress (and any accidental import) for
     * former built-in sample ids so upgrades land on an empty library.
     */
    private fun purgeFormerBuiltIns() {
        if (prefs.getBoolean("purged_builtins_v156", false)) return
        val editor = prefs.edit()
        for (id in Library.formerBuiltInIds) {
            editor.remove("p_$id")
            if (isImported(id)) {
                // removeImported needs apply mid-loop; clear prefs keys here
                editor.remove("b_$id")
                coverFileFor(id).delete()
            }
        }
        val ids = importedIds().filterNot { it in Library.formerBuiltInIds }
        editor.putString("imported_ids", ids.joinToString(","))
        editor.putBoolean("purged_builtins_v156", true)
        editor.apply()
    }

    private fun importedIds(): List<String> =
        prefs.getString("imported_ids", "")
            .orEmpty()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
}
