package com.openyt.core.application

/**
 * Validates, normalizes, and sanitizes URLs to ensure secure transport and prevent
 * leaking user credentials in logs, diagnostics, or database entries.
 */
object SafeUrlSanitizer {

    private val SCHEME_REGEX = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):?//(.*)$")
    private val CREDENTIALS_REGEX = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*://)([^/@]+)@(.*)$")

    /**
     * Checks whether the given url contains embedded user credentials.
     */
    fun hasCredentials(url: String): Boolean {
        return CREDENTIALS_REGEX.matches(url.trim())
    }

    /**
     * Strips credentials from url, returning the clean URL.
     */
    fun stripCredentials(url: String): String {
        val trimmed = url.trim()
        val match = CREDENTIALS_REGEX.matchEntire(trimmed) ?: return trimmed
        val scheme = match.groupValues[1]
        val rest = match.groupValues[3]
        return "$scheme$rest"
    }

    /**
     * Masks embedded credentials for safe inclusion in logs, diagnostics, or error messages.
     */
    fun maskCredentialsForLogs(url: String): String {
        val trimmed = url.trim()
        val match = CREDENTIALS_REGEX.matchEntire(trimmed) ?: return trimmed
        val scheme = match.groupValues[1]
        val rest = match.groupValues[3]
        return "$scheme***:***@$rest"
    }

    /**
     * Validates that url is a valid HTTP or HTTPS URL, strips any embedded credentials,
     * and returns the normalized URL.
     */
    fun validateArtworkUrl(url: String): Result<String> {
        val trimmed = url.trim()
        if (trimmed.isBlank()) {
            return Result.failure(IllegalArgumentException("Artwork URL cannot be blank"))
        }

        val match = SCHEME_REGEX.matchEntire(trimmed)
            ?: return Result.failure(IllegalArgumentException("Invalid URL format: missing scheme"))

        val scheme = match.groupValues[1].lowercase()
        if (scheme != "http" && scheme != "https") {
            return Result.failure(IllegalArgumentException("Unsupported scheme '$scheme': only HTTP and HTTPS are permitted"))
        }

        val cleanUrl = stripCredentials(trimmed)
        val hostPart = cleanUrl.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
        if (hostPart.isBlank()) {
            return Result.failure(IllegalArgumentException("Invalid URL: missing host authority"))
        }

        return Result.success(cleanUrl)
    }
}
