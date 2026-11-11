package com.openyt.core.application

data class DiagnosticTool(
    val name: String,
    val available: Boolean,
    val version: String? = null,
)

data class PlatformDiagnostics(
    val appVersion: String,
    val platform: String,
    val engineName: String,
    val engineVersion: String?,
    val downloadDirectory: String,
    val databasePath: String?,
    val tools: List<DiagnosticTool> = emptyList(),
)

interface DiagnosticsProvider {
    suspend fun snapshot(): PlatformDiagnostics

    fun copyToClipboard(text: String): Boolean
}

object DiagnosticsRedactor {
    private val USER_INFO = Regex("""(https?://)[^/@\s]+@""", RegexOption.IGNORE_CASE)
    private val QUERY_OR_FRAGMENT = Regex("""(https?://[^\s?#]+)[?#][^\s]*""", RegexOption.IGNORE_CASE)
    private val SECRET_KEYS = Regex(
        """(?i)(token|key|secret|password|authorization|cookie)=([^&\s]+)""",
    )

    fun redactUrl(value: String): String =
        value
            .replace(USER_INFO, "$1")
            .replace(SECRET_KEYS, "$1=<redacted>")
            .replace(QUERY_OR_FRAGMENT, "$1")

    fun redactPath(path: String, home: String?): String {
        if (path.isBlank()) return path
        val normalizedPath = path.replace('\\', '/')
        val normalizedHome = home?.replace('\\', '/')?.trimEnd('/')?.takeIf { it.isNotBlank() }
        val insensitive = normalizedPath.getOrNull(1) == ':'
        if (normalizedHome != null &&
            (normalizedPath.equals(normalizedHome, insensitive) ||
                normalizedPath.startsWith("$normalizedHome/", insensitive))) {
            return "~" + normalizedPath.substring(normalizedHome.length)
        }
        return "<local>/" + normalizedPath.substringAfterLast('/')
    }
}

object DiagnosticsFormatter {
    fun format(
        diagnostics: PlatformDiagnostics,
        taskCount: Int,
        libraryCount: Int,
        collectionCount: Int,
    ): String = buildString {
        appendLine("OpenYT diagnostics")
        appendLine("version: ${diagnostics.appVersion}")
        appendLine("platform: ${diagnostics.platform}")
        appendLine(
            "engine: ${diagnostics.engineName}" +
                (diagnostics.engineVersion?.let { " $it" } ?: ""),
        )
        appendLine("downloads: ${diagnostics.downloadDirectory}")
        diagnostics.databasePath?.let { appendLine("database: $it") }
        appendLine("tasks: $taskCount")
        appendLine("library: $libraryCount")
        appendLine("collections: $collectionCount")
        if (diagnostics.tools.isNotEmpty()) {
            appendLine("tools:")
            diagnostics.tools.forEach { tool ->
                append("- ${tool.name}: ")
                append(if (tool.available) "available" else "missing")
                tool.version?.let { append(" ($it)") }
                appendLine()
            }
        }
    }.trimEnd()
}
