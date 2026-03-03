package com.k2fsa.sherpa.onnx.simulate.streaming.asr

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.llm.LiteRTManager
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.llm.QAEngine
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.tts.AndroidTTSManager
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.utils.MisaProductNormalizer
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.utils.NumberNormalizer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

data class PerfMetrics(
    var sttEndTime: Long = 0,
    var llmFirstTokenTime: Long = 0,
    var ttsFirstAudioTime: Long = 0,
    var queryEndTime: Long = 0,
    var firstChunkReceivedTime: Long = 0
) {
    fun getSttLatency(): Long = 0
    fun getLlmLatency(): Long = if (llmFirstTokenTime > sttEndTime) llmFirstTokenTime - sttEndTime else 0
    fun getTtsLatency(): Long =
        if (ttsFirstAudioTime > firstChunkReceivedTime && firstChunkReceivedTime > 0)
            ttsFirstAudioTime - firstChunkReceivedTime
        else 0
    fun getQueryLatency(): Long = if (queryEndTime > sttEndTime) queryEndTime - sttEndTime else 0

    override fun toString(): String {
        return "⚡ TTFT: ${getLlmLatency()}ms | 🔊 TTFA: ${getTtsLatency()}ms | 🔍 Query: ${getQueryLatency()}ms"
    }
}

class VoiceBotManager(private val context: Context) {
    companion object {
        private const val TAG = "VoiceBotManager"
        private const val MODEL_NAME = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm"
    }

    private val ttsManager = AndroidTTSManager(context)
    private val qaEngine = QAEngine(context)
    private val liteRTManager = LiteRTManager(context)

    private val productNormalizer = MisaProductNormalizer(context)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentMetrics = PerfMetrics()

    var onMetricsUpdate: ((PerfMetrics) -> Unit)? = null
    @Volatile var isBotBusy = false
    var onLog: ((String, Boolean) -> Unit)? = null

    init {
        ttsManager.onSpeechStart = {
            if (currentMetrics.ttsFirstAudioTime == 0L) {
                currentMetrics.ttsFirstAudioTime = System.currentTimeMillis()
                notifyMetrics()
            }
            isBotBusy = true
        }
        ttsManager.onSpeechDone = {
            if (!ttsManager.isSpeaking()) isBotBusy = false
            Log.d(TAG, "TTS utterance done, queue may still have items")
        }
    }

    fun init() {
        scope.launch {
            logToUI("System: Initializing...", false)

            val qaFile = "qa_database.txt"
            val vecFile = "vi_fasttext_pruned.vec"
            if (assetExists(qaFile)) {
                logToUI("System: Loading QA Data...", false)
                qaEngine.initialize(qaFile, vecFile)
            }

            logToUI("System: Loading LLM Model...", false)
            val modelPath = getModelPath()
            if (modelPath != null) {
                val success = liteRTManager.init(modelPath)
                if (success) {
                    logToUI("✅ Bot Ready (Local LLM)!", false)
                } else {
                    logToUI("Error: Failed to init LLM.", false)
                }
            } else {
                logToUI("Error: Model .litertlm not found in Storage.", false)
                Log.e(TAG, "Please copy $MODEL_NAME to /sdcard/ or Downloads folder")
            }
        }
    }

    private fun getModelPath(): String? {
        val paths = listOf(
            "/sdcard/$MODEL_NAME",
            "/sdcard/Download/$MODEL_NAME",
            "/storage/emulated/0/$MODEL_NAME",
            "/storage/emulated/0/Download/$MODEL_NAME",
            context.getExternalFilesDir(null)?.absolutePath + "/$MODEL_NAME"
        )
        for (path in paths) {
            val file = File(path)
            if (file.exists()) {
                Log.i(TAG, "Found model at: $path")
                return path
            }
        }
        return null
    }

    private var processJob: Job? = null

    fun onUserSpeechFinalized(text: String) {
        if (text.isBlank() || isBotBusy) return

        currentMetrics = PerfMetrics()
        currentMetrics.sttEndTime = System.currentTimeMillis()
        isFirstSpeak = true
        notifyMetrics()

        val normalizedQuery = text.trim().replaceFirstChar { it.uppercaseChar() }
        logToUI("User: $normalizedQuery", false)

        isBotBusy = true

        processJob = scope.launch {
            logToUI("Bot: ", false)

            val searchText = text.trim().lowercase()

            val queryStartTime = System.currentTimeMillis()
            val localResponse = qaEngine.search(searchText)
            currentMetrics.queryEndTime = System.currentTimeMillis()
            notifyMetrics()

            val responseFlow: Flow<String> = if (localResponse != null) {
                Log.i(TAG, "╔═══════════════════════════════════════")
                Log.i(TAG, "║ ✅ HIT QA Database")
                Log.i(TAG, "║ Query   : $searchText")
                Log.i(TAG, "║ Response: ${localResponse.take(100)}${if (localResponse.length > 100) "..." else ""}")
                Log.i(TAG, "╚═══════════════════════════════════════")

                flow {
                    val words = localResponse.split(" ")
                    for (word in words) {
                        emit("$word ")
                        delay(50)
                    }
                }
            } else {
                Log.i(TAG, "╔═══════════════════════════════════════")
                Log.i(TAG, "║ ❌ MISS QA Database -> Fallback LLM")
                Log.i(TAG, "║ Query: $searchText")
                Log.i(TAG, "╚═══════════════════════════════════════")

                liteRTManager.chatStream(text)
            }

            processResponseFlow(responseFlow)
        }
    }

    private suspend fun processResponseFlow(flow: Flow<String>) {
        val fullTextBuilder = StringBuilder()
        val sentenceBuffer = StringBuilder()
        var sentenceCount = 0
        var isFirstToken = true

        try {
            flow.collect { chunk ->
                if (isFirstToken) {
                    val now = System.currentTimeMillis()
                    currentMetrics.llmFirstTokenTime = now
                    currentMetrics.firstChunkReceivedTime = now
                    notifyMetrics()
                    isFirstToken = false
                }
                fullTextBuilder.append(chunk)
                logToUI("Bot: ${fullTextBuilder.toString()}", true)

                sentenceBuffer.append(chunk)

                // ★ Chỉ cắt theo dấu câu (. ? ! , : \n)
                while (containsSentenceEnd(sentenceBuffer.toString())) {
                    val (sentence, remaining) = splitSentence(sentenceBuffer.toString())
                    if (sentence.isNotBlank()) {
                        speakSentence(sentence, sentenceCount++)
                    }
                    sentenceBuffer.clear()
                    sentenceBuffer.append(remaining)
                }
            }

            // Flush phần còn lại trong buffer
            if (sentenceBuffer.isNotBlank()) {
                speakSentence(sentenceBuffer.toString(), sentenceCount++)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Streaming Error", e)
            logToUI("Bot: [Lỗi] ${e.message}", true)
            isBotBusy = false
        }
    }

    private fun notifyMetrics() {
        CoroutineScope(Dispatchers.Main).launch {
            onMetricsUpdate?.invoke(currentMetrics)
        }
    }

    private var isFirstSpeak = true

    private fun speakSentence(text: String, id: Int) {
        if (isFirstSpeak) {
            currentMetrics.ttsFirstAudioTime = System.currentTimeMillis()
            notifyMetrics()
            isFirstSpeak = false
        }
        val normalizedText = NumberNormalizer.replaceNumbersWithText(text)
        ttsManager.speak(normalizedText, "utterance_$id")
    }

    private fun containsSentenceEnd(text: String): Boolean {
        return text.contains(".") || text.contains("?") || text.contains("!") ||
                text.contains("\n") || text.contains(",") || text.contains(":")
    }

    private fun splitSentence(text: String): Pair<String, String> {
        val match = Regex("[.?!,:\\n]").find(text) ?: return Pair("", text)
        val splitIndex = match.range.last + 1
        return Pair(text.substring(0, splitIndex).trim(), text.substring(splitIndex).trim())
    }

    private fun logToUI(msg: String, isUpdate: Boolean) {
        CoroutineScope(Dispatchers.Main).launch {
            onLog?.invoke(msg, isUpdate)
        }
    }

    private fun assetExists(fileName: String): Boolean {
        return try {
            context.assets.list("")?.contains(fileName) == true
        } catch (e: Exception) { false }
    }

    fun release() {
        scope.cancel()
        ttsManager.shutdown()
        qaEngine.release()
        liteRTManager.release()
    }

    fun reset() {
        Log.i(TAG, "🛑 RESETTING BOT STATE...")
        processJob?.cancel()
        processJob = null
        ttsManager.stop()
        isBotBusy = false
        currentMetrics = PerfMetrics()
        notifyMetrics()
        Log.i(TAG, "✅ Bot State Reset Complete")
    }
}
