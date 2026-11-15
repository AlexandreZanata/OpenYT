package com.openyt.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.openyt.core.application.PortableBackupFailure
import com.openyt.core.application.PortableSourceMetadata
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Android [PortableStreamBridge] supporting both app-owned files and non-seekable
 * `content://` SAF document streams without loading whole archives into RAM.
 */
class AndroidPortableStreamBridge(
    private val context: Context,
    private val fileFallback: FilePortableStreamBridge = FilePortableStreamBridge(),
) : PortableStreamBridge {

    override fun openInputStream(locator: String): InputStream {
        if (!locator.startsWith("content://")) {
            return fileFallback.openInputStream(locator)
        }
        val uri = Uri.parse(locator)
        return try {
            context.contentResolver.openInputStream(uri)
                ?: throw PortableBackupFailure.SourceMissing(listOf("SAF document")).toException()
        } catch (sec: SecurityException) {
            throw PortableBackupFailure.PermissionDenied("SAF document permission revoked").toException()
        }
    }

    override fun openWriteTarget(
        locator: String,
        operationId: String,
        stagingRoot: File,
    ): PortableStreamBridge.OutputTarget {
        if (!locator.startsWith("content://")) {
            return fileFallback.openWriteTarget(locator, operationId, stagingRoot)
        }
        val uri = Uri.parse(locator)
        val rawStream: OutputStream = try {
            context.contentResolver.openOutputStream(uri, "w")
                ?: throw PortableBackupFailure.IoError("Unable to open destination SAF document").toException()
        } catch (sec: SecurityException) {
            throw PortableBackupFailure.PermissionDenied("SAF write permission revoked").toException()
        }
        return object : PortableStreamBridge.OutputTarget {
            override val outputStream: OutputStream = rawStream

            override fun commit() {
                runCatching { rawStream.flush() }
                runCatching { rawStream.close() }
            }

            override fun abort() {
                runCatching { rawStream.close() }
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
            }
        }
    }

    override fun inspectSource(locator: String): PortableSourceMetadata {
        if (!locator.startsWith("content://")) {
            return fileFallback.inspectSource(locator)
        }
        val uri = Uri.parse(locator)
        return try {
            var sizeBytes: Long? = null
            var displayName: String = uri.lastPathSegment ?: "document"
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0 && !cursor.isNull(nameIdx)) displayName = cursor.getString(nameIdx)
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) sizeBytes = cursor.getLong(sizeIdx)
                }
            }
            PortableSourceMetadata.Readable(
                sizeBytes = sizeBytes ?: -1L,
                lastModifiedEpochMillis = 0L,
                displayName = displayName,
            )
        } catch (sec: SecurityException) {
            PortableSourceMetadata.PermissionDenied("SAF permission denied")
        } catch (error: Throwable) {
            PortableSourceMetadata.Missing
        }
    }

    override fun availableSpaceFor(locator: String, fallbackDir: File): Long =
        if (!locator.startsWith("content://")) {
            fileFallback.availableSpaceFor(locator, fallbackDir)
        } else {
            fallbackDir.apply { mkdirs() }.usableSpace.coerceAtLeast(0L)
        }
}
