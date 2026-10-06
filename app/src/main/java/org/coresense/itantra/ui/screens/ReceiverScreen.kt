package org.coresense.itantra.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Reply
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.coresense.itantra.identity.Role
import org.coresense.itantra.protocol.PacketPriority
import org.coresense.itantra.storage.MessageEntity
import org.coresense.itantra.ui.MainViewModel
import org.coresense.itantra.ui.theme.DarkSurface
import org.coresense.itantra.ui.theme.DarkSurfaceVariant
import org.coresense.itantra.ui.theme.PrimaryNeonGreen
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun ReceiverScreen(
    viewModel: MainViewModel,
    onNavigateBack: () -> Unit = {}
) {
    val messages by viewModel.messages.collectAsState()
    val isPlaying = viewModel.activePipelineStage.collectAsState().value == "TTS"
    val identityState by viewModel.identityRepository.userIdentity.collectAsState()
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val coroutineScope = rememberCoroutineScope()

    // UI View Filter ONLY - does NOT alter local identity or packet routing
    var selectedViewFilter by remember { mutableStateOf("ALL") }
    val localDeptId = identityState?.departmentId
    val localRole = identityState?.role ?: Role.USER

    // Department requests inbox filtered by UI display filter
    val departmentRequests = remember(messages, selectedViewFilter) {
        messages.filter { msg ->
            msg.isIncoming && (
                selectedViewFilter == "ALL" ||
                msg.departmentId.equals(selectedViewFilter, ignoreCase = true) ||
                msg.receiverId.equals(selectedViewFilter, ignoreCase = true)
            )
        }.sortedByDescending { it.timestamp }
    }

    var selectedRequest by remember { mutableStateOf<MessageEntity?>(null) }
    var replyText by remember { mutableStateOf("") }

    // Auto-select latest request if none selected
    LaunchedEffect(departmentRequests) {
        if (selectedRequest == null && departmentRequests.isNotEmpty()) {
            selectedRequest = departmentRequests.first()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        // Top Header with Back button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onNavigateBack,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "DEPARTMENT INBOX",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "Identity: ${localRole.name} | Bound Dept: ${localDeptId ?: "None (User)"}",
                    fontSize = 11.sp,
                    color = PrimaryNeonGreen
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Display Filter Section (Display only, does not alter device identity)
        Text(
            text = "View Filter (Display Only):",
            fontSize = 11.sp,
            color = Color.Gray
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            listOf("ALL", "NDRF", "MEDICAL", "POLICE", "FIRE").forEach { dept ->
                val isSelected = selectedViewFilter.equals(dept, ignoreCase = true)
                Button(
                    onClick = {
                        selectedViewFilter = dept
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSelected) PrimaryNeonGreen else DarkSurfaceVariant
                    ),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = dept,
                        color = if (isSelected) DarkSurface else Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Inbox List
        if (departmentRequests.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(DarkSurfaceVariant, RoundedCornerShape(12.dp))
                    .border(1.dp, Color.DarkGray, RoundedCornerShape(12.dp))
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No incoming requests${if (selectedViewFilter != "ALL") " for $selectedViewFilter" else ""}.\nWaiting for field packets...",
                    color = Color.Gray,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(departmentRequests) { req ->
                    val isSelected = selectedRequest?.msgId == req.msgId
                    val isSos = req.priority == "SOS" || req.priority == "URGENT"

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedRequest = req },
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) Color(0xFF1B3B1B) else DarkSurface
                        ),
                        shape = RoundedCornerShape(10.dp),
                        border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, PrimaryNeonGreen) else null
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Notifications,
                                        contentDescription = null,
                                        tint = if (isSos) Color.Red else PrimaryNeonGreen,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "From: ${req.senderId}",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = Color.White
                                    )
                                }
                                Text(
                                    text = timeFormat.format(Date(req.timestamp)),
                                    fontSize = 11.sp,
                                    color = Color.Gray
                                )
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(
                                text = req.text,
                                fontSize = 15.sp,
                                color = Color.White
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "Dept: ${req.departmentId ?: localDeptId ?: "GENERAL"}",
                                        fontSize = 11.sp,
                                        color = PrimaryNeonGreen
                                    )
                                    if (isSos) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "[EMERGENCY]",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.Red
                                        )
                                    }
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = {
                                            coroutineScope.launch {
                                                viewModel.ttsEngine.speak(req.text, org.coresense.itantra.protocol.Language.ENGLISH)
                                            }
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.VolumeUp,
                                            contentDescription = "Read Audio",
                                            tint = if (isPlaying) PrimaryNeonGreen else Color.Gray,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Delivered",
                                        tint = PrimaryNeonGreen,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Text(
                                        text = req.deliveryState,
                                        fontSize = 11.sp,
                                        color = Color.LightGray
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Reply Section
        selectedRequest?.let { targetReq ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Reply,
                                contentDescription = null,
                                tint = PrimaryNeonGreen,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Reply to ${targetReq.senderId}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = PrimaryNeonGreen
                            )
                        }
                        Text(
                            text = "Conv: ${targetReq.conversationId ?: "Direct"}",
                            fontSize = 10.sp,
                            color = Color.Gray
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = replyText,
                            onValueChange = { replyText = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Type department response...", fontSize = 13.sp) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = PrimaryNeonGreen,
                                unfocusedBorderColor = Color.Gray,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            shape = RoundedCornerShape(20.dp)
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        IconButton(
                            onClick = {
                                if (replyText.isNotBlank()) {
                                    viewModel.sendTextMessage(
                                        text = replyText.trim(),
                                        priority = PacketPriority.NORMAL,
                                        receiverId = targetReq.senderId,
                                        conversationId = targetReq.conversationId
                                    )
                                    replyText = ""
                                }
                            },
                            modifier = Modifier
                                .size(44.dp)
                                .background(PrimaryNeonGreen, CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send Reply",
                                tint = DarkSurface,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
