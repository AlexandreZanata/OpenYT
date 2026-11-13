package com.openyt.core.application

import com.openyt.core.domain.SubtitleFormat
import com.openyt.core.domain.SubtitleParseException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimedSubtitleParserTest {

    @Test
    fun parsesStandardSrtWithPortugueseAndEnglishAccents() {
        val srt = """
            1
            00:00:01,000 --> 00:00:04,500
            A persistência e a dedicação são essenciais para o aprendizado da música.

            2
            00:00:05,000 --> 00:00:08,200
            Não há coração sem emoção no café com açúcar.

            3
            00:00:09,100 --> 00:00:12,000
            The naïve patron read the résumé while listening to rock 'n' roll.
        """.trimIndent()

        val track = TimedSubtitleParser.parse(srt, trackId = "track-pt-en")

        assertEquals(SubtitleFormat.SRT, track.format)
        assertEquals(3, track.totalCues)
        assertEquals("track-pt-en:00001", track.cues[0].cueId)
        assertEquals(1000L, track.cues[0].startMs)
        assertEquals(4500L, track.cues[0].endMs)
        assertEquals(3500L, track.cues[0].durationMs)
        assertTrue(track.cues[0].plainText.contains("música"))
        assertTrue(track.cues[0].plainText.contains("aprendizado"))

        assertEquals(5000L, track.cues[1].startMs)
        assertEquals(8200L, track.cues[1].endMs)
        assertTrue(track.cues[1].plainText.contains("coração"))
        assertTrue(track.cues[1].plainText.contains("açúcar"))

        assertEquals(9100L, track.cues[2].startMs)
        assertEquals(12000L, track.cues[2].endMs)
        assertTrue(track.cues[2].plainText.contains("naïve"))
        assertTrue(track.cues[2].plainText.contains("résumé"))
    }

    @Test
    fun parsesStandardWebVttWithMetadataNotesAndCueSettings() {
        val vtt = """
            WEBVTT
            Kind: captions
            Language: pt-BR

            NOTE
            Este é um comentário longo de cabeçalho
            que deve ser completamente ignorado.

            STYLE
            ::cue { color: #ffffff; }

            REGION
            id:reg1 width:40% lines:3

            cue-1
            00:01.000 --> 00:04.000 line:0 position:20% align:start
            Primeira fala do vídeo.

            NOTE comentário inline entre legendas

            00:05.500 --> 00:08.500 line:90% align:center
            Segunda fala sem identificador.
        """.trimIndent()

        val track = TimedSubtitleParser.parse(vtt, trackId = "vtt-meta")

        assertEquals(SubtitleFormat.VTT, track.format)
        assertEquals(2, track.totalCues)
        assertEquals(1000L, track.cues[0].startMs)
        assertEquals(4000L, track.cues[0].endMs)
        assertEquals("Primeira fala do vídeo.", track.cues[0].plainText)

        assertEquals(5500L, track.cues[1].startMs)
        assertEquals(8500L, track.cues[1].endMs)
        assertEquals("Segunda fala sem identificador.", track.cues[1].plainText)
    }

    @Test
    fun stripsHtmlAndVttMarkupSafelyAndDecodesEntities() {
        val srt = """
            1
            00:00:01,000 --> 00:00:04,000
            <b>Olá</b>, <i>mundo</i>! &amp; café &lt;3&gt; &#39;aspas&#39; &quot;citado&quot; &nbsp; espaço.

            2
            00:00:05,000 --> 00:00:08,000
            <font color="#ff0000">Alerta de perigo</font> com <c.yellow>destaque</c> e <script>sem_execucao()</script>.

            3
            00:00:09,000 --> 00:00:12,000
            <v Narrador>Fala do narrador com voz identificada.</v>
        """.trimIndent()

        val track = TimedSubtitleParser.parse(srt, trackId = "strip-test")

        assertEquals(3, track.totalCues)
        // Cue 1: markup stripped, HTML entities decoded
        val text1 = track.cues[0].plainText
        assertEquals("Olá, mundo! & café <3> 'aspas' \"citado\" espaço.", text1)
        assertTrue(track.cues[0].displayText.contains("<b>Olá</b>"))

        // Cue 2: script tags stripped, content preserved as plain text (no HTML execution)
        val text2 = track.cues[1].plainText
        assertEquals("Alerta de perigo com destaque e sem_execucao().", text2)

        // Cue 3: speaker extracted
        assertEquals("Narrador", track.cues[2].speaker)
        assertEquals("Fala do narrador com voz identificada.", track.cues[2].plainText)
    }

    @Test
    fun handlesMultilineAndOverlappingCuesWithDeterministicSorting() {
        val srt = """
            1
            00:00:05,000 --> 00:00:09,000
            Segunda legenda cronologicamente
            que tem duas linhas de texto.

            2
            00:00:01,000 --> 00:00:04,000
            Primeira legenda
            em múltiplas linhas
            para testar junção.

            3
            00:00:03,000 --> 00:00:07,000
            Legenda sobreposta que começa antes do término da primeira.
        """.trimIndent()

        val track = TimedSubtitleParser.parse(srt, trackId = "overlap-test")

        assertEquals(3, track.totalCues)
        // Ordered deterministically by startMs ASC
        assertEquals(1000L, track.cues[0].startMs)
        assertEquals(4000L, track.cues[0].endMs)
        assertEquals("Primeira legenda em múltiplas linhas para testar junção.", track.cues[0].plainText)
        assertEquals("Primeira legenda\nem múltiplas linhas\npara testar junção.", track.cues[0].displayText)

        assertEquals(3000L, track.cues[1].startMs)
        assertEquals(7000L, track.cues[1].endMs)

        assertEquals(5000L, track.cues[2].startMs)
        assertEquals(9000L, track.cues[2].endMs)
    }

    @Test
    fun appliesExplicitSourceAndLocalTimebaseOffsets() {
        val srt = """
            1
            00:00:02,000 --> 00:00:05,000
            Legenda com offset temporal.
        """.trimIndent()

        // Positive offset (+1500 ms)
        val shiftedForward = TimedSubtitleParser.parse(srt, trackId = "offset-pos", timebaseOffsetMs = 1500L)
        assertEquals(3500L, shiftedForward.cues[0].startMs)
        assertEquals(6500L, shiftedForward.cues[0].endMs)
        assertEquals(1500L, shiftedForward.timebaseOffsetMs)

        // Negative offset (-3000 ms), clamps start to 0
        val shiftedBackward = TimedSubtitleParser.parse(srt, trackId = "offset-neg", timebaseOffsetMs = -3000L)
        assertEquals(0L, shiftedBackward.cues[0].startMs)
        assertEquals(2000L, shiftedBackward.cues[0].endMs)
    }

    @Test
    fun recoversNonfatalCueAnomaliesWithDocumentedWarnings() {
        val srt = """
            1
            00:00:05,000 --> 00:00:02,000
            Legenda com tempo final antes do tempo inicial.
            2
            00:00:06,000 --> 00:00:09,000
            Legenda sem linha vazia de separação.

            3
            00:00:10,000 --> 00:00:12,000
            
            4
            00:00:15,000 --> 00:00:18,000
            Legenda final válida.
        """.trimIndent()

        val track = TimedSubtitleParser.parse(srt, trackId = "anomalies-test")

        assertTrue(track.warnings.isNotEmpty(), "Warnings must be recorded for nonfatal anomalies")
        assertEquals(4, track.totalCues)

        // Cue with end < start clamped
        val clampedCue = track.cues.first { it.originalIndex == 1 }
        assertEquals(5000L, clampedCue.startMs)
        assertEquals(5000L, clampedCue.endMs, "End time must be clamped to start time")

        // Empty text cue handled
        val emptyCue = track.cues.first { it.originalIndex == 3 }
        assertEquals("", emptyCue.plainText)
    }

    @Test
    fun handlesBomAndCrlfLineEndingsTransparently() {
        val raw = "\uFEFF1\r\n00:00:01,000 --> 00:00:03,000\r\nTexto com CRLF e BOM\r\n\r\n2\r\n00:00:04,000 --> 00:00:06,000\r\nSegunda linha\r\n"

        val track = TimedSubtitleParser.parse(raw, trackId = "bom-crlf")

        assertEquals(2, track.totalCues)
        assertEquals("Texto com CRLF e BOM", track.cues[0].plainText)
        assertEquals("Segunda linha", track.cues[1].plainText)
    }

    @Test
    fun extractsSpeakersFromVariousConventions() {
        val vtt = """
            WEBVTT

            1
            00:01.000 --> 00:03.000
            <v Alice>Hello, Bob!</v>

            2
            00:04.000 --> 00:06.000
            [Bob] Hi Alice, how are you?

            3
            00:07.000 --> 00:09.000
            Narrador: Era uma vez em uma terra distante...

            4
            00:10.000 --> 00:12.000
            Texto sem nenhum locutor específico.
        """.trimIndent()

        val track = TimedSubtitleParser.parse(vtt, trackId = "speakers")

        assertEquals(4, track.totalCues)
        assertEquals("Alice", track.cues[0].speaker)
        assertEquals("Hello, Bob!", track.cues[0].plainText)

        assertEquals("Bob", track.cues[1].speaker)
        assertEquals("Hi Alice, how are you?", track.cues[1].plainText)

        assertEquals("Narrador", track.cues[2].speaker)
        assertEquals("Era uma vez em uma terra distante...", track.cues[2].plainText)

        assertNull(track.cues[3].speaker)
        assertEquals("Texto sem nenhum locutor específico.", track.cues[3].plainText)
    }

    @Test
    fun rejectsMalformedAndOversizedPayloads() {
        // Empty content
        assertFailsWith<SubtitleParseException> {
            TimedSubtitleParser.parse("")
        }

        // WebVTT missing header signature
        assertFailsWith<SubtitleParseException> {
            TimedSubtitleParser.parse("Kind: captions\n00:01.000 --> 00:03.000\nTexto", formatHint = SubtitleFormat.VTT)
        }

        // Missing timestamp cues
        assertFailsWith<SubtitleParseException> {
            TimedSubtitleParser.parse("Apenas texto puro sem timestamps nem nada.")
        }

        // Oversized content rejection
        assertFailsWith<SubtitleParseException> {
            val hugeString = "1\n00:00:01,000 --> 00:00:02,000\nTexto\n".repeat(500)
            TimedSubtitleParser.parse(hugeString, maxCharacters = 100)
        }

        // Oversized cue count rejection
        assertFailsWith<SubtitleParseException> {
            val cuesString = (1..20).joinToString("\n\n") {
                "$it\n00:00:0${it % 10},000 --> 00:00:0${it % 10 + 1},000\nCue $it"
            }
            TimedSubtitleParser.parse(cuesString, maxCues = 5)
        }
    }
}
