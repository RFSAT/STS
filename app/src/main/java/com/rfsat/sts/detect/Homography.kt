package com.rfsat.sts.detect

/**
 * ============================================================================
 *  PLANE-TO-PLANE HOMOGRAPHY
 * ============================================================================
 *
 * A target face is flat and a camera is a pinhole, so the map from the target
 * plane to the image plane is exactly a projective transform — eight degrees
 * of freedom, fully determined by four point correspondences. Nothing weaker
 * will do: an affine fit cannot represent the keystoning you get from a phone
 * propped at an angle on the bench, and a similarity fit cannot even do that
 * much. Four corners is also the most a user can be asked to tap.
 *
 *      [ x' ]   [ h0 h1 h2 ] [ u ]
 *      [ y' ] = [ h3 h4 h5 ] [ v ]      x = x'/w',  y = y'/w'
 *      [ w' ]   [ h6 h7  1 ] [ 1 ]
 *
 * with (u,v) target-plane millimetres and (x,y) image pixels. h8 is fixed at
 * 1: the transform is homogeneous, so one degree of freedom is pure scale and
 * pinning it leaves exactly the eight unknowns the four correspondences give.
 *
 * Each correspondence contributes two linear equations,
 *
 *      u h0 + v h1 + h2 - x u h6 - x v h7 = x
 *      u h3 + v h4 + h5 - y u h6 - y v h7 = y
 *
 * so four points give an 8x8 system, solved here by Gaussian elimination with
 * partial pivoting. Eight unknowns is far too small to justify anything
 * cleverer, and partial pivoting is what keeps a near-degenerate tap (three
 * corners almost collinear, because the user tapped along one edge) from
 * producing garbage silently instead of failing.
 */
class Homography private constructor(private val h: DoubleArray) {

    /** Target-plane millimetres -> image pixels. */
    fun mmToPx(u: Double, v: Double): Pair<Double, Double> {
        val w = h[6] * u + h[7] * v + 1.0
        if (kotlin.math.abs(w) < 1e-12) return Double.NaN to Double.NaN
        return (h[0] * u + h[1] * v + h[2]) / w to (h[3] * u + h[4] * v + h[5]) / w
    }

    /** Image pixels -> target-plane millimetres, via the inverse transform. */
    fun pxToMm(x: Double, y: Double): Pair<Double, Double> {
        val inv = inverse ?: return Double.NaN to Double.NaN
        val w = inv[6] * x + inv[7] * y + inv[8]
        if (kotlin.math.abs(w) < 1e-12) return Double.NaN to Double.NaN
        return (inv[0] * x + inv[1] * y + inv[2]) / w to (inv[3] * x + inv[4] * y + inv[5]) / w
    }

    /** Full 3x3 inverse, row-major, or null if the forward map is singular. */
    private val inverse: DoubleArray? by lazy {
        val m = doubleArrayOf(h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7], 1.0)
        val det = m[0] * (m[4] * m[8] - m[5] * m[7]) -
            m[1] * (m[3] * m[8] - m[5] * m[6]) +
            m[2] * (m[3] * m[7] - m[4] * m[6])
        if (kotlin.math.abs(det) < 1e-12) return@lazy null
        val d = 1.0 / det
        doubleArrayOf(
            (m[4] * m[8] - m[5] * m[7]) * d, (m[2] * m[7] - m[1] * m[8]) * d, (m[1] * m[5] - m[2] * m[4]) * d,
            (m[5] * m[6] - m[3] * m[8]) * d, (m[0] * m[8] - m[2] * m[6]) * d, (m[2] * m[3] - m[0] * m[5]) * d,
            (m[3] * m[7] - m[4] * m[6]) * d, (m[1] * m[6] - m[0] * m[7]) * d, (m[0] * m[4] - m[1] * m[3]) * d
        )
    }

    /**
     * Local scale in pixels per millimetre at a target-plane point, from the
     * magnitude of the transform's Jacobian.
     *
     * This is not a nicety. Under perspective the scale varies across the
     * face — on a target photographed from 30 degrees off-axis the far edge
     * can be 30% smaller than the near one — so a hole detector using a
     * single global "mm per pixel" looks for the wrong size of blob over most
     * of the target. Evaluated numerically with a 1 mm step, which is both
     * simpler and less error-prone than differentiating the quotient by hand.
     */
    fun pxPerMmAt(u: Double, v: Double): Double {
        val (x0, y0) = mmToPx(u, v)
        val (xu, yu) = mmToPx(u + 1.0, v)
        val (xv, yv) = mmToPx(u, v + 1.0)
        if (x0.isNaN() || xu.isNaN() || xv.isNaN()) return 0.0
        val su = kotlin.math.hypot(xu - x0, yu - y0)
        val sv = kotlin.math.hypot(xv - x0, yv - y0)
        // Geometric mean: the two axes can differ under strong keystoning,
        // and a blob detector wants the equivalent isotropic scale.
        return kotlin.math.sqrt(su * sv)
    }

    companion object {

        /**
         * Fits the transform taking [targetMm] to [imagePx]. Both lists must
         * hold exactly four points in the SAME order — the caller's job is to
         * present them consistently, which for the registration UI means
         * going round the face in one direction from a marked corner.
         *
         * Returns null when the system is singular, which in practice means
         * the four taps were collinear or coincident.
         */
        fun fromCorrespondences(
            targetMm: List<Pair<Double, Double>>,
            imagePx: List<Pair<Double, Double>>
        ): Homography? {
            if (targetMm.size != 4 || imagePx.size != 4) return null
            val a = Array(8) { DoubleArray(8) }
            val b = DoubleArray(8)
            for (i in 0 until 4) {
                val (u, v) = targetMm[i]
                val (x, y) = imagePx[i]
                val r0 = i * 2
                a[r0][0] = u; a[r0][1] = v; a[r0][2] = 1.0
                a[r0][6] = -x * u; a[r0][7] = -x * v
                b[r0] = x
                val r1 = r0 + 1
                a[r1][3] = u; a[r1][4] = v; a[r1][5] = 1.0
                a[r1][6] = -y * u; a[r1][7] = -y * v
                b[r1] = y
            }
            val h = solve(a, b) ?: return null
            val candidate = Homography(h)

            // A unique solution to the 8x8 system does NOT guarantee an
            // invertible 3x3. Taps that collapse the quadrilateral — four
            // points on a line, or a "bowtie" ordering — admit a rank-2
            // solution that maps the whole plane onto a line. It passes every
            // check above, maps millimetres to entirely plausible pixels, and
            // then returns NaN from every inverse. So the fit is verified by
            // round-tripping the correspondences it was built from; anything
            // that cannot come back is rejected here rather than surfacing
            // later as unscoreable shots with no explanation.
            for (i in 0 until 4) {
                val (u, v) = targetMm[i]
                val (x, y) = candidate.mmToPx(u, v)
                if (x.isNaN() || y.isNaN()) return null
                val (bu, bv) = candidate.pxToMm(x, y)
                if (bu.isNaN() || bv.isNaN()) return null
                if (kotlin.math.abs(bu - u) > ROUND_TRIP_TOLERANCE_MM ||
                    kotlin.math.abs(bv - v) > ROUND_TRIP_TOLERANCE_MM
                ) return null
            }
            return candidate
        }

        /** Round-trip slack, millimetres. Far below any registration error a
         *  finger tap could avoid, far above double-precision noise. */
        private const val ROUND_TRIP_TOLERANCE_MM = 1e-3

        /** Gaussian elimination with partial pivoting. Null if singular. */
        private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
            val n = b.size
            for (col in 0 until n) {
                var pivot = col
                for (r in col + 1 until n) {
                    if (kotlin.math.abs(a[r][col]) > kotlin.math.abs(a[pivot][col])) pivot = r
                }
                if (kotlin.math.abs(a[pivot][col]) < 1e-12) return null
                if (pivot != col) {
                    val t = a[pivot]; a[pivot] = a[col]; a[col] = t
                    val tb = b[pivot]; b[pivot] = b[col]; b[col] = tb
                }
                val d = a[col][col]
                for (r in col + 1 until n) {
                    val f = a[r][col] / d
                    if (f == 0.0) continue
                    for (c in col until n) a[r][c] -= f * a[col][c]
                    b[r] -= f * b[col]
                }
            }
            val x = DoubleArray(n)
            for (r in n - 1 downTo 0) {
                var s = b[r]
                for (c in r + 1 until n) s -= a[r][c] * x[c]
                x[r] = s / a[r][r]
            }
            return if (x.any { it.isNaN() || it.isInfinite() }) null else x
        }
    }
}
