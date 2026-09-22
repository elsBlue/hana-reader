package com.hana.reader

import com.hana.reader.tts.NeuralKind
import com.hana.reader.tts.TtsModelManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class TtsModelFilesTest {
    @Test
    fun prefersFp32OnnxAndFindsPiperLayout() {
        val dir = File(createTempDir(), "pack").also { it.mkdirs() }
        File(dir, "model.onnx").writeText("full")
        File(dir, "model.int8.onnx").writeText("quant")
        File(dir, "tokens.txt").writeText("tokens")
        File(dir, "espeak-ng-data").mkdirs()
        File(dir, "espeak-ng-data/phontab").writeText("ph")
        val found = TtsModelManager.findFiles(dir, NeuralKind.Piper)!!
        assertEquals("model.onnx", found.onnx.name)
        assertEquals("espeak-ng-data", found.dataDir.name)
    }

    @Test
    fun piperDoesNotNeedVoicesBin() {
        val dir = File(createTempDir(), "id").also { it.mkdirs() }
        File(dir, "id_ID-news_tts-medium.onnx").writeText("m")
        File(dir, "tokens.txt").writeText("t")
        File(dir, "espeak-ng-data").mkdirs()
        val found = TtsModelManager.findFiles(dir, NeuralKind.Piper)!!
        assertEquals(NeuralKind.Piper, found.kind)
        assertNull(found.voices)
    }

    @Test
    fun missingTokensIsNotAPack() {
        val dir = File(createTempDir(), "bad").also { it.mkdirs() }
        File(dir, "model.onnx").writeText("m")
        File(dir, "espeak-ng-data").mkdirs()
        assertNull(TtsModelManager.findFiles(dir, NeuralKind.Piper))
    }
}
