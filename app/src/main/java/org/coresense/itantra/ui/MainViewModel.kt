package org.coresense.itantra.ui

import android.app.Application
import android.content.Context
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
import org.coresense.itantra.stt.LanguageModelRegistry
import org.coresense.itantra.stt.ModelMetadata
import org.coresense.itantra.stt.OfflineSttEngine
import org.coresense.itantra.stt.SttEngine
import org.coresense.itantra.tts.OfflineAndroidTtsEngine
import org.coresense.itantra.tts.TtsEngine
import org.coresense.itantra.vad.SileroVadDetector
import org.coresense.itantra.vad.VadEvent
import java.util.UUID

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val context: Context = application.applicationContext
    private val database = ITantraApp.instance.database

    // Settings State
    val callsign = MutableStateFlow("CORE-ALPHA")
    val selectedLanguage = MutableStateFlow(Language.HINDI)
    val isWalkieTalkieMode = MutableStateFlow(true) // true = Walkie Talkie (PTT), false = Continuous Call (VAD)
    val isReadBackEnabled = MutableStateFlow(false)
    val vadSensitivity = MutableStateFlow(0.5f)

    fun setAppLanguage(lang: Language) {
        selectedLanguage.value = lang
        viewModelScope.launch(Dispatchers.IO) {
            sttEngine.initialize(lang)
        }
    }

    // Audio & VAD
    val audioRecorder = AudioRecorder()
    val vadDetector = SileroVadDetector()
    val audioLevel = audioRecorder.audioLevel
    val isRecording = audioRecorder.isCapturing

    // STT & TTS
    val modelRegistry = LanguageModelRegistry(context)
    val sttEngine: SttEngine = OfflineSttEngine(modelRegistry)
    val ttsEngine: TtsEngine = OfflineAndroidTtsEngine(context)
    val sentenceAssembler = SentenceAssembler()

    // Transports
    val bluetoothTransport = BluetoothTransport(context, viewModelScope)
    val wifiDirectTransport = WifiDirectTransport(context, viewModelScope)
    val lanUdpTransport = LanUdpTransport(viewModelScope)

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

    val messages = database.messageDao().getAllMessages()
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

    val sosHistory: StateFlow<List<org.coresense.itantra.storage.MessageEntity>> = database.messageDao().getAllMessages()
        .map { list -> list.filter { it.priority == "SOS" || it.priority == "URGENT" } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var audioCaptureJob: Job? = null
    private var sosCountdownJob: Job? = null
    private var pttAudioBuffer = mutableListOf<Short>()

    init {
        // Auto-load Silero VAD ONNX model from assets
        vadDetector.loadFromAssets(context)

        // Initialize GPS location
        gpsProvider.requestLocation()

        // Sync transport flows
        observeActiveTransport()

        // Sync incoming packets from reliability engine
        viewModelScope.launch {
            reliabilityEngine.receivedPackets.collect { packet ->
                handleIncomingPacket(packet)
            }
        }

        // Sync delivery state updates into Room
        viewModelScope.launch {
            reliabilityEngine.messageDeliveryUpdates.collect { (msgId, state) ->
                val rtt = (state as? DeliveryState.Delivered)?.rttMs ?: 0L
                database.messageDao().updateDeliveryState(msgId, state.name, rtt)
            }
        }

        // Update facilities when GPS state changes
        viewModelScope.launch {
            gpsState.collect { state ->
                if (state is GpsState.Fix) {
                    nearestFacilities.value = facilityDirectory.getNearest(state.latitude, state.longitude, 5)
                }
            }
        }

        // Periodic system metric measurement (RAM, CPU, Battery)
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                metricsCollector.measureSystemMetrics()
                delay(2000)
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
    }

    private fun observeActiveTransport() {
        viewModelScope.launch {
            activeTransport.connectionState.collect { connectionState.value = it }
        }
        viewModelScope.launch {
            activeTransport.discoveredPeers.collect { discoveredPeers.value = it }
        }
    }

    fun startDiscovery() {
        activeTransport.startDiscovery()
    }

    fun stopDiscovery() {
        activeTransport.stopDiscovery()
    }

    fun connectToPeer(peer: PeerDevice) {
        activeTransport.connect(peer)
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
        activePipelineStage.value = "MIC"
        pttAudioBuffer.clear()
        liveTranscript.value = "Listening..."
        TransmissionService.start(context, "Walkie-Talkie Transmitting")
        audioRecorder.start(viewModelScope)

        audioCaptureJob?.cancel()
        audioCaptureJob = viewModelScope.launch(Dispatchers.Default) {
            audioRecorder.audioFrames.collect { frame ->
                for (s in frame) pttAudioBuffer.add(s)
                if (vadDetector.processFrame(frame) is VadEvent.SpeechStarted) {
                    activePipelineStage.value = "VAD"
                }
            }
        }
    }

    fun onPttReleased() {
        if (!isWalkieTalkieMode.value) return
        audioCaptureJob?.cancel()
        audioCaptureJob = null
        audioRecorder.stop()
        TransmissionService.stop(context)

        val captured = ShortArray(pttAudioBuffer.size) { pttAudioBuffer[it] }
        pttAudioBuffer.clear()

        if (captured.isEmpty()) {
            liveTranscript.value = ""
            activePipelineStage.value = "IDLE"
            return
        }

        lastPcmBytesCaptured.value = captured.size * 2
        isProcessingStt.value = true
        activePipelineStage.value = "STT"
        liveTranscript.value = "Processing STT..."

        viewModelScope.launch(Dispatchers.IO) {
            sttEngine.initialize(selectedLanguage.value)
            val result = sttEngine.transcribe(captured)
            isProcessingStt.value = false

            if (result.isSuccess) {
                val sttRes = result.getOrThrow()
                val recognized = sttRes.text.trim()

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
                liveTranscript.value = "Error: ${result.exceptionOrNull()?.message}"
                activePipelineStage.value = "IDLE"
            }
        }
    }

    fun confirmReadBackSend(editedText: String? = null) {
        val textToSend = editedText ?: readBackPendingText.value ?: return
        readBackPendingText.value = null
        sendTextMessage(textToSend, PacketPriority.NORMAL)
    }

    fun cancelReadBack() {
        readBackPendingText.value = null
        ttsEngine.stop()
    }

    fun sendTextMessage(text: String, priority: PacketPriority = PacketPriority.NORMAL) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return // STRICT RULE: Never send blank or placeholder text

        val gps = gpsState.value
        val latMicro = (gps as? GpsState.Fix)?.let { (it.latitude * 1_000_000).toInt() }
        val lonMicro = (gps as? GpsState.Fix)?.let { (it.longitude * 1_000_000).toInt() }

        val msgId = (System.currentTimeMillis() shl 16) or (UUID.randomUUID().hashCode().toLong() and 0xFFFFL)
        val seq = reliabilityEngine.nextSequenceNumber()

        val packet = Packet(
            msgId = msgId,
            senderId = callsign.value,
            seq = seq,
            lang = selectedLanguage.value,
            priority = priority,
            text = trimmed,
            timestamp = System.currentTimeMillis(),
            latitudeMicrodegrees = latMicro,
            longitudeMicrodegrees = lonMicro,
            type = PacketType.DATA
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

        // Save locally to Room DB
        viewModelScope.launch(Dispatchers.IO) {
            database.messageDao().insertMessage(
                MessageEntity(
                    msgId = packet.msgId,
                    senderId = packet.senderId,
                    seq = packet.seq,
                    lang = packet.lang.displayName,
                    priority = packet.priority.name,
                    text = packet.text,
                    timestamp = packet.timestamp,
                    latitudeMicrodegrees = packet.latitudeMicrodegrees,
                    longitudeMicrodegrees = packet.longitudeMicrodegrees,
                    deliveryState = "QUEUED",
                    isIncoming = false
                )
            )
            reliabilityEngine.enqueue(packet)
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
        sendTextMessage(text, PacketPriority.SOS)
    }

    fun toggleTrackMe() {
        val next = !isTrackMeActive.value
        isTrackMeActive.value = next
        if (next) {
            trackMeJob = viewModelScope.launch {
                while (isActive && isTrackMeActive.value) {
                    val gps = gpsState.value
                    if (gps is GpsState.Fix) {
                        val beaconText = "[TRACK-ME] Position: ${String.format("%.5f, %.5f", gps.latitude, gps.longitude)} (±${gps.accuracyMeters.toInt()}m)"
                        sendTextMessage(beaconText, PacketPriority.URGENT)
                    }
                    delay(30_000)
                }
            }
        } else {
            trackMeJob?.cancel()
            trackMeJob = null
        }
    }

    fun startSosHold() {
        sosCountdownJob?.cancel()
        sosCountdownJob = viewModelScope.launch {
            val totalSteps = 20
            for (step in 1..totalSteps) {
                delay(100)
                sosCountdownProgress.value = step.toFloat() / totalSteps.toFloat()
            }
            // 2s Hold confirmed!
            triggerSosEmergency()
        }
    }

    fun cancelSosHold() {
        sosCountdownJob?.cancel()
        sosCountdownJob = null
        sosCountdownProgress.value = 0f
    }

    private fun triggerSosEmergency() {
        sosCountdownProgress.value = 0f
        isSosActive.value = true

        val gps = gpsState.value
        val locationStr = if (gps is GpsState.Fix) {
            "GPS: ${String.format("%.5f, %.5f", gps.latitude, gps.longitude)}"
        } else {
            "GPS: NO FIX (Searching)"
        }

        val sosText = "EMERGENCY SOS! Immediate evacuation / assistance needed! $locationStr"
        sendTextMessage(sosText, PacketPriority.SOS)
    }

    fun cancelActiveSos() {
        isSosActive.value = false
        sosAlertManager.stopAlert()
    }

    private fun handleIncomingPacket(packet: Packet) {
        activePipelineStage.value = "RX"
        viewModelScope.launch(Dispatchers.IO) {
            activePipelineStage.value = "DECODE"
            // Save to Room DB
            database.messageDao().insertMessage(
                MessageEntity(
                    msgId = packet.msgId,
                    senderId = packet.senderId,
                    seq = packet.seq,
                    lang = packet.lang.displayName,
                    priority = packet.priority.name,
                    text = packet.text,
                    timestamp = packet.timestamp,
                    latitudeMicrodegrees = packet.latitudeMicrodegrees,
                    longitudeMicrodegrees = packet.longitudeMicrodegrees,
                    deliveryState = "DELIVERED",
                    isIncoming = true
                )
            )

            // Play voice or trigger SOS alert
            activePipelineStage.value = "TTS"
            if (packet.priority == PacketPriority.SOS) {
                val gps = gpsState.value as? GpsState.Fix
                sosAlertManager.triggerIncomingSosAlert(packet, gps?.latitude, gps?.longitude)
            } else {
                ttsEngine.speak(packet.text, packet.lang)
            }
        }
    }

    fun runBenchmark() {
        viewModelScope.launch {
            benchmarkRunner.runAllBenchmarks()
        }
    }

    override fun onCleared() {
        audioRecorder.stop()
        activeTransport.close()
        sttEngine.close()
        ttsEngine.close()
        reliabilityEngine.close()
        super.onCleared()
    }
}
