package com.rfsat.sts

import com.rfsat.sts.detect.LensDistortion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The distortion is measured from the target rather than looked up, because
 * no action camera publishes it — so the measurement itself has to be shown
 * to work.
 *
 * The tests run the model FORWARDS to make ring radii a distorted lens would
 * produce, then ask the estimator to recover the coefficient. That is the
 * only honest way to test an estimator: give it data whose answer is known
 * independently of the code that estimates it.
 */
class LensDistortionTest {

    private val norm = 800.0

    /** Ring radii as a lens with coefficient k would render an even ladder. */
    private fun rings(k: Double, first: Double = 60.0, pitch: Double = 50.0, n: Int = 8) =
        (0 until n).map { LensDistortion.distort(first + pitch * it, norm, k) }

    @Test
    fun `a pinhole camera measures no distortion`() {
        assertNull(LensDistortion.estimate(rings(0.0), norm))
    }

    @Test
    fun `barrel distortion is recovered from the ring ladder`() {
        for (k in listOf(-0.05, -0.10, -0.18, -0.25)) {
            val got = LensDistortion.estimate(rings(k), norm)
            assertTrue("nothing estimated for k=$k", got != null)
            assertEquals("k=$k", k, got!!, 0.01)
        }
    }

    @Test
    fun `pincushion is recovered too, and with the right sign`() {
        val got = LensDistortion.estimate(rings(0.08), norm)
        assertEquals(0.08, got!!, 0.01)
        assertTrue(got > 0)
    }

    @Test
    fun `a ladder that does not start at the first ring is not read as distortion`() {
        // The fitted family often begins at ring 5 or 6. A fit forced
        // through the origin would report that offset as a lens error.
        assertNull(LensDistortion.estimate(rings(0.0, first = 300.0), norm))
    }

    @Test
    fun `too few rings, no answer`() {
        assertNull(LensDistortion.estimate(rings(-0.15, n = 3), norm))
    }

    @Test
    fun `an implausible coefficient is refused rather than applied`() {
        // -0.65 is past anything a real lens does and past what the app will
        // apply; the estimator must say nothing rather than resample the
        // picture by a third.
        assertNull(LensDistortion.estimate(rings(-0.65), norm))
    }

    @Test
    fun `undistort inverts distort`() {
        for (k in listOf(-0.25, -0.1, 0.0, 0.12)) {
            for (r in listOf(10.0, 100.0, 400.0, 790.0)) {
                val there = LensDistortion.distort(r, norm, k)
                val back = LensDistortion.undistort(there, norm, k)
                assertEquals("k=$k r=$r", r, back, 0.05)
            }
        }
    }

    @Test
    fun `noise on the ring radii does not throw the estimate off`() {
        // Half a pixel of jitter is about what the ladder fit leaves.
        val rnd = java.util.Random(7)
        val noisy = rings(-0.15).map { it + rnd.nextGaussian() * 0.5 }
        assertEquals(-0.15, LensDistortion.estimate(noisy, norm)!!, 0.02)
    }

    @Test
    fun `typed values are bounded`() {
        assertEquals(-0.12, LensDistortion.parse("-0.12")!!, 1e-9)
        assertNull(LensDistortion.parse("-2"))
        assertNull(LensDistortion.parse("wide"))
    }
}
