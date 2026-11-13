package com.openyt.core.application

import com.openyt.core.domain.InvalidQueryReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TimedSearchQueryValidatorTest {

    @Test
    fun rejectsEmptyOrBlankQueries() {
        val emptyResult = TimedSearchQueryValidator.validate("")
        assertIs<TimedSearchValidationResult.Invalid>(emptyResult)
        assertEquals(InvalidQueryReason.EMPTY, emptyResult.reason)

        val whitespaceResult = TimedSearchQueryValidator.validate("   \t  \n  ")
        assertIs<TimedSearchValidationResult.Invalid>(whitespaceResult)
        assertEquals(InvalidQueryReason.EMPTY, whitespaceResult.reason)
    }

    @Test
    fun enforcesCharacterLengthLimit() {
        val exactLimit = "a".repeat(256)
        val validResult = TimedSearchQueryValidator.validate(exactLimit)
        assertIs<TimedSearchValidationResult.Valid>(validResult)
        assertEquals(exactLimit, validResult.query.rawQuery)

        val exceedsLimit = "a".repeat(257)
        val invalidResult = TimedSearchQueryValidator.validate(exceedsLimit)
        assertIs<TimedSearchValidationResult.Invalid>(invalidResult)
        assertEquals(InvalidQueryReason.EXCEEDS_LENGTH_LIMIT, invalidResult.reason)
    }

    @Test
    fun enforcesTokenCountLimit() {
        val eightTokens = "one two three four five six seven eight"
        val validResult = TimedSearchQueryValidator.validate(eightTokens)
        assertIs<TimedSearchValidationResult.Valid>(validResult)
        assertEquals(8, validResult.query.tokens.size)

        val nineTokens = "one two three four five six seven eight nine"
        val invalidResult = TimedSearchQueryValidator.validate(nineTokens)
        assertIs<TimedSearchValidationResult.Invalid>(invalidResult)
        assertEquals(InvalidQueryReason.EXCEEDS_TOKEN_LIMIT, invalidResult.reason)
    }

    @Test
    fun escapesQuotesOperatorsAndSqlSyntaxLiterally() {
        val specialQuery = "foo AND OR NOT * '; DROP"
        val result = TimedSearchQueryValidator.validate(specialQuery)
        assertIs<TimedSearchValidationResult.Valid>(result)

        val fts = result.query.ftsMatchQuery
        assertTrue(fts.contains("\"foo\""))
        assertTrue(fts.contains("\"AND\""))
        assertTrue(fts.contains("\"OR\""))
        assertTrue(fts.contains("\"NOT\""))
        assertTrue(fts.contains("\"DROP\""))
    }

    @Test
    fun stripsDiacriticsBidirectionallyForAccents() {
        val original = "Café com açúcar, maçã, café e música!"
        val folded = TimedSearchQueryValidator.stripDiacritics(original)
        assertEquals("cafe com acucar, maca, cafe e musica!", folded)
    }

    @Test
    fun extractsExcerptWithAccurateHighlightsOnShortText() {
        val cueText = "O melhor café com açúcar e música ao vivo."
        val terms = listOf("cafe", "musica")
        val excerpt = TimedSearchQueryValidator.extractExcerpt(cueText, terms)

        assertEquals(cueText, excerpt.text)
        assertEquals(2, excerpt.highlightRanges.size)

        val range1 = excerpt.highlightRanges[0]
        val match1 = cueText.substring(range1.start, range1.endInclusive + 1)
        assertEquals("café", match1)

        val range2 = excerpt.highlightRanges[1]
        val match2 = cueText.substring(range2.start, range2.endInclusive + 1)
        assertEquals("música", match2)
    }

    @Test
    fun extractsExcerptAndTruncatesLongTextWithEllipsis() {
        val longPrefix = "Palavras de introdução sem relevância que servem para preencher espaço inicial no texto da legenda. "
        val target = "Aqui está a palavra secreta procurada pelo usuário. "
        val longSuffix = "E mais um monte de texto subsequente para exceder o limite de cento e sessenta caracteres do resumo da legenda."
        val fullText = longPrefix + target + longSuffix

        val excerpt = TimedSearchQueryValidator.extractExcerpt(fullText, listOf("secreta"), maxChars = 80)

        assertTrue(excerpt.text.startsWith("..."))
        assertTrue(excerpt.text.endsWith("..."))
        assertTrue(excerpt.text.contains("secreta"))
        assertEquals(1, excerpt.highlightRanges.size)

        val range = excerpt.highlightRanges.single()
        val highlighted = excerpt.text.substring(range.start, range.endInclusive + 1)
        assertEquals("secreta", highlighted)
    }

    @Test
    fun handlesCjkAndUnspacedTokensSafely() {
        val cjkQuery = "这是一个测试 世界"
        val result = TimedSearchQueryValidator.validate(cjkQuery)
        assertIs<TimedSearchValidationResult.Valid>(result)

        assertEquals(2, result.query.tokens.size)
        assertEquals("这是一个测试", result.query.tokens[0])
        assertEquals("世界", result.query.tokens[1])
        assertEquals("\"这是一个测试\" \"世界\"", result.query.ftsMatchQuery)
    }
}
