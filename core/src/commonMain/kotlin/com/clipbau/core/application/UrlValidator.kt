package com.clipbau.core.application

sealed interface UrlValidation {
    data class Valid(val url: String) : UrlValidation
    data class Invalid(val reason: String) : UrlValidation
}

/**
 * Validates user supplied media URLs before any engine work happens.
 *
 * This is a portable, allow-list check: only absolute http(s) URLs with a host and
 * no embedded credentials are accepted.
 */
class UrlValidator {
    fun validate(raw: String): UrlValidation {
        val value = raw.trim()
        if (value.isEmpty()) return UrlValidation.Invalid("URL is empty")
        if (value.length > MAX_URL_LENGTH) return UrlValidation.Invalid("URL is too long")
        if (value.any { it.isWhitespace() }) return UrlValidation.Invalid("URL contains whitespace")

        val match = URL_PATTERN.matchEntire(value)
            ?: return UrlValidation.Invalid("URL must start with http:// or https://")

        val authority = match.groupValues[2]
        if (authority.contains('@')) {
            return UrlValidation.Invalid("URL must not contain credentials")
        }
        val host = authority.substringBefore(':')
        if (host.isEmpty() || host == ".") {
            return UrlValidation.Invalid("URL must include a host")
        }
        return UrlValidation.Valid(value)
    }

    private companion object {
        const val MAX_URL_LENGTH = 2048
        val URL_PATTERN = Regex("^(https?)://([^/?#]+)([/?#].*)?$", RegexOption.IGNORE_CASE)
    }
}
