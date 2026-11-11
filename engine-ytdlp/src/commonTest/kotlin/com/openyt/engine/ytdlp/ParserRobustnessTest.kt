package com.openyt.engine.ytdlp

import kotlin.random.Random
import kotlin.test.Test

class ParserRobustnessTest {
    @Test fun malformedExternalOutputNeverEscapesAsAnException() {
        val random = Random(20261111)
        repeat(2_000) {
            val input = CharArray(random.nextInt(0, 300)) { random.nextInt(0, 256).toChar() }.concatToString()
            YtDlpJsonParser.parseInspection(input)
            YtDlpProgressParser.parse(input)
            YtDlpOutputParser.extractFilePath(input)
            YtDlpErrorMapper.fromToolOutput(input)
        }
    }
}
