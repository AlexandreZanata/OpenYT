package com.openyt.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.openyt.core.application.CandidateScanProgress
import com.openyt.core.application.RecoveryCandidateScanPolicy
import com.openyt.core.application.RecoveryCandidateSourcePort
import com.openyt.core.application.RecoveryFolderScanOutput
import com.openyt.core.application.ScannedRecoveryCandidate
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Android recovery scanner supporting both explicit scoped SAF trees/documents
 * (`content://`) and app-owned filesystem directories without full-storage permissions.
 */
class AndroidRecoveryCandidateScanner(
    context: Context,
    private val fileScanner: JvmRecoveryCandidateScanner = JvmRecoveryCandidateScanner(),
) : RecoveryCandidateSourcePort {

    private val appContext = context.applicationContext

    override suspend fun scanExplicitFolders(
        folderLocators: List<String>,
        policy: RecoveryCandidateScanPolicy,
        onProgress: suspend (CandidateScanProgress) -> Unit,
    ): RecoveryFolderScanOutput = withContext(Dispatchers.IO) {
        val contentFolders = folderLocators.filter { it.startsWith("content://", ignoreCase = true) }
        val localFolders = folderLocators.filterNot { it.startsWith("content://", ignoreCase = true) }

        val localResult = if (localFolders.isNotEmpty()) {
            fileScanner.scanExplicitFolders(localFolders, policy, onProgress)
        } else {
            RecoveryFolderScanOutput(emptyList())
        }

        if (contentFolders.isEmpty()) return@withContext localResult

        val candidates = localResult.candidates.toMutableList()
        var truncated = localResult.truncatedByLimit
        var skippedUnsafe = localResult.skippedUnsafeLinks
        val resolver = appContext.contentResolver
        val visitedDocIds = HashSet<String>()

        suspend fun walkTree(treeUri: Uri, docId: String, depth: Int) {
            currentCoroutineContext().ensureActive()
            if (candidates.size >= policy.maxFiles) {
                truncated = true
                return
            }
            if (!visitedDocIds.add("${treeUri.authority}|$docId")) return
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )
            runCatching {
                resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                    val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val sizeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                    val modIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    while (cursor.moveToNext()) {
                        currentCoroutineContext().ensureActive()
                        if (candidates.size >= policy.maxFiles) {
                            truncated = true
                            break
                        }
                        val childId = if (idIdx >= 0) cursor.getString(idIdx) else continue
                        val childName = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty() else ""
                        val mime = if (mimeIdx >= 0) cursor.getString(mimeIdx).orEmpty() else ""
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            if (depth < policy.maxDepth) {
                                walkTree(treeUri, childId, depth + 1)
                            }
                        } else {
                            val kind = JvmRecoveryCandidateScanner.inferMediaKind(childName) ?: inferFromMime(mime)
                            if (kind != null) {
                                val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                                val size = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else 0L
                                val mod = if (modIdx >= 0 && !cursor.isNull(modIdx)) cursor.getLong(modIdx) else 0L
                                candidates += ScannedRecoveryCandidate(
                                    locator = docUri.toString(),
                                    displayName = childName.ifBlank { docUri.lastPathSegment ?: "media" },
                                    sizeBytes = size,
                                    lastModifiedEpochMillis = mod,
                                    mediaKind = kind,
                                )
                                onProgress(
                                    CandidateScanProgress(
                                        filesScanned = candidates.size,
                                        hashesComputed = 0,
                                        currentFileName = childName,
                                    ),
                                )
                            }
                        }
                    }
                }
            }.onFailure {
                skippedUnsafe++
            }
        }

        for (rawUri in contentFolders) {
            if (candidates.size >= policy.maxFiles) {
                truncated = true
                break
            }
            val uri = runCatching { Uri.parse(rawUri) }.getOrNull() ?: continue
            val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: continue
            walkTree(uri, rootDocId, 0)
        }

        RecoveryFolderScanOutput(
            candidates = candidates,
            truncatedByLimit = truncated,
            skippedUnsafeLinks = skippedUnsafe,
        )
    }

    override suspend fun inspectExplicitFiles(fileLocators: List<String>): List<ScannedRecoveryCandidate> =
        withContext(Dispatchers.IO) {
            val contentFiles = fileLocators.filter { it.startsWith("content://", ignoreCase = true) }
            val localFiles = fileLocators.filterNot { it.startsWith("content://", ignoreCase = true) }
            val out = fileScanner.inspectExplicitFiles(localFiles).toMutableList()
            val resolver = appContext.contentResolver
            for (raw in contentFiles) {
                val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: continue
                runCatching {
                    resolver.query(
                        uri,
                        arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                        null,
                        null,
                        null,
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                            val name = if (nameIdx >= 0 && !cursor.isNull(nameIdx)) cursor.getString(nameIdx) else "media"
                            val size = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else 0L
                            val mime = resolver.getType(uri).orEmpty()
                            out += ScannedRecoveryCandidate(
                                locator = raw,
                                displayName = name,
                                sizeBytes = size,
                                lastModifiedEpochMillis = 0L,
                                mediaKind = JvmRecoveryCandidateScanner.inferMediaKind(name) ?: inferFromMime(mime),
                            )
                        }
                    }
                }
            }
            out
        }

    override suspend fun computeFileSha256(locator: String): String? = withContext(Dispatchers.IO) {
        if (!locator.startsWith("content://", ignoreCase = true)) {
            return@withContext fileScanner.computeFileSha256(locator)
        }
        runCatching {
            val uri = Uri.parse(locator)
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(64 * 1024)
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buf)
                    if (read <= 0) break
                    md.update(buf, 0, read)
                }
            } ?: return@withContext null
            md.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }

    private fun inferFromMime(mime: String): com.openyt.core.domain.MediaKind? = when {
        mime.startsWith("video/", ignoreCase = true) -> com.openyt.core.domain.MediaKind.VIDEO
        mime.startsWith("audio/", ignoreCase = true) -> com.openyt.core.domain.MediaKind.AUDIO
        else -> null
    }
}
