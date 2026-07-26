package com.rfsat.sts

import com.rfsat.sts.detect.Homography
import com.rfsat.sts.rules.Gauge
import com.rfsat.sts.scoring.GroupStatistics
import com.rfsat.sts.scoring.Shot
import com.rfsat.sts.targets.TargetCatalog
import com.rfsat.sts.targets.TargetFace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the parts of the app that are pure arithmetic and therefore
 * actually testable without a device: the ring geometry, the decimal
 * derivation, the homography, and the group statistics.
 *
 * These are the calculations a wrong answer would be hardest to NOTICE in.
 * A broken camera pipeline is obvious the first time you point the phone at
 * a target. A ten-ring radius that is out by the gauge radius produces
 * scores that are plausible, self-consistent, and quietly one point low on
 * every marginal shot — so it is worth pinning to published numbers.
 */
class ScoringGeometryTest {

    private val airGauge = Gauge.AIR_4_5 / 2.0        // 2.25 mm
    private val rimfireGauge = Gauge.RIMFIRE_5_6 / 2.0 // 2.8 mm

    // ------------------------------------------------------------------
    //  Ring dimensions against the published ISSF tables
    // ------------------------------------------------------------------

    @Test
    fun `air rifle rings match the published diameters`() {
        val f = TargetCatalog.ISSF_AR10
        fun d(v: Int) = f.rings.first { it.value == v }.diameterMm
        assertEquals(0.5, d(10), 1e-9)
        assertEquals(5.5, d(9), 1e-9)
        assertEquals(10.5, d(8), 1e-9)
        assertEquals(30.5, d(4), 1e-9)   // and this is the black
        assertEquals(45.5, d(1), 1e-9)
        assertEquals(30.5, f.blackDiameterMm, 1e-9)
    }

    @Test
    fun `air pistol rings match the published diameters`() {
        val f = TargetCatalog.ISSF_AP10
        fun d(v: Int) = f.rings.first { it.value == v }.diameterMm
        assertEquals(11.5, d(10), 1e-9)
        assertEquals(27.5, d(9), 1e-9)
        assertEquals(59.5, d(7), 1e-9)   // and this is the black
        assertEquals(155.5, d(1), 1e-9)
    }

    @Test
    fun `fifty metre rifle rings match the published diameters`() {
        val f = TargetCatalog.ISSF_R50
        fun d(v: Int) = f.rings.first { it.value == v }.diameterMm
        assertEquals(10.4, d(10), 1e-9)
        assertEquals(26.4, d(9), 1e-9)
        assertEquals(154.4, d(1), 1e-9)
    }

    // ------------------------------------------------------------------
    //  The touch rule
    // ------------------------------------------------------------------

    @Test
    fun `a shot scores the highest ring its hole touches`() {
        val f = TargetCatalog.ISSF_AP10
        // Ten ring radius 5.75, pellet radius 2.25, so the hole edge reaches
        // the ten ring line for any centre out to 8.00 mm.
        assertEquals(10, f.scoreInteger(0.0, airGauge))
        assertEquals(10, f.scoreInteger(7.99, airGauge))
        assertEquals(10, f.scoreInteger(8.00, airGauge))
        assertEquals(9, f.scoreInteger(8.01, airGauge))
        // Nine ring radius 13.75 -> boundary at 16.00
        assertEquals(9, f.scoreInteger(16.00, airGauge))
        assertEquals(8, f.scoreInteger(16.01, airGauge))
    }

    @Test
    fun `a shot outside the outermost ring is a miss`() {
        val f = TargetCatalog.ISSF_AP10
        val outer = f.outerRadiusMm            // 77.75
        assertEquals(1, f.scoreInteger(outer + airGauge, airGauge))
        assertEquals(0, f.scoreInteger(outer + airGauge + 0.01, airGauge))
    }

    @Test
    fun `the gauge comes from the rules, not the bullet`() {
        // A 5.69 mm .223 bullet scored under the 7.62 mm centrefire gauge
        // gains most of a millimetre of reach, which is the whole point of
        // the rulebook specifying a gauge at all.
        val f = TargetCatalog.ISSF_R300
        val tenRadius = f.rings.first { it.value == 10 }.radiusMm  // 50
        val bulletRadius = 5.69 / 2.0
        val gaugeRadius = Gauge.CENTREFIRE_7_62 / 2.0
        val marginal = tenRadius + gaugeRadius - 0.01
        assertEquals(10, f.scoreInteger(marginal, gaugeRadius))
        assertEquals(9, f.scoreInteger(marginal, bulletRadius))
    }

    // ------------------------------------------------------------------
    //  Decimal scoring, against the derivation in TargetFace
    // ------------------------------------------------------------------

    @Test
    fun `air rifle decimal ten point nine is a quarter millimetre`() {
        val f = TargetCatalog.ISSF_AR10
        assertEquals(10.9, f.scoreDecimal(0.0, airGauge)!!, 1e-9)
        assertEquals(10.9, f.scoreDecimal(0.24, airGauge)!!, 1e-9)
        // Just past 0.25 mm the tenth is lost.
        assertTrue(f.scoreDecimal(0.30, airGauge)!! < 10.9)
    }

    @Test
    fun `air rifle decimal agrees with the integer ring at every boundary`() {
        val f = TargetCatalog.ISSF_AR10
        // Ten ring radius 0.25 + pellet 2.25 = 2.50 mm for a clean 10.0
        assertEquals(10.0, f.scoreDecimal(2.50, airGauge)!!, 1e-9)
        // One ring pitch further out is a clean 9.0
        assertEquals(9.0, f.scoreDecimal(5.00, airGauge)!!, 1e-9)
        assertEquals(8.0, f.scoreDecimal(7.50, airGauge)!!, 1e-9)
    }

    @Test
    fun `air pistol decimal ten point nine is eight tenths of a millimetre`() {
        val f = TargetCatalog.ISSF_AP10
        assertEquals(10.9, f.scoreDecimal(0.79, airGauge)!!, 1e-9)
        assertTrue(f.scoreDecimal(0.85, airGauge)!! < 10.9)
        assertEquals(10.0, f.scoreDecimal(8.00, airGauge)!!, 1e-9)
    }

    @Test
    fun `fifty metre decimal ten point nine is eight tenths of a millimetre`() {
        val f = TargetCatalog.ISSF_R50
        assertEquals(10.9, f.scoreDecimal(0.79, rimfireGauge)!!, 1e-9)
        assertEquals(10.0, f.scoreDecimal(5.2 + rimfireGauge, rimfireGauge)!!, 1e-9)
    }

    @Test
    fun `decimal scoring refuses an unevenly pitched face`() {
        // The NRA high-power faces step 7, 12, 18, 24 inches: not a constant
        // pitch, so a decimal figure is not defined and must not be invented.
        val f = TargetCatalog.NRA_SR_200
        assertNull(f.ringPitchMm)
        assertNull(f.scoreDecimal(50.0, Gauge.CENTREFIRE_7_62 / 2.0))
        // The integer value is still perfectly well defined.
        assertEquals(10, f.scoreInteger(0.0, Gauge.CENTREFIRE_7_62 / 2.0))
    }

    @Test
    fun `a miss scores zero decimal, not a negative number`() {
        val f = TargetCatalog.ISSF_AR10
        assertEquals(0.0, f.scoreDecimal(200.0, airGauge)!!, 1e-9)
    }

    // ------------------------------------------------------------------
    //  Inner ten
    // ------------------------------------------------------------------

    @Test
    fun `inner ten uses the same touch rule`() {
        val f = TargetCatalog.ISSF_P25_PRECISION   // inner ten 25 mm
        val g = rimfireGauge
        assertTrue(f.isInnerTen(12.5 + g, g))
        assertTrue(!f.isInnerTen(12.5 + g + 0.01, g))
    }

    // ------------------------------------------------------------------
    //  Zones
    // ------------------------------------------------------------------

    @Test
    fun `IPSC A zone beats the C zone it sits inside`() {
        val f = TargetCatalog.IPSC_CLASSIC
        val g = Gauge.PISTOL_9_65 / 2.0
        assertEquals("A", f.zoneAt(0.0, 0.0, g)?.name)
        assertEquals("C", f.zoneAt(0.0, 250.0, g)?.name)   // above the A zone
        assertEquals("D", f.zoneAt(200.0, 0.0, g)?.name)   // outside |x| = 150
        assertNull(f.zoneAt(1000.0, 1000.0, g))
    }

    @Test
    fun `IDPA down zones are stored as negative points`() {
        val f = TargetCatalog.IDPA_TARGET
        val g = Gauge.PISTOL_9_65 / 2.0
        assertEquals(0.0, f.zoneAt(0.0, 0.0, g)!!.minorPoints, 1e-9)
        assertEquals(-1.0, f.zoneAt(0.0, 150.0, g)!!.minorPoints, 1e-9)
    }

    // ------------------------------------------------------------------
    //  Homography
    // ------------------------------------------------------------------

    @Test
    fun `homography round trips a keystoned view`() {
        // A 170 mm card seen off-axis: the far edge is narrower.
        val card = listOf(-85.0 to 85.0, 85.0 to 85.0, 85.0 to -85.0, -85.0 to -85.0)
        val image = listOf(300.0 to 200.0, 900.0 to 260.0, 940.0 to 780.0, 260.0 to 840.0)
        val h = Homography.fromCorrespondences(card, image)
        assertNotNull(h)
        card.forEachIndexed { i, (u, v) ->
            val (x, y) = h!!.mmToPx(u, v)
            assertEquals(image[i].first, x, 1e-6)
            assertEquals(image[i].second, y, 1e-6)
            val (bu, bv) = h.pxToMm(x, y)
            assertEquals(u, bu, 1e-6)
            assertEquals(v, bv, 1e-6)
        }
    }

    @Test
    fun `homography rejects taps that collapse the quadrilateral`() {
        val card = listOf(-85.0 to 85.0, 85.0 to 85.0, 85.0 to -85.0, -85.0 to -85.0)
        // Four points on a line: any fit is rank-deficient and cannot be
        // inverted, which is exactly what a shooter tapping along one edge of
        // the card produces.
        assertNull(Homography.fromCorrespondences(
            card, listOf(100.0 to 100.0, 200.0 to 100.0, 300.0 to 100.0, 400.0 to 100.0)
        ))
        // All four taps in the same place.
        assertNull(Homography.fromCorrespondences(
            card, listOf(200.0 to 200.0, 200.0 to 200.0, 200.0 to 200.0, 200.0 to 200.0)
        ))
    }

    @Test
    fun `local scale is larger where the view is nearer`() {
        val card = listOf(-85.0 to 85.0, 85.0 to 85.0, 85.0 to -85.0, -85.0 to -85.0)
        // Bottom edge much wider in the image than the top edge: the bottom
        // of the card is nearer the camera, so its scale must be larger.
        val image = listOf(400.0 to 200.0, 600.0 to 200.0, 900.0 to 800.0, 100.0 to 800.0)
        val h = Homography.fromCorrespondences(card, image)!!
        val top = h.pxPerMmAt(0.0, 85.0)
        val bottom = h.pxPerMmAt(0.0, -85.0)
        assertTrue("bottom=$bottom top=$top", bottom > top * 1.5)
    }

    // ------------------------------------------------------------------
    //  Group statistics
    // ------------------------------------------------------------------

    private fun shot(x: Double, y: Double, i: Int = 1) =
        Shot(index = i, xMm = x, yMm = y, value = 10.0, displayValue = "10")

    @Test
    fun `group centre is the mean of the shots`() {
        val g = GroupStatistics.of(listOf(shot(0.0, 0.0), shot(4.0, 0.0), shot(0.0, 4.0), shot(4.0, 4.0)))
        assertEquals(2.0, g.mpiXMm, 1e-9)
        assertEquals(2.0, g.mpiYMm, 1e-9)
    }

    @Test
    fun `extreme spread is the widest pair`() {
        val g = GroupStatistics.of(listOf(shot(0.0, 0.0), shot(3.0, 4.0), shot(1.0, 1.0)))
        assertEquals(5.0, g.extremeSpreadMm, 1e-9)
    }

    @Test
    fun `a single shot has no dispersion and no centre uncertainty`() {
        val g = GroupStatistics.of(listOf(shot(7.0, -3.0)))
        assertEquals(7.0, g.mpiXMm, 1e-9)
        assertEquals(0.0, g.extremeSpreadMm, 1e-9)
        assertEquals(0.0, g.mpiUncertaintyMm, 1e-9)
    }

    @Test
    fun `misses are excluded from the group`() {
        val hit = shot(0.0, 0.0)
        val miss = Shot(index = 2, xMm = 500.0, yMm = 500.0, value = 0.0, displayValue = "M", miss = true)
        val g = GroupStatistics.of(listOf(hit, miss))
        assertEquals(1, g.shotCount)
        assertEquals(0.0, g.mpiXMm, 1e-9)
    }

    @Test
    fun `group size in MOA uses the small angle relation`() {
        // One milliradian subtends 100 mm at 100 m — the identity the whole
        // correction path is built on.
        val oneMrad = GroupStatistics.of(listOf(shot(0.0, 0.0), shot(100.0, 0.0)))
        assertEquals(1.0, oneMrad.extremeSpreadMrad(100.0), 1e-9)
        assertEquals(3.43775, oneMrad.extremeSpreadMoa(100.0), 1e-6)

        // And one minute of angle subtends 29.089 mm at the same distance.
        val oneMoa = GroupStatistics.of(listOf(shot(0.0, 0.0), shot(29.089, 0.0)))
        assertEquals(1.0, oneMoa.extremeSpreadMoa(100.0), 1e-3)
    }

    // ------------------------------------------------------------------
    //  Even-ring construction
    // ------------------------------------------------------------------

    @Test
    fun `evenRings builds the sequence the ISSF tables print`() {
        val rings = TargetFace.evenRings(tenRingDiameterMm = 100.0, pitchMm = 50.0)
        assertEquals(10, rings.size)
        assertEquals(100.0, rings.first { it.value == 10 }.diameterMm, 1e-9)
        assertEquals(1000.0, rings.first { it.value == 1 }.diameterMm, 1e-9)
    }
}
