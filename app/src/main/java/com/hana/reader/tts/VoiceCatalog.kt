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
            "Clear audiobook — default listen", TtsPacks.EN_SMOOTH.packId
        ),
        CatalogVoice(
            "en_amy", "en", 0, "amy", "Warm",
            "Softer conversational — still continuous", TtsPacks.EN_WARM.packId
        ),
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
