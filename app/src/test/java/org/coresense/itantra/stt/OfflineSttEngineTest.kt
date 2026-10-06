package org.coresense.itantra.stt

import kotlinx.coroutines.runBlocking
import org.coresense.itantra.protocol.Language
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OfflineSttEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var mockRegistry: LanguageModelRegistry
    private lateinit var sttEngine: OfflineSttEngine
    private lateinit var mockStorageDir: File

    @Before
    fun setup() {
        mockStorageDir = tempFolder.newFolder("models_stt")
        mockRegistry = LanguageModelRegistry(
            context = android.content.ContextWrapper(null),
            customBaseDir = mockStorageDir
        )
        mockRegistry.refresh()
        sttEngine = OfflineSttEngine(mockRegistry)
    }

    @After
    fun tearDown() {
        sttEngine.close()
    }

    @Test
    fun test1_modelAvailabilityDetection_reportsMissingWhenNoFiles() {
        for (lang in Language.entries) {
            assertFalse("Model should not be reported installed when file missing: ${lang.displayName}",
                mockRegistry.isModelInstalled(lang))
            val meta = mockRegistry.models.value[lang]
            assertNotNull(meta)
            assertEquals("Status should be MISSING", ModelStatus.MISSING, meta?.status)
            assertEquals("Actual size should be 0", 0f, meta?.actualSizeMb ?: -1f, 0.001f)
        }
        assertTrue("Installed languages should be empty", mockRegistry.getInstalledLanguages().isEmpty())
    }

    @Test
    fun test2_missingModelHandling_returnsModelUnavailableError() = runBlocking {
        val result = sttEngine.initialize(Language.HINDI)
        assertTrue("Initialize should fail when model file is missing", result.isFailure)
        val ex = result.exceptionOrNull()
        assertNotNull(ex)
        assertTrue("Error message must indicate STT_MODEL_UNAVAILABLE: ${ex?.message}",
            ex?.message?.contains("STT_MODEL_UNAVAILABLE") == true)
        assertFalse("isLanguageLoaded should be false", sttEngine.isLanguageLoaded(Language.HINDI))
    }

    @Test
    fun test3_unloadedTranscribeFailsWithModelUnavailable() = runBlocking {
        val pcm = ShortArray(16000) { 1000 }
        val result = sttEngine.transcribe(pcm)
        assertTrue("Transcribe without loaded model must fail", result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue("Error must state STT_MODEL_UNAVAILABLE: ${ex?.message}",
            ex?.message?.contains("STT_MODEL_UNAVAILABLE") == true)
    }

    @Test
    fun test4_emptyAudioBufferHandling() = runBlocking {
        val emptyPcm = ShortArray(0)
        val result = sttEngine.transcribe(emptyPcm)
        assertTrue("Transcribe on empty audio must fail", result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue("Error message should mention empty audio: ${ex?.message}",
            ex?.message?.contains("empty", ignoreCase = true) == true)
    }

    @Test
    fun test5_silenceOrLowRmsAudioHandling() = runBlocking {
        // Even if an engine were somehow initialized, silence should fail gracefully
        val silencePcm = ShortArray(16000) { 0 }
        val result = sttEngine.transcribe(silencePcm)
        assertTrue(result.isFailure)
    }

    @Test
    fun test6_unsupportedOrMissingLanguagesAllFailGracefully() = runBlocking {
        for (lang in Language.entries) {
            val res = sttEngine.initialize(lang)
            assertTrue("Language ${lang.displayName} should fail when model missing", res.isFailure)
            val msg = res.exceptionOrNull()?.message ?: ""
            assertTrue("Failure message must contain STT_MODEL_UNAVAILABLE: $msg",
                msg.contains("STT_MODEL_UNAVAILABLE"))
        }
    }

    @Test
    fun test7_cancellationAndResourceCleanup() = runBlocking {
        sttEngine.close()
        assertNull("currentLanguage must be null after close", sttEngine.currentLanguage())
        assertFalse("isLanguageLoaded must be false after close", sttEngine.isLanguageLoaded(Language.ENGLISH))
    }

    @Test
    fun test8_noFakeTranscriptionStringsReturned() = runBlocking {
        val pcm = ShortArray(16000) { (it % 1000).toShort() }
        val res = sttEngine.transcribe(pcm)
        assertTrue("Result must be failure, not fake success", res.isFailure)
        
        // Ensure no dummy text is returned
        val dummyPhrases = listOf(
            "नमस्ते", "Hello", "emergency", "Test message", "Sample transcription",
            "জরুরী সাহায্য", "அவசர உதவி", "అత్యవసర సహాయం"
        )
        val exceptionMsg = res.exceptionOrNull()?.message ?: ""
        for (dummy in dummyPhrases) {
            assertFalse("Error message or result must not contain fake phrase '$dummy'",
                exceptionMsg.contains(dummy))
        }
    }

    @Test
    fun test9_registryDetectsInstalledModelWhenFileExists() {
        val hindiDir = File(mockStorageDir, Language.HINDI.code).apply { mkdirs() }
        val modelFile = File(hindiDir, "model.onnx").apply { writeBytes(ByteArray(1024)) }
        val tokensFile = File(hindiDir, "tokens.txt").apply { writeText("<blank>\nhello\nworld\n") }

        mockRegistry.refresh()

        assertTrue("Hindi model should be detected as installed", mockRegistry.isModelInstalled(Language.HINDI))
        val meta = mockRegistry.models.value[Language.HINDI]
        assertEquals("Status should be INSTALLED", ModelStatus.INSTALLED, meta?.status)
        assertTrue("Actual size should be > 0", (meta?.actualSizeMb ?: 0f) > 0f)
        assertEquals(listOf(Language.HINDI), mockRegistry.getInstalledLanguages())
    }

    @Test
    fun test10_sttResultPropertiesIntegrity() {
        val result = SttResult(
            text = "alpha bravo",
            confidence = 0.95f,
            processingTimeMs = 120L,
            audioDurationMs = 1000L
        )
        assertEquals("alpha bravo", result.text)
        assertEquals(0.95f, result.confidence, 0.001f)
        assertEquals(120L, result.processingTimeMs)
        assertEquals(1000L, result.audioDurationMs)
        assertEquals(0.12f, result.rtf, 0.001f)
    }

    @Test
    fun test11_englishModelRegistryAndLoading_doesNotReportUnavailableWhenFilesPresent() {
        val enDir = File(mockStorageDir, Language.ENGLISH.code).apply { mkdirs() }
        val modelFile = File(enDir, "model.onnx").apply { writeBytes(ByteArray(2048)) }
        val tokensFile = File(enDir, "tokens.txt").apply {
            writeText("<pad>\n<s>\n</s>\n<unk>\n|\nE\nT\nA\nO\nN\nI\nH\nS\nR\nD\nL\nU\nM\nW\nC\nF\nG\nY\nP\nB\nV\nK\n'\nX\nJ\nQ\nZ\n")
        }

        mockRegistry.refresh()

        assertTrue("English model must be reported installed", mockRegistry.isModelInstalled(Language.ENGLISH))
        val meta = mockRegistry.models.value[Language.ENGLISH]
        assertNotNull(meta)
        assertEquals("Status must be INSTALLED", ModelStatus.INSTALLED, meta?.status)
        assertEquals("Engine must be Wav2Vec2-Base-960h CTC ONNX", "Wav2Vec2-Base-960h CTC ONNX", meta?.engineName)
        assertEquals("model.onnx", meta?.modelFilename)
        assertEquals("tokens.txt", meta?.tokensFilename)
        assertEquals(enDir.absolutePath, meta?.localDirectoryPath)
        assertNotNull("getModelFile must not be null", mockRegistry.getModelFile(Language.ENGLISH))
        assertEquals(modelFile.absolutePath, mockRegistry.getModelFile(Language.ENGLISH)?.absolutePath)
        assertNotNull("getTokensFile must not be null", mockRegistry.getTokensFile(Language.ENGLISH))
        assertEquals(tokensFile.absolutePath, mockRegistry.getTokensFile(Language.ENGLISH)?.absolutePath)

        // Verify tokens count and critical indices
        val tokens = java.io.BufferedReader(java.io.FileReader(tokensFile)).readLines()
        assertEquals("Token count must be 32", 32, tokens.size)
        assertEquals("Index 0 must be <pad>", "<pad>", tokens[0])
        assertEquals("Index 4 must be |", "|", tokens[4])
    }
}

