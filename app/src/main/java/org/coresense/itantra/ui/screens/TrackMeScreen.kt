package org.coresense.itantra.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.coresense.itantra.emergency.GpsState
import org.coresense.itantra.ui.MainViewModel
import org.coresense.itantra.ui.theme.PrimaryNeonGreen
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun TrackMeScreen(viewModel: MainViewModel) {
    val gpsState by viewModel.gpsState.collectAsState()
    val isTracking by viewModel.isTrackMeActive.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "TRACK ME",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.DarkGray, RoundedCornerShape(12.dp))
                .border(2.dp, PrimaryNeonGreen, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            RadarView()
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        if (gpsState is GpsState.Fix) {
            val fix = gpsState as GpsState.Fix
            Text("Lat: ${String.format("%.4f", fix.latitude)}, Long: ${String.format("%.4f", fix.longitude)}", color = Color.LightGray)
        } else {
            Text("Waiting for GPS...", color = Color.Gray)
        }
        
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
fun RadarView() {
    val infiniteTransition = rememberInfiniteTransition(label = "radar")
    val angle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ), label = "angle"
    )

    // Dummy help center blips (polar coordinates: radius ratio, angle in radians)
    val blips = remember {
        listOf(
            Pair(0.4f, Math.toRadians(45.0)),
            Pair(0.7f, Math.toRadians(120.0)),
            Pair(0.5f, Math.toRadians(210.0)),
            Pair(0.9f, Math.toRadians(330.0))
        )
    }

    Canvas(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        val radius = size.minDimension / 2
        val center = Offset(size.width / 2, size.height / 2)

        // Draw concentric circles
        for (i in 1..4) {
            drawCircle(
                color = PrimaryNeonGreen.copy(alpha = 0.3f),
                radius = radius * (i / 4f),
                center = center,
                style = Stroke(width = 2f)
            )
        }

        // Draw center user
        drawCircle(
            color = PrimaryNeonGreen,
            radius = 8.dp.toPx(),
            center = center
        )

        // Draw blips
        for (blip in blips) {
            val r = radius * blip.first
            val theta = blip.second
            val x = center.x + r * cos(theta).toFloat()
            val y = center.y + r * sin(theta).toFloat()
            
            drawCircle(
                color = Color.Red,
                radius = 6.dp.toPx(),
                center = Offset(x, y)
            )
        }

        // Draw sweeping line
        rotate(degrees = angle, pivot = center) {
            val sweepPath = Path().apply {
                moveTo(center.x, center.y)
                lineTo(center.x + radius, center.y - radius / 4)
                lineTo(center.x + radius, center.y + radius / 4)
                close()
            }
            drawPath(
                path = sweepPath,
                color = PrimaryNeonGreen.copy(alpha = 0.2f)
            )
            
            drawLine(
                color = PrimaryNeonGreen,
                start = center,
                end = Offset(center.x + radius, center.y),
                strokeWidth = 4f
            )
        }
    }
}
