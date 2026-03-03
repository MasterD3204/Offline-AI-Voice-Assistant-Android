// đây là file chạy ổn
package com.k2fsa.sherpa.onnx.simulate.streaming.asr.screens

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.TAG
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.VoiceBotManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.PerfMetrics
@Composable
fun HomeScreen(voiceBotManager: VoiceBotManager) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Trạng thái: Người dùng có muốn bật chế độ nghe không?
    var isListeningEnabled by remember { mutableStateOf(false) }

    // Trạng thái thực tế: Mic đang bật hay tắt?
    var isMicActive by remember { mutableStateOf(false) }

    val resultList = remember { mutableStateListOf<String>() }
    val lazyColumnListState = rememberLazyListState()

    var perfMetrics by remember { mutableStateOf(PerfMetrics()) }
    // Init VoiceBot
    LaunchedEffect(Unit) {
        withContext(Dispatchers.Default) {
            voiceBotManager.onLog = { message, isUpdate ->
                if (isUpdate) {
                    if (resultList.isNotEmpty()) resultList[resultList.size - 1] = message
                } else {
                    resultList.add(message)
                }
            }
            voiceBotManager.onMetricsUpdate = { metrics ->
                perfMetrics = metrics // Trigger recompose
            }
            voiceBotManager.init()
        }
    }

    // Auto scroll
    LaunchedEffect(resultList.size) {
        if (resultList.isNotEmpty()) lazyColumnListState.animateScrollToItem(resultList.size - 1)
    }

    // Speech Recognizer Setup
    val speechRecognizer = remember { SpeechRecognizer.createSpeechRecognizer(context) }
    val speechIntent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
    }

    fun startListening() {
        if (!isListeningEnabled) return
        try {
            // Quan trọng: Chỉ start nếu Bot KHÔNG nói
            if (!voiceBotManager.isBotBusy) {
                speechRecognizer.startListening(speechIntent)
                isMicActive = true
                Log.d(TAG, "🎙️ Start Listening")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Start error", e)
            isMicActive = false
        }
    }

    fun stopListening() {
        try {
            speechRecognizer.stopListening()
            isMicActive = false
            Log.d(TAG, "🛑 Stop Listening")
        } catch (e: Exception) {
            Log.e(TAG, "Stop error", e)
        }
    }

    // Listener
    DisposableEffect(Unit) {
        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { isMicActive = false }
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                // Streaming text (optional)
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull()
                if (!text.isNullOrBlank()) {
                    Log.i(TAG, "🗣️ Recognized: $text")
                    voiceBotManager.onUserSpeechFinalized(text)
                }
                // Sau khi nhận kết quả, Mic tự tắt. Logic polling bên dưới sẽ bật lại.
                isMicActive = false
            }

            override fun onError(error: Int) {
                val msg = when(error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "No match"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Timeout"
                    else -> "Error $error"
                }
                Log.w(TAG, "⚠️ Speech Error: $msg")
                isMicActive = false
            }
        }
        speechRecognizer.setRecognitionListener(listener)
        onDispose { speechRecognizer.destroy() }
    }

    // 🔴 LOGIC TỰ ĐỘNG BẬT/TẮT MIC (Thay thế LaunchedEffect biến)
    LaunchedEffect(isListeningEnabled) {
        if (!isListeningEnabled) {
            stopListening()
            return@LaunchedEffect
        }

        // Vòng lặp kiểm tra trạng thái mỗi 200ms
        while (isListeningEnabled) {
            val botSpeaking = voiceBotManager.isBotBusy // Lấy giá trị mới nhất

            if (botSpeaking) {
                if (isMicActive) {
                    // Bot bắt đầu nói -> Tắt Mic ngay
                    stopListening()
                }
            } else {
                if (!isMicActive) {
                    // Bot im lặng và Mic đang tắt -> Bật Mic lên
                    startListening()
                }
            }

            delay(200) // Polling interval
        }
    }

    // Permission Logic
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) { isListeningEnabled = true }
        else { Toast.makeText(context, "Cần quyền Micro", Toast.LENGTH_SHORT).show() }
    }

    // UI Layout
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                shape = MaterialTheme.shapes.small
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text("📊 Performance Metrics", style = MaterialTheme.typography.labelSmall)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        MetricItem("Query", "${perfMetrics.getQueryLatency()}ms")
                        MetricItem("LLM (TTFT)", "${perfMetrics.getLlmLatency()}ms")
                        MetricItem("TTS (TTFA)", "${perfMetrics.getTtsLatency()}ms")
                    }
                }
            }
            Text(
                text = if (isListeningEnabled) (if (isMicActive) "Đang nghe..." else "Đang xử lý...") else "Nhấn bắt đầu",
                modifier = Modifier.padding(16.dp).align(Alignment.CenterHorizontally),
                style = MaterialTheme.typography.titleMedium,
                color = if (isMicActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )

            LazyColumn(
                modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                state = lazyColumnListState,
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                itemsIndexed(resultList) { _, line -> ChatBubble(line) }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                Button(
                    onClick = {
                        if (isListeningEnabled) isListeningEnabled = false
                        else {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                isListeningEnabled = true
                            } else {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = if (isListeningEnabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                ) {
                    Text(if (isListeningEnabled) "🛑 Dừng" else "🎙️ Bắt đầu")
                }
                Spacer(modifier = Modifier.width(16.dp))
                Button(
                    onClick = {
                        // 1. Xóa giao diện Chat
                        resultList.clear()

                        // 2. Reset trạng thái Bot (Dừng nói, dừng suy nghĩ)
                        voiceBotManager.reset()

                        // 3. (Optional) Nếu đang nghe thì cũng dừng nghe luôn cho sạch
                        if (isListeningEnabled) {
                            stopListening()
                            isListeningEnabled = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Text("Xóa & Reset")
                }
            }
        }
    }
}

@Composable
fun ChatBubble(rawText: String) {
    // 1. Phân loại người nói và tách nội dung
    val isUser = rawText.startsWith("User:")
    val isBot = rawText.startsWith("Bot:")

    // Loại bỏ tiền tố "User: " hoặc "Bot: " để hiển thị nội dung sạch
    val content = if (isUser) rawText.removePrefix("User:").trim()
    else if (isBot) rawText.removePrefix("Bot:").trim()
    else rawText

    // 2. Cấu hình Giao diện
    val align = if (isUser) Arrangement.End else Arrangement.Start

    // Màu sắc: User màu nổi (Primary), Bot màu nền nhẹ (Surface/Gray)
    val backgroundColor = if (isUser) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceVariant

    val contentColor = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurface

    // Bo góc:
    // User: Bo tròn hết trừ góc dưới phải
    // Bot: Bo tròn hết trừ góc trên trái
    val bubbleShape = if (isUser) {
        RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp)
    } else {
        RoundedCornerShape(4.dp, 20.dp, 20.dp, 20.dp)
    }

    // 3. Layout chính
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp), // Khoảng cách giữa các tin nhắn
        horizontalArrangement = align,
        verticalAlignment = Alignment.Bottom // Avatar nằm dưới cùng
    ) {
        // --- AVATAR CHO BOT (Bên trái) ---
        if (!isUser) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SmartToy,
                    contentDescription = "Bot Avatar",
                    tint = Color.White,
                    modifier = Modifier.padding(6.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
        }

        // --- KHUNG CHAT (BUBBLE) ---
        // Dùng SelectionContainer để người dùng có thể bôi đen copy text của Bot
        SelectionContainer {
            Surface(
                shape = bubbleShape,
                color = backgroundColor,
                shadowElevation = 1.dp, // Đổ bóng nhẹ cho nổi
                // Bot chiếm tối đa 90% màn hình, User chiếm 85%
                modifier = Modifier.widthIn(max = if (isUser) 300.dp else 360.dp)
                    .weight(1f, fill = false) // Co giãn linh hoạt
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    // Tên người nói (Optional - có thể bỏ nếu thấy rườm rà)
                    Text(
                        text = if (isUser) "Bạn" else "AI Assistant",
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.7f),
                        modifier = Modifier.padding(bottom = 4.dp)
                    )

                    // Nội dung chính
                    Text(
                        text = content,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            lineHeight = 24.sp, // Tăng khoảng cách dòng cho dễ đọc
                            fontWeight = FontWeight.Normal
                        ),
                        color = contentColor
                    )
                }
            }
        }

        // --- AVATAR CHO USER (Bên phải - Optional) ---
        if (isUser) {
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = "User Avatar",
                    tint = Color.White,
                    modifier = Modifier.padding(6.dp)
                )
            }
        }
    }
}

@Composable
fun MetricItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Text(text = label, style = MaterialTheme.typography.bodySmall)
    }
}