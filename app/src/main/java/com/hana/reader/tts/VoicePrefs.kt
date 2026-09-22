package com.hana.reader.tts

import android.content.Context

object VoiceDefaults {
    const val COMFORT_FLAG = "comfort_listen_v1"
    const val ID_CERITA_FLAG = "id_cerita_v1"

    private val WARM_LEGACY_IDS = setOf(
        "af_bella", "af_nicole", "af_sky", "bf_emma", "bf_isabella"
    )

    fun defaultId(language: String): String =
        if (language == "id") "id_cerita" else "en_lessac"

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

    /** News → Cerita: keep the same pack, new catalog id + softer defaults. */
    fun indonesianIdAfterCeritaMigration(saved: String?): String {
        if (saved == null || saved == "id_news") return "id_cerita"
        return VoiceCatalog.canonicalId(saved).takeIf { VoiceCatalog.find(it) != null }
            ?: "id_cerita"
    }
}

class VoicePrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("hana", Context.MODE_PRIVATE)

    fun selectedVoiceId(language: String): String {
        migrateComfortOnce()
        migrateIdCeritaOnce()
        val key = key(language)
        val saved = prefs.getString(key, null)
        if (saved != null && VoiceCatalog.find(saved) != null) {
            val canonical = VoiceCatalog.canonicalId(saved)
            if (canonical != saved) {
                prefs.edit().putString(key, canonical).apply()
            }
            return canonical
        }
        val migrated = if (language == "id") {
            VoiceDefaults.indonesianIdAfterCeritaMigration(saved)
        } else {
            VoiceDefaults.resolveEnglishId(saved)
        }
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
        prefs.edit()
            .putString(key(language), VoiceCatalog.canonicalId(voiceId))
            .putBoolean(VoiceDefaults.COMFORT_FLAG, true)
            .putBoolean(VoiceDefaults.ID_CERITA_FLAG, true)
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

    private fun migrateIdCeritaOnce() {
        if (prefs.getBoolean(VoiceDefaults.ID_CERITA_FLAG, false)) return
        val next = VoiceDefaults.indonesianIdAfterCeritaMigration(prefs.getString(key("id"), null))
        prefs.edit()
            .putBoolean(VoiceDefaults.ID_CERITA_FLAG, true)
            .putString(key("id"), next)
            .apply()
    }

    private fun key(language: String) = "voice_$language"
}
