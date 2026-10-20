package org.coresense.itantra.backend.speech

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.coresense.itantra.core.protocol.Language
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

sealed class DownloadState {
    object Idle : DownloadState()
    data class Downloading(val progressPercent: Int, val bytesDownloaded: Long, val totalBytes: Long) : DownloadState()
    object Verifying : DownloadState()
    object Ready : DownloadState()
    data class Error(val message: String) : DownloadState()
}

/**
 * Robust manager for on-demand downloading, integrity verification,
 * atomic moving, and safe deletion of offline STT language models.
 *
 * Enforces strict safety rules:
 * 1. Never downloads or deletes bundled English and Hindi models.
 * 2. Downloads to temporary .tmp files; verifies SHA-256 and ONNX session compatibility.
 * 3. Cleans up partial .tmp files on cancellation or failure.
 * 4. Atomically renames validated files to final path.
 */
class ModelDownloadManager(
    private val registry: LanguageModelRegistry
) {

    private val _downloadStates = MutableStateFlow<Map<Language, DownloadState>>(emptyMap())
    val downloadStates: StateFlow<Map<Language, DownloadState>> = _downloadStates.asStateFlow()

    private val activeJobs = mutableMapOf<Language, Job>()

    fun getDownloadState(language: Language): DownloadState {
        return _downloadStates.value[language] ?: DownloadState.Idle
    }

    fun isDownloading(language: Language): Boolean {
        return getDownloadState(language) is DownloadState.Downloading
    }

    /**
     * Start downloading a language model in the provided coroutine scope.
     */
    fun startDownload(
        language: Language,
        scope: CoroutineScope,
        onComplete: ((Result<Unit>) -> Unit)? = null
    ): Job {
        activeJobs[language]?.cancel()

        val job = scope.launch(Dispatchers.IO) {
            val result = downloadAndInstall(language)
            withContext(Dispatchers.Main) {
                onComplete?.invoke(result)
            }
        }
        activeJobs[language] = job
        return job
    }

    /**
     * Suspending function executing full download, validation, and atomic installation.
     */
    suspend fun downloadAndInstall(language: Language): Result<Unit> = withContext(Dispatchers.IO) {
        if (registry.isBundled(language)) {
            val err = "Cannot download bundled language ${language.displayName}. It is included inside the application."
            updateState(language, DownloadState.Error(err))
            return@withContext Result.failure(IllegalArgumentException(err))
        }

        val config = LanguageDownloadConfig.getDownloadInfo(language)
        if (!config.isAvailableForDownload || config.modelUrl.isNullOrBlank()) {
            val err = "Model download is not currently available for ${language.displayName} (Coming Soon)."
            updateState(language, DownloadState.Error(err))
            return@withContext Result.failure(IllegalStateException(err))
        }

        val targetDir = File(registry.getModelsStorageDirectory(), language.code)
        if (!targetDir.exists()) targetDir.mkdirs()

        val tempModelFile = File(targetDir, "model.onnx.tmp")
        val tempTokensFile = File(targetDir, "tokens.txt.tmp")
        val finalModelFile = File(targetDir, "model.onnx")
        val finalTokensFile = File(targetDir, "tokens.txt")

        try {
            updateState(language, DownloadState.Downloading(0, 0, config.estimatedSizeBytes))

            // 1. Download Model ONNX file
            val modelDigest = MessageDigest.getInstance("SHA-256")
            downloadStreamWithProgress(
                urlStr = config.modelUrl,
                destFile = tempModelFile,
                digest = modelDigest,
                totalExpected = config.estimatedSizeBytes
            ) { bytesDownloaded, total ->
                val percent = if (total > 0L) ((bytesDownloaded * 100) / total).toInt().coerceIn(0, 95) else 0
                updateState(language, DownloadState.Downloading(percent, bytesDownloaded, total))
            }

            // 2. Download Tokens file if configured
            val tokensDigest = MessageDigest.getInstance("SHA-256")
            if (!config.tokensUrl.isNullOrBlank()) {
                downloadStreamWithProgress(
                    urlStr = config.tokensUrl,
                    destFile = tempTokensFile,
                    digest = tokensDigest,
                    totalExpected = 4096L
                ) { _, _ -> }
            } else {
                // If tokensUrl not separate, check if bundled tokens exist or create placeholder
                if (!tempTokensFile.exists()) {
                    tempTokensFile.writeText("<pad>\n<s>\n</s>\n<unk>\n|\n")
                }
            }

            // 3. Verification stage
            updateState(language, DownloadState.Verifying)

            // Verify model SHA-256 if provided
            if (!config.sha256Model.isNullOrBlank()) {
                val computedHash = bytesToHex(modelDigest.digest())
                if (!computedHash.equals(config.sha256Model, ignoreCase = true)) {
                    throw SecurityException("Model checksum mismatch: expected ${config.sha256Model}, got $computedHash")
                }
            }

            // Verify tokens SHA-256 if provided
            if (!config.sha256Tokens.isNullOrBlank() && !config.tokensUrl.isNullOrBlank()) {
                val computedTokensHash = bytesToHex(tokensDigest.digest())
                if (!computedTokensHash.equals(config.sha256Tokens, ignoreCase = true)) {
                    throw SecurityException("Tokens checksum mismatch: expected ${config.sha256Tokens}, got $computedTokensHash")
                }
            }

            // Smoke test: verify ONNX runtime compatibility
            smokeTestOnnxModel(tempModelFile)

            // 4. Atomic file rename
            if (finalModelFile.exists()) finalModelFile.delete()
            if (finalTokensFile.exists()) finalTokensFile.delete()

            val modelRenamed = tempModelFile.renameTo(finalModelFile)
            val tokensRenamed = tempTokensFile.renameTo(finalTokensFile)

            if (!modelRenamed || !tokensRenamed) {
                throw IllegalStateException("Failed to atomically move downloaded files to destination")
            }

            // 5. Update Registry & State
            registry.refresh()
            registry.updateModelStatus(language, ModelStatus.READY)
            updateState(language, DownloadState.Ready)

            Result.success(Unit)
        } catch (ce: CancellationException) {
            cleanUpTempFiles(tempModelFile, tempTokensFile)
            updateState(language, DownloadState.Idle)
            throw ce
        } catch (e: Exception) {
            cleanUpTempFiles(tempModelFile, tempTokensFile)
            val err = e.message ?: "Download/Verification failed"
            updateState(language, DownloadState.Error(err))
            registry.updateModelStatus(language, ModelStatus.MODEL_LOAD_ERROR, err)
            Result.failure(e)
        }
    }

    fun cancelDownload(language: Language) {
        val job = activeJobs[language]
        if (job != null && job.isActive) {
            job.cancel()
        }
        val targetDir = File(registry.getModelsStorageDirectory(), language.code)
        cleanUpTempFiles(
            File(targetDir, "model.onnx.tmp"),
            File(targetDir, "tokens.txt.tmp")
        )
        updateState(language, DownloadState.Idle)
    }

    /**
     * Delete downloaded model to reclaim user storage.
     * Prevents deleting bundled English and Hindi models.
     */
    fun deleteDownloadedModel(language: Language): Result<Unit> {
        if (registry.isBundled(language)) {
            return Result.failure(
                IllegalArgumentException("Cannot delete bundled language model for ${language.displayName}")
            )
        }

        cancelDownload(language)
        val success = registry.deleteModel(language)
        updateState(language, DownloadState.Idle)

        return if (success) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("Failed to delete model files for ${language.displayName}"))
        }
    }

    private fun cleanUpTempFiles(vararg files: File) {
        for (f in files) {
            try {
                if (f.exists()) f.delete()
            } catch (_: Exception) {}
        }
    }

    private fun smokeTestOnnxModel(modelFile: File) {
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(1)
        }
        val session = env.createSession(modelFile.absolutePath, opts)
        session.close()
    }

    private fun updateState(language: Language, state: DownloadState) {
        val current = _downloadStates.value.toMutableMap()
        current[language] = state
        _downloadStates.value = current
    }

    private fun downloadStreamWithProgress(
        urlStr: String,
        destFile: File,
        digest: MessageDigest,
        totalExpected: Long,
        onProgress: (bytesDownloaded: Long, total: Long) -> Unit
    ) {
        if (destFile.exists()) destFile.delete()

        var connection: HttpURLConnection? = null
        var inputStream: InputStream? = null
        var outputStream: FileOutputStream? = null

        try {
            var currentUrl = URL(urlStr)
            var redirects = 0
            while (redirects < 5) {
                connection = currentUrl.openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 30000
                connection.instanceFollowRedirects = false
                connection.connect()

                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: throw IllegalStateException("HTTP redirect without Location header")
                    currentUrl = URL(location)
                    connection.disconnect()
                    redirects++
                } else if (code in 200..299) {
                    break
                } else {
                    throw IllegalStateException("HTTP server returned error code: $code")
                }
            }

            val conn = connection ?: throw IllegalStateException("Could not establish HTTP connection")
            val contentLength = conn.contentLengthLong
            val total = if (contentLength > 0L) contentLength else totalExpected

            inputStream = conn.inputStream
            outputStream = FileOutputStream(destFile)

            val buffer = ByteArray(32768)
            var bytesDownloaded = 0L
            var read: Int

            while (inputStream.read(buffer).also { read = it } != -1) {
                outputStream.write(buffer, 0, read)
                digest.update(buffer, 0, read)
                bytesDownloaded += read
                onProgress(bytesDownloaded, total)
            }

            outputStream.flush()
        } finally {
            try { outputStream?.close() } catch (_: Exception) {}
            try { inputStream?.close() } catch (_: Exception) {}
            try { connection?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (b in bytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }
}
