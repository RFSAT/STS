package com.rfsat.sts

import com.rfsat.sts.detect.LumaFrame
import com.rfsat.sts.detect.ScaleSettings
import com.rfsat.sts.detect.SourceHoleDetector
import com.rfsat.sts.detect.TargetRegistration
import com.rfsat.sts.targets.TargetCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Pins the two properties that took the user's card from a score of 10 to the
 * correct 19, both of which were found by measurement and neither of which is
 * obvious from the code they replaced.
 */
class SourceHoleDetectorTest {

    private val face = TargetCatalog.builtIns.first { it.id == "issf_ap_10m" }
    private val gauge = 4.5

    /** A synthetic card at a chosen scale, with holes painted where asked. */
    private fun card(
        mmPerPx: Double,
        holesMm: List<Pair<Double, Double>>,
        blackChannelSaturated: Boolean
    ): Pair<TargetRegistration, Pair<LumaFrame, LumaFrame>> {
        val side = ((face.outerRadiusMm * 2.6) / mmPerPx).roundToInt()
        val c = side / 2.0
        val blackR = face.blackDiameterMm / 2.0
        val gaugePx = gauge / mmPerPx

        val chan = ByteArray(side * side)
        val luma = ByteArray(side * side)
        for (j in 0 until side) for (i in 0 until side) {
            val u = (i + 0.5 - c) * mmPerPx
            val v = (c - j - 0.5) * mmPerPx
            val r = hypot(u, v)
            val idx = j * side + i
            val inBlack = r <= blackR
            // Background: paper is bright in both; the mark is dark in both.
            chan[idx] = (if (inBlack) 0 else 249).toByte()
            luma[idx] = (if (inBlack) 13 else 202).toByte()
            // Printed ring lines, one pixel wide, STRONG in the colour channel
            // — this is what fused with the holes when the deviation was
            // merely blurred instead of opened.
            for (ring in 1..6) {
                val rad = face.rings.first { it.value == ring }.diameterMm / 2.0
                if (kotlin.math.abs(r - rad) < mmPerPx * 0.6) { chan[idx] = 20; luma[idx] = 110 }
            }
        }
        for ((hu, hv) in holesMm) {
            val hx = c + hu / mmPerPx
            val hy = c - hv / mmPerPx
            for (j in 0 until side) for (i in 0 until side) {
                val d = hypot(i - hx, j - hy)
                if (d > gaugePx / 2) continue
                val idx = j * side + i
                val u = (i + 0.5 - c) * mmPerPx
                val v = (c - j - 0.5) * mmPerPx
                val inBlack = hypot(u, v) <= blackR
                // A SOLID CORE, then a ramp to the background — which is what
                // a hole measures like. The first version of this painted a
                // ramp all the way from the centre, and the grey opening
                // rightly annihilated it: a feature that is only briefly
                // above threshold is thin in the sense the opening cares
                // about, however wide it looks. Measured on a real hole the
                // eight bands run 108, 132, 145, 174, 195, 201, 202, 202 —
                // flat for the first third and then climbing.
                val t = d / (gaugePx / 2)          // 0 at centre, 1 at the rim
                val ramp = ((t - 0.55) / 0.45).coerceIn(0.0, 1.0)
                if (inBlack) {
                    // In luminance the hole is BRIGHT against the mark…
                    luma[idx] = (150 - (150 - 13) * ramp).roundToInt().toByte()
                    // …and in the colour channel it may be indistinguishable
                    // from the ink, which is exactly the real failure.
                    chan[idx] = if (blackChannelSaturated) 0 else 120
                } else {
                    luma[idx] = (104 + (202 - 104) * ramp).roundToInt().toByte()
                    chan[idx] = (60 + (249 - 60) * ramp).roundToInt().toByte()
                }
            }
        }
        val box = floatArrayOf(
            (c - face.outerRadiusMm / mmPerPx).toFloat(), (c - face.outerRadiusMm / mmPerPx).toFloat(),
            (c + face.outerRadiusMm / mmPerPx).toFloat(), (c + face.outerRadiusMm / mmPerPx).toFloat()
        )
        val reg = TargetRegistration.fromBoundingBox(
            face, box, TargetRegistration.BoxMeaning.OUTER_SCORING_RING, gauge
        )!!
        return reg to (LumaFrame(side, side, chan) to LumaFrame(side, side, luma))
    }

    private fun scoreOf(reg: TargetRegistration, holes: List<com.rfsat.sts.detect.DetectedHole>) =
        holes.sumOf { face.scoreInteger(hypot(it.xMm, it.yMm), gauge / 2.0) }

    @Test
    fun `a shot inside the black is found from luminance when the colour channel is saturated`() {
        ScaleSettings.forcePunctureCheck(true)
        val shots = listOf(0.0 to 12.5, -38.0 to 4.0)      // a 9 and a 6
        val (reg, frames) = card(0.11, shots, blackChannelSaturated = true)
        val (chan, luma) = frames

        // Without luminance the shot in the mark is invisible: in the colour
        // channel it reads exactly as the ink does.
        val without = SourceHoleDetector.detect(reg, chan, gauge, luma = null)
        // With it, both are found and the score is right.
        val with = SourceHoleDetector.detect(reg, chan, gauge, luma = luma)

        assertTrue("colour-only should miss the shot in the black, found ${without.size}",
            without.size < with.size)
        assertEquals(2, with.size)
        assertEquals(15, scoreOf(reg, with))               // 9 + 6
    }

    @Test
    fun `printed ring lines do not fuse with a hole they touch`() {
        // The line is a tenth of the hole's width but stronger in contrast.
        // Blurring trades those off and the line survives; opening deletes it
        // because it is thin, whatever its contrast. On the real card the
        // blurred version rejected 41 of 56 blobs as oversized and reported
        // no shot inside the rings at all.
        ScaleSettings.forcePunctureCheck(true)
        val onTheSixRing = face.rings.first { it.value == 6 }.diameterMm / 2.0
        val (reg, frames) = card(0.11, listOf(-onTheSixRing to 0.0), blackChannelSaturated = false)
        val holes = SourceHoleDetector.detect(reg, frames.first, gauge, luma = frames.second)
        assertEquals(1, holes.size)
        assertEquals(6, face.scoreInteger(hypot(holes[0].xMm, holes[0].yMm), gauge / 2.0))
    }

    @Test
    fun `the same card at half the resolution gives the same score`() {
        ScaleSettings.forcePunctureCheck(true)
        val shots = listOf(0.0 to 12.5, -38.0 to 4.0, -20.9 to -64.6)
        val fine = card(0.11, shots, blackChannelSaturated = true)
        val coarse = card(0.22, shots, blackChannelSaturated = true)
        val a = SourceHoleDetector.detect(fine.first, fine.second.first, gauge, luma = fine.second.second)
        val b = SourceHoleDetector.detect(coarse.first, coarse.second.first, gauge, luma = coarse.second.second)
        assertEquals(3, a.size)
        assertEquals(a.size, b.size)
        assertEquals(scoreOf(fine.first, a), scoreOf(coarse.first, b))
    }
}
