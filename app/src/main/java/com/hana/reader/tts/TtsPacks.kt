package com.hana.reader.tts

enum class NeuralKind { Kokoro, Piper }

data class TtsPack(
    val language: String,
    val kind: NeuralKind,
    val url: String,
    val archiveName: String,
    val minArchiveBytes: Long,
    /** Bump to force re-download when pack quality defaults change. */
    val packId: String,
    val displayName: String,
)

object TtsPacks {
    // kokoro-en-v0_19 speaker map:
    // 0 af, 1 af_bella, 2 af_nicole, 3 af_sarah, 4 af_sky, ...
    const val KOKORO_HANA_SID = 1 // af_bella
    const val KOKORO_CLEAR_SID = 3 // af_sarah
    const val PIPER_SID = 0
    const val KOKORO_VOICE_HANA = "af_bella"
    const val KOKORO_VOICE_CLEAR = "af_sarah"
    const val DEFAULT_RATE = 0.92f
    const val SILENCE_SCALE = 0.4f

    val EN = TtsPack(
        language = "en",
        kind = NeuralKind.Kokoro,
        // fp32 — int8 on Android/ARM has known quality bugs (sherpa-onnx #3754)
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-en-v0_19.tar.bz2",
        archiveName = "kokoro-en-v0_19.tar.bz2",
        minArchiveBytes = 200L * 1024 * 1024,
        packId = "kokoro-en-v0_19-fp32",
        displayName = "Kokoro"
    )

    val ID = TtsPack(
        language = "id",
        kind = NeuralKind.Piper,
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-id_ID-news_tts-medium.tar.bz2",
        archiveName = "vits-piper-id_ID-news_tts-medium.tar.bz2",
        minArchiveBytes = 40L * 1024 * 1024,
        packId = "piper-id-news-medium",
        displayName = "Piper"
    )

    fun forLanguage(language: String): TtsPack? = when (language) {
        "en" -> EN
        "id" -> ID
        else -> null
    }

    fun speakerId(language: String, profile: VoiceProfile, selectedSid: Int? = null): Int {
        if (profile != VoiceProfile.Hana) {
            return if (language == "id") PIPER_SID else KOKORO_CLEAR_SID
        }
        if (language == "id") return PIPER_SID
        return selectedSid ?: KOKORO_HANA_SID
    }

    fun voiceName(language: String, profile: VoiceProfile, selectedId: String? = null): String {
        if (profile != VoiceProfile.Hana) {
            return if (language == "id") "news" else KOKORO_VOICE_CLEAR
        }
        if (selectedId != null) return VoiceCatalog.find(selectedId)?.name ?: selectedId
        return if (language == "id") "news" else KOKORO_VOICE_HANA
    }

    /** Short label for the mini player: what is speaking + how to switch. */
    fun playerCaption(
        language: String,
        profile: VoiceProfile,
        usingNeural: Boolean,
        downloading: Boolean,
        status: String?,
        selectedVoiceName: String? = null
    ): String {
        if (downloading) return status ?: "Downloading voice…"
        return when {
            profile == VoiceProfile.Hana && usingNeural -> {
                val pack = forLanguage(language)
                val engine = pack?.displayName ?: "Neural"
                val voice = selectedVoiceName ?: voiceName(language, profile)
                "$engine · $voice · tap = system"
            }
            profile == VoiceProfile.Hana && !usingNeural -> {
                val why = status?.takeIf {
                    it.isNotBlank() &&
                        !it.equals("Neural voice ready", true) &&
                        !it.equals("Hana voice ready", true)
                }
                if (why != null && why.contains("device", ignoreCase = true).not()) {
                    "System voice · $why · tap = retry Hana"
                } else {
                    "System voice · Hana offline · tap = retry"
                }
            }
            else -> "System voice · tap = Hana"
        }
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
