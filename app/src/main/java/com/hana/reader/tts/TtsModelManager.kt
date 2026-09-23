package com.hana.reader.tts

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

sealed class TtsDownloadState {
    data object Idle : TtsDownloadState()
    data class Downloading(
        val language: String,
        val progress: Float,
        val stage: String
    ) : TtsDownloadState()
    data class Failed(val language: String, val message: String) : TtsDownloadState()
    data class Ready(val language: String) : TtsDownloadState()
}

/**
 * Pack download manager. Downloads run on an app-lifetime [scope] so leaving
 * VoicesScreen does not cancel work. Concurrent [ensure] for the same language
 * awaits the same in-flight job (gate mutex is only held to register the job,
 * never around the blocking HTTP download).
 */
class TtsModelManager(
    context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "tts")
    private val mainHandler = Handler(Looper.getMainLooper())
    private val gate = Mutex()
    private val inFlight = mutableMapOf<String, Job>()
    private val outcomes = mutableMapOf<String, Result<ModelFiles>>()

    @Volatile var lastError: String? = null
        private set

    private val _downloadState = MutableStateFlow<TtsDownloadState>(TtsDownloadState.Idle)
    val downloadState: StateFlow<TtsDownloadState> = _downloadState.asStateFlow()

    init {
        purgeRetiredKokoro()
        purgeRetiredWarm()
    }

    fun isReady(language: String): Boolean {
        val pack = TtsPacks.forLanguage(language) ?: return false
        return readyMatches(language, pack) && findFiles(langDir(language), pack.kind, preferInt8 = preferLowRamInt8()) != null
    }

    /** Leftover extract/partial without a matching .ready marker. */
    fun isIncomplete(language: String): Boolean {
        val dir = langDir(language)
        if (!dir.isDirectory) return false
        if (isReady(language)) return false
        return dir.walkTopDown().any { it.isFile && it.name != READY }
    }

    fun files(language: String): ModelFiles? {
        val pack = TtsPacks.forLanguage(language) ?: return null
        if (!readyMatches(language, pack)) return null
        return findFiles(langDir(language), pack.kind, preferInt8 = preferLowRamInt8())
    }

    fun isDownloading(language: String): Boolean {
        val s = _downloadState.value
        return s is TtsDownloadState.Downloading && s.language == language
    }

    /**
     * Ensure the pack is on disk. Survives leaving the UI; if a download for
     * [language] is already running, waits for that job.
     */
    suspend fun ensure(language: String, onProgress: ((Float) -> Unit)? = null): ModelFiles {
        files(language)?.let {
            publish(TtsDownloadState.Ready(language))
            return it
        }
        val pack = TtsPacks.forLanguage(language)
            ?: error("No neural voice for $language")

        val job = gate.withLock {
            files(language)?.let { return it }
            val existing = inFlight[language]
            if (existing != null && existing.isActive) {
                existing
            } else {
                outcomes.remove(language)
                scope.launch {
                    runDownload(pack, language, onProgress)
                }.also { inFlight[language] = it }
            }
        }

        job.join()

        gate.withLock {
            val result = outcomes[language]
            if (result != null) return result.getOrThrow()
        }
        files(language)?.let { return it }
        error(lastError ?: "Download failed")
    }

    /** Fire-and-forget download that survives screen teardown. */
    fun startDownload(language: String) {
        if (isReady(language)) return
        scope.launch {
            runCatching { ensure(language) }
        }
    }

    private suspend fun runDownload(
        pack: TtsPack,
        language: String,
        onProgress: ((Float) -> Unit)?
    ) {
        try {
            lastError = null
            publish(TtsDownloadState.Downloading(language, 0f, "Connecting…"))
            val found = downloadAndExtract(pack, language) { p, stage ->
                publish(TtsDownloadState.Downloading(language, p, stage))
                onProgress?.invoke(p)
            }
            gate.withLock { outcomes[language] = Result.success(found) }
            publish(TtsDownloadState.Ready(language))
        } catch (t: Throwable) {
            val msg = t.message ?: "Download failed"
            lastError = msg
            gate.withLock { outcomes[language] = Result.failure(t) }
            publish(TtsDownloadState.Failed(language, msg))
        } finally {
            gate.withLock { inFlight.remove(language) }
        }
    }

    private fun downloadAndExtract(
        pack: TtsPack,
        language: String,
        onProgress: (Float, String) -> Unit
    ): ModelFiles {
        val finalDir = langDir(language)
        val staging = File(root, "$language.staging")
        staging.deleteRecursively()
        staging.mkdirs()
        val archive = File(staging, pack.archiveName)
        try {
            onProgress(0f, "Connecting…")
            download(pack.url, archive, pack.minArchiveBytes) { p ->
                onProgress(p, "Downloading… ${(p * 100).toInt()}%")
            }
            onProgress(0.96f, "Extracting…")
            extractTarBz2(archive, staging)
            archive.delete()
            val preferInt8 = preferLowRamInt8()
            val found = findFiles(staging, pack.kind, preferInt8 = preferInt8)
                ?: error("Voice pack extracted but files were missing")
            validatePackFiles(found)
            File(staging, READY).writeText(pack.packId)
            // Atomic swap: live dir replaced only after a validated staging tree.
            if (finalDir.exists()) finalDir.deleteRecursively()
            if (!staging.renameTo(finalDir)) {
                staging.copyRecursively(finalDir, overwrite = true)
                staging.deleteRecursively()
            }
            onProgress(1f, "Ready")
            return findFiles(finalDir, pack.kind, preferInt8 = preferInt8)
                ?: error("Voice pack missing after install")
        } catch (t: Throwable) {
            archive.delete()
            staging.deleteRecursively()
            throw t
        }
    }

    private fun preferLowRamInt8(): Boolean {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        return am.isLowRamDevice || am.memoryClass < 192
    }

    private fun validatePackFiles(files: ModelFiles) {
        if (!files.onnx.isFile || files.onnx.length() < 1_000_000L) {
            error("ONNX model missing or too small")
        }
        files.onnx.inputStream().use { input ->
            val hdr = ByteArray(4)
            if (input.read(hdr) < 4) error("ONNX model unreadable")
        }
        if (files.tokens.readText().isBlank()) error("tokens.txt is empty")
        if (!File(files.dataDir, "phontab").isFile || !File(files.dataDir, "phonindex").isFile) {
            error("espeak-ng-data missing phontab/phonindex")
        }
    }

    private fun publish(state: TtsDownloadState) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            _downloadState.value = state
        } else {
            mainHandler.post { _downloadState.value = state }
        }
    }

    private fun readyMatches(language: String, pack: TtsPack): Boolean {
        val ready = File(langDir(language), READY)
        if (!ready.isFile) return false
        return ready.readText().trim() == pack.packId
    }

    fun deletePack(language: String) {
        scope.launch {
            val job = gate.withLock { inFlight[language] }
            job?.cancel()
            job?.join()
            gate.withLock {
                inFlight.remove(language)
                outcomes.remove(language)
                langDir(language).deleteRecursively()
                File(root, "$language.staging").deleteRecursively()
            }
            publish(TtsDownloadState.Idle)
        }
    }

    fun installedBytes(language: String): Long {
        val dir = langDir(language)
        if (!dir.isDirectory) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun langDir(language: String) = File(root, language)

    /** Drop the old ~300 MB Kokoro folder so Listen does not keep dead weight. */
    private fun purgeRetiredKokoro() {
        val dir = File(root, TtsPacks.RETIRED_KOKORO_STORAGE_KEY)
        val ready = File(dir, READY)
        val marker = if (ready.isFile) ready.readText().trim() else ""
        val looksRetired = marker.contains("kokoro") ||
            dir.walkTopDown().any { it.isFile && it.name == "voices.bin" }
        if (dir.isDirectory && looksRetired) {
            dir.deleteRecursively()
        }
    }

    /** Drop retired Warm (Amy) pack folder — Smooth is the only offline EN neural. */
    private fun purgeRetiredWarm() {
        val dir = File(root, TtsPacks.RETIRED_WARM_STORAGE_KEY)
        if (dir.isDirectory) {
            dir.deleteRecursively()
        }
    }

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
        try {
            conn.connect()
        } catch (t: Throwable) {
            error("Connect timeout or network error: ${t.message ?: "failed"}")
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

        fun findFiles(dir: File, kind: NeuralKind, preferInt8: Boolean = false): ModelFiles? {
            if (!dir.isDirectory) return null
            val files = dir.walkTopDown().filter { it.isFile }.toList()
            // Default: fp32. On low-RAM, prefer int8 when the pack includes it.
            val onnx = files
                .filter { it.name.endsWith(".onnx") }
                .sortedBy {
                    val int8 = it.name.contains("int8")
                    when {
                        preferInt8 && int8 -> 0
                        preferInt8 && !int8 -> 1
                        !preferInt8 && int8 -> 1
                        else -> 0
                    }
                }
                .firstOrNull() ?: return null
            val tokens = files.firstOrNull { it.name == "tokens.txt" } ?: return null
            if (tokens.length() <= 0L) return null
            val dataDir = dir.walkTopDown().firstOrNull { it.isDirectory && it.name == "espeak-ng-data" }
                ?: return null
            if (!File(dataDir, "phontab").isFile || !File(dataDir, "phonindex").isFile) return null
            val voices = files.firstOrNull { it.name == "voices.bin" }
            return ModelFiles(kind, onnx, tokens, dataDir, voices)
        }
    }
}
