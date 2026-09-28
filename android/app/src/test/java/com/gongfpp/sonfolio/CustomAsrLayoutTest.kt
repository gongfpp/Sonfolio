package com.gongfpp.sonfolio

import com.gongfpp.sonfolio.processing.LocalAsrEngine
import com.gongfpp.sonfolio.processing.detectAsrLayout
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CustomAsrLayoutTest {
    @get:Rule val temp = TemporaryFolder()

    private fun file(relative: String, bytes: Int = 16): File {
        val target = File(temp.root, relative)
        target.parentFile?.mkdirs()
        target.writeBytes(ByteArray(bytes))
        return target
    }

    @Test fun `sensevoice needs a model file`() {
        assertNull(detectAsrLayout(temp.root, LocalAsrEngine.SENSE_VOICE))
        file("model.int8.onnx")
        assertEquals("model.int8.onnx", detectAsrLayout(temp.root, LocalAsrEngine.SENSE_VOICE)?.model)
    }

    @Test fun `sensevoice accepts plain model onnx`() {
        file("weights/model.onnx")
        assertEquals("weights/model.onnx", detectAsrLayout(temp.root, LocalAsrEngine.SENSE_VOICE)?.model)
    }

    @Test fun `firered needs both model and tokens`() {
        file("model.int8.onnx")
        assertNull(detectAsrLayout(temp.root, LocalAsrEngine.FIRE_RED_ASR_CTC))
        file("tokens.txt")
        val layout = detectAsrLayout(temp.root, LocalAsrEngine.FIRE_RED_ASR_CTC)
        assertEquals("model.int8.onnx", layout?.model)
        assertEquals("tokens.txt", layout?.tokens)
    }

    @Test fun `qwen3 requires all files and tokenizer contents`() {
        file("conv_frontend.onnx")
        file("encoder.int8.onnx")
        file("decoder.int8.onnx")
        assertNull(detectAsrLayout(temp.root, LocalAsrEngine.QWEN3_ASR))
        file("tokenizer/merges.txt")
        file("tokenizer/vocab.json")
        assertNull(detectAsrLayout(temp.root, LocalAsrEngine.QWEN3_ASR))
        file("tokenizer/tokenizer_config.json")
        val layout = detectAsrLayout(temp.root, LocalAsrEngine.QWEN3_ASR)
        assertEquals("conv_frontend.onnx", layout?.convFrontend)
        assertEquals("encoder.int8.onnx", layout?.encoder)
        assertEquals("decoder.int8.onnx", layout?.decoder)
        assertEquals("tokenizer", layout?.tokenizerDir)
    }

    @Test fun `qwen3 resolves nested export directory`() {
        file("sherpa-onnx-qwen3-asr/conv_frontend.onnx")
        file("sherpa-onnx-qwen3-asr/encoder.onnx")
        file("sherpa-onnx-qwen3-asr/decoder.onnx")
        file("sherpa-onnx-qwen3-asr/tokenizer/merges.txt")
        file("sherpa-onnx-qwen3-asr/tokenizer/vocab.json")
        file("sherpa-onnx-qwen3-asr/tokenizer/tokenizer_config.json")
        val layout = detectAsrLayout(temp.root, LocalAsrEngine.QWEN3_ASR)
        assertEquals("sherpa-onnx-qwen3-asr/encoder.onnx", layout?.encoder)
        assertEquals("sherpa-onnx-qwen3-asr/tokenizer", layout?.tokenizerDir)
    }
}
