package com.rfsat.sts

import com.rfsat.sts.detect.BlackMarkDetector
import com.rfsat.sts.detect.DetectedDisc
import com.rfsat.sts.detect.HoleDetector
import com.rfsat.sts.detect.LumaFrame
import com.rfsat.sts.detect.MaskedIntegralImage
import com.rfsat.sts.detect.TargetRegistration
import com.rfsat.sts.rules.Gauge
import com.rfsat.sts.targets.TargetCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * Regressions for three failures found in the field, each of which produced a
 * complete and plausible wrong answer rather than an error.
 */
class DetectorRegressionTest {

    // ------------------------------------------------------------------
    //  Out-of-frame pixels must not be averaged in
    // ------------------------------------------------------------------

    @Test
    fun `a masked integral ignores pixels the camera never covered`() {
        // Left half paper at 200, right half "out of frame" at 1.
        val w = 40; val h = 10
        val data = ByteArray(w * h) { if ((it % w) < w / 2) 200.toByte() else 1.toByte() }
        val frame = LumaFrame(w, h, data)
        val valid = BooleanArray(w * h) { (it % w) < w / 2 }

        val masked = MaskedIntegralImage(frame, valid)
        // A window straddling the boundary must report the paper value...
        assertEquals(200.0, masked.mean(15, 2, 25, 8), 1e-9)
        // ...whereas averaging everything would have read far darker.
        val naive = com.rfsat.sts.detect.IntegralImage(frame).mean(15, 2, 25, 8)
        assertTrue("the naive mean should be dragged down by the void", naive < 150.0)
    }

    @Test
    fun `a window with too little real data reports nothing rather than guessing`() {
        val w = 20; val h = 20
        val frame = LumaFrame(w, h, ByteArray(w * h) { 200.toByte() })
        val valid = BooleanArray(w * h) { it % w < 2 }        // a thin valid strip
        val masked = MaskedIntegralImage(frame, valid)
        assertTrue(masked.mean(10, 5, 18, 15, minValid = 20).isNaN())
    }

    // ------------------------------------------------------------------
    //  A synthetic target: printed rings must not become shots
    // ------------------------------------------------------------------

    /**
     * Draws a face with concentric ring lines, a black aiming mark, and a
     * known set of holes — the situation the absolute detector was getting
     * wrong, where the printing was scored as shots.
     */
    private fun syntheticTarget(
        size: Int, blackRadius: Double, ringRadii: List<Double>, holes: List<Triple<Double, Double, Double>>
    ): LumaFrame {
        val c = size / 2.0
        val data = ByteArray(size * size)
        for (j in 0 until size) for (i in 0 until size) {
            val r = hypot(i - c, j - c)
            var v = 205                                        // paper
            if (r <= blackRadius) v = 25                       // aiming mark
            if (ringRadii.any { kotlin.math.abs(r - it) < 1.2 }) v = if (r <= blackRadius) 210 else 35
            data[j * size + i] = v.toByte()
        }
        for ((hx, hy, hr) in holes) {
            for (j in 0 until size) for (i in 0 until size) {
                if (hypot(i - (c + hx), j - (c + hy)) <= hr) {
                    val onBlack = hypot(i - c, j - c) <= blackRadius
                    data[j * size + i] = (if (onBlack) 235 else 15).toByte()
                }
            }
        }
        return LumaFrame(size, size, data)
    }

    @Test
    fun `printed rings are not scored as shots`() {
        val face = TargetCatalog.ISSF_AP10
        val size = 400
        // Register the synthetic face so a millimetre maps to a known pixel.
        val reg = TargetRegistration.fromBoundingBox(
            face, floatArrayOf(0f, 0f, size.toFloat(), size.toFloat()),
            TargetRegistration.BoxMeaning.OUTER_SCORING_RING, Gauge.AIR_4_5
        )!!
        val pxPerMm = size / (face.outerRadiusMm * 2.0)
        val gaugePx = Gauge.AIR_4_5 * pxPerMm

        val holes = listOf(
            Triple(0.0, 0.0, gaugePx / 2),
            Triple(40.0, -25.0, gaugePx / 2),
            Triple(-55.0, 30.0, gaugePx / 2)
        )
        val frame = syntheticTarget(
            size,
            blackRadius = face.blackDiameterMm / 2.0 * pxPerMm,
            ringRadii = face.rings.map { it.radiusMm * pxPerMm },
            holes = holes
        )

        val found = HoleDetector.detectAbsolute(reg, reg.rectify(frame), Gauge.AIR_4_5)

        // The face has ten printed rings. Before radial normalisation those
        // rings, and the edge of the aiming mark, were the strongest features
        // in the picture and swamped the three real holes.
        assertTrue(
            "found ${found.size} candidates for 3 holes on a 10-ring face",
            found.size <= 8
        )
        assertTrue("no holes found at all", found.isNotEmpty())
    }

    // ------------------------------------------------------------------
    //  Auto-detection of the aiming mark
    // ------------------------------------------------------------------

    @Test
    fun `the aiming mark is found on a plain synthetic face`() {
        val size = 400
        val frame = syntheticTarget(size, blackRadius = 60.0, ringRadii = listOf(90.0, 120.0, 150.0), holes = emptyList())
        val disc = BlackMarkDetector.detect(frame)
        assertNotNull(disc)
        assertEquals(200.0, disc!!.centreXPx, 6.0)
        assertEquals(200.0, disc.centreYPx, 6.0)
        assertEquals(60.0, disc.radiusPx, 8.0)
        assertTrue("a circle should not read as oblique", disc.ellipticity < 1.1)
    }

    @Test
    fun `a dark surround does not steal the detection from the mark`() {
        // The card occupies the middle; everything outside it is a dark
        // bench. The bench is bigger and darker than the aiming mark, and
        // picking the largest dark region alone would choose it.
        val size = 400
        val inner = syntheticTarget(size, blackRadius = 45.0, ringRadii = listOf(70.0, 95.0), holes = emptyList())
        val data = ByteArray(size * size)
        for (j in 0 until size) for (i in 0 until size) {
            val onCard = i in 110..289 && j in 110..289
            data[j * size + i] = if (onCard) inner.data[j * size + i] else 30.toByte()
        }
        val disc = BlackMarkDetector.detect(LumaFrame(size, size, data))
        assertNotNull(disc)
        assertEquals("should find the mark, not the bench", 200.0, disc!!.centreXPx, 25.0)
        assertEquals(200.0, disc.centreYPx, 25.0)
    }

    // ------------------------------------------------------------------
    //  Tilt is offered, never imposed
    // ------------------------------------------------------------------

    @Test
    fun `a slightly elliptical mark suggests nothing, because noise is not tilt`() {
        // A shot-up aiming mark measures a few percent elliptical from
        // segmentation noise alone. Acting on that turned a square-on target
        // into a visibly skewed box in the field.
        for (e in listOf(1.005, 1.01, 1.015, 1.02)) {
            val d = DetectedDisc(100.0, 100.0, 50.0, e, 0.78, 0.9, axisRatio = 1.0 / e)
            assertTrue(
                "ellipticity $e must not produce a tilt",
                BlackMarkDetector.suggestedTransform(d).isIdentity
            )
        }
    }

    @Test
    fun `the suggestion splits between the axes by the minor axis direction`() {
        // Major axis vertical means the compression is horizontal, so the
        // whole angle belongs to the horizontal tilt.
        val d = DetectedDisc(0.0, 0.0, 50.0, 1.3, 0.78, 0.9, axisRatio = 1 / 1.3, orientationDeg = 90.0)
        val t = BlackMarkDetector.suggestedTransform(d)
        assertTrue(kotlin.math.abs(t.tiltXDeg) > 20.0)
        assertEquals(0.0, t.tiltYDeg, 1e-6)
    }

    // ------------------------------------------------------------------
    //  Printed ring numerals must not be scored as shots
    // ------------------------------------------------------------------

    /** A face with its rings NUMBERED at the four cardinal points, which is
     *  how every competition target is printed and what defeated the radial
     *  median: a numeral occupies four angles out of 360 and barely moves a
     *  median taken around the whole circumference. */
    private fun targetWithNumerals(
        size: Int, blackRadius: Double, ringRadii: List<Double>,
        holes: List<Triple<Double, Double, Double>>
    ): LumaFrame {
        val c = size / 2.0
        val data = ByteArray(size * size)
        for (j in 0 until size) for (i in 0 until size) {
            val r = hypot(i - c, j - c)
            var v = 205
            if (r <= blackRadius) v = 25
            if (ringRadii.any { kotlin.math.abs(r - it) < 1.2 }) v = if (r <= blackRadius) 210 else 35
            data[j * size + i] = v.toByte()
        }
        // A blob standing in for the numeral, in the middle of each annulus,
        // at north, south, east and west.
        val mids = ringRadii.zipWithNext { a, b -> (a + b) / 2.0 }
        for (m in mids) {
            for ((ox, oy) in listOf(0.0 to -m, 0.0 to m, -m to 0.0, m to 0.0)) {
                for (j in 0 until size) for (i in 0 until size) {
                    if (hypot(i - (c + ox), j - (c + oy)) <= 4.0) data[j * size + i] = 30
                }
            }
        }
        for ((hx, hy, hr) in holes) {
            for (j in 0 until size) for (i in 0 until size) {
                if (hypot(i - (c + hx), j - (c + hy)) <= hr) {
                    val onBlack = hypot(i - c, j - c) <= blackRadius
                    data[j * size + i] = (if (onBlack) 235 else 15).toByte()
                }
            }
        }
        return LumaFrame(size, size, data)
    }

    @Test
    fun `printed ring numerals are rejected as shots`() {
        val face = TargetCatalog.ISSF_AP10
        val size = 420
        val reg = TargetRegistration.fromBoundingBox(
            face, floatArrayOf(0f, 0f, size.toFloat(), size.toFloat()),
            TargetRegistration.BoxMeaning.OUTER_SCORING_RING, Gauge.AIR_4_5
        )!!
        val pxPerMm = size / (face.outerRadiusMm * 2.0)
        val gaugePx = Gauge.AIR_4_5 * pxPerMm

        // Three shots, deliberately NOT on the cardinal axes so they have no
        // rotational partners.
        val holes = listOf(
            Triple(18.0, 11.0, gaugePx / 2),
            Triple(-33.0, 21.0, gaugePx / 2),
            Triple(25.0, -44.0, gaugePx / 2)
        )
        val frame = targetWithNumerals(
            size,
            blackRadius = face.blackDiameterMm / 2.0 * pxPerMm,
            ringRadii = face.rings.map { it.radiusMm * pxPerMm },
            holes = holes
        )
        val found = HoleDetector.detectAbsolute(reg, reg.rectify(frame), Gauge.AIR_4_5)

        // Nine annuli x four positions is thirty-six printed marks against
        // three real shots. Before the rotational-twin test they all came
        // back as candidates.
        assertTrue("found ${found.size} candidates for 3 shots among 4-fold numerals", found.size <= 6)
        assertTrue("lost the real shots entirely", found.size >= 2)
    }

    @Test
    fun `a shot on a cardinal axis survives, because it has no twins`() {
        val face = TargetCatalog.ISSF_AP10
        val size = 420
        val reg = TargetRegistration.fromBoundingBox(
            face, floatArrayOf(0f, 0f, size.toFloat(), size.toFloat()),
            TargetRegistration.BoxMeaning.OUTER_SCORING_RING, Gauge.AIR_4_5
        )!!
        val pxPerMm = size / (face.outerRadiusMm * 2.0)
        val gaugePx = Gauge.AIR_4_5 * pxPerMm
        // Directly above the centre — the very place a numeral would sit.
        val frame = targetWithNumerals(
            size, face.blackDiameterMm / 2.0 * pxPerMm,
            face.rings.map { it.radiusMm * pxPerMm },
            listOf(Triple(0.0, -52.0, gaugePx / 2))
        )
        val found = HoleDetector.detectAbsolute(reg, reg.rectify(frame), Gauge.AIR_4_5)
        assertTrue("a lone shot on the vertical axis must not be mistaken for printing", found.isNotEmpty())
    }

    @Test
    fun `card furniture outside the rings is not scored`() {
        val face = TargetCatalog.ISSF_AP10
        val size = 420
        val reg = TargetRegistration.fromBoundingBox(
            face, floatArrayOf(0f, 0f, size.toFloat(), size.toFloat()),
            TargetRegistration.BoxMeaning.OUTER_SCORING_RING, Gauge.AIR_4_5
        )!!
        val pxPerMm = size / (face.outerRadiusMm * 2.0)
        val gaugePx = Gauge.AIR_4_5 * pxPerMm
        val frame = targetWithNumerals(
            size, face.blackDiameterMm / 2.0 * pxPerMm,
            face.rings.map { it.radiusMm * pxPerMm },
            // one real shot, plus a club logo well outside the outermost ring
            listOf(Triple(20.0, 15.0, gaugePx / 2), Triple(-195.0, -195.0, gaugePx))
        )
        val found = HoleDetector.detectAbsolute(reg, reg.rectify(frame), Gauge.AIR_4_5)
        val outsideRings = found.count { it.distanceFromCentreMm > face.outerRadiusMm }
        assertEquals("nothing beyond the outermost ring should be reported", 0, outsideRings)
    }
}
