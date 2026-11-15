package com.openyt.data

import com.openyt.core.application.CandidateScanProgress
import com.openyt.core.application.RecoveryCandidateScanPolicy
import com.openyt.core.application.RecoveryCandidateSourcePort
import com.openyt.core.application.RecoveryFolderScanOutput
import com.openyt.core.application.ScannedRecoveryCandidate
import com.openyt.core.domain.MediaKind
import java.io.File
import java.io.FileInputStream
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Bounded, opt-in local filesystem scanner for assisted library recovery.
 * Enforces:
 * - Only explicitly passed directories are scanned.
 * - `NOFOLLOW_LINKS` (via empty FileVisitOption set) + explicit symlink skip.
 * - Canonical directory cycle guard (`visitedDirs`).
 * - Strict `maxDepth` and `maxFiles` bounds.
 */
class JvmRecoveryCandidateScanner : RecoveryCandidateSourcePort {

    override suspend fun scanExplicitFolders(
        folderLocators: List<String>,
        policy: RecoveryCandidateScanPolicy,
        onProgress: suspend (CandidateScanProgress) -> Unit,
    ): RecoveryFolderScanOutput = withContext(Dispatchers.IO) {
        val candidates = mutableListOf<ScannedRecoveryCandidate>()
        var truncated = false
        var skippedUnsafe = 0
        val visitedCanonicalDirs = HashSet<String>()
        val callerContext = currentCoroutineContext()

        for (rawFolder in folderLocators) {
            callerContext.ensureActive()
            if (candidates.size >= policy.maxFiles) {
                truncated = true
                break
            }
            if (rawFolder.isBlank()) continue
            val rootFile = File(rawFolder)
            if (!rootFile.exists() || !rootFile.isDirectory) continue
            val rootPath = rootFile.toPath()
            if (Files.isSymbolicLink(rootPath)) {
                skippedUnsafe++
                continue
            }
            val canonicalRoot = runCatching { rootPath.toRealPath() }.getOrNull() ?: continue
            val canonicalRootStr = canonicalRoot.toString()

            Files.walkFileTree(
                canonicalRoot,
                emptySet<FileVisitOption>(),
                policy.maxDepth,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        callerContext.ensureActive()
                        if (Files.isSymbolicLink(dir)) {
                            skippedUnsafe++
                            return FileVisitResult.SKIP_SUBTREE
                        }
                        val real = runCatching { dir.toRealPath() }.getOrNull() ?: return FileVisitResult.SKIP_SUBTREE
                        if (!real.startsWith(canonicalRoot)) {
                            skippedUnsafe++
                            return FileVisitResult.SKIP_SUBTREE
                        }
                        if (!visitedCanonicalDirs.add(real.toString())) {
                            return FileVisitResult.SKIP_SUBTREE
                        }
                        return if (candidates.size >= policy.maxFiles) {
                            truncated = true
                            FileVisitResult.TERMINATE
                        } else {
                            FileVisitResult.CONTINUE
                        }
                    }

                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        callerContext.ensureActive()
                        if (Files.isSymbolicLink(file) || !attrs.isRegularFile) {
                            if (Files.isSymbolicLink(file)) skippedUnsafe++
                            return FileVisitResult.CONTINUE
                        }
                        val real = runCatching { file.toRealPath() }.getOrNull() ?: return FileVisitResult.CONTINUE
                        if (!real.startsWith(canonicalRoot)) {
                            skippedUnsafe++
                            return FileVisitResult.CONTINUE
                        }
                        val f = real.toFile()
                        if (!f.canRead()) return FileVisitResult.CONTINUE
                        val kind = inferMediaKind(f.name) ?: return FileVisitResult.CONTINUE
                        candidates += ScannedRecoveryCandidate(
                            locator = f.absolutePath,
                            displayName = f.name,
                            sizeBytes = f.length(),
                            lastModifiedEpochMillis = f.lastModified(),
                            mediaKind = kind,
                        )
                        runBlocking {
                            onProgress(
                                CandidateScanProgress(
                                    filesScanned = candidates.size,
                                    hashesComputed = 0,
                                    currentFileName = f.name,
                                ),
                            )
                        }
                        return if (candidates.size >= policy.maxFiles) {
                            truncated = true
                            FileVisitResult.TERMINATE
                        } else {
                            FileVisitResult.CONTINUE
                        }
                    }

                    override fun visitFileFailed(file: Path, exc: java.io.IOException?): FileVisitResult {
                        return FileVisitResult.CONTINUE
                    }
                },
            )
            if (candidates.size >= policy.maxFiles && truncated) break
            // Preserve canonicalRootStr check used for isolation
            check(canonicalRootStr.isNotEmpty())
        }

        RecoveryFolderScanOutput(
            candidates = candidates,
            truncatedByLimit = truncated,
            skippedUnsafeLinks = skippedUnsafe,
        )
    }

    override suspend fun inspectExplicitFiles(fileLocators: List<String>): List<ScannedRecoveryCandidate> =
        withContext(Dispatchers.IO) {
            fileLocators.mapNotNull { locator ->
                val f = File(locator)
                if (!f.exists() || !f.isFile || !f.canRead()) {
                    null
                } else {
                    ScannedRecoveryCandidate(
                        locator = f.absolutePath,
                        displayName = f.name,
                        sizeBytes = f.length(),
                        lastModifiedEpochMillis = f.lastModified(),
                        mediaKind = inferMediaKind(f.name),
                    )
                }
            }
        }

    override suspend fun computeFileSha256(locator: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val f = File(locator)
            if (!f.isFile || !f.canRead()) return@withContext null
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(64 * 1024)
            FileInputStream(f).use { input ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buf)
                    if (read <= 0) break
                    md.update(buf, 0, read)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }

    companion object {
        private val VIDEO_EXTS = setOf("mp4", "mkv", "webm", "mov", "avi", "m4v", "flv", "ts")
        private val AUDIO_EXTS = setOf("mp3", "m4a", "aac", "opus", "ogg", "wav", "flac", "weba")

        fun inferMediaKind(fileName: String): MediaKind? {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            return when {
                ext in VIDEO_EXTS -> MediaKind.VIDEO
                ext in AUDIO_EXTS -> MediaKind.AUDIO
                else -> null
            }
        }
    }
}
