package com.openyt.data

import com.openyt.core.application.*
import com.openyt.core.domain.*
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class ClipCoverPublicationTest {
    @Test fun retryAfterCoverCommitUsesTheVerifiedAssetWithinASingleCoverBudget() = runBlocking {
        val root = Files.createTempDirectory("clip-cover-retry-").toFile()
        val driver = app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver("jdbc:sqlite:${root.resolve("library.db").path}")
        try {
            com.openyt.data.db.OpenYtDatabase.Schema.create(driver)
            val database = com.openyt.data.db.OpenYtDatabase(driver)
            val repository = SqlDelightDownloadRepository(database)
            repository.insertLibraryEntry(LibraryEntry("source", "Original", MediaKind.VIDEO, root.resolve("source.mp4").path, author = "Source author", source = "fixture"))
            val cover = root.resolve("cover.jpg").apply { writeBytes(ByteArray(128) { it.toByte() }) }
            val inspector = JvmMediaFileInspector()
            val revision = MediaRevision(100, 200, "video-digest")
            val output = ProcessedMedia("processed-one", "Clip", root.resolve("clip.mp4").path, MediaKind.VIDEO, 100,
                revision, cover.path, root.path, inspector.computeSha256(cover.path))
            val assets = JvmOfflineAssetStore(root.resolve("assets"), SqlDelightOfflineAssetRepository(database),
                SqlDelightAssetOperationRepository(database), storageReserveBytes = 0, assetBudgetBytes = cover.length())
            ClipCoverPublisher(assets, inspector).publish(output)
            // Simulate failure/crash after durable cover publication and before
            // the successful journal/library transaction, then reconstruct it.
            val request = MediaProcessingRequest(root.resolve("source.mp4").path, "Source", MediaKind.VIDEO, MediaOperation.TRIM,
                MediaSegment(0, 1000), sourceEntryId = "source", sourceDurationMillis = 2000, jobId = "one")
            val store = SqlDelightClipJobStore(database)
            store.save(ClipJobRecord("one", request, "digest", ClipJobStatus.FAILED, output))
            ClipCoverPublisher(assets, inspector).publish(output)
            store.complete(ClipJobRecord("one", request, "digest", ClipJobStatus.SUCCEEDED, output))
            assertEquals(1, assets.getAssetsForEntry(output.id).size)
            assertEquals("Source author", repository.loadLibrary().single { it.id == output.id }.author)
            assertEquals(ClipJobStatus.SUCCEEDED, store.get("one")!!.status)
            val asset = assets.getAssetsForEntry(output.id).single()
            java.io.File(assets.resolveAssetPath(asset.relativeLocator)).writeText("corrupt")
            assertFails { ClipCoverPublisher(assets, inspector).publish(output) }
            Unit
        } finally { driver.close(); root.deleteRecursively() }
    }
}
