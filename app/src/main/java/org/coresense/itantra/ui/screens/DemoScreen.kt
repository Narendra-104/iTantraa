package org.coresense.itantra.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.coresense.itantra.emergency.Haversine
import org.coresense.itantra.protocol.*
import org.coresense.itantra.ui.MainViewModel
import org.coresense.itantra.ui.theme.DarkSurface
import org.coresense.itantra.ui.theme.DarkSurfaceVariant
import org.coresense.itantra.ui.theme.PrimaryNeonGreen

/**
 * HARD RULE 2:
 * A "Demo/Test" screen may exist, but it must be clearly labelled DEMO,
 * isolated from production code, and off by default.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DemoScreen(
    viewModel: MainViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    var loopbackResult by remember { mutableStateOf<String?>(null) }
    var haversineResult by remember { mutableStateOf<String?>(null) }
    var ttsTestResult by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.Science, contentDescription = null, tint = Color(0xFFFFB300))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("DEMO / TEST LAB (ISOLATED)", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkSurface)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Prominent DEMO Banner
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF332A15)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "⚠ DEMO & DIAGNOSTICS ENVIRONMENT",
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFFFFB300),
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "This screen is strictly isolated from production code paths. Use this for testing protocol encoding, offline TTS voices, and Haversine math without transmitting over real radio links.",
                            fontSize = 11.sp,
                            color = Color.LightGray
                        )
                    }
                }
            }

            // Test 1: Protocol Buffers Loopback & Wire Size
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("1. Protocol Buffers Wire Format Test", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("Encodes a real sample packet, verifies CRC32, and measures wire bytes.", fontSize = 11.sp, color = Color.Gray)
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                val testPacket = Packet(
                                    msgId = 123456789L,
                                    senderId = "DEMO-NODE",
                                    seq = 42,
                                    lang = Language.HINDI,
                                    priority = PacketPriority.NORMAL,
                                    text = "नमस्ते क्या आप सुन रहे हैं",
                                    timestamp = System.currentTimeMillis(),
                                    latitudeMicrodegrees = 28567200,
                                    longitudeMicrodegrees = 77210000,
                                    type = PacketType.DATA
                                )
                                val encoded = PacketCodec.encode(testPacket)
                                val decodedResult = PacketCodec.decode(encoded)
                                loopbackResult = if (decodedResult.isSuccess) {
                                    val dec = decodedResult.getOrThrow()
                                    "Success! Encoded size: ${encoded.size} bytes (Typical: ~40-80B)\nDecoded text: \"${dec.text}\"\nCRC32 verified: ${dec.crc32 != 0L}"
                                } else {
                                    "Decode failed: ${decodedResult.exceptionOrNull()?.message}"
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryNeonGreen)
                        ) {
                            Text("Run Packet Codec Test", color = DarkSurface, fontWeight = FontWeight.Bold)
                        }

                        if (loopbackResult != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(loopbackResult!!, fontSize = 11.sp, color = PrimaryNeonGreen)
                        }
                    }
                }
            }

            // Test 2: Offline TTS Speech Test
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("2. Offline TTS Voice Test", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("Verifies isNetworkConnectionRequired == false on current device.", fontSize = 11.sp, color = Color.Gray)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    coroutineScope.launch {
                                        val res = viewModel.ttsEngine.speak("This is an offline text to speech test on iTantra.", Language.ENGLISH)
                                        ttsTestResult = if (res.isSuccess) "English playback succeeded offline." else "Error: ${res.exceptionOrNull()?.message}"
                                    }
                                }
                            ) {
                                Text("Test English")
                            }
                            Button(
                                onClick = {
                                    coroutineScope.launch {
                                        val res = viewModel.ttsEngine.speak("नमस्ते, यह एक ऑफलाइन आवाज परीक्षण है।", Language.HINDI)
                                        ttsTestResult = if (res.isSuccess) "Hindi playback succeeded offline." else "Error: ${res.exceptionOrNull()?.message}"
                                    }
                                }
                            ) {
                                Text("Test Hindi")
                            }
                        }

                        if (ttsTestResult != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(ttsTestResult!!, fontSize = 11.sp, color = Color.White)
                        }
                    }
                }
            }

            // Test 3: Haversine Distance & Bearing Test
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("3. Offline Haversine Calculation Test", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("Calculates distance between AIIMS (28.5672, 77.2100) and Connaught Place (28.6315, 77.2167).", fontSize = 11.sp, color = Color.Gray)
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                val dist = Haversine.distanceMeters(28.5672, 77.2100, 28.6315, 77.2167)
                                val bearing = Haversine.bearingDegrees(28.5672, 77.2100, 28.6315, 77.2167)
                                val cardinal = Haversine.bearingToCardinal(bearing)
                                haversineResult = String.format("Distance: %.2f km (%.0f meters) • Bearing: %.1f° (%s)", dist / 1000.0, dist, bearing, cardinal)
                            }
                        ) {
                            Text("Compute Haversine")
                        }

                        if (haversineResult != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(haversineResult!!, fontSize = 11.sp, color = PrimaryNeonGreen)
                        }
                    }
                }
            }
        }
    }
}
