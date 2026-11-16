package com.openyt.ui.components

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openyt.ui.i18n.FeatureText
import com.openyt.ui.i18n.LocalStrings
import kotlin.math.PI
import kotlin.math.sin

/**
 * Branded startup loading view shown while persistence and initial state restore.
 *
 * Keeps the loading indicator strictly bounded inside a centered column so
 * parent Surface min-constraints can never stretch it across the viewport.
 */
@Composable
fun StartupLoadingScreen(
    modifier: Modifier = Modifier,
    statusText: String = LocalStrings.current.features[FeatureText.STARTUP_PREPARING],
) {
    val colors = MaterialTheme.colorScheme
    val strings = LocalStrings.current
    val loadingLabel = strings.features[FeatureText.STARTUP_LOADING_LABEL]
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val entrance by animateFloatAsState(
        targetValue = if (entered) 1f else 0.92f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "startup-entrance",
    )

    val wordmark = buildAnnotatedString {
        append(strings.appName.removeSuffix("YT"))
        if (strings.appName.endsWith("YT")) {
            withStyle(SpanStyle(color = colors.secondary)) {
                append("YT")
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .testTag("startup-loading-screen"),
        contentAlignment = Alignment.Center,
    ) {
        if (colors.background.luminance() < 0.2f) {
            val haloColor = colors.secondary.copy(alpha = 0.08f)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithCache {
                        val haloCenter = Offset(size.width / 2f, size.height / 2f)
                        val radius = (minOf(size.width, size.height) * 0.40f).coerceAtLeast(160f)
                        val brush = Brush.radialGradient(
                            colors = listOf(haloColor, Color.Transparent),
                            center = haloCenter,
                            radius = radius,
                        )
                        onDrawBehind {
                            drawCircle(brush = brush, radius = radius, center = haloCenter)
                        }
                    },
            )
        }

        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .padding(horizontal = 32.dp, vertical = 24.dp)
                .graphicsLayer {
                    alpha = entrance
                    scaleX = entrance
                    scaleY = entrance
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(
                painter = BrandIconPainter(),
                contentDescription = null,
                modifier = Modifier
                    .size(84.dp)
                    .testTag("startup-loading-logo"),
            )
            Spacer(Modifier.height(22.dp))
            Text(
                text = wordmark,
                style = MaterialTheme.typography.headlineSmall,
                color = colors.onBackground,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
            )
            Spacer(Modifier.height(18.dp))
            StartupLoadingIndicator(
                label = loadingLabel,
                modifier = Modifier
                    .widthIn(max = 188.dp)
                    .fillMaxWidth()
                    .height(5.dp),
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun StartupLoadingIndicator(
    label: String,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val transition = rememberInfiniteTransition(label = "startup-loading")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "startup-sweep",
    )
    val trackColor = colors.secondary.copy(alpha = 0.18f)
    val sweepColors = listOf(colors.secondary, colors.tertiary)

    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .testTag("startup-loading-indicator")
            .semantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
            },
    ) {
        val radius = size.height / 2f
        val corner = CornerRadius(radius, radius)
        drawRoundRect(
            color = trackColor,
            topLeft = Offset.Zero,
            size = size,
            cornerRadius = corner,
        )
        val segmentFraction = 0.34f + 0.12f * sin(progress * PI).toFloat()
        val segmentWidth = (size.width * segmentFraction).coerceIn(size.height * 2f, size.width)
        val travel = (size.width - segmentWidth).coerceAtLeast(0f)
        val startX = travel * progress
        drawRoundRect(
            brush = Brush.horizontalGradient(
                colors = sweepColors,
                startX = startX,
                endX = startX + segmentWidth,
            ),
            topLeft = Offset(startX, 0f),
            size = Size(segmentWidth, size.height),
            cornerRadius = corner,
        )
    }
}
