package com.rfsat.sts.detect

import com.rfsat.sts.log.Logger
import com.rfsat.sts.targets.TargetFace
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Centre and scale from the PRINTED RING LINES THEMSELVES, by fitting a
 * circle to each one.
 *
 * WHAT IT IS FOR, and what it turned out NOT to be for.
 *
 * It was written on the strength of a comparison that was WRONG, and the
 * mistake is recorded here because the shape of it is worth remembering.
 * [RingFinder] reported a ring pitch of 75.54 px on the user's card where
 * fitting circles to the same rings by hand gave 73.95 px, and that 2.1 per
 * cent looked like a scale error being read straight off every score. It was
 * not. Every pixel figure in a RingFit is in CORRECTED coordinates, and the
 * corrected canvas for that card is 1575 px wide against a 1536 px source —
 * about 2.5 per cent. The two numbers were measured in different frames. Two
 * quantities that disagreed by exactly the amount the frames differ by, and
 * the conclusion drawn was that one of them was wrong.
 *
 * Measured properly, in the frame the fit actually lives in, this recovers a
 * pitch of 8.0008 mm where the true value is 8.000, with every ring inside
 * 0.004 mm — and the ladder recovers 8.000 as well. On that card it changes
 * the reported radii by less than a tenth of a millimetre. It is kept, off by
 * default, for the range corpus: its per-ring residuals (0.46 to 0.83 px) are
 * a direct measure of whether a card is flat and a fit honest, which the
 * ladder's single averaged figure cannot give. On an ANGLED photograph, where
 * the ladder has always been weakest, it may yet earn its place. On a flat
 * scan it does not, and this note is here so that nobody re-derives the
 * 2.1 per cent and believes it.
 */
object RingFamilyFit {

    /** Rays cast per ring. 720 is half a degree; the cost is trivial beside
     *  the ring search that produced the seeds. */
    private const val RAYS = 720

    /** How far either side of the seed radius to hunt for the line, in
     *  pixels. Wider than the seed error the profile can plausibly have,
     *  narrower than half a ring pitch so it cannot lock onto a neighbour. */
    private const val SEARCH_PX = 9.0

    /** Sub-pixel step along each ray. */
    private const val STEP_PX = 0.5

    /** A sample must be at least this dark to count as a printed line. Kept
     *  generous: the fit is robust to a few bad rays, and losing a whole ring
     *  because a card is dim costs far more than a little noise. */
    private const val LINE_MAX_LUMA = 150

    /** Sectors within this cosine of an axis are skipped, because the ring
     *  NUMERALS are printed there and read exactly as dark as the lines. */
    private const val AXIS_GUARD = 0.22

    /** Centre re-estimations. Three is comfortably past convergence on every
     *  frame tried; the second already moves it by well under a pixel. */
    private const val ITERATIONS = 3

    /** A fitted circle is accepted as a ring only if it lands this close to a
     *  catalogue radius, as a fraction of the ring pitch. Beyond it the
     *  circle is not a ring: on this card the footer text produced four more
     *  dark arcs at 966 to 1023 px, well outside the outermost ring, and an
     *  earlier version that simply counted circles outwards from the black
     *  edge gave them ring numbers and corrupted the pitch. */
    private const val ASSIGN_TOLERANCE = 0.35

    /** Fewer than this many assigned rings and the scale is not worth having;
     *  the caller keeps whatever it had. */
    private const val MIN_RINGS = 3

    data class Ring(val value: Int, val radiusPx: Double, val residualPx: Double)

    data class Result(
        val centreXPx: Double,
        val centreYPx: Double,
        /** Millimetres per pixel, from a least-squares fit over every ring. */
        val mmPerPx: Double,
        /** Free intercept of that fit, millimetres. A DIAGNOSTIC, not a
         *  correction: a large one means the centre or the ring assignment is
         *  wrong, and the caller is expected to say so rather than use it. */
        val interceptMm: Double,
        val rings: List<Ring>,
        /** Worst departure of a ring from its catalogue radius, millimetres. */
        val maxResidualMm: Double
    ) {
        val pitchMm: Double
            get() {
                if (rings.size < 2) return Double.NaN
                val s = rings.sortedBy { it.radiusPx }
                val gaps = s.zipWithNext { a, b -> b.radiusPx - a.radiusPx }
                return mmPerPx * gaps.average()
            }
    }

    /**
     * Refines [seedCx]/[seedCy] and derives the scale, given the ring radii a
     * cruder search already found and the face they are supposed to belong
     * to. Returns null when too few rings survive, in which case the caller
     * must keep the scale it already had.
     */
    fun refine(
        frame: LumaFrame,
        seedCx: Double,
        seedCy: Double,
        seedRingsPx: List<Double>,
        face: TargetFace,
        blackRadiusPx: Double
    ): Result? {
        if (seedRingsPx.isEmpty() || blackRadiusPx <= 0.0) return null
        val pitchMm = face.ringPitchMm ?: return null
        if (pitchMm <= 0.0) return null

        var cx = seedCx
        var cy = seedCy
        var fitted: List<Triple<Double, Double, Double>> = emptyList()  // r, resid, unused
        var circles: List<DoubleArray> = emptyList()                    // cx, cy, r, resid

        repeat(ITERATIONS) {
            circles = seedRingsPx.mapNotNull { r0 -> fitOneRing(frame, cx, cy, r0) }
            if (circles.isEmpty()) return@repeat
            cx = circles.map { it[0] }.average()
            cy = circles.map { it[1] }.average()
        }
        if (circles.isEmpty()) return null
        fitted = circles.map { Triple(it[2], it[3], 0.0) }

        // ---- which ring is which ----
        val coarse = blackRadiusMm(face) / blackRadiusPx
        val tol = pitchMm * ASSIGN_TOLERANCE
        val byValue = HashMap<Int, Ring>()
        for ((r, resid, _) in fitted) {
            val mm = r * coarse
            var bestV = -1
            var bestD = Double.MAX_VALUE
            for (v in 1..10) {
                val rad = ringRadiusMm(face, v) ?: continue
                val d = abs(mm - rad)
                if (d < bestD) { bestD = d; bestV = v }
            }
            if (bestV < 0 || bestD > tol) continue
            val existing = byValue[bestV]
            if (existing == null || resid < existing.residualPx) {
                byValue[bestV] = Ring(bestV, r, resid)
            }
        }
        val rings = byValue.values.sortedBy { it.value }
        if (rings.size < MIN_RINGS) {
            Logger.w("RingFamilyFit", "only ${rings.size} of ${fitted.size} circles landed on a ring of ${face.name}")
            return null
        }

        // ---- scale, from every ring at once ----
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
        val n = rings.size
        for (r in rings) {
            val mm = ringRadiusMm(face, r.value) ?: continue
            sx += r.radiusPx; sy += mm; sxx += r.radiusPx * r.radiusPx; sxy += r.radiusPx * mm
        }
        val den = n * sxx - sx * sx
        if (abs(den) < 1e-9) return null
        val slope = (n * sxy - sx * sy) / den
        val inter = (sy - slope * sx) / n
        if (slope <= 0.0) return null

        var worst = 0.0
        for (r in rings) {
            val mm = ringRadiusMm(face, r.value) ?: continue
            worst = maxOf(worst, abs(mm - (slope * r.radiusPx + inter)))
        }
        return Result(cx, cy, slope, inter, rings, worst)
    }

    /** Casts rays, takes the darkest sub-pixel sample on each, fits a circle. */
    private fun fitOneRing(frame: LumaFrame, cx: Double, cy: Double, r0: Double): DoubleArray? {
        val xs = ArrayList<Double>(RAYS)
        val ys = ArrayList<Double>(RAYS)
        for (i in 0 until RAYS) {
            val t = 2.0 * Math.PI * i / RAYS
            val ct = cos(t); val st = sin(t)
            if (abs(ct) < AXIS_GUARD || abs(st) < AXIS_GUARD) continue
            var bestV = Int.MAX_VALUE
            var bestR = -1.0
            var rr = r0 - SEARCH_PX
            while (rr <= r0 + SEARCH_PX) {
                val x = cx + rr * ct; val y = cy + rr * st
                if (x >= 1 && y >= 1 && x < frame.width - 1 && y < frame.height - 1) {
                    val v = frame.at(x.roundToInt(), y.roundToInt())
                    if (v < bestV) { bestV = v; bestR = rr }
                }
                rr += STEP_PX
            }
            if (bestR > 0 && bestV <= LINE_MAX_LUMA) {
                xs.add(cx + bestR * ct); ys.add(cy + bestR * st)
            }
        }
        if (xs.size < 30) return null
        return kasaCircle(xs, ys)
    }

    /**
     * Algebraic circle fit (Kåsa): minimises the residual of
     * x^2 + y^2 = 2ax + 2by + c, which is linear in a, b, c.
     *
     * Geometric fitting would be marginally better conditioned, but the
     * points here surround the centre completely, and that is the case where
     * the algebraic fit's known bias — it under-weights sparse arcs —
     * vanishes. It also cannot fail to converge, which matters on a phone.
     */
    private fun kasaCircle(xs: List<Double>, ys: List<Double>): DoubleArray? {
        val n = xs.size
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        var sx = 0.0; var sy = 0.0
        var sxz = 0.0; var syz = 0.0; var sz = 0.0
        for (i in 0 until n) {
            val x = xs[i]; val y = ys[i]; val z = x * x + y * y
            sx += x; sy += y; sz += z
            sxx += x * x; syy += y * y; sxy += x * y
            sxz += x * z; syz += y * z
        }
        // normal equations for [2a, 2b, c]
        val a11 = sxx - sx * sx / n
        val a12 = sxy - sx * sy / n
        val a22 = syy - sy * sy / n
        val b1 = (sxz - sx * sz / n) / 2.0
        val b2 = (syz - sy * sz / n) / 2.0
        val det = a11 * a22 - a12 * a12
        if (abs(det) < 1e-9) return null
        val cx = (b1 * a22 - b2 * a12) / det
        val cy = (a11 * b2 - a12 * b1) / det
        var rsum = 0.0
        for (i in 0 until n) rsum += hypot(xs[i] - cx, ys[i] - cy)
        val r = rsum / n
        var v = 0.0
        for (i in 0 until n) { val d = hypot(xs[i] - cx, ys[i] - cy) - r; v += d * d }
        return doubleArrayOf(cx, cy, r, sqrt(v / n))
    }

    /** Outer radius of ring [value], millimetres, from the face's own table. */
    private fun ringRadiusMm(face: TargetFace, value: Int): Double? =
        face.rings.firstOrNull { it.value == value }?.let { it.diameterMm / 2.0 }

    private fun blackRadiusMm(face: TargetFace): Double = face.blackDiameterMm / 2.0
}
