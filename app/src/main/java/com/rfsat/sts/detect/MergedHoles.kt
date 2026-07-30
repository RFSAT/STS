package com.rfsat.sts.detect

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * ============================================================================
 *  TWO SHOTS THROUGH ONE PLACE
 * ============================================================================
 *
 * A connected-component detector sees two touching holes as one region. Left
 * alone that costs a whole shot — and it is not a rare case: at 10 m a good
 * shooter puts most of a card through the same few millimetres, which is
 * precisely the situation the app is for.
 *
 * The region is not ambiguous, though. A single hole is round and about one
 * gauge across; two merged holes have roughly twice the area and are clearly
 * longer in one direction than the other. Both facts are already measured —
 * the size and elongation gates in [HoleDetector] use them to REJECT such a
 * region, which is the worst of the three possible answers: not one shot, not
 * two, but none.
 *
 * So: when a region looks like k holes rather than one, split it into k along
 * its own long axis, at the k best-separated peaks of the detector response
 * inside it. If the peaks do not separate cleanly the split is abandoned and
 * the region is left whole, because guessing at a second shot is worse than
 * missing it — a shot invented on paper is a score the shooter did not fire.
 */
object MergedHoles {

    /** Area, in gauges, below which a region is one hole and nothing else. */
    private const val SPLIT_MIN_AREA_RATIO = 1.55

    /** Most holes one region will be split into. Beyond three the geometry
     *  stops being a line and the guess stops being defensible. */
    private const val MAX_PARTS = 3

    /** How far apart two peaks must be, in gauges, to be separate shots.
     *  Below this the pellets overlap so completely that one hole is the
     *  honest answer. */
    private const val MIN_SEPARATION_GAUGES = 0.55

    /** A region longer than this in its own frame is a candidate to split. */
    private const val MIN_ELONGATION = 1.30

    class Part(val x: Double, val y: Double, val pixels: Int)

    /**
     * Splits a region into parts, or returns a single part when it is one
     * hole. Coordinates are rectified pixels.
     */
    fun split(
        pixels: IntArray,
        count: Int,
        response: IntArray,
        width: Int,
        gaugePx: Double,
        expectedArea: Double
    ): List<Part> {
        val whole = listOf(centroid(pixels, count, response, width))
        if (count < expectedArea * SPLIT_MIN_AREA_RATIO) return whole

        // How many holes the area suggests, capped.
        val k = min(MAX_PARTS, max(2, Math.round(count / expectedArea).toInt()))

        // The region's own long axis, from the second moments. Splitting along
        // the bounding box instead would be wrong for a pair lying diagonally.
        val (cx, cy) = whole[0].let { it.x to it.y }
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        for (i in 0 until count) {
            val dx = (pixels[i] % width) - cx
            val dy = (pixels[i] / width) - cy
            sxx += dx * dx; syy += dy * dy; sxy += dx * dy
        }
        val theta = 0.5 * Math.atan2(2 * sxy, sxx - syy)
        val ct = cos(theta); val st = sin(theta)

        // Project every pixel onto that axis and check the region really is
        // elongated before believing there is more than one hole in it.
        var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
        var across = 0.0
        for (i in 0 until count) {
            val dx = (pixels[i] % width) - cx
            val dy = (pixels[i] / width) - cy
            val t = dx * ct + dy * st
            val n = -dx * st + dy * ct
            lo = min(lo, t); hi = max(hi, t)
            across = max(across, abs(n))
        }
        val along = hi - lo
        if (across <= 0.0 || along / (2 * across) < MIN_ELONGATION) return whole

        // Response profile along the axis: two holes make two humps.
        val bins = max(8, Math.ceil(along).toInt())
        val prof = DoubleArray(bins)
        val cnt = IntArray(bins)
        for (i in 0 until count) {
            val dx = (pixels[i] % width) - cx
            val dy = (pixels[i] / width) - cy
            val t = dx * ct + dy * st
            val b = (((t - lo) / along) * (bins - 1)).toInt().coerceIn(0, bins - 1)
            prof[b] += abs(response[pixels[i]]).toDouble()
            cnt[b]++
        }
        for (b in 0 until bins) if (cnt[b] > 0) prof[b] /= cnt[b]

        val peaks = peaksOf(prof, k)
        if (peaks.size < 2) return whole

        // Peaks must be far enough apart to be separate pellets.
        val positions = peaks.map { lo + it.toDouble() / (bins - 1) * along }.sorted()
        for (i in 1 until positions.size) {
            if (positions[i] - positions[i - 1] < gaugePx * MIN_SEPARATION_GAUGES) return whole
        }

        // Assign each pixel to its nearest peak and take a weighted centroid
        // of each group, so the parts sit where the response actually is
        // rather than at evenly spaced guesses.
        val sums = DoubleArray(positions.size)
        val xs = DoubleArray(positions.size)
        val ys = DoubleArray(positions.size)
        val counts = IntArray(positions.size)
        for (i in 0 until count) {
            val px = (pixels[i] % width).toDouble()
            val py = (pixels[i] / width).toDouble()
            val t = (px - cx) * ct + (py - cy) * st
            var best = 0
            for (j in positions.indices) {
                if (abs(t - positions[j]) < abs(t - positions[best])) best = j
            }
            val v = abs(response[pixels[i]]).toDouble()
            sums[best] += v; xs[best] += px * v; ys[best] += py * v; counts[best]++
        }
        val parts = ArrayList<Part>(positions.size)
        for (j in positions.indices) {
            if (sums[j] <= 0.0 || counts[j] < 3) return whole
            parts += Part(xs[j] / sums[j], ys[j] / sums[j], counts[j])
        }
        return parts
    }

    private fun centroid(pixels: IntArray, count: Int, response: IntArray, width: Int): Part {
        var wsum = 0.0; var xs = 0.0; var ys = 0.0
        for (i in 0 until count) {
            val v = abs(response[pixels[i]]).toDouble()
            wsum += v
            xs += (pixels[i] % width) * v
            ys += (pixels[i] / width) * v
        }
        if (wsum <= 0.0) return Part((pixels[0] % width).toDouble(), (pixels[0] / width).toDouble(), count)
        return Part(xs / wsum, ys / wsum, count)
    }

    /** The [k] strongest local maxima, separated by at least a fifth of the
     *  profile so two samples of one hump are not read as two holes. */
    private fun peaksOf(prof: DoubleArray, k: Int): List<Int> {
        val minGap = max(2, prof.size / 5)
        val found = ArrayList<Int>()
        val used = BooleanArray(prof.size)
        repeat(k) {
            var bi = -1
            for (i in prof.indices) {
                if (used[i]) continue
                if (bi < 0 || prof[i] > prof[bi]) bi = i
            }
            if (bi < 0 || prof[bi] <= 0.0) return@repeat
            found += bi
            for (i in max(0, bi - minGap)..min(prof.size - 1, bi + minGap)) used[i] = true
        }
        return found.sorted()
    }
}
