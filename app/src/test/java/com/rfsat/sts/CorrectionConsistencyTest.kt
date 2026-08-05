package com.rfsat.sts

import com.rfsat.sts.profiles.ScopeCatalog
import com.rfsat.sts.scoring.CorrectionCalculator
import com.rfsat.sts.scoring.GroupStatistics
import com.rfsat.sts.scoring.Shot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The headline and the detail line must describe the same state.
 *
 * The Results screen once showed "No adjustment — the sight is already
 * centred" in the box and "Move the point of impact 1.4 mm up and 0.3 mm
 * right" immediately beneath it. Both numbers were right. The two lines were
 * computed independently, so nothing made the second one notice that the
 * first had concluded there was nothing to do — the screen argued with itself
 * in front of the shooter, and there was no way for them to tell which half
 * to believe.
 *
 * [SightCorrection.needsAdjustment] is now the single answer to that
 * question, and these tests hold it to the instruction text.
 */
class CorrectionConsistencyTest {

    private fun groupAt(xMm: Double, yMm: Double): GroupStatistics {
        fun s(i: Int, x: Double, y: Double) =
            Shot(index = i, xMm = x, yMm = y, value = 10.0, displayValue = "10")
        return GroupStatistics.of(listOf(
            s(1, xMm, yMm), s(2, xMm + 0.4, yMm), s(3, xMm - 0.4, yMm),
            s(4, xMm, yMm + 0.4), s(5, xMm, yMm - 0.4)))
    }

    private fun scope(model: String) =
        ScopeCatalog.all.first { it.model == model }.toScopeProfile()

    /** A clicked sight, a group so close to centre that the correction
     *  rounds to no clicks at all. This is the exact case that produced the
     *  contradiction. */
    @Test
    fun `a residual smaller than one click reports no adjustment, and means it`() {
        val corr = CorrectionCalculator.compute(
            group = groupAt(0.4, 0.6), scope = scope("Continental 5-30x56"), distanceM = 100.0)
        assertTrue("the case is only interesting if it rounds to zero clicks",
            corr.windageClicks == 0 && corr.elevationClicks == 0)
        assertTrue(corr.valid)
        assertFalse("nothing to do, so the detail line must not order a move",
            corr.needsAdjustment)
        assertTrue(corr.instruction.contains("No adjustment"))
    }

    /** And the other way round: a group far enough out that clicks are owed
     *  must not be reported as centred. */
    @Test
    fun `a group off the aim point does need adjustment`() {
        val corr = CorrectionCalculator.compute(
            group = groupAt(40.0, 25.0), scope = scope("Continental 5-30x56"), distanceM = 100.0)
        assertTrue(corr.valid)
        assertTrue(corr.needsAdjustment)
        assertFalse(corr.instruction.contains("No adjustment"))
        assertTrue(corr.windageClicks != 0 || corr.elevationClicks != 0)
    }

    /** With no sight at all there is nothing to adjust, but a hold-off is
     *  still advice worth giving — so "needs adjustment" follows the
     *  hold-off, not the absence of turrets. */
    @Test
    fun `no sight still distinguishes a centred group from an offset one`() {
        val centred = CorrectionCalculator.compute(
            group = groupAt(0.0, 0.0), scope = scope("No sight"), distanceM = 25.0)
        assertFalse(centred.needsAdjustment)

        val off = CorrectionCalculator.compute(
            group = groupAt(30.0, 0.0), scope = scope("No sight"), distanceM = 25.0)
        assertTrue(off.needsAdjustment)
    }

    /** An input the calculator cannot work from is not "no adjustment
     *  needed": it is no answer at all, and the detail line must stay empty
     *  rather than print zeros. */
    @Test
    fun `an unusable input needs no adjustment and is not valid`() {
        val none = CorrectionCalculator.compute(
            group = GroupStatistics.of(emptyList()), scope = scope("No sight"), distanceM = 25.0)
        assertFalse(none.valid)
        assertFalse(none.needsAdjustment)
        assertEquals(0, none.windageClicks)
    }
}
