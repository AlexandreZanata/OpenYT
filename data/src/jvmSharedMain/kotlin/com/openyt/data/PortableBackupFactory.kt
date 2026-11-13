package com.openyt.data

import com.openyt.core.application.DefaultPortableBackupUiController
import com.openyt.core.application.OfflineAssetStore
import com.openyt.core.application.PortableBackupExportService
import com.openyt.core.application.PortableBackupUiController
import com.openyt.core.application.PortableRestoreExecutor
import com.openyt.core.application.PortableRestorePlanner
import com.openyt.core.application.SubtitleIndexRebuilder
import com.openyt.core.application.SubtitleIndexRepository
import com.openyt.data.db.OpenYtDatabase
import com.openyt.data.search.JvmSubtitleContentAdapter
import java.io.File

data class PortableBackupRuntime(
    val exportService: PortableBackupExportService,
    val restorePlanner: PortableRestorePlanner,
    val restoreExecutor: PortableRestoreExecutor,
    val controller: PortableBackupUiController,
) {
    suspend fun reconcileOnStartup() {
        exportService.reconcileInterruptedOperations()
        restoreExecutor.reconcileAfterRestart()
    }
}

fun createPortableBackupRuntime(
    database: OpenYtDatabase,
    stagingRoot: File,
    ownedAssetsRoot: File,
    streamBridge: PortableStreamBridge,
    defaultDestinationRoot: () -> String,
    offlineAssetStore: OfflineAssetStore? = null,
    subtitleIndexRepository: SubtitleIndexRepository? = null,
    appVersion: String? = null,
): PortableBackupRuntime {
    val snapshotSource = SqlDelightPortableBackupSnapshotSource(database)
    val journalRepository = SqlDelightPortableOperationRepository(database)
    val storageAccess = JvmPortableStorageAccessPort(
        stagingRoot = stagingRoot,
        streamBridge = streamBridge,
    )
    val writerPort = JvmPortableArchiveWriter(
        stagingRoot = stagingRoot,
        streamBridge = streamBridge,
    )
    val readerPort = JvmPortableArchiveReader(
        streamBridge = streamBridge,
    )
    val ownedResolver: (com.openyt.core.domain.OfflineAsset) -> String? = { asset ->
        if (asset.relativeLocator.isBlank()) null else offlineAssetStore?.resolveAssetPath(asset.relativeLocator)
    }
    val exportService = PortableBackupExportService(
        snapshotSource = snapshotSource,
        writerPort = writerPort,
        storageAccess = storageAccess,
        journalRepository = journalRepository,
        ownedAssetLocatorResolver = ownedResolver,
        nowProvider = { System.currentTimeMillis() },
    )
    val restorePlanner = PortableRestorePlanner(
        snapshotSource = snapshotSource,
        readerPort = readerPort,
        storageAccess = storageAccess,
        journalRepository = journalRepository,
        nowProvider = { System.currentTimeMillis() },
    )
    val subtitleRebuilder = subtitleIndexRepository?.let { repo ->
        SubtitleIndexRebuilder(
            indexRepository = repo,
            contentPort = JvmSubtitleContentAdapter(),
        )
    }
    val restoreExecutor = PortableRestoreExecutor(
        storageAccess = storageAccess,
        journalRepository = journalRepository,
        publicationPort = SqlDelightPortableRestorePublicationPort(database),
        subtitleRebuilder = subtitleRebuilder,
        ownedAssetLocatorResolver = ownedResolver,
        nowProvider = { System.currentTimeMillis() },
    )
    val controller = DefaultPortableBackupUiController(
        exportService = exportService,
        restorePlanner = restorePlanner,
        restoreExecutor = restoreExecutor,
        ownedAssetsRoot = ownedAssetsRoot.absolutePath,
        defaultDestinationRoot = defaultDestinationRoot,
        appVersion = appVersion,
    )
    return PortableBackupRuntime(
        exportService = exportService,
        restorePlanner = restorePlanner,
        restoreExecutor = restoreExecutor,
        controller = controller,
    )
}
