package com.openyt.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.openyt.ui.resources.Res
import com.openyt.ui.resources.bandcamp
import com.openyt.ui.resources.bilibili
import com.openyt.ui.resources.dailymotion
import com.openyt.ui.resources.facebook
import com.openyt.ui.resources.instagram
import com.openyt.ui.resources.pinterest
import com.openyt.ui.resources.reddit
import com.openyt.ui.resources.rumble
import com.openyt.ui.resources.soundcloud
import com.openyt.ui.resources.telegram
import com.openyt.ui.resources.threads
import com.openyt.ui.resources.tiktok
import com.openyt.ui.resources.twitch
import com.openyt.ui.resources.vimeo
import com.openyt.ui.resources.x
import com.openyt.ui.resources.youtube
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
        Icon(
            imageVector = Icons.Default.Public,
            contentDescription = source,
            modifier = modifier,
        )
    }
}

private fun providerIcon(source: String?): DrawableResource? {
    val name = source?.lowercase() ?: return null
    return when {
        name.contains("youtube") -> Res.drawable.youtube
        name.contains("instagram") -> Res.drawable.instagram
        name.contains("tiktok") -> Res.drawable.tiktok
        name == "x" || name.contains("twitter") -> Res.drawable.x
        name.contains("facebook") -> Res.drawable.facebook
        name.contains("soundcloud") -> Res.drawable.soundcloud
        name.contains("vimeo") -> Res.drawable.vimeo
        name.contains("twitch") -> Res.drawable.twitch
        name.contains("reddit") -> Res.drawable.reddit
        name.contains("pinterest") -> Res.drawable.pinterest
        name.contains("dailymotion") -> Res.drawable.dailymotion
        name.contains("bandcamp") -> Res.drawable.bandcamp
        name.contains("threads") -> Res.drawable.threads
        name.contains("telegram") -> Res.drawable.telegram
        name.contains("bilibili") -> Res.drawable.bilibili
        name.contains("rumble") -> Res.drawable.rumble
        else -> null
    }
}
