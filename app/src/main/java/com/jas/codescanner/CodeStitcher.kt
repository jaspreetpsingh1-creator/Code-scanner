package com.jas.codescanner

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One OCR line, with its box in screen (view) coordinates. */
data class OcrSegment(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val height: Float get() = bottom - top
    val centerY: Float get() = (top + bottom) / 2f
}

/** One line of code: its text (no leading spaces) and its indent in columns. */
class Row(val text: String, val indent: Int) {
    /** Whitespace-free, OCR-confusion-tolerant form used only for matching. */
    val key: String = normalizeKey(text)
    val isBlank: Boolean get() = key.isEmpty()

    fun render(): String = if (isBlank) "" else " ".repeat(indent) + text
    fun withIndent(newIndent: Int) = Row(text, newIndent)

    companion object {
        val BLANK = Row("", 0)
    }
}

/** Collapse characters OCR often confuses so the same line matches across frames. */
fun normalizeKey(s: String): String {
    val sb = StringBuilder(s.length)
    for (c in s) {
        if (c.isWhitespace()) continue
        sb.append(
            when (c) {
                'O', 'o' -> '0'
                'l', 'I', 'i', '|', '!' -> '1'
                '‘', '’', '`', '´' -> '\''
                '“', '”' -> '"'
                else -> c.lowercaseChar()
            }
        )
    }
    return sb.toString()
}

/** 1.0 = identical, 0.0 = nothing alike (normalized edit distance). */
fun similarity(a: String, b: String): Double {
    if (a == b) return 1.0
    if (a.isEmpty() || b.isEmpty()) return 0.0
    val la = a.length
    val lb = b.length
    if (min(la, lb) < 0.7 * max(la, lb)) return 0.0 // can't reach the match threshold anyway
    var prev = IntArray(lb + 1) { it }
    var cur = IntArray(lb + 1)
    for (i in 1..la) {
        cur[0] = i
        val ca = a[i - 1]
        for (j in 1..lb) {
            val cost = if (ca == b[j - 1]) 0 else 1
            cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
        }
        val t = prev; prev = cur; cur = t
    }
    return 1.0 - prev[lb].toDouble() / max(la, lb)
}

private fun median(values: List<Float>): Float {
    if (values.isEmpty()) return 0f
    val s = values.sorted()
    return s[s.size / 2]
}

/**
 * Turns the OCR lines found inside the crop box into ordered rows of code:
 * merges pieces on the same visual line, rebuilds indentation from x-position,
 * and inserts blank lines where there is a vertical gap.
 */
object FrameBuilder {
    private class Geom(val text: String, val left: Float, val centerY: Float)

    fun build(segments: List<OcrSegment>): List<Row> {
        val segs = segments.filter { it.text.isNotBlank() && it.height > 0f }
        if (segs.isEmpty()) return emptyList()

        val hMed = median(segs.map { it.height })
        val charW = estimateCharWidth(segs, hMed)

        // 1. Group segments that sit on the same visual line.
        val groups = mutableListOf<MutableList<OcrSegment>>()
        for (s in segs.sortedBy { it.centerY }) {
            val g = groups.lastOrNull()
            if (g != null && sameLine(g, s)) g.add(s) else groups.add(mutableListOf(s))
        }

        // 2. Join each group left-to-right, keeping wide gaps as multiple spaces.
        val geoms = groups.map { g ->
            val parts = g.sortedBy { it.left }
            val sb = StringBuilder(parts[0].text.trim())
            for (k in 1 until parts.size) {
                val gap = parts[k].left - parts[k - 1].right
                val spaces = max(1, (gap / charW).roundToInt()).coerceAtMost(40)
                repeat(spaces) { sb.append(' ') }
                sb.append(parts[k].text.trim())
            }
            Geom(sb.toString(), parts[0].left, (g.minOf { it.top } + g.maxOf { it.bottom }) / 2f)
        }

        // 3. Line pitch, to detect blank lines.
        val diffs = (1 until geoms.size).map { geoms[it].centerY - geoms[it - 1].centerY }
        val normal = diffs.filter { it > 0f && it < hMed * 2.2f }
        val pitch = if (normal.isNotEmpty()) median(normal) else hMed * 1.3f

        // 4. Indentation relative to the left-most line in this frame (snapped to 2 columns).
        val minLeft = geoms.minOf { it.left }
        val rows = mutableListOf<Row>()
        geoms.forEachIndexed { idx, g ->
            if (idx > 0 && pitch > 0f) {
                val blanks = ((g.centerY - geoms[idx - 1].centerY) / pitch).roundToInt() - 1
                repeat(blanks.coerceIn(0, 3)) { rows.add(Row.BLANK) }
            }
            val cols = ((g.left - minLeft) / charW).roundToInt()
            val snapped = ((cols / 2f).roundToInt()) * 2
            rows.add(Row(g.text, snapped))
        }
        return rows
    }

    private fun estimateCharWidth(segs: List<OcrSegment>, hMed: Float): Float {
        val widths = segs.mapNotNull {
            val n = it.text.trim().length
            if (n >= 3) (it.right - it.left) / n else null
        }
        val w = median(widths)
        return if (w > 0f) w else max(1f, hMed * 0.55f)
    }

    private fun sameLine(g: List<OcrSegment>, s: OcrSegment): Boolean {
        val top = g.minOf { it.top }
        val bottom = g.maxOf { it.bottom }
        val overlap = min(bottom, s.bottom) - max(top, s.top)
        return overlap >= 0.5f * min(bottom - top, s.height)
    }
}

/**
 * Keeps the captured code and adds only the lines it hasn't seen yet.
 *
 * Each frame is aligned against the tail of what's already captured (like stitching
 * a panorama, but line by line). Lines below the overlap are new. A new line is only
 * committed once two consecutive frames agree on it, which filters out blurry frames.
 */
class CodeStitcher(
    private val window: Int = 80,
    private val matchThreshold: Double = 0.85,
) {
    enum class State { NO_TEXT, READING, TRACKING, ADDED, LOST }
    data class Result(val state: State, val added: Int, val total: Int)

    private class Alignment(val d: Int, val fIdx: List<Int>, val sIdx: List<Int>, val sim: Array<DoubleArray>)

    private val rows = mutableListOf<Row>()
    private val batches = ArrayDeque<Int>()
    private var pending: List<Row> = emptyList()

    @Synchronized fun lineCount(): Int = rows.size

    @Synchronized fun text(): String = rows.joinToString("\n") { it.render() }

    @Synchronized fun clear() {
        rows.clear(); batches.clear(); pending = emptyList()
    }

    @Synchronized fun resetPending() {
        pending = emptyList()
    }

    /** Removes the last batch of lines that was added. Returns how many lines were removed. */
    @Synchronized fun undo(): Int {
        val n = batches.removeLastOrNull() ?: return 0
        repeat(min(n, rows.size)) { rows.removeAt(rows.lastIndex) }
        while (rows.isNotEmpty() && rows.last().isBlank) rows.removeAt(rows.lastIndex)
        pending = emptyList()
        return n
    }

    /** Replaces the captured text (used for restoring and for manual edits). */
    @Synchronized fun load(text: String) {
        clear()
        for (raw in text.replace("\r", "").split('\n')) {
            val line = raw.replace("\t", "    ")
            val trimmed = line.trimStart()
            rows.add(if (trimmed.isBlank()) Row.BLANK else Row(trimmed.trimEnd(), line.length - trimmed.length))
        }
        while (rows.isNotEmpty() && rows.first().isBlank) rows.removeAt(0)
        while (rows.isNotEmpty() && rows.last().isBlank) rows.removeAt(rows.lastIndex)
    }

    @Synchronized fun process(frame: List<Row>): Result {
        val first = frame.indexOfFirst { !it.isBlank }
        if (first < 0) return Result(State.NO_TEXT, 0, rows.size)
        var view = frame.subList(first, frame.size)
        while (view.isNotEmpty() && view.last().isBlank) view = view.subList(0, view.size - 1)

        // Nothing captured yet: wait until two frames line up, then take the first one.
        if (rows.isEmpty()) {
            if (pending.isNotEmpty() && align(view, pending) != null) {
                val seed = pending
                rows.addAll(seed)
                batches.addLast(seed.size)
                pending = emptyList()
                val more = process(view)
                val seeded = seed.count { !it.isBlank }
                return Result(State.ADDED, seeded + more.added, rows.size)
            }
            pending = view
            return Result(State.READING, 0, rows.size)
        }

        val a = align(view, rows) ?: run {
            pending = emptyList()
            return Result(State.LOST, 0, rows.size)
        }
        val fn = a.fIdx.size
        val wn = a.sIdx.size
        val lastI = min(fn - 1, wn - 1 - a.d) // last frame line that overlaps what we have
        if (lastI >= fn - 1) {
            pending = emptyList()
            return Result(State.TRACKING, 0, rows.size)
        }

        // Carry indentation over from the overlap, so it's consistent across frames.
        val diffs = (max(0, -a.d)..lastI)
            .filter { a.sim[it][it + a.d] >= matchThreshold }
            .map { rows[a.sIdx[it + a.d]].indent - view[a.fIdx[it]].indent }
            .sorted()
        val offset = if (diffs.isEmpty()) 0 else diffs[diffs.size / 2]

        val newPart = view.subList(a.fIdx[lastI] + 1, view.size)
            .map { if (it.isBlank) it else it.withIndent(max(0, it.indent + offset)) }
        return confirm(newPart)
    }

    /** Commits the new lines that the previous frame also saw. */
    private fun confirm(newPart: List<Row>): Result {
        val newNB = newPart.indices.filter { !newPart[it].isBlank }
        if (newNB.isEmpty()) {
            pending = emptyList()
            return Result(State.TRACKING, 0, rows.size)
        }
        val pendNB = pending.filter { !it.isBlank }
        var k = 0
        while (k < newNB.size && k < pendNB.size &&
            similarity(newPart[newNB[k]].key, pendNB[k].key) >= matchThreshold
        ) k++

        if (k == 0) {
            pending = newPart
            return Result(State.READING, 0, rows.size)
        }
        val commitCount = newNB[k - 1] + 1
        rows.addAll(newPart.subList(0, commitCount))
        batches.addLast(commitCount)
        pending = newPart.subList(commitCount, newPart.size).toList()
        return Result(State.ADDED, k, rows.size)
    }

    /**
     * Finds where [frame] sits relative to the end of [stored]. Returns the shift d such that
     * frame non-blank line i corresponds to window non-blank line i + d, or null if no
     * convincing overlap exists.
     */
    private fun align(frame: List<Row>, stored: List<Row>): Alignment? {
        val fIdx = frame.indices.filter { !frame[it].isBlank }
        val sIdx = stored.indices.filter { !stored[it].isBlank }.takeLast(window)
        val fn = fIdx.size
        val wn = sIdx.size
        if (fn == 0 || wn == 0) return null
        val sim = Array(fn) { i -> DoubleArray(wn) { j -> similarity(frame[fIdx[i]].key, stored[sIdx[j]].key) } }

        var bestD: Int? = null
        var bestScore = 0.0
        for (d in -(fn - 1) until wn) {
            val iStart = max(0, -d)
            val iEnd = min(fn - 1, wn - 1 - d)
            if (iStart > iEnd) continue
            var overlap = 0
            var matches = 0
            var score = 0.0
            for (i in iStart..iEnd) {
                overlap++
                if (sim[i][i + d] >= matchThreshold) {
                    matches++
                    // Short lines like "}" are weak evidence; long lines are strong.
                    score += min(frame[fIdx[i]].key.length, 12) / 12.0
                }
            }
            if (matches == 0) continue
            val ratio = matches.toDouble() / overlap
            // ">=" so that, on a tie, the alignment closest to the end wins.
            if (ratio >= 0.6 && score >= 1.0 && score >= bestScore) {
                bestScore = score
                bestD = d
            }
        }
        return bestD?.let { Alignment(it, fIdx, sIdx, sim) }
    }
}
