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

    /** Soft offline Indonesian — same official Piper news_tts pack, storytelling label. */
    val INDONESIAN = listOf(
        CatalogVoice(
            "id_cerita", "id", 0, "cerita", "Cerita",
            "Softer storytelling pace — offline Indonesian", TtsPacks.ID.packId
        ),
    )

    /** Legacy prefs / captions after the News → Cerita rename. */
    private val ALIASES = mapOf("id_news" to "id_cerita")

    fun all(): List<CatalogVoice> = ENGLISH + INDONESIAN

    fun forLanguage(language: String): List<CatalogVoice> =
        if (language == "id") INDONESIAN else ENGLISH

    fun find(id: String): CatalogVoice? {
        all().firstOrNull { it.id == id }?.let { return it }
        val alias = ALIASES[id] ?: return null
        return all().firstOrNull { it.id == alias }
    }

    fun findBySid(language: String, sid: Int): CatalogVoice? =
        forLanguage(language).firstOrNull { it.sid == sid }

    /** Canonical id after News → Cerita (and future) renames. */
    fun canonicalId(id: String): String = ALIASES[id] ?: id
}
