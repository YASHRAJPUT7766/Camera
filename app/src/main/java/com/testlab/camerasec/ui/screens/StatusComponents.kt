package com.testlab.camerasec.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.testlab.camerasec.ui.theme.ActiveRed
import com.testlab.camerasec.ui.theme.OkGreen
import com.testlab.camerasec.ui.theme.WarnAmber

/**
 * A prominent, unmissable "ACTIVE" pill used for both CAMERA ACTIVE and
 * STREAMING ACTIVE indicators. Pulses gently so it can't be mistaken for
 * static decoration — the whole point is that it's impossible to miss while
 * the camera or the stream is actually running.
 */
@Composable
fun ActiveIndicator(label: String, color: Color = ActiveRed, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .graphicsLayer { this.alpha = alpha }
                .clip(CircleShape)
                .background(Color.White)
        )
        Text(
            text = label,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
fun StatusChip(label: String, active: Boolean, activeColor: Color = OkGreen, inactiveColor: Color = Color.Gray) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background((if (active) activeColor else inactiveColor).copy(alpha = 0.15f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (active) activeColor else inactiveColor)
        )
        Text(
            text = label,
            color = if (active) activeColor else inactiveColor,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun WarningBanner(text: String) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(WarnAmber.copy(alpha = 0.18f))
            .padding(12.dp)
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
