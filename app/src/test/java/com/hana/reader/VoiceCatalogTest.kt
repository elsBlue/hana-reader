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
    fun englishStartsWithBellaAndOmitsSilentBlend() {
        val en = VoiceCatalog.ENGLISH
        assertEquals("af_bella", en.first().id)
        assertEquals(TtsPacks.KOKORO_HANA_SID, en.first().sid)
        assertNull(VoiceCatalog.find("af"))
        assertFalse(en.any { it.id == "af" })
        assertTrue(en.any { it.id == "af_bella" })
        assertTrue(en.any { it.id == "en_lessac" && it.packId == TtsPacks.EN_SMOOTH.packId })
    }

    @Test
    fun indonesianNewsRemains() {
        assertEquals("id_news", VoiceCatalog.INDONESIAN.first().id)
        assertEquals(0, VoiceCatalog.INDONESIAN.first().sid)
    }
}
