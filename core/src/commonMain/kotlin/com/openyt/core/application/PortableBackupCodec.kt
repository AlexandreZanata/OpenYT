package com.openyt.core.application

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object PortableArchivePathRules {
    private val SHA256_REGEX = Regex("^[0-9a-f]{64}$")
    private val WINDOWS_DRIVE_REGEX = Regex("^[A-Za-z]:.*$")
    private val SENSITIVE_QUERY_PARAM_NAMES = setOf(
        "token", "access_token", "auth", "cookie", "cookies",
        "signature", "sig", "key", "secret", "password", "passwd", "session", "sid",
    )

    fun isValidSha256(value: String): Boolean = SHA256_REGEX.matches(value)

    fun validateEntryPath(rawPath: String): Result<String> {
        if (rawPath.isBlank()) {
            return Result.failure(PortableBackupFailure.SecurityViolation("Archive entry path cannot be blank").toException())
        }
        if (rawPath.any { it == '\u0000' || it.code < 0x20 || it == '\\' }) {
            return Result.failure(PortableBackupFailure.SecurityViolation("Archive entry path contains forbidden characters").toException())
        }
        if (rawPath.startsWith("/") || rawPath.startsWith("//") || WINDOWS_DRIVE_REGEX.matches(rawPath)) {
            return Result.failure(PortableBackupFailure.SecurityViolation("Archive entry path must be relative: $rawPath").toException())
        }
        val segments = rawPath.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) {
            return Result.failure(PortableBackupFailure.SecurityViolation("Archive entry path contains traversal or empty segments: $rawPath").toException())
        }
        if (rawPath != PortableBackupFormat.MANIFEST_ENTRY_NAME &&
            !rawPath.startsWith("media/") &&
            !rawPath.startsWith("assets/")
        ) {
            return Result.failure(PortableBackupFailure.SecurityViolation("Unexpected archive path prefix: $rawPath").toException())
        }
        return Result.success(rawPath)
    }

    fun sanitizeFileName(rawName: String, fallback: String = "media.bin"): String {
        val base = rawName.substringAfterLast('/').substringAfterLast('\\').trim()
        val cleaned = buildString(base.length) {
            base.forEach { ch ->
                when {
                    ch == '\u0000' || ch.code < 0x20 -> append('_')
                    ch in charArrayOf('\\', '/', ':', '*', '?', '"', '<', '>', '|') -> append('_')
                    else -> append(ch)
                }
            }
        }.trim('.', ' ')
            .take(140)
        return when {
            cleaned.isBlank() || cleaned == "." || cleaned == ".." -> fallback
            WINDOWS_DRIVE_REGEX.matches(cleaned) -> "_$cleaned"
            else -> cleaned
        }
    }

    fun buildBlobArchivePath(folder: String, slotId: String, originalFileName: String): String {
        val safeFolder = if (folder == "assets") "assets" else "media"
        val safeSlot = sanitizeFileName(slotId, fallback = "item")
        val safeFile = sanitizeFileName(originalFileName, fallback = "blob.bin")
        return "$safeFolder/$safeSlot/$safeFile"
    }

    /**
     * Strips user credentials and sensitive token/cookie query parameters from a URL.
     * Rejects non-http(s) URIs such as `file://`, `content://`, or local filesystem paths.
     */
    fun sanitizeExternalUrlOrNull(rawUrl: String?): String? {
        val trimmed = rawUrl?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val validated = SafeUrlSanitizer.validateArtworkUrl(trimmed).getOrNull() ?: return null
        val fragmentIndex = validated.indexOf('#')
        val withoutFragment = if (fragmentIndex >= 0) validated.substring(0, fragmentIndex) else validated
        val queryIndex = withoutFragment.indexOf('?')
        if (queryIndex < 0) return withoutFragment
        val base = withoutFragment.substring(0, queryIndex)
        val rawQuery = withoutFragment.substring(queryIndex + 1)
        val safeParams = rawQuery.split('&')
            .filter { it.isNotBlank() }
            .filter { pair ->
                val name = pair.substringBefore('=').lowercase()
                name !in SENSITIVE_QUERY_PARAM_NAMES
            }
        return if (safeParams.isEmpty()) base else "$base?${safeParams.joinToString("&")}"
    }

    fun containsSensitiveSecretOrMachinePath(value: String): Boolean {
        if (value.isBlank()) return false
        val trimmed = value.trim()
        if (SafeUrlSanitizer.hasCredentials(trimmed)) return true
        val lower = trimmed.lowercase()
        if (lower.startsWith("content://") || lower.startsWith("file://")) return true
        if (WINDOWS_DRIVE_REGEX.matches(trimmed) || trimmed.startsWith("\\\\")) return true
        if (lower.contains("cookie=") || lower.contains("set-cookie:") || lower.contains("authorization:")) return true
        return false
    }
}

object PortableBackupCodec {
    private val compactJson = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val prettyJson = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun encode(manifest: PortableBackupManifest, pretty: Boolean = false): String {
        val json = if (pretty) prettyJson else compactJson
        return json.encodeToString(manifest)
    }

    fun encodeJournalPlan(plan: PortableOperationJournalRecord): String =
        compactJson.encodeToString(plan)

    fun decode(rawBytes: ByteArray): Result<PortableBackupManifest> = runCatching {
        if (rawBytes.size.toLong() > PortableBackupFormat.MAX_MANIFEST_BYTES) {
            throw PortableBackupFailure.SizeLimitExceeded(
                limitName = "manifest.json",
                actual = rawBytes.size.toLong(),
                limit = PortableBackupFormat.MAX_MANIFEST_BYTES,
            ).toException()
        }
        decode(rawBytes.decodeToString()).getOrThrow()
    }

    fun decode(rawJson: String): Result<PortableBackupManifest> = runCatching {
        if (rawJson.encodeToByteArray().size.toLong() > PortableBackupFormat.MAX_MANIFEST_BYTES) {
            throw PortableBackupFailure.SizeLimitExceeded(
                limitName = "manifest.json",
                actual = rawJson.encodeToByteArray().size.toLong(),
                limit = PortableBackupFormat.MAX_MANIFEST_BYTES,
            ).toException()
        }
        val manifest = try {
            compactJson.decodeFromString<PortableBackupManifest>(rawJson)
        } catch (error: Exception) {
            throw PortableBackupFailure.InvalidManifest("Malformed manifest JSON: ${error.message}").toException()
        }
        validateManifest(manifest).getOrThrow()
        manifest
    }

    fun validateManifest(manifest: PortableBackupManifest): Result<Unit> = runCatching {
        if (manifest.formatVersion < 1) {
            throw PortableBackupFailure.InvalidManifest("Invalid formatVersion: ${manifest.formatVersion}").toException()
        }
        if (manifest.minReaderVersion > PortableBackupFormat.FORMAT_VERSION) {
            throw PortableBackupFailure.UnsupportedVersion(
                foundVersion = manifest.formatVersion,
                minReaderVersion = manifest.minReaderVersion,
            ).toException()
        }
        if (manifest.bundleId.isBlank()) {
            throw PortableBackupFailure.InvalidManifest("bundleId cannot be blank").toException()
        }
        if (manifest.privacyNotice != PortableBackupFormat.PLAINTEXT_PRIVACY_MARKER) {
            throw PortableBackupFailure.InvalidManifest("Invalid or missing privacyNotice").toException()
        }

        val entryIds = mutableSetOf<String>()
        val normalizedBlobPaths = mutableSetOf<String>()
        val caseFoldBlobPaths = mutableSetOf<String>()
        var totalEntriesCount = 1L // manifest.json

        manifest.entries.forEach { entry ->
            if (entry.exportId.isBlank() || !entryIds.add(entry.exportId)) {
                throw PortableBackupFailure.InvalidManifest("Duplicate or blank entry exportId: ${entry.exportId}").toException()
            }
            if (entry.title.isBlank()) {
                throw PortableBackupFailure.InvalidManifest("Entry ${entry.exportId} has blank title").toException()
            }
            entry.thumbnailUrl?.let { url ->
                if (PortableArchivePathRules.containsSensitiveSecretOrMachinePath(url) ||
                    SafeUrlSanitizer.validateArtworkUrl(url).isFailure
                ) {
                    throw PortableBackupFailure.SecurityViolation("Entry ${entry.exportId} contains disallowed URL or credentials").toException()
                }
            }
            entry.primaryMedia?.let { blob ->
                validateBlob(blob, normalizedBlobPaths, caseFoldBlobPaths)
                totalEntriesCount++
            }
            val assetIds = mutableSetOf<String>()
            entry.offlineAssets.forEach { asset ->
                if (asset.exportId.isBlank() || !assetIds.add(asset.exportId)) {
                    throw PortableBackupFailure.InvalidManifest("Duplicate or blank offline asset exportId").toException()
                }
                asset.blob?.let { blob ->
                    validateBlob(blob, normalizedBlobPaths, caseFoldBlobPaths)
                    totalEntriesCount++
                }
            }
            entry.study?.let { study ->
                val noteIds = mutableSetOf<String>()
                study.notes.forEach { note ->
                    if (note.exportId.isBlank() || !noteIds.add(note.exportId)) {
                        throw PortableBackupFailure.InvalidManifest("Duplicate or blank study note exportId").toException()
                    }
                    if (note.timestampMillis < 0L || (note.endMillis != null && note.endMillis < note.timestampMillis)) {
                        throw PortableBackupFailure.InvalidManifest("Invalid study note timestamps").toException()
                    }
                }
                val bookmarkIds = mutableSetOf<String>()
                study.bookmarks.forEach { bm ->
                    if (bm.exportId.isBlank() || !bookmarkIds.add(bm.exportId)) {
                        throw PortableBackupFailure.InvalidManifest("Duplicate or blank bookmark exportId").toException()
                    }
                    if (bm.timestampMillis < 0L) {
                        throw PortableBackupFailure.InvalidManifest("Negative bookmark timestamp").toException()
                    }
                }
                val rangeIds = mutableSetOf<String>()
                study.ranges.forEach { range ->
                    if (range.exportId.isBlank() || !rangeIds.add(range.exportId)) {
                        throw PortableBackupFailure.InvalidManifest("Duplicate or blank range exportId").toException()
                    }
                    if (range.startMillis < 0L || range.endMillis <= range.startMillis) {
                        throw PortableBackupFailure.InvalidManifest("Invalid study range bounds").toException()
                    }
                }
            }
        }

        if (totalEntriesCount > PortableBackupFormat.MAX_ARCHIVE_ENTRIES) {
            throw PortableBackupFailure.SizeLimitExceeded(
                limitName = "archiveEntryCount",
                actual = totalEntriesCount,
                limit = PortableBackupFormat.MAX_ARCHIVE_ENTRIES.toLong(),
            ).toException()
        }

        val collectionIds = mutableSetOf<String>()
        manifest.collections.forEach { collection ->
            if (collection.exportId.isBlank() || !collectionIds.add(collection.exportId)) {
                throw PortableBackupFailure.InvalidManifest("Duplicate or blank collection exportId").toException()
            }
            collection.entryExportIds.forEach { refId ->
                if (refId !in entryIds) {
                    throw PortableBackupFailure.InvalidManifest(
                        "Collection ${collection.exportId} references unknown entry $refId"
                    ).toException()
                }
            }
        }

        val smartIds = mutableSetOf<String>()
        manifest.smartCollections.forEach { smart ->
            if (smart.exportId.isBlank() || !smartIds.add(smart.exportId)) {
                throw PortableBackupFailure.InvalidManifest("Duplicate or blank smartCollection exportId").toException()
            }
        }

        val profileIds = mutableSetOf<String>()
        manifest.downloadProfiles.forEach { rec ->
            if (rec.exportId.isBlank() || !profileIds.add(rec.exportId)) {
                throw PortableBackupFailure.InvalidManifest("Duplicate or blank profile exportId").toException()
            }
            if (rec.profile.id.isBlank() || rec.profile.name.isBlank()) {
                throw PortableBackupFailure.InvalidManifest("Profile ${rec.exportId} has blank id/name").toException()
            }
        }
    }

    private fun validateBlob(
        blob: PortableBlobRef,
        normalizedPaths: MutableSet<String>,
        caseFoldPaths: MutableSet<String>,
    ) {
        val cleanPath = PortableArchivePathRules.validateEntryPath(blob.archivePath).getOrThrow()
        if (cleanPath == PortableBackupFormat.MANIFEST_ENTRY_NAME) {
            throw PortableBackupFailure.SecurityViolation("Blob cannot overwrite manifest.json").toException()
        }
        if (!normalizedPaths.add(cleanPath)) {
            throw PortableBackupFailure.SecurityViolation("Duplicate blob archive path: $cleanPath").toException()
        }
        if (!caseFoldPaths.add(cleanPath.lowercase())) {
            throw PortableBackupFailure.SecurityViolation("Case-insensitive collision in blob archive path: $cleanPath").toException()
        }
        if (blob.byteCount < 0L) {
            throw PortableBackupFailure.InvalidManifest("Negative byteCount for $cleanPath").toException()
        }
        if (!PortableArchivePathRules.isValidSha256(blob.sha256)) {
            throw PortableBackupFailure.InvalidManifest("Invalid sha256 format for $cleanPath").toException()
        }
    }
}
