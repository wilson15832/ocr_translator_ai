package com.example.ocr_translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the in-place sizing arithmetic.
 *
 * The figures are lifted from two real captures of the same game screen, logged on the device by
 * the `inPlace ocr=… quad=… ink=… slot=… size=…` dump. Every case below is a bug that shipped:
 * they are here because each was diagnosed by measuring pixels off a screenshot, twice with a
 * confident explanation that turned out to be wrong.
 *
 * Where a test names a "was" value, that is what the previous implementation produced.
 */
class LineMetricsTest {

    // A paragraph of five lines from the Skadi screen. The 61 is 「山の女神───スカディ（スカジ）…」,
    // whose box came back 23px taller than either neighbour; the 35 is 「ている。」, a short line of
    // small kana. Tops are absolute screen coordinates from the same log.
    private val paragraphHeights = listOf(38, 61, 35, 39, 38)
    private val paragraphTops = listOf(478, 510, 571, 612, 658)

    // The title block from the Phantom screen: name, reading, class line, each smaller than the
    // last. Two of the three shared a group; all three must keep their own size.
    private val titleHeights = listOf(54, 48, 42)

    @Test
    fun `median takes the midpoint of an even-sized list`() {
        // Was sorted[size / 2], which returned the upper sample: 50 for this pair, biasing every
        // even-sized group's pitch upward by half the spread.
        assertEquals(42, LineMetrics.median(listOf(50, 35)))
        assertEquals(42, LineMetrics.median(listOf(35, 50)))
    }

    @Test
    fun `median of an odd-sized list is the middle sample`() {
        assertEquals(39, LineMetrics.median(listOf(38, 39, 61)))
    }

    @Test
    fun `median of nothing is zero rather than a crash`() {
        assertEquals(0, LineMetrics.median(emptyList()))
        assertEquals(0, LineMetrics.percentile(emptyList(), 0.75f))
    }

    @Test
    fun `body height ignores a single inflated box`() {
        // The upper quartile sits with the four lines that agree, not with the outlier. Taking the
        // max here is what started the whole failure chain: 61 instead of 39.
        assertEquals(39, LineMetrics.bodyHeight(paragraphHeights))
    }

    @Test
    fun `body height of a title block is its own largest line`() {
        // Nothing to reject here — the sizes genuinely differ, and the tallest is the body.
        assertEquals(54, LineMetrics.bodyHeight(titleHeights))
    }

    @Test
    fun `a short ordinary line is not mistaken for ruby`() {
        // 「ている。」 against a body of 39: threshold 23.4, so it stays. Against the old max-based
        // reference of 61 the threshold was 36.6 and it was dropped, which left a line-sized hole
        // in the paragraph and sent the next line's slot across it.
        val body = LineMetrics.bodyHeight(paragraphHeights)
        assertFalse(LineMetrics.isFurigana(35, body))
        assertTrue("the old max-based threshold would have dropped it", 35 < 61 * LineMetrics.FURIGANA_RATIO)
    }

    @Test
    fun `ruby is still recognised`() {
        // Furigana runs about half the body size, comfortably under the threshold.
        assertTrue(LineMetrics.isFurigana(20, 40))
        assertTrue(LineMetrics.isFurigana(23, 40))
        assertFalse(LineMetrics.isFurigana(25, 40))
    }

    @Test
    fun `ink height clips an inflated line towards its neighbours`() {
        val body = LineMetrics.bodyHeight(paragraphHeights)
        assertEquals(42, LineMetrics.inkHeight(61, body))
    }

    @Test
    fun `ink height leaves an ordinary line alone`() {
        val body = LineMetrics.bodyHeight(paragraphHeights)
        assertEquals(38, LineMetrics.inkHeight(38, body))
        assertEquals(39, LineMetrics.inkHeight(39, body))
    }

    @Test
    fun `title lines keep their own sizes`() {
        // The regression that prompted splitting size from spacing: two of these shared a group and
        // a single group figure rendered them identically. Each must come through distinct.
        val body = LineMetrics.bodyHeight(titleHeights)
        val sizes = titleHeights.map { LineMetrics.inkHeight(it, body) }
        assertEquals(listOf(54, 48, 42), sizes)
        assertEquals("no two title lines may share a size", sizes.size, sizes.distinct().size)
    }

    @Test
    fun `slot steps over the displaced top of an inflated line`() {
        // Gaps are 32, 61, 41, 46. The 32 and the 61 are the same defect seen twice — the line's
        // top is pushed up, shortening the gap above it and lengthening the one below — and the
        // median steps over both. Fitting to the raw 61 gave slot 97 and text at 85px, against
        // neighbours at 33-40.
        assertEquals(43, LineMetrics.lineSlot(paragraphTops, paragraphHeights))
    }

    @Test
    fun `slot for a lone line comes from its own height`() {
        // A group of one has no gap to measure. 54 * 1.25 = 67, which is what the device logged
        // for the standalone 「SERVANT」 line.
        assertEquals(67, LineMetrics.lineSlot(listOf(40), listOf(54)))
    }

    @Test
    fun `slot for a two-line group is the gap between them`() {
        // Reading and class line, tops 118 and 184, heights 48 and 42: body 48, gap 66, within
        // [48, 76]. The device logged slot=66 for both.
        assertEquals(66, LineMetrics.lineSlot(listOf(118, 184), listOf(48, 42)))
    }

    @Test
    fun `slot is never shorter than the glyphs it has to cover`() {
        // Overlapping tops would otherwise hand back a slot that can't hide the original.
        assertEquals(40, LineMetrics.lineSlot(listOf(100, 110), listOf(40, 40)))
    }

    @Test
    fun `slot is capped when the grouping reached across a blank line`() {
        // Body 40, so the ceiling is 64 however far apart the tops are. Without it the line is
        // fitted to the empty space below it and comes back several times too large.
        assertEquals(64, LineMetrics.lineSlot(listOf(100, 400), listOf(40, 40)))
    }

    @Test
    fun `tops need not arrive in order`() {
        val shuffled = paragraphTops.reversed()
        assertEquals(
            LineMetrics.lineSlot(paragraphTops, paragraphHeights),
            LineMetrics.lineSlot(shuffled, paragraphHeights)
        )
    }

    @Test
    fun `duplicate tops do not produce a zero slot`() {
        // Two blocks side by side on one line share a top; the zero gap must not become the pitch.
        assertEquals(50, LineMetrics.lineSlot(listOf(100, 100), listOf(40, 40)))
    }
}
