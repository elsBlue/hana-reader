package com.hana.reader.tts

import android.content.Context

object VoiceDefaults {
    const val COMFORT_FLAG = "comfort_listen_v1"

    fun defaultId(language: String): String =
        if (language == "id") "id_news" else "en_lessac"

    /**
     * Old factory default was Bella/Kokoro, which waits twice on slower phones.
     * Treat unset + Bella as Smooth unless the user already picked another voice.
     */
    fun englishIdAfterComfortMigration(saved: String?): String {
        if (saved == null || saved == "af_bella") return "en_lessac"
        if (VoiceCatalog.find(saved) != null) return saved
        return "en_lessac"
    }
}

class VoicePrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("hana", Context.MODE_PRIVATE)

    fun selectedVoiceId(language: String): String {
        migrateComfortOnce()
        val key = key(language)
        val saved = prefs.getString(key, null)
        if (saved != null && VoiceCatalog.find(saved) != null) return saved
        val migrated = VoiceDefaults.defaultId(language)
        if (saved != null && saved != migrated) {
            prefs.edit().putString(key, migrated).apply()
        }
        return migrated
    }

    fun selectedSid(language: String): Int {
        return VoiceCatalog.find(selectedVoiceId(language))?.sid
            ?: if (language == "id") 0 else TtsPacks.PIPER_SID
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
