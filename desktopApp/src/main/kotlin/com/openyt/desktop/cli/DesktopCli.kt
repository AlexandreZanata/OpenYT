package com.openyt.desktop.cli

import com.openyt.core.application.DownloadCoordinator
import com.openyt.core.application.EnqueuePlaylistUseCase
import com.openyt.core.application.FolderImportService
import com.openyt.core.application.InspectMediaUseCase
import com.openyt.core.application.BulkFilePlanner
import com.openyt.core.application.BulkFileService
import com.openyt.core.application.LibraryArchiveCodec
import com.openyt.core.application.LibraryArchiveService
import com.openyt.core.application.LibraryImportPolicy
import com.openyt.core.application.LibraryQuery
import com.openyt.core.application.OrganizationPlanner
import com.openyt.core.application.StorageAnalyzer
import com.openyt.core.application.toLibraryFilter
import com.openyt.core.domain.DownloadFailure
import com.openyt.core.domain.DownloadPriority
import com.openyt.core.domain.DownloadRequest
import com.openyt.core.domain.DownloadStatus
import com.openyt.core.domain.DownloadTask
import com.openyt.core.domain.EngineResult
import com.openyt.core.domain.Inspection
import com.openyt.core.domain.MediaFormat
import com.openyt.core.domain.MediaInfo
import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.OrganizationPreset
import com.openyt.core.domain.SmartCollection
import com.openyt.core.domain.PlaylistEntry
import com.openyt.core.domain.PlaylistInfo
import com.openyt.data.EncryptedCookieRepository
import com.openyt.data.FileBulkFileOperator
import com.openyt.data.FileFolderScanner
import com.openyt.data.SqlDelightDownloadRepository
import com.openyt.data.createOpenYtDatabase
import com.openyt.data.defaultDatabasePath
import com.openyt.desktop.BuildInfo
import com.openyt.desktop.defaultDownloadsDirectory
import com.openyt.desktop.downloader.DesktopYtDlpEngine
import com.openyt.desktop.player.DesktopPlaybackLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import kotlinx.serialization.json.put

/**
 * Headless desktop entry point used by the real Linux validation script.
 *
 * Commands never initialise Compose and reuse the same core use cases as the UI.
 */
object DesktopCli {
    private val json = Json { prettyPrint = true }

    fun run(rawArgs: List<String>): Int {
        val command = rawArgs.firstOrNull() ?: return usage()
        val args = CliArgs(rawArgs.drop(1))
        return try {
            runBlocking {
                when (command) {
                    "version" -> {
                        println(BuildInfo.version)
                        0
                    }

                    "inspect" -> inspect(args)
                    "download" -> download(args)
                    "playlist" -> playlist(args)
                    "library" -> library(args)
                    "cookies" -> cookies(args)
                    "playback" -> playback(args)
                    else -> usage()
                }
            }
        } catch (e: CliException) {
            System.err.println("error: ${e.message}")
            1
        } catch (e: Exception) {
            System.err.println("error: ${e.message ?: e::class.simpleName}")
            1
        }
    }

    private suspend fun inspect(args: CliArgs): Int {
        val url = args.required("url")
        val engine = DesktopYtDlpEngine()
        return try {
            when (val result = InspectMediaUseCase(engine)(url)) {
                is EngineResult.Success -> {
                    emitInspection(result.value, args.flag("json"))
                    0
                }

                is EngineResult.Failure -> {
                    emitFailure("inspect", result.failure, args.flag("json"))
                    1
                }
            }
        } finally {
            engine.close()
        }
    }

    private suspend fun download(args: CliArgs): Int {
        val url = args.required("url")
        val kind = parseKind(args.value("kind") ?: "video")
        val priority = parsePriority(args.value("priority"))
        val preset = parsePreset(args.value("preset"))
        val engine = DesktopYtDlpEngine()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return try {
            val inspection = inspectOrFail(engine, url)
            val media = when (inspection) {
                is Inspection.Single -> inspection.media
                is Inspection.Playlist -> throw CliException(
                    "playlist URL detected; use the playlist command",
                )
            }
            val coordinator = DownloadCoordinator(
                engine = engine,
                scope = scope,
                cookieRepository = EncryptedCookieRepository(cookieDirectory()),
            )
            args.value("limit-bytes-per-second")?.toLongOrNull()?.let(coordinator::setSpeedLimit)
            val id = coordinator.enqueue(
                media = media,
                request = DownloadRequest(
                    url = url,
                    kind = kind,
                    formatId = args.value("format-id"),
                    destinationDirectory = args.value("dir") ?: defaultDownloadsDirectory(),
                    fileNameTemplate = OrganizationPlanner.singleTemplate(
                        preset,
                        args.value("template"),
                    ),
                ),
                priority = priority,
            )
            val task = awaitTasks(coordinator, listOf(id), args.timeoutMillis()).single()
            emitTask("download", task, args.flag("json"))
            if (task.status == DownloadStatus.COMPLETED) 0 else 1
        } finally {
            scope.cancel()
            engine.close()
        }
    }

    private suspend fun playlist(args: CliArgs): Int {
        val url = args.required("url")
        val kind = parseKind(args.value("kind") ?: "video")
        val priority = parsePriority(args.value("priority"))
        val preset = parsePreset(args.value("preset"))
        val engine = DesktopYtDlpEngine()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return try {
            val inspection = inspectOrFail(engine, url)
            val playlist = when (inspection) {
                is Inspection.Playlist -> inspection.playlist
                is Inspection.Single -> throw CliException(
                    "single media URL detected; use the download command",
                )
            }
            val entries = selectEntries(
                entries = playlist.entries,
                indexes = args.value("indexes"),
                limit = args.value("limit")?.toIntOrNull(),
            )
            if (entries.isEmpty()) throw CliException("no playlist entries selected")

            val coordinator = DownloadCoordinator(
                engine = engine,
                scope = scope,
                cookieRepository = EncryptedCookieRepository(cookieDirectory()),
            )
            args.value("limit-bytes-per-second")?.toLongOrNull()?.let(coordinator::setSpeedLimit)
            val ids = EnqueuePlaylistUseCase(coordinator)(
                playlist = playlist,
                entries = entries,
                kind = kind,
                downloadsDirectory = args.value("dir") ?: defaultDownloadsDirectory(),
                priority = priority,
                preset = preset,
                customFileNameTemplate = args.value("template"),
            )
            val tasks = awaitTasks(coordinator, ids, args.timeoutMillis())
            emitPlaylist(playlist, tasks, args.flag("json"))
            if (tasks.all { it.status == DownloadStatus.COMPLETED }) 0 else 1
        } finally {
            scope.cancel()
            engine.close()
        }
    }

    private fun playback(args: CliArgs): Int {
        if (!args.flag("check")) throw CliException("playback requires --check")
        val launcher = DesktopPlaybackLauncher()
        val payload = buildJsonObject {
            put("ok", true)
            put("command", "playback")
            put("canPlay", launcher.canPlay)
            put("vlcAvailable", launcher.isVlcAvailable())
        }
        println(json.encodeToString(JsonElement.serializer(), payload))
        return 0
    }

    private suspend fun cookies(args: CliArgs): Int {
        val repository = EncryptedCookieRepository(cookieDirectory())
        when {
            args.value("import") != null -> {
                val path = args.required("import")
                val file = File(path)
                if (!file.isFile || file.length() > MAX_COOKIE_FILE_BYTES) {
                    throw CliException("cookies file is missing or too large")
                }
                repository.importCookies(file.readText())
            }

            args.flag("clear") -> repository.clear()
        }
        val configured = repository.isConfigured()
        if (args.flag("json")) {
            val payload = buildJsonObject {
                put("ok", true)
                put("command", "cookies")
                put("configured", configured)
            }
            println(json.encodeToString(JsonElement.serializer(), payload))
        } else {
            println("cookies configured=$configured")
        }
        return 0
    }

    private fun cookieDirectory(): File =
        File(defaultDatabasePath()).parentFile.resolve("cookies")

    private suspend fun library(args: CliArgs): Int {
        val addTag = args.value("add-tag")
        val removeTag = args.value("remove-tag")
        val createSmart = args.value("create-smart")
        val deleteSmart = args.value("delete-smart")
        val smartId = args.value("smart")
        val search = args.value("search")
        val exportPath = args.value("export")
        val importPath = args.value("import")
        val scanPath = args.value("scan")
        val storage = args.flag("storage")
        val bulkRename = args.value("bulk-rename")
        val bulkMove = args.value("bulk-move")
        if (
            !args.flag("list") &&
            addTag == null &&
            removeTag == null &&
            createSmart == null &&
            deleteSmart == null &&
            smartId == null &&
            search == null &&
            exportPath == null &&
            importPath == null &&
            scanPath == null &&
            !storage &&
            bulkRename == null &&
            bulkMove == null
        ) {
            throw CliException(
                "library requires --list, --add-tag, --remove-tag, --create-smart, " +
                    "--delete-smart, --smart, or --search",
            )
        }
        val database = createOpenYtDatabase(defaultDatabasePath())
        val repository = SqlDelightDownloadRepository(database)
        if (addTag != null) {
            repository.addLibraryTag(args.required("entry"), addTag)
        }
        if (removeTag != null) {
            repository.removeLibraryTag(args.required("entry"), removeTag)
        }
        val archiveService = LibraryArchiveService(repository)
        var bulkResult: com.openyt.core.application.BulkApplyResult? = null
        if (bulkRename != null || bulkMove != null) {
            val ids = args.value("ids")
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?.toSet()
                .orEmpty()
            if (ids.isEmpty()) throw CliException("--ids is required for bulk operations")
            val selected = repository.loadLibrary().filter { it.id in ids }
            if (selected.isEmpty()) throw CliException("no library entries matched --ids")
            val plan = if (bulkRename != null) {
                BulkFilePlanner.planRename(selected, bulkRename) { File(it).exists() }
            } else {
                BulkFilePlanner.planMove(selected, bulkMove.orEmpty()) { File(it).exists() }
            }
            bulkResult = BulkFileService(FileBulkFileOperator(), repository).apply(plan)
        }
        var storageReport: com.openyt.core.application.StorageReport? = null
        if (storage) {
            val known = FileFolderScanner().scan(
                args.value("storage-dir") ?: defaultDownloadsDirectory(),
            )
            val missingIds = repository.loadLibrary()
                .filter { it.filePath.isBlank() || !File(it.filePath).isFile }
                .map { it.id }
                .toSet()
            storageReport = StorageAnalyzer.analyze(
                entries = repository.loadLibrary(),
                missingIds = missingIds,
                knownFiles = known,
            )
        }
        var scanResult: com.openyt.core.application.FolderImportResult? = null
        if (scanPath != null) {
            scanResult = FolderImportService(FileFolderScanner(), repository).import(scanPath)
        }
        var exportedPath: String? = null
        var importResult: com.openyt.core.application.LibraryImportResult? = null
        if (exportPath != null) {
            val archive = archiveService.export()
            File(exportPath).writeText(LibraryArchiveCodec.encode(archive))
            exportedPath = File(exportPath).absolutePath
        }
        if (importPath != null) {
            val archive = LibraryArchiveCodec.decode(File(importPath).readText())
                .getOrElse { throw CliException("invalid library archive: ${it.message}") }
            val policy = when (args.value("policy")?.lowercase()) {
                null, "merge" -> LibraryImportPolicy.MERGE
                "replace" -> LibraryImportPolicy.REPLACE
                else -> throw CliException("--policy must be merge or replace")
            }
            importResult = archiveService.import(archive, policy)
        }
        var createdSmart: SmartCollection? = null
        if (createSmart != null) {
            createdSmart = SmartCollection(
                id = "smart-${System.currentTimeMillis()}",
                name = createSmart,
                query = args.value("query") ?: "",
                kind = args.value("kind")?.let(::parseKind),
                tag = args.value("tag"),
                minSizeBytes = args.value("min-size-bytes")?.toLongOrNull(),
                maxSizeBytes = args.value("max-size-bytes")?.toLongOrNull(),
                createdWithinDays = args.value("created-within-days")?.toIntOrNull(),
            )
            repository.insertSmartCollection(createdSmart)
        }
        if (deleteSmart != null) {
            repository.deleteSmartCollection(deleteSmart)
        }
        val smartCollections = repository.loadSmartCollections()
        val collections = repository.loadCollections()
        val membership = repository.loadCollectionMembership()
        var entries = repository.loadLibrary()
        if (smartId != null) {
            val smart = smartCollections.firstOrNull { it.id == smartId }
                ?: throw CliException("smart collection not found: $smartId")
            entries = LibraryQuery.apply(
                entries = entries,
                missingIds = emptySet(),
                filter = smart.toLibraryFilter(),
                nowEpochMillis = System.currentTimeMillis(),
            ).map { it.entry }
        }
        if (search != null) {
            entries = LibraryQuery.apply(
                entries = entries,
                missingIds = emptySet(),
                filter = com.openyt.core.application.LibraryFilter(query = search),
                nowEpochMillis = System.currentTimeMillis(),
            ).map { it.entry }
        }
        if (args.flag("json")) {
            val payload = buildJsonObject {
                put("ok", true)
                put("command", "library")
                createdSmart?.let {
                    put("createdSmartCollection", it.id)
                }
                exportedPath?.let { put("exportPath", it) }
                bulkResult?.let { result ->
                    put(
                        "bulkResult",
                        buildJsonObject {
                            put("success", result.success)
                            put("rolledBack", result.rolledBack)
                            put(
                                "applied",
                                buildJsonArray {
                                    result.applied.forEach { change ->
                                        add(
                                            buildJsonObject {
                                                put("entryId", change.entryId)
                                                put("fromPath", change.fromPath)
                                                put("toPath", change.toPath)
                                            },
                                        )
                                    }
                                },
                            )
                        },
                    )
                }
                storageReport?.let { report ->
                    put(
                        "storage",
                        buildJsonObject {
                            put("totalEntries", report.totalEntries)
                            put("totalBytes", report.totalBytes)
                            put(
                                "largest",
                                buildJsonArray {
                                    report.largest.forEach { entry ->
                                        add(
                                            buildJsonObject {
                                                put("id", entry.id)
                                                put("title", entry.title)
                                                putNullable("sizeBytes", entry.sizeBytes)
                                                put("filePath", entry.filePath)
                                            },
                                        )
                                    }
                                },
                            )
                            put(
                                "duplicateGroups",
                                buildJsonArray {
                                    report.duplicateGroups.forEach { group ->
                                        add(
                                            buildJsonObject {
                                                put(
                                                    "entries",
                                                    buildJsonArray {
                                                        group.entries.forEach { entry ->
                                                            add(JsonPrimitive(entry.id))
                                                        }
                                                    },
                                                )
                                                put("totalBytes", group.totalBytes)
                                            },
                                        )
                                    }
                                },
                            )
                            put(
                                "missing",
                                buildJsonArray {
                                    report.missing.forEach { entry -> add(JsonPrimitive(entry.id)) }
                                },
                            )
                            put(
                                "orphans",
                                buildJsonArray {
                                    report.orphanFiles.forEach { file ->
                                        add(JsonPrimitive(file.path))
                                    }
                                },
                            )
                        },
                    )
                }
                scanResult?.let { result ->
                    put(
                        "scanResult",
                        buildJsonObject {
                            put("added", result.added)
                            put("skipped", result.skipped)
                        },
                    )
                }
                importResult?.let { result ->
                    put(
                        "importResult",
                        buildJsonObject {
                            put("entriesImported", result.entriesImported)
                            put("entriesUpdated", result.entriesUpdated)
                            put("entriesSkipped", result.entriesSkipped)
                            put("collectionsImported", result.collectionsImported)
                            put("smartCollectionsImported", result.smartCollectionsImported)
                        },
                    )
                }
                put(
                    "smartCollections",
                    buildJsonArray {
                        smartCollections.forEach { smart ->
                            add(
                                buildJsonObject {
                                    put("id", smart.id)
                                    put("name", smart.name)
                                    put("query", smart.query)
                                    putNullable("kind", smart.kind?.name)
                                    putNullable("tag", smart.tag)
                                    putNullable("minSizeBytes", smart.minSizeBytes)
                                    putNullable("maxSizeBytes", smart.maxSizeBytes)
                                    putNullable("createdWithinDays", smart.createdWithinDays)
                                },
                            )
                        }
                    },
                )
                put(
                    "collections",
                    buildJsonArray {
                        collections.forEach { collection ->
                            add(
                                buildJsonObject {
                                    put("id", collection.id)
                                    put("name", collection.name)
                                },
                            )
                        }
                    },
                )
                put(
                    "membership",
                    buildJsonObject {
                        membership.forEach { (collectionId, entryIds) ->
                            put(
                                collectionId,
                                buildJsonArray {
                                    entryIds.forEach { entryId -> add(JsonPrimitive(entryId)) }
                                },
                            )
                        }
                    },
                )
                put(
                    "entries",
                    buildJsonArray {
                        entries.forEach { entry ->
                            add(
                                buildJsonObject {
                                    put("id", entry.id)
                                    put("title", entry.title)
                                    put("kind", entry.kind.name)
                                    put("filePath", entry.filePath)
                                    putNullable("sizeBytes", entry.sizeBytes)
                                    putNullable("thumbnailUrl", entry.thumbnailUrl)
                                    putNullable("source", entry.source)
                                    put("createdAtEpochMillis", entry.createdAtEpochMillis)
                                    put(
                                        "tags",
                                        buildJsonArray {
                                            entry.tags.forEach { tag -> add(JsonPrimitive(tag)) }
                                        },
                                    )
                                },
                            )
                        }
                    },
                )
            }
            println(json.encodeToString(JsonElement.serializer(), payload))
        } else {
            entries.forEach { entry ->
                println("${entry.kind.name}\t${entry.title}\t${entry.filePath}")
            }
        }
        return 0
    }

    private suspend fun inspectOrFail(engine: DesktopYtDlpEngine, url: String): Inspection =
        when (val result = InspectMediaUseCase(engine)(url)) {
            is EngineResult.Success -> result.value
            is EngineResult.Failure -> throw CliException(
                "inspection failed: ${describe(result.failure)}",
            )
        }

    private suspend fun awaitTasks(
        coordinator: DownloadCoordinator,
        ids: List<String>,
        timeoutMillis: Long,
    ): List<DownloadTask> = withTimeout(timeoutMillis) {
        coordinator.tasks.first { tasks ->
            ids.all { id -> tasks.any { it.id == id && it.status.isFinished } }
        }.filter { it.id in ids }
    }

    private fun emitInspection(inspection: Inspection, asJson: Boolean) {
        if (asJson) {
            val payload = buildJsonObject {
                put("ok", true)
                put("command", "inspect")
                put("inspection", inspectionJson(inspection))
            }
            println(json.encodeToString(JsonElement.serializer(), payload))
            return
        }
        when (inspection) {
            is Inspection.Single -> {
                val media = inspection.media
                println("media\t${media.source}\t${media.title}")
                media.formats.forEach { format ->
                    println(
                        "format\t${format.id}\t${format.kind.name}\t" +
                            "${format.container}\t${format.width ?: "-"}x${format.height ?: "-"}",
                    )
                }
            }

            is Inspection.Playlist -> {
                val playlist = inspection.playlist
                println(
                    "playlist\t${playlist.source}\t${playlist.title}\t" +
                        "${playlist.entries.size} entries",
                )
                playlist.entries.forEach { entry ->
                    println("entry\t${entry.index}\t${entry.title}\t${entry.url}")
                }
            }
        }
    }

    private fun emitFailure(command: String, failure: DownloadFailure, asJson: Boolean) {
        if (asJson) {
            val payload = buildJsonObject {
                put("ok", false)
                put("command", command)
                put("failure", failureJson(failure))
            }
            println(json.encodeToString(JsonElement.serializer(), payload))
        } else {
            System.err.println("error: ${describe(failure)}")
        }
    }

    private fun emitTask(command: String, task: DownloadTask, asJson: Boolean) {
        if (asJson) {
            val payload = buildJsonObject {
                put("ok", task.status == DownloadStatus.COMPLETED)
                put("command", command)
                put("task", taskJson(task))
            }
            println(json.encodeToString(JsonElement.serializer(), payload))
            return
        }
        println("${task.id}\t${task.status.name}\t${task.filePath.orEmpty()}")
        task.failure?.let { System.err.println("error: ${describe(it)}") }
    }

    private fun emitPlaylist(
        playlist: PlaylistInfo,
        tasks: List<DownloadTask>,
        asJson: Boolean,
    ) {
        if (asJson) {
            val payload = buildJsonObject {
                put("ok", tasks.all { it.status == DownloadStatus.COMPLETED })
                put("command", "playlist")
                put("playlist", playlistJson(playlist))
                put("tasks", JsonArray(tasks.map(::taskJson)))
            }
            println(json.encodeToString(JsonElement.serializer(), payload))
            return
        }
        println("playlist\t${playlist.title}\t${tasks.size} tasks")
        tasks.forEach { task ->
            println("${task.id}\t${task.status.name}\t${task.filePath.orEmpty()}")
        }
    }

    private fun inspectionJson(inspection: Inspection): JsonObject = when (inspection) {
        is Inspection.Single -> buildJsonObject {
            put("type", "media")
            put("media", mediaJson(inspection.media))
        }

        is Inspection.Playlist -> buildJsonObject {
            put("type", "playlist")
            put("playlist", playlistJson(inspection.playlist))
        }
    }

    private fun mediaJson(media: MediaInfo): JsonObject = buildJsonObject {
        put("sourceId", media.sourceId)
        put("source", media.source)
        put("title", media.title)
        putNullable("author", media.author)
        putNullable("durationSeconds", media.durationSeconds)
        putNullable("thumbnailUrl", media.thumbnailUrl)
        put("webpageUrl", media.webpageUrl)
        put("isLive", media.isLive)
        put("formats", JsonArray(media.formats.map(::formatJson)))
    }

    private fun playlistJson(playlist: PlaylistInfo): JsonObject = buildJsonObject {
        put("sourceId", playlist.sourceId)
        put("source", playlist.source)
        put("title", playlist.title)
        putNullable("author", playlist.author)
        putNullable("thumbnailUrl", playlist.thumbnailUrl)
        put("webpageUrl", playlist.webpageUrl)
        put("entries", JsonArray(playlist.entries.map(::entryJson)))
    }

    private fun entryJson(entry: PlaylistEntry): JsonObject = buildJsonObject {
        put("index", entry.index)
        put("title", entry.title)
        put("url", entry.url)
        putNullable("author", entry.author)
        putNullable("durationSeconds", entry.durationSeconds)
        putNullable("thumbnailUrl", entry.thumbnailUrl)
    }

    private fun formatJson(format: MediaFormat): JsonObject = buildJsonObject {
        put("id", format.id)
        put("kind", format.kind.name)
        put("container", format.container)
        putNullable("videoCodec", format.videoCodec)
        putNullable("audioCodec", format.audioCodec)
        putNullable("width", format.width)
        putNullable("height", format.height)
        putNullable("bitrateKbps", format.bitrateKbps)
        putNullable("sizeBytes", format.sizeBytes)
        putNullable("note", format.note)
    }

    private fun taskJson(task: DownloadTask): JsonObject = buildJsonObject {
        put("id", task.id)
        put("status", task.status.name)
        put("percent", task.percent)
        putNullable("speedBytesPerSecond", task.speedBytesPerSecond)
        putNullable("etaSeconds", task.etaSeconds)
        putNullable("filePath", task.filePath)
        putNullable("sizeBytes", task.sizeBytes)
        task.failure?.let { put("failure", failureJson(it)) }
    }

    private fun failureJson(failure: DownloadFailure): JsonObject = buildJsonObject {
        put("type", failure::class.simpleName ?: "Failure")
        when (failure) {
            is DownloadFailure.InvalidUrl -> put("detail", failure.reason)
            is DownloadFailure.Unavailable -> putNullable("detail", failure.reason)
            is DownloadFailure.DependencyMissing -> put("detail", failure.component)
            is DownloadFailure.ProcessingFailed -> putNullable("detail", failure.reason)
            is DownloadFailure.Unexpected -> putNullable("detail", failure.reason)
            else -> Unit
        }
    }

    private fun describe(failure: DownloadFailure): String = when (failure) {
        is DownloadFailure.InvalidUrl -> "invalid URL: ${failure.reason}"
        DownloadFailure.UnsupportedSource -> "unsupported source"
        is DownloadFailure.Unavailable -> "content unavailable: ${failure.reason.orEmpty()}"
        DownloadFailure.Network -> "network failure"
        DownloadFailure.InsufficientSpace -> "insufficient disk space"
        DownloadFailure.PermissionDenied -> "permission denied"
        is DownloadFailure.DependencyMissing -> "missing dependency: ${failure.component}"
        is DownloadFailure.ProcessingFailed -> "processing failed: ${failure.reason.orEmpty()}"
        is DownloadFailure.Unexpected -> "unexpected failure: ${failure.reason.orEmpty()}"
    }

    private fun parseKind(value: String): MediaKind = when (value.lowercase()) {
        "video" -> MediaKind.VIDEO
        "audio" -> MediaKind.AUDIO
        else -> throw CliException("--kind must be video or audio")
    }

    private fun parsePriority(value: String?): DownloadPriority = when (value?.lowercase()) {
        null, "normal" -> DownloadPriority.NORMAL
        "high" -> DownloadPriority.HIGH
        "low" -> DownloadPriority.LOW
        else -> throw CliException("--priority must be high, normal, or low")
    }

    private fun parsePreset(value: String?): OrganizationPreset = when (value?.lowercase()) {
        null, "playlist" -> OrganizationPreset.PLAYLIST
        "playlist-channel" -> OrganizationPreset.PLAYLIST_CHANNEL
        "playlist-year" -> OrganizationPreset.PLAYLIST_YEAR
        "channel" -> OrganizationPreset.CHANNEL
        "year" -> OrganizationPreset.YEAR
        else -> throw CliException(
            "--preset must be playlist, playlist-channel, playlist-year, channel, or year",
        )
    }

    private fun selectEntries(
        entries: List<PlaylistEntry>,
        indexes: String?,
        limit: Int?,
    ): List<PlaylistEntry> {
        val selected = if (indexes.isNullOrBlank()) {
            entries
        } else {
            val wanted = indexes.split(',')
                .mapNotNull { it.trim().toIntOrNull() }
                .toSet()
            entries.filter { it.index in wanted }
        }
        return limit?.let { selected.take(it) } ?: selected
    }

    private fun usage(): Int {
        System.err.println(
            """
            OpenYT headless CLI
              main --cli inspect  --url <url> [--json]
              main --cli download --url <url> [--kind video|audio] [--format-id <id>]
                                  [--priority high|normal|low] [--dir <path>]
                                  [--template <t>] [--preset <name>]
                                  [--limit-bytes-per-second <n>]
                                  [--timeout-ms <n>] [--json]
              main --cli playlist --url <url> [--kind video|audio] [--indexes 1,2]
                                  [--limit <n>] [--priority high|normal|low]
                                  [--dir <path>] [--preset <name>]
                                  [--limit-bytes-per-second <n>]
                                  [--timeout-ms <n>] [--json]
              main --cli library  --list [--json]
              main --cli library  --entry <id> --add-tag <tag>|--remove-tag <tag> [--json]
              main --cli library  --create-smart <name> [--query ..] [--kind video|audio]
                                  [--tag ..] [--min-size-bytes ..] [--max-size-bytes ..]
                                  [--created-within-days ..] [--json]
              main --cli library  --delete-smart <id> [--json]
              main --cli library  --smart <id> [--json]
              main --cli library  --search <query> [--json]
              main --cli library  --export <path> [--json]
              main --cli library  --import <path> [--policy merge|replace] [--json]
              main --cli library  --scan <directory> [--json]
              main --cli library  --storage [--storage-dir <directory>] [--json]
              main --cli library  --bulk-rename <pattern> --ids <id,id> [--json]
              main --cli library  --bulk-move <directory> --ids <id,id> [--json]
              main --cli cookies  [--import <path>] [--check] [--clear] [--json]
              main --cli playback --check [--json]
              main --cli version
            """.trimIndent(),
        )
        return 2
    }

    private fun JsonObjectBuilder.putNullable(key: String, value: String?) {
        put(key, value?.let(::JsonPrimitive) ?: JsonNull)
    }

    private fun JsonObjectBuilder.putNullable(key: String, value: Long?) {
        put(key, value?.let(::JsonPrimitive) ?: JsonNull)
    }

    private fun JsonObjectBuilder.putNullable(key: String, value: Int?) {
        put(key, value?.let(::JsonPrimitive) ?: JsonNull)
    }

    private const val MAX_COOKIE_FILE_BYTES = 2L * 1024 * 1024

    private fun JsonObjectBuilder.putNullable(key: String, value: Double?) {
        put(key, value?.let(::JsonPrimitive) ?: JsonNull)
    }
}

private class CliException(message: String) : Exception(message)

private class CliArgs(raw: List<String>) {
    private val options = mutableMapOf<String, String>()
    private val flags = mutableSetOf<String>()

    init {
        var index = 0
        while (index < raw.size) {
            val token = raw[index]
            if (!token.startsWith("--")) {
                index++
                continue
            }
            val name = token.removePrefix("--")
            val next = raw.getOrNull(index + 1)
            if (next != null && !next.startsWith("--")) {
                options[name] = next
                index += 2
            } else {
                flags += name
                index++
            }
        }
    }

    fun value(name: String): String? = options[name]

    fun required(name: String): String =
        options[name]?.takeIf { it.isNotBlank() }
            ?: throw CliException("missing --$name")

    fun flag(name: String): Boolean = name in flags

    fun timeoutMillis(): Long = value("timeout-ms")?.toLongOrNull() ?: 900_000L
}
