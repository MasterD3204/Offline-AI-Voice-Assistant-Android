package com.k2fsa.sherpa.onnx.simulate.streaming.asr.utils

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Normalize tên sản phẩm MISA cho TTS.
 * Load dictionary từ assets/misa_product.json,
 * gộp tất cả categories (spelling out, transliteration, third party)
 * thành 1 map duy nhất, rồi replace trong text (ưu tiên cụm dài nhất trước).
 */
class MisaProductNormalizer(context: Context) {

    companion object {
        private const val TAG = "MisaProductNormalizer"
        private const val ASSET_FILE = "misa_product.json"
    }

    // Map đã merge & sắp xếp theo độ dài key giảm dần
    private val replacementMap: List<Pair<String, String>>

    init {
        val merged = mutableMapOf<String, String>()

        try {
            val jsonStr = context.assets.open(ASSET_FILE)
                .bufferedReader().use { it.readText() }
            val root = JSONObject(jsonStr)

            // Duyệt qua tất cả category: "spelling out", "transliteration", "third party"
            root.keys().forEach { category ->
                val categoryObj = root.getJSONObject(category)
                categoryObj.keys().forEach { key ->
                    val value = categoryObj.getString(key)
                    val lowerKey = key.lowercase().trim()
                    // Nếu trùng key, ưu tiên giữ entry đầu tiên (hoặc bạn có thể đổi logic)
                    if (!merged.containsKey(lowerKey)) {
                        merged[lowerKey] = value
                    }
                }
            }

            Log.i(TAG, "Loaded ${merged.size} replacement entries from $ASSET_FILE")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load $ASSET_FILE", e)
        }

        // Sắp xếp theo độ dài key GIẢM DẦN → match cụm dài nhất trước
        // Ví dụ: "amis kế toán doanh nghiệp" phải match trước "amis kế toán" và trước "amis"
        replacementMap = merged.entries
            .sortedByDescending { it.key.length }
            .map { Pair(it.key, it.value) }
    }

    /**
     * Normalize text cho TTS:
     * - Lowercase toàn bộ
     * - Tìm & thay thế theo dictionary (longest match first, whole word boundary)
     *
     * @param text Text gốc từ LLM/QA
     * @return Text đã normalize cho TTS
     */
    fun normalizeForTTS(text: String): String {
        var result = text.lowercase()

        for ((key, value) in replacementMap) {
            // Dùng word boundary để tránh replace nhầm substring
            // Ví dụ: "amis" không replace bên trong "amiserable"
            // Regex.escape để handle ký tự đặc biệt như ".", "/"
            val pattern = "\\b${Regex.escape(key)}\\b"
            val regex = Regex(pattern, RegexOption.IGNORE_CASE)
            result = regex.replace(result, value)
        }

        return result
    }
}
