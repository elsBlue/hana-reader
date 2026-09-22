package com.hana.reader.tts

enum class NeuralKind { Kokoro, Piper }

data class TtsPack(
    val language: String,
    val kind: NeuralKind,
    val url: String,
    val archiveName: String,
    val minArchiveBytes: Long,
)

object TtsPacks {
    const val KOKORO_HANA_SID = 2 // af_nicole — breathy / soft for long listening
    const val KOKORO_CLEAR_SID = 3 // af_sarah — brighter, more neutral
    const val PIPER_SID = 0

    val EN = TtsPack(
        language = "en",
        kind = NeuralKind.Kokoro,
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-en-v0_19.tar.bz2",
        archiveName = "kokoro-int8-en-v0_19.tar.bz2",
        minArchiveBytes = 80L * 1024 * 1024
    )

    val ID = TtsPack(
        language = "id",
        kind = NeuralKind.Piper,
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-id_ID-news_tts-medium.tar.bz2",
        archiveName = "vits-piper-id_ID-news_tts-medium.tar.bz2",
        minArchiveBytes = 40L * 1024 * 1024
    )

    fun forLanguage(language: String): TtsPack? = when (language) {
        "en" -> EN
        "id" -> ID
        else -> null
    }

    fun speakerId(language: String, profile: VoiceProfile): Int {
        if (language == "id") return PIPER_SID
        return if (profile == VoiceProfile.Hana) KOKORO_HANA_SID else KOKORO_CLEAR_SID
    }

    /** Reject tar entries that would write outside the destination (zip/tar slip). */
    fun safeTarRelative(name: String): String? {
        val raw = name.replace('\\', '/').trim()
        if (raw.isEmpty() || raw.startsWith("/")) return null
        val parts = raw.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }
}
