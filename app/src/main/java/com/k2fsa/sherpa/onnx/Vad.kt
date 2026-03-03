// Copyright (c)  2023  Xiaomi Corporation
package com.k2fsa.sherpa.onnx

import android.content.res.AssetManager

data class SileroVadModelConfig(
    var model: String = "",
    var threshold: Float = 0.5F,
    var minSilenceDuration: Float = 0.25F,
    var minSpeechDuration: Float = 0.25F,
    var windowSize: Int = 512,
    var maxSpeechDuration: Float = 5.0F,
)

data class TenVadModelConfig(
    var model: String = "",
    var threshold: Float = 0.5F,
    var minSilenceDuration: Float = 0.25F,
    var minSpeechDuration: Float = 0.25F,
    var windowSize: Int = 256,
    var maxSpeechDuration: Float = 5.0F,
)

data class VadModelConfig(
    var sileroVadModelConfig: SileroVadModelConfig = SileroVadModelConfig(),
    var tenVadModelConfig: TenVadModelConfig = TenVadModelConfig(),
    var sampleRate: Int = 16000,
    var numThreads: Int = 1,
    var provider: String = "cpu",
    var debug: Boolean = false,
)

class SpeechSegment(val start: Int, val samples: FloatArray)




class Vad(
    assetManager: AssetManager? = null,
    config: VadModelConfig,
) {
    private var ptr: Long

    init {
        if (assetManager != null) {
            ptr = newFromAsset(assetManager, config)
        } else {
            ptr = newFromFile(config)
        }
    }

    protected fun finalize() {
        delete()
    }

    // --- SỬA Ở ĐÂY: Đổi từ private/protected thành public ---
    // Hoặc đơn giản là xóa từ khóa private đi
    fun delete() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0
        }
    }
    // -------------------------------------------------------

    fun acceptWaveform(samples: FloatArray) {
        acceptWaveform(ptr, samples)
    }

    fun empty(): Boolean {
        return empty(ptr)
    }

    fun pop() {
        pop(ptr)
    }

    fun isSpeechDetected(): Boolean {
        return isSpeechDetected(ptr)
    }

    fun reset() {
        reset(ptr)
    }

    fun front(): SpeechSegment {
        // Gọi hàm native trả về Object[]
        val objArray = frontNative(ptr)

        // Convert Object[] thành SpeechSegment
        val start = objArray[0] as Int
        val samples = objArray[1] as FloatArray

        return SpeechSegment(start = start, samples = samples)
    }

    fun flush() {
        flush(ptr)
    }

    private external fun delete(ptr: Long)

    private external fun newFromAsset(
        assetManager: AssetManager,
        config: VadModelConfig,
    ): Long

    private external fun newFromFile(
        config: VadModelConfig,
    ): Long

    private external fun acceptWaveform(ptr: Long, samples: FloatArray)
    private external fun empty(ptr: Long): Boolean
    private external fun pop(ptr: Long)
    private external fun isSpeechDetected(ptr: Long): Boolean
    private external fun reset(ptr: Long)
    private external fun front(ptr: Long): SpeechSegment
    // Đổi tên hàm native và kiểu trả về
    @JvmName("front")
    private external fun frontNative(ptr: Long): Array<Any>
    private external fun flush(ptr: Long)

    companion object {
        init {
            System.loadLibrary("sherpa-onnx-jni")
        }
    }
}

// Please visit
// https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx
// to download silero_vad.onnx
// and put it inside the assets/
// directory
//
// For ten-vad, please use
// https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/ten-vad.onnx
//
fun getVadModelConfig(type: Int): VadModelConfig? {
    when (type) {
        0 -> {
            return VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = "silero_vad.onnx",
                    threshold = 0.5F,
                    minSilenceDuration = 0.25F,
                    minSpeechDuration = 0.25F,
                    windowSize = 512,
                ),
                sampleRate = 16000,
                numThreads = 1,
                provider = "cpu",
            )
        }

        1 -> {
            return VadModelConfig(
                tenVadModelConfig = TenVadModelConfig(
                    model = "ten-vad.onnx",
                    threshold = 0.5F,
                    minSilenceDuration = 0.25F,
                    minSpeechDuration = 0.25F,
                    windowSize = 256,
                ),
                sampleRate = 16000,
                numThreads = 1,
                provider = "cpu",
            )
        }
    }
    return null
}
