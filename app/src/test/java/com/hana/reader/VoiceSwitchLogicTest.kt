package com.hana.reader

import com.hana.reader.tts.TtsPacks
import com.hana.reader.tts.VoiceSwitchLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        // Retired Warm id still resolves to Smooth pack (no crash).
        assertEquals(TtsPacks.EN_SMOOTH, TtsPacks.packForVoice("en_amy"))
        assertNull(TtsPacks.packForVoice("id_news"))
        assertNull(TtsPacks.packForVoice("id_cerita"))
    }


    @Test
    fun warmPrepareOnlyWhenFinishedPackMatchesSelectedVoice() {
        val smoothKey = TtsPacks.EN_SMOOTH.storageKey
        val retiredWarmKey = TtsPacks.RETIRED_WARM_STORAGE_KEY
        assertTrue(VoiceSwitchLogic.shouldWarmPrepareAfterDownload(smoothKey, "en_lessac"))
        assertFalse(VoiceSwitchLogic.shouldWarmPrepareAfterDownload(retiredWarmKey, "en_lessac"))
        // en_amy canonicalizes to Smooth → prepare only when Smooth pack finished.
        assertTrue(VoiceSwitchLogic.shouldWarmPrepareAfterDownload(smoothKey, "en_amy"))
        assertFalse(VoiceSwitchLogic.shouldWarmPrepareAfterDownload(retiredWarmKey, "en_amy"))
        assertFalse(VoiceSwitchLogic.shouldWarmPrepareAfterDownload(smoothKey, null))
    }


    @Test
    fun synthEpochRejectsStaleResults() {
        assertTrue(VoiceSwitchLogic.acceptSynthResult(3, 3))
        assertFalse(VoiceSwitchLogic.acceptSynthResult(3, 4))
    }

    @Test
    fun queueFillOnlyForActivePack() {
        assertTrue(VoiceSwitchLogic.maySynthForQueue(null, "piper-en-lessac-medium"))
        assertTrue(VoiceSwitchLogic.maySynthForQueue("piper-en-lessac-medium", "piper-en-lessac-medium"))
        assertFalse(VoiceSwitchLogic.maySynthForQueue("piper-en-lessac-medium", "other-pack"))
    }
}
