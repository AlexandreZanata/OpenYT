package com.openyt.core.application

import com.openyt.core.domain.ParsedSubtitleTrack
import com.openyt.core.domain.SubtitleFormat
import com.openyt.core.domain.SubtitleParseException
import com.openyt.core.domain.TimedCue

/**
 * Pure Kotlin parser for SRT and WebVTT subtitle tracks.
 *
 * Guarantees:
 * - Operates entirely on in-memory strings (no filesystem, process or network I/O).
 * - Enforces bounded execution (default max 10 MiB, 100,000 cues per track).
 * - Linear-time ($O(N)$) tag stripping and HTML entity decoding with zero regular expression backtracking.
 * - Robust against BOM, CRLF, missing cue separators, out-of-order sequence numbers, and WebVTT metadata.
 * - Produces deterministically ordered cues with stable cue IDs.
 * - Explicit local vs. source timebase offset shifting without negative timestamp drift.
 */
object TimedSubtitleParser {

    const val DEFAULT_MAX_CHARACTERS = 10_485_760 // 10 MiB
    const val DEFAULT_MAX_CUES = 100_000
    private const val MAX_CUE_DURATION_MS = 86_400_000L // 24 hours

    fun parse(
        content: String,
        trackId: String = "track-default",
        entryId: String = "",
        language: String? = null,
        formatHint: SubtitleFormat? = null,
        timebaseOffsetMs: Long = 0L,
        maxCues: Int = DEFAULT_MAX_CUES,
        maxCharacters: Int = DEFAULT_MAX_CHARACTERS,
    ): ParsedSubtitleTrack {
        if (content.length > maxCharacters) {
            throw SubtitleParseException("Subtitle content exceeds maximum character limit ($maxCharacters)")
        }

        val cleanContent = content.removePrefix("\uFEFF") // Strip UTF-8 BOM
        if (cleanContent.isBlank()) {
            throw SubtitleParseException("Subtitle track is empty")
        }

        val lines = cleanContent.lineSequence()
            .map { it.trimEnd('\r', ' ', '\t') }
            .toList()

        val firstNonBlank = lines.firstOrNull { it.isNotBlank() }.orEmpty()
        val isVtt = formatHint == SubtitleFormat.VTT ||
            firstNonBlank.startsWith("WEBVTT") ||
            cleanContent.startsWith("WEBVTT")

        val format = if (isVtt) SubtitleFormat.VTT else (formatHint ?: SubtitleFormat.SRT)

        if (format == SubtitleFormat.VTT && !firstNonBlank.startsWith("WEBVTT")) {
            throw SubtitleParseException("Malformed WebVTT: missing 'WEBVTT' signature", 1)
        }

        val warnings = mutableListOf<String>()
        val cues = mutableListOf<TimedCue>()

        var currentStartMs: Long? = null
        var currentEndMs: Long? = null
        val currentTextLines = mutableListOf<String>()
        var inBlockComment = false
        var originalIndex = 0

        fun flushCue(lineNumber: Int) {
            val start = currentStartMs ?: return
            val end = currentEndMs ?: start

            if (cues.size >= maxCues) {
                throw SubtitleParseException("Subtitle track exceeds maximum cue limit ($maxCues cues)", lineNumber)
            }

            val rawText = currentTextLines.joinToString("\n").trim()
            if (rawText.isEmpty()) {
                warnings.add("Cue at line $lineNumber has empty text")
            }

            // Adjust times with timebase offset
            val adjustedStartMs = (start + timebaseOffsetMs).coerceAtLeast(0L)
            val adjustedEndMs = (end + timebaseOffsetMs).coerceAtLeast(adjustedStartMs)

            val (speaker, textWithoutSpeaker) = extractSpeaker(rawText)
            val plainText = normalizeSearchableText(textWithoutSpeaker)
            val displayText = rawText

            originalIndex++
            val cueId = "$trackId:${originalIndex.toString().padStart(5, '0')}"

            cues.add(
                TimedCue(
                    cueId = cueId,
                    trackId = trackId,
                    entryId = entryId,
                    startMs = adjustedStartMs,
                    endMs = adjustedEndMs,
                    displayText = displayText,
                    plainText = plainText,
                    originalIndex = originalIndex,
                    speaker = speaker,
                ),
            )

            currentStartMs = null
            currentEndMs = null
            currentTextLines.clear()
        }

        var startIndex = 0
        if (format == SubtitleFormat.VTT) {
            // Skip the WEBVTT header line and any initial header metadata until the first empty line
            startIndex = 1
            while (startIndex < lines.size) {
                val line = lines[startIndex]
                if (line.isBlank()) {
                    startIndex++
                    break
                }
                if (line.contains("-->")) {
                    // Cues started immediately without empty line after WEBVTT
                    break
                }
                startIndex++
            }
        }

        for (i in startIndex until lines.size) {
            val lineNumber = i + 1
            val line = lines[i]

            if (line.isBlank()) {
                if (inBlockComment) {
                    inBlockComment = false
                } else if (currentStartMs != null) {
                    flushCue(lineNumber)
                }
                continue
            }

            if (inBlockComment) {
                continue
            }

            if (format == SubtitleFormat.VTT &&
                (line.startsWith("NOTE", ignoreCase = true) ||
                    line.startsWith("STYLE", ignoreCase = true) ||
                    line.startsWith("REGION", ignoreCase = true))
            ) {
                inBlockComment = true
                continue
            }

            if (line.contains("-->")) {
                if (currentStartMs != null) {
                    // Documented nonfatal recovery: previous cue was missing separating empty line
                    warnings.add("Cue at line $lineNumber was not separated by an empty line; flushing previous cue.")
                    flushCue(lineNumber)
                }

                val timeRange = parseTimestampLine(line, lineNumber, warnings)
                if (timeRange != null) {
                    currentStartMs = timeRange.first
                    currentEndMs = timeRange.second
                } else {
                    warnings.add("Skipping cue with unparseable timestamp at line $lineNumber: '$line'")
                }
                continue
            }

            if (currentStartMs != null) {
                currentTextLines.add(line)
            }
        }

        // Flush trailing cue if present
        if (currentStartMs != null) {
            flushCue(lines.size)
        }

        if (cues.isEmpty()) {
            throw SubtitleParseException("No valid subtitle timestamp cues found")
        }

        val sortedCues = cues.sortedWith(
            compareBy({ it.startMs }, { it.endMs }, { it.originalIndex }),
        )

        return ParsedSubtitleTrack(
            trackId = trackId,
            format = format,
            cues = sortedCues,
            warnings = warnings,
            timebaseOffsetMs = timebaseOffsetMs,
            entryId = entryId,
            language = language,
        )
    }

    fun tryParse(
        content: String,
        trackId: String = "track-default",
        entryId: String = "",
        language: String? = null,
        formatHint: SubtitleFormat? = null,
        timebaseOffsetMs: Long = 0L,
        maxCues: Int = DEFAULT_MAX_CUES,
        maxCharacters: Int = DEFAULT_MAX_CHARACTERS,
    ): Result<ParsedSubtitleTrack> = runCatching {
        parse(content, trackId, entryId, language, formatHint, timebaseOffsetMs, maxCues, maxCharacters)
    }

    private fun parseTimestampLine(
        line: String,
        lineNumber: Int,
        warnings: MutableList<String>,
    ): Pair<Long, Long>? {
        val parts = line.split("-->", limit = 2)
        if (parts.size != 2) return null

        val startToken = parts[0].trim()
        val endPart = parts[1].trim()
        val endToken = endPart.substringBefore(' ').substringBefore('\t').trim()

        val startMs = parseTimestampToken(startToken) ?: return null
        val rawEndMs = parseTimestampToken(endToken) ?: return null

        val endMs = if (rawEndMs < startMs) {
            warnings.add("Cue at line $lineNumber has end time ($rawEndMs ms) before start time ($startMs ms); clamping end time to start time.")
            startMs
        } else {
            rawEndMs
        }

        if (endMs - startMs > MAX_CUE_DURATION_MS) {
            warnings.add("Cue at line $lineNumber has unusually large duration (${(endMs - startMs) / 1000}s).")
        }

        return Pair(startMs, endMs)
    }

    private fun parseTimestampToken(token: String): Long? {
        if (token.isEmpty() || token.startsWith('-')) return null

        val normalized = token.replace(',', '.')
        val parts = normalized.split('.')
        val timePart = parts[0]
        val millisPart = if (parts.size > 1) parts[1] else "0"

        val millis = millisPart.padEnd(3, '0').take(3).toLongOrNull() ?: return null

        val segments = timePart.split(':')
        val (hours, minutes, seconds) = when (segments.size) {
            2 -> {
                val m = segments[0].toLongOrNull() ?: return null
                val s = segments[1].toLongOrNull() ?: return null
                Triple(0L, m, s)
            }
            3 -> {
                val h = segments[0].toLongOrNull() ?: return null
                val m = segments[1].toLongOrNull() ?: return null
                val s = segments[2].toLongOrNull() ?: return null
                Triple(h, m, s)
            }
            else -> return null
        }

        if (hours < 0L || minutes < 0L || seconds < 0L || millis < 0L) return null
        if (seconds >= 60L) return null

        val totalMs = (hours * 3600L + minutes * 60L + seconds) * 1000L + millis
        return if (totalMs >= 0L) totalMs else null
    }

    /**
     * Strips HTML-like formatting and VTT tags linearly without regex backtracking.
     */
    fun stripMarkup(text: String): String {
        if (text.isEmpty() || (!text.contains('<') && !text.contains('&'))) {
            return text
        }

        val sb = StringBuilder(text.length)
        var inTag = false
        for (i in text.indices) {
            val c = text[i]
            when {
                c == '<' -> inTag = true
                c == '>' -> inTag = false
                !inTag -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /**
     * Decodes standard and numeric HTML entities.
     */
    fun decodeHtmlEntities(text: String): String {
        if (!text.contains('&')) return text

        var result = text
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")

        // Decode numeric decimal entities &#\d+;
        if (result.contains("&#")) {
            val sb = StringBuilder(result.length)
            var i = 0
            while (i < result.length) {
                if (result.startsWith("&#", i)) {
                    val semi = result.indexOf(';', i + 2)
                    if (semi != -1 && semi - i <= 10) {
                        val numStr = result.substring(i + 2, semi)
                        val codePoint = if (numStr.startsWith("x", ignoreCase = true)) {
                            numStr.substring(1).toIntOrNull(16)
                        } else {
                            numStr.toIntOrNull()
                        }
                        if (codePoint != null && codePoint in 0x20..0x10FFFF) {
                            try {
                                sb.append(codePoint.toChar())
                                i = semi + 1
                                continue
                            } catch (_: Throwable) {
                            }
                        }
                    }
                }
                sb.append(result[i])
                i++
            }
            result = sb.toString()
        }

        return result
    }

    /**
     * Normalizes text for searchable full-text indexing: strips markup, decodes entities,
     * collapses whitespace, and trims.
     */
    fun normalizeSearchableText(rawText: String): String {
        val stripped = stripMarkup(rawText)
        val decoded = decodeHtmlEntities(stripped)

        val sb = StringBuilder(decoded.length)
        var lastWasWhitespace = false
        for (i in decoded.indices) {
            val c = decoded[i]
            if (c.isWhitespace()) {
                if (!lastWasWhitespace && sb.isNotEmpty()) {
                    sb.append(' ')
                    lastWasWhitespace = true
                }
            } else {
                sb.append(c)
                lastWasWhitespace = false
            }
        }
        return sb.toString().trim()
    }

    /**
     * Extracts speaker annotation if present from WebVTT voice tags `<v Speaker>`
     * or standard prefix annotations `Speaker: text` or `[Speaker] text`.
     */
    fun extractSpeaker(text: String): Pair<String?, String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Pair(null, text)

        // WebVTT <v Speaker Name>Text</v>
        if (trimmed.startsWith("<v", ignoreCase = true)) {
            val closingAngle = trimmed.indexOf('>')
            if (closingAngle != -1) {
                val speakerTag = trimmed.substring(2, closingAngle).trim()
                val body = trimmed.substring(closingAngle + 1).removeSuffix("</v>").trim()
                if (speakerTag.isNotEmpty()) {
                    return Pair(speakerTag, body)
                }
            }
        }

        // Bracketed speaker prefix: [Narrador] Texto
        if (trimmed.startsWith('[') && trimmed.contains(']')) {
            val closingBracket = trimmed.indexOf(']')
            if (closingBracket in 2..40) {
                val speaker = trimmed.substring(1, closingBracket).trim()
                val rest = trimmed.substring(closingBracket + 1).trim()
                if (speaker.isNotEmpty() && !speaker.any { it == '\n' || it == '\r' }) {
                    return Pair(speaker, rest)
                }
            }
        }

        // Colon-delimited speaker prefix: "Narrador: Texto"
        val colon = trimmed.indexOf(':')
        if (colon in 2..30 && !trimmed.substring(0, colon).contains('\n')) {
            val possibleSpeaker = trimmed.substring(0, colon).trim()
            if (possibleSpeaker.all { it.isLetterOrDigit() || it.isWhitespace() || it == '-' || it == '_' }) {
                val rest = trimmed.substring(colon + 1).trim()
                return Pair(possibleSpeaker, rest)
            }
        }

        return Pair(null, text)
    }
}
