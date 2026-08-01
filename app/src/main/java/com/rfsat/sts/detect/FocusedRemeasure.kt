package com.rfsat.sts.detect

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Measures a hole where something else says one is, rather than searching for
 * it.
 *
 * WHAT THIS IS FOR. Claude's second opinion is good at COUNTING holes and bad
 * at placing them: a vision model's positions carry a few per cent of the
 * image, several millimetres on a 170 mm card, where the app's own detector
 * places a hole it has found to between 0.2 and 1.7 mm. Accepting a suggested
 * position as a shot therefore imports an error of up to a whole ring.
 *
 * So the suggestion is treated as a PLACE TO LOOK, and the answer that gets
 * scored is measured here. The prior is genuinely different from a sweep of
 * the whole card — the question is no longer "is there anything hole-like
 * anywhere?" but "is there a hole HERE, where an independent observer says
 * there is one?" — which is why a lower threshold is defensible in this
 * window and would not be defensible across the card.
 *
 * It still has to LOOK like a hole. A suggestion that lands on blank paper
 * returns null and is reported as unconfirmed rather than quietly placed.
 *
 * Works on the RECTIFIED photograph, which is already on the millimetre grid,
 * so the mapping is one linear step and the source frame — long since
 * released — is not needed.
 */
object FocusedRemeasure {

    /** How far from the suggested point to look, in gauges. Comfortably more
     *  than the model's error and comfortably less than the distance to a
     *  neighbouring shot on any plausible group. */
    private const val SEARCH_GAUGES = 1.6

    /** Levels from the local background before a pixel counts as core. Lower
     *  than the sweep's own margin because this window has a reason to be
     *  looked at; see the note above. */
    private const val CORE_MARGIN = 30.0

    /** Accepted core area, as a fraction of a gauge's area. Wide, because a
     *  drilled or torn hole is not a disc, but not so wide that a shadow
     *  qualifies. */
    private const val MIN_AREA_RATIO = 0.35
    private const val MAX_AREA_RATIO = 3.00

    /** The measured centre must land within this many gauges of where the
     *  suggestion pointed, or it is a different feature and not the answer. */
    private const val MAX_DRIFT_GAUGES = 1.3

    data class Found(
        val xMm: Double,
        val yMm: Double,
        val diameterMm: Double,
        val contrastLevels: Double,
        /** How far the measurement moved from the suggested point. Worth
         *  surfacing: a large drift is a correct measurement of something,
         *  but not necessarily of the thing that was pointed at. */
        val driftMm: Double
    )

    /**
     * [photo] is the rectified card, spanning [uMin]..[uMax] by [vMin]..[vMax]
     * millimetres. [targetU]/[targetV] is where to look.
     */
    fun at(
        photo: LumaFrame,
        uMin: Double, uMax: Double, vMin: Double, vMax: Double,
        blackRadiusMm: Double,
        gaugeMm: Double,
        /** Radii of the printed ring lines, millimetres. Pixels sitting on
         *  one are ignored: inside the aiming mark the rings are printed
         *  WHITE, which is exactly the "brighter than the background" test
         *  that finds a hole there. Without this a shot near the centre
         *  swallows the 10 and 9 rings into its core and is then thrown out
         *  as far too large — which is precisely what happened to the two
         *  central shots on card A. */
        ringRadiiMm: DoubleArray,
        targetU: Double, targetV: Double
    ): Found? {
        val w = photo.width
        val h = photo.height
        if (w < 8 || h < 8 || gaugeMm <= 0.0) return null
        val mmPerPxX = (uMax - uMin) / w
        val mmPerPxY = (vMax - vMin) / h
        if (mmPerPxX <= 0.0 || mmPerPxY <= 0.0) return null
        val gaugePx = gaugeMm / mmPerPxX
        if (gaugePx < 3.0) return null

        val cx = (targetU - uMin) / mmPerPxX
        val cy = (vMax - targetV) / mmPerPxY
        val r = (gaugePx * SEARCH_GAUGES).roundToInt()
        val x0 = (cx - r).roundToInt().coerceAtLeast(0)
        val x1 = (cx + r).roundToInt().coerceAtMost(w - 1)
        val y0 = (cy - r).roundToInt().coerceAtLeast(0)
        val y1 = (cy + r).roundToInt().coerceAtMost(h - 1)
        if (x1 - x0 < 6 || y1 - y0 < 6) return null

        // ---- background from the RIM of the window, per zone ----
        //
        // The middle of the window is the thing being measured, so it must
        // not set the level it is measured against. The outer ring of the
        // window is card, and over a window three gauges across the lighting
        // is flat enough that a median of it is the local background.
        val rimLo = gaugePx * 1.15
        val paperRim = ArrayList<Int>()
        val blackRim = ArrayList<Int>()
        for (j in y0..y1) {
            for (i in x0..x1) {
                val d = hypot(i - cx, j - cy)
                if (d < rimLo || d > r) continue
                val u = uMin + (i + 0.5) * mmPerPxX
                val v = vMax - (j + 0.5) * mmPerPxY
                if (onPrintedRing(hypot(u, v), ringRadiiMm)) continue
                val sample = photo.at(i, j)
                if (hypot(u, v) <= blackRadiusMm) blackRim.add(sample) else paperRim.add(sample)
            }
        }
        val paperBg = median(paperRim)
        val blackBg = median(blackRim)
        if (paperBg == null && blackBg == null) return null

        // ---- the core, each pixel against its own zone ----
        var sw = 0.0; var sx = 0.0; var sy = 0.0
        var count = 0
        var coreSum = 0.0
        for (j in y0..y1) {
            for (i in x0..x1) {
                if (hypot(i - cx, j - cy) > r) continue
                val u = uMin + (i + 0.5) * mmPerPxX
                val v = vMax - (j + 0.5) * mmPerPxY
                val inBlack = hypot(u, v) <= blackRadiusMm
                val bg = (if (inBlack) blackBg else paperBg) ?: continue
                val value = photo.at(i, j).toDouble()
                // A hole is BRIGHTER than the aiming mark and DARKER than the
                // paper. Deciding that per pixel rather than per hole is what
                // lets a shot straddling the black edge be measured at all.
                val isCore = if (inBlack) value > bg + CORE_MARGIN else value < bg - CORE_MARGIN
                if (!isCore) continue
                if (onPrintedRing(hypot(u, v), ringRadiiMm)) continue
                val wgt = abs(value - bg)
                sw += wgt; sx += i * wgt; sy += j * wgt
                coreSum += value
                count++
            }
        }
        if (count == 0 || sw <= 0.0) return null

        val gaugeArea = Math.PI * (gaugePx / 2.0) * (gaugePx / 2.0)
        if (count < MIN_AREA_RATIO * gaugeArea || count > MAX_AREA_RATIO * gaugeArea) return null

        val mx = sx / sw
        val my = sy / sw
        val foundU = uMin + (mx + 0.5) * mmPerPxX
        val foundV = vMax - (my + 0.5) * mmPerPxY
        val drift = hypot(foundU - targetU, foundV - targetV)
        if (drift > MAX_DRIFT_GAUGES * gaugeMm) return null

        val inBlackHere = hypot(foundU, foundV) <= blackRadiusMm
        val bgHere = (if (inBlackHere) blackBg else paperBg) ?: return null

        // IT STILL HAS TO LOOK LIKE A HOLE.
        //
        // Everything above asks "is there a compact patch here that differs
        // from its surroundings?" — and paper texture, a crease and a patch
        // of mottled ink all answer yes. Measured on card A, two suggestions
        // dropped on blank card were placed as shots before this line
        // existed, one of them inside the aiming mark.
        //
        // [PunctureCheck] is the test that already separates a hole from
        // print on this corpus, and it is applied here unchanged rather than
        // re-derived: a hole takes the most material out of its centre, so it
        // has real contrast between core and rim.
        if (!PunctureCheck.isPuncture(photo, mx, my, gaugePx, inBlackHere)) return null

        val dia = 2.0 * Math.sqrt(count / Math.PI) * mmPerPxX
        return Found(foundU, foundV, dia, abs(coreSum / count - bgHere), drift)
    }

    /** Half-width of the band around a printed ring that is treated as ink
     *  rather than card. A ring line is about 0.3 mm wide on the sheet; 0.6
     *  covers it with the blur a photograph adds. */
    private const val RING_BAND_MM = 0.6

    private fun onPrintedRing(rMm: Double, rings: DoubleArray): Boolean {
        for (rr in rings) if (abs(rMm - rr) < RING_BAND_MM) return true
        return false
    }

    private fun median(v: List<Int>): Double? {
        if (v.size < 12) return null
        val s = v.sorted()
        return s[s.size / 2].toDouble()
    }
}
