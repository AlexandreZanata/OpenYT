package com.openyt.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import com.openyt.ui.theme.Ink
import com.openyt.ui.theme.Paper
import com.openyt.ui.theme.TealDark
import com.openyt.ui.theme.TealLight

@Composable
fun BrandIconPainter(): Painter = rememberVectorPainter(BrandIcon)

// The paths and gradient coordinates match docs/branding/openyt-app-icon.svg.
private val BrandIcon = ImageVector.Builder(
    name = "OpenYT",
    defaultWidth = 128.dp,
    defaultHeight = 128.dp,
    viewportWidth = 128f,
    viewportHeight = 128f,
).addPath(
    pathData = PathParser().parsePathString(
        "M32 2 H96 A30 30 0 0 1 126 32 V96 A30 30 0 0 1 96 126 " +
            "H32 A30 30 0 0 1 2 96 V32 A30 30 0 0 1 32 2 Z",
    ).toNodes(),
    fill = Brush.linearGradient(
        colors = listOf(Color(0xFF252E2C), Ink),
        start = Offset(12f, 2f),
        end = Offset(116f, 126f),
    ),
    stroke = SolidColor(Color(0xFF354441)),
    strokeLineWidth = 1.5f,
).addPath(
    pathData = PathParser().parsePathString("M45 84 A35 35 0 1 1 83 84").toNodes(),
    stroke = Brush.linearGradient(
        0f to TealDark,
        0.55f to Color(0xFF1EB8A8),
        1f to TealLight,
        start = Offset(29f, 86f),
        end = Offset(96f, 23f),
    ),
    strokeLineWidth = 12f,
    strokeLineCap = StrokeCap.Round,
).addPath(
    pathData = PathParser().parsePathString(
        "M55 44 Q55 41 58 43 L76 53 Q79.5 55 76 57 L58 67 Q55 69 55 66 Z",
    ).toNodes(),
    fill = SolidColor(Paper),
).addPath(
    pathData = PathParser().parsePathString(
        "M60 75 Q60 73 62 73 H66 Q68 73 68 75 V91 H75 Q79 91 76 94 " +
            "L66 105 Q64 107 62 105 L52 94 Q49 91 53 91 H60 Z",
    ).toNodes(),
    fill = SolidColor(TealLight),
).addPath(
    pathData = PathParser().parsePathString("M37 99 V103 Q37 114 48 114 H80 Q91 114 91 103 V99").toNodes(),
    stroke = SolidColor(Color(0xFF479E92)),
    strokeLineWidth = 6f,
    strokeLineCap = StrokeCap.Round,
).build()
