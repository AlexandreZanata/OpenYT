package com.openyt.data

import com.openyt.core.application.LibraryHealthAnalyzer
import com.openyt.core.application.MediaHealthInspector
import com.openyt.core.application.MediaProbeHealth
import com.openyt.core.application.MediaProbeOutcomeKind
import com.openyt.core.domain.MediaRevision
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.nio.file.AccessDeniedException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class JvmMediaHealthInspector : MediaHealthInspector {

    override suspend fun inspect(
        locator: String,
        expectedRevision: MediaRevision?,
        computeHashIfUncertain: Boolean,
    ): MediaProbeHealth = withContext(Dispatchers.IO) {
        if (locator.isBlank()) {
            return@withContext MediaProbeHealth(
                outcome = MediaProbeOutcomeKind.MISSING,
                safeDiagnostic = "EMPTY_LOCATOR",
            )
        }
        try {
            val file = File(locator)
            val parent = file.parentFile
            if (!file.exists()) {
                if (parent != null && parent.exists() && (!parent.canRead() || !parent.canExecute())) {
                    return@withContext MediaProbeHealth(
                        outcome = MediaProbeOutcomeKind.PERMISSION_DENIED,
                        safeDiagnostic = "PARENT_UNREADABLE",
                    )
                }
                return@withContext MediaProbeHealth(
                    outcome = MediaProbeOutcomeKind.MISSING,
                    safeDiagnostic = "NOT_FOUND",
                )
            }
            if (!file.isFile) {
                return@withContext MediaProbeHealth(
                    outcome = MediaProbeOutcomeKind.MISSING,
                    safeDiagnostic = "NOT_A_FILE",
                )
            }
            if (!file.canRead()) {
                return@withContext MediaProbeHealth(
                    outcome = MediaProbeOutcomeKind.PERMISSION_DENIED,
                    safeDiagnostic = "UNREADABLE",
                )
            }

            val sizeBytes = file.length()
            val modifiedMillis = file.lastModified()
            val shouldHash = expectedRevision?.contentSha256 != null && (
                computeHashIfUncertain ||
                    sizeBytes != expectedRevision.sizeBytes ||
                    (expectedRevision.lastModifiedEpochMillis > 0L &&
                        modifiedMillis != expectedRevision.lastModifiedEpochMillis)
                )

            val computedSha = if (shouldHash) {
                streamSha256(file)
            } else {
                // Verify actual read access on at least the first byte even without hashing.
                verifyReadable(file)
                null
            }

            MediaProbeHealth(
                outcome = MediaProbeOutcomeKind.AVAILABLE,
                sizeBytes = sizeBytes,
                lastModifiedEpochMillis = modifiedMillis,
                contentSha256 = computedSha,
            )
        } catch (_: SecurityException) {
            MediaProbeHealth(
                outcome = MediaProbeOutcomeKind.PERMISSION_DENIED,
                safeDiagnostic = "SECURITY_EXCEPTION",
            )
        } catch (_: AccessDeniedException) {
            MediaProbeHealth(
                outcome = MediaProbeOutcomeKind.PERMISSION_DENIED,
                safeDiagnostic = "ACCESS_DENIED",
            )
        } catch (_: FileNotFoundException) {
            val file = File(locator)
            val outcome = if (file.exists() && !file.canRead()) {
                MediaProbeOutcomeKind.PERMISSION_DENIED
            } else {
                MediaProbeOutcomeKind.MISSING
            }
            MediaProbeHealth(
                outcome = outcome,
                safeDiagnostic = if (outcome == MediaProbeOutcomeKind.PERMISSION_DENIED) "UNREADABLE" else "NOT_FOUND",
            )
        } catch (error: Throwable) {
            MediaProbeHealth(
                outcome = MediaProbeOutcomeKind.IO_ERROR,
                safeDiagnostic = LibraryHealthAnalyzer.sanitizeHealthDiagnostic(error::class.simpleName),
            )
        }
    }

    private fun verifyReadable(file: File) {
        FileInputStream(file).use { input ->
            input.read()
        }
    }

    private fun streamSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        FileInputStream(file).use { input ->
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
