package com.openyt.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun DownloadProgressBar(
    title: String,
    percent: Double,
    modifier: Modifier = Modifier,
) {
    val target = (percent / 100.0).toFloat().coerceIn(0f, 1f)
    val fraction by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing),
        label = "download-progress",
    )
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(percent = 50)
    Box(
        modifier = modifier
            .height(6.dp)
            .clip(shape)
            .background(colors.secondary.copy(alpha = 0.16f))
            .semantics {
                contentDescription = "$title: ${percent.toInt()}%"
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
            },
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction)
                .clip(shape)
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(colors.secondary, colors.tertiary),
                    ),
                ),
        )
    }
}
