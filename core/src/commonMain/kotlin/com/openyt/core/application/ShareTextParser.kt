package com.openyt.core.application

/**
 * Extracts the first usable http(s) URL from shared text (for example an
 * Android ACTION_SEND payload). Trailing punctuation is trimmed.
 */
object ShareTextParser {
    private val URL_PATTERN = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
    private val TRAILING = charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '}', '"', '\'')

    fun firstUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val validator = UrlValidator()
        return URL_PATTERN.findAll(text)
            .map { it.value.trimEnd(*TRAILING) }
            .firstOrNull { validator.validate(it) is UrlValidation.Valid }
    }
}
