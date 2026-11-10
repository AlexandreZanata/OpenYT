package com.openyt.core.application

import com.openyt.core.domain.LibraryEntry

data class BulkChange(
    val entryId: String,
    val fromPath: String,
    val toPath: String,
)

data class BulkPlan(
    val changes: List<BulkChange>,
    val conflicts: List<String> = emptyList(),
) {
    val canApply: Boolean
        get() = conflicts.isEmpty() && changes.isNotEmpty()
}

data class BulkFailure(
    val entryId: String,
    val path: String,
    val reason: String,
)

data class BulkApplyResult(
    val applied: List<BulkChange> = emptyList(),
    val failures: List<BulkFailure> = emptyList(),
    val rolledBack: Boolean = false,
) {
    val success: Boolean
        get() = failures.isEmpty() && applied.isNotEmpty()
}

fun interface BulkFileOperator {
    suspend fun apply(plan: BulkPlan): BulkApplyResult
}

object BulkFilePlanner {
    fun planRename(
        entries: List<LibraryEntry>,
        pattern: String,
        targetExists: (String) -> Boolean,
    ): BulkPlan {
        if (pattern.isBlank()) return BulkPlan(emptyList(), listOf("pattern is blank"))
        val sources = entries.map { it.filePath }.toSet()
        val targets = mutableSetOf<String>()
        val conflicts = mutableListOf<String>()
        val changes = mutableListOf<BulkChange>()

        entries.forEachIndexed { index, entry ->
            if (entry.filePath.isBlank()) {
                conflicts += "${entry.title}: empty path"
                return@forEachIndexed
            }
            val extension = entry.filePath.substringAfterLast('.', "")
            val rendered = render(pattern, entry, index + 1, extension)
            val fileName = OrganizationPlanner.sanitizeSegment(rendered).let { name ->
                if (extension.isNotBlank() && !name.endsWith(".$extension", ignoreCase = true)) {
                    "$name.$extension"
                } else {
                    name
                }
            }
            val parent = parentOf(entry.filePath)
            val target = if (parent.isBlank()) fileName else "$parent/$fileName"
            if (!targets.add(target)) {
                conflicts += "duplicate target: $target"
            } else if (target !in sources && targetExists(target)) {
                conflicts += "target exists: $target"
            }
            changes += BulkChange(entry.id, entry.filePath, target)
        }
        return BulkPlan(changes, conflicts)
    }

    fun planMove(
        entries: List<LibraryEntry>,
        destinationDirectory: String,
        targetExists: (String) -> Boolean,
    ): BulkPlan {
        val destination = destinationDirectory.trim().trimEnd('/', '\\')
        if (destination.isBlank()) return BulkPlan(emptyList(), listOf("destination is blank"))
        val sources = entries.map { it.filePath }.toSet()
        val targets = mutableSetOf<String>()
        val conflicts = mutableListOf<String>()
        val changes = mutableListOf<BulkChange>()

        entries.forEach { entry ->
            if (entry.filePath.isBlank()) {
                conflicts += "${entry.title}: empty path"
                return@forEach
            }
            val fileName = entry.filePath.substringAfterLast('/').substringAfterLast('\\')
            val target = "$destination/$fileName"
            if (!targets.add(target)) {
                conflicts += "duplicate target: $target"
            } else if (target !in sources && targetExists(target)) {
                conflicts += "target exists: $target"
            }
            changes += BulkChange(entry.id, entry.filePath, target)
        }
        return BulkPlan(changes, conflicts)
    }

    private fun render(
        pattern: String,
        entry: LibraryEntry,
        index: Int,
        extension: String,
    ): String = pattern
        .replace("{title}", entry.title)
        .replace("{author}", entry.author.orEmpty())
        .replace("{index}", index.toString())
        .replace("{id}", entry.id)
        .replace("{ext}", extension)

    private fun parentOf(path: String): String {
        val separator = maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\'))
        return if (separator <= 0) "" else path.substring(0, separator)
    }
}

/**
 * Applies a plan with a staged two-phase move. Failures roll every change back;
 * files are never deleted or overwritten.
 */
class BulkFileService(
    private val operator: BulkFileOperator,
    private val repository: DownloadRepository?,
) {
    suspend fun apply(plan: BulkPlan): BulkApplyResult {
        if (!plan.canApply) {
            return BulkApplyResult(
                failures = listOf(BulkFailure("", "", "plan has conflicts")),
            )
        }
        val result = operator.apply(plan)
        if (result.rolledBack) return result
        result.applied.forEach { change ->
            runCatching {
                repository?.updateLibraryPath(change.entryId, change.toPath)
            }
        }
        return result
    }
}
