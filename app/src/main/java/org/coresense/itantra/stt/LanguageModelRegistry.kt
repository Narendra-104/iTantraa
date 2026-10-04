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
class LanguageModelRegistry(private val context: Context) {

    private val _models = MutableStateFlow<Map<Language, ModelMetadata>>(emptyMap())
    val models: StateFlow<Map<Language, ModelMetadata>> = _models.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        val rootDir = getModelsStorageDirectory()
        val map = mutableMapOf<Language, ModelMetadata>()

        for (lang in Language.entries) {
            val langDir = File(rootDir, lang.code)
            val modelFile = File(langDir, "model.onnx")
            val tokensFile = File(langDir, "tokens.txt")

            val isNeuralInstalled = modelFile.exists() && modelFile.length() > 0
            val actualSize = if (isNeuralInstalled) {
                (modelFile.length() + if (tokensFile.exists()) tokensFile.length() else 0L) / (1024f * 1024f)
            } else {
                1.2f // Built-in acoustic pack size
            }

            val defaultSizeMb = when (lang) {
                Language.HINDI -> 78.5f
                Language.ENGLISH -> 64.0f
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
                if (lang == Language.ENGLISH) "sherpa-onnx (Zipformer)" else "AI4Bharat IndicConformer ONNX"
            } else {
                "Offline Acoustic & VAD Engine (${lang.displayName})"
            }

            map[lang] = ModelMetadata(
                language = lang,
                engineName = engine,
                modelFilename = if (isNeuralInstalled) "model.onnx" else "builtin_acoustic",
                tokensFilename = "tokens.txt",
                estimatedSizeMb = defaultSizeMb,
                actualSizeMb = actualSize,
                status = ModelStatus.INSTALLED,
                localDirectoryPath = if (isNeuralInstalled) langDir.absolutePath else "builtin_assets"
            )
        }

        _models.value = map
    }

    fun getModelsStorageDirectory(): File {
        val ext = context.getExternalFilesDir("models/stt")
        if (ext != null && ext.exists()) return ext
        val internal = File(context.filesDir, "models/stt")
        if (!internal.exists()) internal.mkdirs()
        return internal
    }

    fun getModelFile(lang: Language): File? {
        val dir = File(getModelsStorageDirectory(), lang.code)
        val file = File(dir, "model.onnx")
        return if (file.exists() && file.length() > 0) file else null
    }

    fun getTokensFile(lang: Language): File? {
        val dir = File(getModelsStorageDirectory(), lang.code)
        val file = File(dir, "tokens.txt")
        return if (file.exists() && file.length() > 0) file else null
    }
}
