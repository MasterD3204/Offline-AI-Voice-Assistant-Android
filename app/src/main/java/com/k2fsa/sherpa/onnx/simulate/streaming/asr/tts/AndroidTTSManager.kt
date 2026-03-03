//file này chạy ô
package com.k2fsa.sherpa.onnx.simulate.streaming.asr.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

class AndroidTTSManager(context: Context) {
    companion object {
        private const val TAG = "AndroidTTS"
    }

    private var tts: TextToSpeech? = null
    private var isInitialized = false

    // Callback khi bắt đầu/kết thúc nói 1 câu
    var onSpeechStart: (() -> Unit)? = null
    var onSpeechDone: (() -> Unit)? = null

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale("vi", "VN"))

                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e(TAG, "Tiếng Việt không được hỗ trợ trên máy này!")
                } else {
                    isInitialized = true
                    Log.i(TAG, "Android TTS Initialized Successfully")
                    setupListener()
                }
            } else {
                Log.e(TAG, "Init Failed")
            }
        }
    }

    private fun setupListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.d(TAG, "TTS Start: $utteranceId")
                onSpeechStart?.invoke()
            }

            override fun onDone(utteranceId: String?) {
                Log.d(TAG, "TTS Done: $utteranceId")
                onSpeechDone?.invoke()
            }

            override fun onError(utteranceId: String?) {
                Log.e(TAG, "TTS Error: $utteranceId")
                onSpeechDone?.invoke() // Coi như xong để không bị treo
            }
        })
    }

    fun speak(text: String, id: String) {
        if (!isInitialized) return

        // QUEUE_ADD: Thêm vào hàng đợi (nói xong câu trước mới nói câu này)
        // QUEUE_FLUSH: Ngắt câu đang nói để nói câu này ngay
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, id)
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.shutdown()
    }

    fun isSpeaking(): Boolean {
        return tts?.isSpeaking ?: false
    }
}