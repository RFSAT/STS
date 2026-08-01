package com.rfsat.sts

import com.rfsat.sts.detect.LumaFrame
import com.rfsat.sts.detect.PunctureCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Pins the one property that separates a shot from a picture of one: a
 * puncture gets steadily lighter outwards from its centre, and printing does
 * not. The numbers in the names are the ones measured on the user's card.
 */
class PunctureCheckTest {

    private val paper = 202
    private val black = 14

    /** A frame of paper with one synthetic feature painted on it. */
    private fun frame(
        w: Int = 120, h: Int = 120, background: Int = 202,
        paint: (Double) -> Int?
    ): LumaFrame {
        val d = ByteArray(w * h) { background.toByte() }
        for (j in 0 until h) for (i in 0 until w) {
            val r = hypot(i - w / 2.0, j - h / 2.0)
            paint(r)?.let { d[j * w + i] = it.toByte() }
        }
        return LumaFrame(w, h, d)
    }

    /**
     * Paints one value per BAND, using the same equal-area boundaries the
     * check itself uses.
     *
     * Painting by radius instead was how the first version of these tests was
     * written, and every one of them was wrong: equal-area bands are narrow
     * far out and wide in the middle, so a feature drawn at a chosen radius
     * lands across two bands and its shape is averaged away. A test that
     * describes the profile it means to describe cannot fail for that reason.
     */
    private fun byBand(gauge: Double, background: Int, values: IntArray): LumaFrame {
        val span = gauge * PunctureCheck.SPAN_GAUGES
        return frame(background = background) { r ->
            if (r >= span) null else {
                val f = r / span
                values[((f * f) * PunctureCheck.BANDS).toInt().coerceIn(0, PunctureCheck.BANDS - 1)]
            }
        }
    }

    /** Dark core fading to paper: what a pellet leaves. */
    private fun hole(gauge: Double) = frame { r ->
        if (r > gauge * 0.75) null
        else (104 + (paper - 104) * (r / (gauge * 0.75))).roundToInt()
    }

    @Test
    fun `a puncture profile is accepted`() {
        val g = 24.0
        assertTrue(PunctureCheck.isPuncture(hole(g), 60.0, 60.0, g, inBlack = false))
    }

    @Test
    fun `a printed roundel with a light centre is refused`() {
        // The ISSF mark: a dark annulus around a pale field. Compact, round,
        // a pellet across, and not a hole.
        val g = 24.0
        // Pale field, dark annulus, then paper. Measured on the real roundel:
        // monotonic 0.71, contrast 15.
        val f = byBand(g, paper, intArrayOf(184, 177, 135, 139, 156, 157, 186, 199))
        val p = PunctureCheck.profile(f, 60.0, 60.0, g, inBlack = false)!!
        assertTrue("monotonic was ${p.monotonic}", p.monotonic < 0.85)
        assertFalse(PunctureCheck.isPuncture(f, 60.0, 60.0, g, inBlack = false))
    }

    @Test
    fun `a flat dark disc with no contrast gradient is refused`() {
        val g = 24.0
        val f = frame { r -> if (r < g * 0.7) 196 else null }   // barely darker than paper
        assertFalse(PunctureCheck.isPuncture(f, 60.0, 60.0, g, inBlack = false))
    }

    @Test
    fun `a hole in the aiming mark is accepted with the polarity flipped`() {
        // Inside the black the evidence arrives with the opposite sign: the
        // hole is BRIGHTER than the mark. The shipped detector missed exactly
        // one shot inside the scoring area on the user's card, and it was
        // this one — the 9, in the black.
        val g = 24.0
        val f = frame(background = black) { r ->
            if (r > g * 0.75) null
            else (132 - (132 - black) * (r / (g * 0.75))).roundToInt()
        }
        assertTrue(PunctureCheck.isPuncture(f, 60.0, 60.0, g, inBlack = true))
        // and the same frame read with the wrong polarity is refused, which is
        // what a single global rule would have done to it
        assertFalse(PunctureCheck.isPuncture(f, 60.0, 60.0, g, inBlack = false))
    }

    @Test
    fun `outside the scoring area the test is stricter`() {
        val g = 24.0
        // TWO bands against the trend out of seven steps is 0.714: over the
        // ordinary floor of 0.6, under the strict floor of 0.75 that applies
        // where every candidate competes with print rather than paper.
        val f = byBand(g, paper, intArrayOf(60, 100, 90, 140, 130, 185, 195, 202))
        val p = PunctureCheck.profile(f, 60.0, 60.0, g, inBlack = false)!!
        assertEquals(5.0 / 7.0, p.monotonic, 1e-9)
        assertTrue(PunctureCheck.isPuncture(f, 60.0, 60.0, g, false, outsideScoringArea = false))
        assertFalse(PunctureCheck.isPuncture(f, 60.0, 60.0, g, false, outsideScoringArea = true))
    }

    @Test
    fun `contrast is the test that does the work, not monotonicity`() {
        // Measured on the punched card: every printed feature — ring lines,
        // numerals, the black field, blank paper — scores 1.00 monotonic,
        // exactly as real holes do, so monotonicity separates a hole from
        // print hardly at all. What separates them is contrast: holes 44 to
        // 98 levels, print under 5. This pins that a perfectly smooth,
        // perfectly monotonic patch of paper is still refused.
        val g = 24.0
        val flat = byBand(g, paper, intArrayOf(198, 199, 199, 200, 200, 201, 201, 202))
        val p = PunctureCheck.profile(flat, 60.0, 60.0, g, inBlack = false)!!
        assertEquals(1.0, p.monotonic, 1e-9)
        assertTrue("contrast was ${p.contrastLevels}", p.contrastLevels < 10.0)
        assertFalse(PunctureCheck.isPuncture(flat, 60.0, 60.0, g, inBlack = false))
    }

    @Test
    fun `bands are equal in area so each holds the same count at any size`() {
        // Equal-WIDTH bands starve the innermost one: the detector works at
        // eight pixels per gauge, so the whole profile is ten pixels across.
        val g = 8.0
        val f = hole(g)
        val p = PunctureCheck.profile(f, 60.0, 60.0, g, inBlack = false)
        assertTrue("a 8 px gauge must still yield a profile", p != null)
        assertEquals(PunctureCheck.BANDS, p!!.bands.size)
    }

    @Test
    fun `a window with too few real pixels is declined rather than guessed`() {
        // Hard against the corner of a small frame there is not enough of a
        // window left to describe, and saying so is better than describing it
        // from the handful of pixels that happen to be there.
        val g = 24.0
        val tiny = LumaFrame(6, 6, ByteArray(36) { paper.toByte() })
        assertNull(PunctureCheck.profile(tiny, 3.0, 3.0, g, inBlack = false))
    }

    @Test
    fun `blank paper is refused for want of contrast, not accepted for being smooth`() {
        // Featureless paper is perfectly monotonic — every band identical —
        // so monotonicity alone would accept it. The contrast floor is what
        // stops that, and this pins the pair working together.
        val g = 24.0
        val blank = LumaFrame(120, 120, ByteArray(120 * 120) { paper.toByte() })
        val p = PunctureCheck.profile(blank, 60.0, 60.0, g, inBlack = false)!!
        assertEquals(1.0, p.monotonic, 1e-9)
        assertEquals(0.0, p.contrastLevels, 1e-9)
        assertFalse(PunctureCheck.isPuncture(blank, 60.0, 60.0, g, inBlack = false))
    }
}
