package com.openyt.android

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openyt.core.application.OfflineAssetMaintenance
import com.openyt.core.application.OfflineLibraryPresenter
import com.openyt.core.application.SettingsController
import com.openyt.core.application.SubtitleImportService
import com.openyt.core.domain.AppSettings
import com.openyt.core.domain.AssetKind
import com.openyt.core.domain.AssetOperationException
import com.openyt.core.domain.AssetOperationFailure
import com.openyt.core.domain.CompanionArtifactReceipt
import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.MediaRevision
import com.openyt.core.domain.ReadinessStatus
import com.openyt.core.domain.RecoveryAction
import com.openyt.data.JvmMediaFileInspector
import com.openyt.data.JvmOfflineAssetStore
import com.openyt.data.SqlDelightAssetOperationRepository
import com.openyt.data.SqlDelightDownloadRepository
import com.openyt.data.SqlDelightOfflineAssetRepository
import com.openyt.data.SqlDelightSettingsRepository
import com.openyt.data.createOpenYtDatabase
import com.openyt.ui.components.MediaThumbnail
import com.openyt.ui.components.OfflineReadinessCard
import com.openyt.ui.i18n.LocalStrings
import com.openyt.ui.i18n.PtBrStrings
import com.openyt.ui.theme.OpenYtTheme
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented Android contract verification for NX2.6 / NX-O06:
 * - Owned artwork and subtitle assets survive volatile Coil/cache clearing across DB reopen;
 * - Unreachable remote artwork URLs never block or spoof local offline readiness;
 * - Pinning protects durable assets from cleanup and configured budget rejections preserve existing assets/media;
 * - Media mutation/removal transitions readiness cleanly to STALE/REVERIFY and UNAVAILABLE/RELINK_MEDIA;
 * - OfflineReadinessCard and local MediaThumbnail render visibly with enlarged text.
 */
@RunWith(AndroidJUnit4::class)
class AndroidOfflineProofTest {

    private val dbName = "openyt-offline-proof-android.db"
    private lateinit var workDir: File

    @After
    fun tearDown() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(dbName)
        if (::workDir.isInitialized) {
            workDir.deleteRecursively()
        }
    }

    @Test
    fun offlineReadinessQuotaPinningCacheClearAndUiRenderSurviveReopenOnAndroid() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.deleteDatabase(dbName)
        workDir = File(context.cacheDir, "offline-proof-work").apply {
            deleteRecursively()
            mkdirs()
        }
        val mediaFile = File(workDir, "media/offline-fixture.mp4").apply {
            parentFile?.mkdirs()
        }
        instrumentation.context.assets.open("player-fixture.mp4").use { input ->
            mediaFile.outputStream().use { input.copyTo(it) }
        }
        val inspector = JvmMediaFileInspector()
        val initialHash = requireNotNull(inspector.computeSha256(mediaFile.canonicalPath))

        val assetsDir = File(workDir, "owned-assets").apply { mkdirs() }
        val renderCacheDir = File(context.cacheDir, "coil-sim-cache").apply {
            deleteRecursively()
            mkdirs()
        }
        File(renderCacheDir, "cached-thumb.bin").writeBytes(ByteArray(4096) { 3 })

        val stageDir = File(workDir, "staged").apply { mkdirs() }
        val artFile = File(stageDir, "cover.jpg")
        val bmp = Bitmap.createBitmap(128, 72, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(24, 96, 168))
        }
        artFile.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        val srtFile = File(stageDir, "lesson.srt").apply {
            writeText("1\n00:00:00,000 --> 00:00:02,000\nOffline Android proof\n")
        }
        val extraSrt = File(stageDir, "too-large.srt").apply {
            writeText("1\n00:00:03,000 --> 00:00:05,000\nExceeds configured asset budget\n")
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val db1 = createOpenYtDatabase(context, dbName)
            val settingsRepo1 = SqlDelightSettingsRepository(db1)
            val settingsController1 = SettingsController(settingsRepo1, scope)
            settingsController1.restore()
            val presetBudget = 256L * 1024L * 1024L
            val customReserve = 64L * 1024L * 1024L
            settingsController1.setAssetBudgetBytes(presetBudget)
            settingsController1.setStorageReserveBytes(customReserve)
            // Poll briefly until Channel write persists to SQLite.
            var persisted = false
            repeat(50) {
                val loaded = settingsRepo1.load()
                if (loaded.assetBudgetBytes == presetBudget && loaded.storageReserveBytes == customReserve) {
                    persisted = true
                    return@repeat
                }
                kotlinx.coroutines.delay(20)
            }
            assertTrue("Expected SettingsController budget/reserve persistence", persisted)
            val tightBudget = artFile.length() + srtFile.length() + 16L
            settingsRepo1.save(
                AppSettings(
                    assetBudgetBytes = tightBudget,
                    storageReserveBytes = customReserve,
                ),
            )
            settingsController1.restore()

            val downloadRepo1 = SqlDelightDownloadRepository(db1)
            val assetRepo1 = SqlDelightOfflineAssetRepository(db1)
            val opRepo1 = SqlDelightAssetOperationRepository(db1)
            val store1 = JvmOfflineAssetStore(
                assetsDirectory = assetsDir,
                repository = assetRepo1,
                operationRepository = opRepo1,
                fileInspector = inspector,
                storageReserveBytesProvider = { 1024L },
                assetBudgetBytesProvider = { settingsController1.settings.value.assetBudgetBytes },
            )

            val entry = LibraryEntry(
                id = "android-off-1",
                title = "Android Offline Proof",
                kind = MediaKind.VIDEO,
                filePath = mediaFile.canonicalPath,
                sizeBytes = mediaFile.length(),
                thumbnailUrl = "https://unreachable.invalid/remote-thumb.jpg",
                mediaRevision = MediaRevision(mediaFile.length(), mediaFile.lastModified(), initialHash),
            )
            downloadRepo1.insertLibraryEntry(entry)

            val artAsset = store1.storeConfirmedArtifact(
                entryId = entry.id,
                receipt = CompanionArtifactReceipt(
                    kind = AssetKind.ARTWORK,
                    filePath = artFile.canonicalPath,
                    isRequired = true,
                ),
                mediaRevision = entry.mediaRevision!!,
                allowedRoots = listOf(stageDir.canonicalPath),
            ).getOrThrow()

            val subService1 = SubtitleImportService(store1.operator, assetRepo1, store1.stagingPort, inspector)
            val subAsset = subService1.importSubtitleFile(
                entryId = entry.id,
                sourcePath = srtFile.canonicalPath,
                language = "pt",
                mediaRevision = entry.mediaRevision!!,
            ).getOrThrow()

            val rejected = subService1.importSubtitleFile(
                entryId = entry.id,
                sourcePath = extraSrt.canonicalPath,
                language = "en",
                mediaRevision = entry.mediaRevision!!,
            )
            assertTrue("Expected asset budget exceedance error", rejected.isFailure)
            val opFailure = (rejected.exceptionOrNull() as? AssetOperationException)?.failure
            assertTrue("Expected StorageBudgetExceeded, got ${rejected.exceptionOrNull()}", opFailure is AssetOperationFailure.StorageBudgetExceeded)

            val cacheBytesBefore = renderCacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            val stateBefore = OfflineLibraryPresenter.present(
                entry = entry,
                assets = assetRepo1.loadAssetsForEntry(entry.id),
                remoteArtworkUrl = entry.thumbnailUrl,
                renderingCacheBytes = cacheBytesBefore,
                fileInspector = inspector,
                resolveAssetPath = { store1.resolveAssetPath(it) },
            )
            assertEquals(ReadinessStatus.READY, stateBefore.readiness.status)
            assertEquals(cacheBytesBefore, stateBefore.storage.renderingCacheBytes)

            // Clear simulated Coil/app cache and reopen database completely offline.
            assertTrue(renderCacheDir.deleteRecursively())
            val db2 = createOpenYtDatabase(context, dbName)
            val reloadedSettings = SqlDelightSettingsRepository(db2).load()
            assertEquals(tightBudget, reloadedSettings.assetBudgetBytes)
            assertEquals(customReserve, reloadedSettings.storageReserveBytes)

            val assetRepo2 = SqlDelightOfflineAssetRepository(db2)
            val store2 = JvmOfflineAssetStore(
                assetsDirectory = assetsDir,
                repository = assetRepo2,
                operationRepository = SqlDelightAssetOperationRepository(db2),
                fileInspector = inspector,
                storageReserveBytes = 1024L,
                assetBudgetBytes = reloadedSettings.assetBudgetBytes,
            )
            val offlineState = OfflineLibraryPresenter.present(
                entry = entry,
                assets = assetRepo2.loadAssetsForEntry(entry.id),
                remoteArtworkUrl = entry.thumbnailUrl,
                renderingCacheBytes = 0L,
                fileInspector = inspector,
                resolveAssetPath = { store2.resolveAssetPath(it) },
            )
            assertEquals(ReadinessStatus.READY, offlineState.readiness.status)
            assertTrue(offlineState.hasVerifiedLocalArtwork)
            val ownedArtFile = File(requireNotNull(offlineState.localArtworkPath)).canonicalFile
            assertTrue(ownedArtFile.isFile && ownedArtFile.path.startsWith(assetsDir.canonicalPath))

            // Render real Compose OfflineReadinessCard + MediaThumbnail on MainActivity and screenshot it.
            val activity = instrumentation.startActivitySync(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as MainActivity
            try {
                instrumentation.runOnMainSync {
                    activity.setContent {
                        OpenYtTheme(darkTheme = true) {
                            val density = LocalDensity.current
                            CompositionLocalProvider(
                                LocalStrings provides PtBrStrings,
                                LocalDensity provides Density(density.density, fontScale = 1.3f),
                            ) {
                                Surface {
                                    Column(
                                        modifier = Modifier.padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        MediaThumbnail(
                                            url = entry.thumbnailUrl,
                                            localArtworkPath = offlineState.localArtworkPath,
                                            modifier = Modifier.fillMaxWidth().height(120.dp),
                                        )
                                        OfflineReadinessCard(
                                            state = offlineState,
                                            onTogglePin = { _, _ -> },
                                            onCleanup = {},
                                            onAction = {},
                                            onRetry = {},
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                instrumentation.waitForIdleSync()
                assertNotNull(awaitText(PtBrStrings.offline.ready))
                assertNotNull(awaitText(PtBrStrings.offline.offlineAssets))
                saveValidationScreenshot("android-offline-readiness.png")
            } finally {
                instrumentation.runOnMainSync { activity.finish() }
            }

            // Pin artwork, clean unpinned subtitle, verify original media bytes intact.
            val maintenance = OfflineAssetMaintenance(assetRepo2, store2)
            assertTrue(maintenance.setPinned(entry.id, artAsset.id, true).isSuccess)
            val preview = maintenance.cleanupPreview(entry.id)
            assertEquals(listOf(subAsset.id), preview.map { it.id })
            assertEquals(1, maintenance.cleanup(entry.id, preview.map { it.id }.toSet()).getOrThrow())
            assertTrue(ownedArtFile.isFile)
            assertEquals(initialHash, inspector.computeSha256(mediaFile.canonicalPath))

            // Mutate media bytes -> STALE + REVERIFY; delete media -> UNAVAILABLE + RELINK_MEDIA.
            mediaFile.appendBytes(byteArrayOf(0x42))
            val staleState = OfflineLibraryPresenter.present(
                entry = entry,
                assets = assetRepo2.loadAssetsForEntry(entry.id),
                remoteArtworkUrl = entry.thumbnailUrl,
                renderingCacheBytes = 0L,
                fileInspector = inspector,
                resolveAssetPath = { store2.resolveAssetPath(it) },
            )
            assertEquals(ReadinessStatus.STALE, staleState.readiness.status)
            assertTrue(staleState.readiness.missingRequirements.any { it.suggestedAction == RecoveryAction.REVERIFY })

            assertTrue(mediaFile.delete())
            val missingState = OfflineLibraryPresenter.present(
                entry = entry,
                assets = assetRepo2.loadAssetsForEntry(entry.id),
                remoteArtworkUrl = entry.thumbnailUrl,
                renderingCacheBytes = 0L,
                fileInspector = inspector,
                resolveAssetPath = { store2.resolveAssetPath(it) },
            )
            assertEquals(ReadinessStatus.UNAVAILABLE, missingState.readiness.status)
            assertTrue(missingState.readiness.missingRequirements.any { it.suggestedAction == RecoveryAction.RELINK_MEDIA })
        } finally {
            scope.cancel()
        }
    }

    private fun awaitText(text: String): android.view.accessibility.AccessibilityNodeInfo {
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        repeat(60) {
            val root = uiAutomation.rootInActiveWindow
            if (root != null) {
                val queue = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
                queue.add(root)
                while (queue.isNotEmpty()) {
                    val node = queue.removeFirst()
                    val candidate = "${node.text ?: ""} ${node.contentDescription ?: ""}"
                    if (candidate.contains(text)) return node
                    for (i in 0 until node.childCount) {
                        node.getChild(i)?.let(queue::addLast)
                    }
                }
            }
            Thread.sleep(100)
        }
        error("Timed out waiting for text '$text' on Android screen")
    }
}
