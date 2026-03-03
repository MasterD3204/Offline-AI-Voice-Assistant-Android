package com.k2fsa.sherpa.onnx.simulate.streaming.asr.llm

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

// Interface Callback để nhận token từ C++
interface StreamCallback {
    fun onToken(token: String)
    fun onToolCall(name: String, argsJson: String)
    fun onDone()
    fun onError(message: String)
}

class NativeLib private constructor() {

    companion object {
        private const val TAG = "NativeLib"
        
        // Load thư viện C++ "ai_core" mà ta đã cấu hình ở bước CMake
        init { System.loadLibrary("ai_core") }

        @Volatile
        private var instance: NativeLib? = null

        fun getInstance(): NativeLib =
            instance ?: synchronized(this) {
                instance ?: NativeLib().also { instance = it }
            }
    }

    private var isModelInitialized = false
    private var coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // --- CÁC HÀM NATIVE (JNI) GIỮ LẠI ---

    // 1. Init Model
    external fun nativeInit(
        path: String,
        threads: Int,
        ctxSize: Int,
        temp: Float,
        topK: Int,
        topP: Float,
        minP: Float,
        mirostat: Int,
        mirostatTau: Float,
        mirostatEta: Float,
        seed: Int
    ): Boolean

    // 2. Generate
    external fun nativeGenerateStream(
        prompt: String,
        maxTokens: Int,
        callback: StreamCallback
    ): Boolean

    // 3. Control & Config
    external fun nativeRelease(): Boolean
    external fun nativeStopGeneration()
    external fun nativeSetSystemPrompt(prompt: String)
    external fun nativeSetChatTemplate(template: String)
    external fun nativeSetToolsJson(toolsJson: String) // Quan trọng cho Tool Calling

    // --- HÀM WRAPPER KOTLIN ---

    fun init(
        path: String,
        threads: Int = 4,
        ctxSize: Int = 2048,
        temp: Float = 0.7f,
        topK: Int = 40,
        topP: Float = 0.9f
    ): Boolean {
        // Release model cũ nếu có
        if (isModelInitialized) nativeRelease()
        
        return try {
            // Gọi hàm native với các tham số mặc định cho các phần ít dùng
            val ok = nativeInit(
                path, threads, ctxSize, temp, topK, topP, 
                0.0f, 0, 0.1f, 0.1f, -1
            )
            isModelInitialized = ok
            if (ok) Log.i(TAG, "LLM Initialized: $path")
            else Log.e(TAG, "LLM Init Failed")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "Init Error", e)
            false
        }
    }

    suspend fun generateStreaming(
        prompt: String,
        callback: StreamCallback,
        maxTokens: Int = 512,
        toolsJson: String = ""
    ) {
        if (!isModelInitialized) {
            callback.onError("Model not initialized")
            return
        }

        // Cập nhật Tools nếu có
        nativeSetToolsJson(toolsJson)

        // Chạy trên luồng IO để không chặn UI, nhưng Native C++ sẽ block thread này
        withContext(Dispatchers.IO) {
            val success = nativeGenerateStream(prompt, maxTokens, callback)
            if (!success) {
                callback.onError("Native generation returned false")
            }
        }
    }

    fun stop() {
        nativeStopGeneration()
    }
    
    fun release() {
        nativeRelease()
        isModelInitialized = false
    }
}