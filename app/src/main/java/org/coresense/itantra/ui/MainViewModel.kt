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
import org.coresense.itantra.stt.LanguageModelRegistry
import org.coresense.itantra.stt.ModelMetadata
import org.coresense.itantra.stt.OfflineSttEngine
import org.coresense.itantra.stt.SttEngine
import org.coresense.itantra.tts.OfflineAndroidTtsEngine
import org.coresense.itantra.tts.TtsEngine
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

    fun setAppLanguage(lang: Language) {
        selectedLanguage.value = lang
        liveTranscript.value = "${lang.displayName} STT [Model Unavailable]"
        viewModelScope.launch(Dispatchers.IO) {
            val res = sttEngine.initialize(lang)
            if (res.isFailure) {
                val err = res.exceptionOrNull()?.message ?: "Failed to load model"
                android.util.Log.e("MainViewModel", "STT init failed for ${lang.displayName}: $err")
                liveTranscript.value = "${lang.displayName} STT [Model Unavailable]"
            } else {
                android.util.Log.i("MainViewModel", "STT initialized successfully for ${lang.displayName}")
                liveTranscript.value = "${lang.displayName} STT [Ready]"
            }
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

    val sosHistory: StateFlow<List<org.coresense.itantra.storage.MessageEntity>> = messageRepository.getAllMessages()
        .map { list -> list.filter { it.priority == "SOS" || it.priority == "URGENT" } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var audioCaptureJob: Job? = null
    private var sosCountdownJob: Job? = null
    private var pttAudioBuffer = mutableListOf<Short>()

    init {
        // Auto-load Silero VAD ONNX model from assets
        vadDetector.loadFromAssets(context)

        // Initialize STT engine: extract bundled models and load active language
        viewModelScope.launch(Dispatchers.IO) {
            modelRegistry.refresh()
            val res = sttEngine.initialize(selectedLanguage.value)
            if (res.isSuccess) {
                try { android.util.Log.i("MainViewModel", "Initial STT model loaded: ${selectedLanguage.value.displayName}") } catch (_: Throwable) {}
                liveTranscript.value = "${selectedLanguage.value.displayName} STT [Ready]"
            } else {
                liveTranscript.value = "${selectedLanguage.value.displayName} STT [Model Unavailable]"
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

        // Periodic system metric measurement (RAM, CPU, Battery)
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                metricsCollector.measureSystemMetrics()
                delay(2000)
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
            activeTransport.discoveredPeers.collect { discoveredPeers.value = it }
        }
    }

    fun startDiscovery() {
        val res = activeTransport.startDiscovery()
        if (res.isFailure) {
            connectionState.value = ConnectionState.Failed(res.exceptionOrNull()?.message ?: "Unknown error")
        }
    }

    fun stopDiscovery() {
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

        synchronized(pttAudioBuffer) {
            pttAudioBuffer.clear()
        }
        audioRecorder.start(viewModelScope)

        audioCaptureJob?.cancel()
        audioCaptureJob = viewModelScope.launch(Dispatchers.IO) {
            audioRecorder.audioFrames.collect { frame ->
                synchronized(pttAudioBuffer) {
                    for (sample in frame) {
                        pttAudioBuffer.add(sample)
                    }
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

        val capturedSamples: ShortArray
        synchronized(pttAudioBuffer) {
            capturedSamples = pttAudioBuffer.toShortArray()
            pttAudioBuffer.clear()
        }

        if (capturedSamples.isEmpty()) {
            activePipelineStage.value = "IDLE"
            liveTranscript.value = "No audio captured"
            return
        }

        activePipelineStage.value = "STT"
        isProcessingStt.value = true
        liveTranscript.value = "Transcribing..."

        viewModelScope.launch(Dispatchers.IO) {
            val initRes = sttEngine.initialize(selectedLanguage.value)
            if (initRes.isFailure) {
                isProcessingStt.value = false
                val errorMsg = initRes.exceptionOrNull()?.message ?: "STT Model Unavailable"
                liveTranscript.value = errorMsg
                activePipelineStage.value = "IDLE"
                return@launch
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

    fun cancelReadBack() {
        readBackPendingText.value = null
        ttsEngine.stop()
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

        // Save locally to Room DB
        viewModelScope.launch(Dispatchers.IO) {
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
        val targetDept = resolveDepartmentId(serviceName) ?: "NDRF"
        sendTextMessage(
            text = text,
            priority = PacketPriority.SOS,
            departmentId = targetDept,
            packetType = PacketType.SOS
        )
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

        // 2. Direct message check: specifically addressed to my deviceId
        val isDirectForMe = packet.receiverId != null && packet.receiverId.equals(myDeviceId, ignoreCase = true)

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

        val convId = when {
            !packet.conversationId.isNullOrBlank() -> packet.conversationId
            targetDeptId != null -> getConversationId(packet.senderId, targetDeptId)
            else -> getConversationId(myDeviceId, packet.senderId)
        }

        viewModelScope.launch(Dispatchers.IO) {
            activePipelineStage.value = "DECODE"

            // Save or update Conversation
            val conv = ConversationEntity(
                conversationId = convId,
                localDeviceId = myDeviceId,
                participantId = packet.senderId,
                departmentId = targetDeptId,
                createdAt = packet.timestamp,
                updatedAt = packet.timestamp
            )
            messageRepository.insertConversation(conv)

            // Save to Room DB
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

            showNotification(packet)

            // Play voice or trigger SOS alert
            activePipelineStage.value = "TTS"
            if (packet.priority == PacketPriority.SOS || packet.type == PacketType.SOS) {
                val gps = gpsState.value as? GpsState.Fix
                sosAlertManager.triggerIncomingSosAlert(packet, gps?.latitude, gps?.longitude)
            } else {
                ttsEngine.speak(packet.text, packet.lang)
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
        audioRecorder.stop()
        activeTransport.close()
        sttEngine.close()
        ttsEngine.close()
        reliabilityEngine.close()
        super.onCleared()
    }
}
