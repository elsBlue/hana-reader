package com.hana.reader

import com.hana.reader.tts.TtsPacks
import com.hana.reader.tts.VoicePrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure range / factory checks for Voice settings (no Android prefs runtime).
 * Persistence is exercised via [VoicePrefs] companions + [TtsPacks] factories.
 */
class VoiceSettingsPrefsTest {
    @Test
    fun sliderRangesAreComfortable() {
        assertEquals(0.70f, VoicePrefs.RATE_MIN)
        assertEquals(1.15f, VoicePrefs.RATE_MAX)
        assertEquals(0.90f, VoicePrefs.LENGTH_MIN)
        assertEquals(1.40f, VoicePrefs.LENGTH_MAX)
        assertEquals(0.30f, VoicePrefs.NOISE_MIN)
        assertEquals(0.80f, VoicePrefs.NOISE_MAX)
        assertEquals(0.35f, VoicePrefs.NOISE_W_MIN)
        assertEquals(0.85f, VoicePrefs.NOISE_W_MAX)
    }

    @Test
    fun warmFactoryIsQuieterThanSmooth() {
        val smooth = TtsPacks.SMOOTH_ACOUSTIC
        val warm = TtsPacks.WARM_ACOUSTIC
        assertTrue(warm.noiseScale < smooth.noiseScale)
        assertTrue(warm.noiseScaleW < smooth.noiseScaleW)
        assertEquals(0.42f, warm.noiseScale)
        assertEquals(0.48f, warm.noiseScaleW)
        assertEquals(1.15f, warm.lengthScale)
        assertEquals(1.15f, smooth.lengthScale)
    }

    @Test
    fun acousticEqualsSupportsReloadFingerprint() {
        val a = TtsPacks.Acoustic(1.15f, 0.42f, 0.48f)
        val b = TtsPacks.Acoustic(1.15f, 0.42f, 0.48f)
        val c = TtsPacks.Acoustic(1.15f, 0.50f, 0.48f)
        assertEquals(a, b)
        assertTrue(a != c)
    }
}
