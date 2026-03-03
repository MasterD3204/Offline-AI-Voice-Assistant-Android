
package com.k2fsa.sherpa.onnx.simulate.streaming.asr

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.screens.HomeScreen
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.ui.theme.SimulateStreamingAsrTheme
import misa.agentplatform.avatarsdktest.FileStorageHelper
import java.io.File

const val TAG = "sherpa-onnx-bot"
private const val REQUEST_RECORD_AUDIO_PERMISSION = 200

@Suppress("DEPRECATION")
class MainActivity : ComponentActivity() {

    companion object{
        const val REQUEST_STORAGE_PERMISSION =10002
    }
    private val permissions: Array<String> = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_EXTERNAL_STORAGE, // Thêm dòng này
        Manifest.permission.WRITE_EXTERNAL_STORAGE
        )

    // Khai báo Manager
    private lateinit var voiceBotManager: VoiceBotManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()



        // 1. Khởi tạo VoiceBotManager
        voiceBotManager = VoiceBotManager(this)

        setContent {
            SimulateStreamingAsrTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // 2. Truyền Manager xuống màn hình chính
                    MainScreen(voiceBotManager)
                }
            }
        }
        ActivityCompat.requestPermissions(this, permissions, REQUEST_RECORD_AUDIO_PERMISSION)
        // Kiểm tra và request storage permissions
        checkStoragePermission()

        // Load file GGUF từ /sdcard/
        //loadGGUFFile()
    }

    private fun loadGGUFFile() {
        val fileHelper = FileStorageHelper(this)

        // Thử lấy file theo 3 cách
        var ggufFile: File? = null

        // Cách 1: Đường dẫn tuyệt đối
        ggufFile = fileHelper.getFileFromPath("/sdcard/Qwen3-0.6B-IQ4_XS.gguf")

        if (ggufFile == null) {
            // Cách 2: Thử đường dẫn đầy đủ
            ggufFile = fileHelper.getFileFromPath("/storage/emulated/0/Qwen3-0.6B-IQ4_XS.gguf")
        }

        if (ggufFile == null) {
            // Cách 3: Tìm theo tên file trong External Storage
            ggufFile = fileHelper.getFileFromExternalStorage("Qwen3-0.6B-IQ4_XS.gguf")
        }

        if (ggufFile != null && ggufFile.exists()) {
            val fileInfo = fileHelper.getFileInfo(ggufFile)
            Log.i(TAG, "✅ GGUF Model file loaded successfully!")
            Log.i(TAG, "📍 Path: ${fileInfo.path}")
            Log.i(TAG, "📊 Size: ${String.format("%.2f", fileInfo.sizeInMB)} MB (${fileInfo.size} bytes)")

            Toast.makeText(
                this,
                "Model loaded: ${fileInfo.name}\nSize: ${String.format("%.2f", fileInfo.sizeInMB)} MB",
                Toast.LENGTH_LONG
            ).show()

            // TODO: Sử dụng file ở đây để load model
            // Ví dụ: loadModelWithPath(ggufFile.absolutePath)

        } else {
            Log.e(TAG, "❌ GGUF Model file not found!")
            Log.e(TAG, "   Searched locations:")
            Log.e(TAG, "   - /sdcard/Qwen3-0.6B-IQ4_XS.gguf")
            Log.e(TAG, "   - /storage/emulated/0/Qwen3-0.6B-IQ4_XS.gguf")
            Log.e(TAG, "   - Download/, Documents/, etc.")

            Toast.makeText(
                this,
                "Model file not found at /sdcard/Qwen3-0.6B-IQ4_XS.gguf\nPlease check the file exists.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ (API 30+): Cần MANAGE_EXTERNAL_STORAGE
            if (!Environment.isExternalStorageManager()) {
                Log.w(TAG, "⚠️ MANAGE_EXTERNAL_STORAGE permission not granted")
                Toast.makeText(
                    this,
                    "App needs storage permission to access model files. Please grant permission.",
                    Toast.LENGTH_LONG
                ).show()

                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Error opening settings", e)
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                }
            } else {
                Log.i(TAG, "✅ MANAGE_EXTERNAL_STORAGE permission granted")
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Android 6-10: Cần READ_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {

                Log.w(TAG, "⚠️ READ_EXTERNAL_STORAGE permission not granted")
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ),
                    REQUEST_STORAGE_PERMISSION
                )
            } else {
                Log.i(TAG, "✅ READ_EXTERNAL_STORAGE permission granted")
            }
        } else {
            // Android 5 và thấp hơn: Không cần runtime permission
            Log.i(TAG, "✅ No runtime permission needed (Android < 6)")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 3. Dọn dẹp tài nguyên khi thoát app
        if (::voiceBotManager.isInitialized) {
            voiceBotManager.release()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val permissionToRecordAccepted = if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION) {
            grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        } else {
            false
        }

        if (!permissionToRecordAccepted) {
            Log.e(TAG, "Audio record is disallowed")
            Toast.makeText(this, "App cần quyền Micro để hoạt động", Toast.LENGTH_SHORT).show()
            finish()
        }
        Log.i(TAG, "Audio record is permitted")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(voiceBotManager: VoiceBotManager, modifier: Modifier = Modifier) {
    // Truyền voiceBotManager vào HomeScreen
    HomeScreen(voiceBotManager = voiceBotManager)
}