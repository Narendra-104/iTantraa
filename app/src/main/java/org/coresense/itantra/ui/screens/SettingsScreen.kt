package org.coresense.itantra.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.coresense.itantra.link.PeerDevice
import org.coresense.itantra.link.TransportType
import org.coresense.itantra.protocol.Language
import org.coresense.itantra.stt.ModelMetadata
import org.coresense.itantra.stt.ModelStatus
import org.coresense.itantra.tts.TtsStatus
import org.coresense.itantra.ui.MainViewModel
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import org.coresense.itantra.ui.theme.DarkSurface
import org.coresense.itantra.ui.theme.DarkSurfaceVariant
import org.coresense.itantra.ui.theme.PrimaryNeonGreen

@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onNavigateToDemo: () -> Unit = {},
    onNavigateToManageLanguages: () -> Unit = {},
    onNavigateBack: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val callsign by viewModel.callsign.collectAsState()
    val activeTransport by viewModel.currentTransportType.collectAsState()
    val discoveredPeers by viewModel.discoveredPeers.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val modelsMap by viewModel.modelRegistry.models.collectAsState()
    val isReadBack by viewModel.isReadBackEnabled.collectAsState()
    val vadSensitivity by viewModel.vadSensitivity.collectAsState()
    val testResult by viewModel.testConnectionResult.collectAsState()

    var editingCallsign by remember { mutableStateOf(false) }
    var tempCallsign by remember { mutableStateOf(callsign) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(14.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onNavigateBack, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "RADIO & LINK SETTINGS",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = Color.White
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
        }

        // Language Setting
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(8.dp)
            ) {
                var langMenuExpanded by remember { mutableStateOf(false) }
                val currentLang by viewModel.selectedLanguage.collectAsState()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(text = "App & Voice Language", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(text = "${currentLang.nativeName} (${currentLang.displayName})", fontSize = 12.sp, color = PrimaryNeonGreen)
                    }
                    Box {
                        OutlinedButton(onClick = { langMenuExpanded = true }) {
                            Text("Switch")
                        }
                        DropdownMenu(
                            expanded = langMenuExpanded,
                            onDismissRequest = { langMenuExpanded = false }
                        ) {
                            Language.entries.forEach { lang ->
                                val meta = modelsMap[lang]
                                val isCurrent = currentLang == lang
                                val isReady = isCurrent && viewModel.sttEngine.isLanguageLoaded(lang)
                                val isInstalled = viewModel.modelRegistry.isModelInstalled(lang)
                                val statusSuffix = when {
                                    isReady -> " [Ready]"
                                    isInstalled -> " [Installed]"
                                    else -> " [Not Installed]"
                                }
                                DropdownMenuItem(
                                    text = { 
                                        Text(
                                            "${lang.nativeName} — ${lang.displayName}$statusSuffix",
                                            color = if (isInstalled) Color.White else Color.Gray
                                        ) 
                                    },
                                    enabled = isInstalled,
                                    onClick = {
                                        if (isInstalled) {
                                            viewModel.setAppLanguage(lang)
                                            langMenuExpanded = false
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
        }

        // Callsign Setting
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(text = "Node Callsign", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(text = callsign, fontSize = 12.sp, color = PrimaryNeonGreen)
                    }
                    Button(
                        onClick = {
                            tempCallsign = callsign
                            editingCallsign = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceVariant)
                    ) {
                        Text("Edit")
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
        }

        // Transport Selector (Wi-Fi Direct / Bluetooth SPP / LAN UDP)
        item {
            Text(text = "OFFLINE LINK TRANSPORT", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
            Spacer(modifier = Modifier.height(6.dp))

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TransportType.entries.forEach { type ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (activeTransport == type) PrimaryNeonGreen.copy(alpha = 0.15f) else DarkSurface
                        ),
                        shape = RoundedCornerShape(8.dp),
                        onClick = { viewModel.setTransport(type) }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(text = type.displayName, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                val desc = when (type) {
                                    TransportType.WIFI_DIRECT -> "High throughput, 100m range, automatic P2P group"
                                    TransportType.BLUETOOTH_RFCOMM -> "Low power SPP RFCOMM socket, reliable 10-30m link"
                                    TransportType.LAN_UDP -> "Ad-hoc Hotspot UDP multicast without internet"
                                }
                                Text(text = desc, fontSize = 10.sp, color = Color.LightGray)
                            }
                            RadioButton(
                                selected = (activeTransport == type),
                                onClick = { viewModel.setTransport(type) }
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
        }

        // Peer Discovery & Pairing
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "PEER DISCOVERY & PAIRING", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                Row {
                    TextButton(onClick = { 
                        android.util.Log.i("WifiDirectTransport", "UI: Scan button pressed by user")
                        viewModel.startDiscovery() 
                    }) {
                        Icon(imageVector = Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text(" Scan", fontSize = 12.sp)
                    }
                    TextButton(onClick = { 
                        android.util.Log.i("WifiDirectTransport", "UI: Stop discovery button pressed by user")
                        viewModel.stopDiscovery() 
                    }) {
                        Text("Stop", fontSize = 12.sp)
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }

        // Active Discovery or Error feedback banner (always visible, not hidden when peers empty)
        if (connectionState is org.coresense.itantra.link.ConnectionState.Discovering) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = PrimaryNeonGreen,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Scanning for nearby peers...",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = PrimaryNeonGreen
                            )
                            Text(
                                text = "Ensure the other phone has Wi-Fi ON and is also scanning.",
                                fontSize = 10.sp,
                                color = Color.LightGray
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
            }
        } else if (connectionState is org.coresense.itantra.link.ConnectionState.Failed) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF330000)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Error",
                            tint = Color.Red,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = (connectionState as org.coresense.itantra.link.ConnectionState.Failed).reason,
                            color = Color(0xFFFF6666),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
            }
        }

        if (discoveredPeers.isEmpty() && connectionState !is org.coresense.itantra.link.ConnectionState.Discovering) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface)
                ) {
                    Text(
                        text = "No peers discovered. Press 'Scan' to discover nearby offline devices.",
                        fontSize = 11.sp,
                        color = Color.Gray,
                        modifier = Modifier.padding(12.dp)
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
            }
        } else if (discoveredPeers.isNotEmpty()) {
            items(discoveredPeers) { peer ->
                val isThisConnected = (connectionState as? org.coresense.itantra.link.ConnectionState.Connected)?.peer?.id == peer.id
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = peer.name, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(text = "${peer.address} • ${peer.transportType.displayName}", fontSize = 10.sp, color = Color.LightGray)
                        }
                        Button(
                            onClick = {
                                if (isThisConnected) {
                                    viewModel.disconnectPeer()
                                } else {
                                    viewModel.connectToPeer(peer)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isThisConnected) Color(0xFF008800) else PrimaryNeonGreen
                            ),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = if (isThisConnected) "Connected" else "Connect",
                                color = if (isThisConnected) Color.White else DarkSurface,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
            }
            if (connectionState is org.coresense.itantra.link.ConnectionState.Connected) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = DarkSurface),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            val connectedPeer = (connectionState as org.coresense.itantra.link.ConnectionState.Connected).peer
                            Button(
                                onClick = { viewModel.testConnection(connectedPeer.id) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Test Connection to ${connectedPeer.name}")
                            }
                            if (testResult != null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = testResult!!,
                                    fontSize = 12.sp,
                                    color = if (testResult!!.startsWith("PASS")) Color(0xFF00FF00) else if (testResult!!.startsWith("FAIL")) Color.Red else Color.Yellow,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }
            }
            item { Spacer(modifier = Modifier.height(10.dp)) }
        }

        if (activeTransport == TransportType.LAN_UDP) {
            item {
                var manualIp by remember { mutableStateOf("") }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Add Manual IP (Fallback if Broadcast blocked)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = manualIp,
                                onValueChange = { manualIp = it },
                                label = { Text("IP Address") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(onClick = {
                                if (manualIp.isNotBlank()) {
                                    viewModel.lanUdpTransport.addManualPeer(manualIp.trim())
                                    manualIp = ""
                                }
                            }) {
                                Text("Add")
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
            }
        }

        // VAD Sensitivity Slider
        item {
            Text(text = "SILERO VAD SPEECH SENSITIVITY", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Speech Threshold", fontSize = 12.sp)
                        Text(String.format("%.2f", vadSensitivity), fontSize = 12.sp, color = PrimaryNeonGreen)
                    }
                    Slider(
                        value = vadSensitivity,
                        onValueChange = { viewModel.vadSensitivity.value = it },
                        valueRange = 0.2f..0.8f
                    )
                    Text("Higher threshold requires louder/clearer speech to trigger transmission.", fontSize = 10.sp, color = Color.Gray)
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
        }

        // Read-Back Toggle
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Read-Back Before Sending", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(text = "Speaks recognized text back to sender before packet transmission", fontSize = 11.sp, color = Color.Gray)
                    }
                    Switch(
                        checked = isReadBack,
                        onCheckedChange = { viewModel.isReadBackEnabled.value = it }
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
        }

        // OFFLINE TEXT-TO-SPEECH (TTS) Card
        item {
            Text(text = "OFFLINE TEXT-TO-SPEECH (TTS)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
            Spacer(modifier = Modifier.height(6.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    val isAutoTts by viewModel.isAutoTtsEnabled.collectAsState()
                    val isTtsSpeaking by viewModel.isTtsSpeaking.collectAsState()
                    val currentLang by viewModel.selectedLanguage.collectAsState()
                    val ttsStatusMsg by viewModel.ttsStatusMessage.collectAsState()
                    val voiceStatus = remember(currentLang) { viewModel.ttsEngine.isLanguageAvailable(currentLang) }
                    val isOfflineVoiceAvailable = voiceStatus == TtsStatus.SYSTEM_TTS_OFFLINE || voiceStatus == TtsStatus.OFFLINE_READY

                    // 1. Switch: Auto-Read Incoming Messages
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Auto-Read Incoming Messages",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                                color = Color.White
                            )
                            Text(
                                text = "Automatically synthesizes voice for ordinary incoming chat messages",
                                fontSize = 11.sp,
                                color = Color.Gray
                            )
                        }
                        Switch(
                            checked = isAutoTts,
                            onCheckedChange = { viewModel.toggleAutoTts(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = DarkSurface,
                                checkedTrackColor = PrimaryNeonGreen
                            )
                        )
                    }

                    HorizontalDivider(color = Color.DarkGray, modifier = Modifier.padding(vertical = 10.dp))

                    // 2. Offline Voice Status Line
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Selected Voice Language:",
                                fontSize = 11.sp,
                                color = Color.Gray
                            )
                            Text(
                                text = "${currentLang.displayName} (${currentLang.nativeName})",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }

                        Surface(
                            color = if (isOfflineVoiceAvailable) PrimaryNeonGreen.copy(alpha = 0.15f) else Color.Red.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(6.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isOfflineVoiceAvailable) PrimaryNeonGreen else Color.Red
                            )
                        ) {
                            Text(
                                text = if (isOfflineVoiceAvailable) "OFFLINE READY" else "UNAVAILABLE",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isOfflineVoiceAvailable) PrimaryNeonGreen else Color(0xFFFF5252)
                            )
                        }
                    }

                    if (!isOfflineVoiceAvailable) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "⚠ No verified offline TTS voice found for ${currentLang.displayName}. Cloud fallback is permanently disabled.",
                            fontSize = 11.sp,
                            color = Color(0xFFFF9800)
                        )
                    }

                    if (ttsStatusMsg != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = ttsStatusMsg ?: "",
                            fontSize = 11.sp,
                            color = if (isTtsSpeaking) PrimaryNeonGreen else Color.LightGray
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 3. Test Offline Voice & Stop Button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { viewModel.testOfflineVoice() },
                            modifier = Modifier.weight(1f).height(42.dp),
                            enabled = !isTtsSpeaking,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PrimaryNeonGreen,
                                contentColor = DarkSurface
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.VolumeUp, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Test Offline Voice", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }

                        if (isTtsSpeaking) {
                            Button(
                                onClick = { viewModel.stopPlayback() },
                                modifier = Modifier.height(42.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = "Stop", modifier = Modifier.size(16.dp), tint = Color.White)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("STOP", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
        }

        // Language Model Manager (11 Languages)
        item {
            Text(text = "LANGUAGE MODEL PACKS (11 LANGUAGES)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
            Text(
                text = "Models storage: ${viewModel.modelRegistry.getModelsStorageDirectory().absolutePath}",
                fontSize = 10.sp,
                color = Color.LightGray
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onNavigateToManageLanguages,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryNeonGreen)
            ) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp), tint = DarkSurface)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Manage Offline Language Packs", color = DarkSurface, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(10.dp))
        }

        items(Language.entries) { lang ->
            val meta = modelsMap[lang]
            val ttsStatus = viewModel.ttsEngine.isLanguageAvailable(lang)
            LanguageModelCard(lang, meta, ttsStatus)
            Spacer(modifier = Modifier.height(6.dp))
        }

        // Isolated DEMO Screen Navigation
        item {
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedButton(
                onClick = onNavigateToDemo,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFFB300))
            ) {
                Icon(imageVector = Icons.Default.Science, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Open Isolated DEMO Screen (Testing Only)", fontSize = 12.sp)
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (editingCallsign) {
        AlertDialog(
            onDismissRequest = { editingCallsign = false },
            title = { Text("Change Node Callsign") },
            text = {
                OutlinedTextField(
                    value = tempCallsign,
                    onValueChange = { tempCallsign = it.uppercase() },
                    label = { Text("Callsign (e.g. ALPHA-1)") }
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (tempCallsign.isNotBlank()) {
                        viewModel.callsign.value = tempCallsign.trim()
                    }
                    editingCallsign = false
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingCallsign = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun LanguageModelCard(lang: Language, meta: ModelMetadata?, ttsStatus: TtsStatus) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${lang.displayName} (${lang.nativeName})",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = Color.White
                )
                Text(
                    text = "STT: ${meta?.engineName ?: "ONNX Conformer"} • TTS: ${ttsStatus.description}",
                    fontSize = 10.sp,
                    color = Color.LightGray
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                val isInstalled = (meta?.actualSizeMb ?: 0f) > 0f
                val isBundled = (lang == Language.ENGLISH || lang == Language.HINDI)
                val statusText = when {
                    isBundled && isInstalled -> "BUNDLED"
                    isBundled && !isInstalled -> "MISSING"
                    meta?.status == ModelStatus.READY -> "READY"
                    meta?.status == ModelStatus.LOADING -> "LOADING"
                    meta?.status == ModelStatus.DOWNLOADING -> "DOWNLOADING"
                    meta?.status == ModelStatus.VERIFYING -> "VERIFYING"
                    meta?.status == ModelStatus.EXTRACTING -> "EXTRACTING"
                    meta?.status == ModelStatus.MODEL_LOAD_ERROR -> "LOAD ERROR"
                    meta?.status == ModelStatus.INFERENCE_ERROR -> "INFER ERROR"
                    isInstalled -> "INSTALLED"
                    meta?.status == ModelStatus.COMING_SOON -> "COMING SOON"
                    else -> "NOT INSTALLED"
                }
                val statusColor = when {
                    isBundled && isInstalled -> PrimaryNeonGreen
                    meta?.status == ModelStatus.READY || isInstalled -> PrimaryNeonGreen
                    meta?.status == ModelStatus.LOADING || meta?.status == ModelStatus.DOWNLOADING || meta?.status == ModelStatus.EXTRACTING -> Color(0xFFFFD700)
                    meta?.status == ModelStatus.VERIFYING -> Color(0xFF00E5FF)
                    meta?.status == ModelStatus.MODEL_LOAD_ERROR || meta?.status == ModelStatus.INFERENCE_ERROR || (isBundled && !isInstalled) -> Color(0xFFFF5252)
                    meta?.status == ModelStatus.COMING_SOON -> Color(0xFFFFB300)
                    else -> Color.Gray
                }
                Text(
                    text = statusText,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = statusColor
                )
                Text(
                    text = if (isInstalled) String.format("%.1f MB", meta?.actualSizeMb ?: 0f) else "~${meta?.estimatedSizeMb?.toInt() ?: 80} MB",
                    fontSize = 10.sp,
                    color = Color.Gray
                )
            }
        }
    }
}
