package com.hana.reader.data

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Minimal EPUB helpers: chapter text, optional cover, and OPF title for import.
 */
object EpubImport {

    fun readTextChapters(input: InputStream): String {
        val chunks = mutableListOf<String>()
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val n = entry.name.lowercase()
                if (!entry.isDirectory && (n.endsWith(".xhtml") || n.endsWith(".html") || n.endsWith(".htm"))) {
                    val html = zip.readBytes().toString(Charsets.UTF_8)
                    val body = stripHtml(html)
                    if (body.length > 40) chunks.add(body)
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        if (chunks.isEmpty()) error("No readable chapters in that EPUB")
        return chunks.joinToString("\n\n")
    }

    /**
     * Extract cover image bytes from an EPUB stream, or null if none.
     * Pure enough for unit tests via [resolveCoverHref].
     */
    fun extractCoverBytes(input: InputStream): ByteArray? {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    // Cap individual entries so a huge media file cannot OOM import.
                    val bytes = zip.readBytes()
                    if (bytes.size <= 8_000_000) {
                        entries[entry.name.replace('\\', '/')] = bytes
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        val container = entries.entries
            .firstOrNull { it.key.equals("META-INF/container.xml", ignoreCase = true) }
            ?.value
            ?.toString(Charsets.UTF_8)
            ?: return fallbackCover(entries)
        val rootPath = Regex(
            """full-path\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).find(container)?.groupValues?.get(1)?.replace('\\', '/')
            ?: return fallbackCover(entries)
        val opfBytes = findEntry(entries, rootPath) ?: return fallbackCover(entries)
        val opf = opfBytes.toString(Charsets.UTF_8)
        val opfDir = rootPath.substringBeforeLast('/', missingDelimiterValue = "").let {
            if (it.isEmpty()) "" else "$it/"
        }
        val href = resolveCoverHref(opf) ?: return fallbackCover(entries)
        val resolved = normalizeZipPath(opfDir + href)
        return findEntry(entries, resolved) ?: fallbackCover(entries)
    }

    /** Persist cover bytes beside the book id; returns absolute path or null. */
    fun writeCoverFile(dest: File, bytes: ByteArray): String? {
        return runCatching {
            dest.parentFile?.mkdirs()
            dest.writeBytes(bytes)
            dest.absolutePath
        }.getOrNull()
    }


    /**
     * Best-effort dc:title from the OPF package document.
     * Returns null when metadata is missing or unreadable.
     */
    fun readMetadataTitle(bytes: ByteArray): String? =
        readMetadataTitle(ByteArrayInputStream(bytes))

    fun readMetadataTitle(input: InputStream): String? {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val data = zip.readBytes()
                    if (data.size <= 2_000_000) {
                        entries[entry.name.replace('\\', '/')] = data
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        val container = entries.entries
            .firstOrNull { it.key.equals("META-INF/container.xml", ignoreCase = true) }
            ?.value
            ?.toString(Charsets.UTF_8)
            ?: return titleFromAnyOpf(entries)
        val rootPath = Regex(
            """full-path\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).find(container)?.groupValues?.get(1)?.replace('\\', '/')
            ?: return titleFromAnyOpf(entries)
        val opfBytes = findEntry(entries, rootPath) ?: return titleFromAnyOpf(entries)
        return parseDcTitle(opfBytes.toString(Charsets.UTF_8)) ?: titleFromAnyOpf(entries)
    }

    private fun titleFromAnyOpf(entries: Map<String, ByteArray>): String? {
        for ((name, data) in entries) {
            if (name.lowercase().endsWith(".opf")) {
                parseDcTitle(data.toString(Charsets.UTF_8))?.let { return it }
            }
        }
        return null
    }

    /** Visible for unit tests. */
    fun parseDcTitle(opf: String): String? {
        val tagged = Regex(
            """<(?:dc:)?title\b[^>]*>([^<]+)</(?:dc:)?title>""",
            setOf(RegexOption.IGNORE_CASE)
        ).find(opf)?.groupValues?.get(1)
        val raw = tagged?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        return raw.takeIf { it.isNotBlank() }
    }

    /**
     * Resolve cover item href from OPF XML (meta cover id, cover-image property, or guide).
     */
    fun resolveCoverHref(opf: String): String? {
        val items = Regex(
            """<item\b([^>]+)>""",
            setOf(RegexOption.IGNORE_CASE)
        ).findAll(opf).map { it.groupValues[1] }.toList()

        fun attr(blob: String, name: String): String? =
            Regex("""$name\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(blob)?.groupValues?.get(1)

        val coverId = Regex(
            """<meta\b[^>]*name\s*=\s*["']cover["'][^>]*content\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).find(opf)?.groupValues?.get(1)
            ?: Regex(
                """<meta\b[^>]*content\s*=\s*["']([^"']+)["'][^>]*name\s*=\s*["']cover["']""",
                RegexOption.IGNORE_CASE
            ).find(opf)?.groupValues?.get(1)

        if (!coverId.isNullOrBlank()) {
            items.firstOrNull { attr(it, "id").equals(coverId, ignoreCase = true) }
                ?.let { attr(it, "href") }
                ?.let { return it }
        }

        items.firstOrNull { blob ->
            attr(blob, "properties")?.split(Regex("\\s+"))
                ?.any { it.equals("cover-image", ignoreCase = true) } == true
        }?.let { attr(it, "href") }?.let { return it }

        val guideHref = Regex(
            """<reference\b[^>]*type\s*=\s*["']cover["'][^>]*href\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).find(opf)?.groupValues?.get(1)
            ?: Regex(
                """<reference\b[^>]*href\s*=\s*["']([^"']+)["'][^>]*type\s*=\s*["']cover["']""",
                RegexOption.IGNORE_CASE
            ).find(opf)?.groupValues?.get(1)
        if (!guideHref.isNullOrBlank()) {
            // Guide may point at an HTML wrapper — prefer matching image item with same stem.
            val base = guideHref.substringAfterLast('/').substringBeforeLast('.')
            items.firstOrNull { blob ->
                val href = attr(blob, "href").orEmpty()
                val media = attr(blob, "media-type").orEmpty()
                media.startsWith("image/") && href.contains(base, ignoreCase = true)
            }?.let { attr(it, "href") }?.let { return it }
            if (guideHref.matches(Regex(""".*\.(jpe?g|png|gif|webp|svg)$""", RegexOption.IGNORE_CASE))) {
                return guideHref
            }
        }
        return null
    }

    private fun fallbackCover(entries: Map<String, ByteArray>): ByteArray? {
        val preferred = entries.entries.firstOrNull { (name, _) ->
            val n = name.lowercase()
            n.contains("cover") && n.matches(Regex(""".*\.(jpe?g|png|gif|webp)$"""))
        }
        if (preferred != null) return preferred.value
        return entries.entries.firstOrNull { (name, _) ->
            name.lowercase().matches(Regex(""".*\.(jpe?g|png)$"""))
        }?.value
    }

    private fun findEntry(entries: Map<String, ByteArray>, path: String): ByteArray? {
        val want = normalizeZipPath(path)
        entries[want]?.let { return it }
        return entries.entries.firstOrNull { it.key.equals(want, ignoreCase = true) }?.value
    }

    private fun normalizeZipPath(path: String): String {
        val parts = path.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        val out = ArrayDeque<String>()
        for (p in parts) {
            if (p == "..") {
                if (out.isNotEmpty()) out.removeLast()
            } else out.addLast(p)
        }
        return out.joinToString("/")
    }

    private fun stripHtml(html: String): String =
        html
            .replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n\n")
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("&nbsp;"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Re-open helper for callers that already consumed the document stream once. */
    fun extractCoverBytes(bytes: ByteArray): ByteArray? =
        extractCoverBytes(ByteArrayInputStream(bytes))

    fun readTextChapters(bytes: ByteArray): String =
        readTextChapters(ByteArrayInputStream(bytes))
}
