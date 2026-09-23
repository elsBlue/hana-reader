package com.hana.reader

import com.hana.reader.tts.OrtRuntimePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrtRuntimePolicyTest {
    @Test
    fun prefersXnnpackThenCpuNeverNnapi() {
        val attempts = OrtRuntimePolicy.loadAttempts(availableProcessors = 8)
        assertEquals(2, attempts.size)
        assertEquals("xnnpack", attempts[0].provider)
        assertEquals(1, attempts[0].numThreads)
        assertEquals("cpu", attempts[1].provider)
        assertEquals(4, attempts[1].numThreads)
        assertFalse(attempts.any { OrtRuntimePolicy.isNnapi(it.provider) })
    }

    @Test
    fun cpuThreadsClampedToTwoThroughFour() {
        assertEquals(2, OrtRuntimePolicy.cpuThreads(1))
        assertEquals(2, OrtRuntimePolicy.cpuThreads(2))
        assertEquals(3, OrtRuntimePolicy.cpuThreads(3))
        assertEquals(4, OrtRuntimePolicy.cpuThreads(4))
        assertEquals(4, OrtRuntimePolicy.cpuThreads(16))
    }

    @Test
    fun lowCorePhoneKeepsCpuFallbackAtTwo() {
        val attempts = OrtRuntimePolicy.loadAttempts(availableProcessors = 2)
        assertEquals(1, attempts[0].numThreads)
        assertEquals(2, attempts[1].numThreads)
        assertTrue(attempts[1].numThreads in 2..4)
    }
}
