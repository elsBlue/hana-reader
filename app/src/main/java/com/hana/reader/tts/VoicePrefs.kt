package com.hana.reader.tts

import android.content.Context

object VoiceDefaults {
    const val COMFORT_FLAG = "comfort_listen_v1"
    /** One-shot: drop retired Indonesian neural prefs (News/Cerita). */
    const val ID_RETIRED_FLAG = "id_neural_retired_v1"
    @Deprecated("Use ID_RETIRED_FLAG")
    const val ID_CERITA_FLAG = "id_cerita_v1"

    private val WARM_LEGACY_IDS = setOf(
        "af_bella", "af_nicole", "af_sky", "bf_emma", "bf_isabella"
    )

    private val RETIRED_ID_IDS = setOf("id_news", "id_cerita")

    fun defaultId(language: String): String = "en_lessac"

    /**
     * Old factory default was Bella/Kokoro. Treat unset + Bella as Smooth
     * unless the user already picked another voice.
     */
    fun englishIdAfterComfortMigration(saved: String?): String {
        if (saved == null || saved == "af_bella") return "en_lessac"
        if (VoiceCatalog.find(saved) != null) return saved
        return resolveEnglishId(saved)
    }

    /** Map leftover Kokoro voice ids after the pack was removed. */
    fun resolveEnglishId(saved: String?): String {
        if (saved != null && VoiceCatalog.find(saved) != null) return saved
        return if (saved in WARM_LEGACY_IDS) "en_amy" else "en_lessac"
    }

    /**
     * Indonesian neural packs retired — map old prefs to Smooth (English catalog)
     * so SharedPreferences do not keep dangling id_* ids.
     */
    fun indonesianIdAfterRetirement(saved: String?): String {
        if (saved == null || saved in RETIRED_ID_IDS) return "en_lessac"
        return VoiceCatalog.canonicalId(saved).takeIf { VoiceCatalog.find(it) != null }
            ?: "en_lessac"
    }

    @Deprecated("Indonesian neural retired", ReplaceWith("indonesianIdAfterRetirement(saved)"))
    fun indonesianIdAfterCeritaMigration(saved: String?): String = indonesianIdAfterRetirement(saved)
}

class VoicePrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("hana", Context.MODE_PRIVATE)

    fun selectedVoiceId(language: String): String {
        migrateComfortOnce()
        migrateIdRetiredOnce()
        // Neural catalog is English-only; ID books use System TTS (no neural pack).
        val keyLang = if (language == "id") "en" else language
        val key = key(keyLang)
        val saved = prefs.getString(key, null)
        if (saved != null && VoiceCatalog.find(saved) != null) {
            val canonical = VoiceCatalog.canonicalId(saved)
            if (canonical != saved) {
                prefs.edit().putString(key, canonical).apply()
            }
            return canonical
        }
        val migrated = VoiceDefaults.resolveEnglishId(saved)
        if (saved != migrated) {
            prefs.edit().putString(key, migrated).apply()
        }
        return migrated
    }

    fun selectedSid(language: String): Int {
        return VoiceCatalog.find(selectedVoiceId(language))?.sid
            ?: TtsPacks.PIPER_SID
    }

    fun setSelectedVoiceId(language: String, voiceId: String) {
        val keyLang = if (language == "id") "en" else language
        prefs.edit()
            .putString(key(keyLang), VoiceCatalog.canonicalId(voiceId))
            .putBoolean(VoiceDefaults.COMFORT_FLAG, true)
            .putBoolean(VoiceDefaults.ID_RETIRED_FLAG, true)
            .apply()
    }

    fun selectedVoice(language: String): CatalogVoice? =
        VoiceCatalog.find(selectedVoiceId(language))

    private fun migrateComfortOnce() {
        if (prefs.getBoolean(VoiceDefaults.COMFORT_FLAG, false)) return
        val next = VoiceDefaults.englishIdAfterComfortMigration(prefs.getString(key("en"), null))
        prefs.edit()
            .putBoolean(VoiceDefaults.COMFORT_FLAG, true)
            .putString(key("en"), next)
            .apply()
    }

    private fun migrateIdRetiredOnce() {
        if (prefs.getBoolean(VoiceDefaults.ID_RETIRED_FLAG, false)) return
        val oldId = prefs.getString(key("id"), null)
        val next = VoiceDefaults.indonesianIdAfterRetirement(oldId)
        // Fold any retired id_* selection into the English Smooth default; drop voice_id key.
        prefs.edit()
            .putBoolean(VoiceDefaults.ID_RETIRED_FLAG, true)
            .remove(key("id"))
            .apply {
                if (prefs.getString(key("en"), null) == null) {
                    putString(key("en"), next)
                }
            }
            .apply()
    }

    private fun key(language: String) = "voice_$language"
}
