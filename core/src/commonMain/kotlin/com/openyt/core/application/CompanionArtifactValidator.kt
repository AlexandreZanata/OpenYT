package com.openyt.core.application

import com.openyt.core.domain.CompanionArtifactReceipt

sealed interface ArtifactValidationResult {
    data class Valid(val receipt: CompanionArtifactReceipt, val sizeBytes: Long) : ArtifactValidationResult
    data class OutOfRoot(val path: String, val allowedRoots: List<String>) : ArtifactValidationResult
    data class FileMissing(val path: String) : ArtifactValidationResult
    data class FileEmpty(val path: String) : ArtifactValidationResult
    data class InvalidPath(val path: String) : ArtifactValidationResult
}

object CompanionArtifactValidator {

    fun normalizePath(path: String): String {
        val trimmed = path.trim().removeSurrounding("\"").removeSurrounding("'").replace('\\', '/')
        val isUnc = trimmed.startsWith("//")
        val isAbsolutePosix = trimmed.startsWith("/") && !isUnc
        val cleaned = trimmed.split('/').filter { it.isNotEmpty() && it != "." }.joinToString("/")
        return when {
            isUnc -> "//$cleaned"
            isAbsolutePosix -> "/$cleaned"
            else -> cleaned
        }
    }

    /**
     * Checks whether [candidatePath] is safely contained inside [allowedRoot].
     * Prevents path traversal (e.g. "../" or prefix collisions like /tmp/root vs /tmp/root2).
     */
    fun isPathWithinRoot(candidatePath: String, allowedRoot: String): Boolean {
        val normCandidate = normalizePath(candidatePath)
        val normRoot = normalizePath(allowedRoot)

        if (normRoot.isEmpty() || normCandidate.isEmpty()) return false

        val isWindows = normCandidate.contains(':') || normRoot.contains(':')
        val equals = if (isWindows) normCandidate.equals(normRoot, ignoreCase = true) else normCandidate == normRoot
        if (equals) return true

        val rootPrefix = if (normRoot.endsWith("/")) normRoot else "$normRoot/"
        val isPrefix = if (isWindows) {
            normCandidate.startsWith(rootPrefix, ignoreCase = true)
        } else {
            normCandidate.startsWith(rootPrefix)
        }

        if (!isPrefix) return false

        val relativePart = normCandidate.substring(rootPrefix.length)
        val segments = relativePart.split('/')
        return !segments.contains("..")
    }

    /**
     * Validates an artifact receipt against expected task-owned roots and host file existence.
     */
    fun validateReceipt(
        receipt: CompanionArtifactReceipt,
        allowedRoots: List<String>,
        fileInspector: MediaFileInspector,
    ): ArtifactValidationResult {
        val cleanPath = receipt.filePath.trim().removeSurrounding("\"").removeSurrounding("'").trim()
        if (cleanPath.isBlank()) return ArtifactValidationResult.InvalidPath(receipt.filePath)

        val isContained = allowedRoots.any { root -> isPathWithinRoot(cleanPath, root) }
        if (!isContained) {
            return ArtifactValidationResult.OutOfRoot(cleanPath, allowedRoots)
        }

        if (!fileInspector.exists(cleanPath)) {
            return ArtifactValidationResult.FileMissing(cleanPath)
        }

        val metadata = fileInspector.inspect(cleanPath)
            ?: return ArtifactValidationResult.FileMissing(cleanPath)

        if (metadata.sizeBytes <= 0L) {
            return ArtifactValidationResult.FileEmpty(cleanPath)
        }

        return ArtifactValidationResult.Valid(receipt.copy(filePath = cleanPath), metadata.sizeBytes)
    }
}
