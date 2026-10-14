package org.coresense.itantra.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.coresense.itantra.link.ConnectionState
import org.coresense.itantra.ui.MainViewModel
import org.coresense.itantra.ui.theme.DarkSurface
import org.coresense.itantra.ui.theme.DarkSurfaceVariant
import org.coresense.itantra.ui.theme.PrimaryNeonGreen

@Composable
fun DialScreen(viewModel: MainViewModel) {
    val isCallActive by viewModel.isCallActive.collectAsState()
    val activeCallPeerId by viewModel.activeCallPeerId.collectAsState()
    val isCallMuted by viewModel.isCallMuted.collectAsState()
    val callDuration by viewModel.callDurationSeconds.collectAsState()
    val callStatusMessage by viewModel.callStatusMessage.collectAsState()
    val callTranscripts by viewModel.callTranscripts.collectAsState()

    val connectionState by viewModel.connectionState.collectAsState()
    val discoveredPeers by viewModel.discoveredPeers.collectAsState()
    val myDeviceId = remember { viewModel.identityRepository.getDeviceId() }
    val connectedPeer = (connectionState as? ConnectionState.Connected)?.peer

    var selectedPeer by remember { mutableStateOf(connectedPeer?.id ?: "") }
    var peerMenuExpanded by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()

    // Auto-scroll transcript when new speech arrives
    LaunchedEffect(callTranscripts.size) {
        if (callTranscripts.isNotEmpty()) {
            listState.animateScrollToItem(callTranscripts.size - 1)
        }
    }

    // Auto-select connected peer if nothing selected yet
    LaunchedEffect(connectedPeer) {
        if (selectedPeer.isBlank() && connectedPeer != null) {
            selectedPeer = connectedPeer.id
        }
    }

    val availablePeers = remember(connectedPeer, discoveredPeers, myDeviceId) {
        val list = mutableListOf<String>()
        connectedPeer?.let {
            if (it.id != "hotspot_broadcast" && !it.id.equals(myDeviceId, ignoreCase = true)) {
                list.add(it.id)
            }
        }
        discoveredPeers.forEach { p ->
            if (p.id != "hotspot_broadcast" && !p.id.equals(myDeviceId, ignoreCase = true) && !list.contains(p.id)) {
                list.add(p.id)
            }
        }
        list
    }

    // Pulsing animation during active call
    val infiniteTransition = rememberInfiniteTransition(label = "callPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isCallActive) 1.08f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Screen Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "DIAL / VOICE CALL",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "Hands-free Speech-over-Packet Telephony",
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }

            Surface(
                color = if (isCallActive) PrimaryNeonGreen.copy(alpha = 0.2f) else DarkSurfaceVariant,
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (isCallActive) PrimaryNeonGreen else Color.Gray
                )
            ) {
                Text(
                    text = if (isCallActive) {
                        val m = callDuration / 60
                        val s = callDuration % 60
                        String.format("%02d:%02d", m, s)
                    } else "IDLE",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isCallActive) PrimaryNeonGreen else Color.LightGray
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Target Peer Selection Card (visible when not in call)
        if (!isCallActive) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.DarkGray)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Call Recipient:",
                        fontSize = 12.sp,
                        color = Color.Gray,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(DarkSurfaceVariant, RoundedCornerShape(8.dp))
                                .padding(12.dp)
                                .clickable { peerMenuExpanded = true },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (selectedPeer.isNotBlank()) selectedPeer else "Select peer device to call...",
                                color = if (selectedPeer.isNotBlank()) Color.White else Color.Gray,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text("▼", color = PrimaryNeonGreen, fontSize = 12.sp)
                        }

                        DropdownMenu(
                            expanded = peerMenuExpanded,
                            onDismissRequest = { peerMenuExpanded = false }
                        ) {
                            if (availablePeers.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("No connected peers found") },
                                    onClick = { peerMenuExpanded = false }
                                )
                            } else {
                                availablePeers.forEach { peerId ->
                                    DropdownMenuItem(
                                        text = { Text(peerId) },
                                        onClick = {
                                            selectedPeer = peerId
                                            peerMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    if (connectedPeer == null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "⚠ No peer connected. Pair over Bluetooth or Wi-Fi hotspot in Link Settings.",
                            fontSize = 11.sp,
                            color = Color(0xFFFF9800)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // Active Call Avatar & Status Card
        Box(
            modifier = Modifier
                .size(110.dp)
                .scale(pulseScale)
                .background(
                    if (isCallActive) {
                        if (isCallMuted) Color(0xFFFF9800).copy(alpha = 0.25f)
                        else PrimaryNeonGreen.copy(alpha = 0.25f)
                    } else DarkSurface,
                    CircleShape
                )
                .border(
                    3.dp,
                    if (isCallActive) {
                        if (isCallMuted) Color(0xFFFF9800) else PrimaryNeonGreen
                    } else Color.Gray,
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isCallActive) Icons.Default.PhoneInTalk else Icons.Default.Person,
                contentDescription = null,
                tint = if (isCallActive) {
                    if (isCallMuted) Color(0xFFFF9800) else PrimaryNeonGreen
                } else Color.Gray,
                modifier = Modifier.size(52.dp)
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Target Peer Name / Callsign Display
        Text(
            text = if (isCallActive) (activeCallPeerId ?: "In Call") else (if (selectedPeer.isNotBlank()) selectedPeer else "Select Peer"),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )

        // Status Line
        Text(
            text = callStatusMessage,
            fontSize = 13.sp,
            color = if (isCallActive) {
                if (isCallMuted) Color(0xFFFF9800) else PrimaryNeonGreen
            } else Color.Gray
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Live Call Conversation Transcript
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(10.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.DarkGray)
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "LIVE CALL TRANSCRIPT",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Gray
                    )
                    Text(
                        text = "${callTranscripts.size} segments",
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                }

                Divider(color = Color.DarkGray, modifier = Modifier.padding(vertical = 6.dp))

                if (callTranscripts.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (isCallActive) "Speak naturally into microphone...\nSpeech segments will transcribe and transmit automatically."
                            else "Start a call to initiate hands-free voice exchange.\n(Speech is transcribed via offline ONNX STT and read aloud via TTS)",
                            color = Color.Gray,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(callTranscripts) { (speaker, text) ->
                            val isMe = speaker.equals("Me", ignoreCase = true)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start
                            ) {
                                Surface(
                                    color = if (isMe) Color(0xFF004D00) else DarkSurfaceVariant,
                                    shape = RoundedCornerShape(8.dp),
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp,
                                        if (isMe) PrimaryNeonGreen.copy(alpha = 0.5f) else Color.Gray.copy(alpha = 0.3f)
                                    )
                                ) {
                                    Column(modifier = Modifier.padding(8.dp)) {
                                        Text(
                                            text = speaker,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isMe) PrimaryNeonGreen else Color(0xFF00E5FF)
                                        )
                                        Text(
                                            text = text,
                                            fontSize = 14.sp,
                                            color = Color.White
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Call Control Buttons
        if (!isCallActive) {
            Button(
                onClick = {
                    if (selectedPeer.isNotBlank()) {
                        viewModel.startCall(selectedPeer)
                    }
                },
                enabled = selectedPeer.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PrimaryNeonGreen,
                    contentColor = DarkSurface,
                    disabledContainerColor = Color.DarkGray,
                    disabledContentColor = Color.Gray
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Call,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "START CALL",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Mute / Unmute Button
                OutlinedButton(
                    onClick = { viewModel.toggleCallMute() },
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = if (isCallMuted) Color(0xFFFF9800) else Color.White
                    ),
                    border = androidx.compose.foundation.BorderStroke(
                        1.5.dp,
                        if (isCallMuted) Color(0xFFFF9800) else Color.Gray
                    )
                ) {
                    Icon(
                        imageVector = if (isCallMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isCallMuted) "UNMUTE" else "MUTE",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }

                // End Call Button
                Button(
                    onClick = { viewModel.endCall() },
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFD32F2F),
                        contentColor = Color.White
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.CallEnd,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "END CALL",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Speech-over-Packet • Mic stays open • Transcribes after pauses • Remote speaks via offline TTS",
            fontSize = 10.sp,
            color = Color.Gray,
            textAlign = TextAlign.Center
        )
    }
}
