package com.hana.reader

import com.hana.reader.tts.TtsPacks
import com.hana.reader.tts.VoiceCatalog
import com.hana.reader.tts.VoiceDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCatalogTest {
    @Test
    fun englishIsSmoothThenWarm() {
        val en = VoiceCatalog.ENGLISH
        assertEquals("en_lessac", en.first().id)
        assertEquals(TtsPacks.EN_SMOOTH.packId, en.first().packId)
        assertEquals("en_amy", en[1].id)
        assertEquals(TtsPacks.EN_WARM.packId, en[1].packId)
        assertNull(VoiceCatalog.find("af"))
        assertNull(VoiceCatalog.find("af_bella"))
        assertFalse(en.any { it.id.startsWith("af_") })
        assertEquals(2, en.size)
        assertEquals(VoiceCatalog.ENGLISH, VoiceCatalog.all())
        assertTrue(VoiceCatalog.forLanguage("id").isEmpty())
    }

    @Test
    fun comfortDefaultMigratesFactoryBellaToSmooth() {
        assertEquals("en_lessac", VoiceDefaults.defaultId("en"))
        assertEquals("en_lessac", VoiceDefaults.defaultId("id"))
        assertEquals("en_lessac", VoiceDefaults.englishIdAfterComfortMigration(null))
        assertEquals("en_lessac", VoiceDefaults.englishIdAfterComfortMigration("af_bella"))
        assertEquals("en_lessac", VoiceDefaults.englishIdAfterComfortMigration("en_lessac"))
    }

    @Test
    fun leftoverKokoroWarmVoicesMapToAmy() {
        assertEquals("en_amy", VoiceDefaults.resolveEnglishId("af_bella"))
        assertEquals("en_amy", VoiceDefaults.resolveEnglishId("af_nicole"))
        assertEquals("en_lessac", VoiceDefaults.resolveEnglishId("am_adam"))
        assertEquals("en_lessac", VoiceDefaults.resolveEnglishId(null))
        assertEquals("en_amy", VoiceDefaults.resolveEnglishId("en_amy"))
    }

    @Test
    fun retiredIndonesianPrefsMapToSmooth() {
        assertEquals("en_lessac", VoiceDefaults.indonesianIdAfterRetirement(null))
        assertEquals("en_lessac", VoiceDefaults.indonesianIdAfterRetirement("id_news"))
        assertEquals("en_lessac", VoiceDefaults.indonesianIdAfterRetirement("id_cerita"))
        assertEquals("en_lessac", VoiceCatalog.canonicalId("id_news"))
        assertEquals("en_lessac", VoiceCatalog.canonicalId("id_cerita"))
        assertNull(VoiceCatalog.find("id_cerita"))
        assertNull(VoiceCatalog.find("id_news"))
        assertNull(TtsPacks.forLanguage("id"))
        assertTrue(TtsPacks.packsForLanguage("id").isEmpty())
    }
}
