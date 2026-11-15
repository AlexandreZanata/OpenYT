package com.openyt.core.application

/**
 * Platform file-picker contract for choosing destinations and sources of
 * portable backup ZIP bundles (.openyt-backup.zip).
 */
interface PortableArchivePickerPort {
    val canPick: Boolean

    fun pickExportLocation(
        suggestedFileName: String,
        onPicked: (String?) -> Unit,
    )

    fun pickRestoreArchive(
        onPicked: (String?) -> Unit,
    )
}

/**
 * UI-consumable facade over export, streaming validation planning, and
 * atomic journaled restore execution.
 */
interface PortableBackupUiController {
    val appVersion: String?
    val defaultDestinationRoot: () -> String

    suspend fun exportBackup(
        archiveLocator: String,
        options: PortableExportOptions,
        onProgress: suspend (PortableExportProgress) -> Unit,
    ): Result<PortableExportReceipt>

    suspend fun prepareRestorePlan(
        archiveLocator: String,
        destinationRoot: String,
        options: PortableRestoreOptions,
        onProgress: suspend (PortableRestoreProgress) -> Unit,
    ): Result<PortableRestorePlan>

    suspend fun discardPlan(plan: PortableRestorePlan)

    suspend fun executeRestore(
        plan: PortableRestorePlan,
        confirmedArchiveSha256: String,
        onProgress: suspend (PortableRestoreProgress) -> Unit,
    ): Result<PortableRestoreReceipt>
}

class DefaultPortableBackupUiController(
    private val exportService: PortableBackupExportService,
    private val restorePlanner: PortableRestorePlanner,
    private val restoreExecutor: PortableRestoreExecutor,
    private val ownedAssetsRoot: String,
    override val defaultDestinationRoot: () -> String,
    override val appVersion: String? = null,
) : PortableBackupUiController {

    override suspend fun exportBackup(
        archiveLocator: String,
        options: PortableExportOptions,
        onProgress: suspend (PortableExportProgress) -> Unit,
    ): Result<PortableExportReceipt> = exportService.exportBackup(
        archiveLocator = archiveLocator,
        options = options,
        appVersion = appVersion,
        onProgress = onProgress,
    )

    override suspend fun prepareRestorePlan(
        archiveLocator: String,
        destinationRoot: String,
        options: PortableRestoreOptions,
        onProgress: suspend (PortableRestoreProgress) -> Unit,
    ): Result<PortableRestorePlan> = restorePlanner.prepareRestorePlan(
        archiveLocator = archiveLocator,
        destinationRoot = destinationRoot,
        options = options,
        onProgress = onProgress,
    )

    override suspend fun discardPlan(plan: PortableRestorePlan) {
        restorePlanner.discardPlan(plan)
    }

    override suspend fun executeRestore(
        plan: PortableRestorePlan,
        confirmedArchiveSha256: String,
        onProgress: suspend (PortableRestoreProgress) -> Unit,
    ): Result<PortableRestoreReceipt> = restoreExecutor.executeRestore(
        plan = plan,
        confirmedArchiveSha256 = confirmedArchiveSha256,
        ownedAssetsRoot = ownedAssetsRoot,
        onProgress = onProgress,
    )
}

fun defaultPortableBackupFileName(epochMillis: Long): String {
    val safeStamp = epochMillis.coerceAtLeast(0L)
    return "openyt-backup-$safeStamp.openyt-backup.zip"
}
