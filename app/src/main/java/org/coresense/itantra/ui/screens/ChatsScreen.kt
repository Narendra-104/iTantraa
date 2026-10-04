package org.coresense.itantra.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    var inputText by remember { mutableStateOf("") }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

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
        var recipientMenuExpanded by remember { mutableStateOf(false) }
        var selectedRecipient by remember { mutableStateOf("NDRF") }
        val recipients = listOf("NDRF", "Medical", "Police", "Fire & Disaster", "Railway", "Civil Defence")
        
        Box(modifier = Modifier.fillMaxWidth().background(DarkSurfaceVariant).padding(8.dp)) {
            OutlinedButton(
                onClick = { recipientMenuExpanded = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
            ) {
                Text("Select Recipient: $selectedRecipient")
            }
            DropdownMenu(
                expanded = recipientMenuExpanded,
                onDismissRequest = { recipientMenuExpanded = false }
            ) {
                recipients.forEach { r ->
                    DropdownMenuItem(
                        text = { Text(r) },
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
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages) { msg ->
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
                        modifier = Modifier.widthIn(max = 280.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
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
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (msg.deliveryState == "DELIVERED" || msg.deliveryState == "ACKNOWLEDGED") "✓✓" else "✓",
                                        color = if (msg.deliveryState == "DELIVERED" || msg.deliveryState == "ACKNOWLEDGED") PrimaryNeonGreen else Color.Gray,
                                        fontSize = 12.sp
                                    )
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
                        viewModel.sendTextMessage(inputText.trim(), PacketPriority.NORMAL)
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
