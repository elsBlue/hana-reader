package com.hana.reader

import com.hana.reader.tts.TtsPacks
import com.hana.reader.tts.VoiceSwitchLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSwitchLogicTest {
    @Test
    fun onSelectActionDownloadsWhenPackMissing() {
        assertEquals(
            VoiceSwitchLogic.SelectAction.DownloadOnly,
            VoiceSwitchLogic.onSelectAction(packReady = false)
        )
        assertEquals(
            VoiceSwitchLogic.SelectAction.SwitchVoice,
            VoiceSwitchLogic.onSelectAction(packReady = true)
        )
    }

    @Test
    fun forLanguageVsPackForVoice() {
        assertEquals(TtsPacks.EN_SMOOTH, TtsPacks.forLanguage("en"))
        assertEquals(TtsPacks.EN_SMOOTH, TtsPacks.packForVoice("en_lessac"))
        assertEquals(TtsPacks.EN_WARM, TtsPacks.packForVoice("en_amy"))
        assertEquals(TtsPacks.EN_WARM.storageKey, TtsPacks.packForVoice("en_amy")!!.storageKey)
        assertEquals(TtsPacks.ID, TtsPacks.packForVoice("id_news"))
    }


    @Test
    fun warmPrepareOnlyWhenFinishedPackMatchesSelectedVoice() {
        val warmKey = TtsPacks.EN_WARM.storageKey
        val smoothKey = TtsPacks.EN_SMOOTH.storageKey
        assertTrue(VoiceSwitchLogic.shouldWarmPrepareAfterDownload(warmKey, "en_amy"))
        assertFalse(VoiceSwitchLogic.shouldWarmPrepareAfterDownload(warmKey, "en_lessac"))
        assertFalse(VoiceSwitchLogic.shouldWarmPrepareAfterDownload(smoothKey, "en_amy"))
        assertFalse(VoiceSwitchLogic.shouldWarmPrepareAfterDownload(warmKey, null))
    }


    @Test
    fun synthEpochRejectsStaleResults() {
        assertTrue(VoiceSwitchLogic.acceptSynthResult(3, 3))
        assertFalse(VoiceSwitchLogic.acceptSynthResult(3, 4))
    }

    @Test
    fun queueFillOnlyForActivePack() {
        assertTrue(VoiceSwitchLogic.maySynthForQueue(null, "piper-en-amy-medium"))
        assertTrue(VoiceSwitchLogic.maySynthForQueue("piper-en-amy-medium", "piper-en-amy-medium"))
        assertFalse(VoiceSwitchLogic.maySynthForQueue("piper-en-lessac-medium", "piper-en-amy-medium"))
    }
}
