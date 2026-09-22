package com.hana.reader

import com.hana.reader.tts.NeuralKind
import com.hana.reader.tts.TtsPacks
import com.hana.reader.tts.VoiceProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsPacksTest {
    @Test
    fun englishIsKokoroInt8() {
        val pack = TtsPacks.forLanguage("en")!!
        assertEquals(NeuralKind.Kokoro, pack.kind)
        assertTrue(pack.url.endsWith("kokoro-int8-en-v0_19.tar.bz2"))
        assertEquals(1, TtsPacks.speakerId("en", VoiceProfile.Hana)) // af_bella
        assertEquals(3, TtsPacks.speakerId("en", VoiceProfile.Clear))
    }

    @Test
    fun indonesianIsPiper() {
        val pack = TtsPacks.forLanguage("id")!!
        assertEquals(NeuralKind.Piper, pack.kind)
        assertTrue(pack.url.contains("id_ID-news_tts-medium"))
        assertEquals(0, TtsPacks.speakerId("id", VoiceProfile.Hana))
    }

    @Test
    fun unknownLanguageHasNoPack() {
        assertNull(TtsPacks.forLanguage("ja"))
    }

    @Test
    fun tarSlipIsRejected() {
        assertNull(TtsPacks.safeTarRelative("../evil.bin"))
        assertNull(TtsPacks.safeTarRelative("foo/../../etc/passwd"))
        assertNull(TtsPacks.safeTarRelative("/abs/path"))
        assertEquals("model.int8.onnx", TtsPacks.safeTarRelative("model.int8.onnx"))
        assertEquals("model.int8.onnx", TtsPacks.safeTarRelative("./model.int8.onnx"))
        assertEquals(
            "kokoro-int8-en-v0_19/voices.bin",
            TtsPacks.safeTarRelative("kokoro-int8-en-v0_19/voices.bin")
        )
    }
}
