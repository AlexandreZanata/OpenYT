package com.openyt.data

import com.openyt.core.application.BulkApplyResult
import com.openyt.core.application.BulkChange
import com.openyt.core.application.BulkFailure
import com.openyt.core.application.BulkFileOperator
import com.openyt.core.application.BulkPlan
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Two-phase move: stage every source under a temporary name, then move staged
 * files to their targets. Any failure rolls all changes back. Nothing is
 * overwritten or deleted.
 */
class FileBulkFileOperator : BulkFileOperator {
    override suspend fun apply(plan: BulkPlan): BulkApplyResult = withContext(Dispatchers.IO) {
        if (!plan.canApply) {
            return@withContext BulkApplyResult(
                failures = listOf(BulkFailure("", "", "plan has conflicts")),
            )
        }

        val staged = mutableListOf<Staged>()
        val applied = mutableListOf<BulkChange>()
        try {
            plan.changes.forEach { change ->
                val source = Path.of(change.fromPath)
                if (!Files.isRegularFile(source)) {
                    error("missing source: ${change.fromPath}")
                }
                val temporary = source.resolveSibling(
                    ".openyt-bulk-${UUID.randomUUID()}-${source.fileName}",
                )
                move(source, temporary)
                staged += Staged(change, source, temporary)
            }
            staged.forEach { item ->
                val target = Path.of(item.change.toPath)
                target.parent?.let { Files.createDirectories(it) }
                if (Files.exists(target)) {
                    error("target exists: ${item.change.toPath}")
                }
                move(item.temporary, target)
                applied += item.change
            }
            BulkApplyResult(applied = applied)
        } catch (e: Exception) {
            applied.forEach { change ->
                runCatching { move(Path.of(change.toPath), Path.of(change.fromPath)) }
            }
            staged.forEach { item ->
                if (Files.exists(item.temporary)) {
                    runCatching { move(item.temporary, item.source) }
                }
            }
            BulkApplyResult(
                failures = listOf(BulkFailure("", "", e.message ?: "bulk operation failed")),
                rolledBack = true,
            )
        }
    }

    private fun move(from: Path, to: Path) {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(from, to)
        }
    }

    private data class Staged(
        val change: BulkChange,
        val source: Path,
        val temporary: Path,
    )
}
