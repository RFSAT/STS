package com.rfsat.sts

import com.rfsat.sts.detect.BlackMarkDetector
import com.rfsat.sts.detect.BoxTransform
import com.rfsat.sts.detect.DetectedDisc
import com.rfsat.sts.detect.TargetRegistration
import com.rfsat.sts.rules.Gauge
import com.rfsat.sts.targets.TargetCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bounding-box registration, and the geometry the auto-detector hands it.
 *
 * These are worth pinning because a box that is subtly the wrong size does
 * not fail — it produces a complete, plausible, uniformly wrong score sheet.
 */
class RegistrationBoxTest {

    private val airGauge = Gauge.AIR_4_5

    // ------------------------------------------------------------------
    //  Scale
    // ------------------------------------------------------------------

    @Test
    fun `a box around the outer ring sets the scale from its diameter`() {
        val face = TargetCatalog.ISSF_AP10          // outer ring 155.5 mm across
        // A 400 px box centred at (500, 500).
        val reg = TargetRegistration.fromBoundingBox(
            face, floatArrayOf(300f, 300f, 700f, 700f),
            TargetRegistration.BoxMeaning.OUTER_SCORING_RING, airGauge
        )
        assertNotNull(reg)
        val h = reg!!.homography

        // The scoring centre must land in the middle of the box.
        val (cx, cy) = h.mmToPx(0.0, 0.0)
        assertEquals(500.0, cx, 1e-6)
        assertEquals(500.0, cy, 1e-6)

        // 400 px across 155.5 mm is 2.5723 px/mm.
        val pxPerMm = 400.0 / 155.5
        val (x, _) = h.mmToPx(10.0, 0.0)
        assertEquals(500.0 + 10.0 * pxPerMm, x, 1e-6)

        // And the y axis is flipped: millimetres run up, pixels run down.
        val (_, yUp) = h.mmToPx(0.0, 10.0)
        assertTrue("+y mm must map to a SMALLER pixel row", yUp < 500.0)
        assertEquals(500.0 - 10.0 * pxPerMm, yUp, 1e-6)
    }

    @Test
    fun `a box around the aiming mark uses the black diameter, not the outer ring`() {
        val face = TargetCatalog.ISSF_AP10          // black 59.5 mm, outer 155.5 mm
        val reg = TargetRegistration.fromBoundingBox(
            face, floatArrayOf(300f, 300f, 700f, 700f),
            TargetRegistration.BoxMeaning.BLACK_AIMING_MARK, airGauge
        )!!
        val pxPerMm = 400.0 / 59.5
        val (x, _) = reg.homography.mmToPx(10.0, 0.0)
        assertEquals(500.0 + 10.0 * pxPerMm, x, 1e-6)
    }

    @Test
    fun `the box round-trips millimetres through pixels`() {
        val face = TargetCatalog.ISSF_R50
        val reg = TargetRegistration.fromBoundingBox(
            face, floatArrayOf(120f, 200f, 920f, 1000f),
            TargetRegistration.BoxMeaning.OUTER_SCORING_RING, Gauge.RIMFIRE_5_6
        )!!
        for ((u, v) in listOf(0.0 to 0.0, 12.3 to -45.6, -70.0 to 70.0)) {
            val (x, y) = reg.homography.mmToPx(u, v)
            val (bu, bv) = reg.homography.pxToMm(x, y)
            assertEquals(u, bu, 1e-6)
            assertEquals(v, bv, 1e-6)
        }
    }

    @Test
    fun `a rectangle is squared off rather than silently changing the scale`() {
        val face = TargetCatalog.ISSF_AP10
        // Left/right and top/bottom given the wrong way round, and not square.
        val reg = TargetRegistration.fromBoundingBox(
            face, floatArrayOf(700f, 700f, 300f, 300f),
            TargetRegistration.BoxMeaning.OUTER_SCORING_RING, airGauge
        )
        assertNotNull(reg)
        val (cx, cy) = reg!!.homography.mmToPx(0.0, 0.0)
        assertEquals(500.0, cx, 1e-6)
        assertEquals(500.0, cy, 1e-6)
    }

    @Test
    fun `a face with no aiming mark cannot be registered by a black box`() {
        val steel = TargetCatalog.STEEL_2MOA_100     // no printed black
        assertNull(
            TargetRegistration.fromBoundingBox(
                steel, floatArrayOf(0f, 0f, 100f, 100f),
                TargetRegistration.BoxMeaning.BLACK_AIMING_MARK, Gauge.RIMFIRE_5_6
            )
        )
        // but its scoring circle is a perfectly good reference
        assertNotNull(
            TargetRegistration.fromBoundingBox(
                steel, floatArrayOf(0f, 0f, 100f, 100f),
                TargetRegistration.BoxMeaning.OUTER_SCORING_RING, Gauge.RIMFIRE_5_6
            )
        )
    }

    @Test
    fun `a degenerate box is refused`() {
        val face = TargetCatalog.ISSF_AP10
        assertNull(
            TargetRegistration.fromBoundingBox(
                face, floatArrayOf(100f, 100f, 101f, 101f),
                TargetRegistration.BoxMeaning.OUTER_SCORING_RING, airGauge
            )
        )
    }

    @Test
    fun `an elliptical mark produces an explicit oblique warning`() {
        val face = TargetCatalog.ISSF_AP10
        val reg = TargetRegistration.fromBoundingBox(
            face, floatArrayOf(300f, 300f, 700f, 700f),
            TargetRegistration.BoxMeaning.OUTER_SCORING_RING, airGauge,
            markEllipticity = 1.4
        )!!
        assertTrue(reg.warnings.any { it.contains("at an angle") })
    }

    // ------------------------------------------------------------------
    //  Expanding the detected mark to the scoring area
    // ------------------------------------------------------------------

    private fun disc(
        r: Double, cx: Double = 300.0, cy: Double = 300.0, e: Double = 1.0,
        orientationDeg: Double = 0.0
    ) = DetectedDisc(cx, cy, r, e, 0.78, 0.9, axisRatio = 1.0 / e, orientationDeg = orientationDeg)

    @Test
    fun `the box expands from the black to the outer ring by the published ratio`() {
        val face = TargetCatalog.ISSF_AP10           // black 59.5, outer 155.5
        val ratio = (face.outerRadiusMm * 2.0) / face.blackDiameterMm
        val (box, meaning) = BlackMarkDetector.boxFor(disc(50.0), face, 1000, 1000)
        assertEquals(TargetRegistration.BoxMeaning.OUTER_SCORING_RING, meaning)
        assertEquals(2.0 * 50.0 * ratio, (box[2] - box[0]).toDouble(), 1e-3)
        // still centred on the mark
        assertEquals(300.0, ((box[0] + box[2]) / 2.0), 1e-6)
    }

    @Test
    fun `it falls back to the aiming mark when the scoring area is off the picture`() {
        val face = TargetCatalog.ISSF_AP10
        // A frame only just larger than the mark: the expanded box cannot fit.
        val (box, meaning) = BlackMarkDetector.boxFor(disc(50.0), face, 120, 120)
        assertEquals(TargetRegistration.BoxMeaning.BLACK_AIMING_MARK, meaning)
        assertEquals(100.0, (box[2] - box[0]).toDouble(), 1e-6)
    }

    @Test
    fun `an oblique mark is recognised as such`() {
        assertTrue(BlackMarkDetector.looksOblique(disc(50.0, e = 1.30)))
        assertTrue(!BlackMarkDetector.looksOblique(disc(50.0, e = 1.02)))
    }

    // ------------------------------------------------------------------
    //  Otsu
    // ------------------------------------------------------------------

    @Test
    fun `otsu splits a bimodal image between its two populations`() {
        // 30% at level 20 (the black mark), 70% at level 210 (paper).
        val values = IntArray(1000) { if (it < 300) 20 else 210 }
        val t = BlackMarkDetector.otsu(values)
        assertTrue("threshold $t should sit between the two modes", t in 20..209)
    }

    @Test
    fun `otsu on a flat image returns something usable rather than crashing`() {
        val t = BlackMarkDetector.otsu(IntArray(500) { 128 })
        assertTrue(t in 0..255)
        assertEquals(127, BlackMarkDetector.otsu(IntArray(0)))
    }

    @Test
    fun `equivalent radius from area is the circle formula`() {
        val r = 40.0
        val area = (Math.PI * r * r).toInt()
        assertEquals(r, BlackMarkDetector.radiusFromArea(area), 0.01)
    }

    // ------------------------------------------------------------------
    //  Tilt and rotation
    // ------------------------------------------------------------------

    @Test
    fun `the identity transform reproduces the plain box corner for corner`() {
        val c = BoxTransform.NONE.cornersFor(cx = 500.0, cy = 500.0, half = 200.0)
        assertEquals(300.0, c[0].first, 1e-9);  assertEquals(300.0, c[0].second, 1e-9)  // top-left
        assertEquals(700.0, c[1].first, 1e-9);  assertEquals(300.0, c[1].second, 1e-9)  // top-right
        assertEquals(700.0, c[2].first, 1e-9);  assertEquals(700.0, c[2].second, 1e-9)  // bottom-right
        assertEquals(300.0, c[3].first, 1e-9);  assertEquals(700.0, c[3].second, 1e-9)  // bottom-left
    }

    @Test
    fun `a tilt foreshortens rather than merely widening`() {
        // The trap this pins: a transform carrying only a projective term
        // makes the outline WIDER whatever sign it is given, and so can never
        // match a target that is leaning away.
        val t = BoxTransform(tiltXDeg = 30.0)
        val pts = t.circleFor(0.0, 0.0, 100.0, segments = 720)
        val w = pts.maxOf { it.first } - pts.minOf { it.first }
        val h = pts.maxOf { it.second } - pts.minOf { it.second }
        assertTrue("tilt must compress, not expand: ratio ${w / h}", w / h < 1.0)
        // cos 30 = 0.866; the keystone term accounts for the small excess
        assertEquals(0.866, w / h, 0.02)
    }

    @Test
    fun `a tilt keystones as well, so the near edge is the larger one`() {
        val t = BoxTransform(tiltXDeg = 30.0)
        val c = t.cornersFor(0.0, 0.0, 100.0)
        val leftEdge = kotlin.math.abs(c[0].second - c[3].second)   // TL to BL
        val rightEdge = kotlin.math.abs(c[1].second - c[2].second)  // TR to BR
        assertTrue("one edge must be magnified relative to the other", leftEdge > rightEdge * 1.05)
    }

    @Test
    fun `rotation turns the outline without changing its size`() {
        val plain = BoxTransform.NONE.circleFor(0.0, 0.0, 100.0, segments = 720)
        val spun = BoxTransform(rotationDeg = 30.0).circleFor(0.0, 0.0, 100.0, segments = 720)
        fun span(p: List<Pair<Double, Double>>) =
            (p.maxOf { it.first } - p.minOf { it.first }) to (p.maxOf { it.second } - p.minOf { it.second })
        val (pw, ph) = span(plain)
        val (sw, sh) = span(spun)
        assertEquals(pw, sw, 1e-6)   // a circle is unchanged by rotation
        assertEquals(ph, sh, 1e-6)

        // but the square corners do move
        val a = BoxTransform.NONE.cornersFor(0.0, 0.0, 100.0)[0]
        val b = BoxTransform(rotationDeg = 30.0).cornersFor(0.0, 0.0, 100.0)[0]
        assertTrue(kotlin.math.hypot(a.first - b.first, a.second - b.second) > 10.0)
    }

    @Test
    fun `the suggested tilt recovers the angle it was generated from`() {
        for (alpha in listOf(10.0, 20.0, 30.0, 40.0)) {
            val pts = BoxTransform(tiltXDeg = alpha).circleFor(0.0, 0.0, 100.0, segments = 720)
            val w = pts.maxOf { it.first } - pts.minOf { it.first }
            val h = pts.maxOf { it.second } - pts.minOf { it.second }
            val observed = BlackMarkDetector.suggestedTransform(
                disc(50.0, e = h / w, orientationDeg = 90.0)   // major axis vertical
            )
            val total = kotlin.math.hypot(observed.tiltXDeg, observed.tiltYDeg)
            assertEquals("tilt of $alpha should be recovered", alpha, total, 1.5)
        }
    }

    @Test
    fun `a round mark suggests no tilt at all`() {
        assertTrue(BlackMarkDetector.suggestedTransform(disc(50.0, e = 1.0)).isIdentity)
        assertTrue(BlackMarkDetector.suggestedTransform(disc(50.0, e = 1.01)).isIdentity)
    }

    @Test
    fun `the suggested tilt is clamped to what the model can express`() {
        val t = BlackMarkDetector.suggestedTransform(disc(50.0, e = 8.0, orientationDeg = 0.0))
        assertTrue(kotlin.math.abs(t.tiltXDeg) <= BoxTransform.MAX_TILT_DEG)
        assertTrue(kotlin.math.abs(t.tiltYDeg) <= BoxTransform.MAX_TILT_DEG)
    }

    @Test
    fun `a tilted registration still round-trips millimetres through pixels`() {
        val face = TargetCatalog.ISSF_R50
        val reg = TargetRegistration.fromBoundingBox(
            face, floatArrayOf(120f, 200f, 920f, 1000f),
            TargetRegistration.BoxMeaning.OUTER_SCORING_RING, Gauge.RIMFIRE_5_6,
            markEllipticity = 1.3,
            transform = BoxTransform(rotationDeg = 12.0, tiltXDeg = 18.0, tiltYDeg = -7.0)
        )!!
        for ((u, v) in listOf(0.0 to 0.0, 12.3 to -45.6, -70.0 to 70.0)) {
            val (x, y) = reg.homography.mmToPx(u, v)
            val (bu, bv) = reg.homography.pxToMm(x, y)
            assertEquals(u, bu, 1e-6)
            assertEquals(v, bv, 1e-6)
        }
        assertTrue(reg.warnings.any { it.contains("rotation") })
    }

    @Test
    fun `an untilted registration still says it cannot model perspective`() {
        val face = TargetCatalog.ISSF_AP10
        val reg = TargetRegistration.fromBoundingBox(
            face, floatArrayOf(300f, 300f, 700f, 700f),
            TargetRegistration.BoxMeaning.OUTER_SCORING_RING, airGauge
        )!!
        assertTrue(reg.warnings.any { it.contains("position and scale only") })
    }
}
