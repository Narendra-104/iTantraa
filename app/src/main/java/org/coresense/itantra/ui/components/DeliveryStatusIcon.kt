package org.coresense.itantra.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.coresense.itantra.ui.theme.DangerRed
import org.coresense.itantra.ui.theme.PrimaryNeonGreen

@Composable
fun DeliveryStatusIcon(
    stateName: String,
    rttMs: Long = 0L,
    modifier: Modifier = Modifier
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        when (stateName) {
            "QUEUED", "SENDING" -> {
                Icon(
                    imageVector = Icons.Default.Schedule,
                    contentDescription = "Queued",
                    tint = Color.Gray,
                    modifier = Modifier.size(14.dp)
                )
            }
            "SENT" -> {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Sent awaiting ACK",
                    tint = Color.LightGray,
                    modifier = Modifier.size(14.dp)
                )
            }
            "DELIVERED" -> {
                Icon(
                    imageVector = Icons.Default.DoneAll,
                    contentDescription = "Delivered with ACK",
                    tint = PrimaryNeonGreen,
                    modifier = Modifier.size(16.dp)
                )
                if (rttMs > 0) {
                    Text(
                        text = " ${rttMs}ms",
                        color = PrimaryNeonGreen,
                        fontSize = 10.sp
                    )
                }
            }
            "FAILED" -> {
                Icon(
                    imageVector = Icons.Default.ErrorOutline,
                    contentDescription = "Failed",
                    tint = DangerRed,
                    modifier = Modifier.size(14.dp)
                )
            }
            else -> {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Sent",
                    tint = Color.Gray,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}
