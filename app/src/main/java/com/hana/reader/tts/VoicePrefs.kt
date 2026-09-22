package com.hana.reader.tts

import android.content.Context

object VoiceDefaults {
    const val COMFORT_FLAG = "comfort_listen_v1"

    private val WARM_LEGACY_IDS = setOf(
        "af_bella", "af_nicole", "af_sky", "bf_emma", "bf_isabella"
    )

    fun defaultId(language: String): String =
        if (language == "id") "id_news" else "en_lessac"

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
}

class VoicePrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("hana", Context.MODE_PRIVATE)

    fun selectedVoiceId(language: String): String {
        migrateComfortOnce()
        val key = key(language)
        val saved = prefs.getString(key, null)
        if (saved != null && VoiceCatalog.find(saved) != null) return saved
        val migrated = if (language == "id") {
            VoiceDefaults.defaultId("id")
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
            .putString(key(language), voiceId)
            .putBoolean(VoiceDefaults.COMFORT_FLAG, true)
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

    private fun key(language: String) = "voice_$language"
}
