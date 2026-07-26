package com.rfsat.sts.scoring

import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * ============================================================================
 *  GROUP STATISTICS
 * ============================================================================
 *
 * The score says how the shooter did. The group says why, and it is the half
 * that improves anything. A 94 with a tight group 8 mm low is a sight
 * adjustment; a 94 scattered evenly around the centre is a technique problem.
 * Reporting only the total hides that distinction, so the app reports both.
 *
 * FIVE MEASURES, EACH ANSWERING A DIFFERENT QUESTION.
 *
 *   Mean point of impact — where the group is. This is the ONLY input to the
 *   sight correction, and the whole reason the rest of this file exists.
 *
 *   Extreme spread — the largest centre-to-centre distance in the group. The
 *   number shooters quote, and the worst estimator in common use: it depends
 *   only on the two worst shots, so it grows with shot count and has enormous
 *   variance. Reported because it is what everyone expects to see.
 *
 *   Mean radius — the average distance from the group centre. Uses every
 *   shot, converges quickly, and is roughly three times more efficient than
 *   extreme spread at the same sample size. The one to watch across sessions.
 *
 *   Radial standard deviation — the dispersion behind the mean radius.
 *
 *   R50 — the radius containing half the shots. Robust to one flyer, which
 *   extreme spread emphatically is not.
 *
 * A NOTE ON BIAS. The mean point of impact is computed from the sample, so
 * with n shots it carries its own uncertainty of roughly sigma/sqrt(n). At
 * n = 3 that is more than half a group radius, which is why [mpiUncertaintyMm]
 * is reported alongside it and why the correction advice refuses to be
 * confident about a three-shot group. Dialling a correction off three shots
 * is, statistically, mostly dialling in the noise.
 */
data class GroupStatistics(
    val shotCount: Int,
    /** Mean point of impact, target-plane mm. */
    val mpiXMm: Double,
    val mpiYMm: Double,
    /** Standard error of the MPI, mm — how well the centre is actually known. */
    val mpiUncertaintyMm: Double,
    /** Largest centre-to-centre separation, mm. */
    val extremeSpreadMm: Double,
    /** Mean distance from the group centre, mm. */
    val meanRadiusMm: Double,
    /** Standard deviation of those distances, mm. */
    val radialSdMm: Double,
    /** Radius containing half the shots, mm. */
    val r50Mm: Double,
    /** Separate horizontal and vertical dispersion — a group that is twice as
     *  tall as it is wide is telling you about position or breathing, not
     *  about the load. */
    val horizontalSdMm: Double,
    val verticalSdMm: Double
) {
    val mpiOffsetMm: Double get() = hypot(mpiXMm, mpiYMm)

    /** Group size in minutes of angle at the given distance. Uses the small
     *  angle relation MOA ≈ (mm / (m * 1000)) * 3437.75. */
    fun extremeSpreadMoa(distanceM: Double): Double =
        if (distanceM > 0) extremeSpreadMm / distanceM * 3.43775 else 0.0

    fun extremeSpreadMrad(distanceM: Double): Double =
        if (distanceM > 0) extremeSpreadMm / distanceM else 0.0

    companion object {

        val EMPTY = GroupStatistics(0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)

        /**
         * Computes the statistics over [shots]. Misses are excluded: a shot
         * that went into the berm has no measured position, and including a
         * "position" the detector never saw would corrupt the very centre the
         * correction is derived from.
         */
        fun of(shots: List<Shot>): GroupStatistics {
            val pts = shots.filter { !it.miss }
            val n = pts.size
            if (n == 0) return EMPTY

            val mx = pts.sumOf { it.xMm } / n
            val my = pts.sumOf { it.yMm } / n

            val radii = pts.map { hypot(it.xMm - mx, it.yMm - my) }.sorted()
            val meanR = radii.average()

            // Sample standard deviation (n-1). With n = 1 there is no
            // dispersion information at all and the honest answer is zero,
            // not a division by zero.
            val radialSd = if (n > 1)
                sqrt(radii.sumOf { (it - meanR) * (it - meanR) } / (n - 1))
            else 0.0

            val hSd = if (n > 1)
                sqrt(pts.sumOf { (it.xMm - mx) * (it.xMm - mx) } / (n - 1)) else 0.0
            val vSd = if (n > 1)
                sqrt(pts.sumOf { (it.yMm - my) * (it.yMm - my) } / (n - 1)) else 0.0

            // Extreme spread: every pair. O(n^2), and n is at most a few
            // hundred, so the obvious implementation is the right one.
            var es = 0.0
            for (i in 0 until n) {
                for (j in i + 1 until n) {
                    val d = hypot(pts[i].xMm - pts[j].xMm, pts[i].yMm - pts[j].yMm)
                    if (d > es) es = d
                }
            }

            // R50 by linear interpolation into the sorted radii, so it moves
            // smoothly as shots are added rather than stepping.
            val r50 = when {
                n == 1 -> 0.0
                else -> {
                    val pos = 0.5 * (n - 1)
                    val lo = pos.toInt()
                    val hi = (lo + 1).coerceAtMost(n - 1)
                    radii[lo] + (pos - lo) * (radii[hi] - radii[lo])
                }
            }

            // Standard error of the mean position. The two axes are combined
            // in quadrature and divided by sqrt(n) — the usual result, and
            // the number that tells you whether a correction is justified.
            val mpiSe = if (n > 1) sqrt((hSd * hSd + vSd * vSd) / n) else 0.0

            return GroupStatistics(
                shotCount = n,
                mpiXMm = mx, mpiYMm = my,
                mpiUncertaintyMm = mpiSe,
                extremeSpreadMm = es,
                meanRadiusMm = meanR,
                radialSdMm = radialSd,
                r50Mm = r50,
                horizontalSdMm = hSd,
                verticalSdMm = vSd
            )
        }
    }
}
