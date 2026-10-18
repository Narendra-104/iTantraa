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
    onNavigateToTts: () -> Unit = {},
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
                        tint = Color.Black
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "LINK & NETWORK SETTINGS",
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
                colors = CardDefaults.cardColors(
                    containerColor = DarkSurface,
                    contentColor = Color.White
                ),
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
                        Text(
                            text = "App & Voice Language",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            color = Color.White
                        )
                        Text(
                            text = "${currentLang.nativeName} (${currentLang.displayName})",
                            fontSize = 12.sp,
                            color = PrimaryNeonGreen
                        )
                    }
                    Box {
                        OutlinedButton(
                            onClick = { langMenuExpanded = true },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = PrimaryNeonGreen),
                            border = androidx.compose.foundation.BorderStroke(1.dp, PrimaryNeonGreen.copy(alpha = 0.6f))
                        ) {
                            Text("Switch", color = PrimaryNeonGreen, fontWeight = FontWeight.Bold)
                        }
                        DropdownMenu(
                            expanded = langMenuExpanded,
                            onDismissRequest = { langMenuExpanded = false },
                            modifier = Modifier.background(DarkSurfaceVariant)
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
                                            color = if (isInstalled) Color.White else Color(0xFF8B949E)
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
                colors = CardDefaults.cardColors(
                    containerColor = DarkSurface,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Node Callsign",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            color = Color.White
                        )
                        Text(
                            text = callsign,
                            fontSize = 12.sp,
                            color = PrimaryNeonGreen
                        )
                    }
                    Button(
                        onClick = {
                            tempCallsign = callsign
                            editingCallsign = true
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = DarkSurfaceVariant,
                            contentColor = Color.White
                        )
                    ) {
                        Text("Edit", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
        }


        // Transport Selector (Wi-Fi Direct / Bluetooth SPP / LAN UDP)
        item {
            Text(
                text = "OFFLINE LINK TRANSPORT",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFE0E0E0)
            )
            Spacer(modifier = Modifier.height(6.dp))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TransportType.entries.forEach { type ->
                    val isSelected = (activeTransport == type)
                    val isConnected = isSelected && (connectionState is org.coresense.itantra.link.ConnectionState.Connected)
                    val isConnecting = isSelected && (connectionState is org.coresense.itantra.link.ConnectionState.Connecting || connectionState is org.coresense.itantra.link.ConnectionState.Discovering)

                    val cardBorder = when {
                        isConnected -> androidx.compose.foundation.BorderStroke(1.5.dp, PrimaryNeonGreen)
                        isSelected -> androidx.compose.foundation.BorderStroke(1.dp, PrimaryNeonGreen.copy(alpha = 0.6f))
                        else -> null
                    }
                    val containerBg = when {
                        isConnected -> PrimaryNeonGreen.copy(alpha = 0.18f)
                        isSelected -> PrimaryNeonGreen.copy(alpha = 0.08f)
                        else -> DarkSurface
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = containerBg,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(10.dp),
                        border = cardBorder,
                        onClick = { viewModel.connectTransport(type) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val transportIcon = when (type) {
                                    TransportType.WIFI_DIRECT -> Icons.Default.Wifi
                                    TransportType.BLUETOOTH_RFCOMM -> Icons.Default.Bluetooth
                                    TransportType.LAN_UDP -> Icons.Default.Share
                                }
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .background(
                                            if (isConnected) PrimaryNeonGreen.copy(alpha = 0.25f) else DarkSurfaceVariant,
                                            RoundedCornerShape(8.dp)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = transportIcon,
                                        contentDescription = null,
                                        tint = if (isConnected || isSelected) PrimaryNeonGreen else Color.LightGray,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = type.displayName,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = if (isConnected || isSelected) PrimaryNeonGreen else Color.White
                                    )
                                    val desc = when (type) {
                                        TransportType.WIFI_DIRECT -> "High throughput, auto P2P connect"
                                        TransportType.BLUETOOTH_RFCOMM -> "Low power SPP RFCOMM, auto-pair"
                                        TransportType.LAN_UDP -> "Ad-hoc Hotspot UDP, 1-click mesh"
                                    }
                                    Text(
                                        text = desc,
                                        fontSize = 11.sp,
                                        color = Color(0xFFB0B0B0)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            // 1-Click Connect Button
                            when {
                                isConnected -> {
                                    Button(
                                        onClick = { viewModel.disconnectPeer() },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color(0xFF1B5E20),
                                            contentColor = Color.White
                                        ),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = PrimaryNeonGreen,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "CONNECTED",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = PrimaryNeonGreen
                                        )
                                    }
                                }
                                isConnecting -> {
                                    Button(
                                        onClick = { viewModel.stopDiscovery() },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color(0xFFE65100),
                                            contentColor = Color.White
                                        ),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(12.dp),
                                            color = Color.White,
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "CONNECTING...",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                    }
                                }
                                else -> {
                                    Button(
                                        onClick = { viewModel.connectTransport(type) },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = PrimaryNeonGreen,
                                            contentColor = Color.Black
                                        ),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            text = "CONNECT",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.Black
                                        )
                                    }
                                }
                            }
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
                Text(
                    text = "PEER DISCOVERY & PAIRING",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFE0E0E0)
                )
                Row {
                    TextButton(
                        onClick = { 
                            android.util.Log.i("WifiDirectTransport", "UI: Scan button pressed by user")
                            viewModel.startDiscovery() 
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = PrimaryNeonGreen)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = PrimaryNeonGreen
                        )
                        Text(
                            text = " Scan",
                            fontSize = 12.sp,
                            color = PrimaryNeonGreen,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    TextButton(
                        onClick = { 
                            android.util.Log.i("WifiDirectTransport", "UI: Stop discovery button pressed by user")
                            viewModel.stopDiscovery() 
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFE0E0E0))
                    ) {
                        Text(
                            text = "Stop",
                            fontSize = 12.sp,
                            color = Color(0xFFE0E0E0),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }

        // Active Discovery, Connecting, or Error feedback banner
        if (connectionState is org.coresense.itantra.link.ConnectionState.Discovering) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = DarkSurface,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, PrimaryNeonGreen.copy(alpha = 0.4f))
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
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = PrimaryNeonGreen
                            )
                            Text(
                                text = "Ensure the other phone has Wi-Fi / Bluetooth ON and is also scanning.",
                                fontSize = 11.sp,
                                color = Color(0xFFE0E0E0)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
            }
        } else if (connectionState is org.coresense.itantra.link.ConnectionState.Connecting) {
            item {
                val connectingPeer = (connectionState as org.coresense.itantra.link.ConnectionState.Connecting).peer
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = DarkSurface,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFFD700).copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Color(0xFFFFD700),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Connecting to ${connectingPeer.name}...",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFFFFD700)
                            )
                            Text(
                                text = "Establishing secure link via ${connectingPeer.transportType.displayName}.",
                                fontSize = 11.sp,
                                color = Color(0xFFE0E0E0)
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
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF330000),
                        contentColor = Color(0xFFFF6666)
                    ),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF5252).copy(alpha = 0.6f))
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Error",
                            tint = Color(0xFFFF5252),
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
                    colors = CardDefaults.cardColors(
                        containerColor = DarkSurface,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "No peers discovered. Press 'Scan' to discover nearby offline devices.",
                        fontSize = 12.sp,
                        color = Color(0xFFB0B0B0),
                        modifier = Modifier.padding(14.dp)
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
            }
        } else if (discoveredPeers.isNotEmpty()) {
            items(discoveredPeers) { peer ->
                val isThisConnected = (connectionState as? org.coresense.itantra.link.ConnectionState.Connected)?.peer?.id == peer.id
                val isThisConnecting = (connectionState as? org.coresense.itantra.link.ConnectionState.Connecting)?.peer?.id == peer.id
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isThisConnected) PrimaryNeonGreen.copy(alpha = 0.12f) else DarkSurface,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(8.dp),
                    border = if (isThisConnected) androidx.compose.foundation.BorderStroke(1.dp, PrimaryNeonGreen.copy(alpha = 0.6f)) else null
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = peer.name,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = if (isThisConnected) PrimaryNeonGreen else Color.White
                            )
                            Text(
                                text = "${peer.address} • ${peer.transportType.displayName}",
                                fontSize = 11.sp,
                                color = Color(0xFFB0B0B0)
                            )
                            if (isThisConnected) {
                                Text(
                                    text = "Active Link • Connected",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = PrimaryNeonGreen
                                )
                            } else if (isThisConnecting) {
                                Text(
                                    text = "Establishing connection...",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color(0xFFFFD700)
                                )
                            }
                        }
                        Button(
                            onClick = {
                                if (isThisConnected) {
                                    viewModel.disconnectPeer()
                                } else if (!isThisConnecting) {
                                    viewModel.connectToPeer(peer)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isThisConnected) Color(0xFF007A3D) else if (isThisConnecting) Color(0xFF333333) else PrimaryNeonGreen,
                                contentColor = if (isThisConnected || isThisConnecting) Color.White else DarkSurface
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = if (isThisConnected) "Connected" else if (isThisConnecting) "Connecting..." else "Connect",
                                color = if (isThisConnected || isThisConnecting) Color.White else DarkSurface,
                                fontSize = 12.sp,
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
                        colors = CardDefaults.cardColors(
                            containerColor = DarkSurface,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, PrimaryNeonGreen.copy(alpha = 0.3f))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            val connectedPeer = (connectionState as org.coresense.itantra.link.ConnectionState.Connected).peer
                            Text(
                                text = "Active Connection: ${connectedPeer.name}",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = PrimaryNeonGreen
                            )
                            Text(
                                text = "Transport: ${connectedPeer.transportType.displayName} (${connectedPeer.address})",
                                fontSize = 11.sp,
                                color = Color(0xFFE0E0E0)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = { viewModel.testConnection(connectedPeer.id) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = DarkSurfaceVariant,
                                    contentColor = Color.White
                                )
                            ) {
                                Text(
                                    text = "Test Connection to ${connectedPeer.name}",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                            if (testResult != null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = testResult!!,
                                    fontSize = 12.sp,
                                    color = if (testResult!!.startsWith("PASS")) PrimaryNeonGreen else if (testResult!!.startsWith("FAIL")) Color(0xFFFF5252) else Color(0xFFFFD700),
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
                    colors = CardDefaults.cardColors(
                        containerColor = DarkSurface,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Add Manual IP (Fallback if Broadcast blocked)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = manualIp,
                                onValueChange = { manualIp = it },
                                label = { Text("IP Address", color = Color(0xFFB0B0B0)) },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = PrimaryNeonGreen,
                                    unfocusedBorderColor = Color(0xFF555555),
                                    focusedLabelColor = PrimaryNeonGreen,
                                    unfocusedLabelColor = Color(0xFFB0B0B0),
                                    cursorColor = PrimaryNeonGreen
                                )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    if (manualIp.isNotBlank()) {
                                        viewModel.lanUdpTransport.addManualPeer(manualIp.trim())
                                        manualIp = ""
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = PrimaryNeonGreen,
                                    contentColor = DarkSurface
                                )
                            ) {
                                Text("Add", color = DarkSurface, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
            }
        }

        // VAD Sensitivity Slider
        item {
            Text(
                text = "SILERO VAD SPEECH SENSITIVITY",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFE0E0E0)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = DarkSurface,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Speech Threshold", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Medium)
                        Text(String.format("%.2f", vadSensitivity), fontSize = 13.sp, color = PrimaryNeonGreen, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = vadSensitivity,
                        onValueChange = { viewModel.vadSensitivity.value = it },
                        valueRange = 0.2f..0.8f,
                        colors = SliderDefaults.colors(
                            thumbColor = PrimaryNeonGreen,
                            activeTrackColor = PrimaryNeonGreen,
                            inactiveTrackColor = Color(0xFF444444)
                        )
                    )
                    Text(
                        text = "Higher threshold requires louder/clearer speech to trigger transmission.",
                        fontSize = 11.sp,
                        color = Color(0xFFB0B0B0)
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
        }

        // Read-Back Toggle
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = DarkSurface,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Read-Back Before Sending",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            color = Color.White
                        )
                        Text(
                            text = "Speaks recognized text back to sender before packet transmission",
                            fontSize = 11.sp,
                            color = Color(0xFFB0B0B0)
                        )
                    }
                    Switch(
                        checked = isReadBack,
                        onCheckedChange = { viewModel.isReadBackEnabled.value = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = DarkSurface,
                            checkedTrackColor = PrimaryNeonGreen,
                            uncheckedThumbColor = Color(0xFFE0E0E0),
                            uncheckedTrackColor = Color(0xFF444444)
                        )
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
        }

        // OFFLINE TEXT-TO-SPEECH (TTS) Card
        item {
            Text(
                text = "OFFLINE TEXT-TO-SPEECH (TTS)",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFE0E0E0)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = DarkSurface,
                    contentColor = Color.White
                ),
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
                                color = Color(0xFFB0B0B0)
                            )
                        }
                        Switch(
                            checked = isAutoTts,
                            onCheckedChange = { viewModel.toggleAutoTts(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = DarkSurface,
                                checkedTrackColor = PrimaryNeonGreen,
                                uncheckedThumbColor = Color(0xFFE0E0E0),
                                uncheckedTrackColor = Color(0xFF444444)
                            )
                        )
                    }

                    HorizontalDivider(color = Color(0xFF333333), modifier = Modifier.padding(vertical = 10.dp))

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
                                color = Color(0xFFB0B0B0)
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
                                if (isOfflineVoiceAvailable) PrimaryNeonGreen else Color(0xFFFF5252)
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
                            color = Color(0xFFFFB300)
                        )
                    }

                    if (ttsStatusMsg != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = ttsStatusMsg ?: "",
                            fontSize = 11.sp,
                            color = if (isTtsSpeaking) PrimaryNeonGreen else Color(0xFFE0E0E0)
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
                                contentColor = DarkSurface,
                                disabledContainerColor = Color(0xFF2A2E39),
                                disabledContentColor = Color(0xFF8B949E)
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(
                                Icons.Default.VolumeUp,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = if (!isTtsSpeaking) DarkSurface else Color(0xFF8B949E)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "Test Offline Voice",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (!isTtsSpeaking) DarkSurface else Color(0xFF8B949E)
                            )
                        }

                        if (isTtsSpeaking) {
                            Button(
                                onClick = { viewModel.stopPlayback() },
                                modifier = Modifier.height(42.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFD32F2F),
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = "Stop", modifier = Modifier.size(16.dp), tint = Color.White)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("STOP", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedButton(
                        onClick = onNavigateToTts,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = PrimaryNeonGreen),
                        border = androidx.compose.foundation.BorderStroke(1.dp, PrimaryNeonGreen.copy(alpha = 0.6f)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.RecordVoiceOver, contentDescription = null, modifier = Modifier.size(16.dp), tint = PrimaryNeonGreen)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Open Text-to-Speech (TTS) Studio", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = PrimaryNeonGreen)
                    }
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
        }

        // Language Model Manager (11 Languages)
        item {
            Text(
                text = "LANGUAGE MODEL PACKS (11 LANGUAGES)",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFE0E0E0)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Models storage: ${viewModel.modelRegistry.getModelsStorageDirectory().absolutePath}",
                fontSize = 10.sp,
                color = Color(0xFFB0B0B0)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onNavigateToManageLanguages,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PrimaryNeonGreen,
                    contentColor = DarkSurface
                )
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
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFFB300)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFFB300).copy(alpha = 0.6f))
            ) {
                Icon(imageVector = Icons.Default.Science, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFFFFB300))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Open Isolated DEMO Screen (Testing Only)", fontSize = 12.sp, color = Color(0xFFFFB300), fontWeight = FontWeight.Medium)
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (editingCallsign) {
        AlertDialog(
            onDismissRequest = { editingCallsign = false },
            title = { Text("Change Node Callsign", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = tempCallsign,
                    onValueChange = { tempCallsign = it.uppercase() },
                    label = { Text("Callsign (e.g. ALPHA-1)", color = Color(0xFFB0B0B0)) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = PrimaryNeonGreen,
                        unfocusedBorderColor = Color(0xFF555555),
                        focusedLabelColor = PrimaryNeonGreen,
                        unfocusedLabelColor = Color(0xFFB0B0B0),
                        cursorColor = PrimaryNeonGreen
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (tempCallsign.isNotBlank()) {
                            viewModel.callsign.value = tempCallsign.trim()
                        }
                        editingCallsign = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryNeonGreen,
                        contentColor = DarkSurface
                    )
                ) {
                    Text("Save", color = DarkSurface, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { editingCallsign = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFB0B0B0))
                ) {
                    Text("Cancel", color = Color(0xFFB0B0B0))
                }
            },
            containerColor = DarkSurface,
            titleContentColor = Color.White,
            textContentColor = Color.White
        )
    }
}

@Composable
fun LanguageModelCard(lang: Language, meta: ModelMetadata?, ttsStatus: TtsStatus) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = DarkSurface,
            contentColor = Color.White
        ),
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
                    color = Color(0xFFB0B0B0)
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                val isInstalled = (meta?.actualSizeMb ?: 0f) > 0f
                val isBundled = (lang == Language.ENGLISH || lang == Language.HINDI || lang == Language.MARATHI)
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
                    else -> Color(0xFF8B949E)
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
                    color = Color(0xFFB0B0B0)
                )
            }
        }
    }
}
