package com.openyt.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.openyt.core.domain.DownloadProfile
import com.openyt.ui.i18n.FeatureText
import com.openyt.ui.i18n.LocalStrings

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DownloadProfileSelector(
    profiles: List<DownloadProfile>,
    selectedProfileId: String?,
    onSelectProfile: (String?) -> Unit,
    modifier: Modifier = Modifier,
    onManageProfiles: (() -> Unit)? = null,
) {
    val strings = LocalStrings.current

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = strings.features[FeatureText.PROFILE_SELECTION],
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        if (onManageProfiles != null) {
            IconButton(
                onClick = onManageProfiles,
                modifier = Modifier.semantics {
                    contentDescription = strings.features[FeatureText.PROFILE_MANAGE]
                },
            ) {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = null,
                )
            }
        }
    }

    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        profiles.forEach { profile ->
            val isSelected = selectedProfileId == profile.id
            val label = profileDisplayName(profile, strings.features)
            FilterChip(
                selected = isSelected,
                onClick = { onSelectProfile(profile.id) },
                label = { Text(label) },
                modifier = Modifier.semantics {
                    contentDescription = "$label, ${if (isSelected) "selecionado" else "não selecionado"}"
                },
            )
        }

        val manualSelected = selectedProfileId == null
        val manualLabel = strings.features[FeatureText.PROFILE_MANUAL]
        FilterChip(
            selected = manualSelected,
            onClick = { onSelectProfile(null) },
            label = { Text(manualLabel) },
            modifier = Modifier.semantics {
                contentDescription = "$manualLabel, ${if (manualSelected) "selecionado" else "não selecionado"}"
            },
        )
    }
}

fun profileDisplayName(profile: DownloadProfile, features: com.openyt.ui.i18n.FeatureStrings): String = when (profile.id) {
    DownloadProfile.BUILTIN_STUDY -> features[FeatureText.PROFILE_STUDY]
    DownloadProfile.BUILTIN_AUDIO -> features[FeatureText.PROFILE_AUDIO]
    DownloadProfile.BUILTIN_SAVE_SPACE -> features[FeatureText.PROFILE_SAVE_SPACE]
    DownloadProfile.BUILTIN_TV -> features[FeatureText.PROFILE_TV]
    else -> profile.name
}
