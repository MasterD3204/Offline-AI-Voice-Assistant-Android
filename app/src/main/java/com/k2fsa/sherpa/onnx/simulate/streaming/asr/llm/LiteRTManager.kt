package com.k2fsa.sherpa.onnx.simulate.streaming.asr.llm

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.File
import java.util.concurrent.CancellationException
class LiteRTManager(private val context: Context) {
    companion object {
        private const val TAG = "LiteRTManager"
        private const val MAX_TOKENS = 512
        private const val TEMPERATURE = 0.1f
        private const val TOP_K = 8
        private const val TOP_P = 0.95f
    }

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var isInitialized = false

    private val systemPrompt = "Bạn là trợ lý ảo trả lời ngắn gọn tất cả câu hỏi."

    fun isReady(): Boolean = isInitialized && engine != null && conversation != null

    fun init(modelPath: String): Boolean {
        if (isInitialized) return true

        Log.d(TAG, "Initializing LiteRT with model: $modelPath")
        val file = File(modelPath)
        if (!file.exists()) {
            Log.e(TAG, "Model file not found at: $modelPath")
            return false
        }

        // FIX: Dùng GPU trực tiếp như code base Google (không cần try-fallback)
        return try {
            val engineConfig = EngineConfig(
                modelPath = modelPath,
                backend = Backend.GPU,
                maxNumTokens = MAX_TOKENS,
                // FIX CHÍNH: cacheDir phải = null khi model KHÔNG nằm ở /data/local/tmp
                // Đây là nguyên nhân gây lỗi OpenCL
                cacheDir = if (modelPath.startsWith("/data/local/tmp")) {
                    context.getExternalFilesDir(null)?.absolutePath
                } else {
                    null
                }
            )

            val eng = Engine(engineConfig)
            eng.initialize()

            val config = ConversationConfig(
                samplerConfig = SamplerConfig(
                    topK = TOP_K,
                    topP = TOP_P.toDouble(),
                    temperature = TEMPERATURE.toDouble()
                ),
                systemInstruction = Contents.of(Content.Text(systemPrompt))
            )

            val conv = eng.createConversation(config)

            engine = eng
            conversation = conv
            isInitialized = true
            Log.i(TAG, "LiteRT initialized with GPU backend")
            true

        } catch (e: Exception) {
            Log.e(TAG, "GPU init failed, trying CPU fallback: ${e.message}", e)
            // Fallback CPU nếu GPU thực sự fail
            initWithCpu(modelPath)
        }
    }

    private fun initWithCpu(modelPath: String): Boolean {
        return try {
            val engineConfig = EngineConfig(
                modelPath = modelPath,
                backend = Backend.CPU,
                maxNumTokens = MAX_TOKENS,
                cacheDir = null
            )

            val eng = Engine(engineConfig)
            eng.initialize()

            val config = ConversationConfig(
                samplerConfig = SamplerConfig(
                    topK = TOP_K,
                    topP = TOP_P.toDouble(),
                    temperature = TEMPERATURE.toDouble()
                ),
                systemInstruction = Contents.of(Content.Text(systemPrompt))
            )

            val conv = eng.createConversation(config)

            engine = eng
            conversation = conv
            isInitialized = true
            Log.i(TAG, "LiteRT initialized with CPU fallback")
            true

        } catch (e: Exception) {
            Log.e(TAG, "CPU init also failed: ${e.message}", e)
            false
        }
    }

    fun chatStream(query: String): Flow<String> = callbackFlow {
        if (!isReady()) {
            trySend("Lỗi: Model chưa được khởi tạo.")
            close()
            return@callbackFlow
        }

        Log.d(TAG, "Sending query: $query")
        val inputContent = Contents.of(Content.Text(query))

        conversation?.sendMessageAsync(inputContent, object : MessageCallback {
            override fun onMessage(message: Message) {
                trySend(message.toString())
            }

            override fun onDone() {
                Log.d(TAG, "Inference Done")
                close()
            }

            override fun onError(throwable: Throwable) {
                if (throwable is CancellationException) {
                    Log.i(TAG, "Inference cancelled")
                    close()
                } else {
                    Log.e(TAG, "LiteRT Error", throwable)
                    trySend("\n[Lỗi: ${throwable.message}]")
                    close()
                }
            }
        })

        awaitClose { Log.d(TAG, "Flow closed") }
    }

    fun release() {
        try { conversation?.close() } catch (e: Exception) { Log.e(TAG, "Error closing conversation", e) }
        try { engine?.close() } catch (e: Exception) { Log.e(TAG, "Error closing engine", e) }
        isInitialized = false
        engine = null
        conversation = null
    }
}
