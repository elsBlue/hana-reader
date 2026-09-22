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
    fun preparingStatusSurfacesInCaption() {
        val prep = TtsPacks.playerCaption(
            "en", VoiceProfile.Hana, usingNeural = true, downloading = false, status = "Preparing voice…"
        )
        assertEquals("Preparing voice…", prep)
        val synth = TtsPacks.playerCaption(
            "en", VoiceProfile.Hana, usingNeural = true, downloading = false, status = "Synthesizing…"
        )
        assertEquals("Synthesizing…", synth)
        val starting = TtsPacks.playerCaption(
            "en", VoiceProfile.Hana, usingNeural = true, downloading = false, status = "Starting…"
        )
        assertEquals("Starting…", starting)
        val firstLine = TtsPacks.playerCaption(
            "en", VoiceProfile.Hana, usingNeural = true, downloading = false, status = "Getting first line… 2s"
        )
        assertEquals("Getting first line… 2s", firstLine)
        val preparing = TtsPacks.playerCaption(
            "en", VoiceProfile.Hana, usingNeural = true, downloading = false, status = "Preparing a few lines… 3s"
        )
        assertEquals("Preparing a few lines… 3s", preparing)
        val slow = TtsPacks.playerCaption(
            "en", VoiceProfile.Hana, usingNeural = true, downloading = false,
            status = "Slow on this phone — Voices → Smooth"
        )
        assertEquals("Slow on this phone — Voices → Smooth", slow)
    }

    @Test
    fun unknownLanguageHasNoPack() {
        assertNull(TtsPacks.forLanguage("ja"))
    }

    @Test
    fun smoothEnglishIsPiperLessac() {
        val pack = TtsPacks.forLanguage("en-smooth")!!
        assertEquals(NeuralKind.Piper, pack.kind)
        assertEquals("en-smooth", pack.storageKey)
        assertEquals("piper-en-lessac-medium", pack.packId)
        assertTrue(pack.url.contains("en_US-lessac-medium"))
        assertFalse(pack.url.contains("int8"))
        assertEquals(TtsPacks.EN_SMOOTH, TtsPacks.packForVoice("en_lessac"))
        assertEquals(TtsPacks.EN, TtsPacks.packForVoice("af_bella"))
        assertEquals(TtsPacks.ID, TtsPacks.packForVoice("id_news"))
        assertEquals(listOf(TtsPacks.EN_SMOOTH, TtsPacks.EN), TtsPacks.packsForLanguage("en"))
        val caption = TtsPacks.playerCaption(
            "en",
            VoiceProfile.Hana,
            usingNeural = true,
            downloading = false,
            status = null,
            selectedVoiceName = "lessac",
            selectedVoiceId = "en_lessac"
        )
        assertTrue(caption.contains("Smooth"))
        assertTrue(caption.contains("lessac"))
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
