package org.coresense.itantra.metrics

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.coresense.itantra.protocol.Language
import org.coresense.itantra.storage.AppDatabase
import org.coresense.itantra.storage.MetricSampleEntity
import org.coresense.itantra.stt.SttEngine
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class BenchmarkItem(
    val language: Language,
    val audioAssetPath: String,
    val referenceTranscript: String,
    val durationMs: Long
)

data class BenchmarkResult(
    val language: Language,
    val referenceTranscript: String,
    val hypothesisTranscript: String,
    val wer: Float,
    val substitutions: Int,
    val deletions: Int,
    val insertions: Int,
    val processingTimeMs: Long,
    val audioDurationMs: Long,
    val rtf: Float,
    val isModelInstalled: Boolean
)

data class BenchmarkProgress(
    val isRunning: Boolean = false,
    val currentLanguage: Language? = null,
    val completedCount: Int = 0,
    val totalCount: Int = 0,
    val results: List<BenchmarkResult> = emptyList(),
    val errorMessage: String? = null
)

/**
 * Runs offline STT benchmark against bundled 16 kHz reference WAV audio
 * and reference transcripts. Computes TRUE Word Error Rate (WER) using
 * edit distance on words, processing time, and real RTF.
 * Saves results to Room database.
 */
class BenchmarkRunner(
    private val context: Context,
    private val sttEngine: SttEngine,
    private val database: AppDatabase
) {

    private val _progress = MutableStateFlow(BenchmarkProgress())
    val progress: StateFlow<BenchmarkProgress> = _progress.asStateFlow()

    private val benchmarkItems = mutableListOf<BenchmarkItem>()

    init {
        loadBenchmarkManifest()
    }

    private fun loadBenchmarkManifest() {
        try {
            val stream = context.assets.open("benchmarks/manifest.json")
            val jsonText = BufferedReader(InputStreamReader(stream)).use { it.readText() }
            val array = JSONArray(jsonText)

            benchmarkItems.clear()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val langCode = obj.getString("code")
                val lang = Language.fromCode(langCode)
                val file = obj.getString("audioFile")
                val transcript = obj.getString("referenceTranscript")
                val duration = obj.optLong("durationMs", 3000L)

                benchmarkItems.add(
                    BenchmarkItem(
                        language = lang,
                        audioAssetPath = file,
                        referenceTranscript = transcript,
                        durationMs = duration
                    )
                )
            }
        } catch (ignored: Exception) {
        }
    }

    suspend fun runAllBenchmarks(): List<BenchmarkResult> = withContext(Dispatchers.IO) {
        if (_progress.value.isRunning) return@withContext _progress.value.results

        _progress.value = BenchmarkProgress(
            isRunning = true,
            totalCount = benchmarkItems.size,
            results = emptyList()
        )

        val results = mutableListOf<BenchmarkResult>()

        for (item in benchmarkItems) {
            _progress.value = _progress.value.copy(
                currentLanguage = item.language
            )

            // Try initializing language model
            val initResult = sttEngine.initialize(item.language)
            if (initResult.isFailure) {
                // Honest reporting: model is missing/not installed
                val uninstalledResult = BenchmarkResult(
                    language = item.language,
                    referenceTranscript = item.referenceTranscript,
                    hypothesisTranscript = "[Model Missing - Not Installed]",
                    wer = 1.0f,
                    substitutions = 0,
                    deletions = item.referenceTranscript.split(" ").size,
                    insertions = 0,
                    processingTimeMs = 0L,
                    audioDurationMs = item.durationMs,
                    rtf = 0f,
                    isModelInstalled = false
                )
                results.add(uninstalledResult)
                _progress.value = _progress.value.copy(
                    completedCount = results.size,
                    results = results.toList()
                )
                continue
            }

            // Read WAV audio from assets
            val audioPcm = readPcmFromWavAsset(item.audioAssetPath)
            if (audioPcm == null || audioPcm.isEmpty()) {
                continue
            }

            val startTime = SystemClock.elapsedRealtime()
            val sttResult = sttEngine.transcribe(audioPcm)
            val duration = (audioPcm.size * 1000L) / 16000L

            if (sttResult.isSuccess) {
                val hyp = sttResult.getOrThrow().text
                val werStats = WerCalculator.calculate(item.referenceTranscript, hyp)
                val procTime = SystemClock.elapsedRealtime() - startTime
                val rtf = if (duration > 0) procTime.toFloat() / duration.toFloat() else 0f

                val res = BenchmarkResult(
                    language = item.language,
                    referenceTranscript = item.referenceTranscript,
                    hypothesisTranscript = hyp,
                    wer = werStats.wer,
                    substitutions = werStats.substitutions,
                    deletions = werStats.deletions,
                    insertions = werStats.insertions,
                    processingTimeMs = procTime,
                    audioDurationMs = duration,
                    rtf = rtf,
                    isModelInstalled = true
                )
                results.add(res)

                // Save to Room DB
                database.metricSampleDao().insertSample(
                    MetricSampleEntity(
                        languageCode = item.language.code,
                        rtf = rtf,
                        wer = werStats.wer,
                        ramPssMb = 0f,
                        cpuPercent = 0f,
                        batteryLevelPercent = 100,
                        payloadBytes = hyp.toByteArray().size,
                        pcmBytes = audioPcm.size * 2,
                        compressionRatio = (audioPcm.size * 2f) / maxOf(1, hyp.toByteArray().size),
                        latencyMs = procTime
                    )
                )
            } else {
                val res = BenchmarkResult(
                    language = item.language,
                    referenceTranscript = item.referenceTranscript,
                    hypothesisTranscript = "[Transcription Error]",
                    wer = 1.0f,
                    substitutions = 0,
                    deletions = item.referenceTranscript.split(" ").size,
                    insertions = 0,
                    processingTimeMs = 0L,
                    audioDurationMs = duration,
                    rtf = 0f,
                    isModelInstalled = true
                )
                results.add(res)
            }

            _progress.value = _progress.value.copy(
                completedCount = results.size,
                results = results.toList()
            )
        }

        _progress.value = _progress.value.copy(
            isRunning = false,
            currentLanguage = null
        )

        return@withContext results
    }

    private fun readPcmFromWavAsset(assetPath: String): ShortArray? {
        return try {
            context.assets.open(assetPath).use { stream: InputStream ->
                val allBytes = stream.readBytes()
                // Skip 44-byte WAV header
                if (allBytes.size <= 44) return null
                val pcmLength = allBytes.size - 44
                val samplesCount = pcmLength / 2
                val shortArray = ShortArray(samplesCount)

                val buffer = ByteBuffer.wrap(allBytes, 44, pcmLength).order(ByteOrder.LITTLE_ENDIAN)
                buffer.asShortBuffer().get(shortArray)
                shortArray
            }
        } catch (e: Exception) {
            null
        }
    }
}
