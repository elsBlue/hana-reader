package com.hana.reader.tts

data class CatalogVoice(
    val id: String,
    val language: String,
    val sid: Int,
    val name: String,
    val label: String,
    val traits: String,
    val packId: String,
)

object VoiceCatalog {
    // Bella first (default). Blend/af sid0 removed — silent / unused in pack UX.
    val ENGLISH = listOf(
        CatalogVoice("af_bella", "en", 1, "af_bella", "Bella", "Warm narration — Hana default", TtsPacks.EN.packId),
        CatalogVoice("en_lessac", "en", 0, "lessac", "Smooth", "Continuous listen — fewer gaps (Piper)", TtsPacks.EN_SMOOTH.packId),
        CatalogVoice("af_nicole", "en", 2, "af_nicole", "Nicole", "Breathy / soft", TtsPacks.EN.packId),
        CatalogVoice("af_sarah", "en", 3, "af_sarah", "Sarah", "Clear / neutral", TtsPacks.EN.packId),
        CatalogVoice("af_sky", "en", 4, "af_sky", "Sky", "Bright / light", TtsPacks.EN.packId),
        CatalogVoice("am_adam", "en", 5, "am_adam", "Adam", "American male", TtsPacks.EN.packId),
        CatalogVoice("am_michael", "en", 6, "am_michael", "Michael", "Deep male", TtsPacks.EN.packId),
        CatalogVoice("bf_emma", "en", 7, "bf_emma", "Emma", "British female", TtsPacks.EN.packId),
        CatalogVoice("bf_isabella", "en", 8, "bf_isabella", "Isabella", "Soft British", TtsPacks.EN.packId),
        CatalogVoice("bm_george", "en", 9, "bm_george", "George", "British male", TtsPacks.EN.packId),
        CatalogVoice("bm_lewis", "en", 10, "bm_lewis", "Lewis", "Casual British male", TtsPacks.EN.packId),
    )

    val INDONESIAN = listOf(
        CatalogVoice("id_news", "id", 0, "news", "News", "Clear offline Piper (news style)", TtsPacks.ID.packId),
    )

    fun all(): List<CatalogVoice> = ENGLISH + INDONESIAN

    fun forLanguage(language: String): List<CatalogVoice> =
        if (language == "id") INDONESIAN else ENGLISH

    fun find(id: String): CatalogVoice? = all().firstOrNull { it.id == id }

    fun findBySid(language: String, sid: Int): CatalogVoice? =
        forLanguage(language).firstOrNull { it.sid == sid }
}
