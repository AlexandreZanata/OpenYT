package com.openyt.core.application

import kotlinx.serialization.json.*

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
    fun parse(body: String, currentVersion: String): UpdateInfo? = runCatching {
        val release = Json.parseToJsonElement(body).jsonObject
        if (release["draft"]?.jsonPrimitive?.booleanOrNull == true || release["prerelease"]?.jsonPrimitive?.booleanOrNull == true) return null
        val tag = release["tag_name"]?.jsonPrimitive?.content?.removePrefix("v") ?: return null
        val url = release["html_url"]?.jsonPrimitive?.content?.takeIf { it.startsWith("https://github.com/") } ?: return null
        val assets = (release["assets"] as? JsonArray).orEmpty().mapNotNull { entry ->
            val download = (entry as? JsonObject)?.get("browser_download_url")?.jsonPrimitive?.content
                ?.takeIf { it.startsWith("https://github.com/") } ?: return@mapNotNull null
            UpdateAsset(download.substringAfterLast('/'), download)
        }
        if (Semver.isNewer(tag, currentVersion)) UpdateInfo(tag, url, assets) else null
    }.getOrNull()
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
