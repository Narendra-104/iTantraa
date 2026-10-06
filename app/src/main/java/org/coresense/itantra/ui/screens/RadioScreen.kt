package org.coresense.itantra.ui.screens

import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import org.coresense.itantra.ui.MainViewModel
import kotlinx.coroutines.launch
import org.coresense.itantra.ui.theme.DarkSurface
import org.coresense.itantra.ui.theme.DarkSurfaceVariant
import org.coresense.itantra.ui.theme.PrimaryNeonGreen
import org.coresense.itantra.ui.theme.SecondaryTeal

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun RadioScreen(
    viewModel: MainViewModel,
    onOpenSettings: () -> Unit = {}
) {
    val transcript by viewModel.liveTranscript.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val selectedLang by viewModel.selectedLanguage.collectAsState()
    val pendingText by viewModel.readBackPendingText.collectAsState()
    val scope = rememberCoroutineScope()
    
    val connectedStr = if (connectionState is org.coresense.itantra.link.ConnectionState.Connected) {
        "Connected: ${(connectionState as org.coresense.itantra.link.ConnectionState.Connected).peer.name}"
    } else {
        "Disconnected"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "AUDIO",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = PrimaryNeonGreen
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(
                        if (connectionState is org.coresense.itantra.link.ConnectionState.Connected) PrimaryNeonGreen else Color.Red,
                        CircleShape
                    )
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = connectedStr, color = Color.White)
        }

        Spacer(modifier = Modifier.height(32.dp))

        // PTT Button
        Box(
            modifier = Modifier
                .size(120.dp)
                .background(
                    if (isRecording) PrimaryNeonGreen.copy(alpha = 0.2f) else DarkSurface,
                    CircleShape
                )
                .border(2.dp, if (isRecording) PrimaryNeonGreen else SecondaryTeal, CircleShape)
                .pointerInteropFilter { event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            viewModel.isReadBackEnabled.value = true // Ensure read back is used
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
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "Mic",
                    tint = if (isRecording) PrimaryNeonGreen else Color.White,
                    modifier = Modifier.size(48.dp)
                )
                Text(
                    text = if (isRecording) "RECORDING..." else "HOLD TO TALK",
                    color = if (isRecording) PrimaryNeonGreen else Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

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
                onClick = { viewModel.confirmReadBackSend() },
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryNeonGreen),
                enabled = pendingText != null || transcript.isNotBlank()
            ) {
                Text("SEND", color = DarkSurface, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.width(8.dp))
                Icon(Icons.Default.Send, contentDescription = "Send", tint = DarkSurface)
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Bottom info
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            var languageMenuExpanded by remember { mutableStateOf(false) }
            Box {
                Text(
                    text = "Language: ${selectedLang.displayName} ▼", 
                    color = Color.LightGray,
                    modifier = Modifier.clickable { languageMenuExpanded = true }
                )
                DropdownMenu(
                    expanded = languageMenuExpanded,
                    onDismissRequest = { languageMenuExpanded = false }
                ) {
                    org.coresense.itantra.protocol.Language.entries.forEach { lang ->
                        DropdownMenuItem(
                            text = { Text(lang.displayName) },
                            onClick = {
                                viewModel.setAppLanguage(lang)
                                languageMenuExpanded = false
                            }
                        )
                    }
                }
            }
            val activeTransport by viewModel.currentTransportType.collectAsState()
            val isConnected = connectionState is org.coresense.itantra.link.ConnectionState.Connected
            Text(
                text = "Connection: ${activeTransport.displayName} ●",
                color = if (isConnected) PrimaryNeonGreen else Color.Gray,
                modifier = Modifier.clickable { onOpenSettings() }
            )
        }
    }
}
