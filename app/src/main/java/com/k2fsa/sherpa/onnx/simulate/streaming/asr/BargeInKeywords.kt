package com.k2fsa.sherpa.onnx.simulate.streaming.asr

/**
 * Danh sách từ khóa "acknowledgment" - khi user nói những từ này,
 * bot trả lời nhanh mà KHÔNG cần qua LLM/QA pipeline.
 *
 * ★ DỄ MỞ RỘNG: Chỉ cần thêm từ vào list bên dưới.
 */
object BargeInKeywords {

    // ── Danh sách từ khóa (lowercase, trim) ──
    // Thêm từ mới vào đây:
    private val ACKNOWLEDGMENT_KEYWORDS = listOf(
        "ok",
        "ok luôn",
        "okay",
        "được rồi",
        "được",
        "ừ",
        "ừm",
        "uhm",
        "uh huh",
        "vâng",
        "dạ",
        "dạ vâng",
        "cảm ơn",
        "cám ơn",
        "thanks",
        "thank you",
        "tốt",
        "tốt rồi",
        "hiểu rồi",
        "rõ rồi",
        "biết rồi",
        "thôi",
        "thôi được rồi",
        "không cần nữa",
        "đủ rồi",
        "ổn rồi",
    )

    // ── Response mặc định ──
    const val ACKNOWLEDGMENT_RESPONSE =
        "Rất vui được giúp đỡ bạn, nếu bạn còn thắc mắc gì, vui lòng cho tôi biết nhé."

    /**
     * Kiểm tra xem text có phải là acknowledgment keyword không.
     * So sánh sau khi lowercase + trim.
     * Hỗ trợ cả exact match và contains cho các từ ngắn.
     */
    fun isAcknowledgment(text: String): Boolean {
        val normalized = text.trim().lowercase()
            .removeSuffix(".")
            .removeSuffix("!")
            .trim()

        return ACKNOWLEDGMENT_KEYWORDS.any { keyword ->
            normalized == keyword || normalized == "$keyword nhé"
        }
    }
}
