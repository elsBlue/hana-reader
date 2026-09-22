package com.hana.reader

import com.hana.reader.tts.NeuralKind
import com.hana.reader.tts.TtsPacks
import com.hana.reader.tts.VoiceProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsPacksTest {
    @Test
    fun englishIsKokoroFp32() {
        val pack = TtsPacks.forLanguage("en")!!
        assertEquals(NeuralKind.Kokoro, pack.kind)
        assertTrue(pack.url.endsWith("kokoro-en-v0_19.tar.bz2"))
        assertFalse(pack.url.contains("int8"))
        assertEquals("kokoro-en-v0_19-fp32", pack.packId)
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
    fun playerCaptionExplainsEngineAndSwitch() {
        val neural = TtsPacks.playerCaption("en", VoiceProfile.Hana, usingNeural = true, downloading = false, status = null)
        assertTrue(neural.contains("Kokoro"))
        assertTrue(neural.contains("af_bella"))
        assertTrue(neural.contains("tap = system"))

        val offline = TtsPacks.playerCaption("en", VoiceProfile.Hana, usingNeural = false, downloading = false, status = null)
        assertTrue(offline.contains("System voice"))
        assertTrue(offline.contains("tap = retry"))

        val clear = TtsPacks.playerCaption("en", VoiceProfile.Clear, usingNeural = false, downloading = false, status = null)
        assertTrue(clear.contains("System voice"))
        assertTrue(clear.contains("tap = Hana"))
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
        assertEquals("model.onnx", TtsPacks.safeTarRelative("model.onnx"))
        assertEquals("model.onnx", TtsPacks.safeTarRelative("./model.onnx"))
        assertEquals(
            "kokoro-en-v0_19/voices.bin",
            TtsPacks.safeTarRelative("kokoro-en-v0_19/voices.bin")
        )
    }
}
