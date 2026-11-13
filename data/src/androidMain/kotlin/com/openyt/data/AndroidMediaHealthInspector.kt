package com.openyt.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.openyt.core.application.LibraryHealthAnalyzer
import com.openyt.core.application.MediaHealthInspector
import com.openyt.core.application.MediaProbeHealth
import com.openyt.core.application.MediaProbeOutcomeKind
import com.openyt.core.domain.MediaRevision
import java.io.FileNotFoundException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Inspects both owned filesystem paths and scoped `content://` URIs on Android,
 * cleanly separating revoked SAF grants from deleted documents without taking
 * unsolicited storage permissions.
 */
class AndroidMediaHealthInspector(
    context: Context,
    private val fileInspector: MediaHealthInspector = JvmMediaHealthInspector(),
) : MediaHealthInspector {

    private val appContext = context.applicationContext

    override suspend fun inspect(
        locator: String,
        expectedRevision: MediaRevision?,
        computeHashIfUncertain: Boolean,
    ): MediaProbeHealth = withContext(Dispatchers.IO) {
        if (!locator.startsWith("content://", ignoreCase = true)) {
            return@withContext fileInspector.inspect(locator, expectedRevision, computeHashIfUncertain)
        }
        val uri = runCatching { Uri.parse(locator) }.getOrNull()
            ?: return@withContext MediaProbeHealth(MediaProbeOutcomeKind.MISSING, safeDiagnostic = "INVALID_URI")
        val resolver = appContext.contentResolver
        try {
            val sizeBytes = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0 && !cursor.isNull(idx)) cursor.getLong(idx) else null
                } else {
                    null
                }
            }
            val shouldHash = expectedRevision?.contentSha256 != null &&
                (computeHashIfUncertain || (sizeBytes != null && sizeBytes != expectedRevision.sizeBytes))
            val computedSha = resolver.openInputStream(uri)?.use { input ->
                if (!shouldHash) {
                    input.read()
                    null
                } else {
                    val md = MessageDigest.getInstance("SHA-256")
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        md.update(buf, 0, read)
                    }
                    md.digest().joinToString("") { "%02x".format(it) }
                }
            } ?: return@withContext MediaProbeHealth(
                outcome = MediaProbeOutcomeKind.MISSING,
                safeDiagnostic = "STREAM_NULL",
            )
            MediaProbeHealth(
                outcome = MediaProbeOutcomeKind.AVAILABLE,
                sizeBytes = sizeBytes,
                contentSha256 = computedSha,
            )
        } catch (_: SecurityException) {
            MediaProbeHealth(
                outcome = MediaProbeOutcomeKind.PERMISSION_DENIED,
                safeDiagnostic = "SAF_PERMISSION_REVOKED",
            )
        } catch (_: FileNotFoundException) {
            val hasGrant = resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
            if (!hasGrant) {
                MediaProbeHealth(
                    outcome = MediaProbeOutcomeKind.PERMISSION_DENIED,
                    safeDiagnostic = "NO_PERSISTED_GRANT",
                )
            } else {
                MediaProbeHealth(
                    outcome = MediaProbeOutcomeKind.MISSING,
                    safeDiagnostic = "NOT_FOUND",
                )
            }
        } catch (error: Throwable) {
            MediaProbeHealth(
                outcome = MediaProbeOutcomeKind.IO_ERROR,
                safeDiagnostic = LibraryHealthAnalyzer.sanitizeHealthDiagnostic(error::class.simpleName),
            )
        }
    }
}
