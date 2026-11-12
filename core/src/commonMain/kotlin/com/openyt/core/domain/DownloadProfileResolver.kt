package com.openyt.core.domain

class DownloadProfileResolver {

    fun resolve(profile: DownloadProfile, mediaInfo: MediaInfo): ProfileResolution {
        if (mediaInfo.formats.isEmpty()) {
            return ProfileResolution.Incompatible(
                profile = profile,
                reason = IncompatibilityReason.NO_FORMATS_AVAILABLE,
                explanation = "No formats available in media metadata",
                actionableAlternative = ActionableAlternative.MANUAL_SELECTION,
            )
        }

        return when (profile.kind) {
            MediaKind.AUDIO -> resolveAudioProfile(profile, mediaInfo)
            MediaKind.VIDEO -> resolveVideoProfile(profile, mediaInfo)
            MediaKind.SUBTITLE -> ProfileResolution.Incompatible(
                profile = profile,
                reason = IncompatibilityReason.CONFLICTING_REQUIREMENTS,
                explanation = "Direct subtitle profiles are not supported as primary download targets",
            )
        }
    }

    private fun resolveAudioProfile(profile: DownloadProfile, mediaInfo: MediaInfo): ProfileResolution {
        val audioCandidates = mediaInfo.formats.filter { isAudioFormat(it) }
        val candidate = if (audioCandidates.isNotEmpty()) {
            audioCandidates.minWithOrNull(audioComparator(profile))
        } else {
            mediaInfo.formats.filter { hasAudioStream(it) }.minWithOrNull(audioComparator(profile))
        }

        if (candidate == null) {
            return ProfileResolution.Incompatible(
                profile = profile,
                reason = IncompatibilityReason.CONFLICTING_REQUIREMENTS,
                explanation = "Source does not contain any usable audio stream",
            )
        }

        val warnings = mutableListOf<ProfileWarning>()
        if (profile.estimatedSizeNotice) {
            warnings += ProfileWarning.EstimatedSize("Final size is an estimate; audio bitrate may vary")
        }

        val plan = ResolvedDownloadPlan(
            profileId = profile.id,
            profileName = profile.name,
            kind = MediaKind.AUDIO,
            selectedVideoFormat = null,
            selectedAudioFormat = candidate,
            container = profile.audioPreset.extension,
            effectiveHeight = null,
            selectedSubtitle = null,
            options = DownloadOptions(audioPreset = profile.audioPreset),
            estimatedSizeBytes = candidate.sizeBytes,
            warnings = warnings,
        )
        return ProfileResolution.Success(plan)
    }

    private fun resolveVideoProfile(profile: DownloadProfile, mediaInfo: MediaInfo): ProfileResolution {
        val videoFormats = mediaInfo.formats.filter { it.kind == MediaKind.VIDEO }
        if (videoFormats.isEmpty()) {
            return ProfileResolution.Incompatible(
                profile = profile,
                reason = IncompatibilityReason.AUDIO_ONLY_SOURCE,
                explanation = "Source contains only audio formats",
                actionableAlternative = ActionableAlternative.SWITCH_TO_AUDIO,
            )
        }

        // Strict TV compatibility check
        if (profile.tvCompatibilityStrict) {
            val tvCandidate = findTvCompatibleVideoFormat(videoFormats, mediaInfo.formats)
            if (tvCandidate == null) {
                return ProfileResolution.Incompatible(
                    profile = profile,
                    reason = IncompatibilityReason.NO_COMPATIBLE_TV_FORMAT,
                    explanation = "Source does not provide a native MP4 format with AVC (H.264) and AAC for TV compatibility",
                    actionableAlternative = ActionableAlternative.MANUAL_SELECTION,
                )
            }
        }

        val selectedVideo = selectBestVideoFormat(videoFormats, profile)
        val selectedAudio = if (hasAudioStream(selectedVideo)) {
            null
        } else {
            selectCompanionAudioFormat(mediaInfo.formats, profile)
        }

        val warnings = mutableListOf<ProfileWarning>()

        if (selectedVideo.videoCodec == null || selectedVideo.videoCodec.equals("unknown", ignoreCase = true)) {
            warnings += ProfileWarning.UnknownCodec("Video codec is unspecified in source metadata; using container fallback")
        }

        if (selectedVideo.height != null && profile.maxHeight != null && selectedVideo.height > profile.maxHeight) {
            warnings += ProfileWarning.FallbackResolution("No format found at or below ${profile.maxHeight}p; selected ${selectedVideo.height}p as fallback")
        }

        var selectedSubtitle: SubtitleSelection? = null
        if (profile.subtitlePreference is ProfileSubtitlePreference.Preferred) {
            val tag = profile.subtitlePreference.languagePrimaryTag
            val allowAuto = profile.subtitlePreference.allowAutomatic
            val match = findBestSubtitleTrack(mediaInfo.subtitles, tag, allowAuto)
            if (match != null) {
                selectedSubtitle = SubtitleSelection(
                    language = match.language,
                    automatic = match.automatic,
                    embed = true,
                )
            } else {
                warnings += ProfileWarning.MissingSubtitle("No matching subtitle track found for language '$tag'")
            }
        }

        if (profile.id == DownloadProfile.BUILTIN_STUDY) {
            if (mediaInfo.thumbnailUrl.isNullOrBlank()) {
                warnings += ProfileWarning.MissingThumbnail("Source metadata does not provide a thumbnail URL")
            }
        }

        if (profile.estimatedSizeNotice) {
            warnings += ProfileWarning.EstimatedSize("Final size is an estimate; lower resolution is not a guaranteed byte limit")
        }

        val container = if (profile.preferMp4Avc) "mp4" else selectedVideo.container
        val totalSize = calculateEstimatedSize(selectedVideo, selectedAudio)

        val plan = ResolvedDownloadPlan(
            profileId = profile.id,
            profileName = profile.name,
            kind = MediaKind.VIDEO,
            selectedVideoFormat = selectedVideo,
            selectedAudioFormat = selectedAudio,
            container = container,
            effectiveHeight = selectedVideo.height,
            selectedSubtitle = selectedSubtitle,
            options = DownloadOptions(subtitle = selectedSubtitle),
            estimatedSizeBytes = totalSize,
            warnings = warnings,
        )
        return ProfileResolution.Success(plan)
    }

    private fun selectBestVideoFormat(videoFormats: List<MediaFormat>, profile: DownloadProfile): MediaFormat {
        val maxHeight = profile.maxHeight
        val inCeiling = if (maxHeight != null) videoFormats.filter { it.height != null && it.height <= maxHeight } else videoFormats
        val unknownHeight = videoFormats.filter { it.height == null }
        val aboveCeiling = if (maxHeight != null) videoFormats.filter { it.height != null && it.height > maxHeight } else emptyList()

        val comparator = videoComparator(profile)
        return when {
            inCeiling.isNotEmpty() -> inCeiling.minWith(comparator)
            unknownHeight.isNotEmpty() -> unknownHeight.minWith(comparator)
            aboveCeiling.isNotEmpty() -> aboveCeiling.minWith(comparator)
            else -> videoFormats.minWith(comparator)
        }
    }

    private fun selectCompanionAudioFormat(formats: List<MediaFormat>, profile: DownloadProfile): MediaFormat? {
        val audioCandidates = formats.filter { isAudioFormat(it) }
        return if (audioCandidates.isNotEmpty()) {
            audioCandidates.minWithOrNull(audioComparator(profile))
        } else {
            formats.filter { hasAudioStream(it) }.minWithOrNull(audioComparator(profile))
        }
    }

    private fun findTvCompatibleVideoFormat(videoFormats: List<MediaFormat>, allFormats: List<MediaFormat>): MediaFormat? {
        val tvVideos = videoFormats.filter { vf ->
            vf.container.equals("mp4", ignoreCase = true) && isAvcCodec(vf.videoCodec) &&
                (vf.height == null || vf.height <= 1080)
        }

        for (tvVideo in tvVideos) {
            if (isAacCodec(tvVideo.audioCodec)) {
                return tvVideo
            }
            val companionAac = allFormats.any { af ->
                (af.container.equals("mp4", ignoreCase = true) || af.container.equals("m4a", ignoreCase = true)) &&
                    isAacCodec(af.audioCodec)
            }
            if (companionAac) {
                return tvVideo
            }
        }
        return null
    }

    private fun findBestSubtitleTrack(subtitles: List<SubtitleTrack>, primaryTag: String, allowAuto: Boolean): SubtitleTrack? {
        val humanExact = subtitles.firstOrNull { !it.automatic && it.language.equals(primaryTag, ignoreCase = true) }
        if (humanExact != null) return humanExact

        val humanPrefix = subtitles.firstOrNull { !it.automatic && it.language.startsWith(primaryTag, ignoreCase = true) }
        if (humanPrefix != null) return humanPrefix

        if (allowAuto) {
            val autoExact = subtitles.firstOrNull { it.automatic && it.language.equals(primaryTag, ignoreCase = true) }
            if (autoExact != null) return autoExact

            return subtitles.firstOrNull { it.automatic && it.language.startsWith(primaryTag, ignoreCase = true) }
        }
        return null
    }

    private fun isAudioFormat(format: MediaFormat): Boolean {
        return format.kind == MediaKind.AUDIO || (format.videoCodec == null || format.videoCodec == "none")
    }

    private fun hasAudioStream(format: MediaFormat): Boolean {
        return format.audioCodec != null && format.audioCodec != "none"
    }

    private fun isAvcCodec(codec: String?): Boolean {
        if (codec == null) return false
        val lower = codec.lowercase()
        return lower.contains("avc") || lower.contains("h264") || lower.contains("h.264")
    }

    private fun isAacCodec(codec: String?): Boolean {
        if (codec == null) return false
        val lower = codec.lowercase()
        return lower.contains("aac") || lower.contains("mp4a")
    }

    private fun calculateEstimatedSize(video: MediaFormat, audio: MediaFormat?): Long? {
        val videoBytes = video.sizeBytes ?: 0L
        val audioBytes = audio?.sizeBytes ?: 0L
        val total = videoBytes + audioBytes
        return if (total > 0L) total else null
    }

    private fun videoComparator(profile: DownloadProfile): Comparator<MediaFormat> = Comparator { a, b ->
        if (profile.preferMp4Avc) {
            val aIsMp4Avc = a.container.equals("mp4", ignoreCase = true) && isAvcCodec(a.videoCodec)
            val bIsMp4Avc = b.container.equals("mp4", ignoreCase = true) && isAvcCodec(b.videoCodec)
            if (aIsMp4Avc != bIsMp4Avc) return@Comparator if (aIsMp4Avc) -1 else 1

            val aIsMp4 = a.container.equals("mp4", ignoreCase = true)
            val bIsMp4 = b.container.equals("mp4", ignoreCase = true)
            if (aIsMp4 != bIsMp4) return@Comparator if (aIsMp4) -1 else 1
        }

        val aHeight = a.height ?: 0
        val bHeight = b.height ?: 0
        if (aHeight != bHeight) return@Comparator bHeight.compareTo(aHeight)

        val aBitrate = a.bitrateKbps ?: 0.0
        val bBitrate = b.bitrateKbps ?: 0.0
        if (aBitrate != bBitrate) return@Comparator bBitrate.compareTo(aBitrate)

        val aSize = a.sizeBytes ?: 0L
        val bSize = b.sizeBytes ?: 0L
        if (aSize != bSize) return@Comparator bSize.compareTo(aSize)

        a.id.compareTo(b.id)
    }

    private fun audioComparator(profile: DownloadProfile): Comparator<MediaFormat> = Comparator { a, b ->
        val prefCodec = profile.audioPreset.codec
        val prefExt = profile.audioPreset.extension

        val aMatches = a.container.equals(prefExt, ignoreCase = true) || a.audioCodec?.contains(prefCodec, ignoreCase = true) == true
        val bMatches = b.container.equals(prefExt, ignoreCase = true) || b.audioCodec?.contains(prefCodec, ignoreCase = true) == true
        if (aMatches != bMatches) return@Comparator if (aMatches) -1 else 1

        val aBitrate = a.bitrateKbps ?: 0.0
        val bBitrate = b.bitrateKbps ?: 0.0
        if (aBitrate != bBitrate) return@Comparator bBitrate.compareTo(aBitrate)

        val aSize = a.sizeBytes ?: 0L
        val bSize = b.sizeBytes ?: 0L
        if (aSize != bSize) return@Comparator bSize.compareTo(aSize)

        a.id.compareTo(b.id)
    }
}
