package com.openyt.ui.components

import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.openyt.ui.resources.Res
import com.openyt.ui.resources.allDrawableResources
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

@Composable
fun ProviderIcon(source: String?, modifier: Modifier = Modifier) {
    val icon = providerIcon(source)
    if (icon != null) {
        Icon(
            painter = painterResource(icon),
            contentDescription = source,
            modifier = modifier,
        )
    } else {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(4.dp))
                .semantics(mergeDescendants = true) { contentDescription = source ?: "" },
            contentAlignment = Alignment.Center,
        ) {
            Text(source.orEmpty().filter { it.isLetterOrDigit() }.take(2).uppercase().ifBlank { "?" }, style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun providerIcon(source: String?): DrawableResource? {
    val key = source?.lowercase()?.substringBefore(':') ?: return null
    val canonical = when {
        key.startsWith("youtube") -> "youtube"
        key.contains("twitter") -> "x"
        key.startsWith("instagram") -> "instagram"
        key.startsWith("tiktok") -> "tiktok"
        key.startsWith("facebook") -> "facebook"
        key.startsWith("soundcloud") -> "soundcloud"
        key.startsWith("twitch") -> "twitch"
        key.startsWith("vk") -> "source_vk"
        else -> key
    }
    val resources = Res.allDrawableResources
    return resources[canonical] ?: SourceIconAliases[key]?.let(resources::get)
        ?: resources["source_$canonical"]
}
