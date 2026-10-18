package org.coresense.itantra.stt

import android.content.Context
import android.content.res.AssetFileDescriptor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.coresense.itantra.protocol.Language
import java.io.File
import java.io.FileOutputStream

enum class ModelStatus {
    INSTALLED,
    MISSING,
    NOT_INSTALLED,
    COMING_SOON,
    DOWNLOADING,
    VERIFYING,
    EXTRACTING,
    LOADING,
    READY,
    INFERENCE_ERROR,
    MODEL_LOAD_ERROR,
    MODEL_UNAVAILABLE
}

data class ModelMetadata(
    val language: Language,
    val engineName: String,
    val modelFilename: String,
    val tokensFilename: String,
    val estimatedSizeMb: Float,
    val actualSizeMb: Float = 0f,
    val status: ModelStatus = ModelStatus.MISSING,
    val localDirectoryPath: String? = null,
    val errorMessage: String? = null,
    val isBundled: Boolean = false
)

/**
 * Registry listing for each of the 10 Indic languages + English:
 * engine, model file, size MB, status.
 *
 * English and Hindi are pre-bundled inside the APK.
 * The other 9 Indian languages are supported for on-demand downloading.
 */
open class LanguageModelRegistry(
    private val context: Context,
    private val customBaseDir: File? = null
) {

    private val _models = MutableStateFlow<Map<Language, ModelMetadata>>(emptyMap())
    val models: StateFlow<Map<Language, ModelMetadata>> = _models.asStateFlow()

    init {
        refresh()
    }

    fun isBundled(lang: Language): Boolean = (lang == Language.ENGLISH || lang == Language.HINDI || lang == Language.MARATHI)

    fun getBundledLanguages(): List<Language> = listOf(Language.ENGLISH, Language.HINDI, Language.MARATHI)

    fun refresh() {
        val rootDir = getModelsStorageDirectory()
        extractBundledAssetsIfPresent(rootDir)
        val currentMap = _models.value
        val map = mutableMapOf<Language, ModelMetadata>()

        for (lang in Language.entries) {
            val langDir = File(rootDir, lang.code)
            val modelFile = File(langDir, "model.onnx")
            val tokensFile = File(langDir, "tokens.txt")

            val isNeuralInstalled = modelFile.exists() && modelFile.length() > 0L
            val actualSize = if (isNeuralInstalled) {
                (modelFile.length() + if (tokensFile.exists()) tokensFile.length() else 0L) / (1024f * 1024f)
            } else {
                0f
            }

            val defaultSizeMb = when (lang) {
                Language.HINDI -> 230.3f
                Language.ENGLISH -> 116.3f
                Language.MARATHI -> 117.4f
                Language.BENGALI -> 82.0f
                Language.TAMIL -> 85.0f
                Language.TELUGU -> 84.0f
                Language.GUJARATI -> 77.0f
                Language.KANNADA -> 83.0f
                Language.MALAYALAM -> 86.0f
                Language.PUNJABI -> 75.0f
                Language.ODIA -> 80.0f
            }

            val engine = if (isNeuralInstalled) {
                when (lang) {
                    Language.ENGLISH -> "Wav2Vec2-Base-960h CTC ONNX"
                    Language.HINDI -> "AI4Bharat IndicWav2Vec Hindi CTC ONNX"
                    Language.MARATHI -> "AI4Bharat Vakyansh Marathi Wav2Vec2 CTC ONNX"
                    else -> "AI4Bharat IndicConformer ONNX"
                }
            } else {
                "Offline Model Unavailable (${lang.displayName})"
            }

            val isBundledLang = isBundled(lang)
            val previousStatus = currentMap[lang]?.status

            val status = when {
                isNeuralInstalled -> {
                    if (previousStatus == ModelStatus.READY) ModelStatus.READY else ModelStatus.INSTALLED
                }
                previousStatus == ModelStatus.DOWNLOADING -> ModelStatus.DOWNLOADING
                previousStatus == ModelStatus.VERIFYING -> ModelStatus.VERIFYING
                isBundledLang -> ModelStatus.MISSING
                LanguageDownloadConfig.isAvailable(lang) -> ModelStatus.NOT_INSTALLED
                else -> ModelStatus.COMING_SOON
            }

            map[lang] = ModelMetadata(
                language = lang,
                engineName = engine,
                modelFilename = if (isNeuralInstalled) "model.onnx" else "MISSING",
                tokensFilename = if (isNeuralInstalled && tokensFile.exists()) "tokens.txt" else "MISSING",
                estimatedSizeMb = defaultSizeMb,
                actualSizeMb = actualSize,
                status = status,
                localDirectoryPath = if (isNeuralInstalled) langDir.absolutePath else null,
                isBundled = isBundledLang
            )
        }

        _models.value = map
    }

    /**
     * Safely delete an on-demand downloaded language model to reclaim storage.
     * Bundled English and Hindi models can NEVER be deleted.
     */
    fun deleteModel(lang: Language): Boolean {
        if (isBundled(lang)) {
            try { android.util.Log.w("LanguageModelRegistry", "Cannot delete bundled model for ${lang.displayName}") } catch (_: Throwable) {}
            return false
        }
        val rootDir = getModelsStorageDirectory()
        val langDir = File(rootDir, lang.code)
        if (langDir.exists()) {
            langDir.listFiles()?.forEach { it.delete() }
            val deleted = langDir.delete()
            refresh()
            return deleted
        }
        refresh()
        return true
    }

    fun updateModelStatus(lang: Language, status: ModelStatus, error: String? = null) {
        val current = _models.value.toMutableMap()
        val existing = current[lang]
        if (existing != null) {
            current[lang] = existing.copy(status = status, errorMessage = error)
            _models.value = current
        }
    }

    fun isModelInstalled(lang: Language): Boolean {
        return getModelFile(lang) != null
    }

    fun getInstalledLanguages(): List<Language> {
        return Language.entries.filter { isModelInstalled(it) }
    }

    open fun getModelsStorageDirectory(): File {
        if (customBaseDir != null) {
            if (!customBaseDir.exists()) customBaseDir.mkdirs()
            return customBaseDir
        }
        val internal = File(context.filesDir, "models/stt")
        if (!internal.exists()) internal.mkdirs()
        return internal
    }

    fun getModelFile(lang: Language): File? {
        val rootDir = getModelsStorageDirectory()
        val dir = File(rootDir, lang.code)
        val file = File(dir, "model.onnx")
        if (file.exists() && file.length() > 0L) return file

        if (customBaseDir == null) {
            try {
                val ext = context.getExternalFilesDir("models/stt")
                if (ext != null) {
                    val extFile = File(File(ext, lang.code), "model.onnx")
                    if (extFile.exists() && extFile.length() > 0L) return extFile
                }
            } catch (_: Exception) {}
        }
        return null
    }

    fun getTokensFile(lang: Language): File? {
        val rootDir = getModelsStorageDirectory()
        val dir = File(rootDir, lang.code)
        val file = File(dir, "tokens.txt")
        if (file.exists() && file.length() > 0L) return file

        if (customBaseDir == null) {
            try {
                val ext = context.getExternalFilesDir("models/stt")
                if (ext != null) {
                    val extFile = File(File(ext, lang.code), "tokens.txt")
                    if (extFile.exists() && extFile.length() > 0L) return extFile
                }
            } catch (_: Exception) {}
        }
        return null
    }

    fun forceReextract(lang: Language): Boolean {
        if (customBaseDir != null) return false
        val rootDir = getModelsStorageDirectory()
        val langDir = File(rootDir, lang.code)
        val targetModel = File(langDir, "model.onnx")
        val targetTokens = File(langDir, "tokens.txt")
        val sizeFile = File(langDir, "model.size")

        try {
            targetModel.delete()
            targetTokens.delete()
            sizeFile.delete()
        } catch (_: Exception) {}

        extractLanguageAsset(lang, rootDir)
        refresh()
        return isModelInstalled(lang)
    }

    fun extractBundledAssetsIfPresent(rootDir: File = getModelsStorageDirectory()) {
        if (customBaseDir != null) return
        for (lang in getBundledLanguages()) {
            extractLanguageAsset(lang, rootDir)
        }
    }

    private fun extractLanguageAsset(lang: Language, rootDir: File) {
        val assetModelPath = "models/stt/${lang.code}/model.onnx"
        val assetTokensPath = "models/stt/${lang.code}/tokens.txt"

        val langDir = File(rootDir, lang.code)
        val targetModel = File(langDir, "model.onnx")
        val targetTokens = File(langDir, "tokens.txt")
        val sizeMarkerFile = File(langDir, "model.size")

        // 1. Check if model asset exists and its expected size
        var assetSize = -1L
        try {
            val afd: AssetFileDescriptor? = context.assets.openFd(assetModelPath)
            if (afd != null) {
                assetSize = afd.length
                afd.close()
            }
        } catch (_: Exception) {
            // Might be compressed in asset manager or openFd unsupported
        }

        // If target exists but size mismatch with uncompressed asset, re-extract
        val needsModelExtract = if (assetSize > 0L && targetModel.exists()) {
            targetModel.length() != assetSize
        } else {
            !targetModel.exists() || targetModel.length() == 0L
        }

        if (needsModelExtract) {
            try {
                context.assets.open(assetModelPath).use { assetStream ->
                    if (!langDir.exists()) langDir.mkdirs()

                    val tempModel = File(langDir, "model.onnx.tmp")
                    if (tempModel.exists()) tempModel.delete()

                    FileOutputStream(tempModel).use { output ->
                        val buffer = ByteArray(65536)
                        var read: Int
                        while (assetStream.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                        }
                        output.flush()
                    }

                    if (tempModel.length() > 0L) {
                        if (targetModel.exists()) targetModel.delete()
                        tempModel.renameTo(targetModel)
                        if (assetSize > 0L) {
                            try { sizeMarkerFile.writeText(assetSize.toString()) } catch (_: Exception) {}
                        }
                        try { android.util.Log.i("LanguageModelRegistry", "Successfully extracted ${lang.displayName} model.onnx (${targetModel.length()} bytes)") } catch (_: Throwable) {}
                    }
                }
            } catch (_: Exception) {
                // Asset not packaged in APK
            }
        }

        // 2. Extract tokens.txt
        val needsTokensExtract = !targetTokens.exists() || targetTokens.length() == 0L
        if (needsTokensExtract) {
            try {
                context.assets.open(assetTokensPath).use { assetTokensStream ->
                    if (!langDir.exists()) langDir.mkdirs()
                    val tempTokens = File(langDir, "tokens.txt.tmp")
                    if (tempTokens.exists()) tempTokens.delete()

                    FileOutputStream(tempTokens).use { output ->
                        assetTokensStream.copyTo(output)
                        output.flush()
                    }

                    if (tempTokens.length() > 0L) {
                        if (targetTokens.exists()) targetTokens.delete()
                        tempTokens.renameTo(targetTokens)
                        try { android.util.Log.i("LanguageModelRegistry", "Successfully extracted ${lang.displayName} tokens.txt (${targetTokens.length()} bytes)") } catch (_: Throwable) {}
                    }
                }
            } catch (_: Exception) {
                // Tokens asset not packaged in APK
            }
        }
    }
}
