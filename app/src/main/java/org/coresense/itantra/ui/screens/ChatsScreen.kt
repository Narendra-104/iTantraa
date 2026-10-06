package org.coresense.itantra.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import org.coresense.itantra.link.ConnectionState
import org.coresense.itantra.protocol.PacketPriority
import org.coresense.itantra.ui.MainViewModel
import org.coresense.itantra.ui.theme.DarkSurface
import org.coresense.itantra.ui.theme.DarkSurfaceVariant
import org.coresense.itantra.ui.theme.PrimaryNeonGreen
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun ChatsScreen(viewModel: MainViewModel) {
    val messages by viewModel.messages.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val discoveredPeers by viewModel.discoveredPeers.collectAsState()
    val connectedPeer = (connectionState as? ConnectionState.Connected)?.peer

    var inputText by remember { mutableStateOf("") }
    var showTopQuestions by remember { mutableStateOf(false) }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    var selectedRecipient by remember { mutableStateOf(connectedPeer?.id ?: "GLOBAL") }
    var recipientMenuExpanded by remember { mutableStateOf(false) }

    // Aggregate all available and past recipients
    val recipientOptions = remember(connectedPeer, discoveredPeers, messages) {
        val list = mutableListOf<String>()
        connectedPeer?.let { list.add(it.id) }
        discoveredPeers.forEach { p -> if (!list.contains(p.id)) list.add(p.id) }
        messages.mapNotNull { if (it.isIncoming) it.senderId else it.receiverId }.forEach { id ->
            if (id.isNotBlank() && !list.contains(id)) list.add(id)
        }
        listOf("GLOBAL", "NDRF", "Medical", "Police", "Fire & Disaster", "Railway", "Civil Defence").forEach { d ->
            if (!list.contains(d)) list.add(d)
        }
        list
    }

    val currentConversationId = remember(selectedRecipient) {
        if (selectedRecipient == "GLOBAL" || selectedRecipient == "ALL") null
        else viewModel.getConversationId(selectedRecipient)
    }

    val resolvedDept = remember(selectedRecipient) { viewModel.resolveDepartmentId(selectedRecipient) }

    val filteredMessages = remember(messages, selectedRecipient, currentConversationId, resolvedDept) {
        if (selectedRecipient == "ALL") {
            messages
        } else {
            messages.filter { msg ->
                (currentConversationId != null && msg.conversationId == currentConversationId) ||
                (resolvedDept != null && msg.departmentId.equals(resolvedDept, ignoreCase = true)) ||
                msg.receiverId.equals(selectedRecipient, ignoreCase = true) ||
                msg.senderId.equals(selectedRecipient, ignoreCase = true) ||
                (selectedRecipient == "GLOBAL" && (msg.receiverId == null || msg.receiverId.equals("GLOBAL", ignoreCase = true)))
            }
        }
    }

    val topQuestions = listOf(
        "Need immediate assistance.",
        "What is your status?",
        "Send backup now.",
        "Area is clear.",
        "Heading to checkpoint."
    )

    if (showTopQuestions) {
        AlertDialog(
            onDismissRequest = { showTopQuestions = false },
            title = { Text("Top Questions", color = Color.White) },
            text = {
                Column {
                    topQuestions.forEach { question ->
                        Text(
                            text = question,
                            color = PrimaryNeonGreen,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.sendTextMessage(
                                        text = question,
                                        priority = PacketPriority.NORMAL,
                                        receiverId = selectedRecipient,
                                        conversationId = currentConversationId
                                    )
                                    showTopQuestions = false
                                }
                                .padding(12.dp)
                        )
                        Divider(color = Color.DarkGray)
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
        // Header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(DarkSurface)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "CHAT",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
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
                            val label = if (connectedPeer != null && r == connectedPeer.id) "${connectedPeer.name} ($r)" else r
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
        
        Divider(color = Color.Gray)

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
                                    Text(
                                        text = msg.senderId,
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
                                    horizontalArrangement = Arrangement.End,
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

        Divider(color = Color.Gray)

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
                placeholder = { Text("Type message...") },
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
                    contentDescription = "Send",
                    tint = DarkSurface
                )
            }
        }
    }
}
