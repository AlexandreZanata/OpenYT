package com.openyt.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp
import com.openyt.core.application.ShareTextParser
import com.openyt.ui.i18n.FeatureText
import com.openyt.ui.i18n.LocalStrings
import kotlinx.coroutines.delay

/** Reads only while explicitly enabled and the host is in the foreground. */
@Composable
internal fun ClipboardSuggestion(enabled: Boolean, active: Boolean, onUse: (String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    val strings = LocalStrings.current
    var candidate by remember { mutableStateOf<String?>(null) }
    var lastSeen by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(enabled, active, clipboard) {
        if (!enabled || !active) { candidate = null; return@LaunchedEffect }
        while (true) {
            val url = ShareTextParser.firstUrl(runCatching { clipboard.getText()?.text }.getOrNull())
            if (url != lastSeen) { lastSeen = url; candidate = url }
            delay(2_000)
        }
    }
    candidate?.takeIf { enabled && active }?.let { url ->
        Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text(strings.features[FeatureText.CLIPBOARD_FOUND])
                Row {
                    TextButton(onClick = { candidate = null; onUse(url) }) { Text(strings.features[FeatureText.USE_LINK]) }
                    TextButton(onClick = { candidate = null }) { Text(strings.features[FeatureText.DISMISS]) }
                }
            }
        }
    }
}
