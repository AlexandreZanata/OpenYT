package com.openyt.core.domain

import kotlinx.serialization.Serializable

@Serializable
sealed interface ProfileSubtitlePreference {
    @Serializable
    data object None : ProfileSubtitlePreference

    @Serializable
    data class Preferred(
        val languagePrimaryTag: String,
        val allowAutomatic: Boolean = false,
    ) : ProfileSubtitlePreference
}

@Serializable
data class DownloadProfile(
    val id: String,
    val name: String,
    val isBuiltIn: Boolean = false,
    val kind: MediaKind = MediaKind.VIDEO,
    val maxHeight: Int? = null,
    val preferMp4Avc: Boolean = true,
    val audioPreset: AudioPreset = AudioPreset.M4A,
    val subtitlePreference: ProfileSubtitlePreference = ProfileSubtitlePreference.None,
    val tvCompatibilityStrict: Boolean = false,
    val estimatedSizeNotice: Boolean = false,
) {
    companion object {
        const val BUILTIN_STUDY = "builtin-study"
        const val BUILTIN_AUDIO = "builtin-audio"
        const val BUILTIN_SAVE_SPACE = "builtin-save-space"
        const val BUILTIN_TV = "builtin-tv"

        fun study(primaryLanguage: String = "pt"): DownloadProfile = DownloadProfile(
            id = BUILTIN_STUDY,
            name = "Study offline",
            isBuiltIn = true,
            kind = MediaKind.VIDEO,
            maxHeight = 1080,
            preferMp4Avc = true,
            subtitlePreference = ProfileSubtitlePreference.Preferred(
                languagePrimaryTag = primaryLanguage,
                allowAutomatic = false,
            ),
            tvCompatibilityStrict = false,
            estimatedSizeNotice = false,
        )

        val AudioOnly: DownloadProfile = DownloadProfile(
            id = BUILTIN_AUDIO,
            name = "Audio only",
            isBuiltIn = true,
            kind = MediaKind.AUDIO,
            maxHeight = null,
            preferMp4Avc = false,
            audioPreset = AudioPreset.M4A,
            subtitlePreference = ProfileSubtitlePreference.None,
            tvCompatibilityStrict = false,
            estimatedSizeNotice = false,
        )

        val SaveSpace: DownloadProfile = DownloadProfile(
            id = BUILTIN_SAVE_SPACE,
            name = "Save space",
            isBuiltIn = true,
            kind = MediaKind.VIDEO,
            maxHeight = 480,
            preferMp4Avc = true,
            subtitlePreference = ProfileSubtitlePreference.None,
            tvCompatibilityStrict = false,
            estimatedSizeNotice = true,
        )

        val WatchOnTv: DownloadProfile = DownloadProfile(
            id = BUILTIN_TV,
            name = "Watch on TV",
            isBuiltIn = true,
            kind = MediaKind.VIDEO,
            maxHeight = 1080,
            preferMp4Avc = true,
            subtitlePreference = ProfileSubtitlePreference.None,
            tvCompatibilityStrict = true,
            estimatedSizeNotice = false,
        )

        val BuiltIns: List<DownloadProfile> = listOf(
            study(),
            AudioOnly,
            SaveSpace,
            WatchOnTv,
        )
    }
}

sealed interface ProfileWarning {
    data class MissingSubtitle(val detail: String) : ProfileWarning
    data class MissingThumbnail(val detail: String) : ProfileWarning
    data class EstimatedSize(val detail: String) : ProfileWarning
    data class UnknownCodec(val detail: String) : ProfileWarning
    data class FallbackResolution(val detail: String) : ProfileWarning
}

enum class IncompatibilityReason {
    AUDIO_ONLY_SOURCE,
    NO_COMPATIBLE_TV_FORMAT,
    NO_FORMATS_AVAILABLE,
    CONFLICTING_REQUIREMENTS,
}

enum class ActionableAlternative {
    SWITCH_TO_AUDIO,
    MANUAL_SELECTION,
    USE_SAVE_SPACE,
}

data class ResolvedDownloadPlan(
    val profileId: String,
    val profileName: String,
    val kind: MediaKind,
    val selectedVideoFormat: MediaFormat?,
    val selectedAudioFormat: MediaFormat?,
    val container: String,
    val effectiveHeight: Int?,
    val selectedSubtitle: SubtitleSelection?,
    val options: DownloadOptions,
    val estimatedSizeBytes: Long?,
    val warnings: List<ProfileWarning> = emptyList(),
)

sealed interface ProfileResolution {
    data class Success(val plan: ResolvedDownloadPlan) : ProfileResolution

    data class Incompatible(
        val profile: DownloadProfile,
        val reason: IncompatibilityReason,
        val explanation: String,
        val actionableAlternative: ActionableAlternative? = null,
    ) : ProfileResolution
}

@Serializable
data class ProfileProvenance(
    val profileId: String,
    val profileName: String,
    val isBuiltIn: Boolean = false,
    val resolvedContainer: String? = null,
    val effectiveHeight: Int? = null,
    val selectedFormatId: String? = null,
    val sourceWebpageUrl: String? = null,
)

fun ResolvedDownloadPlan.toDownloadRequest(
    url: String,
    destinationDirectory: String,
    exportTargetUri: String? = null,
    limitBytesPerSecond: Long? = null,
    cookiesFilePath: String? = null,
    playlistItemIndex: Int? = null,
): DownloadRequest {
    require(!destinationDirectory.startsWith("content://")) {
        "SAF content URI cannot be used as destinationDirectory: $destinationDirectory"
    }
    val formatId = when {
        selectedVideoFormat != null && selectedAudioFormat != null ->
            "${selectedVideoFormat.id}+${selectedAudioFormat.id}"
        selectedVideoFormat != null -> selectedVideoFormat.id
        selectedAudioFormat != null -> selectedAudioFormat.id
        else -> null
    }
    val provenance = ProfileProvenance(
        profileId = profileId,
        profileName = profileName,
        isBuiltIn = DownloadProfile.BuiltIns.any { it.id == profileId },
        resolvedContainer = container,
        effectiveHeight = effectiveHeight,
        selectedFormatId = formatId,
        sourceWebpageUrl = url,
    )
    val snapshotOptions = options.copy(
        subtitle = selectedSubtitle ?: options.subtitle,
        exportTargetUri = exportTargetUri,
        profileProvenance = provenance,
    )
    return DownloadRequest(
        url = url,
        kind = kind,
        formatId = formatId,
        destinationDirectory = destinationDirectory,
        limitBytesPerSecond = limitBytesPerSecond,
        cookiesFilePath = cookiesFilePath,
        playlistItemIndex = playlistItemIndex,
        options = snapshotOptions,
    )
}

sealed interface PlanRevalidationResult {
    data class Valid(val request: DownloadRequest) : PlanRevalidationResult

    data class RequiresApproval(
        val previousProvenance: ProfileProvenance?,
        val proposedPlan: ResolvedDownloadPlan,
        val reason: String,
    ) : PlanRevalidationResult

    data class Incompatible(
        val resolution: ProfileResolution.Incompatible,
    ) : PlanRevalidationResult
}

fun revalidatePlan(
    task: DownloadTask,
    freshMediaInfo: MediaInfo,
    profile: DownloadProfile? = null,
): PlanRevalidationResult {
    val previousProvenance = task.request.options.profileProvenance
    val previousFormatId = task.request.formatId

    val constituentIds = previousFormatId?.split("+") ?: emptyList()
    val formatStillExists = constituentIds.isNotEmpty() && constituentIds.all { id ->
        freshMediaInfo.formats.any { it.id == id }
    }

    if (formatStillExists && previousProvenance != null) {
        return PlanRevalidationResult.Valid(task.request)
    }

    val effectiveProfile = profile
        ?: previousProvenance?.let { prov ->
            DownloadProfile.BuiltIns.find { it.id == prov.profileId }
                ?: DownloadProfile(
                    id = prov.profileId,
                    name = prov.profileName,
                    isBuiltIn = prov.isBuiltIn,
                    kind = task.request.kind,
                    maxHeight = prov.effectiveHeight,
                )
        }
        ?: DownloadProfile.study()

    return when (val resolution = DownloadProfileResolver.resolve(effectiveProfile, freshMediaInfo)) {
        is ProfileResolution.Success -> {
            PlanRevalidationResult.RequiresApproval(
                previousProvenance = previousProvenance,
                proposedPlan = resolution.plan,
                reason = "Source format expired or changed from '$previousFormatId'",
            )
        }
        is ProfileResolution.Incompatible -> {
            PlanRevalidationResult.Incompatible(resolution)
        }
    }
}
