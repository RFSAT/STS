package com.rfsat.sts

import com.rfsat.sts.detect.DetectedHole
import com.rfsat.sts.detect.ImageLoader
import com.rfsat.sts.rules.RuleCatalog
import com.rfsat.sts.scoring.ScoringEngine
import com.rfsat.sts.scoring.Shot
import com.rfsat.sts.scoring.ShotDistribution
import com.rfsat.sts.targets.TargetCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The distribution buckets, and the photo importer's decode arithmetic. */
class ShotDistributionTest {

    private fun hole(x: Double, y: Double) = DetectedHole(x, y, 4.5, 40.0, 1.0, 1.0)

    private fun shot(value: Double, display: String, i: Int = 1, miss: Boolean = false,
                     sighter: Boolean = false, inner: Boolean = false, zone: String = "") =
        Shot(index = i, xMm = 0.0, yMm = 0.0, value = value, displayValue = display,
             innerTen = inner, zoneName = zone, miss = miss, sighter = sighter)

    // ------------------------------------------------------------------

    @Test
    fun `every ring gets a bucket even when nobody hit it`() {
        val face = TargetCatalog.ISSF_AP10
        val rules = RuleCatalog.ISSF_AP60.copy(decimalScoring = false)
        val shots = listOf(shot(10.0, "10", 1), shot(10.0, "10", 2), shot(8.0, "8", 3))
        val d = ShotDistribution.of(shots, face, rules)
        // Ten rings plus the miss bar. An empty ring silently dropped would
        // hide the shape of the distribution, which is the only thing the
        // histogram exists to show.
        assertEquals(11, d.buckets.size)
        assertEquals(2, d.buckets.first { it.label == "10" }.count)
        assertEquals(0, d.buckets.first { it.label == "9" }.count)
        assertEquals(1, d.buckets.first { it.label == "8" }.count)
        assertEquals(2, d.peak)
    }

    @Test
    fun `decimal shots bucket by their whole ring but average by their true value`() {
        val face = TargetCatalog.ISSF_AR10
        val rules = RuleCatalog.ISSF_AR60
        // A 10.9 and a 10.1 are very different shots in the same ring. The
        // bucket must not distinguish them; the mean must.
        val shots = listOf(shot(10.9, "10.9", 1), shot(10.1, "10.1", 2))
        val d = ShotDistribution.of(shots, face, rules)
        assertEquals(2, d.buckets.first { it.label == "10" }.count)
        assertEquals(10.5, d.mean, 1e-9)
        assertTrue(d.sd > 0.5)
    }

    @Test
    fun `misses get their own bucket and are excluded from the ring counts`() {
        val face = TargetCatalog.ISSF_AP10
        val rules = RuleCatalog.ISSF_AP60.copy(decimalScoring = false)
        val d = ShotDistribution.of(
            listOf(shot(10.0, "10", 1), shot(0.0, "M", 2, miss = true)), face, rules
        )
        assertEquals(1, d.misses)
        assertEquals(1, d.buckets.first { it.isMiss }.count)
        assertEquals(2, d.shotCount)
    }

    @Test
    fun `sighters are left out of the distribution entirely`() {
        val face = TargetCatalog.ISSF_AP10
        val rules = RuleCatalog.ISSF_AP60.copy(decimalScoring = false)
        val d = ShotDistribution.of(
            listOf(shot(10.0, "10", 0, sighter = true), shot(9.0, "9", 1)), face, rules
        )
        assertEquals(1, d.shotCount)
        assertEquals(9.0, d.mean, 1e-9)
        assertEquals(0, d.buckets.first { it.label == "10" }.count)
    }

    @Test
    fun `percentages are of the shots fired, and sum to a hundred`() {
        val face = TargetCatalog.ISSF_AP10
        val rules = RuleCatalog.ISSF_AP60.copy(decimalScoring = false)
        val shots = (1..4).map { shot(if (it <= 3) 10.0 else 9.0, if (it <= 3) "10" else "9", it) }
        val d = ShotDistribution.of(shots, face, rules)
        assertEquals(75.0, d.buckets.first { it.label == "10" }.percentOf(d.shotCount), 1e-9)
        assertEquals(100.0, d.buckets.sumOf { it.percentOf(d.shotCount) }, 1e-9)
    }

    @Test
    fun `practical faces bucket by zone`() {
        val face = TargetCatalog.IPSC_CLASSIC
        val rules = RuleCatalog.IPSC_COMSTOCK
        val shots = listOf(
            ScoringEngine.scoreHole(hole(0.0, 0.0), face, rules, null, 1),
            ScoringEngine.scoreHole(hole(0.0, 250.0), face, rules, null, 2)
        )
        val d = ShotDistribution.of(shots, face, rules)
        assertEquals(1, d.buckets.first { it.label == "A" }.count)
        assertEquals(1, d.buckets.first { it.label == "C" }.count)
    }

    @Test
    fun `an empty string reports nothing rather than dividing by zero`() {
        val d = ShotDistribution.of(emptyList(), TargetCatalog.ISSF_AR10, RuleCatalog.ISSF_AR60)
        assertTrue(d.isEmpty)
        assertEquals(0, d.shotCount)
        assertEquals(0.0, d.mean, 1e-9)
        assertEquals(0.0, d.sd, 1e-9)
    }

    @Test
    fun `a single shot has a mean but no dispersion`() {
        val face = TargetCatalog.ISSF_AR10
        val d = ShotDistribution.of(listOf(shot(10.4, "10.4", 1)), face, RuleCatalog.ISSF_AR60)
        assertEquals(10.4, d.mean, 1e-9)
        assertEquals(0.0, d.sd, 1e-9)
        assertEquals("10.4", d.bestLabel)
    }

    @Test
    fun `inner tens are counted when the rules ask for them and not otherwise`() {
        val face = TargetCatalog.ISSF_P25_PRECISION
        val shots = listOf(shot(10.0, "10", 1, inner = true), shot(10.0, "10", 2))
        assertEquals(1, ShotDistribution.of(shots, face, RuleCatalog.ISSF_P25_SPORT).innerTens)
        assertEquals(
            0,
            ShotDistribution.of(shots, face, RuleCatalog.ISSF_P25_SPORT.copy(countInnerTens = false)).innerTens
        )
    }

    // ------------------------------------------------------------------
    //  Photo import
    // ------------------------------------------------------------------

    @Test
    fun `decode subsampling brings both edges to the cap or under`() {
        // A 50 MP phone photograph decoded at full size is ~200 MB as
        // ARGB_8888 and an OutOfMemoryError on most devices. The cap is an
        // upper bound, not a target: the widely copied version of this
        // function stops one step early and returns an image LARGER than the
        // limit, which is right for a thumbnail and wrong for a memory bound.
        assertEquals(4, ImageLoader.sampleSizeFor(8160, 6120, 3000))
        assertEquals(1, ImageLoader.sampleSizeFor(2000, 1500, 3000))
        assertEquals(2, ImageLoader.sampleSizeFor(6000, 4000, 3000))   // exactly 3000, so no further step
        assertEquals(2, ImageLoader.sampleSizeFor(4032, 3024, 3000))
        assertEquals(4, ImageLoader.sampleSizeFor(12000, 9000, 3000))
        for ((w, h) in listOf(8160 to 6120, 6000 to 4000, 4032 to 3024, 12000 to 9000, 108 to 108)) {
            val s = ImageLoader.sampleSizeFor(w, h, 3000)
            assertTrue("$w x $h / $s exceeds the cap", w / s <= 3000 && h / s <= 3000)
            assertTrue("sample size must be a power of two", s and (s - 1) == 0)
        }
    }
}
