package org.coresense.itantra.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.coresense.itantra.ui.theme.PrimaryNeonGreen
import kotlin.math.sin

@Composable
fun AudioWaveform(
    amplitude: Float,
    isCapturing: Boolean,
    modifier: Modifier = Modifier,
    barCount: Int = 16,
    activeColor: Color = PrimaryNeonGreen,
    inactiveColor: Color = Color.Gray.copy(alpha = 0.3f)
) {
    Row(
        modifier = modifier.height(36.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 0 until barCount) {
            val factor = if (isCapturing) {
                // Symmetrical wave pattern
                val phase = sin(Math.PI * i / barCount).toFloat()
                (amplitude * phase * 1.5f + 0.1f).coerceIn(0.1f, 1f)
            } else {
                0.1f
            }

            val animatedHeight by animateFloatAsState(
                targetValue = factor,
                label = "bar_height_$i"
            )

            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight(animatedHeight)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (isCapturing) activeColor else inactiveColor)
            )
        }
    }
}
