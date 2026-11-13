package com.openyt.core.application

/**
 * Typed deep-link routes accepted by OpenYT hosts.
 */
sealed interface OpenYtRoute {
    data class Download(val url: String) : OpenYtRoute
    data class LibraryEntry(
        val entryId: String,
        val timestampMillis: Long? = null,
    ) : OpenYtRoute
}

/** Formats and strictly validates OpenYT deep links across hosts. */
object OpenYtLink {
    fun create(url: String): String? {
        val valid = UrlValidator().validate(url) as? UrlValidation.Valid ?: return null
        val encoded = percentEncodeUtf8(valid.url)
        return "openyt://download?url=$encoded"
    }

    fun parse(link: String?): String? {
        if (link == null || !link.startsWith("openyt://download?", ignoreCase = true) || link.length > 12_000) {
            return null
        }
        val fields = link.substringAfter('?').split('&').filter { it.substringBefore('=') == "url" }
        if (fields.size != 1) return null
        val raw = fields.single().substringAfter('=', "")
        val decoded = percentDecodeUtf8Strict(raw) ?: return null
        return (UrlValidator().validate(decoded) as? UrlValidation.Valid)?.url
    }

    fun parseRoute(link: String?): OpenYtRoute? {
        parse(link)?.let { return OpenYtRoute.Download(it) }
        OpenYtEntryLink.parse(link)?.let { return it }
        return null
    }

    fun formatRoute(route: OpenYtRoute): String? = when (route) {
        is OpenYtRoute.Download -> create(route.url)
        is OpenYtRoute.LibraryEntry -> OpenYtEntryLink.create(route.entryId, route.timestampMillis)
    }

    internal fun percentEncodeUtf8(value: String): String = buildString {
        for (byte in value.encodeToByteArray()) {
            val code = byte.toInt() and 0xFF
            if (isUnreservedByte(code)) {
                append(code.toChar())
            } else {
                append('%').append(code.toString(16).uppercase().padStart(2, '0'))
            }
        }
    }

    internal fun percentDecodeUtf8Strict(raw: String): String? {
        val output = ByteArray(raw.length)
        var index = 0
        var size = 0
        while (index < raw.length) {
            val ch = raw[index]
            if (ch == '%') {
                if (index + 2 >= raw.length) return null
                val hi = raw[index + 1].digitToIntOrNull(16) ?: return null
                val lo = raw[index + 2].digitToIntOrNull(16) ?: return null
                output[size++] = ((hi shl 4) or lo).toByte()
                index += 3
            } else {
                for (b in ch.toString().encodeToByteArray()) {
                    if (size >= output.size) return null
                    output[size++] = b
                }
                index++
            }
        }
        val bytes = output.copyOf(size)
        return try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            null
        }
    }

    private fun isUnreservedByte(code: Int): Boolean =
        code in 'a'.code..'z'.code ||
            code in 'A'.code..'Z'.code ||
            code in '0'.code..'9'.code ||
            code in intArrayOf('-'.code, '_'.code, '.'.code, '~'.code)
}

/**
 * Formats and strictly validates local study library entry links of the form
 * `openyt://library/entry/<percent-encoded-id>?t=<milliseconds>` (query `?t=` optional).
 */
object OpenYtEntryLink {
    const val MAX_LINK_LENGTH = 2_048
    const val MAX_ENTRY_ID_LENGTH = 256
    const val MAX_TIMESTAMP_MILLIS = 86_400_000_000L

    private const val PREFIX = "openyt://library"
    private const val ENTRY_SEGMENT = "/entry/"

    fun create(
        entryId: String,
        timestampMillis: Long? = null,
    ): String? {
        if (!isValidEntryId(entryId)) return null
        if (timestampMillis != null && (timestampMillis < 0L || timestampMillis > MAX_TIMESTAMP_MILLIS)) {
            return null
        }
        val encodedId = OpenYtLink.percentEncodeUtf8(entryId)
        val link = if (timestampMillis != null) {
            "$PREFIX$ENTRY_SEGMENT$encodedId?t=$timestampMillis"
        } else {
            "$PREFIX$ENTRY_SEGMENT$encodedId"
        }
        return link.takeIf { it.length <= MAX_LINK_LENGTH }
    }

    fun parse(link: String?): OpenYtRoute.LibraryEntry? {
        if (link == null || link.isEmpty() || link.length > MAX_LINK_LENGTH) return null
        if (link.any { it.isISOControl() || it.isWhitespace() || it == '#' || it == '\\' }) return null
        if (!link.startsWith(PREFIX, ignoreCase = true)) return null

        val remainder = link.substring(PREFIX.length)
        if (!remainder.startsWith(ENTRY_SEGMENT)) return null

        val afterPrefix = remainder.substring(ENTRY_SEGMENT.length)
        val questionIndex = afterPrefix.indexOf('?')
        val encodedId: String
        val timestampMillis: Long?

        if (questionIndex < 0) {
            encodedId = afterPrefix
            timestampMillis = null
        } else {
            encodedId = afterPrefix.substring(0, questionIndex)
            val query = afterPrefix.substring(questionIndex + 1)
            if (query.isEmpty() || '?' in query) return null
            timestampMillis = parseTimestampQuery(query) ?: return null
        }

        if (encodedId.isEmpty() || '/' in encodedId) return null
        val entryId = OpenYtLink.percentDecodeUtf8Strict(encodedId) ?: return null
        if (!isValidEntryId(entryId)) return null
        if ('/' in entryId || '\\' in entryId || entryId == "." || entryId == "..") return null

        return OpenYtRoute.LibraryEntry(
            entryId = entryId,
            timestampMillis = timestampMillis,
        )
    }

    private fun parseTimestampQuery(query: String): Long? {
        val parts = query.split('&')
        if (parts.size != 1) return null
        val param = parts.single()
        val equalsIndex = param.indexOf('=')
        if (equalsIndex <= 0) return null
        val key = param.substring(0, equalsIndex)
        if (key != "t") return null
        val rawValue = param.substring(equalsIndex + 1)
        if (rawValue.isEmpty() || rawValue.length > 16) return null
        if (!rawValue.all { it in '0'..'9' }) return null
        val parsed = rawValue.toLongOrNull() ?: return null
        return parsed.takeIf { it in 0L..MAX_TIMESTAMP_MILLIS }
    }

    private fun isValidEntryId(entryId: String): Boolean {
        if (entryId.isBlank() || entryId.length > MAX_ENTRY_ID_LENGTH) return false
        if ('/' in entryId || '\\' in entryId || entryId == "." || entryId == "..") return false
        return entryId.none { it.isISOControl() }
    }
}
