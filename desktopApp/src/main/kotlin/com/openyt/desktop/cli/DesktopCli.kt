package com.openyt.desktop.cli

import com.openyt.core.application.DownloadCoordinator
import com.openyt.core.application.EnqueuePlaylistUseCase
import com.openyt.core.application.InspectMediaUseCase
import com.openyt.core.application.OrganizationPlanner
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
import com.openyt.core.domain.PlaylistEntry
import com.openyt.core.domain.PlaylistInfo
import com.openyt.data.EncryptedCookieRepository
import com.openyt.data.SqlDelightDownloadRepository
import com.openyt.data.createOpenYtDatabase
import com.openyt.data.defaultDatabasePath
import com.openyt.desktop.BuildInfo
import com.openyt.desktop.defaultDownloadsDirectory
import com.openyt.desktop.downloader.DesktopYtDlpEngine
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
        if (!args.flag("list") && addTag == null && removeTag == null) {
            throw CliException("library requires --list, --add-tag, or --remove-tag")
        }
        val database = createOpenYtDatabase(defaultDatabasePath())
        val repository = SqlDelightDownloadRepository(database)
        if (addTag != null) {
            repository.addLibraryTag(args.required("entry"), addTag)
        }
        if (removeTag != null) {
            repository.removeLibraryTag(args.required("entry"), removeTag)
        }
        val entries = repository.loadLibrary()
        if (args.flag("json")) {
            val payload = buildJsonObject {
                put("ok", true)
                put("command", "library")
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
              main --cli cookies  [--import <path>] [--check] [--clear] [--json]
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
