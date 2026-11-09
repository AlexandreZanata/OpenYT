package com.openyt.android.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import com.openyt.core.application.DiagnosticTool
import com.openyt.core.application.DiagnosticsProvider
import com.openyt.core.application.DiagnosticsRedactor
import com.openyt.core.application.PlatformDiagnostics
import com.openyt.core.application.UpdateChecker
import com.openyt.core.application.UpdateInfo
import com.openyt.core.application.GitHubReleaseParser
import com.openyt.core.application.UrlOpener
import com.openyt.core.domain.DownloadEngine
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidDiagnosticsProvider(
    private val context: Context,
    private val engine: DownloadEngine,
    private val downloadsDirectory: String,
) : DiagnosticsProvider {
    override suspend fun snapshot(): PlatformDiagnostics {
        val capabilities = engine.capabilities()
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "unknown"
        val externalRoot = context.getExternalFilesDir(null)?.absolutePath
        val database = context.getDatabasePath("openyt.db").absolutePath
        val appRoot = context.filesDir.parentFile?.absolutePath

        return PlatformDiagnostics(
            appVersion = version,
            platform = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            engineName = capabilities.engineName,
            engineVersion = capabilities.engineVersion,
            downloadDirectory = externalRoot?.let {
                DiagnosticsRedactor.redactPath(downloadsDirectory, it)
            } ?: "<app-downloads>",
            databasePath = appRoot?.let {
                DiagnosticsRedactor.redactPath(database, it)
            },
            tools = listOf(
                DiagnosticTool("yt-dlp", available = true, version = capabilities.engineVersion),
                DiagnosticTool("ffmpeg", available = true),
                DiagnosticTool("quickjs", available = true),
            ),
        )
    }

    override fun copyToClipboard(text: String): Boolean = runCatching {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("OpenYT diagnostics", text))
        true
    }.getOrDefault(false)
}

class AndroidUpdateChecker : UpdateChecker {
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

class AndroidUrlOpener(private val context: Context) : UrlOpener {
    override fun open(url: String): Boolean = runCatching {
        context.startActivity(
            android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse(url),
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.getOrDefault(false)
}
