package com.openyt.data

import com.openyt.core.application.DefaultLibraryRecoveryUiController
import com.openyt.core.application.DownloadRepository
import com.openyt.core.application.LibraryHealthAnalyzer
import com.openyt.core.application.LibraryRecoveryUiController
import com.openyt.core.application.MediaHealthInspector
import com.openyt.core.application.OfflineAssetRepository
import com.openyt.core.application.PlaybackSessionControl
import com.openyt.core.application.RecoveryCandidateMatcher
import com.openyt.core.application.RecoveryCandidateSourcePort
import com.openyt.core.application.RelinkExecutor
import com.openyt.core.application.StudyRepository
import com.openyt.core.application.SubtitleIndexRepository
import com.openyt.data.db.OpenYtDatabase
import java.io.File

data class LibraryRecoveryRuntime(
    val healthAnalyzer: LibraryHealthAnalyzer,
    val candidateMatcher: RecoveryCandidateMatcher,
    val relinkExecutor: RelinkExecutor,
    val controller: LibraryRecoveryUiController,
)

fun createLibraryRecoveryRuntime(
    database: OpenYtDatabase,
    downloadRepository: DownloadRepository,
    offlineAssetRepository: OfflineAssetRepository?,
    subtitleIndexRepository: SubtitleIndexRepository?,
    studyRepository: StudyRepository?,
    stagingRoot: File,
    ownedRecoveryRootProvider: () -> String,
    streamBridge: PortableStreamBridge = FilePortableStreamBridge(),
    healthInspector: MediaHealthInspector = JvmMediaHealthInspector(),
    candidateSourcePort: RecoveryCandidateSourcePort = JvmRecoveryCandidateScanner(),
    playbackSessionControl: PlaybackSessionControl? = null,
): LibraryRecoveryRuntime {
    val healthAnalyzer = LibraryHealthAnalyzer(
        inspector = healthInspector,
        nowProvider = { System.currentTimeMillis() },
    )
    val matcher = RecoveryCandidateMatcher(candidateSourcePort)
    val executor = RelinkExecutor(
        transferPort = JvmRelinkTransferPort(stagingRoot = stagingRoot, streamBridge = streamBridge),
        publicationPort = SqlDelightRelinkPublicationPort(database),
        journalRepository = SqlDelightPortableOperationRepository(database),
        playbackSessionControl = playbackSessionControl,
        nowProvider = { System.currentTimeMillis() },
    )
    val controller = DefaultLibraryRecoveryUiController(
        downloadRepository = downloadRepository,
        offlineAssetRepository = offlineAssetRepository,
        subtitleIndexRepository = subtitleIndexRepository,
        studyRepository = studyRepository,
        healthAnalyzer = healthAnalyzer,
        candidateSourcePort = candidateSourcePort,
        candidateMatcher = matcher,
        relinkExecutor = executor,
        ownedRecoveryRootProvider = ownedRecoveryRootProvider,
        nowProvider = { System.currentTimeMillis() },
    )
    return LibraryRecoveryRuntime(
        healthAnalyzer = healthAnalyzer,
        candidateMatcher = matcher,
        relinkExecutor = executor,
        controller = controller,
    )
}
