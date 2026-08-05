package com.rfsat.sts

import com.rfsat.sts.detect.AspectCorrection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stretch that makes the printed rings round again.
 *
 * The test that matters is the last kind: not "does it return the number I
 * expect" but "do the rings actually come out round if this is applied". The
 * arithmetic is short enough to get wrong by inspection and long enough that
 * nobody would notice for weeks — the symptom would be a card that scores
 * slightly wrong in one direction.
 */
class AspectCorrectionTest {

    @Test
    fun `a round fit needs nothing`() {
        assertNull(AspectCorrection.suggest(1.0, 0.0))
        assertNull(AspectCorrection.suggest(0.99, 0.0))
    }

    @Test
    fun `noise on a square-on card is left alone`() {
        // 2% out of round is what a good photograph of a flat card measures.
        // Correcting that would distort a picture that was already right.
        assertNull(AspectCorrection.suggest(0.98, 0.0))
        assertNotNull(AspectCorrection.suggest(1.0 - AspectCorrection.NOISE_FLOOR - 0.01, 0.0))
    }

    @Test
    fun `a wide fit stretches the HEIGHT and leaves the width alone`() {
        val s = AspectCorrection.suggest(0.90, 0.0)!!
        assertEquals(1.0, s.scaleX, 1e-9)
        assertEquals(1.0 / 0.90, s.scaleY, 1e-9)
        // The short axis is stretched rather than the long one shortened:
        // throwing away pixels costs detections.
        assertTrue(s.scaleY > 1.0)
    }

    @Test
    fun `a tall fit stretches the WIDTH`() {
        val s = AspectCorrection.suggest(0.90, 90.0)!!
        assertEquals(1.0 / 0.90, s.scaleX, 1e-9)
        assertEquals(1.0, s.scaleY, 1e-9)
    }

    @Test
    fun `orientation is read modulo 180 degrees`() {
        // 180 degrees is the same axis as 0, and -90 the same as 90. An
        // ellipse has no head or tail.
        assertEquals(AspectCorrection.suggest(0.9, 0.0)!!.scaleY,
            AspectCorrection.suggest(0.9, 180.0)!!.scaleY, 1e-9)
        assertEquals(AspectCorrection.suggest(0.9, 90.0)!!.scaleX,
            AspectCorrection.suggest(0.9, -90.0)!!.scaleX, 1e-9)
    }

    @Test
    fun `an oblique long axis is refused, because a stretch cannot express it`() {
        // 45 degrees is a card photographed from a corner. That is a
        // projective distortion; stretching the picture would make it worse
        // while looking like a fix.
        assertNull(AspectCorrection.suggest(0.85, 45.0))
        assertNull(AspectCorrection.suggest(0.85, 30.0))
    }

    @Test
    fun `an implausible reading is refused rather than resampled`() {
        assertNull(AspectCorrection.suggest(0.50, 0.0))
    }

    @Test
    fun `applying the suggestion actually makes the rings round`() {
        for (ratio in listOf(0.95, 0.90, 0.82, 0.70)) {
            for (angle in listOf(0.0, 90.0, 180.0)) {
                val s = AspectCorrection.suggest(ratio, angle)!!
                val after = AspectCorrection.ratioAfter(ratio, angle, s.scaleX, s.scaleY)
                assertEquals("ratio $ratio at $angle deg", 1.0, after, 1e-9)
            }
        }
    }

    @Test
    fun `typed percentages are bounded, and rubbish is refused`() {
        assertEquals(1.08, AspectCorrection.parsePercent("108")!!, 1e-9)
        assertEquals(1.0, AspectCorrection.parsePercent(" 100 ")!!, 1e-9)
        assertNull(AspectCorrection.parsePercent(""))
        assertNull(AspectCorrection.parsePercent("wide"))
        assertNull(AspectCorrection.parsePercent("400"))
        assertNull(AspectCorrection.parsePercent("10"))
    }

    @Test
    fun `pressing apply on an unchanged pair does nothing`() {
        assertTrue(!AspectCorrection.worthApplying(1.0, 1.0))
        assertTrue(!AspectCorrection.worthApplying(1.002, 0.999))
        assertTrue(AspectCorrection.worthApplying(1.0, 1.05))
    }
}
