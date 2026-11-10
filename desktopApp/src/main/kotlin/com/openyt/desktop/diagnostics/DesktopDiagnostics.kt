package com.openyt.desktop.diagnostics

import com.openyt.core.application.DiagnosticTool
import com.openyt.core.application.DiagnosticsProvider
import com.openyt.core.application.DiagnosticsRedactor
import com.openyt.core.application.GitHubReleaseParser
import com.openyt.core.application.PlatformDiagnostics
import com.openyt.core.application.UpdateChecker
import com.openyt.core.application.UpdateInfo
import com.openyt.core.application.UrlOpener
import com.openyt.core.domain.DownloadEngine
import com.openyt.data.defaultDatabasePath
import com.openyt.desktop.BuildInfo
import com.openyt.desktop.downloader.ExecutableLocator
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DesktopDiagnosticsProvider(
    private val engine: DownloadEngine,
    private val downloadsDirectory: String,
    private val locator: ExecutableLocator = ExecutableLocator(),
) : DiagnosticsProvider {
    override suspend fun snapshot(): PlatformDiagnostics = withContext(Dispatchers.IO) {
        val capabilities = engine.capabilities()
        val home = System.getProperty("user.home")
        val jsRuntime = locator.findJsRuntime()

        PlatformDiagnostics(
            appVersion = BuildInfo.version,
            platform = "${System.getProperty("os.name")} ${System.getProperty("os.version")}",
            engineName = capabilities.engineName,
            engineVersion = capabilities.engineVersion,
            downloadDirectory = DiagnosticsRedactor.redactPath(downloadsDirectory, home),
            databasePath = DiagnosticsRedactor.redactPath(defaultDatabasePath(), home),
            tools = listOf(
                DiagnosticTool("yt-dlp", locator.findYtDlp() != null, capabilities.engineVersion),
                DiagnosticTool("ffmpeg", locator.findFfmpeg() != null),
                DiagnosticTool("ffprobe", locator.findFfprobe() != null),
                DiagnosticTool("js-runtime", jsRuntime != null, jsRuntime),
            ),
        )
    }

    override fun copyToClipboard(text: String): Boolean = runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        true
    }.getOrDefault(false)
}

class DesktopUpdateChecker : UpdateChecker {
    override suspend fun latest(currentVersion: String): UpdateInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL(RELEASE_API).openConnection() as HttpURLConnection
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "OpenYT/$currentVersion")
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            try {
                if (connection.responseCode !in 200..299) return@runCatching null
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                GitHubReleaseParser.parse(body, currentVersion)
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    private companion object {
        const val RELEASE_API =
            "https://api.github.com/repos/AlexandreZanata/OpenYT/releases/latest"
    }
}

class DesktopUrlOpener : UrlOpener {
    override fun open(url: String): Boolean {
        val browsed = runCatching {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI(url))
                true
            } else {
                false
            }
        }.getOrDefault(false)
        return browsed || runCatching {
            ProcessBuilder("xdg-open", url).start()
            true
        }.getOrDefault(false)
    }
}

class DesktopUpdateInstaller : com.openyt.core.application.UpdateInstaller {
    override suspend fun install(update: com.openyt.core.application.UpdateInfo): Boolean =
        withContext(Dispatchers.IO) {
            val os = System.getProperty("os.name").orEmpty().lowercase()
            val suffix = when {
                os.contains("win") -> ".msi"
                os.contains("mac") -> ".dmg"
                else -> ".deb"
            }
            val asset = update.assets.firstOrNull {
                it.name.endsWith(suffix, ignoreCase = true)
            } ?: return@withContext false
            val directory = java.io.File(
                System.getProperty("user.home") ?: ".",
                "Downloads/OpenYT/updates",
            ).apply { mkdirs() }
            val file = java.io.File(directory, asset.name)
            val downloaded = runCatching {
                val connection = URL(asset.url).openConnection() as HttpURLConnection
                connection.connectTimeout = 15_000
                connection.readTimeout = 120_000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "OpenYT")
                try {
                    if (connection.responseCode !in 200..299) return@runCatching false
                    connection.inputStream.use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
                    true
                } finally {
                    connection.disconnect()
                }
            }.getOrDefault(false)
            if (!downloaded) return@withContext false

            val opened = runCatching {
                if (Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().open(file)
                    true
                } else {
                    false
                }
            }.getOrDefault(false)
            opened || runCatching {
                ProcessBuilder("xdg-open", file.absolutePath).start()
                true
            }.getOrDefault(false)
        }
}
