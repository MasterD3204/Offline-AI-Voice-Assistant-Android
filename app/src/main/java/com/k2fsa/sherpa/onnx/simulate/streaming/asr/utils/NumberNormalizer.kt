package com.k2fsa.sherpa.onnx.simulate.streaming.asr.utils

import java.text.DecimalFormat

object NumberNormalizer {
    private val digits = arrayOf("không", "một", "hai", "ba", "bốn", "năm", "sáu", "bảy", "tám", "chín")

    fun replaceNumbersWithText(text: String): String {
        // Tìm tất cả các con số trong chuỗi
        val regex = Regex("\\d+")
        return regex.replace(text) { matchResult ->
            val numberStr = matchResult.value
            try {
                // Nếu số quá dài, đọc từng số một (ví dụ số điện thoại)
                if (numberStr.length > 9) {
                    numberStr.map { digits[it.toString().toInt()] }.joinToString(" ")
                } else {
                    // Đọc theo kiểu số đếm (ví dụ: 100 -> một trăm)
                    readNumber(numberStr.toLong())
                }
            } catch (e: Exception) {
                numberStr // Fallback nếu lỗi
            }
        }
    }

    private fun readNumber(number: Long): String {
        if (number == 0L) return digits[0]
        var n = number
        val str = StringBuilder()
        
        val billions = n / 1_000_000_000
        if (billions > 0) {
            str.append(readTriple(billions.toInt())).append(" tỷ ")
            n %= 1_000_000_000
        }

        val millions = n / 1_000_000
        if (millions > 0) {
            str.append(readTriple(millions.toInt())).append(" triệu ")
            n %= 1_000_000
        }

        val thousands = n / 1_000
        if (thousands > 0) {
            str.append(readTriple(thousands.toInt())).append(" nghìn ")
            n %= 1_000
        }

        if (n > 0) {
            str.append(readTriple(n.toInt()))
        }

        return str.toString().trim()
    }

    private fun readTriple(num: Int): String {
        var n = num
        val str = StringBuilder()
        val hundreds = n / 100
        val tens = (n % 100) / 10
        val units = n % 10

        if (hundreds > 0) {
            str.append(digits[hundreds]).append(" trăm ")
            if (tens == 0 && units > 0) str.append("linh ")
        }

        if (tens > 1) {
            str.append(digits[tens]).append(" mươi ")
            if (units == 1) str.append("mốt ")
            else if (units == 5) str.append("lăm ")
            else if (units > 0) str.append(digits[units])
        } else if (tens == 1) {
            str.append("mười ")
            if (units == 1) str.append("một ")
            else if (units == 5) str.append("lăm ")
            else if (units > 0) str.append(digits[units])
        } else if (tens == 0 && units > 0) {
            str.append(digits[units])
        }
        return str.toString()
    }
}