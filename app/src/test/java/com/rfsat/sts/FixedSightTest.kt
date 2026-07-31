package com.rfsat.sts

import com.rfsat.sts.profiles.ClickUnit
import com.rfsat.sts.profiles.ScopeCatalog
import com.rfsat.sts.profiles.ScopeProfile
import com.rfsat.sts.profiles.SightType
import com.rfsat.sts.scoring.CorrectionCalculator
import com.rfsat.sts.scoring.GroupStatistics
import com.rfsat.sts.scoring.Shot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sights that cannot be clicked: a fixed factory iron sight, and none at all.
 *
 * The interesting behaviour is what the app REFUSES to say. A sight with no
 * turrets must never be told to turn one, and a sight radius that has not
 * been measured must never be guessed — a plausible-looking default would
 * produce a confident instruction to move the rear sight by the wrong amount,
 * which is worse than admitting the number is missing.
 */
class FixedSightTest {

    private fun groupAt(xMm: Double, yMm: Double): GroupStatistics {
        fun s(i: Int, x: Double, y: Double) =
            Shot(index = i, xMm = x, yMm = y, value = 10.0, displayValue = "10")
        return GroupStatistics.of(listOf(
            s(1, xMm, yMm), s(2, xMm + 1.0, yMm), s(3, xMm - 1.0, yMm),
            s(4, xMm, yMm + 1.0), s(5, xMm, yMm - 1.0)))
    }

    private fun entry(model: String) = ScopeCatalog.all.first { it.model == model }

    @Test
    fun `both fixed sights are in the catalogue and report no click`() {
        val iron = entry("Built-in iron sight")
        val none = entry("No sight")
        assertEquals(ClickUnit.NONE, iron.clickUnit)
        assertEquals(ClickUnit.NONE, none.clickUnit)
        assertFalse(iron.toScopeProfile().hasClicks)
        assertFalse(none.toScopeProfile().hasClicks)
    }

    @Test
    fun `a catalogue pick keeps the kind of sight it actually is`() {
        // Everything used to become a telescopic sight on the way out of the
        // catalogue, which was harmless until a sight arrived that has no
        // turrets to offer.
        assertEquals(SightType.NONE, entry("No sight").toScopeProfile().sightType)
        assertEquals(SightType.OPEN_SIGHTS, entry("Built-in iron sight").toScopeProfile().sightType)
        assertEquals(SightType.DIOPTER, entry("6834 diopter").toScopeProfile().sightType)
        assertEquals(SightType.RED_DOT, entry("507C").toScopeProfile().sightType)
        assertEquals(SightType.SCOPE, entry("Continental 5-30x56").toScopeProfile().sightType)
    }

    @Test
    fun `a target pistol rear sight is an open sight AND still has clicks`() {
        // The rule used to be that no open sight has clicks, which is false of
        // exactly the sights this app exists for.
        val morini = entry("CM162 rear sight").toScopeProfile()
        assertEquals(SightType.OPEN_SIGHTS, morini.sightType)
        assertTrue("a Morini rear sight is click-adjustable", morini.hasClicks)
    }

    @Test
    fun `with no sight the app refuses to advise an adjustment`() {
        val p = entry("No sight").toScopeProfile()
        val c = CorrectionCalculator.compute(groupAt(0.0, 0.0), p, 10.0)
        assertEquals(0, c.windageClicks)
        assertEquals(0, c.elevationClicks)
        assertFalse(c.hasRearSightAdvice)
        assertTrue("must say why", c.warnings.any { it.contains("no adjustable sight") })
    }

    @Test
    fun `with no sight the hold-off runs the same way as the error`() {
        // A group landing HIGH and RIGHT needs a LOWER and LEFT hold. The
        // first version of this sentence had both words the wrong way round.
        val p = entry("No sight").toScopeProfile()
        val c = CorrectionCalculator.compute(groupAt(20.0, 15.0), p, 10.0)
        assertTrue("said: ${c.instruction}", c.instruction.contains("lower"))
        assertTrue("said: ${c.instruction}", c.instruction.contains("left"))
        val d = CorrectionCalculator.compute(groupAt(-20.0, -15.0), p, 10.0)
        assertTrue("said: ${d.instruction}", d.instruction.contains("higher"))
        assertTrue("said: ${d.instruction}", d.instruction.contains("right"))
    }

    @Test
    fun `an unmeasured sight radius is asked for, not invented`() {
        val p = entry("Built-in iron sight").toScopeProfile()
        assertEquals(0.0, p.sightRadiusMm, 1e-9)
        val c = CorrectionCalculator.compute(groupAt(10.0, 10.0), p, 25.0)
        assertFalse(c.hasRearSightAdvice)
        assertTrue(c.warnings.any { it.contains("sight radius") })
    }

    @Test
    fun `given a sight radius the iron sight gets a millimetre instruction`() {
        // 250 mm sight radius, 10 mm of impact error at 25 m:
        // rear movement = 10 / 25000 * 250 = 0.10 mm.
        val p = entry("Built-in iron sight").toScopeProfile().copy(sightRadiusMm = 250.0)
        val c = CorrectionCalculator.compute(groupAt(10.0, 0.0), p, 25.0)
        assertTrue(c.hasRearSightAdvice)
        assertEquals(-0.10, c.rearSightMoveXMm, 0.005)
        assertTrue("said: ${c.instruction}", c.instruction.contains("rear sight"))
    }

    @Test
    fun `pickers do not print a magnification a diopter does not have`() {
        assertFalse(entry("No sight").label().contains("×"))
        assertFalse(entry("6834 diopter").label().contains("×"))
        assertTrue(entry("Continental 5-30x56").label().contains("×"))
    }
}
