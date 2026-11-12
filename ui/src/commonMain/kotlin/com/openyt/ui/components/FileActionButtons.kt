package com.openyt.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.openyt.core.application.MediaActions
import com.openyt.core.domain.ShareableMedia
import com.openyt.ui.i18n.FeatureText
import com.openyt.ui.i18n.LocalStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Both entry points report platform failures and keep blocking OS calls off the UI. */
@Composable
fun FileActionButtons(media: ShareableMedia, actions: MediaActions, enabled: Boolean = true) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    var busy by remember(media.path) { mutableStateOf(false) }
    var failure by remember(media.path) { mutableStateOf<FeatureText?>(null) }
    fun execute(error: FeatureText, action: () -> Boolean) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val succeeded = withContext(Dispatchers.Default) {
                    try { action() } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
                }
                if (!succeeded) failure = error
            } finally { busy = false }
        }
    }
    if (actions.canOpen) TextButton(enabled = enabled && !busy, onClick = {
        execute(FeatureText.OPEN_FILE_FAILED) { actions.open(media) }
    }) { Text(strings.openAction) }
    if (actions.canReveal) TextButton(enabled = enabled && !busy, onClick = {
        execute(FeatureText.REVEAL_FILE_FAILED) { actions.reveal(media) }
    }) { Text(strings.revealAction) }
    failure?.let { error ->
        AlertDialog(
            onDismissRequest = { failure = null },
            title = { Text(strings.appName) },
            text = { Text(strings.features[error]) },
            confirmButton = { TextButton(onClick = { failure = null }) { Text(strings.closeAction) } },
        )
    }
}
