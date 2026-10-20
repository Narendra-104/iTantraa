package org.coresense.itantra.frontend.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import org.coresense.itantra.backend.communication.ConnectionState
import org.coresense.itantra.core.protocol.Language
import org.coresense.itantra.core.protocol.PacketPriority
import org.coresense.itantra.frontend.viewmodel.MainViewModel
import org.coresense.itantra.frontend.theme.DarkSurface
import org.coresense.itantra.frontend.theme.DarkSurfaceVariant
import org.coresense.itantra.frontend.theme.PrimaryNeonGreen
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun ChatsScreen(viewModel: MainViewModel) {
    val selectedLang by viewModel.selectedLanguage.collectAsState()
    val strings = remember(selectedLang) { org.coresense.itantra.frontend.i18n.AppLocalization.getStrings(selectedLang) }
    val messages by viewModel.messages.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val discoveredPeers by viewModel.discoveredPeers.collectAsState()
    val isAutoTts by viewModel.isAutoTtsEnabled.collectAsState()
    val isTtsSpeaking by viewModel.isTtsSpeaking.collectAsState()
    val currentlyPlayingId by viewModel.currentlyPlayingMessageId.collectAsState()
    val connectedPeer = (connectionState as? ConnectionState.Connected)?.peer

    var inputText by remember { mutableStateOf("") }
    var showTopQuestions by remember { mutableStateOf(false) }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val myDeviceId = remember { viewModel.identityRepository.getDeviceId() }

    var selectedRecipient by remember { mutableStateOf(connectedPeer?.id ?: "GLOBAL") }
    var recipientMenuExpanded by remember { mutableStateOf(false) }

    // Aggregate all available and past recipients
    val recipientOptions = remember(connectedPeer, discoveredPeers, messages, myDeviceId) {
        val list = mutableListOf<String>()
        list.add("ALL")
        list.add("GLOBAL")
        connectedPeer?.let {
            if (!it.id.equals(myDeviceId, ignoreCase = true) && !list.contains(it.id)) {
                list.add(it.id)
            }
        }
        discoveredPeers.forEach { p ->
            if (!p.id.equals(myDeviceId, ignoreCase = true) && !list.contains(p.id)) {
                list.add(p.id)
            }
        }
        messages.mapNotNull { if (it.isIncoming) it.senderId else it.receiverId }.forEach { id ->
            if (id.isNotBlank() && id != "GLOBAL" && id != "ALL" && !id.equals(myDeviceId, ignoreCase = true) && !list.contains(id)) {
                list.add(id)
            }
        }
        listOf("NDRF", "Medical", "Police", "Fire & Disaster", "Railway", "Civil Defence").forEach { d ->
            if (!list.contains(d)) list.add(d)
        }
        list
    }

    val currentConversationId = remember(selectedRecipient) {
        if (selectedRecipient == "GLOBAL" || selectedRecipient == "ALL") null
        else viewModel.getConversationId(selectedRecipient)
    }

    val resolvedDept = remember(selectedRecipient) { viewModel.resolveDepartmentId(selectedRecipient) }

    val filteredMessages = remember(messages, selectedRecipient, currentConversationId, resolvedDept, myDeviceId, connectedPeer) {
        val baseMessages = messages.filter { !it.text.startsWith("[TRACK-ME]") }
        when (selectedRecipient) {
            "ALL" -> baseMessages
            "GLOBAL" -> baseMessages.filter { msg ->
                // In GLOBAL channel, display:
                // 1. General broadcasts (receiverId is null/GLOBAL/hotspot and not addressed to a specific department)
                // 2. SOS emergency alerts (always visible in Global Feed)
                // 3. Direct incoming messages addressed to this device
                // 4. Outgoing messages sent by this device to GLOBAL/broadcast
                val isSos = msg.priority.equals("SOS", ignoreCase = true)
                val isGeneralBroadcast = (msg.receiverId.isNullOrBlank() || msg.receiverId.equals("GLOBAL", ignoreCase = true) || msg.receiverId.equals("hotspot_broadcast", ignoreCase = true)) && msg.departmentId == null
                val isBroadcast = isGeneralBroadcast || isSos
                val isDirectToMe = msg.isIncoming && (msg.receiverId.equals(myDeviceId, ignoreCase = true) || viewModel.isDirectRecipient(msg.receiverId, myDeviceId))
                val isMyBroadcast = !msg.isIncoming && (isGeneralBroadcast || isSos)
                isBroadcast || isDirectToMe || isMyBroadcast
            }
            else -> baseMessages.filter { msg ->
                val isConnectedTarget = connectedPeer != null && (
                    selectedRecipient.equals(connectedPeer.id, ignoreCase = true) ||
                    selectedRecipient.equals(connectedPeer.address, ignoreCase = true) ||
                    selectedRecipient.equals(connectedPeer.name, ignoreCase = true) ||
                    selectedRecipient.equals("hotspot_broadcast", ignoreCase = true)
                )

                val matchesConnectedPeer = isConnectedTarget && (
                    msg.receiverId.equals(connectedPeer?.id, ignoreCase = true) ||
                    msg.senderId.equals(connectedPeer?.id, ignoreCase = true) ||
                    msg.receiverId.equals(connectedPeer?.address, ignoreCase = true) ||
                    msg.senderId.equals(connectedPeer?.address, ignoreCase = true) ||
                    msg.receiverId.equals("hotspot_broadcast", ignoreCase = true) ||
                    (viewModel.currentTransportType.value == org.coresense.itantra.backend.communication.TransportType.BLUETOOTH_RFCOMM && !msg.senderId.equals(myDeviceId, ignoreCase = true)) ||
                    (viewModel.currentTransportType.value == org.coresense.itantra.backend.communication.TransportType.BLUETOOTH_RFCOMM && !msg.isIncoming)
                )

                (currentConversationId != null && msg.conversationId == currentConversationId) ||
                (resolvedDept != null && (
                    viewModel.areDepartmentsMatching(msg.departmentId, resolvedDept) ||
                    viewModel.areDepartmentsMatching(msg.receiverId, resolvedDept)
                )) ||
                msg.receiverId.equals(selectedRecipient, ignoreCase = true) ||
                msg.senderId.equals(selectedRecipient, ignoreCase = true) ||
                matchesConnectedPeer
            }
        }
    }

    val topQuestions = remember {
        listOf(
            "Need immediate assistance.",
            "What is your status and location?",
            "Send backup and medical supplies.",
            "Area secured and checkpoint clear.",
            "Heading towards target coordinates.",
            "Casualty count update requested.",
            "Route blocked, requesting alternate path.",
            "All personnel accounted for."
        )
    }

    var isSendingQuestion by remember { mutableStateOf(false) }

    if (showTopQuestions) {
        AlertDialog(
            onDismissRequest = { showTopQuestions = false },
            title = {
                Text(
                    text = "Quick Dispatch Questions",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Recipient: $selectedRecipient",
                        fontSize = 11.sp,
                        color = PrimaryNeonGreen,
                        fontWeight = FontWeight.SemiBold
                    )
                    HorizontalDivider(color = Color.DarkGray)
                    topQuestions.forEach { question ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    text = question,
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    TextButton(
                                        onClick = {
                                            inputText = question
                                            showTopQuestions = false
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Text("Edit in Composer", color = Color.LightGray, fontSize = 11.sp)
                                    }
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Button(
                                        onClick = {
                                            if (!isSendingQuestion) {
                                                isSendingQuestion = true
                                                viewModel.sendTextMessage(
                                                    text = question,
                                                    priority = PacketPriority.NORMAL,
                                                    receiverId = selectedRecipient,
                                                    conversationId = currentConversationId
                                                )
                                                showTopQuestions = false
                                                isSendingQuestion = false
                                            }
                                        },
                                        enabled = !isSendingQuestion,
                                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryNeonGreen),
                                        shape = RoundedCornerShape(6.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                                    ) {
                                        Text("Send", color = DarkSurface, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTopQuestions = false }) {
                    Text("Close", color = Color.Gray)
                }
            },
            containerColor = DarkSurface
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Header with Title and Auto-Read Toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(DarkSurface)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "CHAT",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            // Auto-Read Toggle Button
            Surface(
                color = if (isAutoTts) PrimaryNeonGreen.copy(alpha = 0.15f) else Color.DarkGray.copy(alpha = 0.5f),
                shape = RoundedCornerShape(20.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (isAutoTts) PrimaryNeonGreen else Color.Gray
                ),
                modifier = Modifier.clickable { viewModel.toggleAutoTts() }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isAutoTts) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                        contentDescription = if (isAutoTts) "Disable Auto-Read" else "Enable Auto-Read",
                        tint = if (isAutoTts) PrimaryNeonGreen else Color.LightGray,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isAutoTts) "Auto-Read: ON" else "Auto-Read: OFF",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isAutoTts) PrimaryNeonGreen else Color.LightGray
                    )
                }
            }
        }
        
        // Recipient Selection
        Box(modifier = Modifier.fillMaxWidth().background(DarkSurfaceVariant).padding(8.dp)) {
            OutlinedButton(
                onClick = { recipientMenuExpanded = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
            ) {
                Text("Recipient: $selectedRecipient")
            }
            DropdownMenu(
                expanded = recipientMenuExpanded,
                onDismissRequest = { recipientMenuExpanded = false }
            ) {
                recipientOptions.forEach { r ->
                    DropdownMenuItem(
                        text = {
                            val label = when {
                                r == "ALL" -> "ALL (Unified Feed)"
                                r == "GLOBAL" -> "GLOBAL (Broadcast)"
                                connectedPeer != null && r == connectedPeer.id -> "${connectedPeer.name} ($r)"
                                else -> r
                            }
                            Text(label)
                        },
                        onClick = {
                            selectedRecipient = r
                            recipientMenuExpanded = false
                        }
                    )
                }
            }
        }
        
        HorizontalDivider(color = Color.Gray)

        // Active Speech Status Banner (if TTS is speaking)
        if (isTtsSpeaking) {
            Surface(
                color = Color(0xFF1E3A1E),
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, PrimaryNeonGreen),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = null,
                            tint = PrimaryNeonGreen,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Speaking message...",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Button(
                        onClick = { viewModel.stopPlayback() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = "Stop", modifier = Modifier.size(14.dp), tint = Color.White)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("STOP", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
        }

        // Chat History
        if (filteredMessages.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No messages with $selectedRecipient yet.\nType a message below to start chatting.",
                    color = Color.Gray,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredMessages) { msg ->
                    val isMine = !msg.isIncoming
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start
                    ) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (isMine) Color(0xFF004D00) else DarkSurfaceVariant
                            ),
                            shape = RoundedCornerShape(
                                topStart = 12.dp,
                                topEnd = 12.dp,
                                bottomStart = if (isMine) 12.dp else 0.dp,
                                bottomEnd = if (isMine) 0.dp else 12.dp
                            ),
                            modifier = Modifier
                                .widthIn(max = 280.dp)
                                .clickable {
                                    // Reply click: tapping an incoming message selects that sender as the active recipient
                                    if (!isMine && msg.senderId.isNotBlank()) {
                                        selectedRecipient = msg.senderId
                                    }
                                }
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                if (!isMine) {
                                    val isDirect = msg.receiverId?.equals(myDeviceId, ignoreCase = true) == true || viewModel.isDirectRecipient(msg.receiverId, myDeviceId)
                                    val senderDisplay = if (isDirect && (selectedRecipient == "GLOBAL" || selectedRecipient == "ALL")) {
                                        "${msg.senderId} [Direct]"
                                    } else {
                                        msg.senderId
                                    }
                                    Text(
                                        text = senderDisplay,
                                        color = PrimaryNeonGreen,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                }
                                Text(
                                    text = msg.text,
                                    color = Color.White,
                                    fontSize = 16.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val isPlayingThis = currentlyPlayingId == msg.msgId
                                    Surface(
                                        color = if (isPlayingThis) PrimaryNeonGreen.copy(alpha = 0.25f) else Color.Black.copy(alpha = 0.35f),
                                        shape = RoundedCornerShape(8.dp),
                                        border = androidx.compose.foundation.BorderStroke(
                                            1.dp,
                                            if (isPlayingThis) PrimaryNeonGreen else Color.Gray.copy(alpha = 0.5f)
                                        ),
                                        modifier = Modifier
                                            .clickable {
                                                val msgLang = Language.fromDisplayName(msg.lang)
                                                viewModel.togglePlayMessage(msg.msgId, msg.text, msgLang)
                                            }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = if (isPlayingThis) Icons.Default.Stop else Icons.AutoMirrored.Filled.VolumeUp,
                                                contentDescription = if (isPlayingThis) "Stop Reading" else "Read Aloud",
                                                tint = if (isPlayingThis) PrimaryNeonGreen else Color.White,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = if (isPlayingThis) "Stop" else "Read",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = if (isPlayingThis) PrimaryNeonGreen else Color.White
                                            )
                                        }
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = timeFormat.format(Date(msg.timestamp)),
                                            color = Color.Gray,
                                            fontSize = 12.sp
                                        )
                                        if (isMine) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            val (statusText, statusColor) = when (msg.deliveryState) {
                                                "DELIVERED", "ACKNOWLEDGED" -> Pair("✓✓ Delivered", PrimaryNeonGreen)
                                                "SENT" -> Pair("✓ Sent", Color.LightGray)
                                                "QUEUED", "SENDING" -> Pair("⏳ Sending", Color.Gray)
                                                "FAILED" -> Pair("✗ Failed", Color.Red)
                                                else -> Pair(msg.deliveryState, Color.Gray)
                                            }
                                            Text(
                                                text = statusText,
                                                color = statusColor,
                                                fontSize = 11.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = Color.Gray)

        // Input Area
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { showTopQuestions = true },
                modifier = Modifier
                    .size(48.dp)
                    .background(Color.DarkGray, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.QuestionAnswer,
                    contentDescription = "Top Questions",
                    tint = PrimaryNeonGreen
                )
            }
            
            Spacer(modifier = Modifier.width(8.dp))

            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(strings.typeMessageHint) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PrimaryNeonGreen,
                    unfocusedBorderColor = Color.Gray,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                shape = RoundedCornerShape(24.dp)
            )
            
            Spacer(modifier = Modifier.width(8.dp))
            
            IconButton(
                onClick = {
                    if (inputText.isNotBlank()) {
                        viewModel.sendTextMessage(
                            text = inputText.trim(),
                            priority = PacketPriority.NORMAL,
                            receiverId = selectedRecipient,
                            conversationId = currentConversationId
                        )
                        inputText = ""
                    }
                },
                modifier = Modifier
                    .size(48.dp)
                    .background(PrimaryNeonGreen, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = strings.sendBtn,
                    tint = DarkSurface
                )
            }
        }
    }
}
