package com.k2fsa.sherpa.onnx.simulate.streaming.asr.llm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.*
import java.security.MessageDigest
import java.util.*
import kotlin.math.*

data class QAPair(
    val id: Int,
    val question: String,
    val answer: String,
    var vector: FloatArray? = null
)

/**
 * QAEngine cải thiện với:
 * 1. Inverted Index cho BM25 (O(1) lookup thay vì O(N) scan)
 * 2. Pre-computed Term Frequency per document
 * 3. Cache invalidation bằng content hash
 * 4. Coroutine cancellation support
 * 5. Dispatcher injection cho testability
 * 6. Tunable parameters
 */
class QAEngine(
    private val context: Context,
    // Best practice: Inject dispatcher để dễ test
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    companion object {
        private const val TAG = "QAEngine"
        private const val EMBEDDING_DIM = 300
        private const val CACHE_FILE_NAME = "qa_embeddings_v2.bin"

        // BM25 Params - Dùng giá trị chuẩn từ Elasticsearch
        private const val K1 = 1.2  // Giảm từ 1.5 -> 1.2 (chuẩn Elasticsearch)
        private const val B = 0.75

        // Threshold nên thấp hơn cho average word vectors
        // FastText average pooling cho cosine thấp hơn Sentence Transformers
        private const val SIMILARITY_THRESHOLD = 0.9f

        // Trọng số hybrid scoring
        private const val COSINE_WEIGHT = 0.4
        private const val BM25_WEIGHT = 0.6
        private const val BM25_MAX_SCORE = 5.0
    }

    private val qaList = mutableListOf<QAPair>()
    private val wordVectors = HashMap<String, FloatArray>(50000) // Pre-size

    @Volatile
    private var isReady = false // Volatile cho thread-safety

    // ===== BM25 Data Structures (Cải thiện) =====
    // Inverted Index: token -> List<Pair<docIndex, termFrequency>>
    private val invertedIndex = HashMap<String, List<Pair<Int, Int>>>()
    private val docLengths = mutableListOf<Int>()
    private var avgDocLen = 0.0
    private val idf = HashMap<String, Double>()

    // Pre-computed TF cho mỗi document: docIndex -> Map<token, count>
    private val docTermFreq = mutableListOf<Map<String, Int>>()

    // Content hash để validate cache
    private var qaContentHash: String = ""

    suspend fun initialize(qaFileName: String, vecFileName: String) = withContext(ioDispatcher) {
        val startTime = System.currentTimeMillis()

        try {
            // 1. Load QA Pairs
            loadQAPairs(qaFileName)

            // 2. Load Word Vectors
            loadWordVectors(vecFileName)

            // 3. Build Indexes
            buildIndices()

            isReady = true
            Log.i(TAG, "QA Engine initialized in ${System.currentTimeMillis() - startTime}ms. " +
                    "Loaded ${qaList.size} pairs, ${wordVectors.size} vectors.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize QA Engine", e)
            isReady = false
        }
    }

    private suspend fun loadQAPairs(fileName: String) {
        qaList.clear()
        var idCounter = 0
        val hashBuilder = StringBuilder()
        val currentContext = kotlin.coroutines.coroutineContext
        try {
            context.assets.open(fileName).bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    // Kiểm tra cancellation mỗi dòng (lightweight check)
                    currentContext.ensureActive()

                    if (line.isBlank()) return@forEach

                    val parts = line.split("|", limit = 2)
                    if (parts.size == 2) {
                        val question = parts[0].trim()
                        val answer = parts[1].trim().replace("\\n", "\n")
                        qaList.add(QAPair(idCounter++, question, answer))
                        hashBuilder.append(question) // Chỉ hash question vì vector dựa trên question
                    }
                }
            }
            // Tính content hash cho cache validation
            qaContentHash = hashBuilder.toString().md5()
            Log.i(TAG, "Loaded ${qaList.size} QA pairs. Hash: ${qaContentHash.take(8)}...")
        } catch (e: Exception) {
            Log.e(TAG, "Error reading QA file", e)
            throw e
        }
    }

    private suspend fun loadWordVectors(fileName: String) {
        wordVectors.clear()
        var lineCount = 0
        val currentContext = kotlin.coroutines.coroutineContext
        try {
            context.assets.open(fileName).bufferedReader().use { reader ->
                reader.readLine() // Skip header

                var line = reader.readLine()
                while (line != null) {
                    // Kiểm tra cancellation mỗi 1000 dòng để giảm overhead
                    if (++lineCount % 1000 == 0) {
                        currentContext.ensureActive()
                    }

                    if (line.isNotBlank()) {
                        val firstSpaceIndex = line.indexOf(' ')
                        if (firstSpaceIndex != -1) {
                            val word = line.substring(0, firstSpaceIndex)
                            val vectorStr = line.substring(firstSpaceIndex + 1)

                            val stringTokenizer = StringTokenizer(vectorStr, " ")
                            val vec = FloatArray(EMBEDDING_DIM)
                            var i = 0
                            while (stringTokenizer.hasMoreTokens() && i < EMBEDDING_DIM) {
                                vec[i++] = stringTokenizer.nextToken().toFloat()
                            }
                            wordVectors[word] = vec
                        }
                    }
                    line = reader.readLine()
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error reading Vector file: $fileName", e)
            throw e
        }
        Log.i(TAG, "Loaded ${wordVectors.size} word vectors.")
    }

    /**
     * Build BM25 Inverted Index + Sentence Embeddings
     *
     * Cải thiện chính:
     * - Inverted Index: Thay vì scan toàn bộ corpus mỗi query,
     *   chỉ cần lookup các documents chứa query terms -> O(K) thay vì O(N)
     * - Pre-computed TF: Không cần đếm lại term frequency mỗi lần search
     */
    private fun buildIndices() {
        docLengths.clear()
        idf.clear()
        docTermFreq.clear()

        val docFreq = HashMap<String, Int>()
        // Temporary inverted index builder: token -> MutableList<Pair<docIdx, tf>>
        val invertedBuilder = HashMap<String, MutableList<Pair<Int, Int>>>()

        qaList.forEachIndexed { idx, qa ->
            val tokens = tokenize(qa.question)
            docLengths.add(tokens.size)

            // Tính term frequency cho document này
            val tfMap = HashMap<String, Int>()
            for (token in tokens) {
                tfMap[token] = (tfMap[token] ?: 0) + 1
            }
            docTermFreq.add(tfMap)

            // Build inverted index
            for ((token, freq) in tfMap) {
                // Document frequency (mỗi token chỉ đếm 1 lần per doc)
                docFreq[token] = (docFreq[token] ?: 0) + 1
                // Thêm vào inverted index
                invertedBuilder.getOrPut(token) { mutableListOf() }.add(Pair(idx, freq))
            }
        }

        // Finalize inverted index (convert to immutable lists)
        invertedIndex.clear()
        for ((token, postings) in invertedBuilder) {
            invertedIndex[token] = postings.toList()
        }

        avgDocLen = docLengths.average()
        val totalDocs = qaList.size.toDouble()

        // Tính IDF
        for ((term, freq) in docFreq) {
            idf[term] = ln((totalDocs - freq + 0.5) / (freq + 0.5) + 1.0)
        }

        // Build sentence embeddings (with cache)
        buildSentenceEmbeddings()

        Log.i(TAG, "Built inverted index with ${invertedIndex.size} unique terms.")
    }

    /**
     * Cache embeddings với content hash validation
     */
    private fun buildSentenceEmbeddings() {
        val cacheFile = File(context.cacheDir, CACHE_FILE_NAME)

        if (cacheFile.exists()) {
            try {
                ObjectInputStream(FileInputStream(cacheFile)).use { ois ->
                    val cachedHash = ois.readObject() as? String
                    @Suppress("UNCHECKED_CAST")
                    val cachedVectors = ois.readObject() as List<FloatArray>

                    // Validate bằng content hash thay vì chỉ size
                    if (cachedHash == qaContentHash && cachedVectors.size == qaList.size) {
                        for (i in qaList.indices) qaList[i].vector = cachedVectors[i]
                        Log.i(TAG, "Loaded vectors from cache (hash matched).")
                        return
                    } else {
                        Log.w(TAG, "Cache invalidated: hash mismatch or size mismatch.")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Cache read failed, rebuilding.", e)
            }
        }

        // Compute embeddings
        val vectorsToCache = mutableListOf<FloatArray>()
        qaList.forEach { qa ->
            val vec = sentenceEmbedding(qa.question)
            qa.vector = vec
            vectorsToCache.add(vec)
        }

        // Save cache với hash
        try {
            ObjectOutputStream(FileOutputStream(cacheFile)).use { oos ->
                oos.writeObject(qaContentHash)
                oos.writeObject(vectorsToCache)
            }
            Log.i(TAG, "Saved ${vectorsToCache.size} vectors to cache.")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save cache", e)
        }
    }

    private fun tokenize(text: String): List<String> {
        return text.lowercase()
            .replace(Regex("[^a-z0-9àáạảãâầấậẩẫăằắặẳẵèéẹẻẽêềếệểễìíịỉĩòóọỏõôồốộổỗơờớợởỡùúụủũưừứựửữỳýỵỷỹđ ]"), "")
            .split("\\s+".toRegex())
            .filter { it.isNotBlank() }
    }

    /**
     * Sentence embedding bằng average pooling + L2 normalization
     * Đã normalize sẵn -> cosine similarity = dot product
     */
    private fun sentenceEmbedding(text: String): FloatArray {
        val tokens = tokenize(text)
        val sentVec = FloatArray(EMBEDDING_DIM)
        var count = 0

        for (token in tokens) {
            val vec = wordVectors[token]
            if (vec != null) {
                for (i in 0 until EMBEDDING_DIM) sentVec[i] += vec[i]
                count++
            }
        }

        if (count > 0) {
            val invCount = 1.0f / count
            for (i in 0 until EMBEDDING_DIM) sentVec[i] *= invCount
        }

        // L2 Normalize
        var normSq = 0.0f
        for (i in 0 until EMBEDDING_DIM) normSq += sentVec[i] * sentVec[i]
        val norm = sqrt(normSq)
        if (norm > 0f) {
            val invNorm = 1.0f / norm
            for (i in 0 until EMBEDDING_DIM) sentVec[i] *= invNorm
        }

        return sentVec
    }

    /**
     * Search cải thiện với Inverted Index
     *
     * Thay vì duyệt toàn bộ N documents:
     * 1. BM25: Chỉ tính score cho documents chứa ít nhất 1 query term (via inverted index)
     * 2. Cosine: Vẫn cần tính cho tất cả (hoặc chỉ candidates từ BM25)
     *
     * Optimization: 2-phase search
     * - Phase 1: BM25 via inverted index -> lấy top candidates
     * - Phase 2: Cosine similarity chỉ trên candidates -> hybrid score
     */
    fun search(query: String): String? {
        if (!isReady || qaList.isEmpty()) return null

        val queryTokens = tokenize(query)
        if (queryTokens.isEmpty()) return null

        val queryVector = sentenceEmbedding(query)

        // Phase 1: BM25 scoring via Inverted Index
        val bm25Scores = HashMap<Int, Double>()

        for (token in queryTokens) {
            val idfVal = idf[token] ?: continue
            val postings = invertedIndex[token] ?: continue

            for ((docIdx, tf) in postings) {
                val docLen = docLengths[docIdx]
                val num = tf * (K1 + 1)
                val den = tf + K1 * (1 - B + B * (docLen / avgDocLen))
                val termScore = idfVal * (num / den)
                bm25Scores[docIdx] = (bm25Scores[docIdx] ?: 0.0) + termScore
            }
        }

        // Phase 2: Hybrid scoring
        var bestScore = -1.0
        var bestMatch: QAPair? = null
        var bestCosine = 0.0
        var bestBm25 = 0.0
        var bestNormalizedBm25 = 0.0

        if (bm25Scores.isNotEmpty()) {
            for ((docIdx, bm25Score) in bm25Scores) {
                val docVector = qaList[docIdx].vector ?: continue

                var cosineScore = 0.0
                for (j in 0 until EMBEDDING_DIM) {
                    cosineScore += queryVector[j] * docVector[j]
                }

                val normalizedBm25 = min(bm25Score, BM25_MAX_SCORE) / BM25_MAX_SCORE
                val finalScore = (cosineScore * COSINE_WEIGHT) + (normalizedBm25 * BM25_WEIGHT)

                if (finalScore > bestScore) {
                    bestScore = finalScore
                    bestMatch = qaList[docIdx]
                    bestCosine = cosineScore
                    bestBm25 = bm25Score
                    bestNormalizedBm25 = normalizedBm25
                }
            }
        } else {
            for (i in qaList.indices) {
                val docVector = qaList[i].vector ?: continue
                var cosineScore = 0.0
                for (j in 0 until EMBEDDING_DIM) {
                    cosineScore += queryVector[j] * docVector[j]
                }

                if (cosineScore > bestScore) {
                    bestScore = cosineScore
                    bestMatch = qaList[i]
                    bestCosine = cosineScore
                    bestBm25 = 0.0
                    bestNormalizedBm25 = 0.0
                }
            }
        }

        // ========== LOG CHI TIẾT NGƯỠNG ==========
        val isHit = bestScore >= SIMILARITY_THRESHOLD
        val searchMode = if (bm25Scores.isNotEmpty()) "HYBRID (BM25+Cosine)" else "COSINE_ONLY"

        Log.i(TAG, "╔══════════════════════════════════════════════")
        Log.i(TAG, "║ 🔍 QA Search Result")
        Log.i(TAG, "║ Query        : '$query'")
        Log.i(TAG, "║ Search Mode  : $searchMode")
        Log.i(TAG, "║ BM25 Candidates: ${bm25Scores.size} / ${qaList.size}")
        Log.i(TAG, "╠──────────────────────────────────────────────")
        Log.i(TAG, "║ 📊 Scores:")
        Log.i(TAG, "║   Cosine Score      : ${"%.6f".format(bestCosine)}")
        Log.i(TAG, "║   BM25 Raw Score    : ${"%.6f".format(bestBm25)}")
        Log.i(TAG, "║   BM25 Normalized   : ${"%.6f".format(bestNormalizedBm25)}")
        Log.i(TAG, "║   ➤ Final Score     : ${"%.6f".format(bestScore)}")
        Log.i(TAG, "╠──────────────────────────────────────────────")
        Log.i(TAG, "║ 🎯 Threshold Check:")
        Log.i(TAG, "║   SIMILARITY_THRESHOLD : $SIMILARITY_THRESHOLD")
        Log.i(TAG, "║   Final Score          : ${"%.6f".format(bestScore)}")
        Log.i(TAG, "║   Result               : ${if (isHit) "✅ HIT (score >= threshold)" else "❌ MISS (score < threshold)"}")
        if (bestMatch != null) {
            Log.i(TAG, "╠──────────────────────────────────────────────")
            Log.i(TAG, "║ 📄 Best Match:")
            Log.i(TAG, "║   ID       : ${bestMatch.id}")
            Log.i(TAG, "║   Question : ${bestMatch.question.take(80)}${if (bestMatch.question.length > 80) "..." else ""}")
            Log.i(TAG, "║   Answer   : ${bestMatch.answer.take(80)}${if (bestMatch.answer.length > 80) "..." else ""}")
        }
        Log.i(TAG, "╚══════════════════════════════════════════════")

        return if (isHit) bestMatch?.answer else null
    }

    fun release() {
        wordVectors.clear()
        qaList.clear()
        invertedIndex.clear()
        docTermFreq.clear()
        docLengths.clear()
        idf.clear()
        isReady = false
    }

    // ===== Utility =====
    private fun String.md5(): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(this.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}

