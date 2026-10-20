package org.coresense.itantra.frontend.screens

import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.coresense.itantra.core.protocol.PacketPriority
import org.coresense.itantra.frontend.viewmodel.MainViewModel
import org.coresense.itantra.frontend.theme.DarkSurface
import org.coresense.itantra.frontend.theme.DarkSurfaceVariant
import org.coresense.itantra.frontend.theme.PrimaryNeonGreen
import org.coresense.itantra.frontend.theme.SecondaryTeal

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun RadioScreen(
    viewModel: MainViewModel,
    onNavigateBack: () -> Unit = {},
    onOpenSettings: () -> Unit = {}
) {
    val transcript by viewModel.liveTranscript.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val selectedLang by viewModel.selectedLanguage.collectAsState()
    val strings = remember(selectedLang) { org.coresense.itantra.frontend.i18n.AppLocalization.getStrings(selectedLang) }
    val pendingText by viewModel.readBackPendingText.collectAsState()
    val scope = rememberCoroutineScope()

    var showRecipientDialog by remember { mutableStateOf(false) }
    var isSendingMessage by remember { mutableStateOf(false) }
    var sendStatusMessage by remember { mutableStateOf<String?>(null) }
    
    val connectedStr = if (connectionState is org.coresense.itantra.backend.communication.ConnectionState.Connected) {
        "${strings.connectedStatus}: ${(connectionState as org.coresense.itantra.backend.communication.ConnectionState.Connected).peer.name}"
    } else {
        strings.disconnectedStatus
    }

    val departmentOptions = remember {
        listOf(
            "NDRF" to "NDRF / Search & Rescue",
            "MEDICAL" to "Medical Response",
            "POLICE" to "Police",
            "FIRE" to "Fire & Disaster",
            "RAILWAY" to "Railway Commission",
            "CIVIL" to "Civil Defence",
            "GLOBAL" to "All / Global Broadcast"
        )
    }

    if (showRecipientDialog) {
        AlertDialog(
            onDismissRequest = { showRecipientDialog = false },
            title = {
                Text(
                    text = "Select Receiving Department",
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Choose the department to receive this transcribed audio dispatch:",
                        fontSize = 12.sp,
                        color = Color.LightGray
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    departmentOptions.forEach { (deptId, deptTitle) ->
                        Button(
                            onClick = {
                                if (isSendingMessage) return@Button
                                isSendingMessage = true
                                val textToSend = (pendingText ?: transcript).trim()
                                if (textToSend.isNotBlank()) {
                                    viewModel.sendTextMessage(
                                        text = textToSend,
                                        priority = PacketPriority.NORMAL,
                                        departmentId = deptId
                                    )
                                    viewModel.readBackPendingText.value = null
                                    sendStatusMessage = "Dispatched to $deptTitle"
                                }
                                showRecipientDialog = false
                                scope.launch {
                                    delay(500)
                                    isSendingMessage = false
                                }
                            },
                            enabled = !isSendingMessage,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceVariant),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = deptTitle,
                                color = PrimaryNeonGreen,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showRecipientDialog = false }) {
                    Text("Cancel", color = Color.Gray)
                }
            },
            containerColor = DarkSurface,
            titleContentColor = Color.White,
            textContentColor = Color.White
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top Header: centered title without arrow symbol
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = strings.tabRadio.uppercase(),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = PrimaryNeonGreen
            )
        }
        
        Spacer(modifier = Modifier.height(10.dp))
        
        // Status Row: Red dot preserved, Connected & Disconnected text displayed in black
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(
                        if (connectionState is org.coresense.itantra.backend.communication.ConnectionState.Connected) PrimaryNeonGreen else Color.Red,
                        CircleShape
                    )
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = connectedStr,
                color = Color.Black,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        // PTT Button
        Box(
            modifier = Modifier
                .size(125.dp)
                .background(
                    if (isRecording) PrimaryNeonGreen.copy(alpha = 0.2f) else DarkSurface,
                    CircleShape
                )
                .border(2.dp, if (isRecording) PrimaryNeonGreen else SecondaryTeal, CircleShape)
                .pointerInteropFilter { event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            viewModel.isReadBackEnabled.value = true
                            viewModel.onPttPressed()
                            true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            viewModel.onPttReleased()
                            true
                        }
                        else -> false
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "Mic",
                    tint = if (isRecording) PrimaryNeonGreen else Color.White,
                    modifier = Modifier.size(38.dp)
                )
                Text(
                    text = if (isRecording) strings.listening.uppercase() else strings.holdPtt.uppercase(),
                    color = if (isRecording) PrimaryNeonGreen else Color.White,
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        // Transcript Box
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .background(DarkSurfaceVariant, RoundedCornerShape(8.dp))
                .border(1.dp, Color.Gray, RoundedCornerShape(8.dp))
                .padding(16.dp)
        ) {
            Text(
                text = pendingText ?: transcript.ifEmpty { "\"Need assistance at building entrance.\"" },
                color = if (pendingText != null || transcript.isNotEmpty()) Color.White else Color.Gray,
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        if (sendStatusMessage != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = sendStatusMessage ?: "",
                color = PrimaryNeonGreen,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(
                onClick = { scope.launch { viewModel.ttsEngine.speak(pendingText ?: transcript, selectedLang) } },
                colors = ButtonDefaults.buttonColors(containerColor = DarkSurface),
                enabled = pendingText != null || transcript.isNotBlank()
            ) {
                Icon(Icons.Default.VolumeUp, contentDescription = "Play", tint = Color.White)
                Spacer(modifier = Modifier.width(8.dp))
                Text("PLAY", color = Color.White)
            }

            Button(
                onClick = {
                    showRecipientDialog = true
                },
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryNeonGreen),
                enabled = (pendingText != null || transcript.isNotBlank()) && !isSendingMessage
            ) {
                Text(strings.sendBtn.uppercase(), color = DarkSurface, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.width(8.dp))
                Icon(Icons.Default.Send, contentDescription = "Send", tint = DarkSurface)
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Bottom info with black text
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            var languageMenuExpanded by remember { mutableStateOf(false) }
            Box {
                val currentLangLabel = if (selectedLang == org.coresense.itantra.core.protocol.Language.MARATHI) "Marathi (मराठी)" else selectedLang.displayName
                Text(
                    text = "Language: $currentLangLabel ▼", 
                    color = Color.Black,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable { languageMenuExpanded = true }
                )
                DropdownMenu(
                    expanded = languageMenuExpanded,
                    onDismissRequest = { languageMenuExpanded = false }
                ) {
                    org.coresense.itantra.core.protocol.Language.entries.forEach { lang ->
                        val isCurrent = selectedLang == lang
                        val isReady = isCurrent && viewModel.sttEngine.isLanguageLoaded(lang)
                        val isInstalled = viewModel.modelRegistry.isModelInstalled(lang)
                        val suffix = when {
                            isReady -> " [Ready]"
                            isInstalled -> " [Installed]"
                            else -> " [Not Installed]"
                        }
                        val langLabel = if (lang == org.coresense.itantra.core.protocol.Language.MARATHI) "Marathi (मराठी)" else lang.displayName
                        DropdownMenuItem(
                            text = { 
                                Text(
                                    "$langLabel$suffix",
                                    color = if (isInstalled) Color.White else Color.Gray
                                ) 
                            },
                            enabled = isInstalled,
                            onClick = {
                                if (isInstalled) {
                                    viewModel.setAppLanguage(lang)
                                    languageMenuExpanded = false
                                }
                            }
                        )
                    }
                }
            }
            val activeTransport by viewModel.currentTransportType.collectAsState()
            Text(
                text = "Connection: ${activeTransport.displayName} ●",
                color = Color.Black,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable { onOpenSettings() }
            )
        }
    }
}
