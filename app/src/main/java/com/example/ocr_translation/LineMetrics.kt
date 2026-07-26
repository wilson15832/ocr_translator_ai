package com.example.ocr_translation

/**
 * The arithmetic behind in-place layout: given a group of OCR lines, how big to set the type and
 * how tall each cover has to be.
 *
 * Pure, and separate from [OverlayService] for one reason — so it can be tested. Every decision in
 * here was got wrong at least once, and each time it was diagnosed by measuring pixels off a
 * screenshot, which took several rounds and produced two confident explanations that turned out to
 * be false. The inputs are a handful of integers; asserting on them directly is both faster and
 * conclusive.
 *
 * Nothing here imports from `android.*`. That is what keeps the tests plain JVM tests: `Rect` and
 * friends are signature-only stubs under unit test and would quietly answer 0 to everything.
 *
 * Two measurements come out of a group and they are deliberately not the same one:
 *
 *  - **ink height** — a line's own height, which sets its type size. Per line, because a group can
 *    legitimately hold more than one size.
 *  - **slot** — the distance a cover has to span to tile the original, which is a group figure,
 *    because a single line's top and bottom are both moved by whatever punctuation it contains.
 */
object LineMetrics {

    /** Blocks shorter than this share of the group's body height are treated as furigana. */
    const val FURIGANA_RATIO = 0.6f

    /** Where in a group's sorted line heights the body text's own height is read off. */
    const val BODY_HEIGHT_PERCENTILE = 0.75f

    /**
     * How far one line may exceed the group's body height before it stops being read as that line's
     * size. Punctuation that reaches past the kana — 「（）」, 「───」 — inflates the box without
     * changing the type, and it is always a lone outlier among lines that agree.
     */
    const val HEIGHT_OUTLIER_MAX = 1.1f

    /**
     * Ceiling on the slot as a multiple of the body height. Leading is a fraction of the glyph
     * height, never a multiple of it, so anything past this is the gap below the line rather than
     * the line.
     */
    const val SLOT_MAX_OVER_HEIGHT = 1.6f

    /** Slot for a group of one, which has no gap to measure. */
    const val LONE_LINE_SLOT_RATIO = 1.25f

    /**
     * True median.
     *
     * `sorted[size / 2]` — which this used to be — returns the *upper* of the two middle samples on
     * an even-sized list, so a two-line group took the larger of its two measurements every time.
     * That biased the pitch upward, and an inflated pitch inflates both the font and the box.
     */
    fun median(values: List<Int>): Int {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    /** The value [fraction] of the way through [values] in sorted order; 0.5 is the median. */
    fun percentile(values: List<Int>, fraction: Float): Int {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        val index = Math.round((sorted.size - 1) * fraction).coerceIn(sorted.indices)
        return sorted[index]
    }

    /**
     * Representative height of the *body* text in a group, robust at both ends.
     *
     * The upper quartile rather than the max or the median: the max is one bracket away from being
     * wrong, and the median sits between the two sizes when a group is half ruby. At three quarters
     * of the way up, a lone inflated box is still an outlier while ruby — never more than about
     * half the lines — stays below it.
     */
    fun bodyHeight(heights: List<Int>): Int =
        percentile(heights, BODY_HEIGHT_PERCENTILE).coerceAtLeast(1)

    /** Whether a line of [height] is ruby against a group whose body measures [bodyHeight]. */
    fun isFurigana(height: Int, bodyHeight: Int): Boolean = height < bodyHeight * FURIGANA_RATIO

    /**
     * The height to fit a line's type to: its own, capped against the group's body height.
     *
     * The cap is what makes per-line sizing safe. A line inflated by punctuation is a lone outlier
     * against neighbours that agree with each other, so the body height sits with the neighbours
     * and clips it; a title genuinely larger than the lines under it *is* the top of the group, so
     * the body height sits at its own size and it passes through untouched.
     */
    fun inkHeight(lineHeight: Int, bodyHeight: Int): Int =
        lineHeight
            .coerceAtMost((bodyHeight * HEIGHT_OUTLIER_MAX).toInt())
            .coerceAtLeast(1)

    /**
     * Line pitch — the distance from one line's top to the next's — which is what a cover has to be
     * to tile the original.
     *
     * Sizing covers to the OCR height instead was the source of the drift: an OCR box hugs its
     * glyphs, so it is shorter than the pitch by the line spacing, and every cover ended up that
     * spacing short of its neighbour while its padding pushed it past the next line's top.
     *
     * The median of the gaps, so one displaced top can't carry it: punctuation that pushes a line's
     * top up shortens the gap above it and lengthens the one below by the same amount, and the
     * median steps over both.
     *
     * @param tops each line's top edge, in any order.
     */
    fun typicalPitch(tops: List<Int>, bodyHeight: Int): Int {
        val fallback = (bodyHeight * LONE_LINE_SLOT_RATIO).toInt()
        val sorted = tops.sorted()
        if (sorted.size < 2) return fallback
        val gaps = sorted.zipWithNext { a, b -> b - a }.filter { it > 0 }
        if (gaps.isEmpty()) return fallback
        return median(gaps).coerceAtLeast(bodyHeight)
    }

    /**
     * The vertical slot every line in a group gets: [typicalPitch], held between the body height
     * and a multiple of it.
     *
     * The lower bound is what a cover has to be to hide the glyphs at all; the upper one catches a
     * pitch stretched by a grouping that reached across a blank line.
     */
    fun lineSlot(tops: List<Int>, heights: List<Int>): Int {
        val height = bodyHeight(heights)
        return typicalPitch(tops, height)
            .coerceIn(height, (height * SLOT_MAX_OVER_HEIGHT).toInt().coerceAtLeast(height))
    }
}
