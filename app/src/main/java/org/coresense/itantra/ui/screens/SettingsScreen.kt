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
import org.coresense.itantra.ui.theme.DarkSurface
import org.coresense.itantra.ui.theme.DarkSurfaceVariant
import org.coresense.itantra.ui.theme.PrimaryNeonGreen

@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onNavigateToDemo: () -> Unit,
    modifier: Modifier = Modifier
) {
    val callsign by viewModel.callsign.collectAsState()
    val activeTransport by viewModel.currentTransportType.collectAsState()
    val discoveredPeers by viewModel.discoveredPeers.collectAsState()
    val modelsMap by viewModel.modelRegistry.models.collectAsState()
    val isReadBack by viewModel.isReadBackEnabled.collectAsState()
    val vadSensitivity by viewModel.vadSensitivity.collectAsState()

    var editingCallsign by remember { mutableStateOf(false) }
    var tempCallsign by remember { mutableStateOf(callsign) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(14.dp)
    ) {
        item {
            Text(
                text = "RADIO & LINK SETTINGS",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
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
                                DropdownMenuItem(
                                    text = { Text("${lang.nativeName} — ${lang.displayName}") },
                                    onClick = {
                                        viewModel.setAppLanguage(lang)
                                        langMenuExpanded = false
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
                    TextButton(onClick = { viewModel.startDiscovery() }) {
                        Icon(imageVector = Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text(" Scan", fontSize = 12.sp)
                    }
                    TextButton(onClick = { viewModel.stopDiscovery() }) {
                        Text("Stop", fontSize = 12.sp)
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }

        if (discoveredPeers.isEmpty()) {
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
        } else {
            items(discoveredPeers) { peer ->
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
                        Column {
                            Text(text = peer.name, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(text = "${peer.address} • ${peer.transportType.displayName}", fontSize = 10.sp, color = Color.LightGray)
                        }
                        Button(
                            onClick = { viewModel.connectToPeer(peer) },
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryNeonGreen),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                        ) {
                            Text("Connect", color = DarkSurface, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
            }
            item { Spacer(modifier = Modifier.height(10.dp)) }
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
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Language Model Manager (11 Languages)
        item {
            Text(text = "LANGUAGE MODEL PACKS (11 LANGUAGES)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
            Text(
                text = "Models side-loaded in storage: ${viewModel.modelRegistry.getModelsStorageDirectory().absolutePath}",
                fontSize = 10.sp,
                color = Color.LightGray
            )
            Spacer(modifier = Modifier.height(6.dp))
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
                val isInstalled = meta?.status == ModelStatus.INSTALLED
                Text(
                    text = if (isInstalled) "INSTALLED" else "MISSING",
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = if (isInstalled) PrimaryNeonGreen else Color(0xFFFF5252)
                )
                Text(
                    text = if (isInstalled) String.format("%.1f MB", meta.actualSizeMb) else "~${meta?.estimatedSizeMb?.toInt() ?: 80} MB",
                    fontSize = 10.sp,
                    color = Color.Gray
                )
            }
        }
    }
}
