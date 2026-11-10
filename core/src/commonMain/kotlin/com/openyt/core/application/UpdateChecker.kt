package com.openyt.core.application

data class UpdateAsset(
    val name: String,
    val url: String,
)

data class UpdateInfo(
    val version: String,
    val releaseUrl: String,
    val assets: List<UpdateAsset> = emptyList(),
)

fun interface UpdateChecker {
    /** Returns an update only when the latest release is newer than [currentVersion]. */
    suspend fun latest(currentVersion: String): UpdateInfo?
}

fun interface UpdateInstaller {
    /** Downloads the platform installer and hands it to the OS; returns false on failure. */
    suspend fun install(update: UpdateInfo): Boolean
}

fun interface UrlOpener {
    fun open(url: String): Boolean
}

object GitHubReleaseParser {
    private val TAG = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"")
    private val URL = Regex("\"html_url\"\\s*:\\s*\"([^\"]+)\"")
    private val ASSET = Regex("\"browser_download_url\"\\s*:\\s*\"([^\"]+)\"")

    fun parse(body: String, currentVersion: String): UpdateInfo? {
        val tag = TAG.find(body)?.groupValues?.getOrNull(1)?.removePrefix("v") ?: return null
        val url = URL.find(body)?.groupValues?.getOrNull(1) ?: return null
        val assets = ASSET.findAll(body)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .filter { it.startsWith("https://") }
            .map { UpdateAsset(name = it.substringAfterLast('/'), url = it) }
            .toList()
        return if (Semver.isNewer(tag, currentVersion)) UpdateInfo(tag, url, assets) else null
    }
}

object Semver {
    fun compare(a: String, b: String): Int {
        val left = parse(a) ?: return 0
        val right = parse(b) ?: return 0
        for (index in 0..2) {
            if (left[index] != right[index]) return left[index].compareTo(right[index])
        }
        return 0
    }

    fun isNewer(candidate: String, current: String): Boolean = compare(candidate, current) > 0

    private fun parse(value: String): List<Int>? {
        val cleaned = value.trim().removePrefix("v").substringBefore('-').substringBefore('+')
        val parts = cleaned.split('.')
        if (parts.isEmpty() || parts.size > 3) return null
        val numbers = parts.map { it.toIntOrNull() ?: return null }
        return List(3) { numbers.getOrElse(it) { 0 } }
    }
}
