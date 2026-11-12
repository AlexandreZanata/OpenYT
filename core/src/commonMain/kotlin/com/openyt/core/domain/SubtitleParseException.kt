package com.openyt.core.domain

class SubtitleParseException(
    val reason: String,
    val lineNumber: Int? = null,
    cause: Throwable? = null,
) : Exception(
    if (lineNumber != null) "Subtitle parse error at line $lineNumber: $reason" else "Subtitle parse error: $reason",
    cause,
)
