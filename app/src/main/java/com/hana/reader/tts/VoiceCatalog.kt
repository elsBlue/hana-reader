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
        CatalogVoice(
            "en_amy", "en", 0, "amy", "Warm",
            "Softer voice — commas and sentence space kept", TtsPacks.EN_WARM.packId
        ),
    )

    /** Retired Indonesian neural ids (News/Cerita) — resolve harmlessly for old prefs. */
    private val RETIRED_ID_ALIASES = mapOf(
        "id_news" to "en_lessac",
        "id_cerita" to "en_lessac",
    )

    fun all(): List<CatalogVoice> = ENGLISH

    fun forLanguage(language: String): List<CatalogVoice> =
        if (language == "en") ENGLISH else emptyList()

    fun find(id: String): CatalogVoice? = all().firstOrNull { it.id == id }

    fun findBySid(language: String, sid: Int): CatalogVoice? =
        forLanguage(language).firstOrNull { it.sid == sid }

    /** Canonical id after renames / retired Indonesian neural prefs. */
    fun canonicalId(id: String): String = RETIRED_ID_ALIASES[id] ?: id
}
