package com.hana.reader.tts

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class ModelFiles(
    val kind: NeuralKind,
    val onnx: File,
    val tokens: File,
    val dataDir: File,
    val voices: File?
)

class TtsModelManager(context: Context) {
    private val root = File(context.applicationContext.filesDir, "tts")
    private val mutex = Mutex()
    @Volatile var lastError: String? = null
        private set

    fun isReady(language: String): Boolean {
        val pack = TtsPacks.forLanguage(language) ?: return false
        return readyMatches(language, pack) && findFiles(langDir(language), pack.kind) != null
    }

    fun files(language: String): ModelFiles? {
        val pack = TtsPacks.forLanguage(language) ?: return null
        if (!readyMatches(language, pack)) return null
        return findFiles(langDir(language), pack.kind)
    }

    suspend fun ensure(language: String, onProgress: (Float) -> Unit): ModelFiles {
        mutex.withLock {
            files(language)?.let { return it }
            val pack = TtsPacks.forLanguage(language)
                ?: error("No neural voice for $language")
            lastError = null
            val dir = langDir(language)
            dir.deleteRecursively()
            dir.mkdirs()
            val archive = File(dir, pack.archiveName)
            try {
                download(pack.url, archive, pack.minArchiveBytes, onProgress)
                extractTarBz2(archive, dir)
                archive.delete()
                val found = findFiles(dir, pack.kind)
                    ?: error("Voice pack extracted but files were missing")
                File(dir, READY).writeText(pack.packId)
                onProgress(1f)
                return found
            } catch (t: Throwable) {
                archive.delete()
                lastError = t.message ?: "Download failed"
                throw t
            }
        }
    }

    private fun readyMatches(language: String, pack: TtsPack): Boolean {
        val ready = File(langDir(language), READY)
        if (!ready.isFile) return false
        return ready.readText().trim() == pack.packId
    }

    fun deletePack(language: String) {
        langDir(language).deleteRecursively()
    }

    fun installedBytes(language: String): Long {
        val dir = langDir(language)
        if (!dir.isDirectory) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun langDir(language: String) = File(root, language)

    private fun download(url: String, dest: File, minBytes: Long, onProgress: (Float) -> Unit) {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".part")
        tmp.delete()
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 30_000
            readTimeout = 120_000
            setRequestProperty("User-Agent", "HanaReader/1.2")
        }
        conn.inputStream.use { input ->
            val total = conn.contentLengthLong.coerceAtLeast(1L)
            FileOutputStream(tmp).use { out ->
                val buf = ByteArray(64 * 1024)
                var copied = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    copied += n
                    onProgress((copied.toFloat() / total).coerceIn(0f, 0.95f))
                }
            }
        }
        if (tmp.length() < minBytes) {
            tmp.delete()
            error("Voice download was incomplete")
        }
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
    }

    private fun extractTarBz2(archive: File, dest: File) {
        dest.mkdirs()
        archive.inputStream().buffered().use { raw ->
            BZip2CompressorInputStream(raw).use { bz ->
                TarArchiveInputStream(bz).use { tar ->
                    while (true) {
                        val entry = tar.nextEntry ?: break
                        if (entry.isDirectory) continue
                        val rel = TtsPacks.safeTarRelative(entry.name) ?: continue
                        val out = File(dest, rel)
                        val canonical = out.canonicalFile
                        if (!canonical.path.startsWith(dest.canonicalFile.path + File.separator) &&
                            canonical.path != dest.canonicalFile.path
                        ) continue
                        canonical.parentFile?.mkdirs()
                        FileOutputStream(canonical).use { tar.copyTo(it) }
                    }
                }
            }
        }
    }

    companion object {
        private const val READY = ".ready"

        fun findFiles(dir: File, kind: NeuralKind): ModelFiles? {
            if (!dir.isDirectory) return null
            val files = dir.walkTopDown().filter { it.isFile }.toList()
            // Prefer full-precision ONNX over int8 (better quality on ARM).
            val onnx = files
                .filter { it.name.endsWith(".onnx") }
                .sortedBy { if (it.name.contains("int8")) 1 else 0 }
                .firstOrNull() ?: return null
            val tokens = files.firstOrNull { it.name == "tokens.txt" } ?: return null
            val dataDir = dir.walkTopDown().firstOrNull { it.isDirectory && it.name == "espeak-ng-data" }
                ?: return null
            val voices = files.firstOrNull { it.name == "voices.bin" }
            if (kind == NeuralKind.Kokoro && voices == null) return null
            return ModelFiles(kind, onnx, tokens, dataDir, voices)
        }
    }
}
