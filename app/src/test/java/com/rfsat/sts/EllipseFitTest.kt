package com.rfsat.sts

import com.rfsat.sts.detect.EdgePoint
import com.rfsat.sts.detect.EllipseFitter
import com.rfsat.sts.detect.RingShapeSelector
import com.rfsat.sts.detect.ShapeCorrection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Every expected value here was produced by an independent implementation of
 * the same method, checked against closed-form geometry first, and only then
 * compared with this code. They are not this code's own output written down.
 */
class EllipseFitTest {

    private fun ellipsePoints(
        a: Double, b: Double, thDeg: Double, cx: Double, cy: Double,
        n: Int = 240, fraction: Double = 1.0, noise: Double = 0.0, seed: Long = 11
    ): List<EdgePoint> {
        val th = Math.toRadians(thDeg)
        val rng = Random(seed)
        return (0 until n).map { k ->
            val t = 2 * Math.PI * fraction * k / n
            val x = a * cos(t)
            val y = b * sin(t)
            EdgePoint(
                cx + x * cos(th) - y * sin(th) + rng.nextGaussian() * noise,
                cy + x * sin(th) + y * cos(th) + rng.nextGaussian() * noise
            )
        }
    }

    @Test
    fun `recovers all five parameters of a known ellipse`() {
        val pts = ellipsePoints(120.0, 80.0, 28.6, 400.0, 300.0)
        val e = EllipseFitter.fit(pts)
        assertNotNull(e)
        e!!
        assertEquals(400.0, e.centreXPx, 1e-6)
        assertEquals(300.0, e.centreYPx, 1e-6)
        assertEquals(120.0, e.semiMajorPx, 1e-6)
        assertEquals(80.0, e.semiMinorPx, 1e-6)
        assertEquals(28.6, ((e.orientationDeg % 180) + 180) % 180, 1e-4)
    }

    @Test
    fun `recovers a steeply rotated ellipse`() {
        val e = EllipseFitter.fit(ellipsePoints(200.0, 140.0, -63.0, 400.0, 300.0))!!
        assertEquals(200.0, e.semiMajorPx, 1e-6)
        assertEquals(140.0, e.semiMinorPx, 1e-6)
        assertEquals(117.0, ((e.orientationDeg % 180) + 180) % 180, 1e-4)
    }

    @Test
    fun `a true circle comes back with axis ratio one`() {
        val e = EllipseFitter.fit(ellipsePoints(150.0, 150.0, 0.0, 400.0, 300.0))!!
        assertEquals(1.0, e.axisRatio, 1e-9)
    }

    /**
     * The projective identity the whole method rests on: a circle seen at
     * tilt a projects to an ellipse of axis ratio 1/cos(a). If this drifts,
     * every recovered tilt is wrong by the same factor.
     */
    @Test
    fun `axis ratio matches one over cosine of the tilt`() {
        for (tiltDeg in listOf(10.0, 20.0, 30.0, 40.0)) {
            val c = cos(Math.toRadians(tiltDeg))
            val e = EllipseFitter.fit(ellipsePoints(150.0 / c, 150.0, 25.0, 400.0, 300.0))!!
            assertEquals(1.0 / c, e.axisRatio, 1e-6)
        }
    }

    @Test
    fun `noise of one pixel barely moves the fit`() {
        val e = EllipseFitter.fit(ellipsePoints(150.0, 100.0, 30.0, 400.0, 300.0, noise = 1.0))!!
        assertEquals(1.5, e.axisRatio, 0.01)
        assertEquals(30.0, ((e.orientationDeg % 180) + 180) % 180, 1.0)
    }

    /**
     * Algebraic least squares has no resistance to outliers whatsoever, so
     * this is the difference between the plain and the trimmed fit rather
     * than a nicety. Measured: 40 strays in 280 points moved the plain fit's
     * ORIENTATION by 22 degrees.
     */
    @Test
    fun `trimming survives fourteen per cent gross outliers`() {
        val rng = Random(3)
        val pts = ellipsePoints(150.0, 100.0, 30.0, 400.0, 300.0, noise = 0.7).toMutableList()
        repeat(40) {
            pts += EdgePoint(400.0 + (rng.nextDouble() - 0.5) * 480, 300.0 + (rng.nextDouble() - 0.5) * 480)
        }
        val plain = EllipseFitter.fit(pts)!!
        val (trimmed, _) = EllipseFitter.fitTrimmed(pts)
        assertNotNull(trimmed)
        assertTrue(
            "the plain fit should be visibly damaged, else this test proves nothing",
            abs(plain.axisRatio - 1.5) > 0.03
        )
        assertEquals(1.5, trimmed!!.axisRatio, 0.03)
        assertEquals(30.0, ((trimmed.orientationDeg % 180) + 180) % 180, 2.0)
    }

    /**
     * Coverage, not point count, is the thing that predicts failure. A 15 per
     * cent arc has 36 points and an axis ratio out by 113 per cent; the gate
     * has to be able to tell those apart.
     */
    @Test
    fun `angular coverage tracks the arc actually present`() {
        // An arc covering fraction f of the circumference occupies floor(36f)
        // bins plus one, because both of its endpoints land in a bin of their
        // own. Asserted to 0.005 rather than loosely: at a slack tolerance
        // this test cannot distinguish a correct count from one that smears
        // each point into its neighbour, and a whole bin of false coverage is
        // the difference between passing and failing the 0.45 gate.
        assertEquals(1.0, EllipseFitter.angularCoverage(
            ellipsePoints(150.0, 100.0, 30.0, 400.0, 300.0), 400.0, 300.0), 0.005)
        for (fraction in listOf(0.75, 0.5, 0.25)) {
            val pts = ellipsePoints(150.0, 100.0, 30.0, 400.0, 300.0, fraction = fraction)
            val cov = EllipseFitter.angularCoverage(pts, 400.0, 300.0)
            assertEquals(fraction + 1.0 / 36.0, cov, 0.005)
        }
    }

    @Test
    fun `a short arc is rejected rather than fitted badly`() {
        val pts = ellipsePoints(150.0, 100.0, 30.0, 400.0, 300.0, n = 200, fraction = 0.15)
        val choice = RingShapeSelector.choose(pts)
        if (choice != null) {
            assertFalse(
                "a 15% arc must never be used to correct the geometry",
                choice.usedEllipse
            )
        }
    }

    /**
     * The failure the selector exists to prevent: an ellipse fitted to a
     * circle always beats a circle on the points it saw, and applying that
     * would skew every radius on a perfectly square-on target.
     */
    @Test
    fun `a square-on circle is not corrected`() {
        val pts = ellipsePoints(150.0, 150.0, 0.0, 400.0, 300.0, noise = 0.8)
        val choice = RingShapeSelector.choose(pts)
        assertNotNull(choice)
        assertFalse(choice!!.usedEllipse)
    }

    @Test
    fun `a genuinely elliptical outline is corrected`() {
        val pts = ellipsePoints(150.0, 115.0, 25.0, 400.0, 300.0, noise = 0.8)
        val choice = RingShapeSelector.choose(pts)
        assertNotNull(choice)
        assertTrue(choice!!.usedEllipse)
        assertEquals(150.0 / 115.0, choice.model.axisRatio, 0.02)
    }

    /** Two runs on the same points must give the same answer, or the same
     *  photograph can be scored two different ways. */
    @Test
    fun `the choice is deterministic`() {
        val pts = ellipsePoints(150.0, 118.0, 40.0, 400.0, 300.0, noise = 1.2)
        val a = RingShapeSelector.choose(pts)!!
        val b = RingShapeSelector.choose(pts)!!
        assertEquals(a.gainPercent, b.gainPercent, 0.0)
        assertEquals(a.usedEllipse, b.usedEllipse)
        assertEquals(a.model.axisRatio, b.model.axisRatio, 0.0)
    }

    @Test
    fun `the correction turns the fitted ellipse back into a circle`() {
        val pts = ellipsePoints(150.0, 100.0, 33.0, 400.0, 300.0)
        val e = EllipseFitter.fit(pts)!!
        val corr = ShapeCorrection(e.centreXPx, e.centreYPx, e.orientationRad, e.axisRatio)
        val mapped = pts.map { val (x, y) = corr.forward(it.x, it.y); EdgePoint(x, y) }
        val after = EllipseFitter.fit(mapped)!!
        assertEquals(1.0, after.axisRatio, 1e-6)
        val radii = mapped.map { hypot(it.x - after.centreXPx, it.y - after.centreYPx) }
        assertEquals(radii.min(), radii.max(), 1e-6)
    }

    @Test
    fun `forward and inverse of the correction round-trip`() {
        val corr = ShapeCorrection(120.0, 340.0, Math.toRadians(37.0), 1.24)
        for (p in listOf(0.0 to 0.0, 500.0 to 20.0, -80.0 to 640.0, 120.0 to 340.0)) {
            val (fx, fy) = corr.forward(p.first, p.second)
            val (bx, by) = corr.inverse(fx, fy)
            assertEquals(p.first, bx, 1e-9)
            assertEquals(p.second, by, 1e-9)
        }
    }

    /**
     * A bullet hole fused to the aiming mark adds a lobe. The pre-clean has to
     * remove it WITHOUT removing genuine foreshortening, which at 40 degrees
     * spans 0.87 to 1.14 of the mean radius.
     */
    @Test
    fun `radial pre-clean removes a lobe but keeps real foreshortening`() {
        val pts = ellipsePoints(150.0, 115.0, 0.0, 400.0, 300.0, n = 400).toMutableList()
        repeat(60) { k ->
            val t = Math.PI * 0.25 + k * 0.004
            pts += EdgePoint(400 + 230 * cos(t), 300 + 230 * sin(t))
        }
        val cleaned = EllipseFitter.radialPreClean(pts)
        assertTrue("the lobe should be gone", cleaned.size < pts.size)
        val e = EllipseFitter.fit(cleaned)!!
        assertEquals(150.0 / 115.0, e.axisRatio, 0.05)
    }
}
