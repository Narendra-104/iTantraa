package org.coresense.itantra.ui

import android.app.Application
import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.map
import org.coresense.itantra.protocol.PacketCodec
import org.coresense.itantra.ITantraApp
import org.coresense.itantra.audio.AudioRecorder
import org.coresense.itantra.audio.TransmissionService
import org.coresense.itantra.emergency.Facility
import org.coresense.itantra.emergency.FacilityDirectory
import org.coresense.itantra.emergency.GpsLocationProvider
import org.coresense.itantra.emergency.GpsState
import org.coresense.itantra.emergency.SosAlertManager
import org.coresense.itantra.link.BluetoothTransport
import org.coresense.itantra.link.ConnectionState
import org.coresense.itantra.link.LanUdpTransport
import org.coresense.itantra.link.LinkTransport
import org.coresense.itantra.link.PeerDevice
import org.coresense.itantra.link.TransportType
import org.coresense.itantra.link.WifiDirectTransport
import org.coresense.itantra.metrics.BenchmarkProgress
import org.coresense.itantra.metrics.BenchmarkRunner
import org.coresense.itantra.metrics.LiveDeviceMetrics
import org.coresense.itantra.metrics.MetricsCollector
import org.coresense.itantra.protocol.Language
import org.coresense.itantra.protocol.Packet
import org.coresense.itantra.protocol.PacketPriority
import org.coresense.itantra.protocol.PacketType
import org.coresense.itantra.reliability.DeliveryState
import org.coresense.itantra.reliability.ReliabilityEngine
import org.coresense.itantra.reliability.ReliabilityStats
import org.coresense.itantra.sentence.SentenceAssembler
import org.coresense.itantra.storage.MessageEntity
import org.coresense.itantra.stt.EnglishSpeechNormalizer
import org.coresense.itantra.stt.LanguageModelRegistry
import org.coresense.itantra.stt.ModelDownloadManager
import org.coresense.itantra.stt.ModelMetadata
import org.coresense.itantra.stt.OfflineSttEngine
import org.coresense.itantra.stt.SttEngine
import org.coresense.itantra.tts.OfflineAndroidTtsEngine
import org.coresense.itantra.tts.TtsEngine
import org.coresense.itantra.tts.TtsStatus
import org.coresense.itantra.vad.SileroVadDetector
import org.coresense.itantra.vad.VadEvent
import org.coresense.itantra.identity.Department
import org.coresense.itantra.identity.Role
import org.coresense.itantra.storage.ConversationEntity
import java.util.UUID

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val context: Context = application.applicationContext
    private val database = ITantraApp.instance.database
    val identityRepository = org.coresense.itantra.data.repository.IdentityRepository(context)
    private val messageRepository = org.coresense.itantra.data.repository.MessageRepository(ITantraApp.instance.database.messageDao(), ITantraApp.instance.database.conversationDao())

    // Settings State
    val callsign = MutableStateFlow("CORE-ALPHA")
    val receiverDepartment = MutableStateFlow("GLOBAL") // GLOBAL, NDRF, POLICE, FIRE
    val selectedLanguage = MutableStateFlow(Language.HINDI)
    val isWalkieTalkieMode = MutableStateFlow(true) // true = Walkie Talkie (PTT), false = Continuous Call (VAD)
    val isReadBackEnabled = MutableStateFlow(false)
    val vadSensitivity = MutableStateFlow(0.5f)

    // Audio & VAD
    val audioRecorder = AudioRecorder()
    val vadDetector = SileroVadDetector()
    val audioLevel = audioRecorder.audioLevel
    val isRecording = audioRecorder.isCapturing

    // STT & TTS
    val modelRegistry = LanguageModelRegistry(context)
    val sttEngine: SttEngine = OfflineSttEngine(modelRegistry)
    val downloadManager = ModelDownloadManager(modelRegistry)
    val ttsEngine: TtsEngine = OfflineAndroidTtsEngine(context)
    val sentenceAssembler = SentenceAssembler()

    fun setAppLanguage(lang: Language): Boolean {
        if (!modelRegistry.isModelInstalled(lang)) {
            liveTranscript.value = "${lang.displayName} STT [Not Installed - Download in Settings]"
            return false
        }
        selectedLanguage.value = lang
        liveTranscript.value = "${lang.displayName} STT [Loading...]"
        modelRegistry.updateModelStatus(lang, org.coresense.itantra.stt.ModelStatus.LOADING)
        viewModelScope.launch(Dispatchers.IO) {
            val res = sttEngine.initialize(lang)
            if (res.isFailure) {
                val err = res.exceptionOrNull()?.message ?: "Failed to load model"
                android.util.Log.e("MainViewModel", "STT init failed for ${lang.displayName}: $err")
                val isUnavailable = err.contains("STT_MODEL_UNAVAILABLE")
                val status = if (isUnavailable) org.coresense.itantra.stt.ModelStatus.MODEL_UNAVAILABLE else org.coresense.itantra.stt.ModelStatus.MODEL_LOAD_ERROR
                modelRegistry.updateModelStatus(lang, status, err)
                liveTranscript.value = if (isUnavailable) {
                    "${lang.displayName} STT [Model Unavailable]"
                } else {
                    "${lang.displayName} STT [Load Error: ${err.take(30)}]"
                }
            } else {
                android.util.Log.i("MainViewModel", "STT initialized successfully for ${lang.displayName}")
                modelRegistry.updateModelStatus(lang, org.coresense.itantra.stt.ModelStatus.READY)
                liveTranscript.value = "${lang.displayName} STT [Ready]"
            }
        }
        return true
    }

    fun downloadLanguage(lang: Language) {
        downloadManager.startDownload(lang, viewModelScope) { result: Result<Unit> ->
            if (result.isSuccess) {
                modelRegistry.updateModelStatus(lang, org.coresense.itantra.stt.ModelStatus.READY)
            }
        }
    }

    fun cancelLanguageDownload(lang: Language) {
        downloadManager.cancelDownload(lang)
    }

    fun deleteLanguage(lang: Language): Result<Unit> {
        if (selectedLanguage.value == lang) {
            setAppLanguage(Language.HINDI)
        }
        return downloadManager.deleteDownloadedModel(lang)
    }

    // Transports
    val bluetoothTransport = BluetoothTransport(context, viewModelScope)
    val wifiDirectTransport = WifiDirectTransport(context, viewModelScope)
    val lanUdpTransport = LanUdpTransport(viewModelScope, context)

    val currentTransportType = MutableStateFlow(TransportType.BLUETOOTH_RFCOMM)
    private var activeTransport: LinkTransport = bluetoothTransport

    val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val discoveredPeers = MutableStateFlow<List<PeerDevice>>(emptyList())

    // Reliability Engine
    var reliabilityEngine = ReliabilityEngine(activeTransport, viewModelScope, callsign.value)
        private set

    val reliabilityStats: StateFlow<ReliabilityStats> = reliabilityEngine.stats

    // Emergency & Location
    val gpsProvider = GpsLocationProvider(context)
    val gpsState: StateFlow<GpsState> = gpsProvider.gpsState
    val facilityDirectory = FacilityDirectory(context)
    val nearestFacilities = MutableStateFlow<List<Facility>>(emptyList())
    val sosAlertManager = SosAlertManager(context, ttsEngine, viewModelScope)

    // Metrics & Benchmark
    val metricsCollector = MetricsCollector(context)
    val liveMetrics: StateFlow<LiveDeviceMetrics> = metricsCollector.liveMetrics
    val benchmarkRunner = BenchmarkRunner(context, sttEngine, database)
    val benchmarkProgress: StateFlow<BenchmarkProgress> = benchmarkRunner.progress

    // Live Transcripts & Message History
    val liveTranscript = MutableStateFlow("")
    val readBackPendingText = MutableStateFlow<String?>(null)
    val isProcessingStt = MutableStateFlow(false)

    val messages = messageRepository.getAllMessages()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // SOS hold state
    val sosCountdownProgress = MutableStateFlow(0f)
    val isSosActive = MutableStateFlow(false)

    // Dashboard Telemetry & Emergency Service Features
    val isTrackMeActive = MutableStateFlow(false)
    val activePipelineStage = MutableStateFlow("IDLE") // MIC, VAD, STT, CLASSIFY, ENCODE, TX, RX, DECODE, TTS
    val lastPcmBytesCaptured = MutableStateFlow(0)
    val lastProtobufBytesSent = MutableStateFlow(0)
    val lastReductionPercent = MutableStateFlow(0f)
    private var trackMeJob: Job? = null

    private val _trackedPeers = MutableStateFlow<Map<String, TrackedPeer>>(emptyMap())
    val trackedPeers: StateFlow<Map<String, TrackedPeer>> = _trackedPeers.asStateFlow()

    // Step 13: Dial Hands-Free Speech-over-Packet Call States
    val isCallActive = MutableStateFlow(false)
    val activeCallPeerId = MutableStateFlow<String?>(null)
    val isCallMuted = MutableStateFlow(false)
    val callDurationSeconds = MutableStateFlow(0)
    val callStatusMessage = MutableStateFlow("Ready to call")
    val callTranscripts = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    private var callJob: Job? = null
    private var callTimerJob: Job? = null

    val sosHistory: StateFlow<List<org.coresense.itantra.storage.MessageEntity>> = messageRepository.getAllMessages()
        .map { list -> list.filter { it.priority == "SOS" || it.priority == "URGENT" } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var audioCaptureJob: Job? = null
    private var sosCountdownJob: Job? = null
    private val pttAudioFrames = java.util.concurrent.ConcurrentLinkedQueue<ShortArray>()

    init {
        // Auto-load Silero VAD ONNX model from assets
        vadDetector.loadFromAssets(context)

        // Initialize STT engine: extract bundled models and load active language
        viewModelScope.launch(Dispatchers.IO) {
            modelRegistry.refresh()
            liveTranscript.value = "${selectedLanguage.value.displayName} STT [Loading...]"
            modelRegistry.updateModelStatus(selectedLanguage.value, org.coresense.itantra.stt.ModelStatus.LOADING)
            val res = sttEngine.initialize(selectedLanguage.value)
            if (res.isSuccess) {
                try { android.util.Log.i("MainViewModel", "Initial STT model loaded: ${selectedLanguage.value.displayName}") } catch (_: Throwable) {}
                modelRegistry.updateModelStatus(selectedLanguage.value, org.coresense.itantra.stt.ModelStatus.READY)
                liveTranscript.value = "${selectedLanguage.value.displayName} STT [Ready]"
            } else {
                val err = res.exceptionOrNull()?.message ?: "Failed to load model"
                val isUnavailable = err.contains("STT_MODEL_UNAVAILABLE")
                val status = if (isUnavailable) org.coresense.itantra.stt.ModelStatus.MODEL_UNAVAILABLE else org.coresense.itantra.stt.ModelStatus.MODEL_LOAD_ERROR
                modelRegistry.updateModelStatus(selectedLanguage.value, status, err)
                liveTranscript.value = if (isUnavailable) {
                    "${selectedLanguage.value.displayName} STT [Model Unavailable]"
                } else {
                    "${selectedLanguage.value.displayName} STT [Load Error: ${err.take(30)}]"
                }
            }
        }

        // Initialize GPS location
        gpsProvider.requestLocation()

        // Sync transport flows
        observeActiveTransport()

        // Sync incoming packets and delivery updates from reliability engine
        observeReliabilityEngine()

        // Update facilities when GPS state changes
        viewModelScope.launch {
            gpsState.collect { state ->
                if (state is GpsState.Fix) {
                    nearestFacilities.value = facilityDirectory.getNearest(state.latitude, state.longitude, 5)
                }
            }
        }

        // Step 13: Automatically end call if transport link disconnects
        viewModelScope.launch {
            connectionState.collect { state ->
                if (state !is ConnectionState.Connected && isCallActive.value) {
                    callStatusMessage.value = "Call ended: transport disconnected"
                    endCall()
                }
            }
        }
    }

    private var enginePacketJob: Job? = null
    private var engineDeliveryJob: Job? = null

    private fun observeReliabilityEngine() {
        enginePacketJob?.cancel()
        enginePacketJob = viewModelScope.launch {
            reliabilityEngine.receivedPackets.collect { packet ->
                handleIncomingPacket(packet)
            }
        }

        engineDeliveryJob?.cancel()
        engineDeliveryJob = viewModelScope.launch {
            reliabilityEngine.messageDeliveryUpdates.collect { (msgId, state) ->
                val rtt = (state as? DeliveryState.Delivered)?.rttMs ?: 0L
                messageRepository.updateDeliveryState(msgId, state.name, rtt)
            }
        }
    }

    fun setTransport(type: TransportType) {
        if (currentTransportType.value == type) return
        activeTransport.disconnect()
        currentTransportType.value = type

        activeTransport = when (type) {
            TransportType.BLUETOOTH_RFCOMM -> bluetoothTransport
            TransportType.WIFI_DIRECT -> wifiDirectTransport
            TransportType.LAN_UDP -> lanUdpTransport
        }

        reliabilityEngine.close()
        reliabilityEngine = ReliabilityEngine(activeTransport, viewModelScope, callsign.value)
        observeActiveTransport()
        observeReliabilityEngine()
    }

    private var transportConnectionJob: Job? = null
    private var transportPeersJob: Job? = null

    private fun observeActiveTransport() {
        transportConnectionJob?.cancel()
        transportConnectionJob = viewModelScope.launch {
            activeTransport.connectionState.collect { connectionState.value = it }
        }
        transportPeersJob?.cancel()
        transportPeersJob = viewModelScope.launch {
            activeTransport.discoveredPeers.collect { peers ->
                android.util.Log.i("WifiDirectTransport", "MainViewModel: activeTransport (${activeTransport.transportType}) discoveredPeers updated with ${peers.size} peer(s)")
                peers.forEach { p ->
                    android.util.Log.d("WifiDirectTransport", "  -> MainViewModel peer: id=${p.id}, name=${p.name}, address=${p.address}")
                }
                discoveredPeers.value = peers
            }
        }
    }

    fun startDiscovery() {
        android.util.Log.i("WifiDirectTransport", "MainViewModel: startDiscovery() invoked on transport ${activeTransport.transportType}")
        val res = activeTransport.startDiscovery()
        if (res.isFailure) {
            val err = res.exceptionOrNull()?.message ?: "Unknown error"
            android.util.Log.e("WifiDirectTransport", "MainViewModel: startDiscovery() failed: $err")
            connectionState.value = ConnectionState.Failed(err)
        } else {
            android.util.Log.i("WifiDirectTransport", "MainViewModel: startDiscovery() returned success")
        }
    }

    fun stopDiscovery() {
        android.util.Log.i("WifiDirectTransport", "MainViewModel: stopDiscovery() invoked")
        activeTransport.stopDiscovery()
    }

    fun connectToPeer(peer: PeerDevice) {
        val res = activeTransport.connect(peer)
        if (res.isFailure) {
            connectionState.value = ConnectionState.Failed(res.exceptionOrNull()?.message ?: "Unknown error")
        }
    }

    fun disconnectPeer() {
        activeTransport.disconnect()
    }

    // --- PTT & Continuous Audio Engine ---

    private var continuousCallJob: Job? = null

    fun toggleCallingMode() {
        val nextMode = !isWalkieTalkieMode.value
        isWalkieTalkieMode.value = nextMode
        if (!nextMode) {
            startContinuousCalling()
        } else {
            stopContinuousCalling()
        }
    }

    fun startContinuousCalling() {
        stopContinuousCalling()
        liveTranscript.value = "2-Way Call: Listening for speech (VAD Active)..."
        TransmissionService.start(context, "Continuous 2-Way Calling")
        audioRecorder.start(viewModelScope)

        continuousCallJob = viewModelScope.launch(Dispatchers.Default) {
            audioRecorder.audioFrames.collect { frame ->
                when (val vadEvent = vadDetector.processFrame(frame)) {
                    is VadEvent.SpeechStarted -> {
                        liveTranscript.value = "Speech Detected..."
                    }
                    is VadEvent.SpeechEnded -> {
                        val pcm = vadEvent.completeUtterance
                        if (pcm.isNotEmpty()) {
                            isProcessingStt.value = true
                            liveTranscript.value = "VAD Pause: Transcribing..."
                            sttEngine.initialize(selectedLanguage.value)
                            val res = sttEngine.transcribe(pcm)
                            isProcessingStt.value = false
                            if (res.isSuccess) {
                                val recognized = res.getOrThrow().text.trim()
                                if (recognized.isNotBlank()) {
                                    val normalizedSentences = sentenceAssembler.finalizeUtterance(recognized)
                                    for (sentence in normalizedSentences) {
                                        liveTranscript.value = sentence
                                        sendTextMessage(sentence, PacketPriority.NORMAL)
                                    }
                                }
                            } else {
                                val err = res.exceptionOrNull()?.message ?: "STT Failed"
                                liveTranscript.value = err
                            }
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    fun stopContinuousCalling() {
        continuousCallJob?.cancel()
        continuousCallJob = null
        audioRecorder.stop()
        TransmissionService.stop(context)
        vadDetector.resetState()
        liveTranscript.value = ""
    }

    fun onPttPressed() {
        if (!isWalkieTalkieMode.value) return

        // Verify microphone recording permission
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            liveTranscript.value = "Microphone permission required"
            activePipelineStage.value = "IDLE"
            return
        }

        activePipelineStage.value = "MIC"
        liveTranscript.value = "Listening..."
        TransmissionService.start(context, "Walkie-Talkie Transmitting")

        pttAudioFrames.clear()
        audioRecorder.start(viewModelScope)

        audioCaptureJob?.cancel()
        audioCaptureJob = viewModelScope.launch(Dispatchers.IO) {
            audioRecorder.audioFrames.collect { frame ->
                pttAudioFrames.add(frame)
            }
        }
    }

    fun onPttReleased() {
        if (!isWalkieTalkieMode.value) return
        viewModelScope.launch(Dispatchers.IO) {
            delay(300) // Allow trailing audio frame to complete and avoid clipping word ending
            audioCaptureJob?.cancel()
            audioCaptureJob = null
            audioRecorder.stop()
            TransmissionService.stop(context)

            val totalSamples = pttAudioFrames.sumOf { it.size }
            val capturedSamples = ShortArray(totalSamples)
            var offset = 0
            while (pttAudioFrames.isNotEmpty()) {
                val frame = pttAudioFrames.poll() ?: break
                System.arraycopy(frame, 0, capturedSamples, offset, frame.size)
                offset += frame.size
            }
            pttAudioFrames.clear()

            if (capturedSamples.isEmpty()) {
                activePipelineStage.value = "IDLE"
                liveTranscript.value = "No audio captured"
                return@launch
            }

            activePipelineStage.value = "STT"
            isProcessingStt.value = true
            liveTranscript.value = "Transcribing..."

            if (!sttEngine.isLanguageLoaded(selectedLanguage.value)) {
                val initRes = sttEngine.initialize(selectedLanguage.value)
                if (initRes.isFailure) {
                    isProcessingStt.value = false
                    val errorMsg = initRes.exceptionOrNull()?.message ?: "STT Model Unavailable"
                    liveTranscript.value = errorMsg
                    activePipelineStage.value = "IDLE"
                    return@launch
                }
            }

            val sttRes = sttEngine.transcribe(capturedSamples)
            isProcessingStt.value = false

            if (sttRes.isSuccess) {
                val recognized = sttRes.getOrThrow().text.trim()
                if (recognized.isNotBlank()) {
                    liveTranscript.value = recognized
                    activePipelineStage.value = "CLASSIFY"
                    if (isReadBackEnabled.value) {
                        readBackPendingText.value = recognized
                        ttsEngine.speak(recognized, selectedLanguage.value)
                    } else {
                        sendTextMessage(recognized, PacketPriority.NORMAL)
                    }
                } else {
                    liveTranscript.value = "No clear speech recognized."
                    activePipelineStage.value = "IDLE"
                }
            } else {
                val errorMsg = sttRes.exceptionOrNull()?.message ?: "STT Error"
                liveTranscript.value = errorMsg
                activePipelineStage.value = "IDLE"
            }
        }
    }

    fun confirmReadBackSend(editedText: String? = null) {
        val textToSend = editedText ?: readBackPendingText.value ?: return
        readBackPendingText.value = null
        sendTextMessage(textToSend, PacketPriority.NORMAL)
    }

    // -------------------------------------------------------------------------
    // Step 11: Centralized Offline Text-To-Speech (TTS) Control State
    // -------------------------------------------------------------------------
    val isAutoTtsEnabled = MutableStateFlow(true)
    val isTtsSpeaking = MutableStateFlow(false)
    val currentlyPlayingMessageId = MutableStateFlow<Long?>(null)
    val ttsStatusMessage = MutableStateFlow<String?>("Offline TTS Ready")
    private var activePlaybackJob: Job? = null

    fun toggleAutoTts(enabled: Boolean? = null) {
        val next = enabled ?: !isAutoTtsEnabled.value
        isAutoTtsEnabled.value = next
        if (!next) {
            stopPlayback()
        }
    }

    fun togglePlayMessage(msgId: Long, text: String, lang: Language = selectedLanguage.value) {
        if (currentlyPlayingMessageId.value == msgId) {
            stopPlayback()
            return
        }

        stopPlayback()

        activePlaybackJob = viewModelScope.launch(Dispatchers.Main) {
            currentlyPlayingMessageId.value = msgId
            isTtsSpeaking.value = true
            activePipelineStage.value = "TTS"
            try {
                val status = ttsEngine.isLanguageAvailable(lang)
                if (status != TtsStatus.SYSTEM_TTS_OFFLINE && status != TtsStatus.OFFLINE_READY) {
                    ttsStatusMessage.value = "No offline voice for ${lang.displayName} ($status). Cloud fallback disabled."
                    return@launch
                }
                ttsStatusMessage.value = "Reading message aloud..."
                val res = ttsEngine.speak(text, lang, isEmergency = false)
                if (res.isFailure) {
                    ttsStatusMessage.value = res.exceptionOrNull()?.message ?: "TTS playback error"
                }
            } catch (e: Exception) {
                ttsStatusMessage.value = "TTS playback error: ${e.message}"
                try { android.util.Log.w("MainViewModel", "Speech playback error: ${e.message}") } catch (_: Throwable) {}
            } finally {
                if (currentlyPlayingMessageId.value == msgId) {
                    currentlyPlayingMessageId.value = null
                    isTtsSpeaking.value = false
                    activePipelineStage.value = "IDLE"
                }
            }
        }
    }

    fun stopPlayback() {
        activePlaybackJob?.cancel()
        activePlaybackJob = null
        ttsEngine.stop()
        currentlyPlayingMessageId.value = null
        isTtsSpeaking.value = false
        activePipelineStage.value = "IDLE"
    }

    fun testOfflineVoice(lang: Language = selectedLanguage.value) {
        stopPlayback()
        val testPhrase = when (lang) {
            Language.HINDI -> "नमस्ते, यह आई-तंत्रा का ऑफ़लाइन वाक् परीक्षण है।"
            Language.MARATHI -> "नमस्कार, ही आय-तंतराची ऑफलाइन चाचणी आहे."
            Language.BENGALI -> "নমস্কার, এটি আই-তন্ত্রের অফলাইন বক্তৃতা পরীক্ষা।"
            Language.TAMIL -> "வணக்கம், இது ஐ-தந்த்ராவின் ஆஃப்லைன் குரல் சோதனை."
            Language.TELUGU -> "నమస్కారం, ఇది ఐ-తంత్ర ఆఫ్ లైన్ వాయిస్ పరీక్ష."
            Language.GUJARATI -> "નમસ્તે, આ આઇ-તંત્રનું ઑફલાઇન વૉઇસ ટેસ્ટ છે."
            Language.KANNADA -> "ನಮಸ್ಕಾರ, ಇದು ಐ-ತಂತ್ರದ ಆಫ್‌ಲೈನ್ ಧ್ವನಿ ಪರೀಕ್ಷೆ."
            Language.MALAYALAM -> "നമസ്കാരം, ഇത് ഐ-തന്ത്ര ഓഫ്‌ലൈൻ ശബ്ദ പരിശോധനയാണ്."
            Language.PUNJABI -> "ਸਤਿ ਸ੍ਰੀ ਅਕਾਲ, ਇਹ ਆਈ-ਤੰਤਰਾ ਦਾ ਔਫਲਾਈਨ ਵੌਇਸ ਟੈਸਟ ਹੈ।"
            Language.ODIA -> "ନମସ୍କାର, ଏହା ଆଇ-ତନ୍ତ୍ରାର ଅଫଲାଇନ ଭଏସ ପରୀକ୍ଷଣ।"
            else -> "This is an offline text to speech test on iTantra."
        }
        activePlaybackJob = viewModelScope.launch(Dispatchers.Main) {
            isTtsSpeaking.value = true
            activePipelineStage.value = "TTS"
            ttsStatusMessage.value = "Testing offline voice for ${lang.displayName}..."
            val res = ttsEngine.speak(testPhrase, lang, isEmergency = false)
            if (res.isSuccess) {
                ttsStatusMessage.value = "Voice test completed successfully for ${lang.displayName}."
            } else {
                ttsStatusMessage.value = res.exceptionOrNull()?.message ?: "Voice test failed for ${lang.displayName}"
            }
            isTtsSpeaking.value = false
            activePipelineStage.value = "IDLE"
        }
    }

    fun cancelReadBack() {
        readBackPendingText.value = null
        stopPlayback()
    }

    // -------------------------------------------------------------------------
    // Step 13: Dial / Hands-Free Speech-over-Packet Call Session
    // -------------------------------------------------------------------------

    fun startCall(targetPeerId: String) {
        val trimmed = targetPeerId.trim()
        if (trimmed.isBlank() || trimmed.equals("GLOBAL", ignoreCase = true) || trimmed.equals("ALL", ignoreCase = true)) {
            callStatusMessage.value = "Please select a specific peer device to call"
            return
        }

        if (isCallActive.value) return

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            callStatusMessage.value = "Microphone permission required for call"
            return
        }

        val conn = connectionState.value
        if (conn !is ConnectionState.Connected) {
            callStatusMessage.value = "No active peer connection. Connect over Bluetooth or Wi-Fi first."
            return
        }

        // Pre-initialize STT model for current language if not loaded
        if (!sttEngine.isLanguageLoaded(selectedLanguage.value)) {
            viewModelScope.launch {
                val initRes = sttEngine.initialize(selectedLanguage.value)
                if (initRes.isFailure) {
                    callStatusMessage.value = "STT model unavailable: ${initRes.exceptionOrNull()?.message}"
                }
            }
        }

        isCallActive.value = true
        activeCallPeerId.value = trimmed
        isCallMuted.value = false
        callDurationSeconds.value = 0
        callStatusMessage.value = "Connected with $trimmed"
        callTranscripts.value = emptyList()

        TransmissionService.start(context)

        // 1. Duration timer
        callTimerJob?.cancel()
        callTimerJob = viewModelScope.launch {
            while (isActive && isCallActive.value) {
                delay(1000)
                if (isCallActive.value) {
                    callDurationSeconds.value += 1
                }
            }
        }

        // 2. Start AudioRecorder in continuous mode
        val startRes = audioRecorder.start(viewModelScope)
        if (startRes.isFailure) {
            callStatusMessage.value = "Mic start failed: ${startRes.exceptionOrNull()?.message}"
            endCall()
            return
        }

        // 3. Reset VAD
        vadDetector.resetState()

        // 4. Continuous VAD frame processing loop
        callJob?.cancel()
        callJob = viewModelScope.launch(Dispatchers.IO) {
            audioRecorder.audioFrames.collect { frame ->
                if (!isCallActive.value) return@collect
                if (isCallMuted.value) return@collect // Audio silenced while muted

                val vadEvent = vadDetector.processFrame(frame)
                when (vadEvent) {
                    is VadEvent.SpeechStarted -> {
                        if (isCallActive.value && !isCallMuted.value) {
                            callStatusMessage.value = "Speaking..."
                            activePipelineStage.value = "MIC"
                        }
                    }
                    is VadEvent.SpeechActive -> {
                        // speech in progress
                    }
                    is VadEvent.SpeechEnded -> {
                        val completeAudio = vadEvent.completeUtterance
                        if (completeAudio.isNotEmpty() && isCallActive.value && !isCallMuted.value) {
                            callStatusMessage.value = "Transcribing..."
                            activePipelineStage.value = "STT"
                            processAndSendCallSegment(completeAudio, trimmed)
                            if (isCallActive.value && !isCallMuted.value) {
                                callStatusMessage.value = "Connected with $trimmed"
                                activePipelineStage.value = "IDLE"
                            }
                        }
                    }
                    is VadEvent.Silence -> {
                        // quiet
                    }
                }
            }
        }
    }

    private suspend fun processAndSendCallSegment(audioSamples: ShortArray, targetPeerId: String) {
        if (!isCallActive.value || isCallMuted.value) return

        if (!sttEngine.isLanguageLoaded(selectedLanguage.value)) {
            val initRes = sttEngine.initialize(selectedLanguage.value)
            if (initRes.isFailure) return
        }

        val sttRes = sttEngine.transcribe(audioSamples)
        if (sttRes.isSuccess) {
            val raw = sttRes.getOrThrow().text.trim()
            if (raw.isNotBlank()) {
                val normalized = if (selectedLanguage.value == Language.ENGLISH) {
                    EnglishSpeechNormalizer.normalize(raw)
                } else {
                    raw
                }

                if (normalized.isNotBlank()) {
                    val currentList = callTranscripts.value.toMutableList()
                    currentList.add(Pair("Me", normalized))
                    callTranscripts.value = currentList

                    sendTextMessage(
                        text = normalized,
                        priority = PacketPriority.NORMAL,
                        receiverId = targetPeerId
                    )
                }
            }
        }
    }

    fun toggleCallMute() {
        val next = !isCallMuted.value
        isCallMuted.value = next
        callStatusMessage.value = if (next) "Microphone Muted" else "Connected with ${activeCallPeerId.value}"
    }

    fun endCall() {
        isCallActive.value = false
        activeCallPeerId.value = null
        isCallMuted.value = false
        callTimerJob?.cancel()
        callTimerJob = null
        callJob?.cancel()
        callJob = null
        audioRecorder.stop()
        vadDetector.resetState()
        TransmissionService.stop(context)
        callStatusMessage.value = "Call ended"
    }

    fun sendTextMessage(
        text: String,
        priority: PacketPriority = PacketPriority.NORMAL,
        receiverId: String? = null,
        conversationId: String? = null,
        departmentId: String? = null,
        packetType: PacketType? = null
    ) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return // STRICT RULE: Never send blank or placeholder text

        val myDeviceId = identityRepository.getDeviceId()
        val localIdentity = identityRepository.userIdentity.value

        val rawReceiver = if (receiverId.isNullOrBlank() || receiverId == "GLOBAL" || receiverId == "ALL") null else receiverId.trim()
        val resolvedDept = departmentId?.let { resolveDepartmentId(it) ?: it } ?: resolveDepartmentId(rawReceiver)
        val isDeptTarget = resolvedDept != null

        // When sending to a department, receiverId is null (broadcast to department per Step 8 & 9)
        val targetReceiver = if (isDeptTarget) null else rawReceiver
        
        // Department ID is either the target department or (if sent by a department device) the sender's department
        val targetDeptId = resolvedDept ?: (if (localIdentity?.role == Role.DEPARTMENT) localIdentity.departmentId else null)

        val convId = when {
            !conversationId.isNullOrBlank() -> conversationId
            resolvedDept != null -> getConversationId(myDeviceId, resolvedDept)
            targetReceiver != null -> getConversationId(myDeviceId, targetReceiver)
            else -> "conv_global"
        }

        val gps = gpsState.value
        val latMicro = (gps as? GpsState.Fix)?.let { (it.latitude * 1_000_000).toInt() }
        val lonMicro = (gps as? GpsState.Fix)?.let { (it.longitude * 1_000_000).toInt() }
        val accMeters = (gps as? GpsState.Fix)?.accuracyMeters

        val msgId = (System.currentTimeMillis() shl 16) or (UUID.randomUUID().hashCode().toLong() and 0xFFFFL)
        val seq = reliabilityEngine.nextSequenceNumber()

        val type = packetType ?: if (priority == PacketPriority.SOS) PacketType.SOS else PacketType.DATA

        val packet = Packet(
            msgId = msgId,
            senderId = myDeviceId,
            receiverId = targetReceiver,
            departmentId = targetDeptId,
            conversationId = convId,
            seq = seq,
            lang = selectedLanguage.value,
            priority = priority,
            text = trimmed,
            timestamp = System.currentTimeMillis(),
            latitudeMicrodegrees = latMicro,
            longitudeMicrodegrees = lonMicro,
            accuracyMeters = accMeters,
            type = type
        )

        activePipelineStage.value = "ENCODE"
        val encodedBytes = try { PacketCodec.encode(packet).size } catch (e: Exception) { packet.text.toByteArray().size + 24 }
        lastProtobufBytesSent.value = encodedBytes
        val pcm = lastPcmBytesCaptured.value
        if (pcm > 0) {
            lastReductionPercent.value = (100f * (1f - (encodedBytes.toFloat() / pcm.toFloat()))).coerceIn(0f, 99.9f)
        } else {
            lastReductionPercent.value = 97.4f
        }
        activePipelineStage.value = "TX"

        // Save locally to Room DB (skip pure telemetry LOCATION packets from polluting Chat DB)
        viewModelScope.launch(Dispatchers.IO) {
            if (packet.type != PacketType.LOCATION) {
                val conv = ConversationEntity(
                    conversationId = convId,
                    localDeviceId = myDeviceId,
                    participantId = targetReceiver ?: resolvedDept,
                    departmentId = targetDeptId,
                    createdAt = packet.timestamp,
                    updatedAt = packet.timestamp
                )
                messageRepository.insertConversation(conv)

                messageRepository.insertMessage(
                    MessageEntity(
                        msgId = packet.msgId,
                        senderId = packet.senderId,
                        seq = packet.seq,
                        lang = packet.lang.displayName,
                        priority = packet.priority.name,
                        text = packet.text,
                        receiverId = packet.receiverId,
                        departmentId = packet.departmentId,
                        conversationId = packet.conversationId,
                        timestamp = packet.timestamp,
                        latitudeMicrodegrees = packet.latitudeMicrodegrees,
                        longitudeMicrodegrees = packet.longitudeMicrodegrees,
                        deliveryState = "QUEUED",
                        isIncoming = false
                    )
                )
            }
            reliabilityEngine.enqueue(packet)
        }
    }

    val testConnectionResult = MutableStateFlow<String?>(null)

    fun testConnection(peerId: String) {
        testConnectionResult.value = "Testing..."
        val msgId = (System.currentTimeMillis() shl 16) or (UUID.randomUUID().hashCode().toLong() and 0xFFFFL)
        val packet = Packet(
            msgId = msgId,
            senderId = identityRepository.getDeviceId(),
            receiverId = peerId,
            seq = reliabilityEngine.nextSequenceNumber(),
            lang = selectedLanguage.value,
            priority = PacketPriority.NORMAL,
            text = "[TEST_CONNECTION]",
            timestamp = System.currentTimeMillis(),
            type = PacketType.DATA
        )
        viewModelScope.launch(Dispatchers.IO) {
            reliabilityEngine.enqueue(packet)
            reliabilityEngine.messageDeliveryUpdates.collect { (id, state) ->
                if (id == msgId) {
                    if (state is DeliveryState.Delivered) {
                        testConnectionResult.value = "PASS (RTT: ${state.rttMs} ms)"
                    } else if (state is DeliveryState.Failed) {
                        testConnectionResult.value = "FAIL: ${state.reason}"
                    }
                }
            }
        }
    }

    // --- SOS Emergency & Dashboard Features ---

    fun dispatchEmergencyService(serviceName: String, description: String) {
        isSosActive.value = true
        val gps = gpsState.value
        val locationStr = if (gps is GpsState.Fix) {
            "GPS: ${String.format("%.5f, %.5f", gps.latitude, gps.longitude)}"
        } else {
            "GPS: NO FIX"
        }
        val text = "[$serviceName EMERGENCY] $description. $locationStr"
        activePipelineStage.value = "CLASSIFY"
        val targetDept = resolveDepartmentId(serviceName) ?: "NDRF"
        sendTextMessage(
            text = text,
            priority = PacketPriority.SOS,
            departmentId = targetDept,
            packetType = PacketType.SOS
        )
    }

    fun startTrackMe(targetRecipient: String = "GLOBAL", intervalMs: Long = 15_000L) {
        if (isTrackMeActive.value) return
        isTrackMeActive.value = true
        gpsProvider.startContinuousUpdates(intervalMs = intervalMs)

        trackMeJob?.cancel()
        trackMeJob = viewModelScope.launch {
            // Immediate transmission of current fix if ready
            sendLocationBeacon(targetRecipient)

            while (isActive && isTrackMeActive.value) {
                delay(intervalMs)
                if (isActive && isTrackMeActive.value) {
                    sendLocationBeacon(targetRecipient)
                }
            }
        }
    }

    fun stopTrackMe() {
        isTrackMeActive.value = false
        trackMeJob?.cancel()
        trackMeJob = null
        gpsProvider.stopContinuousUpdates()
    }

    fun toggleTrackMe(targetRecipient: String = "GLOBAL") {
        if (isTrackMeActive.value) {
            stopTrackMe()
        } else {
            startTrackMe(targetRecipient)
        }
    }

    fun sendLocationBeacon(targetRecipient: String = "GLOBAL") {
        val gps = gpsState.value
        if (gps is GpsState.Fix) {
            val lat = gps.latitude
            val lon = gps.longitude
            val acc = gps.accuracyMeters
            val beaconText = "[TRACK-ME] Lat: ${String.format(java.util.Locale.US, "%.5f", lat)}, Lon: ${String.format(java.util.Locale.US, "%.5f", lon)} (±${acc.toInt()}m)"
            sendTextMessage(
                text = beaconText,
                priority = PacketPriority.URGENT,
                receiverId = if (targetRecipient == "GLOBAL" || targetRecipient == "ALL") null else targetRecipient,
                packetType = PacketType.LOCATION
            )
        }
    }

    fun startSosHold(emergencyType: String? = null) {
        sosCountdownJob?.cancel()
        sosCountdownJob = viewModelScope.launch {
            val totalSteps = 20
            for (step in 1..totalSteps) {
                delay(100)
                sosCountdownProgress.value = step.toFloat() / totalSteps.toFloat()
            }
            // 2s Hold confirmed!
            triggerSosEmergency(emergencyType)
        }
    }

    fun cancelSosHold() {
        sosCountdownJob?.cancel()
        sosCountdownJob = null
        sosCountdownProgress.value = 0f
    }

    fun triggerSosEmergency(emergencyType: String? = null) {
        sosCountdownProgress.value = 0f
        isSosActive.value = true

        val gps = gpsState.value
        val locationStr = if (gps is GpsState.Fix) {
            "GPS: ${String.format("%.5f, %.5f", gps.latitude, gps.longitude)}"
        } else {
            "GPS: NO FIX (Searching)"
        }

        val typeTag = emergencyType?.uppercase() ?: "GENERAL"
        val targetDept = resolveDepartmentId(emergencyType) ?: "NDRF"
        val sosText = "[$typeTag EMERGENCY SOS] Immediate evacuation / assistance needed! $locationStr"
        sendTextMessage(
            text = sosText,
            priority = PacketPriority.SOS,
            departmentId = targetDept,
            packetType = PacketType.SOS
        )
    }

    fun cancelActiveSos() {
        isSosActive.value = false
        sosAlertManager.stopAlert()
    }

    private fun handleIncomingPacket(packet: Packet) {
        activePipelineStage.value = "RX"

        val localIdentity = identityRepository.userIdentity.value
        val myDeviceId = identityRepository.getDeviceId()

        // 1. Ignore echo of our own outgoing packet
        if (packet.senderId.equals(myDeviceId, ignoreCase = true)) {
            return
        }

        // 2. Direct message check: specifically addressed to my deviceId or local IP
        val isDirectForMe = isDirectRecipient(packet.receiverId, myDeviceId)

        if (packet.text == "[TEST_CONNECTION]") {
            // Already ACKed by ReliabilityEngine, ignore for Chat/DB
            return
        }

        // 3. Department request check
        val targetDeptId = packet.departmentId ?: resolveDepartmentId(packet.receiverId)
        val isDepartmentMessage = targetDeptId != null

        // Department device routing rule (Step 8 Requirement 4 & Safety Rule):
        // A department device accepts a department-addressed packet ONLY when:
        // packet.departmentId == localDepartmentId AND local identity role == DEPARTMENT.
        // Persistent IdentityRepository identity is authoritative. UI selectors NEVER affect routing.
        val isForMyDepartment = if (isDepartmentMessage && targetDeptId != null) {
            val isDeptRole = localIdentity?.role == Role.DEPARTMENT
            val localDeptId = localIdentity?.departmentId
            isDeptRole && localDeptId != null && (
                localDeptId.equals(targetDeptId, ignoreCase = true) ||
                (targetDeptId == "FIRE" && localDeptId.equals("FIRE_DISASTER", ignoreCase = true)) ||
                (targetDeptId == "FIRE_DISASTER" && localDeptId.equals("FIRE", ignoreCase = true)) ||
                (targetDeptId == "CIVIL" && localDeptId.equals("CIVIL_DEFENCE", ignoreCase = true)) ||
                (targetDeptId == "CIVIL_DEFENCE" && localDeptId.equals("CIVIL", ignoreCase = true))
            )
        } else {
            false
        }

        // 4. Global broadcast check: only for general broadcast messages not addressed to a department
        val isGlobalBroadcast = (packet.receiverId == null || packet.receiverId.equals("GLOBAL", ignoreCase = true)) && !isDepartmentMessage

        val isAccepted = isDirectForMe || isForMyDepartment || isGlobalBroadcast

        if (!isAccepted) {
            // STRICT RULE: Do NOT store or display packets addressed to another device or department
            return
        }

        if (packet.hasValidCoordinates()) {
            val lat = packet.latitudeDegrees!!
            val lon = packet.longitudeDegrees!!
            val currentMap = _trackedPeers.value.toMutableMap()
            currentMap[packet.senderId] = TrackedPeer(
                deviceId = packet.senderId,
                latitude = lat,
                longitude = lon,
                accuracyMeters = packet.accuracyMeters,
                timestamp = packet.timestamp,
                lastReceivedTime = System.currentTimeMillis()
            )
            _trackedPeers.value = currentMap
        }

        val convId = when {
            !packet.conversationId.isNullOrBlank() -> packet.conversationId
            targetDeptId != null -> getConversationId(packet.senderId, targetDeptId)
            else -> getConversationId(myDeviceId, packet.senderId)
        }

        viewModelScope.launch(Dispatchers.IO) {
            activePipelineStage.value = "DECODE"

            // Save or update Conversation & Message in Room DB (skip pure telemetry LOCATION packets from polluting Chat DB)
            if (packet.type != PacketType.LOCATION) {
                val conv = ConversationEntity(
                    conversationId = convId,
                    localDeviceId = myDeviceId,
                    participantId = packet.senderId,
                    departmentId = targetDeptId,
                    createdAt = packet.timestamp,
                    updatedAt = packet.timestamp
                )
                messageRepository.insertConversation(conv)

                messageRepository.insertMessage(
                    MessageEntity(
                        msgId = packet.msgId,
                        senderId = packet.senderId,
                        seq = packet.seq,
                        lang = packet.lang.displayName,
                        priority = packet.priority.name,
                        text = packet.text,
                        receiverId = packet.receiverId,
                        departmentId = targetDeptId,
                        conversationId = convId,
                        timestamp = packet.timestamp,
                        latitudeMicrodegrees = packet.latitudeMicrodegrees,
                        longitudeMicrodegrees = packet.longitudeMicrodegrees,
                        deliveryState = "DELIVERED",
                        isIncoming = true
                    )
                )
            }

            // If currently in a call with this sender, append to live call transcript
            if (isCallActive.value && activeCallPeerId.value?.equals(packet.senderId, ignoreCase = true) == true && packet.type != PacketType.LOCATION) {
                val currentList = callTranscripts.value.toMutableList()
                currentList.add(Pair(packet.senderId, packet.text))
                callTranscripts.value = currentList
            }

            if (packet.type != PacketType.LOCATION) {
                showNotification(packet)
            }

            // Play voice or trigger SOS alert
            if (packet.type == PacketType.LOCATION) {
                // Location telemetry beacons are purely for tracking; do NOT interrupt via TTS
                activePipelineStage.value = "IDLE"
            } else if (packet.priority == PacketPriority.SOS || packet.type == PacketType.SOS) {
                activePipelineStage.value = "TTS"
                val gps = gpsState.value as? GpsState.Fix
                sosAlertManager.triggerIncomingSosAlert(packet, gps?.latitude, gps?.longitude)
            } else if (isAutoTtsEnabled.value) {
                // Auto-read incoming chat or dial speech: track via activePlaybackJob so it updates speaking state and can be stopped cleanly
                stopPlayback()
                activePlaybackJob = viewModelScope.launch(Dispatchers.Main) {
                    currentlyPlayingMessageId.value = packet.msgId
                    isTtsSpeaking.value = true
                    activePipelineStage.value = "TTS"
                    try {
                        ttsEngine.speak(packet.text, packet.lang)
                    } catch (e: Exception) {
                        try { android.util.Log.w("MainViewModel", "Auto-TTS error: ${e.message}") } catch (_: Throwable) {}
                    } finally {
                        if (currentlyPlayingMessageId.value == packet.msgId) {
                            currentlyPlayingMessageId.value = null
                            isTtsSpeaking.value = false
                            activePipelineStage.value = "IDLE"
                        }
                    }
                }
            } else {
                activePipelineStage.value = "IDLE"
            }
        }
    }

    fun getConversationId(target: String): String {
        return getConversationId(identityRepository.getDeviceId(), target)
    }

    fun resolveDepartmentId(nameOrId: String?): String? {
        if (nameOrId == null) return null
        val clean = nameOrId.trim()
        return when {
            clean.contains("NDRF", ignoreCase = true) -> "NDRF"
            clean.contains("Medic", ignoreCase = true) -> "MEDICAL"
            clean.contains("Police", ignoreCase = true) || clean.contains("Woman", ignoreCase = true) || clean.contains("Child", ignoreCase = true) -> "POLICE"
            clean.contains("Fire", ignoreCase = true) || clean.contains("Disaster", ignoreCase = true) -> "FIRE"
            clean.contains("Railway", ignoreCase = true) -> "RAILWAY"
            clean.contains("Civil", ignoreCase = true) -> "CIVIL"
            Department.entries.any { it.id.equals(clean, ignoreCase = true) || it.name.equals(clean, ignoreCase = true) } -> {
                Department.entries.first { it.id.equals(clean, ignoreCase = true) || it.name.equals(clean, ignoreCase = true) }.id
            }
            else -> null
        }
    }

    fun isDepartment(nameOrId: String?): Boolean = resolveDepartmentId(nameOrId) != null
    fun isDirectRecipient(receiverId: String?, myDeviceId: String = identityRepository.getDeviceId()): Boolean {
        if (receiverId.isNullOrBlank()) return false
        if (receiverId.equals(myDeviceId, ignoreCase = true)) return true
        if (receiverId.equals("GLOBAL", ignoreCase = true) || receiverId.equals("ALL", ignoreCase = true)) return false
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val netIf = interfaces.nextElement()
                val addrs = netIf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    val host = addr.hostAddress
                    if (host != null && host.equals(receiverId, ignoreCase = true)) {
                        return true
                    }
                }
            }
        } catch (_: Exception) {}
        return false
    }

    companion object {
        fun getConversationId(partyA: String, partyB: String): String {
            val a = partyA.trim()
            val b = partyB.trim()
            val sorted = listOf(a, b).sorted()
            return "conv_${sorted[0]}_${sorted[1]}"
        }
    }

    private fun showNotification(packet: Packet) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                "itantra_msgs",
                "Messages",
                android.app.NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }
        val builder = androidx.core.app.NotificationCompat.Builder(context, "itantra_msgs")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(if (packet.priority == PacketPriority.SOS) "SOS EMERGENCY!" else "Message from ${packet.senderId}")
            .setContentText(packet.text)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        notificationManager.notify(packet.msgId.toInt(), builder.build())
    }

    fun runBenchmark() {
        viewModelScope.launch {
            benchmarkRunner.runAllBenchmarks()
        }
    }

    override fun onCleared() {
        endCall()
        stopPlayback()
        audioRecorder.stop()
        gpsProvider.stopContinuousUpdates()
        activeTransport.close()
        sttEngine.close()
        ttsEngine.close()
        reliabilityEngine.close()
        super.onCleared()
    }
}

/**
 * Real-time tracked peer GPS location state.
 */
data class TrackedPeer(
    val deviceId: String,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float? = null,
    val timestamp: Long,
    val lastReceivedTime: Long = System.currentTimeMillis()
) {
    /**
     * Stale location detection: considered stale if older than 2 minutes (120,000 ms).
     */
    val isStale: Boolean
        get() = (System.currentTimeMillis() - lastReceivedTime) > 120_000L
}
