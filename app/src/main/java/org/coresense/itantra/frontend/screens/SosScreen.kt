package org.coresense.itantra.frontend.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Shield
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
import org.coresense.itantra.backend.location.GpsState
import org.coresense.itantra.backend.communication.ConnectionState
import org.coresense.itantra.frontend.viewmodel.MainViewModel
import org.coresense.itantra.frontend.theme.DarkSurface
import org.coresense.itantra.frontend.theme.DarkSurfaceVariant
import org.coresense.itantra.frontend.theme.PrimaryNeonGreen

data class SosDepartmentTarget(
    val id: String,
    val title: String
)

@Composable
fun SosScreen(viewModel: MainViewModel) {
    val selectedLang by viewModel.selectedLanguage.collectAsState()
    val strings = remember(selectedLang) { org.coresense.itantra.frontend.i18n.AppLocalization.getStrings(selectedLang) }
    val gpsState by viewModel.gpsState.collectAsState()
    val isSosActive by viewModel.isSosActive.collectAsState()
    val countdown by viewModel.sosCountdownProgress.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val discoveredPeers by viewModel.discoveredPeers.collectAsState()
    val connectedPeer = (connectionState as? ConnectionState.Connected)?.peer

    val departmentTargets = remember {
        listOf(
            SosDepartmentTarget("NDRF", "NDRF / Search & Rescue"),
            SosDepartmentTarget("MEDICAL", "Medical Response"),
            SosDepartmentTarget("POLICE", "Police"),
            SosDepartmentTarget("FIRE", "Fire & Disaster"),
            SosDepartmentTarget("RAILWAY", "Railway Commission"),
            SosDepartmentTarget("CIVIL", "Civil Defence")
        )
    }

    var isDirectUserMode by remember { mutableStateOf(false) }
    var selectedDeptId by remember { mutableStateOf<String?>("NDRF") }
    var customUserId by remember { mutableStateOf("") }

    val effectiveTarget = remember(isDirectUserMode, selectedDeptId, customUserId) {
        if (isDirectUserMode) {
            customUserId.trim().ifEmpty { null }
        } else {
            selectedDeptId
        }
    }

    val buttonEnabled = !effectiveTarget.isNullOrBlank()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = strings.tabSos.uppercase(),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Red
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Direct offline dispatch via mesh packet",
            fontSize = 12.sp,
            color = Color.LightGray
        )

        if (isSosActive) {
            Spacer(modifier = Modifier.height(14.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.Red.copy(alpha = 0.25f)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.Red),
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "🔴 SOS ACTIVE",
                            color = Color.Red,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "Emergency alarm sounding & transmitting",
                            color = Color.White,
                            fontSize = 11.sp
                        )
                    }
                    Button(
                        onClick = { viewModel.cancelActiveSos() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "SILENCE / STOP",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Big Red SOS Button with 2s hold detection
        Box(
            modifier = Modifier
                .size(190.dp)
                .background(
                    if (!buttonEnabled) Color.DarkGray
                    else if (isSosActive) Color.Red.copy(alpha = 0.35f)
                    else DarkSurface,
                    CircleShape
                )
                .border(4.dp, if (buttonEnabled) Color.Red else Color.Gray, CircleShape)
                .pointerInput(buttonEnabled, effectiveTarget) {
                    if (buttonEnabled) {
                        detectTapGestures(
                            onPress = {
                                viewModel.startSosHold(effectiveTarget)
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
                Text("🔴", fontSize = 42.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (isSosActive) "SOS ACTIVE" else strings.holdSosBtn.uppercase(),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center
                )
                if (effectiveTarget != null) {
                    Text(
                        text = "To: $effectiveTarget",
                        color = PrimaryNeonGreen,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Target Selector Section (Department vs Direct User ID)
        Text(
            text = "Select Emergency Recipient Target:",
            color = Color.Black,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { isDirectUserMode = false },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (!isDirectUserMode) Color.Red else DarkSurfaceVariant
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = "Department",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }

            Button(
                onClick = { isDirectUserMode = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isDirectUserMode) Color.Red else DarkSurfaceVariant
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = "Direct User ID",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (!isDirectUserMode) {
            // Department Target Grid (NDRF, Medical, Police, Fire & Disaster, Railway, Civil Defence)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                departmentTargets.chunked(2).forEach { rowTargets ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowTargets.forEach { dept ->
                            val isSelected = selectedDeptId.equals(dept.id, ignoreCase = true)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(52.dp)
                                    .background(
                                        if (isSelected) Color(0xFF8B0000) else DarkSurface,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .border(
                                        if (isSelected) 2.dp else 1.dp,
                                        if (isSelected) Color.Red else Color.DarkGray,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .clickable { selectedDeptId = dept.id },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = dept.title,
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 6.dp),
                                    maxLines = 2
                                )
                            }
                        }
                        if (rowTargets.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        } else {
            // Direct User ID Mode
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Enter Recipient User / Device ID:",
                        fontSize = 12.sp,
                        color = Color.LightGray
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = customUserId,
                        onValueChange = { customUserId = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("e.g. USER-02 or IT-CITIZEN-02", fontSize = 13.sp) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Red,
                            unfocusedBorderColor = Color.Gray,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        shape = RoundedCornerShape(8.dp)
                    )

                    // Quick suggestion chips for connected or discovered peers
                    val peerSuggestions = remember(connectedPeer, discoveredPeers) {
                        val set = mutableSetOf<String>()
                        connectedPeer?.let { if (it.id != "hotspot_broadcast") set.add(it.id) }
                        discoveredPeers.forEach { if (it.id != "hotspot_broadcast") set.add(it.id) }
                        set.toList()
                    }

                    if (peerSuggestions.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Nearby Peer Suggestions:", fontSize = 11.sp, color = Color.Gray)
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            peerSuggestions.forEach { peerId ->
                                Button(
                                    onClick = { customUserId = peerId },
                                    colors = ButtonDefaults.buttonColors(containerColor = DarkSurface),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(peerId, color = PrimaryNeonGreen, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // GPS Coordinates Location Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
            shape = RoundedCornerShape(10.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("📍", fontSize = 20.sp)
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text("Emergency Location Status", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    when (val gps = gpsState) {
                        is GpsState.Fix -> {
                            Text(
                                text = "Lat: ${String.format(java.util.Locale.US, "%.5f", gps.latitude)}, Lon: ${String.format(java.util.Locale.US, "%.5f", gps.longitude)} (±${gps.accuracyMeters.toInt()}m)",
                                color = PrimaryNeonGreen,
                                fontSize = 11.sp
                            )
                        }
                        is GpsState.Searching -> {
                            Text("Searching for GPS satellites...", color = Color.Yellow, fontSize = 11.sp)
                        }
                        else -> {
                            Text("GPS Fix Unavailable", color = Color.Red, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // SMS Emergency Integration (Opens Native Cellular SMS Composer)
        val context = androidx.compose.ui.platform.LocalContext.current
        val userProfile by viewModel.userProfile.collectAsState()
        val identityState by viewModel.identityRepository.userIdentity.collectAsState()

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.DarkGray),
            shape = RoundedCornerShape(10.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "SMS Emergency Fallback (Cellular)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "If cellular signal is available, open your phone's SMS app to review and send this emergency message.",
                    fontSize = 11.sp,
                    color = Color.LightGray
                )
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = {
                        val victimName = userProfile?.name?.ifBlank { null } ?: identityState?.name ?: "Citizen"
                        val bloodGroup = userProfile?.bloodGroup?.ifBlank { null } ?: viewModel.identityRepository.getProfileDetail(org.coresense.itantra.data.repository.IdentityRepository.KEY_PROFILE_BLOOD_GROUP, "Unknown")
                        val gps = gpsState
                        val locStr = if (gps is GpsState.Fix) {
                            "Lat: ${String.format(java.util.Locale.US, "%.5f", gps.latitude)}, Lon: ${String.format(java.util.Locale.US, "%.5f", gps.longitude)} (https://maps.google.com/?q=${gps.latitude},${gps.longitude})"
                        } else {
                            "Location fix unavailable"
                        }
                        val targetStr = effectiveTarget ?: "Emergency Response"
                        val smsBody = "EMERGENCY SOS! I need urgent help.\nName: $victimName\nBlood: $bloodGroup\nTarget: $targetStr\nLocation: $locStr\nDevice: ${viewModel.identityRepository.getDeviceId()}"

                        val intent = android.content.Intent(android.content.Intent.ACTION_SENDTO).apply {
                            data = android.net.Uri.parse("smsto:")
                            putExtra("sms_body", smsBody)
                        }
                        try {
                            context.startActivity(intent)
                        } catch (_: Exception) {}
                    },
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceVariant),
                    border = androidx.compose.foundation.BorderStroke(1.dp, PrimaryNeonGreen),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "OPEN IN PHONE SMS APP",
                        color = PrimaryNeonGreen,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

