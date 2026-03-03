package misa.agentplatform.avatarsdktest

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * FileStorageHelper - Utility class để lấy file từ bộ nhớ máy
 * 
 * Chức năng:
 * - Lấy file từ Assets
 * - Lấy file từ Internal Storage
 * - Lấy file từ External Storage
 * - Tìm file đệ quy trong thư mục
 * - Copy file từ Uri (content://)
 * - Mở File Picker
 * 
 * @author FileStorageHelper
 * @version 1.0
 */
class FileStorageHelper(private val context: Context) {
    
    companion object {
        private const val TAG = "FileStorageHelper"
        private const val PICK_FILE_REQUEST_CODE = 1001
    }
    
    /**
     * Lấy file từ Assets và copy sang Internal Storage (cache)
     * 
     * @param assetPath đường dẫn file trong assets (ví dụ: "models/model.gguf")
     * @param targetFileName tên file đích (mặc định giống tên gốc)
     * @return File object nếu thành công, null nếu thất bại
     * 
     * Ví dụ:
     * ```
     * val file = fileHelper.getFileFromAssets("voice/sample.mp3")
     * ```
     */
    fun getFileFromAssets(assetPath: String, targetFileName: String? = null): File? {
        return try {
            val fileName = targetFileName ?: assetPath.substringAfterLast("/")
            val targetFile = File(context.cacheDir, fileName)
            
            Log.d(TAG, "📦 Copying from assets: $assetPath → ${targetFile.absolutePath}")
            
            // Mở file từ assets
            val inputStream: InputStream = context.assets.open(assetPath)
            
            // Copy sang cache directory
            val outputStream = FileOutputStream(targetFile)
            val buffer = ByteArray(4096)
            var bytesRead: Int
            
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
            }
            
            outputStream.flush()
            outputStream.close()
            inputStream.close()
            
            Log.i(TAG, "✅ File copied from assets: ${targetFile.absolutePath} (${targetFile.length()} bytes)")
            targetFile
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error copying file from assets: $assetPath", e)
            null
        }
    }
    
    /**
     * Lấy file từ Internal Storage
     * Tìm trong các thư mục:
     * - filesDir: /data/data/package/files/
     * - cacheDir: /data/data/package/cache/
     * 
     * @param fileName tên file cần tìm
     * @param searchRecursive có tìm đệ quy trong thư mục con không (mặc định: true)
     * @return File object nếu tìm thấy, null nếu không
     * 
     * Ví dụ:
     * ```
     * val file = fileHelper.getFileFromInternalStorage("Qwen3-0.6BIQ4_XS.gguf")
     * ```
     */
    fun getFileFromInternalStorage(fileName: String, searchRecursive: Boolean = true): File? {
        Log.d(TAG, "🔍 Searching file in Internal Storage: $fileName")
        
        // 1. Tìm trong filesDir
        val filesDir = context.filesDir
        val fileInFilesDir = if (searchRecursive) {
            searchFileInDirectory(filesDir, fileName)
        } else {
            File(filesDir, fileName).takeIf { it.exists() }
        }
        
        if (fileInFilesDir != null) {
            Log.i(TAG, "✅ Found in filesDir: ${fileInFilesDir.absolutePath}")
            return fileInFilesDir
        }
        
        // 2. Tìm trong cacheDir
        val cacheDir = context.cacheDir
        val fileInCacheDir = if (searchRecursive) {
            searchFileInDirectory(cacheDir, fileName)
        } else {
            File(cacheDir, fileName).takeIf { it.exists() }
        }
        
        if (fileInCacheDir != null) {
            Log.i(TAG, "✅ Found in cacheDir: ${fileInCacheDir.absolutePath}")
            return fileInCacheDir
        }
        
        Log.w(TAG, "⚠️ File not found in Internal Storage: $fileName")
        return null
    }
    
    /**
     * Lấy file từ External Storage
     * Tìm trong các thư mục:
     * - externalFilesDir: /storage/emulated/0/Android/data/package/files/
     * - Download folder: /storage/emulated/0/Download/
     * - Documents folder: /storage/emulated/0/Documents/
     * - /sdcard/ (root external storage)
     * 
     * @param fileName tên file cần tìm
     * @param searchRecursive có tìm đệ quy trong thư mục con không (mặc định: true)
     * @return File object nếu tìm thấy, null nếu không
     * 
     * Ví dụ:
     * ```
     * val file = fileHelper.getFileFromExternalStorage("Qwen3-0.6BIQ4_XS.gguf")
     * ```
     */
    fun getFileFromExternalStorage(fileName: String, searchRecursive: Boolean = true): File? {
        Log.d(TAG, "🔍 Searching file in External Storage: $fileName")
        
        // 0. Tìm trực tiếp trong /sdcard/ (root external storage)
        val sdcardDir = Environment.getExternalStorageDirectory()
        val fileInSdcard = File(sdcardDir, fileName)
        if (fileInSdcard.exists() && fileInSdcard.isFile) {
            Log.i(TAG, "✅ Found in /sdcard/: ${fileInSdcard.absolutePath}")
            return fileInSdcard
        }
        
        // 1. Tìm trong externalFilesDir
        context.getExternalFilesDir(null)?.let { externalFilesDir ->
            val fileInExternalFiles = if (searchRecursive) {
                searchFileInDirectory(externalFilesDir, fileName)
            } else {
                File(externalFilesDir, fileName).takeIf { it.exists() }
            }
            
            if (fileInExternalFiles != null) {
                Log.i(TAG, "✅ Found in externalFilesDir: ${fileInExternalFiles.absolutePath}")
                return fileInExternalFiles
            }
        }
        
        // 2. Tìm trong Download folder
        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val fileInDownload = if (searchRecursive) {
            searchFileInDirectory(downloadDir, fileName)
        } else {
            File(downloadDir, fileName).takeIf { it.exists() }
        }
        
        if (fileInDownload != null) {
            Log.i(TAG, "✅ Found in Download folder: ${fileInDownload.absolutePath}")
            return fileInDownload
        }
        
        // 3. Tìm trong Documents folder
        val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val fileInDocuments = if (searchRecursive) {
            searchFileInDirectory(documentsDir, fileName)
        } else {
            File(documentsDir, fileName).takeIf { it.exists() }
        }
        
        if (fileInDocuments != null) {
            Log.i(TAG, "✅ Found in Documents folder: ${fileInDocuments.absolutePath}")
            return fileInDocuments
        }
        
        Log.w(TAG, "⚠️ File not found in External Storage: $fileName")
        return null
    }
    
    /**
     * Tìm file đệ quy trong thư mục
     * 
     * @param directory thư mục cần tìm
     * @param fileName tên file cần tìm
     * @return File object nếu tìm thấy, null nếu không
     */
    fun searchFileInDirectory(directory: File, fileName: String): File? {
        if (!directory.exists() || !directory.isDirectory) {
            return null
        }
        
        try {
            // Tìm trực tiếp trong thư mục này
            val directFile = File(directory, fileName)
            if (directFile.exists() && directFile.isFile) {
                return directFile
            }
            
            // Tìm đệ quy trong các thư mục con
            directory.listFiles()?.forEach { file ->
                if (file.isDirectory) {
                    val found = searchFileInDirectory(file, fileName)
                    if (found != null) {
                        return found
                    }
                } else if (file.name == fileName) {
                    return file
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error searching in directory: ${directory.absolutePath}", e)
        }
        
        return null
    }
    
    /**
     * Lấy file từ đường dẫn tuyệt đối
     * 
     * @param absolutePath đường dẫn tuyệt đối của file
     * @return File object nếu file tồn tại, null nếu không
     * 
     * Ví dụ:
     * ```
     * val file = fileHelper.getFileFromPath("/storage/emulated/0/Download/model.gguf")
     * ```
     */
    fun getFileFromPath(absolutePath: String): File? {
        return try {
            val file = File(absolutePath)
            if (file.exists() && file.isFile) {
                Log.i(TAG, "✅ File found at path: $absolutePath")
                file
            } else {
                Log.w(TAG, "⚠️ File not found at path: $absolutePath")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error accessing file at path: $absolutePath", e)
            null
        }
    }
    
    /**
     * Copy file từ Uri (content://) sang Internal Storage
     * Hữu ích khi nhận file từ File Picker hoặc Share Intent
     * 
     * @param uri Uri của file (ví dụ: content://...)
     * @param targetFileName tên file đích (mặc định: lấy từ Uri)
     * @return File object nếu thành công, null nếu thất bại
     * 
     * Ví dụ:
     * ```
     * val uri = data?.data // từ onActivityResult
     * val file = fileHelper.copyUriToFile(uri, "model.gguf")
     * ```
     */
    fun copyUriToFile(uri: Uri?, targetFileName: String? = null): File? {
        if (uri == null) {
            Log.e(TAG, "❌ Uri is null")
            return null
        }
        
        return try {
            val fileName = targetFileName ?: getFileNameFromUri(uri) ?: "temp_file_${System.currentTimeMillis()}"
            val targetFile = File(context.cacheDir, fileName)
            
            Log.d(TAG, "📦 Copying from Uri: $uri → ${targetFile.absolutePath}")
            
            // Mở InputStream từ Uri
            val inputStream = context.contentResolver.openInputStream(uri)
            if (inputStream == null) {
                Log.e(TAG, "❌ Cannot open InputStream from Uri: $uri")
                return null
            }
            
            // Copy sang target file
            val outputStream = FileOutputStream(targetFile)
            val buffer = ByteArray(4096)
            var bytesRead: Int
            
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
            }
            
            outputStream.flush()
            outputStream.close()
            inputStream.close()
            
            Log.i(TAG, "✅ File copied from Uri: ${targetFile.absolutePath} (${targetFile.length()} bytes)")
            targetFile
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error copying file from Uri: $uri", e)
            null
        }
    }
    
    /**
     * Lấy tên file từ Uri
     */
    private fun getFileNameFromUri(uri: Uri): String? {
        var fileName: String? = null
        
        // Try to get filename from content resolver
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    fileName = cursor.getString(nameIndex)
                }
            }
        }
        
        // Fallback to last path segment
        if (fileName == null) {
            fileName = uri.lastPathSegment
        }
        
        return fileName
    }
    
    /**
     * Mở File Picker để chọn file
     * Gọi method này trong Activity và xử lý kết quả trong onActivityResult
     * 
     * @param activity Activity hiện tại
     * @param requestCode request code để xác định trong onActivityResult (mặc định: 1001)
     * 
     * Ví dụ:
     * ```
     * // Trong Activity:
     * fileHelper.openFilePicker(this)
     * 
     * // Trong onActivityResult:
     * if (requestCode == 1001 && resultCode == RESULT_OK) {
     *     val file = fileHelper.handleFilePickerResult(data, "model.gguf")
     * }
     * ```
     */
    fun openFilePicker(activity: Activity, requestCode: Int = PICK_FILE_REQUEST_CODE) {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*" // Cho phép chọn mọi loại file
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        
        try {
            activity.startActivityForResult(
                Intent.createChooser(intent, "Select File"),
                requestCode
            )
            Log.d(TAG, "📂 File Picker opened")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error opening File Picker", e)
        }
    }
    
    /**
     * Xử lý kết quả từ File Picker trong onActivityResult
     * 
     * @param data Intent data từ onActivityResult
     * @param targetFileName tên file đích (mặc định: lấy từ Uri)
     * @return File object nếu thành công, null nếu thất bại
     */
    fun handleFilePickerResult(data: Intent?, targetFileName: String? = null): File? {
        val uri = data?.data
        if (uri == null) {
            Log.e(TAG, "❌ No file selected from File Picker")
            return null
        }
        
        Log.d(TAG, "✅ File selected from picker: $uri")
        return copyUriToFile(uri, targetFileName)
    }
    
    /**
     * Lấy thông tin file (size, path, exists)
     * 
     * @param file File object cần kiểm tra
     * @return FileInfo object chứa thông tin file
     */
    fun getFileInfo(file: File?): FileInfo {
        return if (file == null || !file.exists()) {
            FileInfo(exists = false)
        } else {
            FileInfo(
                exists = true,
                path = file.absolutePath,
                name = file.name,
                size = file.length(),
                sizeInMB = file.length() / (1024.0 * 1024.0),
                isFile = file.isFile,
                isDirectory = file.isDirectory
            )
        }
    }
    
    /**
     * Data class chứa thông tin file
     */
    data class FileInfo(
        val exists: Boolean,
        val path: String = "",
        val name: String = "",
        val size: Long = 0L,
        val sizeInMB: Double = 0.0,
        val isFile: Boolean = false,
        val isDirectory: Boolean = false
    ) {
        override fun toString(): String {
            return if (exists) {
                "File: $name\nPath: $path\nSize: ${String.format("%.2f", sizeInMB)} MB (${size} bytes)"
            } else {
                "File does not exist"
            }
        }
    }
}
