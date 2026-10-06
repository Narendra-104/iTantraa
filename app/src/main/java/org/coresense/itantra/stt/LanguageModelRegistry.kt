package org.coresense.itantra.stt

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.coresense.itantra.protocol.Language
import java.io.File

enum class ModelStatus {
    INSTALLED,
    MISSING,
    LOADING,
    ERROR
}

data class ModelMetadata(
    val language: Language,
    val engineName: String,
    val modelFilename: String,
    val tokensFilename: String,
    val estimatedSizeMb: Float,
    val actualSizeMb: Float = 0f,
    val status: ModelStatus = ModelStatus.MISSING,
    val localDirectoryPath: String? = null
)

/**
 * Registry listing for each of the 10 Indic languages + English:
 * engine, model file, size MB, status (installed / missing).
 *
 * Models are side-loaded into app storage (adb/file picker/bundled assets),
 * NEVER downloaded from the internet at runtime.
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

    fun refresh() {
        val rootDir = getModelsStorageDirectory()
        extractBundledAssetsIfPresent(rootDir)
        val map = mutableMapOf<Language, ModelMetadata>()

        for (lang in Language.entries) {
            val langDir = File(rootDir, lang.code)
            val modelFile = File(langDir, "model.onnx")
            val tokensFile = File(langDir, "tokens.txt")

            val isNeuralInstalled = modelFile.exists() && modelFile.length() > 0
            val actualSize = if (isNeuralInstalled) {
                (modelFile.length() + if (tokensFile.exists()) tokensFile.length() else 0L) / (1024f * 1024f)
            } else {
                0f
            }

            val defaultSizeMb = when (lang) {
                Language.HINDI -> 78.5f
                Language.ENGLISH -> 116.3f
                Language.BENGALI -> 82.0f
                Language.TAMIL -> 85.0f
                Language.TELUGU -> 84.0f
                Language.MARATHI -> 79.0f
                Language.GUJARATI -> 77.0f
                Language.KANNADA -> 83.0f
                Language.MALAYALAM -> 86.0f
                Language.PUNJABI -> 75.0f
                Language.ODIA -> 80.0f
            }

            val engine = if (isNeuralInstalled) {
                if (lang == Language.ENGLISH) "Wav2Vec2-Base-960h CTC ONNX" else "AI4Bharat IndicConformer ONNX"
            } else {
                "Offline Model Unavailable (${lang.displayName})"
            }

            map[lang] = ModelMetadata(
                language = lang,
                engineName = engine,
                modelFilename = if (isNeuralInstalled) "model.onnx" else "MISSING",
                tokensFilename = if (isNeuralInstalled && tokensFile.exists()) "tokens.txt" else "MISSING",
                estimatedSizeMb = defaultSizeMb,
                actualSizeMb = actualSize,
                status = if (isNeuralInstalled) ModelStatus.INSTALLED else ModelStatus.MISSING,
                localDirectoryPath = if (isNeuralInstalled) langDir.absolutePath else null
            )
        }

        _models.value = map
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
        if (file.exists() && file.length() > 0) return file

        // Fallback to external files dir if side-loaded there
        if (customBaseDir == null) {
            try {
                val ext = context.getExternalFilesDir("models/stt")
                if (ext != null) {
                    val extFile = File(File(ext, lang.code), "model.onnx")
                    if (extFile.exists() && extFile.length() > 0) return extFile
                }
            } catch (_: Exception) {}
        }
        return null
    }

    fun getTokensFile(lang: Language): File? {
        val rootDir = getModelsStorageDirectory()
        val dir = File(rootDir, lang.code)
        val file = File(dir, "tokens.txt")
        if (file.exists() && file.length() > 0) return file

        if (customBaseDir == null) {
            try {
                val ext = context.getExternalFilesDir("models/stt")
                if (ext != null) {
                    val extFile = File(File(ext, lang.code), "tokens.txt")
                    if (extFile.exists() && extFile.length() > 0) return extFile
                }
            } catch (_: Exception) {}
        }
        return null
    }

    fun extractBundledAssetsIfPresent(rootDir: File = getModelsStorageDirectory()) {
        if (customBaseDir != null) return // Never extract into test mock directories
        for (lang in Language.entries) {
            val assetModelPath = "models/stt/${lang.code}/model.onnx"
            val assetTokensPath = "models/stt/${lang.code}/tokens.txt"

            val langDir = File(rootDir, lang.code)
            val targetModel = File(langDir, "model.onnx")
            val targetTokens = File(langDir, "tokens.txt")

            try {
                // Directly check asset presence by attempting to open stream
                context.assets.open(assetModelPath).use { assetStream ->
                    if (!langDir.exists()) langDir.mkdirs()

                    // Do not re-extract if file already exists with content
                    if (!targetModel.exists() || targetModel.length() == 0L) {
                        try { android.util.Log.i("LanguageModelRegistry", "Extracting asset $assetModelPath to ${targetModel.absolutePath}") } catch (_: Throwable) {}
                        targetModel.outputStream().use { output ->
                            assetStream.copyTo(output)
                        }
                        try { android.util.Log.i("LanguageModelRegistry", "Successfully extracted model.onnx (${targetModel.length()} bytes)") } catch (_: Throwable) {}
                    }
                }
            } catch (_: Exception) {
                // Asset not present for this language
            }

            try {
                context.assets.open(assetTokensPath).use { assetTokensStream ->
                    if (!langDir.exists()) langDir.mkdirs()
                    if (!targetTokens.exists() || targetTokens.length() == 0L) {
                        try { android.util.Log.i("LanguageModelRegistry", "Extracting asset $assetTokensPath to ${targetTokens.absolutePath}") } catch (_: Throwable) {}
                        targetTokens.outputStream().use { output ->
                            assetTokensStream.copyTo(output)
                        }
                        try { android.util.Log.i("LanguageModelRegistry", "Successfully extracted tokens.txt (${targetTokens.length()} bytes)") } catch (_: Throwable) {}
                    }
                }
            } catch (_: Exception) {
                // Tokens asset not present
            }
        }
    }
}

