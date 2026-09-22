package com.hana.reader

import com.hana.reader.tts.TtsPacks
import com.hana.reader.tts.VoiceCatalog
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
    }

    @Test
    fun comfortDefaultMigratesFactoryBellaToSmooth() {
        assertEquals("en_lessac", com.hana.reader.tts.VoiceDefaults.defaultId("en"))
        assertEquals("en_lessac", com.hana.reader.tts.VoiceDefaults.englishIdAfterComfortMigration(null))
        assertEquals("en_lessac", com.hana.reader.tts.VoiceDefaults.englishIdAfterComfortMigration("af_bella"))
        assertEquals("en_lessac", com.hana.reader.tts.VoiceDefaults.englishIdAfterComfortMigration("en_lessac"))
        assertEquals("id_news", com.hana.reader.tts.VoiceDefaults.defaultId("id"))
    }

    @Test
    fun leftoverKokoroWarmVoicesMapToAmy() {
        assertEquals("en_amy", com.hana.reader.tts.VoiceDefaults.resolveEnglishId("af_bella"))
        assertEquals("en_amy", com.hana.reader.tts.VoiceDefaults.resolveEnglishId("af_nicole"))
        assertEquals("en_lessac", com.hana.reader.tts.VoiceDefaults.resolveEnglishId("am_adam"))
        assertEquals("en_lessac", com.hana.reader.tts.VoiceDefaults.resolveEnglishId(null))
        assertEquals("en_amy", com.hana.reader.tts.VoiceDefaults.resolveEnglishId("en_amy"))
    }

    @Test
    fun indonesianNewsRemains() {
        assertEquals("id_news", VoiceCatalog.INDONESIAN.first().id)
        assertEquals(0, VoiceCatalog.INDONESIAN.first().sid)
    }
}
