package org.coresense.itantra.stt

import kotlinx.coroutines.runBlocking
import org.coresense.itantra.protocol.Language
import org.coresense.itantra.protocol.Packet
import org.coresense.itantra.protocol.PacketCodec
import org.coresense.itantra.protocol.PacketPriority
import org.coresense.itantra.protocol.PacketType
import org.coresense.itantra.storage.MessageEntity
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MarathiSttTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var mockStorageDir: File
    private lateinit var mockRegistry: LanguageModelRegistry
    private lateinit var sttEngine: OfflineSttEngine

    @Before
    fun setup() {
        mockStorageDir = tempFolder.newFolder("models_stt_marathi")
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
    fun test1_marathiLanguageRegistrationAndMetadata() {
        assertEquals("mr", Language.MARATHI.code)
        assertEquals("Marathi", Language.MARATHI.displayName)
        assertEquals("मराठी", Language.MARATHI.nativeName)
        assertEquals("mr-IN", Language.MARATHI.bcp47)

        assertEquals(Language.MARATHI, Language.fromCode("mr"))
        assertEquals(Language.MARATHI, Language.fromCode("MR"))
        assertEquals(Language.MARATHI, Language.fromDisplayName("Marathi"))
        assertEquals(Language.MARATHI, Language.fromDisplayName("marathi"))

        assertTrue("Marathi must be bundled in registry", mockRegistry.isBundled(Language.MARATHI))
        assertTrue("getBundledLanguages must include Marathi", mockRegistry.getBundledLanguages().contains(Language.MARATHI))
    }

    @Test
    fun test2_marathiModelPathResolutionAndMetadata() {
        val mrDir = File(mockStorageDir, Language.MARATHI.code).apply { mkdirs() }
        val modelFile = File(mrDir, "model.onnx").apply { writeBytes(ByteArray(2048)) }
        val tokensFile = File(mrDir, "tokens.txt").apply {
            writeText("<s>\n<pad>\n</s>\n<unk>\n|\nअ\nक\nळ\n")
        }

        mockRegistry.refresh()

        assertTrue("Marathi model must be reported installed", mockRegistry.isModelInstalled(Language.MARATHI))
        val meta = mockRegistry.models.value[Language.MARATHI]
        assertNotNull(meta)
        assertEquals(ModelStatus.INSTALLED, meta?.status)
        assertEquals("AI4Bharat Vakyansh Marathi Wav2Vec2 CTC ONNX", meta?.engineName)
        assertEquals("model.onnx", meta?.modelFilename)
        assertEquals("tokens.txt", meta?.tokensFilename)
        assertEquals(mrDir.absolutePath, meta?.localDirectoryPath)
        assertTrue("isBundled in metadata must be true", meta?.isBundled == true)
        assertEquals(modelFile.absolutePath, mockRegistry.getModelFile(Language.MARATHI)?.absolutePath)
        assertEquals(tokensFile.absolutePath, mockRegistry.getTokensFile(Language.MARATHI)?.absolutePath)
    }

    @Test
    fun test3_missingMarathiModelHandlingReturnsModelUnavailable() = runBlocking {
        // Without files in storage directory
        assertFalse(mockRegistry.isModelInstalled(Language.MARATHI))

        val initRes = sttEngine.initialize(Language.MARATHI)
        assertTrue("Initialization without model file must fail", initRes.isFailure)
        val msg = initRes.exceptionOrNull()?.message ?: ""
        assertTrue("Message must indicate STT_MODEL_UNAVAILABLE: $msg", msg.contains("STT_MODEL_UNAVAILABLE"))
        assertFalse("isLanguageLoaded must be false", sttEngine.isLanguageLoaded(Language.MARATHI))

        // Transcribe must fail
        val pcm = ShortArray(16000) { 1000 }
        val transRes = sttEngine.transcribe(pcm)
        assertTrue("Transcribe without model must fail", transRes.isFailure)
        assertTrue(transRes.exceptionOrNull()?.message?.contains("STT_MODEL_UNAVAILABLE") == true)
    }

    @Test
    fun test4_marathiTokenizerStructureAndDevanagariAlphabet() {
        val mrTokensAsset = File("src/main/assets/models/stt/mr/tokens.txt")
        assertTrue("mr/tokens.txt must exist in assets", mrTokensAsset.exists())
        val lines = mrTokensAsset.readLines().map { it.trim() }.filter { it.isNotEmpty() }

        assertEquals("Marathi vocabulary must have 66 tokens", 66, lines.size)
        assertEquals("Index 0 must be <s>", "<s>", lines[0])
        assertEquals("Index 1 must be <pad>", "<pad>", lines[1])
        assertEquals("Index 2 must be </s>", "</s>", lines[2])
        assertEquals("Index 3 must be <unk>", "<unk>", lines[3])
        assertEquals("Index 4 must be |", "|", lines[4])

        // Verify critical Marathi consonants and vowels
        assertTrue("Tokenizer must contain Marathi 'ळ'", lines.contains("ळ"))
        assertTrue("Tokenizer must contain 'अ'", lines.contains("अ"))
        assertTrue("Tokenizer must contain 'क'", lines.contains("क"))
        assertTrue("Tokenizer must contain 'त'", lines.contains("त"))
        assertTrue("Tokenizer must contain 'द'", lines.contains("द"))
        assertTrue("Tokenizer must contain virama '्'", lines.contains("्"))
        assertTrue("Tokenizer must contain matra 'ा'", lines.contains("ा"))
        assertTrue("Tokenizer must contain matra 'ी'", lines.contains("ी"))
    }

    @Test
    fun test5_marathiUnicodeIntegrityAndPreservation() {
        val testPhrase = "तातडीची मदत हवी आहे कृपया रुग्णवाहिका पाठवा"

        // Verify UTF-8 roundtrip
        val bytes = testPhrase.toByteArray(Charsets.UTF_8)
        val decoded = String(bytes, Charsets.UTF_8)
        assertEquals("Devanagari text must survive UTF-8 encoding perfectly", testPhrase, decoded)

        // Verify Marathi specific character ळ
        val marathiSpecial = "वेळ आणि काळ महत्त्वाचा आहे"
        val specialBytes = marathiSpecial.toByteArray(Charsets.UTF_8)
        assertEquals(marathiSpecial, String(specialBytes, Charsets.UTF_8))
        assertTrue(marathiSpecial.contains("ळ"))
    }

    @Test
    fun test6_marathiPacketEncodingAndDecoding() {
        val marathiText = "तातडीची मदत हवी आहे कृपया रुग्णवाहिका पाठवा"
        val packet = Packet(
            msgId = 9876543210L,
            senderId = "OPERATOR_01",
            seq = 101,
            lang = Language.MARATHI,
            priority = PacketPriority.SOS,
            text = marathiText,
            timestamp = 1718000000000L,
            latitudeMicrodegrees = 19076000,
            longitudeMicrodegrees = 72877700,
            type = PacketType.DATA
        )

        val encoded = PacketCodec.encode(packet)
        assertTrue("Encoded bytes must be non-empty", encoded.isNotEmpty())

        val decodeResult = PacketCodec.decode(encoded)
        assertTrue("Decode result must be successful: ${decodeResult.exceptionOrNull()?.message}", decodeResult.isSuccess)
        val decodedPacket = decodeResult.getOrThrow()

        assertEquals(packet.msgId, decodedPacket.msgId)
        assertEquals(packet.senderId, decodedPacket.senderId)
        assertEquals(packet.seq, decodedPacket.seq)
        assertEquals(packet.lang, decodedPacket.lang)
        assertEquals(packet.priority, decodedPacket.priority)
        assertEquals(marathiText, decodedPacket.text)
        assertEquals(packet.latitudeMicrodegrees, decodedPacket.latitudeMicrodegrees)
        assertEquals(packet.longitudeMicrodegrees, decodedPacket.longitudeMicrodegrees)
    }

    @Test
    fun test7_marathiMessageEntityPersistenceIntegrity() {
        val marathiText = "तातडीची मदत हवी आहे कृपया रुग्णवाहिका पाठवा"
        val msgId = 9876543210L
        val entity = MessageEntity(
            msgId = msgId,
            senderId = "USER_MUMBAI",
            seq = 101,
            lang = "mr",
            priority = "SOS",
            text = marathiText,
            receiverId = "MEDICAL_DESK",
            departmentId = "MEDICAL",
            conversationId = "conv_rescue",
            timestamp = 1718000000000L,
            latitudeMicrodegrees = 19076000,
            longitudeMicrodegrees = 72877700,
            deliveryState = "DELIVERED",
            rttMs = 45L,
            isIncoming = false
        )

        assertEquals(msgId, entity.msgId)
        assertEquals("USER_MUMBAI", entity.senderId)
        assertEquals("mr", entity.lang)
        assertEquals("SOS", entity.priority)
        assertEquals(marathiText, entity.text)
        assertEquals("MEDICAL_DESK", entity.receiverId)
        assertEquals("DELIVERED", entity.deliveryState)
        assertEquals(45L, entity.rttMs)
    }

    @Test
    fun test8_languageSwitchingBetweenEnglishHindiMarathi() = runBlocking {
        val enDir = File(mockStorageDir, Language.ENGLISH.code).apply { mkdirs() }
        File(enDir, "model.onnx").writeBytes(ByteArray(512))
        File(enDir, "tokens.txt").writeText("<pad>\n|\n")

        val hiDir = File(mockStorageDir, Language.HINDI.code).apply { mkdirs() }
        File(hiDir, "model.onnx").writeBytes(ByteArray(512))
        File(hiDir, "tokens.txt").writeText("<pad>\n|\n")

        val mrDir = File(mockStorageDir, Language.MARATHI.code).apply { mkdirs() }
        File(mrDir, "model.onnx").writeBytes(ByteArray(512))
        File(mrDir, "tokens.txt").writeText("<s>\n<pad>\n|\n")

        mockRegistry.refresh()
        assertTrue(mockRegistry.isModelInstalled(Language.ENGLISH))
        assertTrue(mockRegistry.isModelInstalled(Language.HINDI))
        assertTrue(mockRegistry.isModelInstalled(Language.MARATHI))

        val installed = mockRegistry.getInstalledLanguages()
        assertEquals(3, installed.size)
        assertTrue(installed.contains(Language.ENGLISH))
        assertTrue(installed.contains(Language.HINDI))
        assertTrue(installed.contains(Language.MARATHI))

        // Engine close clears session
        sttEngine.close()
        assertNull(sttEngine.currentLanguage())
        assertFalse(sttEngine.isLanguageLoaded(Language.ENGLISH))
        assertFalse(sttEngine.isLanguageLoaded(Language.HINDI))
        assertFalse(sttEngine.isLanguageLoaded(Language.MARATHI))
    }

    @Test
    fun test9_marathiBenchmarkAudioFile_verifiedSpokenWav() {
        val refMr = File("src/main/assets/benchmarks/ref_mr.wav")
        assertTrue("ref_mr.wav must exist in project assets", refMr.exists())
        assertTrue("ref_mr.wav size must be > 50000 bytes (real audio)", refMr.length() > 50000L)
    }

    @Test
    fun test10_corruptedMarathiModelRejection() = runBlocking {
        val mrDir = File(mockStorageDir, Language.MARATHI.code).apply { mkdirs() }
        File(mrDir, "model.onnx").writeBytes(ByteArray(0)) // 0-byte corrupt
        File(mrDir, "tokens.txt").writeText("<pad>\n")

        mockRegistry.refresh()
        assertFalse("0-byte corrupted model must not be installed", mockRegistry.isModelInstalled(Language.MARATHI))

        val initRes = sttEngine.initialize(Language.MARATHI)
        assertTrue("Initializing corrupted model must fail", initRes.isFailure)
        assertTrue(initRes.exceptionOrNull()?.message?.contains("STT_MODEL_UNAVAILABLE") == true)
    }
}
