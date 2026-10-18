package com.jas.codescanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeStitcherTest {

    private val words = listOf(
        "amount", "client", "ledger", "score", "window", "buffer", "parser", "token",
        "reader", "status", "filter", "result", "matrix", "vendor", "branch", "signal",
        "region", "header", "cursor", "symbol", "record", "budget", "socket", "format",
    )

    private val source: List<Row> = (0 until 60).map { i ->
        val a = words[i % words.size]
        val b = words[(i * 7 + 3) % 23]
        val c = words[(i * 5 + 11) % 19]
        when {
            i % 9 == 8 -> Row.BLANK
            i % 5 == 4 -> Row("}", (i % 3) * 4)
            else -> Row("val $a = $b.$c($i)", (i % 3) * 4)
        }
    }

    private fun expected(): String = source.joinToString("\n") { it.render() }

    /** What the camera sees: a window of rows, indents relative to the left-most visible line. */
    private fun frameAt(start: Int, height: Int): List<Row> {
        var rows = source.subList(start, minOf(source.size, start + height))
        while (rows.isNotEmpty() && rows.first().isBlank) rows = rows.drop(1)
        while (rows.isNotEmpty() && rows.last().isBlank) rows = rows.dropLast(1)
        val minIndent = rows.filter { !it.isBlank }.minOf { it.indent }
        return rows.map { if (it.isBlank) it else it.withIndent(it.indent - minIndent) }
    }

    private fun scroll(stitcher: CodeStitcher, step: Int, height: Int = 14) {
        var start = 0
        while (true) {
            stitcher.process(frameAt(start, height))
            if (start + height >= source.size) break
            start = minOf(start + step, source.size - height)
        }
        repeat(3) { stitcher.process(frameAt(start, height)) } // user stops on the last screen
    }

    @Test fun slowScrollCapturesEverythingOnce() {
        val s = CodeStitcher()
        scroll(s, step = 1)
        assertEquals(expected(), s.text())
    }

    @Test fun fastScrollCapturesEverythingOnce() {
        val s = CodeStitcher()
        scroll(s, step = 6)
        assertEquals(expected(), s.text())
    }

    @Test fun scrollingBackUpDoesNotDuplicate() {
        val s = CodeStitcher()
        for (start in listOf(0, 3, 6, 9, 12, 6, 3, 0, 3, 6, 9, 12, 15)) s.process(frameAt(start, 14))
        repeat(2) { s.process(frameAt(15, 14)) }
        val got = s.text().split('\n')
        val want = expected().split('\n').take(got.size)
        assertEquals(want, got)
        assertTrue(got.size >= 28)
    }

    @Test fun ocrNoiseStillMatches() {
        val s = CodeStitcher()
        s.load(frameAt(0, 14).joinToString("\n") { it.render() })
        // Same screen, but OCR misread some characters (O vs 0, l vs 1).
        val noisy = frameAt(2, 14).map { if (it.isBlank) it else Row(it.text.replace('0', 'O').replace("val ", "vaI "), it.indent) }
        val r = s.process(noisy)
        assertTrue(r.state != CodeStitcher.State.LOST)
    }

    @Test fun jumpWithNoOverlapIsReportedAsLost() {
        val s = CodeStitcher()
        s.process(frameAt(0, 10)); s.process(frameAt(0, 10))
        assertEquals(CodeStitcher.State.LOST, s.process(frameAt(40, 10)).state)
    }

    @Test fun frameBuilderRebuildsIndentAndBlankLines() {
        // Monospace: 10px per char, 30px line pitch, text 20px tall.
        fun seg(text: String, col: Int, line: Int) =
            OcrSegment(text, col * 10f, line * 30f, (col + text.length) * 10f, line * 30f + 20f)
        val rows = FrameBuilder.build(
            listOf(
                seg("def main():", 0, 0),
                seg("x = 1", 4, 1),
                seg("return x", 4, 3),
            )
        )
        assertEquals(listOf("def main():", "    x = 1", "", "    return x"), rows.map { it.render() })
    }
}
