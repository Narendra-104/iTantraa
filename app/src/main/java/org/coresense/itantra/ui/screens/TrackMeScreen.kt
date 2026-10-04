package org.coresense.itantra.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.filled.Map
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.coresense.itantra.emergency.GpsState
import org.coresense.itantra.ui.MainViewModel
import org.coresense.itantra.ui.theme.DarkSurface
import org.coresense.itantra.ui.theme.PrimaryNeonGreen

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
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = androidx.compose.material.icons.Icons.Default.Map,
                    contentDescription = "Map",
                    tint = PrimaryNeonGreen,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Live Location Map & Nearest Help Centers", 
                    color = Color.White, 
                    fontWeight = FontWeight.Bold, 
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (gpsState is GpsState.Fix) {
                    val fix = gpsState as GpsState.Fix
                    Text("Lat: ${String.format("%.4f", fix.latitude)}, Long: ${String.format("%.4f", fix.longitude)}", color = Color.LightGray)
                } else {
                    Text("Waiting for GPS...", color = Color.Gray)
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
    }
}
