package com.rfsat.sts

import com.rfsat.sts.detect.DetectedHole
import com.rfsat.sts.profiles.ClickUnit
import com.rfsat.sts.profiles.ScopeProfile
import com.rfsat.sts.profiles.SightType
import com.rfsat.sts.rules.MatchScoring
import com.rfsat.sts.rules.RuleCatalog
import com.rfsat.sts.scoring.CorrectionCalculator
import com.rfsat.sts.scoring.GroupStatistics
import com.rfsat.sts.scoring.ScoringEngine
import com.rfsat.sts.scoring.Shot
import com.rfsat.sts.targets.TargetCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rule aggregation and the sight-correction arithmetic. */
class ScoringRulesTest {

    private fun hole(x: Double, y: Double) =
        DetectedHole(x, y, 4.5, 40.0, 1.0, 1.0)

    // ------------------------------------------------------------------
    //  Rule sets
    // ------------------------------------------------------------------

    @Test
    fun `every catalogue rule set points at a target face that exists`() {
        RuleCatalog.builtIns.forEach { r ->
            assertTrue(
                "rule set '${r.name}' names the unknown face '${r.targetFaceId}'",
                TargetCatalog.byId(r.targetFaceId) != null
            )
        }
    }

    @Test
    fun `catalogue ids are unique`() {
        val faceIds = TargetCatalog.builtIns.map { it.id }
        assertEquals(faceIds.size, faceIds.distinct().size)
        val ruleIds = RuleCatalog.builtIns.map { it.id }
        assertEquals(ruleIds.size, ruleIds.distinct().size)
    }

    @Test
    fun `a decimal sixty shot match maxes at six five four`() {
        assertEquals(654.0, RuleCatalog.ISSF_AR60.maxScore(), 1e-9)
        assertEquals(600.0, RuleCatalog.ISSF_P50.maxScore(), 1e-9)
        assertEquals(1200.0, RuleCatalog.ISSF_R50_3P.maxScore(), 1e-9)
    }

    @Test
    fun `hit factor and time-plus-penalty have no fixed maximum`() {
        assertEquals(0.0, RuleCatalog.IPSC_COMSTOCK.maxScore(), 1e-9)
        assertEquals(0.0, RuleCatalog.IDPA_STAGE.maxScore(), 1e-9)
        assertTrue(RuleCatalog.IDPA_STAGE.lowerIsBetter)
        assertTrue(!RuleCatalog.ISSF_AR60.lowerIsBetter)
    }

    // ------------------------------------------------------------------
    //  Aggregation
    // ------------------------------------------------------------------

    @Test
    fun `ten centred shots on the air rifle face total the decimal maximum`() {
        val face = TargetCatalog.ISSF_AR10
        val rules = RuleCatalog.ISSF_AR60.copy(matchShots = 10)
        val shots = (1..10).map { i ->
            ScoringEngine.scoreHole(hole(0.0, 0.0), face, rules, null, i, series = 1)
        }
        val res = ScoringEngine.aggregate(shots, face, rules)
        assertEquals(109.0, res.total, 1e-9)
    }

    @Test
    fun `excess shots annul the lowest values`() {
        val face = TargetCatalog.ISSF_AP10
        val rules = RuleCatalog.ISSF_AP60.copy(matchShots = 3, shotsPerSeries = 3, decimalScoring = false)
        // Three tens and one deliberately poor shot; the poor one must drop.
        val good = (1..3).map { ScoringEngine.scoreHole(hole(0.0, 0.0), face, rules, null, it) }
        val poor = ScoringEngine.scoreHole(hole(60.0, 0.0), face, rules, null, 4)
        val res = ScoringEngine.aggregate(good + poor, face, rules)
        assertEquals(30.0, res.total, 1e-9)
        assertTrue(res.warnings.any { it.contains("more than the course of fire") })
    }

    @Test
    fun `sighters are plotted but not counted`() {
        val face = TargetCatalog.ISSF_AP10
        val rules = RuleCatalog.ISSF_AP60.copy(matchShots = 2, shotsPerSeries = 2, decimalScoring = false)
        val sighter = ScoringEngine.scoreHole(hole(0.0, 0.0), face, rules, null, 0, sighter = true)
        val counted = (1..2).map { ScoringEngine.scoreHole(hole(0.0, 0.0), face, rules, null, it) }
        val res = ScoringEngine.aggregate(listOf(sighter) + counted, face, rules)
        assertEquals(20.0, res.total, 1e-9)
        assertEquals(3, res.shots.size)
    }

    @Test
    fun `asking for decimals on an unevenly pitched face warns and falls back`() {
        val face = TargetCatalog.NRA_SR_200
        val rules = RuleCatalog.NRA_NMC.copy(decimalScoring = true, matchShots = 1, shotsPerSeries = 1)
        val shots = listOf(ScoringEngine.scoreHole(hole(0.0, 0.0), face, rules, null, 1))
        val res = ScoringEngine.aggregate(shots, face, rules)
        assertEquals(10.0, res.total, 1e-9)
        assertTrue(res.warnings.any { it.contains("evenly pitched") })
    }

    @Test
    fun `hit factor needs a stage time and says so when it has none`() {
        val face = TargetCatalog.IPSC_CLASSIC
        val rules = RuleCatalog.IPSC_COMSTOCK
        val shots = (1..2).map { ScoringEngine.scoreHole(hole(0.0, 0.0), face, rules, null, it) }
        val untimed = ScoringEngine.aggregate(shots, face, rules, elapsedSeconds = 0.0)
        assertTrue(untimed.warnings.any { it.contains("stage time") })

        val timed = ScoringEngine.aggregate(shots, face, rules, elapsedSeconds = 2.0)
        assertEquals(MatchScoring.HIT_FACTOR, rules.matchScoring)
        assertEquals(5.0, timed.derivedFigure, 1e-9)   // two A hits, 10 points, in 2 seconds
    }

    // ------------------------------------------------------------------
    //  Click conversion
    // ------------------------------------------------------------------

    @Test
    fun `one tenth mrad click is one tenth of a mrad`() {
        val s = ScopeProfile(clickUnit = ClickUnit.MRAD_TENTH, sightTypeName = SightType.SCOPE.name)
        assertEquals(0.1, s.clickMrad, 1e-12)
    }

    @Test
    fun `a quarter MOA click is the right fraction of a mrad`() {
        val s = ScopeProfile(clickUnit = ClickUnit.MOA_QUARTER, sightTypeName = SightType.SCOPE.name)
        assertEquals(0.25 / 3.43775, s.clickMrad, 1e-12)
    }

    @Test
    fun `a millimetre-at-distance click converts through the identity mrad equals mm per metre`() {
        // 100 mm at 100 m is exactly one milliradian.
        val s = ScopeProfile(
            clickUnit = ClickUnit.MM_AT_REFERENCE,
            clickMmAtReference = 100.0, clickReferenceDistanceM = 100.0,
            sightTypeName = SightType.DIOPTER.name
        )
        assertEquals(1.0, s.clickMrad, 1e-12)

        // The realistic diopter case: 2 mm at 10 m is 0.2 mrad.
        val diopter = ScopeProfile(
            clickUnit = ClickUnit.MM_AT_REFERENCE,
            clickMmAtReference = 2.0, clickReferenceDistanceM = 10.0,
            sightTypeName = SightType.DIOPTER.name
        )
        assertEquals(0.2, diopter.clickMrad, 1e-12)
    }

    @Test
    fun `a sight with no reference distance cannot produce an infinite click`() {
        val broken = ScopeProfile(
            clickUnit = ClickUnit.MM_AT_REFERENCE,
            clickMmAtReference = 2.0, clickReferenceDistanceM = 0.0
        )
        assertEquals(0.0, broken.clickMrad, 1e-12)
        assertTrue(!broken.hasClicks)
    }

    // ------------------------------------------------------------------
    //  Correction
    // ------------------------------------------------------------------

    private fun groupAt(x: Double, y: Double, n: Int = 10) = GroupStatistics.of(
        (1..n).map { Shot(index = it, xMm = x, yMm = y, value = 10.0, displayValue = "10") }
    )

    @Test
    fun `a low left group asks for up and right`() {
        val scope = ScopeProfile(clickUnit = ClickUnit.MRAD_TENTH, sightTypeName = SightType.SCOPE.name)
        // 100 mm at 100 m is exactly 1 mrad, so ten clicks of 0.1 mrad.
        val c = CorrectionCalculator.compute(groupAt(-100.0, -100.0), scope, distanceM = 100.0)
        assertEquals("UP", c.elevationDirection)
        assertEquals("RIGHT", c.windageDirection)
        assertEquals(10, c.elevationClicks)
        assertEquals(10, c.windageClicks)
        assertEquals(1.0, c.elevationMrad, 1e-9)
    }

    @Test
    fun `an inverted drum reverses the printed instruction but not the physics`() {
        val normal = ScopeProfile(clickUnit = ClickUnit.MRAD_TENTH, sightTypeName = SightType.SCOPE.name)
        val inverted = normal.copy(invertElevationDirection = true)
        val g = groupAt(0.0, -100.0)
        val a = CorrectionCalculator.compute(g, normal, distanceM = 100.0)
        val b = CorrectionCalculator.compute(g, inverted, distanceM = 100.0)
        assertEquals("UP", a.elevationDirection)
        assertEquals("DOWN", b.elevationDirection)
        // The physical requirement is identical either way.
        assertEquals(a.elevationMrad, b.elevationMrad, 1e-12)
    }

    @Test
    fun `a diopter correction comes out in the maker's own clicks`() {
        // 2 mm per click at 10 m; a group 6 mm low needs three clicks up.
        val diopter = ScopeProfile(
            clickUnit = ClickUnit.MM_AT_REFERENCE,
            clickMmAtReference = 2.0, clickReferenceDistanceM = 10.0,
            sightTypeName = SightType.DIOPTER.name
        )
        val c = CorrectionCalculator.compute(groupAt(0.0, -6.0), diopter, distanceM = 10.0)
        assertEquals(3, c.elevationClicks)
        assertEquals("UP", c.elevationDirection)
    }

    @Test
    fun `an open sight with a known radius is told how far to move`() {
        val iron = ScopeProfile(
            clickUnit = ClickUnit.MM_AT_REFERENCE,
            sightTypeName = SightType.OPEN_SIGHTS.name,
            sightRadiusMm = 500.0
        )
        // 20 mm low at 10 m over a 500 mm sight radius: 20/10000 * 500 = 1 mm.
        val c = CorrectionCalculator.compute(groupAt(0.0, -20.0), iron, distanceM = 10.0)
        assertTrue(c.hasRearSightAdvice)
        assertEquals(1.0, c.rearSightMoveYMm, 1e-9)
    }

    @Test
    fun `a three shot group is flagged as too few to chase`() {
        val scope = ScopeProfile(clickUnit = ClickUnit.MRAD_TENTH, sightTypeName = SightType.SCOPE.name)
        val c = CorrectionCalculator.compute(groupAt(-30.0, 0.0, n = 3), scope, distanceM = 100.0)
        assertTrue(c.warnings.any { it.contains("Only 3 shot") })
    }

    @Test
    fun `shooting away from the zero distance warns that the zero is changing`() {
        val scope = ScopeProfile(clickUnit = ClickUnit.MRAD_TENTH, sightTypeName = SightType.SCOPE.name)
        val c = CorrectionCalculator.compute(
            groupAt(0.0, -30.0), scope, distanceM = 300.0, zeroDistanceM = 100.0
        )
        assertTrue(c.warnings.any { it.contains("re-zero") })
    }

    @Test
    fun `no distance means no correction at all`() {
        val scope = ScopeProfile(clickUnit = ClickUnit.MRAD_TENTH, sightTypeName = SightType.SCOPE.name)
        val c = CorrectionCalculator.compute(groupAt(0.0, -30.0), scope, distanceM = 0.0)
        assertTrue(!c.valid)
    }
}
