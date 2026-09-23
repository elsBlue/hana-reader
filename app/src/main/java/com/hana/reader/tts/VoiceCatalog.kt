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
    val ENGLISH = listOf(
        CatalogVoice(
            "en_lessac", "en", 0, "lessac", "Smooth",
            "Clear audiobook — slower, with pauses", TtsPacks.EN_SMOOTH.packId
        ),
    )

    /**
     * Retired ids — resolve harmlessly for old prefs.
     * Indonesian neural (News/Cerita) and Warm (Amy) map to Smooth.
     */
    private val RETIRED_ALIASES = mapOf(
        "id_news" to "en_lessac",
        "id_cerita" to "en_lessac",
        "en_amy" to "en_lessac",
    )

    fun all(): List<CatalogVoice> = ENGLISH

    fun forLanguage(language: String): List<CatalogVoice> =
        if (language == "en") ENGLISH else emptyList()

    fun find(id: String): CatalogVoice? = all().firstOrNull { it.id == id }

    fun findBySid(language: String, sid: Int): CatalogVoice? =
        forLanguage(language).firstOrNull { it.sid == sid }

    /** Canonical id after renames / retired Warm + Indonesian neural prefs. */
    fun canonicalId(id: String): String = RETIRED_ALIASES[id] ?: id
}
