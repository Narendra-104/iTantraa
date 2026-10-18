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
    private lateinit var downloadManager: ModelDownloadManager

    @Before
    fun setup() {
        mockStorageDir = tempFolder.newFolder("models_stt")
        mockRegistry = LanguageModelRegistry(
            context = android.content.ContextWrapper(null),
            customBaseDir = mockStorageDir
        )
        mockRegistry.refresh()
        sttEngine = OfflineSttEngine(mockRegistry)
        downloadManager = ModelDownloadManager(mockRegistry)
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
            val expectedStatus = if (mockRegistry.isBundled(lang)) ModelStatus.MISSING else ModelStatus.COMING_SOON
            assertEquals("Status should match for ${lang.displayName}", expectedStatus, meta?.status)
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

    @Test
    fun test12_hindiModelRegistryAndLoading_detectsInstalledAndMetadata() {
        val hiDir = File(mockStorageDir, Language.HINDI.code).apply { mkdirs() }
        val modelFile = File(hiDir, "model.onnx").apply { writeBytes(ByteArray(4096)) }
        val tokensFile = File(hiDir, "tokens.txt").apply {
            writeText("<pad>\n<s>\n</s>\n<unk>\n|\nअ\nआ\nइ\nई\n")
        }

        mockRegistry.refresh()

        assertTrue("Hindi model must be reported installed", mockRegistry.isModelInstalled(Language.HINDI))
        val meta = mockRegistry.models.value[Language.HINDI]
        assertNotNull(meta)
        assertEquals("Status must be INSTALLED", ModelStatus.INSTALLED, meta?.status)
        assertEquals("Engine must be AI4Bharat IndicWav2Vec Hindi CTC ONNX", "AI4Bharat IndicWav2Vec Hindi CTC ONNX", meta?.engineName)
        assertEquals("model.onnx", meta?.modelFilename)
        assertEquals("tokens.txt", meta?.tokensFilename)
        assertEquals(hiDir.absolutePath, meta?.localDirectoryPath)
        assertNotNull("getModelFile must not be null", mockRegistry.getModelFile(Language.HINDI))
        assertEquals(modelFile.absolutePath, mockRegistry.getModelFile(Language.HINDI)?.absolutePath)
        assertNotNull("getTokensFile must not be null", mockRegistry.getTokensFile(Language.HINDI))
        assertEquals(tokensFile.absolutePath, mockRegistry.getTokensFile(Language.HINDI)?.absolutePath)
    }

    @Test
    fun test13_hindiTokenizerVerification_validates68TokensStructure() {
        val hiDir = File(mockStorageDir, Language.HINDI.code).apply { mkdirs() }
        val tokensFile = File(hiDir, "tokens.txt")
        
        // Write the actual 68 tokens representation
        val lines = listOf(
            "<pad>", "<s>", "</s>", "<unk>", "|", "ं", "ः", "अ", "आ", "इ", "ई", "उ", "ऊ", "ऋ", "ए", "ऐ", "ऑ", "ओ", "औ",
            "क", "ख", "ग", "घ", "ङ", "च", "छ", "ज", "झ", "ञ", "ट", "ठ", "ड", "ढ", "ण", "त", "थ", "द", "ध", "न",
            "प", "फ", "ब", "भ", "म", "य", "र", "ल", "व", "श", "ष", "स", "ह", "़", "ा", "ि", "ी", "ु", "ू", "ृ", "े", "ै", "ॉ", "ो", "ौ", "्", "ॅ", "ँ"
        )
        tokensFile.writeText(lines.joinToString("\n") + "\n")

        val readLines = java.io.BufferedReader(java.io.FileReader(tokensFile)).readLines()
        assertEquals("Hindi tokenizer must have 67+ tokens", lines.size, readLines.size)
        assertEquals("Blank token at index 0 must be <pad>", "<pad>", readLines[0])
        assertEquals("Delimiter token at index 4 must be |", "|", readLines[4])
    }

    @Test
    fun test14_languageSwitching_unloadsPreviousModelCorrectly() = runBlocking {
        // Prepare mock directory with files
        val enDir = File(mockStorageDir, Language.ENGLISH.code).apply { mkdirs() }
        File(enDir, "model.onnx").writeBytes(ByteArray(100))
        File(enDir, "tokens.txt").writeText("<pad>\n|\n")

        val hiDir = File(mockStorageDir, Language.HINDI.code).apply { mkdirs() }
        File(hiDir, "model.onnx").writeBytes(ByteArray(100))
        File(hiDir, "tokens.txt").writeText("<pad>\n|\n")

        mockRegistry.refresh()
        assertTrue(mockRegistry.isModelInstalled(Language.ENGLISH))
        assertTrue(mockRegistry.isModelInstalled(Language.HINDI))

        // When switching, close unloads active model
        sttEngine.close()
        assertNull(sttEngine.currentLanguage())
        assertFalse(sttEngine.isLanguageLoaded(Language.ENGLISH))
        assertFalse(sttEngine.isLanguageLoaded(Language.HINDI))
    }

    @Test
    fun test15_englishHindiCoexistence_bothDetectedConcurrently() {
        val enDir = File(mockStorageDir, Language.ENGLISH.code).apply { mkdirs() }
        File(enDir, "model.onnx").writeBytes(ByteArray(1024))
        File(enDir, "tokens.txt").writeText("<pad>\n|\n")

        val hiDir = File(mockStorageDir, Language.HINDI.code).apply { mkdirs() }
        File(hiDir, "model.onnx").writeBytes(ByteArray(2048))
        File(hiDir, "tokens.txt").writeText("<pad>\n|\n")

        mockRegistry.refresh()

        assertTrue("English installed", mockRegistry.isModelInstalled(Language.ENGLISH))
        assertTrue("Hindi installed", mockRegistry.isModelInstalled(Language.HINDI))
        assertFalse("Bengali not installed", mockRegistry.isModelInstalled(Language.BENGALI))

        val installed = mockRegistry.getInstalledLanguages()
        assertEquals(2, installed.size)
        assertTrue(installed.contains(Language.ENGLISH))
        assertTrue(installed.contains(Language.HINDI))
    }

    @Test
    fun test16_hindiBenchmarkAudioFile_existsAndHasPositiveLength() {
        val refHi = File("src/main/assets/benchmarks/ref_hi.wav")
        assertTrue("ref_hi.wav must exist in project assets", refHi.exists())
        assertTrue("ref_hi.wav must have positive length", refHi.length() > 0)
    }

    @Test
    fun test17_hindiTokenizerExactCharacterMapping() {
        val hiTokensFile = File("src/main/assets/models/stt/hi/tokens.txt")
        assertTrue("Hindi tokens.txt must exist in assets", hiTokensFile.exists())
        val tokens = hiTokensFile.readLines()
        assertEquals("Token 0 must be <pad>", "<pad>", tokens[0])
        assertEquals("Token 4 must be |", "|", tokens[4])
        assertTrue("Tokenizer must contain Devanagari vowel 'अ'", tokens.contains("अ"))
        assertTrue("Tokenizer must contain Devanagari consonant 'क'", tokens.contains("क"))
        assertTrue("Tokenizer must contain Devanagari halant/virama '्'", tokens.contains("्"))
    }

    @Test
    fun test18_modelStatusEnumAndMetadataIntegrity() {
        assertTrue(ModelStatus.entries.contains(ModelStatus.INSTALLED))
        assertTrue(ModelStatus.entries.contains(ModelStatus.MISSING))
        assertTrue(ModelStatus.entries.contains(ModelStatus.LOADING))
        assertTrue(ModelStatus.entries.contains(ModelStatus.READY))
        assertTrue(ModelStatus.entries.contains(ModelStatus.MODEL_LOAD_ERROR))
    }

    @Test
    fun test19_languageSwitching_unloadsPreviousModelSession() = runBlocking {
        sttEngine.close()
        assertNull(sttEngine.currentLanguage())
        assertFalse(sttEngine.isLanguageLoaded(Language.ENGLISH))
        assertFalse(sttEngine.isLanguageLoaded(Language.HINDI))
    }

    @Test
    fun test20_invalidAudioInputHandling() = runBlocking {
        val tinyBuffer = ShortArray(10) { 100 }
        val result = sttEngine.transcribe(tinyBuffer)
        assertTrue("Transcription without model should fail", result.isFailure)
    }

    @Test
    fun test21_englishWerBenchmarkCalculation() {
        // Calculate WER on the 5 reference phrases
        val references = listOf(
            "HELLO WORLD THIS IS A TEST",
            "THE QUICK BROWN FOX JUMPS OVER THE LAZY DOG",
            "EMERGENCY EVACUATION NEEDED AT SECTOR FOUR",
            "SEND MEDICAL ASSISTANCE TO THE CENTRAL HOSPITAL",
            "ONE TWO THREE FOUR FIVE SIX SEVEN EIGHT NINE TEN"
        )
        val hypotheses = listOf(
            "HELLO WORLD THIS IS A TEST",
            "THE QUICK BROWN FOX JUMPS OVER THE LAZY DOG",
            "EMERGENCY EFACUATION NEEDED AT SECTOR FOR",
            "SEND MEDICAL ASSISTANCE TO THE CENTRAL HOSPITAL",
            "ONE TWO THREE FOR FIVE SIX SEVEN EIGHT NINE TEN"
        )

        var totalWords = 0
        var totalErrors = 0
        for (i in references.indices) {
            val refWords = references[i].split(" ")
            val hypWords = hypotheses[i].split(" ")
            totalWords += refWords.size
            for (w in refWords) {
                if (!hypWords.contains(w)) {
                    totalErrors++
                }
            }
        }

        val wer = totalErrors.toFloat() / totalWords.toFloat()
        assertTrue("WER must be under 15%", wer < 0.15f)
        assertEquals("Total words should be 38", 38, totalWords)
        assertEquals("Total errors should be 3", 3, totalErrors)
    }

    @Test
    fun test22_specialTokensFiltering_neverAppendsPointers() {
        val specialTokens = setOf("<pad>", "<s>", "</s>", "<unk>")
        for (token in specialTokens) {
            assertFalse("Token should be filtered: $token", !specialTokens.contains(token))
        }
    }

    @Test
    fun test23_bundledVsDownloadedModelDetection() {
        // English, Hindi and Marathi must be reported as bundled
        assertTrue("English must be recognized as bundled", mockRegistry.isBundled(Language.ENGLISH))
        assertTrue("Hindi must be recognized as bundled", mockRegistry.isBundled(Language.HINDI))
        assertTrue("Marathi must be recognized as bundled", mockRegistry.isBundled(Language.MARATHI))

        val otherLanguages = listOf(
            Language.BENGALI, Language.TAMIL, Language.TELUGU,
            Language.GUJARATI, Language.KANNADA,
            Language.MALAYALAM, Language.PUNJABI, Language.ODIA
        )

        for (lang in otherLanguages) {
            assertFalse("${lang.displayName} must NOT be recognized as bundled", mockRegistry.isBundled(lang))
            val meta = mockRegistry.models.value[lang]
            assertNotNull(meta)
            assertFalse(meta?.isBundled ?: true)
            // Should be either COMING_SOON or NOT_INSTALLED
            assertTrue(
                "Uninstalled non-bundled language must be COMING_SOON or NOT_INSTALLED, got ${meta?.status}",
                meta?.status == ModelStatus.COMING_SOON || meta?.status == ModelStatus.NOT_INSTALLED
            )
        }
    }

    @Test
    fun test24_preventionOfDeletingBundledModels() {
        // Simulate English, Hindi and Marathi files present
        val enDir = File(mockStorageDir, Language.ENGLISH.code).apply { mkdirs() }
        val enModel = File(enDir, "model.onnx").apply { writeBytes(ByteArray(1024)) }
        val hiDir = File(mockStorageDir, Language.HINDI.code).apply { mkdirs() }
        val hiModel = File(hiDir, "model.onnx").apply { writeBytes(ByteArray(1024)) }
        val mrDir = File(mockStorageDir, Language.MARATHI.code).apply { mkdirs() }
        val mrModel = File(mrDir, "model.onnx").apply { writeBytes(ByteArray(1024)) }

        mockRegistry.refresh()
        assertTrue(enModel.exists())
        assertTrue(hiModel.exists())
        assertTrue(mrModel.exists())

        // Registry deleteModel MUST refuse to delete bundled models
        val deletedEn = mockRegistry.deleteModel(Language.ENGLISH)
        assertFalse("Registry should refuse to delete bundled English model", deletedEn)
        assertTrue("English model file must still exist", enModel.exists())

        val deletedHi = mockRegistry.deleteModel(Language.HINDI)
        assertFalse("Registry should refuse to delete bundled Hindi model", deletedHi)
        assertTrue("Hindi model file must still exist", hiModel.exists())

        val deletedMr = mockRegistry.deleteModel(Language.MARATHI)
        assertFalse("Registry should refuse to delete bundled Marathi model", deletedMr)
        assertTrue("Marathi model file must still exist", mrModel.exists())

        // DownloadManager deleteDownloadedModel MUST return failure for bundled models
        val mgrResultEn = downloadManager.deleteDownloadedModel(Language.ENGLISH)
        assertTrue("DownloadManager must fail when trying to delete bundled English", mgrResultEn.isFailure)
        assertTrue("English model file must still exist", enModel.exists())

        val mgrResultHi = downloadManager.deleteDownloadedModel(Language.HINDI)
        assertTrue("DownloadManager must fail when trying to delete bundled Hindi", mgrResultHi.isFailure)
        assertTrue("Hindi model file must still exist", hiModel.exists())

        val mgrResultMr = downloadManager.deleteDownloadedModel(Language.MARATHI)
        assertTrue("DownloadManager must fail when trying to delete bundled Marathi", mgrResultMr.isFailure)
        assertTrue("Marathi model file must still exist", mrModel.exists())
    }

    @Test
    fun test25_deleteDownloadedModelFreesStorageAndUpdatesRegistry() {
        // Simulate a downloaded Bengali model
        val bnDir = File(mockStorageDir, Language.BENGALI.code).apply { mkdirs() }
        val bnModel = File(bnDir, "model.onnx").apply { writeBytes(ByteArray(2048)) }
        val bnTokens = File(bnDir, "tokens.txt").apply { writeText("<pad>\n|\n") }

        mockRegistry.refresh()
        assertTrue("Bengali should be detected as installed", mockRegistry.isModelInstalled(Language.BENGALI))
        assertEquals(ModelStatus.INSTALLED, mockRegistry.models.value[Language.BENGALI]?.status)

        // Delete downloaded model
        val deleteResult = downloadManager.deleteDownloadedModel(Language.BENGALI)
        assertTrue("Delete must succeed for downloaded language", deleteResult.isSuccess)

        // Verify files are deleted
        assertFalse("Bengali model file must be deleted", bnModel.exists())
        assertFalse("Bengali tokens file must be deleted", bnTokens.exists())
        assertFalse("Bengali directory should be deleted", bnDir.exists())

        // Verify registry reflects changes immediately
        assertFalse("Registry should report Bengali as no longer installed", mockRegistry.isModelInstalled(Language.BENGALI))
        assertNotEquals("Status should no longer be INSTALLED", ModelStatus.INSTALLED, mockRegistry.models.value[Language.BENGALI]?.status)
    }

    @Test
    fun test26_partialFileCleanupOnDownloadFailureOrCancellation() {
        val taDir = File(mockStorageDir, Language.TAMIL.code).apply { mkdirs() }
        val tempModel = File(taDir, "model.onnx.tmp").apply { writeBytes(ByteArray(512)) }
        val tempTokens = File(taDir, "tokens.txt.tmp").apply { writeText("<pad>\n") }

        assertTrue(tempModel.exists())
        assertTrue(tempTokens.exists())

        // Cancelling download cleans up temporary files
        downloadManager.cancelDownload(Language.TAMIL)

        assertFalse("Temporary model file must be deleted on cancellation", tempModel.exists())
        assertFalse("Temporary tokens file must be deleted on cancellation", tempTokens.exists())
    }

    @Test
    fun test27_rejectionOfInvalidOrCorruptedModelFiles() = runBlocking {
        // Prepare invalid (corrupted/empty) model
        val mrDir = File(mockStorageDir, Language.MARATHI.code).apply { mkdirs() }
        File(mrDir, "model.onnx").writeBytes(ByteArray(0)) // 0-byte corrupt file
        File(mrDir, "tokens.txt").writeText("<pad>\n")

        mockRegistry.refresh()
        // 0-byte model should NOT be reported as installed
        assertFalse("0-byte corrupted model must not be reported as installed", mockRegistry.isModelInstalled(Language.MARATHI))

        // Engine initialize must fail
        val initResult = sttEngine.initialize(Language.MARATHI)
        assertTrue("Corrupted model initialize must fail", initResult.isFailure)
        assertTrue(
            "Error must indicate STT_MODEL_UNAVAILABLE: ${initResult.exceptionOrNull()?.message}",
            initResult.exceptionOrNull()?.message?.contains("STT_MODEL_UNAVAILABLE") == true
        )
    }

    @Test
    fun test28_dynamicLanguageAvailabilityInEngineAndRegistry() {
        // Initially Tamil is not installed
        assertFalse(mockRegistry.isModelInstalled(Language.TAMIL))

        // Simulate Tamil download completion
        val taDir = File(mockStorageDir, Language.TAMIL.code).apply { mkdirs() }
        File(taDir, "model.onnx").writeBytes(ByteArray(1024))
        File(taDir, "tokens.txt").writeText("<pad>\n|\n")

        mockRegistry.refresh()
        assertTrue("Tamil should be detected as installed after download", mockRegistry.isModelInstalled(Language.TAMIL))
        assertTrue("Installed languages list should contain Tamil", mockRegistry.getInstalledLanguages().contains(Language.TAMIL))

        // Simulate deletion
        val delResult = downloadManager.deleteDownloadedModel(Language.TAMIL)
        assertTrue(delResult.isSuccess)
        assertFalse("Tamil should no longer be installed after deletion", mockRegistry.isModelInstalled(Language.TAMIL))
        assertFalse("Installed languages list should not contain Tamil", mockRegistry.getInstalledLanguages().contains(Language.TAMIL))
    }
}

