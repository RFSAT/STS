package com.rfsat.sts

import com.rfsat.sts.detect.LumaFrame
import com.rfsat.sts.detect.RingFinder
import com.rfsat.sts.detect.TargetRegistration
import com.rfsat.sts.rules.Gauge
import com.rfsat.sts.targets.TargetCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * Ring fitting, which is now what sets the scale.
 *
 * The figures asserted here were measured against four real targets — two
 * synthetic, two scans — before the Kotlin was written: pitch recovered to
 * within 0.0 to 1.5 per cent, and the correct face identified with 0.3 to
 * 1.3 per cent agreement against 8 per cent or worse for the runner-up.
 */
class RingFinderTest {

    /** A concentric target: black aiming mark, evenly spaced ring lines. */
    private fun target(
        size: Int, centreX: Double, centreY: Double,
        firstRingPx: Double, pitchPx: Double, ringCount: Int, blackRadiusPx: Double
    ): LumaFrame {
        val data = ByteArray(size * size)
        val radii = (0 until ringCount).map { firstRingPx + it * pitchPx }
        for (j in 0 until size) for (i in 0 until size) {
            val r = hypot(i - centreX, j - centreY)
            var v = 210
            if (r <= blackRadiusPx) v = 25
            if (radii.any { kotlin.math.abs(r - it) < 1.1 }) v = if (r <= blackRadiusPx) 215 else 30
            data[j * size + i] = v.toByte()
        }
        return LumaFrame(size, size, data)
    }

    @Test
    fun `the ring pitch is recovered from a synthetic family`() {
        val frame = target(500, 250.0, 250.0, firstRingPx = 60.0, pitchPx = 22.0,
            ringCount = 8, blackRadiusPx = 45.0)
        val fit = RingFinder.find(frame)
        assertNotNull("no ring family found", fit)
        assertEquals(22.0, fit!!.pitchPx, 0.7)
        assertTrue("expected several rings, got ${fit.ringCount}", fit.ringCount >= 5)
        assertTrue("fit should be confident, was ${fit.confidence}", fit.confidence > 0.5)
    }

    @Test
    fun `the centre is found even when the target is off-centre in the frame`() {
        val frame = target(500, 190.0, 300.0, 55.0, 20.0, 8, 40.0)
        val fit = RingFinder.find(frame)
        assertNotNull(fit)
        assertEquals(190.0, fit!!.centreXPx, 6.0)
        assertEquals(300.0, fit.centreYPx, 6.0)
    }

    @Test
    fun `a half pitch is not preferred over the true one`() {
        // Each printed line has two edges, so a half-pitch ladder fits every
        // real ring PLUS every spurious edge and wins on inlier count alone.
        // An earlier version returned exactly half the true pitch on two of
        // four real targets because of it.
        val frame = target(600, 300.0, 300.0, 70.0, 30.0, 8, 55.0)
        val fit = RingFinder.find(frame)!!
        assertEquals("returned half the true pitch", 30.0, fit.pitchPx, 1.0)
    }

    @Test
    fun `the fitted pitch identifies which face was shot`() {
        // Air pistol proportions: pitch 8 mm, black 59.5 mm. At 4 px/mm the
        // pitch is 32 px and the black radius 119 px.
        val pxPerMm = 4.0
        val frame = target(900, 450.0, 450.0,
            firstRingPx = 5.75 * pxPerMm + 8.0 * pxPerMm,   // the 9 ring
            pitchPx = 8.0 * pxPerMm, ringCount = 9,
            blackRadiusPx = 29.75 * pxPerMm)
        val fit = RingFinder.find(frame)!!
        val matches = RingFinder.identify(fit, 29.75 * pxPerMm, TargetCatalog.builtIns)
        assertTrue("nothing identified", matches.isNotEmpty())
        assertEquals(TargetCatalog.ISSF_AP10.id, matches[0].face.id)
        assertTrue("best match too loose: ${matches[0].relativeError}", matches[0].relativeError < 0.05)
        val runnerUp = matches.getOrNull(1)
        if (runnerUp != null) {
            assertTrue(
                "the runner-up should be clearly worse",
                runnerUp.relativeError > matches[0].relativeError * 3
            )
        }
    }

    @Test
    fun `a registration built from the fit puts the rings where they belong`() {
        val pxPerMm = 4.0
        val face = TargetCatalog.ISSF_AP10
        val frame = target(900, 450.0, 450.0,
            5.75 * pxPerMm + 8.0 * pxPerMm, 8.0 * pxPerMm, 9, 29.75 * pxPerMm)
        val fit = RingFinder.find(frame)!!
        val reg = TargetRegistration.fromRingFit(face, fit, Gauge.AIR_4_5)
        assertNotNull(reg)
        // The ten-ring boundary must land at its true pixel radius.
        val tenMm = face.rings.first { it.value == 10 }.radiusMm
        val (x, y) = reg!!.homography.mmToPx(tenMm, 0.0)
        val (cx, cy) = reg.homography.mmToPx(0.0, 0.0)
        assertEquals(tenMm * pxPerMm, hypot(x - cx, y - cy), 3.0)
    }

    @Test
    fun `a face with unevenly pitched rings cannot be scaled from a pitch`() {
        val frame = target(500, 250.0, 250.0, 60.0, 22.0, 8, 45.0)
        val fit = RingFinder.find(frame)!!
        // The American high-power faces step 7, 12, 18, 24 inches.
        assertTrue(TargetCatalog.NRA_SR_200.ringPitchMm == null)
        assertEquals(null, TargetRegistration.fromRingFit(
            TargetCatalog.NRA_SR_200, fit, Gauge.CENTREFIRE_7_62))
    }

    @Test
    fun `featureless images produce no fit rather than a wrong one`() {
        val blank = LumaFrame(400, 400, ByteArray(400 * 400) { 200.toByte() })
        assertEquals(null, RingFinder.find(blank))
    }

    // ------------------------------------------------------------------
    //  The colour channel
    // ------------------------------------------------------------------

    @Test
    fun `paper colour is the median, so a big black mark cannot drag it`() {
        // Two thirds yellow card, one third black aiming mark. A mean would
        // land between them; the median stays on the card.
        val px = IntArray(3000) { if (it < 2000) 0xFFFEDB83.toInt() else 0xFF1A1A1A.toInt() }
        val (r, g, b) = LumaFrame.paperColourOf(px)
        // Doubles, not Ints: JUnit's three-argument assertEquals is
        // (double, double, double) and Kotlin does not widen Int to Double
        // for overload resolution, so an Int triple matches nothing.
        assertEquals(0xFE.toDouble(), r.toDouble(), 6.0)
        assertEquals(0xDB.toDouble(), g.toDouble(), 6.0)
        assertEquals(0x83.toDouble(), b.toDouble(), 6.0)
    }
}
