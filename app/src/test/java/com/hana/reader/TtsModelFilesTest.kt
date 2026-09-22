package com.hana.reader

import com.hana.reader.tts.NeuralKind
import com.hana.reader.tts.TtsModelManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class TtsModelFilesTest {
    private fun piperLayout(dir: File, onnxName: String = "model.onnx", int8: Boolean = true) {
        File(dir, onnxName).writeText("full-precision-model")
        if (int8) File(dir, "model.int8.onnx").writeText("quant")
        File(dir, "tokens.txt").writeText("tokens")
        File(dir, "espeak-ng-data").mkdirs()
        File(dir, "espeak-ng-data/phontab").writeText("ph")
        File(dir, "espeak-ng-data/phonindex").writeText("idx")
    }

    @Test
    fun prefersFp32OnnxAndFindsPiperLayout() {
        val dir = File(createTempDir(), "pack").also { it.mkdirs() }
        piperLayout(dir)
        val found = TtsModelManager.findFiles(dir, NeuralKind.Piper)!!
        assertEquals("model.onnx", found.onnx.name)
        assertEquals("espeak-ng-data", found.dataDir.name)
    }

    @Test
    fun prefersInt8WhenRequestedAndPresent() {
        val dir = File(createTempDir(), "pack").also { it.mkdirs() }
        piperLayout(dir)
        val found = TtsModelManager.findFiles(dir, NeuralKind.Piper, preferInt8 = true)!!
        assertEquals("model.int8.onnx", found.onnx.name)
    }

    @Test
    fun preferInt8FallsBackToFp32WhenOnlyFp32Exists() {
        val dir = File(createTempDir(), "pack").also { it.mkdirs() }
        piperLayout(dir, int8 = false)
        val found = TtsModelManager.findFiles(dir, NeuralKind.Piper, preferInt8 = true)!!
        assertEquals("model.onnx", found.onnx.name)
    }

    @Test
    fun piperDoesNotNeedVoicesBin() {
        val dir = File(createTempDir(), "id").also { it.mkdirs() }
        piperLayout(dir, onnxName = "id_ID-news_tts-medium.onnx", int8 = false)
        val found = TtsModelManager.findFiles(dir, NeuralKind.Piper)!!
        assertEquals(NeuralKind.Piper, found.kind)
        assertNull(found.voices)
    }

    @Test
    fun missingTokensIsNotAPack() {
        val dir = File(createTempDir(), "bad").also { it.mkdirs() }
        File(dir, "model.onnx").writeText("m")
        File(dir, "espeak-ng-data").mkdirs()
        File(dir, "espeak-ng-data/phontab").writeText("ph")
        File(dir, "espeak-ng-data/phonindex").writeText("idx")
        assertNull(TtsModelManager.findFiles(dir, NeuralKind.Piper))
    }

    @Test
    fun missingPhonindexIsNotAPack() {
        val dir = File(createTempDir(), "bad").also { it.mkdirs() }
        File(dir, "model.onnx").writeText("m")
        File(dir, "tokens.txt").writeText("t")
        File(dir, "espeak-ng-data").mkdirs()
        File(dir, "espeak-ng-data/phontab").writeText("ph")
        assertNull(TtsModelManager.findFiles(dir, NeuralKind.Piper))
    }
}
