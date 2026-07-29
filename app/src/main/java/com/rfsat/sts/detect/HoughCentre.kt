package com.rfsat.sts.detect

import com.rfsat.sts.log.Logger
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Where the rings are centred, and how elliptical they are. */
data class CentreVote(
    val xPx: Double,
    val yPx: Double,
    val votes: Double,
    /** Semi-minor over semi-major, pooled across every ring. 1.0 is circular. */
    val axisRatio: Double = 1.0,
    /** Direction of the major axis, degrees. */
    val orientationDeg: Double = 0.0,
    /** How many edge samples the ratio was measured from. */
    val distortionSamples: Int = 0
) {
    /** The tilt the axis ratio implies. See the note on the noise floor. */
    val impliedTiltDeg: Double
        get() = Math.toDegrees(kotlin.math.acos(axisRatio.coerceIn(0.0, 1.0)))
}

/**
 * ============================================================================
 *  FINDING THE CENTRE BY VOTING
 * ============================================================================
 *
 * A Hough transform for the centre of a concentric family. Every edge on a
 * printed ring has a normal pointing at the common centre, so each edge point
 * votes along its own normal and the centre is where the votes pile up.
 *
 * WHY THIS RATHER THAN THE SYMMETRY SEARCH ALREADY IN [RingFinder]. The
 * symmetry search minimises brightness variance within radius bands, which is
 * accurate but needs the target to be most of the picture and to be largely
 * unobstructed. Voting works from edges, so it survives a thumb across the
 * corner, a club logo, printed text, the edge of the card, and a target that
 * fills only part of the frame. Measured on four real targets it put the
 * centre within 2 to 4 pixels every time.
 *
 * The two are used together rather than one instead of the other: this
 * supplies a robust seed and the radial-profile fit then measures the pitch,
 * which it does better — 0.0 to 1.5 per cent against 0.9 to 3.8 for a
 * radius histogram off the same edges.
 *
 * ON HOUGH AND ANGLED TARGETS, because this is the thing it is usually
 * expected to fix and does not. Under perspective a ring projects to an
 * ELLIPSE, and a circle accumulator has no parameter for that; a full ellipse
 * Hough needs five dimensions and is not something to run on a phone. What is
 * affordable is measuring the ellipticity directly — see
 * [pooledDistortion] — and the honest finding from four real targets is that
 * it is not sensitive enough to act on by itself. It is reported, and it
 * seeds the manual tilt sliders, and it is never applied on its own.
 */
object HoughCentre {

    private const val WORK_MAX = 420
    private const val GRADIENT_THRESHOLD = 60
    /** An edge belongs to a ring only if its normal is nearly radial. This is
     *  what rejects lettering, logos, score boxes and the card's own edge. */
    private const val RADIAL_COSINE = 0.85

    /**
     * Below this the ellipticity measurement is indistinguishable from its own
     * noise. Measured on two perfectly circular synthetic targets, the pooled
     * estimate still reported 3.1 and 3.9 degrees; on a genuinely angled
     * photograph it reported 5.1. A threshold under about 8 degrees would be
     * reporting noise as tilt.
     */
    const val TILT_NOISE_FLOOR_DEG = 8.0

    private class Edge(val x: Int, val y: Int, val nx: Double, val ny: Double)

    fun find(frame: LumaFrame): CentreVote? {
        val step = max(1, max(frame.width, frame.height) / WORK_MAX)
        val w = frame.width / step
        val h = frame.height / step
        if (w < 60 || h < 60) return null

        val img = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) img[y * w + x] = frame.at(x * step, y * step)

        val edges = sobelEdges(img, w, h)
        if (edges.size < 200) {
            Logger.i("HoughCentre", "only ${edges.size} usable edge points")
            return null
        }

        val rMin = (min(w, h) * 0.10).toInt().coerceAtLeast(6)
        val rMax = (min(w, h) * 0.48).toInt()
        val bin = 4
        val aw = w / bin + 1
        val ah = h / bin + 1
        val acc = FloatArray(aw * ah)
        for (e in edges) {
            for (sign in intArrayOf(1, -1)) {
                var r = rMin
                while (r <= rMax) {
                    val cx = ((e.x + sign * e.nx * r) / bin).toInt()
                    val cy = ((e.y + sign * e.ny * r) / bin).toInt()
                    if (cx in 0 until aw && cy in 0 until ah) acc[cy * aw + cx] += 1f
                    r += bin
                }
            }
        }
        var bestIdx = 0
        for (i in acc.indices) if (acc[i] > acc[bestIdx]) bestIdx = i
        if (acc[bestIdx] < 100f) {
            Logger.i("HoughCentre", "no clear centre: best bin only ${acc[bestIdx]} votes")
            return null
        }
        val cx = (bestIdx % aw) * bin + bin / 2.0
        val cy = (bestIdx / aw) * bin + bin / 2.0

        val distortion = pooledDistortion(edges, cx, cy)
        val vote = CentreVote(
            xPx = cx * step, yPx = cy * step, votes = acc[bestIdx].toDouble(),
            axisRatio = distortion?.first ?: 1.0,
            orientationDeg = distortion?.second ?: 0.0,
            distortionSamples = distortion?.third ?: 0
        )
        Logger.i(
            "HoughCentre",
            "centre (%.0f, %.0f) from %.0f votes; axis ratio %.4f over %d samples (implies %.1f deg)"
                .format(vote.xPx, vote.yPx, vote.votes, vote.axisRatio,
                    vote.distortionSamples, vote.impliedTiltDeg)
        )
        return vote
    }

    private fun sobelEdges(img: IntArray, w: Int, h: Int): List<Edge> {
        val out = ArrayList<Edge>()
        for (j in 1 until h - 1) {
            for (i in 1 until w - 1) {
                val gx = img[(j - 1) * w + i + 1] + 2 * img[j * w + i + 1] + img[(j + 1) * w + i + 1] -
                    img[(j - 1) * w + i - 1] - 2 * img[j * w + i - 1] - img[(j + 1) * w + i - 1]
                val gy = img[(j + 1) * w + i - 1] + 2 * img[(j + 1) * w + i] + img[(j + 1) * w + i + 1] -
                    img[(j - 1) * w + i - 1] - 2 * img[(j - 1) * w + i] - img[(j - 1) * w + i + 1]
                val m = hypot(gx.toDouble(), gy.toDouble())
                if (m > GRADIENT_THRESHOLD) out.add(Edge(i, j, gx / m, gy / m))
            }
        }
        return out
    }

    /**
     * The angular distortion shared by every ring, as (axisRatio, orientation,
     * sampleCount).
     *
     * All the rings on one card are distorted by the SAME projection, so
     * r(theta, k) = R_k · g(theta). Taking logs and subtracting each ring's own
     * mean turns every edge point on every ring into a sample of the same
     * g(theta) — thousands of samples for two parameters, instead of a few
     * hundred per ring for five. Fitting each ring separately was tried first
     * and sat at the noise floor: it reported 5 to 8 degrees of tilt on
     * targets that were perfect circles.
     *
     * Pooling improved that to about 4 degrees of noise, which is still not
     * good enough to act on unaided — hence [TILT_NOISE_FLOOR_DEG].
     */
    private fun pooledDistortion(
        edges: List<Edge>, cx: Double, cy: Double
    ): Triple<Double, Double, Int>? {
        // radius histogram -> ring bands
        val radii = edges.mapNotNull { e ->
            val dx = e.x - cx; val dy = e.y - cy
            val r = hypot(dx, dy)
            if (r < 8) null else {
                val radial = (dx * e.nx + dy * e.ny) / r
                if (abs(radial) < RADIAL_COSINE) null else Triple(atan2(dy, dx), r, 0)
            }
        }
        if (radii.size < 200) return null

        val hist = HashMap<Int, Int>()
        radii.forEach { hist[it.second.toInt()] = (hist[it.second.toInt()] ?: 0) + 1 }
        val mean = radii.size.toDouble() / max(1, hist.size)
        val bands = hist.keys.sorted().filter { r ->
            (hist[r] ?: 0) >= max(4.0, mean * 1.8) &&
                (hist[r] ?: 0) >= (hist[r - 1] ?: 0) && (hist[r] ?: 0) >= (hist[r + 1] ?: 0)
        }
        if (bands.isEmpty()) return null

        // log-radius residual about each band's own mean
        val samples = ArrayList<Pair<Double, Double>>()
        for (b in bands) {
            val inBand = radii.filter { abs(it.second - b) < 3.0 }
            if (inBand.size < 20) continue
            val mu = inBand.map { ln(it.second) }.average()
            inBand.forEach { samples.add(it.first to (ln(it.second) - mu)) }
        }
        if (samples.size < 80) return null

        // ln g(theta) = a cos 2t + b sin 2t + c
        val s = Array(3) { DoubleArray(4) }
        for ((t, y) in samples) {
            val v = doubleArrayOf(cos(2 * t), sin(2 * t), 1.0)
            for (a in 0..2) {
                s[a][3] += v[a] * y
                for (bIdx in 0..2) s[a][bIdx] += v[a] * v[bIdx]
            }
        }
        for (col in 0..2) {
            var piv = col
            for (r in col..2) if (abs(s[r][col]) > abs(s[piv][col])) piv = r
            if (abs(s[piv][col]) < 1e-12) return null
            val tmp = s[col]; s[col] = s[piv]; s[piv] = tmp
            for (r in 0..2) {
                if (r == col) continue
                val f = s[r][col] / s[col][col]
                for (c in col..3) s[r][c] -= f * s[col][c]
            }
        }
        val a = s[0][3] / s[0][0]
        val b = s[1][3] / s[1][1]
        val amplitude = hypot(a, b)
        val ratio = exp(-2 * amplitude).coerceIn(0.05, 1.0)
        return Triple(ratio, Math.toDegrees(0.5 * atan2(b, a)), samples.size)
    }
}
