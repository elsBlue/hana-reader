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
    fun englishStartsWithSmoothAndKeepsBella() {
        val en = VoiceCatalog.ENGLISH
        assertEquals("en_lessac", en.first().id)
        assertEquals(TtsPacks.EN_SMOOTH.packId, en.first().packId)
        assertNull(VoiceCatalog.find("af"))
        assertFalse(en.any { it.id == "af" })
        assertTrue(en.any { it.id == "af_bella" })
        assertTrue(en.any { it.id == "en_lessac" && it.packId == TtsPacks.EN_SMOOTH.packId })
    }

    @Test
    fun comfortDefaultMigratesFactoryBellaToSmooth() {
        assertEquals("en_lessac", com.hana.reader.tts.VoiceDefaults.defaultId("en"))
        assertEquals("en_lessac", com.hana.reader.tts.VoiceDefaults.englishIdAfterComfortMigration(null))
        assertEquals("en_lessac", com.hana.reader.tts.VoiceDefaults.englishIdAfterComfortMigration("af_bella"))
        assertEquals("af_nicole", com.hana.reader.tts.VoiceDefaults.englishIdAfterComfortMigration("af_nicole"))
        assertEquals("id_news", com.hana.reader.tts.VoiceDefaults.defaultId("id"))
    }

    @Test
    fun indonesianNewsRemains() {
        assertEquals("id_news", VoiceCatalog.INDONESIAN.first().id)
        assertEquals(0, VoiceCatalog.INDONESIAN.first().sid)
    }
}
