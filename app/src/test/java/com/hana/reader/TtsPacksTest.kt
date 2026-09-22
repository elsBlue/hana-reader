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
    fun englishDefaultIsSmoothPiper() {
        val pack = TtsPacks.forLanguage("en")!!
        assertEquals(NeuralKind.Piper, pack.kind)
        assertEquals(TtsPacks.EN_SMOOTH, pack)
        assertTrue(pack.url.contains("en_US-lessac-medium"))
        assertFalse(pack.url.contains("int8"))
        assertFalse(pack.url.contains("kokoro"))
        assertEquals(0, TtsPacks.speakerId("en", VoiceProfile.Hana))
        assertEquals(0, TtsPacks.speakerId("en", VoiceProfile.Clear))
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
        val neural = TtsPacks.playerCaption(
            "en",
            VoiceProfile.Hana,
            usingNeural = true,
            downloading = false,
            status = null,
            selectedVoiceName = "lessac",
            selectedVoiceId = "en_lessac"
        )
        assertTrue(neural.contains("Smooth"))
        assertTrue(neural.contains("lessac"))
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
    fun warmEnglishIsPiperAmy() {
        val pack = TtsPacks.forLanguage("en-amy")!!
        assertEquals(NeuralKind.Piper, pack.kind)
        assertEquals("en-amy", pack.storageKey)
        assertEquals("piper-en-amy-medium", pack.packId)
        assertTrue(pack.url.contains("en_US-amy-medium"))
        assertFalse(pack.url.contains("int8"))
        assertEquals(TtsPacks.EN_SMOOTH, TtsPacks.packForVoice("en_lessac"))
        assertEquals(TtsPacks.EN_WARM, TtsPacks.packForVoice("en_amy"))
        assertNull(TtsPacks.packForVoice("af_bella"))
        assertEquals(TtsPacks.ID, TtsPacks.packForVoice("id_news"))
        assertEquals(listOf(TtsPacks.EN_SMOOTH, TtsPacks.EN_WARM), TtsPacks.packsForLanguage("en"))
        val caption = TtsPacks.playerCaption(
            "en",
            VoiceProfile.Hana,
            usingNeural = true,
            downloading = false,
            status = null,
            selectedVoiceName = "amy",
            selectedVoiceId = "en_amy"
        )
        assertTrue(caption.contains("Warm"))
        assertTrue(caption.contains("amy"))
    }

    @Test
    fun comfortListenHonorsPausesAndSlowsDown() {
        assertEquals(1.0f, TtsPacks.DEFAULT_RATE)
        assertEquals(1.0f, TtsPacks.SILENCE_SCALE)
        assertEquals(400, TtsPacks.SENTENCE_PAUSE_MS)
        assertEquals(180, TtsPacks.COMMA_PAUSE_MS)
        val smooth = TtsPacks.acousticFor(TtsPacks.EN_SMOOTH.packId)
        assertEquals(1.15f, smooth.lengthScale)
        assertEquals(0.667f, smooth.noiseScale)
        assertEquals(0.70f, smooth.noiseScaleW)
        val warm = TtsPacks.acousticFor(TtsPacks.EN_WARM.packId)
        assertEquals(1.20f, warm.lengthScale)
        assertEquals(0.667f, warm.noiseScale)
        assertEquals(0.85f, warm.noiseScaleW)
        val id = TtsPacks.acousticFor(TtsPacks.ID.packId)
        assertEquals(1.18f, id.lengthScale)
        assertEquals(0.667f, id.noiseScale)
    }

    @Test
    fun tarSlipIsRejected() {
        assertNull(TtsPacks.safeTarRelative("../evil.bin"))
        assertNull(TtsPacks.safeTarRelative("foo/../../etc/passwd"))
        assertNull(TtsPacks.safeTarRelative("/abs/path"))
        assertEquals("model.onnx", TtsPacks.safeTarRelative("model.onnx"))
        assertEquals("model.onnx", TtsPacks.safeTarRelative("./model.onnx"))
        assertEquals(
            "vits-piper-en_US-amy-medium/en_US-amy-medium.onnx",
            TtsPacks.safeTarRelative("vits-piper-en_US-amy-medium/en_US-amy-medium.onnx")
        )
    }
}
