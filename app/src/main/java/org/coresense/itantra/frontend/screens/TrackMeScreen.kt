package org.coresense.itantra.frontend.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.coresense.itantra.backend.location.GpsState
import org.coresense.itantra.backend.emergency.Haversine
import org.coresense.itantra.backend.communication.ConnectionState
import org.coresense.itantra.frontend.viewmodel.MainViewModel
import org.coresense.itantra.frontend.viewmodel.TrackedPeer
import org.coresense.itantra.frontend.theme.DarkSurface
import org.coresense.itantra.frontend.theme.DarkSurfaceVariant
import org.coresense.itantra.frontend.theme.PrimaryNeonGreen
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

@Composable
fun TrackMeScreen(viewModel: MainViewModel) {
    val gpsState by viewModel.gpsState.collectAsState()
    val isTracking by viewModel.isTrackMeActive.collectAsState()
    val trackedPeersMap by viewModel.trackedPeers.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val discoveredPeers by viewModel.discoveredPeers.collectAsState()

    val myDeviceId = remember { viewModel.identityRepository.getDeviceId() }
    val connectedPeer = (connectionState as? ConnectionState.Connected)?.peer

    var selectedRecipient by remember { mutableStateOf("GLOBAL") }
    var recipientMenuExpanded by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(0) } // 0 = Radar Vector, 1 = Peer Directory

    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    val recipientOptions = remember(connectedPeer, discoveredPeers) {
        val list = mutableListOf("GLOBAL", "ALL")
        connectedPeer?.let {
            if (it.id != "hotspot_broadcast" && !list.contains(it.id)) list.add(it.id)
        }
        discoveredPeers.forEach { p ->
            if (p.id != "hotspot_broadcast" && !list.contains(p.id)) list.add(p.id)
        }
        list
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "TRACK ME",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                Text(
                    text = "Real Offline GPS Telemetry & Mesh Visualization",
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }

            // Tracking Active / Inactive Badge
            Surface(
                color = if (isTracking) PrimaryNeonGreen.copy(alpha = 0.2f) else Color.DarkGray,
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (isTracking) PrimaryNeonGreen else Color.Gray
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(if (isTracking) PrimaryNeonGreen else Color.Gray, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isTracking) "TRACKING ON" else "STOPPED",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isTracking) PrimaryNeonGreen else Color.LightGray
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Local Device Location & Controller Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(10.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, if (isTracking) PrimaryNeonGreen else Color.DarkGray)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (gpsState is GpsState.Fix) Icons.Default.GpsFixed else Icons.Default.GpsNotFixed,
                            contentDescription = null,
                            tint = if (gpsState is GpsState.Fix) PrimaryNeonGreen else Color.Gray,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "My Device: $myDeviceId",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = Color.White
                        )
                    }

                    // Recipient Selector Dropdown
                    Box {
                        Text(
                            text = "Target: $selectedRecipient ▼",
                            fontSize = 12.sp,
                            color = PrimaryNeonGreen,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .background(DarkSurfaceVariant, RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 3.dp)
                                .clickable { recipientMenuExpanded = true }
                        )
                        DropdownMenu(
                            expanded = recipientMenuExpanded,
                            onDismissRequest = { recipientMenuExpanded = false }
                        ) {
                            recipientOptions.forEach { opt ->
                                DropdownMenuItem(
                                    text = { Text(opt) },
                                    onClick = {
                                        selectedRecipient = opt
                                        recipientMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Real Local GPS Coordinates Display
                when (val state = gpsState) {
                    is GpsState.Fix -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Lat: ${String.format(Locale.US, "%.5f", state.latitude)}°, Lon: ${String.format(Locale.US, "%.5f", state.longitude)}°",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = PrimaryNeonGreen
                            )
                            Text(
                                text = "±${state.accuracyMeters.toInt()}m",
                                fontSize = 12.sp,
                                color = Color.LightGray
                            )
                        }
                        Text(
                            text = "Fix acquired: ${timeFormat.format(Date(state.timestamp))} (Real GPS)",
                            fontSize = 11.sp,
                            color = Color.Gray
                        )
                    }
                    is GpsState.Searching -> {
                        Text(
                            text = "🛰 Searching for GPS satellite fix...",
                            fontSize = 13.sp,
                            color = Color(0xFFFFC107)
                        )
                    }
                    is GpsState.ProviderDisabled -> {
                        Text(
                            text = "⚠ Location (GPS) disabled. Turn ON GPS in device settings.",
                            fontSize = 12.sp,
                            color = Color(0xFFFF5252)
                        )
                    }
                    is GpsState.PermissionDenied -> {
                        Text(
                            text = "⚠ Location permission not granted. Grant location access to track.",
                            fontSize = 12.sp,
                            color = Color(0xFFFF5252)
                        )
                    }
                    is GpsState.NoFix -> {
                        Text(
                            text = "🛰 Awaiting GPS satellite lock. Ensure outdoor view of sky.",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Start / Stop Tracking Controls Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            if (isTracking) {
                                viewModel.stopTrackMe()
                            } else {
                                viewModel.startTrackMe(targetRecipient = selectedRecipient, intervalMs = 1000L)
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isTracking) Color(0xFFD32F2F) else PrimaryNeonGreen,
                            contentColor = if (isTracking) Color.White else DarkSurface
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(
                            imageVector = if (isTracking) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isTracking) "STOP TRACKING" else "START TRACKING",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }

                    // Send Immediate Location Beacon
                    OutlinedButton(
                        onClick = { viewModel.sendLocationBeacon(selectedRecipient) },
                        enabled = gpsState is GpsState.Fix,
                        modifier = Modifier.weight(0.8f),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = PrimaryNeonGreen,
                            disabledContentColor = Color.Gray
                        )
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("BEACON", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Tab Selector: Radar Vector vs Peer Directory
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = DarkSurface,
            contentColor = PrimaryNeonGreen
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("RADAR VECTOR (${trackedPeersMap.size})", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("PEER LIST (${trackedPeersMap.size})", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Tab Content
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black, RoundedCornerShape(10.dp))
                .border(1.dp, Color.DarkGray, RoundedCornerShape(10.dp))
        ) {
            val isConnected = connectionState is ConnectionState.Connected
            if (selectedTab == 0) {
                RadarVectorView(
                    myGps = gpsState as? GpsState.Fix,
                    trackedPeers = trackedPeersMap.values.toList(),
                    isConnected = isConnected
                )
            } else {
                PeerDirectoryView(
                    myGps = gpsState as? GpsState.Fix,
                    trackedPeers = trackedPeersMap.values.toList(),
                    timeFormat = timeFormat,
                    isConnected = isConnected
                )
            }
        }
    }
}

/**
 * Offline Real Vector Radar Canvas:
 * Plots real peers relative to the local device GPS fix using genuine azimuth and distance math.
 * Never uses simulated coordinates or mock blips.
 */
@Composable
fun RadarVectorView(
    myGps: GpsState.Fix?,
    trackedPeers: List<TrackedPeer>,
    isConnected: Boolean = true
) {
    val infiniteTransition = rememberInfiniteTransition(label = "radarSweep")
    val sweepAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sweep"
    )

    // Calculate maximum distance to scale radar rings
    val maxDistanceMeters = remember(myGps, trackedPeers) {
        if (myGps == null || trackedPeers.isEmpty()) 1000.0
        else {
            val maxD = trackedPeers.maxOfOrNull { peer ->
                Haversine.distanceMeters(myGps.latitude, myGps.longitude, peer.latitude, peer.longitude)
            } ?: 1000.0
            max(maxD * 1.25, 200.0) // At least 200m scale with 25% padding
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            val radius = size.minDimension / 2
            val center = Offset(size.width / 2, size.height / 2)

            // 1. Draw 4 Concentric Distance Range Rings
            for (i in 1..4) {
                val ringFraction = i / 4f
                val ringRadius = radius * ringFraction
                drawCircle(
                    color = PrimaryNeonGreen.copy(alpha = 0.25f),
                    radius = ringRadius,
                    center = center,
                    style = Stroke(width = 1.5f)
                )

                // Label ring distance
                val ringDistMeters = (maxDistanceMeters * ringFraction).toInt()
                val label = if (ringDistMeters >= 1000) "${ringDistMeters / 1000}km" else "${ringDistMeters}m"
                drawContext.canvas.nativeCanvas.apply {
                    val paint = android.graphics.Paint().apply {
                        color = android.graphics.Color.GRAY
                        textSize = 24f
                        isAntiAlias = true
                    }
                    drawText(label, center.x + 8f, center.y - ringRadius + 24f, paint)
                }
            }

            // 2. Draw Cardinal Crosshairs (N, S, E, W)
            drawLine(
                color = PrimaryNeonGreen.copy(alpha = 0.3f),
                start = Offset(center.x, center.y - radius),
                end = Offset(center.x, center.y + radius),
                strokeWidth = 1f
            )
            drawLine(
                color = PrimaryNeonGreen.copy(alpha = 0.3f),
                start = Offset(center.x - radius, center.y),
                end = Offset(center.x + radius, center.y),
                strokeWidth = 1f
            )

            // North indicator
            drawContext.canvas.nativeCanvas.apply {
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.GREEN
                    textSize = 30f
                    isFakeBoldText = true
                    isAntiAlias = true
                    textAlign = android.graphics.Paint.Align.CENTER
                }
                drawText("N", center.x, center.y - radius - 8f, paint)
            }

            // 3. Draw Sweeping Radar Line
            rotate(degrees = sweepAngle, pivot = center) {
                val sweepPath = Path().apply {
                    moveTo(center.x, center.y)
                    lineTo(center.x + radius, center.y - radius / 5)
                    lineTo(center.x + radius, center.y + radius / 5)
                    close()
                }
                drawPath(path = sweepPath, color = PrimaryNeonGreen.copy(alpha = 0.15f))
                drawLine(
                    color = PrimaryNeonGreen,
                    start = center,
                    end = Offset(center.x + radius, center.y),
                    strokeWidth = 2.5f
                )
            }

            // 4. Center Marker (Local Device)
            drawCircle(
                color = PrimaryNeonGreen,
                radius = 6.dp.toPx(),
                center = center
            )
            drawCircle(
                color = Color.Black,
                radius = 2.dp.toPx(),
                center = center
            )

            // 5. Draw Genuine Tracked Peer Blips from Real Relative Coordinates
            if (myGps != null) {
                for (peer in trackedPeers) {
                    val dist = Haversine.distanceMeters(myGps.latitude, myGps.longitude, peer.latitude, peer.longitude)
                    val bearing = Haversine.bearingDegrees(myGps.latitude, myGps.longitude, peer.latitude, peer.longitude)

                    // Convert bearing and distance to canvas Cartesian coordinates
                    // (0 degrees bearing = North = negative Y, 90 degrees = East = positive X)
                    val normalizedDistRatio = (dist / maxDistanceMeters).coerceIn(0.05, 1.0)
                    val blipR = (radius * normalizedDistRatio).toFloat()
                    val thetaRad = Math.toRadians(bearing - 90.0) // 0 deg is top (North)

                    val blipX = center.x + blipR * cos(thetaRad).toFloat()
                    val blipY = center.y + blipR * sin(thetaRad).toFloat()

                    val status = peer.getStatus(isConnected)
                    val blipColor = Color(status.colorHex)

                    // Draw accuracy boundary circle if reported
                    peer.accuracyMeters?.let { accM ->
                        val accRatio = (accM.toDouble() / maxDistanceMeters).coerceIn(0.02, 0.4)
                        val accR = (radius * accRatio).toFloat()
                        drawCircle(
                            color = blipColor.copy(alpha = 0.2f),
                            radius = accR.coerceAtLeast(10f),
                            center = Offset(blipX, blipY),
                            style = Stroke(width = 1.dp.toPx())
                        )
                    }

                    // Draw peer blip circle
                    drawCircle(
                        color = blipColor,
                        radius = 7.dp.toPx(),
                        center = Offset(blipX, blipY)
                    )
                    drawCircle(
                        color = Color.White,
                        radius = 3.dp.toPx(),
                        center = Offset(blipX, blipY)
                    )

                    // Label with Peer ID, status, and distance
                    val distLabel = if (dist >= 1000) String.format(Locale.US, "%.1fkm", dist / 1000.0) else "${dist.toInt()}m"
                    val accLabel = peer.accuracyMeters?.let { " ±${it.toInt()}m" } ?: ""
                    drawContext.canvas.nativeCanvas.apply {
                        val paint = android.graphics.Paint().apply {
                            color = status.colorHex.toInt()
                            textSize = 24f
                            isFakeBoldText = true
                            isAntiAlias = true
                        }
                        drawText("${peer.deviceId} • ${status.label} ($distLabel$accLabel)", blipX + 12f, blipY + 8f, paint)
                    }
                }
            }
        }

        // Overlay status if no peers or no local GPS
        if (myGps == null) {
            Surface(
                color = Color.Black.copy(alpha = 0.75f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Awaiting local device GPS fix to compute peer positions...",
                    color = Color.LightGray,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(12.dp)
                )
            }
        } else if (trackedPeers.isEmpty()) {
            Surface(
                color = Color.Black.copy(alpha = 0.65f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "No peer location beacons received yet.\nStart tracking on a connected peer phone.",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}

/**
 * Detailed Peer Directory View:
 * Lists all received peers with genuine coordinates, timestamp, distance, bearing, and stale indicator.
 */
@Composable
fun PeerDirectoryView(
    myGps: GpsState.Fix?,
    trackedPeers: List<TrackedPeer>,
    timeFormat: SimpleDateFormat,
    isConnected: Boolean = true
) {
    if (trackedPeers.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = null,
                    tint = Color.Gray,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "No Tracked Peers",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Peers transmitting real location over Bluetooth or Wi-Fi hotspot will appear here automatically.",
                    fontSize = 12.sp,
                    color = Color.Gray,
                    textAlign = TextAlign.Center
                )
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(trackedPeers, key = { it.deviceId }) { peer ->
                val status = peer.getStatus(isConnected)
                val statusColor = Color(status.colorHex)

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        statusColor
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .background(statusColor, CircleShape)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = peer.deviceId,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = Color.White
                                )
                            }

                            Surface(
                                color = statusColor.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(6.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, statusColor)
                            ) {
                                Text(
                                    text = status.label,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = statusColor
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        // Real Coordinates
                        Text(
                            text = "Lat: ${String.format(Locale.US, "%.5f", peer.latitude)}°, Lon: ${String.format(Locale.US, "%.5f", peer.longitude)}°",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White
                        )

                        // Relative Distance & Bearing from Local Device (if myGps is available)
                        if (myGps != null) {
                            val dist = Haversine.distanceMeters(myGps.latitude, myGps.longitude, peer.latitude, peer.longitude)
                            val bearing = Haversine.bearingDegrees(myGps.latitude, myGps.longitude, peer.latitude, peer.longitude)
                            val cardinal = Haversine.bearingToCardinal(bearing)

                            val distFormatted = if (dist >= 1000) String.format(Locale.US, "%.2f km", dist / 1000.0) else "${dist.toInt()} m"

                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Distance: $distFormatted ($cardinal, ${bearing.toInt()}°)",
                                    fontSize = 12.sp,
                                    color = Color(0xFF00E5FF),
                                    fontWeight = FontWeight.SemiBold
                                )
                                peer.accuracyMeters?.let { acc ->
                                    val accCategory = when {
                                        acc <= 10f -> "High GPS"
                                        acc <= 30f -> "Standard"
                                        else -> "Reduced"
                                    }
                                    Text(
                                        text = "±${acc.toInt()}m ($accCategory)",
                                        fontSize = 11.sp,
                                        color = if (acc > 50f) Color(0xFFFF9800) else Color.LightGray
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        val fixAgeSeconds = ((System.currentTimeMillis() - peer.timestamp) / 1000).coerceAtLeast(0)
                        val latencyMs = (peer.lastReceivedTime - peer.timestamp).coerceAtLeast(0)
                        Text(
                            text = "Fix Age: ${fixAgeSeconds}s • Latency: ${latencyMs}ms • Seq: #${peer.seq}",
                            fontSize = 11.sp,
                            color = Color.LightGray
                        )
                        Text(
                            text = "Last received: ${timeFormat.format(Date(peer.lastReceivedTime))} (Fix: ${timeFormat.format(Date(peer.timestamp))})",
                            fontSize = 10.sp,
                            color = Color.Gray
                        )
                    }
                }
            }
        }
    }
}
