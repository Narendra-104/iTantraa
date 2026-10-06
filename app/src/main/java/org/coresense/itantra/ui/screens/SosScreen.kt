package org.coresense.itantra.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.coresense.itantra.emergency.GpsState
import org.coresense.itantra.ui.MainViewModel
import org.coresense.itantra.ui.theme.DarkSurface
import org.coresense.itantra.ui.theme.PrimaryNeonGreen

@Composable
fun SosScreen(viewModel: MainViewModel) {
    val gpsState by viewModel.gpsState.collectAsState()
    val isSosActive by viewModel.isSosActive.collectAsState()
    val countdown by viewModel.sosCountdownProgress.collectAsState()

    var selectedEmergency by remember { mutableStateOf<String?>(null) }
    val emergencies = listOf("Police", "Fire", "Medical", "Disaster", "Woman", "Child", "Railway")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "🚨 SOS",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Red
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "EMERGENCY ALERT",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )

        Spacer(modifier = Modifier.height(48.dp))

        // Big Red SOS Button
        val buttonEnabled = selectedEmergency != null
        Box(
            modifier = Modifier
                .size(200.dp)
                .background(if (!buttonEnabled) Color.DarkGray else if (isSosActive) Color.Red.copy(alpha = 0.3f) else DarkSurface, CircleShape)
                .border(4.dp, if (buttonEnabled) Color.Red else Color.Gray, CircleShape)
                .pointerInput(buttonEnabled) {
                    if (buttonEnabled) {
                        detectTapGestures(
                            onPress = {
                                viewModel.startSosHold(selectedEmergency)
                                tryAwaitRelease()
                                viewModel.cancelSosHold()
                            }
                        )
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                progress = { countdown },
                modifier = Modifier.fillMaxSize(),
                color = Color.White,
                strokeWidth = 8.dp,
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🔴", fontSize = 48.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (isSosActive) "SOS ACTIVE" else "PRESS FOR SOS",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(48.dp))



        Spacer(modifier = Modifier.height(16.dp))
        
        Text("Select Emergency Type:", color = Color.White, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            emergencies.chunked(2).forEach { rowItems ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowItems.forEach { item ->
                        val isSelected = selectedEmergency == item
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .background(if (isSelected) Color.Red else DarkSurface, RoundedCornerShape(8.dp))
                                .border(1.dp, if (isSelected) Color.Red else Color.Gray, RoundedCornerShape(8.dp))
                                .clickable { selectedEmergency = item },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(item, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    if (rowItems.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }


        
        Spacer(modifier = Modifier.height(16.dp))
    }
}
