package com.hana.reader.tts

enum class NeuralKind { Piper }

data class TtsPack(
    val language: String,
    val kind: NeuralKind,
    val url: String,
    val archiveName: String,
    val minArchiveBytes: Long,
    /** Bump to force re-download when pack quality defaults change. */
    val packId: String,
    val displayName: String,
    val storageKey: String,
)

object TtsPacks {
    const val PIPER_SID = 0
    /** Slightly slower than conversation — people need time to hear the words. */
    const val DEFAULT_RATE = 0.82f
    /** 1.0 = natural Piper pauses. 0.4 was crushing commas and sentence gaps. */
    const val SILENCE_SCALE = 1.35f
    /** >1 draws vowels out a little so Smooth is not a rush. */
    const val LENGTH_SCALE = 1.12f
    /** Lower than Piper default (0.667) — cuts the VITS radio-static shimmer. */
    const val NOISE_SCALE = 0.50f
    const val NOISE_SCALE_W = 0.60f
    const val SENTENCE_PAUSE_MS = 320
    const val COMMA_PAUSE_MS = 140
    const val BREATH_PAUSE_MS = 40
    const val RETIRED_KOKORO_STORAGE_KEY = "en"

    val EN_SMOOTH = TtsPack(
        language = "en",
        kind = NeuralKind.Piper,
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-lessac-medium.tar.bz2",
        archiveName = "vits-piper-en_US-lessac-medium.tar.bz2",
        minArchiveBytes = 40L * 1024 * 1024,
        packId = "piper-en-lessac-medium",
        displayName = "Smooth",
        storageKey = "en-smooth"
    )

    /** Warmer English Piper — same speed class as Smooth, replaces Kokoro/Bella. */
    val EN_WARM = TtsPack(
        language = "en",
        kind = NeuralKind.Piper,
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-amy-medium.tar.bz2",
        archiveName = "vits-piper-en_US-amy-medium.tar.bz2",
        minArchiveBytes = 40L * 1024 * 1024,
        packId = "piper-en-amy-medium",
        displayName = "Warm",
        storageKey = "en-amy"
    )

    val ID = TtsPack(
        language = "id",
        kind = NeuralKind.Piper,
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-id_ID-news_tts-medium.tar.bz2",
        archiveName = "vits-piper-id_ID-news_tts-medium.tar.bz2",
        minArchiveBytes = 40L * 1024 * 1024,
        packId = "piper-id-news-medium",
        displayName = "Piper",
        storageKey = "id"
    )

    fun all(): List<TtsPack> = listOf(EN_SMOOTH, EN_WARM, ID)

    fun forLanguage(language: String): TtsPack? = when (language) {
        "en", "en-smooth" -> EN_SMOOTH
        "en-amy" -> EN_WARM
        "id" -> ID
        else -> null
    }

    fun packsForLanguage(language: String): List<TtsPack> = when (language) {
        "en" -> listOf(EN_SMOOTH, EN_WARM)
        "id" -> listOf(ID)
        else -> emptyList()
    }

    fun packById(packId: String): TtsPack? = all().firstOrNull { it.packId == packId }

    fun packForVoice(voiceId: String?): TtsPack? {
        val voice = voiceId?.let { VoiceCatalog.find(it) } ?: return null
        return packById(voice.packId) ?: forLanguage(voice.language)
    }

    fun speakerId(language: String, profile: VoiceProfile, selectedSid: Int? = null): Int {
        if (language == "id") return PIPER_SID
        return selectedSid ?: PIPER_SID
    }

    fun voiceName(language: String, profile: VoiceProfile, selectedId: String? = null): String {
        if (selectedId != null) return VoiceCatalog.find(selectedId)?.name ?: selectedId
        return if (language == "id") "news" else "lessac"
    }

    /** Short label for the mini player: what is speaking + how to switch. */
    fun playerCaption(
        language: String,
        profile: VoiceProfile,
        usingNeural: Boolean,
        downloading: Boolean,
        status: String?,
        selectedVoiceName: String? = null,
        selectedVoiceId: String? = null
    ): String {
        if (downloading) return status ?: "Downloading voice…"
        // Surface prepare/synth wait so Listen does not look frozen.
        if (status != null && (
            status.contains("Preparing", ignoreCase = true) ||
            status.contains("Synthesizing", ignoreCase = true) ||
            status.contains("Loading", ignoreCase = true) ||
            status.contains("Starting", ignoreCase = true) ||
            status.contains("Getting first", ignoreCase = true) ||
            status.contains("Slow on this phone", ignoreCase = true)
        )) {
            return status
        }
        return when {
            profile == VoiceProfile.Hana && usingNeural -> {
                val voice = VoiceCatalog.find(selectedVoiceId.orEmpty())
                val pack = voice?.let { packById(it.packId) } ?: forLanguage(language)
                val engine = pack?.displayName ?: "Neural"
                val name = selectedVoiceName ?: voiceName(language, profile)
                "$engine · $name · tap = system"
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
