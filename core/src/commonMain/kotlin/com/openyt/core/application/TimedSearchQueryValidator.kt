package com.openyt.core.application

import com.openyt.core.domain.InvalidQueryReason
import com.openyt.core.domain.TextHighlightRange
import com.openyt.core.domain.TimedSearchExcerpt

sealed interface TimedSearchValidationResult {
    data class Valid(val query: ValidatedTimedSearchQuery) : TimedSearchValidationResult
    data class Invalid(val reason: InvalidQueryReason, val message: String) : TimedSearchValidationResult
}

data class ValidatedTimedSearchQuery(
    val rawQuery: String,
    val normalizedQuery: String,
    val tokens: List<String>,
    val ftsMatchQuery: String,
)

object TimedSearchQueryValidator {
    const val MAX_QUERY_LENGTH = 256
    const val MAX_TOKEN_COUNT = 8
    private val WHITESPACE_REGEX = Regex("\\s+")
    private val WORD_REGEX = Regex("[\\p{L}\\p{N}_]+")

    fun validate(rawQuery: String): TimedSearchValidationResult {
        if (rawQuery.isBlank()) {
            return TimedSearchValidationResult.Invalid(
                reason = InvalidQueryReason.EMPTY,
                message = "Search query cannot be blank",
            )
        }

        if (rawQuery.length > MAX_QUERY_LENGTH) {
            return TimedSearchValidationResult.Invalid(
                reason = InvalidQueryReason.EXCEEDS_LENGTH_LIMIT,
                message = "Search query length (${rawQuery.length}) exceeds maximum limit of $MAX_QUERY_LENGTH characters",
            )
        }

        val rawTokens = rawQuery.trim().split(WHITESPACE_REGEX).filter { it.isNotEmpty() }
        if (rawTokens.isEmpty()) {
            return TimedSearchValidationResult.Invalid(
                reason = InvalidQueryReason.EMPTY,
                message = "Search query contains no searchable tokens",
            )
        }

        if (rawTokens.size > MAX_TOKEN_COUNT) {
            return TimedSearchValidationResult.Invalid(
                reason = InvalidQueryReason.EXCEEDS_TOKEN_LIMIT,
                message = "Search query contains ${rawTokens.size} tokens, exceeding maximum limit of $MAX_TOKEN_COUNT tokens",
            )
        }

        val words = WORD_REGEX.findAll(rawQuery).map { it.value }.toList()
        if (words.isEmpty()) {
            return TimedSearchValidationResult.Invalid(
                reason = InvalidQueryReason.EMPTY,
                message = "Search query contains no searchable terms",
            )
        }

        val ftsMatchQuery = words.joinToString(" ") { escapeFtsToken(it) }
        val normalizedQuery = words.joinToString(" ")

        return TimedSearchValidationResult.Valid(
            ValidatedTimedSearchQuery(
                rawQuery = rawQuery,
                normalizedQuery = normalizedQuery,
                tokens = words,
                ftsMatchQuery = ftsMatchQuery,
            ),
        )
    }

    fun escapeFtsToken(token: String): String {
        val inner = token.replace("\"", "\"\"")
        return "\"$inner\""
    }

    fun stripDiacritics(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            sb.append(stripDiacritic(ch))
        }
        return sb.toString()
    }

    private fun stripDiacritic(ch: Char): Char = when (ch) {
        'á', 'à', 'â', 'ã', 'ä', 'å', 'ā', 'ă', 'ą' -> 'a'
        'Á', 'À', 'Â', 'Ã', 'Ä', 'Å', 'Ā', 'Ă', 'Ą' -> 'a'
        'é', 'è', 'ê', 'ë', 'ē', 'ĕ', 'ė', 'ę', 'ě' -> 'e'
        'É', 'È', 'Ê', 'Ë', 'Ē', 'Ĕ', 'Ė', 'Ę', 'Ě' -> 'e'
        'í', 'ì', 'î', 'ï', 'ī', 'ĭ', 'į', 'ı' -> 'i'
        'Í', 'Ì', 'Î', 'Ï', 'Ī', 'Ĭ', 'Į', 'İ' -> 'i'
        'ó', 'ò', 'ô', 'õ', 'ö', 'ø', 'ō', 'ŏ', 'ő' -> 'o'
        'Ó', 'Ò', 'Ô', 'Õ', 'Ö', 'Ø', 'Ō', 'Ŏ', 'Ő' -> 'o'
        'ú', 'ù', 'û', 'ü', 'ū', 'ŭ', 'ů', 'ű', 'ų' -> 'u'
        'Ú', 'Ù', 'Û', 'Ü', 'Ū', 'Ŭ', 'Ů', 'Ű', 'Ų' -> 'u'
        'ç', 'ć', 'ĉ', 'ċ', 'č' -> 'c'
        'Ç', 'Ć', 'Ĉ', 'Ċ', 'Č' -> 'c'
        'ñ', 'ń', 'ņ', 'ň' -> 'n'
        'Ñ', 'Ń', 'Ņ', 'Ň' -> 'n'
        'ý', 'ÿ', 'ŷ' -> 'y'
        'Ý', 'Ÿ', 'Ŷ' -> 'y'
        else -> ch.lowercaseChar()
    }

    fun extractExcerpt(
        plainText: String,
        searchTerms: List<String>,
        maxChars: Int = 160,
    ): TimedSearchExcerpt {
        if (plainText.isEmpty()) {
            return TimedSearchExcerpt(text = "", highlightRanges = emptyList())
        }

        val foldedText = stripDiacritics(plainText)
        val ranges = mutableListOf<IntRange>()

        for (term in searchTerms) {
            val foldedTerm = stripDiacritics(term.trim())
            if (foldedTerm.isEmpty()) continue

            var startIndex = 0
            while (startIndex < foldedText.length) {
                val found = foldedText.indexOf(foldedTerm, startIndex)
                if (found == -1) break
                val end = found + foldedTerm.length - 1
                ranges.add(found..end)
                startIndex = found + foldedTerm.length
            }
        }

        // Sort ranges and merge overlaps
        val sortedRanges = ranges.sortedBy { it.first }
        val mergedRanges = mutableListOf<IntRange>()
        for (r in sortedRanges) {
            if (mergedRanges.isEmpty()) {
                mergedRanges.add(r)
            } else {
                val last = mergedRanges.last()
                if (r.first <= last.last + 1) {
                    mergedRanges[mergedRanges.lastIndex] = last.first..maxOf(last.last, r.last)
                } else {
                    mergedRanges.add(r)
                }
            }
        }

        if (plainText.length <= maxChars) {
            return TimedSearchExcerpt(
                text = plainText,
                highlightRanges = mergedRanges.map { TextHighlightRange(it.first, it.last) },
            )
        }

        val firstMatch = mergedRanges.firstOrNull()
        val windowStart = if (firstMatch != null) {
            (firstMatch.first - 30).coerceIn(0, (plainText.length - maxChars).coerceAtLeast(0))
        } else {
            0
        }
        val windowEnd = (windowStart + maxChars).coerceAtMost(plainText.length)

        val prefix = if (windowStart > 0) "..." else ""
        val suffix = if (windowEnd < plainText.length) "..." else ""
        val snippet = prefix + plainText.substring(windowStart, windowEnd) + suffix

        val offset = prefix.length - windowStart
        val adjustedRanges = mergedRanges
            .filter { it.first >= windowStart && it.last < windowEnd }
            .map { TextHighlightRange(it.first + offset, it.last + offset) }

        return TimedSearchExcerpt(
            text = snippet,
            highlightRanges = adjustedRanges,
        )
    }
}
