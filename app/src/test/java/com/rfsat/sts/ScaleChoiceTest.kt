package com.rfsat.sts

import com.rfsat.sts.detect.RingFit
import com.rfsat.sts.detect.ScaleMode
import com.rfsat.sts.detect.ScaleSettings
import com.rfsat.sts.detect.TargetRegistration
import com.rfsat.sts.targets.TargetCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scale is the most consequential number the app measures: every scoring
 * error is proportional to it. These fix the arithmetic and the selection
 * rule, not the image processing that feeds them.
 */
class ScaleChoiceTest {

    private val face = TargetCatalog.ISSF_AP10       // black 59.5 mm, pitch 8 mm
    private val pitchMm = face.ringPitchMm!!
    private val blackRadiusMm = face.blackDiameterMm / 2.0

    /** A fit with a given ladder pitch and, optionally, a mark radius. */
    private fun fitWith(pitchPx: Double, markPx: Double?): RingFit {
        val shape = markPx?.let {
            com.rfsat.sts.detect.RingShapeChoice(
                model = com.rfsat.sts.detect.EllipseModel(0.0, 0.0, it, it, 0.0),
                usedEllipse = false, gainPercent = 0.0, coverage = 1.0,
                pointCount = 400, reason = "test"
            )
        }
        return RingFit(
            centreXPx = 0.0, centreYPx = 0.0, pitchPx = pitchPx,
            ringsPx = listOf(pitchPx, 2 * pitchPx), residualPx = 0.0,
            confidence = 1.0, shape = shape
        )
    }

    /** The mark radius that corresponds exactly to a given pitch in pixels. */
    private fun markFor(pitchPx: Double) = pitchPx * (blackRadiusMm / pitchMm)

    @Test
    fun `with no mark the ladder is used unchanged`() {
        ScaleSettings.forceMode(ScaleMode.CROSS_CHECK)
        val c = TargetRegistration.chooseScale(face, fitWith(40.0, null), pitchMm)
        assertEquals(pitchMm / 40.0, c.mmPerPx, 1e-12)
        assertNull(c.disagreement)
    }

    @Test
    fun `the mark alone recovers the same scale it was built from`() {
        ScaleSettings.forceMode(ScaleMode.MARK_ONLY)
        val c = TargetRegistration.chooseScale(face, fitWith(999.0, markFor(40.0)), pitchMm)
        assertEquals(pitchMm / 40.0, c.mmPerPx, 1e-9)
    }

    @Test
    fun `ladder-only ignores the mark even when it disagrees`() {
        ScaleSettings.forceMode(ScaleMode.LADDER_ONLY)
        val c = TargetRegistration.chooseScale(face, fitWith(40.0, markFor(80.0)), pitchMm)
        assertEquals(pitchMm / 40.0, c.mmPerPx, 1e-12)
    }

    /** Averaging two independent readings is the point of agreeing. */
    @Test
    fun `when they agree the mean is used`() {
        ScaleSettings.forceMode(ScaleMode.CROSS_CHECK)
        val c = TargetRegistration.chooseScale(face, fitWith(40.0, markFor(41.0)), pitchMm)
        val expected = ((pitchMm / 40.0) + (pitchMm / 41.0)) / 2.0
        assertEquals(expected, c.mmPerPx, 1e-9)
        assertNull("2.5% apart is agreement", c.disagreement)
    }

    /**
     * The case this exists for. On an angled card the ladder loses rings and
     * drifts; the mark does not. Measured across tilts of 0 to 25 degrees the
     * ladder's scale varied by up to 90 per cent on one card while the mark
     * held to 1.9.
     */
    @Test
    fun `when they disagree the mark wins and it is reported`() {
        ScaleSettings.forceMode(ScaleMode.CROSS_CHECK)
        val c = TargetRegistration.chooseScale(face, fitWith(40.0, markFor(52.0)), pitchMm)
        assertEquals(pitchMm / 52.0, c.mmPerPx, 1e-9)
        assertNotNull(c.disagreement)
        assertTrue(c.disagreement!!.contains("disagree"))
        assertTrue("a disagreement should point at the face", c.disagreement.contains("face"))
    }

    @Test
    fun `the agreement threshold sits between the two regimes`() {
        // each method is good to about 1.5% when working, and the failures
        // measured were 8% and worse, so the boundary belongs between them
        assertTrue(ScaleSettings.AGREEMENT_TOLERANCE > 0.02)
        assertTrue(ScaleSettings.AGREEMENT_TOLERANCE < 0.08)
    }

    @Test
    fun `a face with no black falls back to the ladder`() {
        ScaleSettings.forceMode(ScaleMode.CROSS_CHECK)
        val noBlack = TargetCatalog.NRA_MR1_600
        val p = noBlack.ringPitchMm ?: return
        val c = TargetRegistration.chooseScale(noBlack, fitWith(40.0, null), p)
        assertEquals(p / 40.0, c.mmPerPx, 1e-12)
    }
}
