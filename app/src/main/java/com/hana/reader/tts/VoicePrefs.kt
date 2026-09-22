package com.hana.reader.tts

import android.content.Context

class VoicePrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("hana", Context.MODE_PRIVATE)

    fun selectedVoiceId(language: String): String {
        val key = key(language)
        val saved = prefs.getString(key, null)
        if (saved != null && VoiceCatalog.find(saved) != null) return saved
        return defaultId(language)
    }

    fun selectedSid(language: String): Int {
        return VoiceCatalog.find(selectedVoiceId(language))?.sid
            ?: if (language == "id") 0 else TtsPacks.KOKORO_HANA_SID
    }

    fun setSelectedVoiceId(language: String, voiceId: String) {
        prefs.edit().putString(key(language), voiceId).apply()
    }

    fun selectedVoice(language: String): CatalogVoice? =
        VoiceCatalog.find(selectedVoiceId(language))

    private fun key(language: String) = "voice_$language"

    private fun defaultId(language: String): String =
        if (language == "id") "id_news" else "af_bella"
}
