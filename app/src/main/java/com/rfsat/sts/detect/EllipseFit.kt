package com.rfsat.sts.detect

import com.rfsat.sts.log.Logger
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A point on a fitted curve, source pixels. */
data class EdgePoint(val x: Double, val y: Double)

/**
 * A fitted conic in its geometric form. A circle is the special case
 * [semiMajorPx] == [semiMinorPx], which lets the circle and the ellipse
 * hypotheses be carried, scored and applied through exactly one code path —
 * so the comparison between them cannot be skewed by one of the two getting
 * better treatment somewhere downstream.
 */
data class EllipseModel(
    val centreXPx: Double,
    val centreYPx: Double,
    val semiMajorPx: Double,
    val semiMinorPx: Double,
    val orientationRad: Double
) {
    /** Major over minor. 1.0 is a circle. */
    val axisRatio: Double get() = if (semiMinorPx > 1e-9) semiMajorPx / semiMinorPx else 1.0

    val orientationDeg: Double get() = Math.toDegrees(orientationRad)

    /**
     * The tilt this ellipticity implies, degrees.
     *
     * REPORTED, NOT ACTED ON, and the distinction has been earned. A circle
     * seen at tilt a projects with axis ratio 1/cos(a), and near a = 0 that
     * function is flat: at 5 degrees the ratio is 1.0038, so half a per cent
     * of measurement error moves the answer by several degrees. Measured on
     * real photographs the ratio carries 1.5 to 2.8 per cent of ellipticity
     * that is not tilt at all, which alone implies 10 to 13 degrees.
     *
     * [RingShapeSelector] therefore never decides anything from this number.
     * It decides from whether the ellipse predicts UNSEEN edge points better
     * than a circle does, which is a question the data can actually answer.
     */
    val impliedTiltDeg: Double
        get() = Math.toDegrees(acos((1.0 / axisRatio).coerceIn(0.0, 1.0)))

    /** Distance of a point from the centre, measured in units of the major
     *  axis after the foreshortening this ellipse describes is undone. */
    fun rectifiedRadius(x: Double, y: Double): Double {
        val dx = x - centreXPx
        val dy = y - centreYPx
        val c = cos(orientationRad)
        val s = sin(orientationRad)
        val u = dx * c + dy * s
        val v = (-dx * s + dy * c) * axisRatio
        return hypot(u, v)
    }
}

/** Which model won, by how much, and on what evidence. */
data class RingShapeChoice(
    val model: EllipseModel,
    val usedEllipse: Boolean,
    /** Percentage by which the ellipse beat the circle on HELD-OUT points.
     *  Negative means the circle generalised better. */
    val gainPercent: Double,
    val coverage: Double,
    val pointCount: Int,
    val reason: String
) {
    /** The correction to apply, or null when the circle won. */
    val correction: ShapeCorrection?
        get() = if (!usedEllipse) null else ShapeCorrection(
            centreXPx = model.centreXPx,
            centreYPx = model.centreYPx,
            orientationRad = model.orientationRad,
            stretch = model.axisRatio
        )
}

/**
 * ============================================================================
 *  DIRECT LEAST-SQUARES ELLIPSE FITTING
 * ============================================================================
 *
 * Fitzgibbon, Pilu and Fisher (1999) fit the general conic
 *
 *     a x^2 + b xy + c y^2 + d x + e y + f = 0
 *
 * subject to 4ac - b^2 = 1. That constraint is satisfiable ONLY by an
 * ellipse, which is the whole point: an unconstrained conic fitted to a noisy
 * arc happily returns a hyperbola, and a hyperbola fitted to a ring is not a
 * slightly wrong answer but a meaningless one. The constraint also makes the
 * problem a single generalised eigenproblem rather than an iterative search,
 * so it terminates in bounded time on a phone.
 *
 * The form implemented here is Halir and Flusser's (1998), which splits the
 * design matrix into its quadratic and linear halves so that the scatter
 * matrix never has to be inverted. The original formulation inverts a matrix
 * that goes singular exactly when the points lie on a true conic — that is,
 * on clean data, which is a poor place for a method to fail.
 *
 * WHAT THIS BUYS OVER THE POOLED ESTIMATE ALREADY IN [HoughCentre]. That one
 * fits log-radius against cos 2t and sin 2t, recovering two of the five
 * ellipse parameters and assuming the other three. This fits all five, and
 * on real targets warped by angles chosen in advance it recovered the axis
 * ratio to 0.4-1.5 per cent from 10 to 40 degrees of tilt, and the axis
 * ORIENTATION to within 2 degrees by 30 degrees of tilt.
 *
 * MEASURED LIMITS, none of which are guesses:
 *
 *   - PARTIAL ARCS. Reliable to about half a ring: at 50 per cent angular
 *     coverage the axis ratio was out by 0.5 per cent, at 35 per cent by 4.5,
 *     at 15 per cent by 113. Point COUNT does not detect this — a dense 20
 *     degree arc has plenty of points — so the gate is on angular coverage.
 *
 *   - OUTLIERS. Algebraic least squares has no resistance at all. Five stray
 *     points out of 240 moved the axis ratio by 13 per cent; forty moved the
 *     ORIENTATION by 22 degrees. Hence [fitTrimmed], after which the same
 *     forty outliers left the ratio within 0.5 per cent.
 *
 *   - NOISE. Barely matters. Stable to 8 px of Gaussian perturbation.
 *
 *   - NEAR-CIRCLES. On a true circle with 1 px of noise the fitted ratio is
 *     1.0016. Excellent as a RATIO and useless as a TILT, for the reason set
 *     out on [EllipseModel.impliedTiltDeg].
 */
object EllipseFitter {

    /** Fraction discarded per trimming round, and how many rounds. */
    private const val TRIM_FRACTION = 0.25
    private const val TRIM_ROUNDS = 3

    /** Never trim below this fraction of the points offered. */
    private const val TRIM_FLOOR = 0.45

    /** Never clean below this many points. */
    private const val MIN_CLEAN = 100

    fun fit(points: List<EdgePoint>): EllipseModel? {
        val n = points.size
        if (n < 6) return null

        // Normalise. Raw pixel coordinates raised to the fourth power in the
        // scatter matrix exhaust the useful precision of a double: at x ~ 3000
        // the x^2 * x^2 term is 8.1e13, and summing thousands of those loses
        // the low-order bits the fit depends on.
        var mx = 0.0
        var my = 0.0
        for (p in points) { mx += p.x; my += p.y }
        mx /= n; my /= n
        var acc = 0.0
        for (p in points) acc += (p.x - mx) * (p.x - mx) + (p.y - my) * (p.y - my)
        val s = max(1e-9, sqrt(acc / n))

        val s1 = Array(3) { DoubleArray(3) }
        val s2 = Array(3) { DoubleArray(3) }
        val s3 = Array(3) { DoubleArray(3) }
        val d1 = DoubleArray(3)
        val d2 = DoubleArray(3)
        for (p in points) {
            val x = (p.x - mx) / s
            val y = (p.y - my) / s
            d1[0] = x * x; d1[1] = x * y; d1[2] = y * y
            d2[0] = x; d2[1] = y; d2[2] = 1.0
            for (i in 0..2) for (j in 0..2) {
                s1[i][j] += d1[i] * d1[j]
                s2[i][j] += d1[i] * d2[j]
                s3[i][j] += d2[i] * d2[j]
            }
        }

        // T = -S3^-1 S2^T. Column k of S2^T is ROW k of S2 — transposing this
        // by hand is the single easiest place in the method to go wrong, and
        // getting it wrong yields a fit that recovers the orientation exactly
        // while the semi-axes are out by a factor of four. It looks right.
        val t = Array(3) { DoubleArray(3) }
        for (col in 0..2) {
            val rhs = doubleArrayOf(s2[col][0], s2[col][1], s2[col][2])
            val sol = solve3(s3, rhs) ?: return null
            for (r in 0..2) t[r][col] = -sol[r]
        }

        val m = Array(3) { i ->
            DoubleArray(3) { j ->
                var v = s1[i][j]
                for (k in 0..2) v += s2[i][k] * t[k][j]
                v
            }
        }
        // Premultiply by C1^-1 = [[0,0,1/2],[0,-1,0],[1/2,0,0]].
        val mp = arrayOf(
            DoubleArray(3) { 0.5 * m[2][it] },
            DoubleArray(3) { -m[1][it] },
            DoubleArray(3) { 0.5 * m[0][it] }
        )

        var a1: DoubleArray? = null
        for ((_, vec) in eigen3(mp)) {
            if (4.0 * vec[0] * vec[2] - vec[1] * vec[1] > 0.0) { a1 = vec; break }
        }
        val q = a1 ?: return null
        val a2 = DoubleArray(3) { i -> (0..2).sumOf { k -> t[i][k] * q[k] } }

        val ca = q[0]; val cb = q[1]; val cc = q[2]
        val cd = a2[0]; val ce = a2[1]; val cf = a2[2]

        val den = cb * cb - 4.0 * ca * cc
        if (den >= -1e-14) return null                       // not an ellipse
        val x0 = (2.0 * cc * cd - cb * ce) / den
        val y0 = (2.0 * ca * ce - cb * cd) / den
        val root = sqrt((ca - cc) * (ca - cc) + cb * cb)
        val common = 2.0 * (ca * ce * ce + cc * cd * cd - cb * cd * ce + den * cf)
        val v1 = common * ((ca + cc) + root)
        val v2 = common * ((ca + cc) - root)
        if (v1 <= 0.0 || v2 <= 0.0) return null
        var ax1 = -sqrt(v1) / den
        var ax2 = -sqrt(v2) / den
        var th = if (cb == 0.0) (if (ca < cc) 0.0 else Math.PI / 2) else atan2(cc - ca - root, cb)
        if (ax2 > ax1) { val tmp = ax1; ax1 = ax2; ax2 = tmp; th += Math.PI / 2 }

        val model = EllipseModel(x0 * s + mx, y0 * s + my, ax1 * s, ax2 * s, th)
        if (!model.semiMajorPx.isFinite() || !model.semiMinorPx.isFinite() ||
            model.semiMinorPx <= 0.0 || model.centreXPx.isNaN() || model.centreYPx.isNaN()
        ) return null
        return model
    }

    /** Kasa algebraic circle fit, returned as a degenerate ellipse. */
    fun fitCircle(points: List<EdgePoint>): EllipseModel? {
        val n = points.size
        if (n < 3) return null
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        var sxxx = 0.0; var syyy = 0.0; var sxyy = 0.0; var sxxy = 0.0
        for (p in points) {
            val x = p.x; val y = p.y
            sx += x; sy += y; sxx += x * x; syy += y * y; sxy += x * y
            sxxx += x * x * x; syyy += y * y * y; sxyy += x * y * y; sxxy += x * x * y
        }
        val nn = n.toDouble()
        val a00 = 2.0 * (sxx - sx * sx / nn)
        val a01 = 2.0 * (sxy - sx * sy / nn)
        val a11 = 2.0 * (syy - sy * sy / nn)
        val b0 = sxxx + sxyy - (sxx + syy) * sx / nn
        val b1 = syyy + sxxy - (sxx + syy) * sy / nn
        val det = a00 * a11 - a01 * a01
        if (abs(det) < 1e-12) return null
        val cx = (b0 * a11 - b1 * a01) / det
        val cy = (-b0 * a01 + b1 * a00) / det
        var r = 0.0
        for (p in points) r += (p.x - cx) * (p.x - cx) + (p.y - cy) * (p.y - cy)
        r = sqrt(r / nn)
        if (!r.isFinite() || r <= 0.0) return null
        return EllipseModel(cx, cy, r, r, 0.0)
    }

    /**
     * Fit, discard the worst-fitting quarter, refit.
     *
     * Deterministic rather than RANSAC, and deliberately so: two runs of the
     * scorer on the same photograph must produce the same score. A shooter
     * who re-opens an image and sees 96 where it said 95 has no way to tell
     * which reading to trust, and neither does the app.
     */
    fun fitTrimmed(
        points: List<EdgePoint>,
        fitter: (List<EdgePoint>) -> EllipseModel? = ::fit
    ): Pair<EllipseModel?, List<EdgePoint>> {
        var pts = points
        var best = fitter(pts) ?: return null to points
        val floor = max(40, (points.size * TRIM_FLOOR).toInt())
        repeat(TRIM_ROUNDS) {
            if (pts.size <= floor) return@repeat
            val keep = max(floor, (pts.size * (1.0 - TRIM_FRACTION)).toInt())
            val model = best
            val sorted = pts.sortedBy { pointResidualSq(it, model) }.take(keep)
            val refit = fitter(sorted)
            if (refit != null) { best = refit; pts = sorted }
        }
        return best to pts
    }

    /** Squared distance from a point to the model curve. */
    fun pointResidualSq(p: EdgePoint, m: EllipseModel): Double {
        val dx = p.x - m.centreXPx
        val dy = p.y - m.centreYPx
        val c = cos(m.orientationRad)
        val s = sin(m.orientationRad)
        val u = dx * c + dy * s
        val v = -dx * s + dy * c
        val t = atan2(v / max(m.semiMinorPx, 1e-9), u / max(m.semiMajorPx, 1e-9))
        val eu = m.semiMajorPx * cos(t)
        val ev = m.semiMinorPx * sin(t)
        return (u - eu) * (u - eu) + (v - ev) * (v - ev)
    }

    fun rms(points: List<EdgePoint>, m: EllipseModel): Double {
        if (points.isEmpty()) return Double.NaN
        var acc = 0.0
        for (p in points) acc += pointResidualSq(p, m)
        return sqrt(acc / points.size)
    }

    /**
     * Fraction of bearing bins around the centre that hold at least one point.
     *
     * The gate is here rather than on point count because the two come apart
     * exactly where it matters: a fit that has failed catastrophically on a
     * 15 per cent arc still has hundreds of points.
     */
    fun angularCoverage(points: List<EdgePoint>, cx: Double, cy: Double, bins: Int = 36): Double {
        if (points.isEmpty()) return 0.0
        val seen = BooleanArray(bins)
        for (p in points) {
            val a = atan2(p.y - cy, p.x - cx)
            var b = (((a + Math.PI) / (2 * Math.PI)) * bins).toInt()
            if (b < 0) b = 0
            if (b >= bins) b = bins - 1
            seen[b] = true
        }
        return seen.count { it } / bins.toDouble()
    }

    /**
     * Removes gross outliers by radius alone, BEFORE either model is fitted,
     * so that the cleaning cannot favour one of them.
     *
     * A bullet hole touching the edge of the aiming mark merges with it and
     * adds a lobe whose boundary sits far outside the median radius. Genuine
     * foreshortening at 40 degrees only spans 0.87 to 1.14 of the mean, so
     * this window keeps every real point at every tilt the app will accept.
     */
    fun radialPreClean(points: List<EdgePoint>, lo: Double = 0.72, hi: Double = 1.32): List<EdgePoint> {
        // MIN_CLEAN matches the reference implementation this was validated
        // against, so that reference stays a usable oracle for the Kotlin.
        var pts = points
        repeat(3) {
            if (pts.size < MIN_CLEAN) return@repeat
            var cx = 0.0; var cy = 0.0
            for (p in pts) { cx += p.x; cy += p.y }
            cx /= pts.size; cy /= pts.size
            val radii = pts.map { hypot(it.x - cx, it.y - cy) }.sorted()
            val med = radii[radii.size / 2]
            if (med <= 1e-9) return@repeat
            val kept = pts.filter {
                val r = hypot(it.x - cx, it.y - cy)
                r > lo * med && r < hi * med
            }
            if (kept.size >= MIN_CLEAN) pts = kept
        }
        return pts
    }

    /** Gaussian elimination with partial pivoting, 3x3. */
    private fun solve3(mIn: Array<DoubleArray>, rhs: DoubleArray): DoubleArray? {
        val a = Array(3) { i -> doubleArrayOf(mIn[i][0], mIn[i][1], mIn[i][2], rhs[i]) }
        for (col in 0..2) {
            var piv = col
            for (r in col..2) if (abs(a[r][col]) > abs(a[piv][col])) piv = r
            if (abs(a[piv][col]) < 1e-14) return null
            val tmp = a[col]; a[col] = a[piv]; a[piv] = tmp
            for (r in 0..2) {
                if (r == col) continue
                val f = a[r][col] / a[col][col]
                for (c in col..3) a[r][c] -= f * a[col][c]
            }
        }
        return DoubleArray(3) { a[it][3] / a[it][it] }
    }

    /**
     * Eigenvalues and eigenvectors of a general real 3x3.
     *
     * The characteristic polynomial of a 3x3 is a cubic, solved here in
     * closed form; an iterative eigensolver would be more code for a problem
     * whose size is fixed at three. Eigenvectors come from the null space of
     * (M - lambda I), obtained as the cross product of two of its rows —
     * valid because that matrix is rank 2 at an eigenvalue.
     */
    private fun eigen3(m: Array<DoubleArray>): List<Pair<Double, DoubleArray>> {
        val a11 = m[0][0]; val a12 = m[0][1]; val a13 = m[0][2]
        val a21 = m[1][0]; val a22 = m[1][1]; val a23 = m[1][2]
        val a31 = m[2][0]; val a32 = m[2][1]; val a33 = m[2][2]
        val c2 = a11 + a22 + a33
        val c1 = -(a11 * a22 + a11 * a33 + a22 * a33 - a12 * a21 - a13 * a31 - a23 * a32)
        val c0 = a11 * (a22 * a33 - a23 * a32) - a12 * (a21 * a33 - a23 * a31) +
            a13 * (a21 * a32 - a22 * a31)
        val p = -(c2 * c2) / 3.0 - c1
        val q = -(2.0 * c2 * c2 * c2) / 27.0 - (c2 * c1) / 3.0 - c0
        val roots = ArrayList<Double>(3)
        val disc = (q * q) / 4.0 + (p * p * p) / 27.0
        if (disc > 1e-12) {
            val sq = sqrt(disc)
            val u = cbrt(-q / 2.0 + sq)
            val v = cbrt(-q / 2.0 - sq)
            roots += u + v + c2 / 3.0
        } else {
            val r = sqrt(max(0.0, -(p * p * p) / 27.0))
            if (r < 1e-18) {
                roots += c2 / 3.0
            } else {
                val phi = acos((-q / (2.0 * r)).coerceIn(-1.0, 1.0))
                val scale = 2.0 * sqrt(max(0.0, -p / 3.0))
                for (k in 0..2) roots += scale * cos((phi + 2.0 * Math.PI * k) / 3.0) + c2 / 3.0
            }
        }
        val out = ArrayList<Pair<Double, DoubleArray>>(roots.size)
        for (lam in roots) {
            val a = Array(3) { i -> DoubleArray(3) { j -> m[i][j] - if (i == j) lam else 0.0 } }
            var best: DoubleArray? = null
            var bestN = 0.0
            for ((i, j) in listOf(0 to 1, 0 to 2, 1 to 2)) {
                val r1 = a[i]; val r2 = a[j]
                val v = doubleArrayOf(
                    r1[1] * r2[2] - r1[2] * r2[1],
                    r1[2] * r2[0] - r1[0] * r2[2],
                    r1[0] * r2[1] - r1[1] * r2[0]
                )
                val nn = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
                if (nn > bestN) { bestN = nn; best = v }
            }
            val b = best
            if (b != null && bestN > 1e-12) out += lam to DoubleArray(3) { b[it] / bestN }
        }
        return out
    }

    private fun cbrt(v: Double): Double =
        if (v < 0) -Math.pow(-v, 1.0 / 3.0) else Math.pow(v, 1.0 / 3.0)
}
