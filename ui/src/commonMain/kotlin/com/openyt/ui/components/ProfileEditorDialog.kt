package com.openyt.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.openyt.core.domain.AudioPreset
import com.openyt.core.domain.DownloadProfile
import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.ProfileSubtitlePreference
import com.openyt.ui.i18n.FeatureText
import com.openyt.ui.i18n.LocalStrings

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileEditorDialog(
    profile: DownloadProfile?,
    onSave: (DownloadProfile) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onDelete: ((String) -> Unit)? = null,
    onExport: ((DownloadProfile) -> Unit)? = null,
) {
    val strings = LocalStrings.current
    val isEditing = profile != null
    val isBuiltIn = profile?.isBuiltIn == true

    var name by remember { mutableStateOf(profile?.name.orEmpty()) }
    var kind by remember { mutableStateOf(profile?.kind ?: MediaKind.VIDEO) }
    var maxHeight by remember { mutableStateOf(profile?.maxHeight) }
    var audioPreset by remember { mutableStateOf(profile?.audioPreset ?: AudioPreset.M4A) }
    var tvStrict by remember { mutableStateOf(profile?.tvCompatibilityStrict ?: false) }

    val initialSubLang = (profile?.subtitlePreference as? ProfileSubtitlePreference.Preferred)?.languagePrimaryTag.orEmpty()
    val initialAllowAuto = (profile?.subtitlePreference as? ProfileSubtitlePreference.Preferred)?.allowAutomatic ?: false
    var subtitleLang by remember { mutableStateOf(initialSubLang) }
    var allowAutoSub by remember { mutableStateOf(initialAllowAuto) }

    val isValid = name.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (isEditing) strings.features[FeatureText.EDIT_PROFILE] else strings.features[FeatureText.ADD_PROFILE],
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
        },
        text = {
            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (isBuiltIn) {
                    Text(
                        text = strings.features[FeatureText.IMMUTABLE_PROFILE_HINT],
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(strings.features[FeatureText.PROFILE_NAME]) },
                    singleLine = true,
                    enabled = !isBuiltIn,
                    modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = strings.features[FeatureText.PROFILE_NAME]
                    },
                )

                // Media Kind
                Text(
                    text = strings.features[FeatureText.PROFILE_KIND],
                    style = MaterialTheme.typography.labelLarge,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = kind == MediaKind.VIDEO,
                        onClick = { kind = MediaKind.VIDEO },
                        label = { Text(strings.features[FeatureText.KIND_VIDEO]) },
                        enabled = !isBuiltIn,
                    )
                    FilterChip(
                        selected = kind == MediaKind.AUDIO,
                        onClick = { kind = MediaKind.AUDIO },
                        label = { Text(strings.features[FeatureText.KIND_AUDIO]) },
                        enabled = !isBuiltIn,
                    )
                }

                // If Video: Max Height
                if (kind == MediaKind.VIDEO) {
                    Text(
                        text = strings.features[FeatureText.PROFILE_MAX_HEIGHT],
                        style = MaterialTheme.typography.labelLarge,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val heights = listOf(1080, 720, 480, 360, null)
                        heights.forEach { h ->
                            val label = if (h != null) "${h}p" else strings.features[FeatureText.RES_UNLIMITED]
                            FilterChip(
                                selected = maxHeight == h,
                                onClick = { maxHeight = h },
                                label = { Text(label) },
                                enabled = !isBuiltIn,
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Checkbox(
                            checked = tvStrict,
                            onCheckedChange = { tvStrict = it },
                            enabled = !isBuiltIn,
                        )
                        Text(
                            text = strings.features[FeatureText.TV_STRICT_LABEL],
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }

                // If Audio: Preset
                if (kind == MediaKind.AUDIO) {
                    Text(
                        text = strings.features[FeatureText.PROFILE_AUDIO_PRESET],
                        style = MaterialTheme.typography.labelLarge,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AudioPreset.entries.forEach { preset ->
                            FilterChip(
                                selected = audioPreset == preset,
                                onClick = { audioPreset = preset },
                                label = { Text(preset.extension.uppercase()) },
                                enabled = !isBuiltIn,
                            )
                        }
                    }
                }

                // Subtitle settings
                OutlinedTextField(
                    value = subtitleLang,
                    onValueChange = { subtitleLang = it.trim().take(8) },
                    label = { Text(strings.features[FeatureText.PROFILE_SUBTITLE_LANG]) },
                    placeholder = { Text("Ex: pt, en") },
                    singleLine = true,
                    enabled = !isBuiltIn,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (subtitleLang.isNotBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Checkbox(
                            checked = allowAutoSub,
                            onCheckedChange = { allowAutoSub = it },
                            enabled = !isBuiltIn,
                        )
                        Text(
                            text = strings.features[FeatureText.PROFILE_ALLOW_AUTO_SUB],
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (!isBuiltIn) {
                Button(
                    onClick = {
                        val subPref = if (subtitleLang.isNotBlank()) {
                            ProfileSubtitlePreference.Preferred(
                                languagePrimaryTag = subtitleLang,
                                allowAutomatic = allowAutoSub,
                            )
                        } else {
                            ProfileSubtitlePreference.None
                        }
                        val result = DownloadProfile(
                            id = profile?.id ?: "custom-${System.currentTimeMillis()}",
                            name = name.trim(),
                            isBuiltIn = false,
                            kind = kind,
                            maxHeight = maxHeight,
                            preferMp4Avc = true,
                            audioPreset = audioPreset,
                            subtitlePreference = subPref,
                            tvCompatibilityStrict = tvStrict,
                            estimatedSizeNotice = maxHeight != null && maxHeight!! <= 480,
                        )
                        onSave(result)
                    },
                    enabled = isValid,
                ) {
                    Text(strings.features[FeatureText.SAVE_PROFILE])
                }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isEditing && !isBuiltIn && onDelete != null && profile != null) {
                    TextButton(
                        onClick = { onDelete(profile.id) },
                    ) {
                        Text(strings.features[FeatureText.DELETE_PROFILE], color = MaterialTheme.colorScheme.error)
                    }
                }
                if (isEditing && onExport != null && profile != null) {
                    TextButton(
                        onClick = { onExport(profile) },
                    ) {
                        Text(strings.features[FeatureText.EXPORT_PROFILE])
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(strings.closeAction)
                }
            }
        },
    )
}
