package com.hana.reader.tts

data class PcmAudio(
    val samples: FloatArray,
    val sampleRate: Int
)

interface TtsEngine {
    fun setRate(rate: Float)
    fun setPitch(pitch: Float)
    fun stop()
    fun shutdown()
}
