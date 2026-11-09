package com.openyt.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.openyt.ui.i18n.LocalStrings

@Composable
fun BrandMark(
    modifier: Modifier = Modifier,
    vertical: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val appName = LocalStrings.current.appName
    val wordmark = buildAnnotatedString {
        append(appName.removeSuffix("YT"))
        if (appName.endsWith("YT")) {
            withStyle(SpanStyle(color = colors.secondary)) {
                append("YT")
            }
        }
    }

    if (vertical) {
        Column(
            modifier = modifier.padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(BrandIconPainter(), contentDescription = null, modifier = Modifier.size(40.dp))
            Spacer(Modifier.size(6.dp))
            Text(
                wordmark,
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurface,
                fontWeight = FontWeight.Bold,
            )
        }
    } else {
        Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
            Image(BrandIconPainter(), contentDescription = null, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                wordmark,
                style = MaterialTheme.typography.titleMedium,
                color = colors.onSurface,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
