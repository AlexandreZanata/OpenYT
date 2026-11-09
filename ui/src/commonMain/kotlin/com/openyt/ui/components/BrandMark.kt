package com.openyt.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openyt.ui.i18n.LocalStrings

@Composable
fun BrandMark(
    modifier: Modifier = Modifier,
    vertical: Boolean = false,
) {
    val strings = LocalStrings.current
    val iconSize = if (vertical) 40.dp else 32.dp
    val mark: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                .size(iconSize)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(iconSize * 0.6f),
            )
        }
    }

    if (vertical) {
        Column(
            modifier = modifier.padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            mark()
            Spacer(Modifier.size(6.dp))
            Text(
                strings.appName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    } else {
        Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
            mark()
            Spacer(Modifier.width(10.dp))
            Text(
                strings.appName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
