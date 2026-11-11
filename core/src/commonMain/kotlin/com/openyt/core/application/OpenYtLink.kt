package com.openyt.core.application

/** Encoded handoff only. Links open inspection and never start a download silently. */
object OpenYtLink {
    fun create(url: String): String? {
        val valid = UrlValidator().validate(url) as? UrlValidation.Valid ?: return null
        val encoded = valid.url.encodeToByteArray().joinToString("") { byte ->
            val value = byte.toInt() and 255
            val char = value.toChar()
            if (char.isLetterOrDigit() && value < 128 || char in "-._~") char.toString()
            else "%" + value.toString(16).uppercase().padStart(2, '0')
        }
        return "openyt://download?url=$encoded"
    }

    fun parse(link: String?): String? {
        if (link == null || !link.startsWith("openyt://download?", ignoreCase = true) || link.length > 12_000) return null
        val fields = link.substringAfter('?').split('&').filter { it.substringBefore('=') == "url" }
        if (fields.size != 1) return null
        val encoded = fields.single().substringAfter('=', "")
        val bytes = mutableListOf<Byte>()
        var index = 0
        while (index < encoded.length) {
            when (val char = encoded[index]) {
                '%' -> {
                    val hex = encoded.substring(index + 1, (index + 3).coerceAtMost(encoded.length))
                    if (hex.length != 2) return null
                    bytes += (hex.toIntOrNull(16) ?: return null).toByte()
                    index += 3
                }
                else -> {
                    bytes.addAll((if (char == '+') " " else char.toString()).encodeToByteArray().toList())
                    index++
                }
            }
        }
        val decoded = runCatching { bytes.toByteArray().decodeToString(throwOnInvalidSequence = true) }.getOrNull() ?: return null
        return (UrlValidator().validate(decoded) as? UrlValidation.Valid)?.url
    }
}
