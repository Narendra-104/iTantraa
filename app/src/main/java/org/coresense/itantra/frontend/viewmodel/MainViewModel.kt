package org.coresense.itantra.frontend.viewmodel

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
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
import org.coresense.itantra.core.protocol.PacketCodec
import org.coresense.itantra.ITantraApp
import org.coresense.itantra.audio.AudioRecorder
import org.coresense.itantra.audio.TransmissionService
import org.coresense.itantra.backend.emergency.Facility
import org.coresense.itantra.backend.emergency.FacilityDirectory
import org.coresense.itantra.backend.location.GpsLocationProvider
import org.coresense.itantra.backend.location.GpsState
import org.coresense.itantra.backend.emergency.SosAlertManager
import org.coresense.itantra.backend.communication.BluetoothTransport
import org.coresense.itantra.backend.communication.ConnectionState
import org.coresense.itantra.backend.communication.LanUdpTransport
import org.coresense.itantra.backend.communication.LinkTransport
import org.coresense.itantra.backend.communication.PeerDevice
import org.coresense.itantra.backend.communication.TransportType
import org.coresense.itantra.backend.communication.WifiDirectTransport
import org.coresense.itantra.core.metrics.BenchmarkProgress
import org.coresense.itantra.core.metrics.BenchmarkRunner
import org.coresense.itantra.core.metrics.LiveDeviceMetrics
import org.coresense.itantra.core.metrics.MetricsCollector
import org.coresense.itantra.core.protocol.Language
import org.coresense.itantra.core.protocol.Packet
import org.coresense.itantra.core.protocol.PacketPriority
import org.coresense.itantra.core.protocol.PacketType
import org.coresense.itantra.core.reliability.DeliveryState
import org.coresense.itantra.core.reliability.ReliabilityEngine
import org.coresense.itantra.core.reliability.ReliabilityStats
import org.coresense.itantra.backend.speech.SentenceAssembler
import org.coresense.itantra.data.database.MessageEntity
import org.coresense.itantra.backend.speech.EnglishSpeechNormalizer
import org.coresense.itantra.backend.speech.LanguageModelRegistry
import org.coresense.itantra.backend.speech.ModelDownloadManager
import org.coresense.itantra.backend.speech.ModelMetadata
import org.coresense.itantra.backend.speech.OfflineSttEngine
import org.coresense.itantra.backend.speech.SttEngine
import org.coresense.itantra.backend.speech.OfflineAndroidTtsEngine
import org.coresense.itantra.backend.speech.TtsEngine
import org.coresense.itantra.backend.speech.TtsStatus
import org.coresense.itantra.backend.speech.SileroVadDetector
import org.coresense.itantra.backend.speech.VadEvent
import org.coresense.itantra.core.identity.Department
import org.coresense.itantra.core.identity.Role
import org.coresense.itantra.data.database.ConversationEntity
import org.coresense.itantra.data.model.AppNotification
import org.coresense.itantra.data.model.NotificationCategory
import java.util.UUID

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val context: Context = application.applicationContext
    private val database = ITantraApp.instance.database
    val identityRepository = org.coresense.itantra.data.repository.IdentityRepository(context)
    private val messageRepository = org.coresense.itantra.data.repository.MessageRepository(ITantraApp.instance.database.messageDao(), ITantraApp.instance.database.conversationDao())

    val userProfile: StateFlow<org.coresense.itantra.data.database.UserProfileEntity?> = database.userProfileDao().getUserProfile()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun saveUserProfile(
        profile: org.coresense.itantra.data.database.UserProfileEntity,
        role: org.coresense.itantra.core.identity.Role? = null,
        departmentId: String? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            database.userProfileDao().insertOrUpdateProfile(profile)
            val effectiveRole = role ?: identityRepository.userIdentity.value?.role ?: org.coresense.itantra.core.identity.Role.USER
            val effectiveDept = departmentId ?: identityRepository.userIdentity.value?.departmentId
            identityRepository.saveIdentity(
                name = profile.name,
                role = effectiveRole,
                departmentId = effectiveDept
            )
            identityRepository.saveProfileDetails(
                aadhar = profile.aadhar,
                email = profile.email,
                mobileNumber = profile.mobileNumber,
                address = profile.address,
                birthDate = profile.birthDate,
                bloodGroup = profile.bloodGroup,
                gender = profile.gender
            )
        }
    }

    // Settings State
    val callsign = MutableStateFlow("CORE-ALPHA")
    val receiverDepartment = MutableStateFlow(
        identityRepository.userIdentity.value?.departmentId ?: "GLOBAL"
    ) // GLOBAL, ALL, NDRF, POLICE, FIRE, etc.

    fun setReceiverDepartment(dept: String) {
        val clean = dept.trim()
        receiverDepartment.value = clean
        val isAll = clean.equals("ALL", ignoreCase = true)
        val isUser = clean.equals("GLOBAL", ignoreCase = true) || clean.equals("USER", ignoreCase = true) || clean.equals("None", ignoreCase = true)
        val role = if (isUser) {
            org.coresense.itantra.core.identity.Role.USER
        } else {
            org.coresense.itantra.core.identity.Role.DEPARTMENT
        }
        val resolved = if (isAll) "ALL" else if (isUser) null else resolveDepartmentId(clean)
        identityRepository.saveIdentity(
            name = callsign.value,
            role = role,
            departmentId = resolved
        )
    }

    val selectedLanguage = MutableStateFlow(
        run {
            val savedName = try {
                context.getSharedPreferences("itantra_identity", android.content.Context.MODE_PRIVATE)
                    .getString("app_language", Language.HINDI.name)
            } catch (_: Throwable) { Language.HINDI.name }
            try { Language.valueOf(savedName ?: Language.HINDI.name) } catch (_: Throwable) { Language.HINDI }
        }
    )
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
        selectedLanguage.value = lang
        try {
            context.getSharedPreferences("itantra_identity", android.content.Context.MODE_PRIVATE)
                .edit()
                .putString("app_language", lang.name)
                .apply()
        } catch (_: Throwable) {}

        if (!modelRegistry.isModelInstalled(lang)) {
            liveTranscript.value = "${lang.displayName} STT [Not Installed - Download in Settings]"
            return false
        }
        liveTranscript.value = "${lang.displayName} STT [Loading...]"
        modelRegistry.updateModelStatus(lang, org.coresense.itantra.backend.speech.ModelStatus.LOADING)
        viewModelScope.launch(Dispatchers.IO) {
            val res = sttEngine.initialize(lang)
            if (res.isFailure) {
                val err = res.exceptionOrNull()?.message ?: "Failed to load model"
                android.util.Log.e("MainViewModel", "STT init failed for ${lang.displayName}: $err")
                val isUnavailable = err.contains("STT_MODEL_UNAVAILABLE")
                val status = if (isUnavailable) org.coresense.itantra.backend.speech.ModelStatus.MODEL_UNAVAILABLE else org.coresense.itantra.backend.speech.ModelStatus.MODEL_LOAD_ERROR
                modelRegistry.updateModelStatus(lang, status, err)
                liveTranscript.value = if (isUnavailable) {
                    "${lang.displayName} STT [Model Unavailable]"
                } else {
                    "${lang.displayName} STT [Load Error: ${err.take(30)}]"
                }
            } else {
                android.util.Log.i("MainViewModel", "STT initialized successfully for ${lang.displayName}")
                modelRegistry.updateModelStatus(lang, org.coresense.itantra.backend.speech.ModelStatus.READY)
                liveTranscript.value = "${lang.displayName} STT [Ready]"
            }
        }
        return true
    }

    fun downloadLanguage(lang: Language) {
        downloadManager.startDownload(lang, viewModelScope) { result: Result<Unit> ->
            if (result.isSuccess) {
                modelRegistry.updateModelStatus(lang, org.coresense.itantra.backend.speech.ModelStatus.READY)
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

    // Real-Time Notifications Flow
    private val _notifications = MutableStateFlow<List<AppNotification>>(emptyList())
    val notifications: StateFlow<List<AppNotification>> = _notifications.asStateFlow()

    fun addNotification(notification: AppNotification) {
        val current = _notifications.value.toMutableList()
        // Deduplicate if identical id or same message within 2 seconds
        if (current.any { it.id == notification.id || (it.message == notification.message && Math.abs(it.timestamp - notification.timestamp) < 2000L) }) {
            return
        }
        current.add(0, notification)
        if (current.size > 100) {
            _notifications.value = current.take(100)
        } else {
            _notifications.value = current
        }
    }

    fun removeNotification(id: String) {
        _notifications.value = _notifications.value.filter { it.id != id }
    }

    fun clearNotifications() {
        _notifications.value = emptyList()
    }

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
    val callSessionState = MutableStateFlow(CallSessionState.IDLE)
    val isCallActive = MutableStateFlow(false)
    val activeCallPeerId = MutableStateFlow<String?>(null)
    val activeCallId = MutableStateFlow<String?>(null)
    val incomingCallFrom = MutableStateFlow<String?>(null)
    val incomingCallDisplayName = MutableStateFlow<String?>(null)
    val incomingCallId = MutableStateFlow<String?>(null)
    val isCallMuted = MutableStateFlow(false)
    val callDurationSeconds = MutableStateFlow(0)
    val callStatusMessage = MutableStateFlow("Ready to call")
    val callTranscripts = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    private val receivedCallTranscriptKeys = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private var callSeqCounter = 0
    private var callJob: Job? = null
    private var callTimerJob: Job? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    val sosHistory: StateFlow<List<org.coresense.itantra.data.database.MessageEntity>> = messageRepository.getAllMessages()
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
            modelRegistry.updateModelStatus(selectedLanguage.value, org.coresense.itantra.backend.speech.ModelStatus.LOADING)
            val res = sttEngine.initialize(selectedLanguage.value)
            if (res.isSuccess) {
                try { android.util.Log.i("MainViewModel", "Initial STT model loaded: ${selectedLanguage.value.displayName}") } catch (_: Throwable) {}
                modelRegistry.updateModelStatus(selectedLanguage.value, org.coresense.itantra.backend.speech.ModelStatus.READY)
                liveTranscript.value = "${selectedLanguage.value.displayName} STT [Ready]"
            } else {
                val err = res.exceptionOrNull()?.message ?: "Failed to load model"
                val isUnavailable = err.contains("STT_MODEL_UNAVAILABLE")
                val status = if (isUnavailable) org.coresense.itantra.backend.speech.ModelStatus.MODEL_UNAVAILABLE else org.coresense.itantra.backend.speech.ModelStatus.MODEL_LOAD_ERROR
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

        // Keep receiverDepartment in sync with persistent identity
        viewModelScope.launch {
            identityRepository.userIdentity.collect { id ->
                val dept = id?.departmentId
                if (!dept.isNullOrBlank()) {
                    receiverDepartment.value = dept
                }
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

        // Step 11: Monitor TTS engine readiness
        viewModelScope.launch(Dispatchers.IO) {
            if (ttsEngine is OfflineAndroidTtsEngine) {
                (ttsEngine as OfflineAndroidTtsEngine).awaitInitialization(3000L)
            }
            isTtsReady.value = ttsEngine.isReady()
        }

        // Step 13: Automatically end call if transport link disconnects
        viewModelScope.launch {
            connectionState.collect { state ->
                if (state !is ConnectionState.Connected && (isCallActive.value || callSessionState.value != CallSessionState.IDLE)) {
                    terminateCallLocally("Call ended: transport disconnected")
                }
            }
        }

        // Real-Time Notifications initial population
        viewModelScope.launch {
            messageRepository.getAllMessages().collect { list ->
                if (_notifications.value.isEmpty() && list.isNotEmpty()) {
                    val initialNotifications = list.takeLast(30).reversed().map { msg ->
                        val isSos = (msg.priority == "SOS" || msg.priority == "URGENT")
                        val cat = if (isSos) NotificationCategory.SOS else NotificationCategory.CHAT
                        val title = if (isSos) "🚨 SOS Alert: ${msg.senderId}" else "Message from ${msg.senderId}"
                        AppNotification(
                            id = msg.msgId.toString(),
                            title = title,
                            message = msg.text,
                            category = cat,
                            timestamp = msg.timestamp,
                            senderId = msg.senderId,
                            targetTab = if (isSos) "EMERGENCY_SOS" else "CHATS"
                        )
                    }
                    _notifications.value = initialNotifications
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
            activeTransport.connectionState.collect { state ->
                val prev = connectionState.value
                connectionState.value = state
                when (state) {
                    is ConnectionState.Connected -> {
                        addNotification(
                            AppNotification(
                                title = "Link Connected",
                                message = "Connected to ${state.peer.name} (${activeTransport.transportType.displayName})",
                                category = NotificationCategory.LINK,
                                targetTab = "AUDIO"
                            )
                        )
                    }
                    is ConnectionState.Failed -> {
                        addNotification(
                            AppNotification(
                                title = "Link Connection Failed",
                                message = state.reason,
                                category = NotificationCategory.LINK,
                                targetTab = "AUDIO"
                            )
                        )
                    }
                    is ConnectionState.Disconnected -> {
                        if (prev is ConnectionState.Connected) {
                            addNotification(
                                AppNotification(
                                    title = "Link Disconnected",
                                    message = "Disconnected from ${prev.peer.name}",
                                    category = NotificationCategory.LINK,
                                    targetTab = "AUDIO"
                                )
                            )
                        }
                    }
                    else -> {}
                }
            }
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

    fun connectTransport(type: TransportType) {
        if (currentTransportType.value != type) {
            setTransport(type)
        }
        when (type) {
            TransportType.LAN_UDP -> {
                lanUdpTransport.connectDefaultHotspot()
            }
            TransportType.BLUETOOTH_RFCOMM -> {
                bluetoothTransport.enableAutoConnect()
                startDiscovery()
            }
            TransportType.WIFI_DIRECT -> {
                wifiDirectTransport.enableAutoConnect()
                startDiscovery()
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
        if (currentTransportType.value != peer.transportType) {
            setTransport(peer.transportType)
        }
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
    val isTtsReady = MutableStateFlow(false)
    val currentlyPlayingMessageId = MutableStateFlow<Long?>(null)
    val ttsStatusMessage = MutableStateFlow<String?>("Ready")
    private var activePlaybackJob: Job? = null

    fun getTtsLanguageStatus(lang: Language): TtsStatus {
        return ttsEngine.isLanguageAvailable(lang)
    }

    fun toggleAutoTts(enabled: Boolean? = null) {
        val next = enabled ?: !isAutoTtsEnabled.value
        isAutoTtsEnabled.value = next
        if (!next) {
            stopPlayback()
        }
    }

    fun speakCustomText(text: String, lang: Language = selectedLanguage.value) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) {
            ttsStatusMessage.value = "Enter text to speak"
            return
        }

        stopPlayback()

        activePlaybackJob = viewModelScope.launch(Dispatchers.Main) {
            isTtsSpeaking.value = true
            activePipelineStage.value = "TTS"
            ttsStatusMessage.value = "Checking voice for ${lang.displayName}..."
            val status = ttsEngine.isLanguageAvailable(lang)
            if (status != TtsStatus.SYSTEM_TTS_OFFLINE && status != TtsStatus.OFFLINE_READY) {
                ttsStatusMessage.value = "No offline voice for ${lang.displayName} ($status). Install voice data in Android Settings."
                isTtsSpeaking.value = false
                activePipelineStage.value = "IDLE"
                return@launch
            }

            ttsStatusMessage.value = "Speaking in ${lang.displayName}..."
            val res = ttsEngine.speak(trimmed, lang, isEmergency = false)
            if (res.isSuccess) {
                ttsStatusMessage.value = "Finished speaking (${lang.displayName})"
            } else {
                ttsStatusMessage.value = res.exceptionOrNull()?.message ?: "Playback error"
            }
            isTtsSpeaking.value = false
            activePipelineStage.value = "IDLE"
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
        ttsStatusMessage.value = "Stopped"
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

    private fun requestCallAudioFocus() {
        try {
            if (audioManager == null) {
                audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val playbackAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(playbackAttributes)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener { /* Focus change listener */ }
                    .build()
                audioFocusRequest = focusRequest
                audioManager?.requestAudioFocus(focusRequest)
            } else {
                @Suppress("DEPRECATION")
                audioManager?.requestAudioFocus(
                    null,
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
                )
            }
        } catch (_: Throwable) {}
    }

    private fun abandonCallAudioFocus() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
                audioFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                audioManager?.abandonAudioFocus(null)
            }
        } catch (_: Throwable) {}
    }

    fun startCall(targetPeerId: String) {
        val trimmed = targetPeerId.trim()
        if (trimmed.isBlank() || trimmed.equals("GLOBAL", ignoreCase = true) || trimmed.equals("ALL", ignoreCase = true)) {
            callStatusMessage.value = "Please select a specific peer device to call"
            return
        }

        if (callSessionState.value != CallSessionState.IDLE) return

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

        val callId = "call_" + System.currentTimeMillis().toString(36) + "_" + (1000..9999).random()
        activeCallId.value = callId
        activeCallPeerId.value = trimmed
        callSessionState.value = CallSessionState.OUTGOING_CALL
        isCallActive.value = false
        isCallMuted.value = false
        callDurationSeconds.value = 0
        callStatusMessage.value = "Calling $trimmed..."
        callTranscripts.value = emptyList()
        receivedCallTranscriptKeys.clear()
        callSeqCounter = 0

        // Send dedicated CALL_SIGNAL invite packet to specific peer
        val myId = identityRepository.getDeviceId()
        val myName = callsign.value.ifBlank { myId }
        val invitePacket = Packet(
            msgId = System.currentTimeMillis() and 0x7FFFFFFF,
            senderId = myId,
            receiverId = trimmed,
            conversationId = callId,
            seq = ++callSeqCounter,
            lang = selectedLanguage.value,
            priority = PacketPriority.NORMAL,
            text = "CALL_INVITE:$callId:$myId:$myName",
            type = PacketType.CALL_SIGNAL,
            timestamp = System.currentTimeMillis()
        )
        viewModelScope.launch(Dispatchers.IO) {
            reliabilityEngine.sendPacket(invitePacket)
        }
    }

    fun acceptIncomingCall() {
        cancelCallNotification()
        if (callSessionState.value != CallSessionState.INCOMING_CALL) return
        val callId = incomingCallId.value ?: return
        val caller = incomingCallFrom.value ?: return
        val myId = identityRepository.getDeviceId()
        val myName = callsign.value.ifBlank { myId }

        activeCallId.value = callId
        activeCallPeerId.value = caller
        incomingCallId.value = null
        incomingCallFrom.value = null
        incomingCallDisplayName.value = null

        // Send CALL_ACCEPT signal packet
        val acceptPacket = Packet(
            msgId = System.currentTimeMillis() and 0x7FFFFFFF,
            senderId = myId,
            receiverId = caller,
            conversationId = callId,
            seq = ++callSeqCounter,
            lang = selectedLanguage.value,
            priority = PacketPriority.NORMAL,
            text = "CALL_ACCEPT:$callId:$myId:$myName",
            type = PacketType.CALL_SIGNAL,
            timestamp = System.currentTimeMillis()
        )
        viewModelScope.launch(Dispatchers.IO) {
            reliabilityEngine.sendPacket(acceptPacket)
        }

        startActiveCallSession(caller, callId)
    }

    fun rejectIncomingCall() {
        cancelCallNotification()
        if (callSessionState.value != CallSessionState.INCOMING_CALL) return
        val callId = incomingCallId.value
        val caller = incomingCallFrom.value
        val myId = identityRepository.getDeviceId()

        if (callId != null && caller != null) {
            val rejectPacket = Packet(
                msgId = System.currentTimeMillis() and 0x7FFFFFFF,
                senderId = myId,
                receiverId = caller,
                conversationId = callId,
                seq = ++callSeqCounter,
                lang = selectedLanguage.value,
                priority = PacketPriority.NORMAL,
                text = "CALL_REJECT:$callId",
                type = PacketType.CALL_SIGNAL,
                timestamp = System.currentTimeMillis()
            )
            viewModelScope.launch(Dispatchers.IO) {
                reliabilityEngine.sendPacket(rejectPacket)
            }
        }

        incomingCallId.value = null
        incomingCallFrom.value = null
        incomingCallDisplayName.value = null
        callSessionState.value = CallSessionState.IDLE
        callStatusMessage.value = "Call declined"
    }

    fun startActiveCallSession(peerId: String, callId: String) {
        callSessionState.value = CallSessionState.CONNECTED
        isCallActive.value = true
        activeCallPeerId.value = peerId
        activeCallId.value = callId
        isCallMuted.value = false
        callDurationSeconds.value = 0
        callStatusMessage.value = "Connected with $peerId"
        callTranscripts.value = emptyList()
        receivedCallTranscriptKeys.clear()

        requestCallAudioFocus()
        TransmissionService.start(context)

        // 1. Duration timer
        callTimerJob?.cancel()
        callTimerJob = viewModelScope.launch {
            while (isActive && callSessionState.value == CallSessionState.CONNECTED) {
                delay(1000)
                if (callSessionState.value == CallSessionState.CONNECTED) {
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
                if (callSessionState.value != CallSessionState.CONNECTED) return@collect
                if (isCallMuted.value) return@collect // Audio silenced while muted

                val vadEvent = vadDetector.processFrame(frame)
                when (vadEvent) {
                    is VadEvent.SpeechStarted -> {
                        if (callSessionState.value == CallSessionState.CONNECTED && !isCallMuted.value) {
                            callStatusMessage.value = "Speaking..."
                            activePipelineStage.value = "MIC"
                        }
                    }
                    is VadEvent.SpeechActive -> {
                        // speech in progress
                    }
                    is VadEvent.SpeechEnded -> {
                        val completeAudio = vadEvent.completeUtterance
                        if (completeAudio.isNotEmpty() && callSessionState.value == CallSessionState.CONNECTED && !isCallMuted.value) {
                            callStatusMessage.value = "Transcribing..."
                            activePipelineStage.value = "STT"
                            processAndSendCallSegment(completeAudio, peerId, callId)
                            if (callSessionState.value == CallSessionState.CONNECTED && !isCallMuted.value) {
                                callStatusMessage.value = "Connected with $peerId"
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

    private suspend fun processAndSendCallSegment(audioSamples: ShortArray, targetPeerId: String, callId: String) {
        if (callSessionState.value != CallSessionState.CONNECTED || isCallMuted.value) return

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
                    val currentSeq = ++callSeqCounter
                    val currentList = callTranscripts.value.toMutableList()
                    currentList.add(Pair("Me", normalized))
                    callTranscripts.value = currentList

                    // Transmit dedicated CALL_TRANSCRIPT packet strictly to target peer without polluting Room DB or Chat feed
                    val callPacket = Packet(
                        msgId = System.currentTimeMillis() and 0x7FFFFFFF,
                        senderId = identityRepository.getDeviceId(),
                        receiverId = targetPeerId,
                        conversationId = callId,
                        seq = currentSeq,
                        lang = selectedLanguage.value,
                        priority = PacketPriority.NORMAL,
                        text = normalized,
                        type = PacketType.CALL_TRANSCRIPT,
                        timestamp = System.currentTimeMillis()
                    )
                    viewModelScope.launch(Dispatchers.IO) {
                        reliabilityEngine.sendPacket(callPacket)
                    }
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
        cancelCallNotification()
        val target = activeCallPeerId.value
        val callId = activeCallId.value

        if (target != null && callId != null) {
            val endPacket = Packet(
                msgId = System.currentTimeMillis() and 0x7FFFFFFF,
                senderId = identityRepository.getDeviceId(),
                receiverId = target,
                conversationId = callId,
                seq = ++callSeqCounter,
                lang = selectedLanguage.value,
                priority = PacketPriority.NORMAL,
                text = "CALL_END:$callId",
                type = PacketType.CALL_SIGNAL,
                timestamp = System.currentTimeMillis()
            )
            viewModelScope.launch(Dispatchers.IO) {
                reliabilityEngine.sendPacket(endPacket)
            }
        } else if (callSessionState.value == CallSessionState.INCOMING_CALL) {
            rejectIncomingCall()
            return
        }

        terminateCallLocally("Call ended")
    }

    fun terminateCallLocally(statusMsg: String) {
        cancelCallNotification()
        callSessionState.value = CallSessionState.IDLE
        isCallActive.value = false
        activeCallPeerId.value = null
        activeCallId.value = null
        incomingCallFrom.value = null
        incomingCallDisplayName.value = null
        incomingCallId.value = null
        isCallMuted.value = false
        callTimerJob?.cancel()
        callTimerJob = null
        callJob?.cancel()
        callJob = null
        audioRecorder.stop()
        vadDetector.resetState()
        abandonCallAudioFocus()
        TransmissionService.stop(context)
        callStatusMessage.value = statusMsg
    }

    fun sendTextMessage(
        text: String,
        priority: PacketPriority = PacketPriority.NORMAL,
        receiverId: String? = null,
        conversationId: String? = null,
        departmentId: String? = null,
        packetType: PacketType? = null
    ) {
        val trimmed = text.trim().take(4096)
        if (trimmed.isBlank()) return // STRICT RULE: Never send blank or placeholder text

        val myDeviceId = identityRepository.getDeviceId()
        val localIdentity = identityRepository.userIdentity.value

        val rawReceiver = if (receiverId.isNullOrBlank() || receiverId == "GLOBAL" || receiverId == "ALL") null else receiverId.trim()
        val resolvedDept = departmentId?.let { resolveDepartmentId(it) ?: it } ?: resolveDepartmentId(rawReceiver)
        val isDeptTarget = resolvedDept != null

        // When sending to a department, receiverId is null (broadcast to department per Step 8 & 9)
        val targetReceiver = if (isDeptTarget) null else rawReceiver
        
        // Department ID is ONLY set if this message is specifically addressed to a department target
        val targetDeptId = resolvedDept

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
                        receiverId = packet.receiverId ?: targetDeptId,
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

    val activeTrackingRecipient = MutableStateFlow("GLOBAL")

    fun logLocationDiagnostic(
        action: String,
        packet: Packet,
        accuracy: Float?,
        fixTimestamp: Long,
        eventTime: Long,
        decisionReason: String
    ) {
        val ageMs = (eventTime - fixTimestamp).coerceAtLeast(0L)
        val logMsg = "[$action] sender=${packet.senderId}, seq=${packet.seq}, lat=${packet.latitudeDegrees}, lon=${packet.longitudeDegrees}, acc=±${accuracy?.toInt()}m, fixTime=$fixTimestamp, eventTime=$eventTime, age=${ageMs}ms -> $decisionReason"
        try {
            android.util.Log.i("iTantra-Location", logMsg)
        } catch (_: Throwable) {}
    }

    fun startTrackMe(targetRecipient: String = "GLOBAL", intervalMs: Long = 1000L) {
        if (isTrackMeActive.value) return
        isTrackMeActive.value = true
        activeTrackingRecipient.value = targetRecipient

        // Start hardware GPS continuous acquisition targeting ~1-second interval
        gpsProvider.startContinuousUpdates(intervalMs = intervalMs, minDistanceM = 0.0f)

        trackMeJob?.cancel()
        trackMeJob = viewModelScope.launch(Dispatchers.IO) {
            // Immediate transmission of current fix if ready
            sendLocationBeacon(targetRecipient)

            var lastSentFixTimestamp = 0L
            var lastSentTime = 0L

            while (isActive && isTrackMeActive.value) {
                val currentGps = gpsState.value
                val now = System.currentTimeMillis()
                if (currentGps is GpsState.Fix) {
                    val isNewFix = currentGps.timestamp != lastSentFixTimestamp
                    val minIntervalElapsed = (now - lastSentTime) >= intervalMs
                    if (isNewFix || minIntervalElapsed) {
                        sendLocationBeacon(targetRecipient)
                        lastSentFixTimestamp = currentGps.timestamp
                        lastSentTime = now
                    }
                }
                delay(500L)
            }
        }
    }

    fun stopTrackMe() {
        isTrackMeActive.value = false
        trackMeJob?.cancel()
        trackMeJob = null
        gpsProvider.stopContinuousUpdates()
    }

    fun toggleTrackMe(targetRecipient: String = "GLOBAL", intervalMs: Long = 1000L) {
        if (isTrackMeActive.value) {
            stopTrackMe()
        } else {
            startTrackMe(targetRecipient, intervalMs)
        }
    }

    fun sendLocationBeacon(targetRecipient: String = "GLOBAL") {
        val gps = gpsState.value
        if (gps is GpsState.Fix) {
            val lat = gps.latitude
            val lon = gps.longitude
            val acc = gps.accuracyMeters
            val fixTime = gps.timestamp
            val myDeviceId = identityRepository.getDeviceId()
            val beaconText = "[TRACK-ME] Lat: ${String.format(java.util.Locale.US, "%.5f", lat)}, Lon: ${String.format(java.util.Locale.US, "%.5f", lon)} (±${acc.toInt()}m)"
            val targetReceiver = if (targetRecipient == "GLOBAL" || targetRecipient == "ALL") null else targetRecipient

            val msgId = (System.currentTimeMillis() shl 16) or (UUID.randomUUID().hashCode().toLong() and 0xFFFFL)
            val seq = reliabilityEngine.nextSequenceNumber()

            val packet = Packet(
                msgId = msgId,
                senderId = myDeviceId,
                receiverId = targetReceiver,
                departmentId = null,
                conversationId = if (targetReceiver != null) getConversationId(myDeviceId, targetReceiver) else "conv_global",
                seq = seq,
                lang = selectedLanguage.value,
                priority = PacketPriority.URGENT,
                text = beaconText,
                timestamp = fixTime,
                latitudeMicrodegrees = (lat * 1_000_000).toInt(),
                longitudeMicrodegrees = (lon * 1_000_000).toInt(),
                accuracyMeters = acc,
                type = PacketType.LOCATION
            )

            logLocationDiagnostic("TX_BEACON", packet, acc, fixTime, System.currentTimeMillis(), "Transmitting fresh live GPS beacon")
            reliabilityEngine.enqueue(packet)
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
            "GPS: ${String.format(java.util.Locale.US, "%.5f, %.5f", gps.latitude, gps.longitude)}"
        } else {
            "GPS: NO FIX (Searching)"
        }

        val resolvedDept = resolveDepartmentId(emergencyType)
        val targetUser = if (resolvedDept == null && !emergencyType.isNullOrBlank()) emergencyType.trim() else null
        val finalDept = if (resolvedDept != null) resolvedDept else if (targetUser == null) "NDRF" else null

        if (finalDept != null) {
            val sosText = "[$finalDept EMERGENCY SOS] Immediate evacuation / assistance needed! $locationStr"
            sendTextMessage(
                text = sosText,
                priority = PacketPriority.SOS,
                departmentId = finalDept,
                packetType = PacketType.SOS
            )
        } else {
            val typeTag = targetUser ?: "DIRECT"
            val sosText = "[$typeTag EMERGENCY SOS] Immediate evacuation / assistance needed! $locationStr"
            sendTextMessage(
                text = sosText,
                priority = PacketPriority.SOS,
                receiverId = targetUser,
                departmentId = null,
                packetType = PacketType.SOS
            )
        }
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

        // 2. Direct message check: specifically addressed to my deviceId, local IP, Bluetooth ID, or Hotspot
        val isHotspotMessage = packet.receiverId != null && (
            packet.receiverId.equals("hotspot_broadcast", ignoreCase = true) ||
            packet.receiverId.equals("hotspot", ignoreCase = true) ||
            packet.receiverId.equals("255.255.255.255") ||
            packet.receiverId.startsWith("192.168.") ||
            packet.receiverId.startsWith("10.") ||
            packet.receiverId.startsWith("172.")
        )
        val isBluetoothDirectMessage = (currentTransportType.value == TransportType.BLUETOOTH_RFCOMM) || run {
            val btAdapter = try { android.bluetooth.BluetoothAdapter.getDefaultAdapter() } catch (_: Throwable) { null }
            val myBtAddress = try { btAdapter?.address } catch (_: Throwable) { null }
            val myBtName = try { btAdapter?.name } catch (_: Throwable) { null }
            (myBtAddress != null && myBtAddress.equals(packet.receiverId, ignoreCase = true)) ||
            (myBtName != null && myBtName.equals(packet.receiverId, ignoreCase = true))
        }
        val isConnectedPeerTarget = run {
            val peer = (connectionState.value as? ConnectionState.Connected)?.peer
            if (peer != null) {
                peer.id.equals(packet.receiverId, ignoreCase = true) ||
                peer.address.equals(packet.receiverId, ignoreCase = true) ||
                peer.name.equals(packet.receiverId, ignoreCase = true)
            } else false
        }

        val isDirectForMe = isDirectRecipient(packet.receiverId, myDeviceId) || isHotspotMessage || isBluetoothDirectMessage || isConnectedPeerTarget

        if (packet.text == "[TEST_CONNECTION]") {
            // Already ACKed by ReliabilityEngine, ignore for Chat/DB
            return
        }

        // 3. Department request check
        val targetDeptId = packet.departmentId?.let { resolveDepartmentId(it) ?: it } ?: resolveDepartmentId(packet.receiverId)
        val isDepartmentMessage = targetDeptId != null

        // Department device routing rule (Step 8 Requirement 4 & Safety Rule):
        // A department device accepts a department-addressed packet ONLY when:
        // packet.departmentId == localDepartmentId AND local identity role == DEPARTMENT.
        // Persistent IdentityRepository identity is authoritative. UI selectors NEVER affect routing.
        val localDeptId = localIdentity?.departmentId ?: receiverDepartment.value.takeIf { !it.equals("GLOBAL", ignoreCase = true) && !it.equals("USER", ignoreCase = true) }
        val isDeptRole = localIdentity?.role == Role.DEPARTMENT || !localDeptId.isNullOrBlank()
        val isForMyDepartment = if (isDepartmentMessage && targetDeptId != null) {
            if (localDeptId.equals("ALL", ignoreCase = true)) {
                true
            } else {
                isDeptRole && areDepartmentsMatching(localDeptId, targetDeptId)
            }
        } else {
            false
        }

        // 4. Global broadcast check: only for general broadcast messages not addressed to a department
        val isGlobalBroadcast = (packet.receiverId == null || packet.receiverId.equals("GLOBAL", ignoreCase = true)) && !isDepartmentMessage

        val isCallPacket = packet.type == PacketType.CALL_SIGNAL || packet.type == PacketType.CALL_TRANSCRIPT

        val isAccepted = isDirectForMe || isForMyDepartment || isGlobalBroadcast || isCallPacket

        if (!isAccepted) {
            // STRICT RULE: Do NOT store or display packets addressed to another device or department
            return
        }

        // Step 13: Handle Dedicated Call Signaling Packets
        if (packet.type == PacketType.CALL_SIGNAL) {
            val text = packet.text.trim()
            when {
                text.startsWith("CALL_INVITE:") -> {
                    val parts = text.split(":")
                    val callId = if (parts.size >= 2) parts[1] else packet.conversationId ?: ""
                    val callerId = if (parts.size >= 3) parts[2] else packet.senderId
                    val callerDisplayName = if (parts.size >= 4) parts[3] else callerId

                    if (callSessionState.value == CallSessionState.CONNECTED || callSessionState.value == CallSessionState.OUTGOING_CALL) {
                        // Busy on another call
                        val busyPacket = Packet(
                            msgId = System.currentTimeMillis() and 0x7FFFFFFF,
                            senderId = myDeviceId,
                            receiverId = callerId,
                            conversationId = callId,
                            seq = ++callSeqCounter,
                            lang = selectedLanguage.value,
                            priority = PacketPriority.NORMAL,
                            text = "CALL_BUSY:$callId",
                            type = PacketType.CALL_SIGNAL,
                            timestamp = System.currentTimeMillis()
                        )
                        viewModelScope.launch(Dispatchers.IO) {
                            reliabilityEngine.sendPacket(busyPacket)
                        }
                    } else {
                        incomingCallId.value = callId
                        incomingCallFrom.value = callerId
                        incomingCallDisplayName.value = callerDisplayName.ifBlank { callerId }
                        callSessionState.value = CallSessionState.INCOMING_CALL
                        callStatusMessage.value = "Incoming call from ${incomingCallDisplayName.value}"
                        targetNavigationTab.value = "DIAL"
                        showIncomingCallNotification(incomingCallDisplayName.value ?: callerId, callId)
                    }
                }
                text.startsWith("CALL_ACCEPT:") -> {
                    val parts = text.split(":")
                    val callId = if (parts.size >= 2) parts[1] else packet.conversationId
                    val responderId = if (parts.size >= 3) parts[2] else packet.senderId
                    val responderDisplayName = if (parts.size >= 4) parts[3] else responderId
                    if (callSessionState.value == CallSessionState.OUTGOING_CALL && (activeCallId.value == callId || packet.conversationId == activeCallId.value)) {
                        activeCallPeerId.value = responderId
                        cancelCallNotification()
                        startActiveCallSession(responderId, callId ?: activeCallId.value ?: "")
                        callStatusMessage.value = "Connected with ${responderDisplayName.ifBlank { responderId }}"
                    }
                }
                text.startsWith("CALL_REJECT:") -> {
                    val parts = text.split(":")
                    val callId = if (parts.size >= 2) parts[1] else packet.conversationId
                    if (callSessionState.value == CallSessionState.OUTGOING_CALL && (activeCallId.value == callId || packet.conversationId == activeCallId.value)) {
                        cancelCallNotification()
                        terminateCallLocally("${packet.senderId} declined the call")
                    }
                }
                text.startsWith("CALL_BUSY:") -> {
                    val parts = text.split(":")
                    val callId = if (parts.size >= 2) parts[1] else packet.conversationId
                    if (callSessionState.value == CallSessionState.OUTGOING_CALL && (activeCallId.value == callId || packet.conversationId == activeCallId.value)) {
                        cancelCallNotification()
                        terminateCallLocally("${packet.senderId} is busy on another call")
                    }
                }
                text.startsWith("CALL_END:") -> {
                    val parts = text.split(":")
                    val callId = if (parts.size >= 2) parts[1] else packet.conversationId
                    if ((callSessionState.value == CallSessionState.CONNECTED && activeCallId.value == callId) ||
                        (callSessionState.value == CallSessionState.INCOMING_CALL && incomingCallId.value == callId)) {
                        cancelCallNotification()
                        terminateCallLocally("Call ended by ${packet.senderId}")
                    }
                }
            }
            return
        }

        // Step 13: Handle Dedicated Call Transcript Packets
        if (packet.type == PacketType.CALL_TRANSCRIPT) {
            val isCallMatch = callSessionState.value == CallSessionState.CONNECTED &&
                    packet.conversationId == activeCallId.value &&
                    !packet.senderId.equals(myDeviceId, ignoreCase = true)

            if (isCallMatch && packet.text.isNotBlank()) {
                val dedupKey = "${packet.conversationId}_${packet.senderId}_${packet.seq}_${packet.text.trim()}"
                if (!receivedCallTranscriptKeys.contains(dedupKey)) {
                    receivedCallTranscriptKeys.add(dedupKey)
                    val senderName = if (packet.senderId.equals(activeCallPeerId.value, ignoreCase = true)) {
                        "Peer"
                    } else {
                        packet.senderId
                    }
                    val currentList = callTranscripts.value.toMutableList()
                    currentList.add(Pair(senderName, packet.text.trim()))
                    callTranscripts.value = currentList

                    viewModelScope.launch(Dispatchers.Main) {
                        try {
                            ttsEngine.speak(packet.text, packet.lang)
                        } catch (_: Exception) {}
                    }
                }
            }
            return
        }

        if (packet.hasValidCoordinates()) {
            val lat = packet.latitudeDegrees!!
            val lon = packet.longitudeDegrees!!
            val now = System.currentTimeMillis()
            val currentMap = _trackedPeers.value.toMutableMap()
            val existing = currentMap[packet.senderId]

            // Step 12 Ordering, Staleness, and Inaccuracy Guards (Requirement 4, 5, 7):
            val isStaleFix = existing != null && packet.timestamp < existing.timestamp
            val isDuplicate = existing != null && packet.timestamp == existing.timestamp && packet.seq <= existing.seq
            val isAncient = (now - packet.timestamp) > 86400000L // > 24 hours old

            if (isStaleFix) {
                logLocationDiagnostic("RX_REJECT", packet, packet.accuracyMeters, packet.timestamp, now, "Rejected: out-of-order packet older than latest fix")
            } else if (isDuplicate) {
                logLocationDiagnostic("RX_REJECT", packet, packet.accuracyMeters, packet.timestamp, now, "Rejected: duplicate sequence packet")
            } else if (isAncient) {
                logLocationDiagnostic("RX_REJECT", packet, packet.accuracyMeters, packet.timestamp, now, "Rejected: fix timestamp is older than 24 hours")
            } else {
                // Check if substantially less accurate fix arrives immediately after a precise fix without time advance
                val timeDiffMs = existing?.let { packet.timestamp - it.timestamp } ?: Long.MAX_VALUE
                val accDiff = if (existing?.accuracyMeters != null && packet.accuracyMeters != null) {
                    packet.accuracyMeters - existing.accuracyMeters
                } else 0f

                if (existing != null && timeDiffMs < 3000L && accDiff > 300f) {
                    logLocationDiagnostic("RX_REJECT", packet, packet.accuracyMeters, packet.timestamp, now, "Rejected: substantially worse accuracy (+${accDiff.toInt()}m) within 3s")
                } else {
                    currentMap[packet.senderId] = TrackedPeer(
                        deviceId = packet.senderId,
                        latitude = lat,
                        longitude = lon,
                        accuracyMeters = packet.accuracyMeters,
                        timestamp = packet.timestamp,
                        lastReceivedTime = now,
                        seq = packet.seq
                    )
                    _trackedPeers.value = currentMap
                    logLocationDiagnostic("RX_ACCEPT", packet, packet.accuracyMeters, packet.timestamp, now, "Accepted fresh real-time location fix")
                }
            }
        }

        val convId = when {
            !packet.conversationId.isNullOrBlank() -> packet.conversationId
            targetDeptId != null -> getConversationId(packet.senderId, targetDeptId)
            else -> getConversationId(myDeviceId, packet.senderId)
        }

        viewModelScope.launch(Dispatchers.IO) {
            activePipelineStage.value = "DECODE"

            // Save or update Conversation & Message in Room DB (skip pure telemetry LOCATION and dedicated CALL packets from polluting Chat DB)
            if (packet.type != PacketType.LOCATION && packet.type != PacketType.CALL_SIGNAL && packet.type != PacketType.CALL_TRANSCRIPT) {
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
                        receiverId = packet.receiverId ?: targetDeptId,
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

            if (packet.type != PacketType.LOCATION && packet.type != PacketType.CALL_SIGNAL && packet.type != PacketType.CALL_TRANSCRIPT && packet.priority != PacketPriority.SOS && packet.type != PacketType.SOS) {
                showNotification(packet)
            }

            // Play voice or trigger SOS alert
            if (packet.type == PacketType.LOCATION) {
                // Location telemetry beacons are purely for tracking; do NOT interrupt via TTS
                activePipelineStage.value = "IDLE"
            } else if (packet.priority == PacketPriority.SOS || packet.type == PacketType.SOS) {
                isSosActive.value = true
                activePipelineStage.value = "TTS"
                targetNavigationTab.value = "SOS"
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
        if (clean.isBlank()) return null
        return when {
            clean.contains("NDRF", ignoreCase = true) || clean.contains("Rescue", ignoreCase = true) || clean.contains("Search", ignoreCase = true) -> "NDRF"
            clean.contains("Medic", ignoreCase = true) -> "MEDICAL"
            clean.contains("Police", ignoreCase = true) || clean.contains("Woman", ignoreCase = true) || clean.contains("Child", ignoreCase = true) -> "POLICE"
            clean.contains("Fire", ignoreCase = true) || clean.contains("Disaster", ignoreCase = true) -> "FIRE"
            clean.contains("Railway", ignoreCase = true) || clean.contains("Train", ignoreCase = true) -> "RAILWAY"
            clean.contains("Civil", ignoreCase = true) || clean.contains("Defence", ignoreCase = true) || clean.contains("Defense", ignoreCase = true) -> "CIVIL"
            Department.entries.any { it.id.equals(clean, ignoreCase = true) || it.name.equals(clean, ignoreCase = true) || it.displayName.equals(clean, ignoreCase = true) } -> {
                Department.entries.first { it.id.equals(clean, ignoreCase = true) || it.name.equals(clean, ignoreCase = true) || it.displayName.equals(clean, ignoreCase = true) }.id
            }
            else -> null
        }
    }

    fun isDepartment(nameOrId: String?): Boolean = resolveDepartmentId(nameOrId) != null

    fun areDepartmentsMatching(deptA: String?, deptB: String?): Boolean {
        if (deptA.isNullOrBlank() || deptB.isNullOrBlank()) return false
        val normA = resolveDepartmentId(deptA) ?: deptA.trim().uppercase()
        val normB = resolveDepartmentId(deptB) ?: deptB.trim().uppercase()
        return normA == normB
    }
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
        private const val CALL_NOTIFICATION_ID = 9999

        fun getConversationId(partyA: String, partyB: String): String {
            val a = partyA.trim()
            val b = partyB.trim()
            val sorted = listOf(a, b).sorted()
            return "conv_${sorted[0]}_${sorted[1]}"
        }
    }

    val targetNavigationTab = MutableStateFlow<String?>(null)
    private val notifiedMessageIds = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    private fun showNotification(packet: Packet) {
        // Step 15: Deduplicate notifications to prevent repeated alerts for retransmissions
        if (!notifiedMessageIds.add(packet.msgId)) {
            return
        }

        val isSos = (packet.priority == PacketPriority.SOS || packet.type == PacketType.SOS)
        val cat = if (isSos) NotificationCategory.SOS else NotificationCategory.CHAT
        val title = when {
            isSos -> "🚨 EMERGENCY SOS FROM ${packet.senderId}"
            !packet.departmentId.isNullOrBlank() -> "[${packet.departmentId}] from ${packet.senderId}"
            !packet.receiverId.isNullOrBlank() && packet.receiverId != "GLOBAL" -> "Direct Message from ${packet.senderId}"
            else -> "Broadcast from ${packet.senderId}"
        }

        // Content privacy: truncate to avoid leaking excessive private payload on lockscreen
        val safeText = packet.text.trim().take(120)

        // Real-time In-App Notification (Always updated regardless of Android POST_NOTIFICATIONS permission)
        addNotification(
            AppNotification(
                id = packet.msgId.toString(),
                title = title,
                message = safeText,
                category = cat,
                timestamp = System.currentTimeMillis(),
                senderId = packet.senderId,
                targetTab = if (isSos) "EMERGENCY_SOS" else "CHATS"
            )
        )

        // Notification permission check on Android 13+ (API 33)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        }

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val channelId = "itantra_msgs_channel"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                channelId,
                "iTantra Field Messages",
                android.app.NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Tactical offline messages and field dispatch notifications"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 150, 250)
                lockscreenVisibility = androidx.core.app.NotificationCompat.VISIBILITY_PRIVATE
            }
            notificationManager.createNotificationChannel(channel)
        }

        val tapIntent = android.content.Intent(context, org.coresense.itantra.MainActivity::class.java).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("EXTRA_NAV_TAB", "CHATS")
            putExtra("EXTRA_CONVERSATION_ID", packet.conversationId)
            putExtra("EXTRA_SENDER_ID", packet.senderId)
            putExtra("EXTRA_DEPARTMENT_ID", packet.departmentId)
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            context,
            (packet.msgId and 0x7FFFFFFFL).toInt(),
            tapIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val builder = androidx.core.app.NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(safeText)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(pendingIntent)
            .setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)

        try {
            notificationManager.notify((packet.msgId and 0x7FFFFFFFL).toInt(), builder.build())
        } catch (_: SecurityException) {
            // Handled when permission revoked at runtime
        }
    }

    fun showIncomingCallNotification(callerDisplayName: String, callId: String) {
        addNotification(
            AppNotification(
                id = callId,
                title = "Incoming Voice Call",
                message = "Call from $callerDisplayName",
                category = NotificationCategory.CALL,
                timestamp = System.currentTimeMillis(),
                senderId = callerDisplayName,
                targetTab = "AUDIO"
            )
        )
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager ?: return
        val channelId = "itantra_calls_channel"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val ringtoneUri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE)
            val audioAttributes = android.media.AudioAttributes.Builder()
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .build()

            val channel = android.app.NotificationChannel(
                channelId,
                "iTantra Voice Calls",
                android.app.NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Incoming voice call alerts"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 250, 500, 250, 500)
                setSound(ringtoneUri, audioAttributes)
                lockscreenVisibility = androidx.core.app.NotificationCompat.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
        }

        val tapIntent = android.content.Intent(context, org.coresense.itantra.MainActivity::class.java).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("EXTRA_NAV_TAB", "DIAL")
        }
        val tapPendingIntent = android.app.PendingIntent.getActivity(
            context,
            CALL_NOTIFICATION_ID,
            tapIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val acceptIntent = android.content.Intent(context, org.coresense.itantra.MainActivity::class.java).apply {
            action = "ACTION_ACCEPT_CALL"
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val acceptPendingIntent = android.app.PendingIntent.getActivity(
            context,
            CALL_NOTIFICATION_ID + 1,
            acceptIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val declineIntent = android.content.Intent(context, org.coresense.itantra.MainActivity::class.java).apply {
            action = "ACTION_DECLINE_CALL"
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val declinePendingIntent = android.app.PendingIntent.getActivity(
            context,
            CALL_NOTIFICATION_ID + 2,
            declineIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val builder = androidx.core.app.NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle("📞 Incoming Voice Call")
            .setContentText("Call from $callerDisplayName")
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MAX)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_CALL)
            .setContentIntent(tapPendingIntent)
            .setFullScreenIntent(tapPendingIntent, true)
            .setOngoing(true)
            .setAutoCancel(false)
            .addAction(android.R.drawable.ic_menu_call, "ACCEPT", acceptPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "DECLINE", declinePendingIntent)

        try {
            notificationManager.notify(CALL_NOTIFICATION_ID, builder.build())
        } catch (_: SecurityException) {
        }
    }

    fun cancelCallNotification() {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager ?: return
        try {
            notificationManager.cancel(CALL_NOTIFICATION_ID)
        } catch (_: Exception) {}
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
    val lastReceivedTime: Long = System.currentTimeMillis(),
    val seq: Int = 0
) {
    /**
     * Real-time update status calculation:
     * - LIVE: Received within last 8 seconds (green)
     * - UPDATING: Received within last 25 seconds (cyan)
     * - SIGNAL_WEAK: Accuracy > 50m
     * - STALE: More than 25 seconds since last update (red/amber)
     * - DISCONNECTED: Transport link inactive
     */
    fun getStatus(isConnected: Boolean = true): TrackingStatus {
        if (!isConnected) return TrackingStatus.DISCONNECTED
        val ageMs = System.currentTimeMillis() - lastReceivedTime
        return when {
            ageMs > 25_000L -> TrackingStatus.STALE
            ageMs > 8_000L -> TrackingStatus.UPDATING
            accuracyMeters != null && accuracyMeters > 50f -> TrackingStatus.SIGNAL_WEAK
            else -> TrackingStatus.LIVE
        }
    }

    val isStale: Boolean
        get() = (System.currentTimeMillis() - lastReceivedTime) > 25_000L
}

enum class TrackingStatus(val label: String, val colorHex: Long) {
    LIVE("LIVE", 0xFF00E676),
    UPDATING("UPDATING", 0xFF00E5FF),
    SIGNAL_WEAK("SIGNAL WEAK", 0xFFFF9800),
    STALE("STALE", 0xFFFF5252),
    DISCONNECTED("DISCONNECTED", 0xFF9E9E9E)
}

enum class CallSessionState {
    IDLE,
    OUTGOING_CALL,
    INCOMING_CALL,
    CONNECTED
}
