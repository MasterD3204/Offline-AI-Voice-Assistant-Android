package com.k2fsa.sherpa.onnx.simulate.streaming.asr.llm

import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

class GeminiHelper(private val apiKey: String) {
    private val model = GenerativeModel(modelName = "gemini-2.5-flash", apiKey = apiKey)

    // Hàm trả về Flow để streaming
    fun chatStream(query: String): Flow<String> = flow {
        val systemInstruction = "Bạn là trợ lý ảo hữu ích. Trả lời ngắn gọn bằng tiếng Việt."
        val prompt = "$systemInstruction\nUser hỏi: $query"
        
        try {
            // Streaming từ Google
            val responseStream = model.generateContentStream(prompt)
            responseStream.collect { chunk ->
                chunk.text?.let { emit(it) }
            }
        } catch (e: Exception) {
            Log.e("GeminiHelper", "Error", e)
            emit("Xin lỗi, có lỗi kết nối mạng.")
        }
    }.flowOn(Dispatchers.IO)
}