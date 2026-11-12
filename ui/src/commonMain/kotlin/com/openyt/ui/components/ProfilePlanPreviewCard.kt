package com.openyt.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openyt.core.domain.ActionableAlternative
import com.openyt.core.domain.DownloadProfile
import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.ProfileResolution
import com.openyt.core.domain.ProfileWarning
import com.openyt.core.domain.ResolvedDownloadPlan
import com.openyt.ui.i18n.FeatureText
import com.openyt.ui.i18n.LocalStrings
import com.openyt.ui.util.formatBytes

@Composable
fun ProfilePlanPreviewCard(
    resolution: ProfileResolution,
    downloadsDirectory: String,
    onDownloadPlan: (ResolvedDownloadPlan) -> Unit,
    onSelectProfile: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current

    when (resolution) {
        is ProfileResolution.Success -> {
            val plan = resolution.plan
            Card(
                modifier = modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            val profileTitle = when (plan.profileId) {
                                DownloadProfile.BUILTIN_STUDY -> strings.features[FeatureText.PROFILE_STUDY]
                                DownloadProfile.BUILTIN_AUDIO -> strings.features[FeatureText.PROFILE_AUDIO]
                                DownloadProfile.BUILTIN_SAVE_SPACE -> strings.features[FeatureText.PROFILE_SAVE_SPACE]
                                DownloadProfile.BUILTIN_TV -> strings.features[FeatureText.PROFILE_TV]
                                else -> plan.profileName
                            }
                            Text(
                                text = profileTitle,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.semantics { heading() },
                            )
                            val targetDesc = when (plan.kind) {
                                MediaKind.VIDEO -> "${plan.effectiveHeight ?: "auto"}p · ${plan.container.uppercase()}"
                                MediaKind.AUDIO -> "${strings.features[FeatureText.KIND_AUDIO]} · ${plan.container.uppercase()}"
                                MediaKind.SUBTITLE -> "Sub · ${plan.container.uppercase()}"
                            }
                            Text(
                                text = targetDesc,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Text(
                                text = plan.container.uppercase(),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }

                    // Plan details (subtitles, estimated size, destination)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        plan.selectedSubtitle?.let { sub ->
                            val embedNotice = if (sub.embed) " (${strings.features[FeatureText.LABEL_EMBEDDED]})" else ""
                            Text(
                                text = "${strings.features[FeatureText.LABEL_SUBTITLE]}: ${sub.language.uppercase()}$embedNotice",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        plan.estimatedSizeBytes?.let { size ->
                            Text(
                                text = "${strings.features[FeatureText.PROFILE_ESTIMATED_SIZE]}: ${formatBytes(size)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        Text(
                            text = "${strings.features[FeatureText.PROFILE_DESTINATION]}: $downloadsDirectory",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // Warnings
                    if (plan.warnings.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            plan.warnings.forEach { warning ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.tertiary,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    val warningText = when (warning) {
                                        is ProfileWarning.MissingSubtitle -> strings.features[FeatureText.PROFILE_WARNING_SUBTITLE]
                                        is ProfileWarning.MissingThumbnail -> strings.features[FeatureText.PROFILE_WARNING_THUMBNAIL]
                                        is ProfileWarning.FallbackResolution -> strings.features[FeatureText.PROFILE_WARNING_FALLBACK]
                                        is ProfileWarning.EstimatedSize -> strings.features[FeatureText.PROFILE_WARNING_SIZE]
                                        is ProfileWarning.UnknownCodec -> warning.detail
                                    }
                                    Text(
                                        text = warningText,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.tertiary,
                                    )
                                }
                            }
                        }
                    }

                    // Explicit Download Action
                    Button(
                        onClick = { onDownloadPlan(plan) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentDescription = "${strings.features[FeatureText.DOWNLOAD_WITH_PROFILE]}: ${plan.profileName}"
                            },
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = strings.features[FeatureText.DOWNLOAD_WITH_PROFILE],
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        }

        is ProfileResolution.Incompatible -> {
            Card(
                modifier = modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            text = strings.features[FeatureText.PROFILE_INCOMPATIBLE],
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.semantics { heading() },
                        )
                    }

                    Text(
                        text = resolution.explanation,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )

                    // Actionable alternatives - NOT dead buttons!
                    resolution.actionableAlternative?.let { alternative ->
                        val (actionText, targetProfileId) = when (alternative) {
                            ActionableAlternative.SWITCH_TO_AUDIO ->
                                strings.features[FeatureText.ACTION_SWITCH_TO_AUDIO] to DownloadProfile.BUILTIN_AUDIO
                            ActionableAlternative.USE_SAVE_SPACE ->
                                strings.features[FeatureText.ACTION_USE_SAVE_SPACE] to DownloadProfile.BUILTIN_SAVE_SPACE
                            ActionableAlternative.MANUAL_SELECTION ->
                                strings.features[FeatureText.ACTION_MANUAL_SELECTION] to null
                        }

                        Button(
                            onClick = { onSelectProfile(targetProfileId) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics {
                                    contentDescription = actionText
                                },
                        ) {
                            Text(actionText)
                        }
                    } ?: run {
                        // Fallback manual action
                        OutlinedButton(
                            onClick = { onSelectProfile(null) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics {
                                    contentDescription = strings.features[FeatureText.ACTION_MANUAL_SELECTION]
                                },
                        ) {
                            Text(strings.features[FeatureText.ACTION_MANUAL_SELECTION])
                        }
                    }
                }
            }
        }
    }
}
