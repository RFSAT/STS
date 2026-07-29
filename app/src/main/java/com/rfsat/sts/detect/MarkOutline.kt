package com.rfsat.sts.detect

import com.rfsat.sts.log.Logger
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * ============================================================================
 *  EXTRACTING A CLOSED OUTLINE TO FIT
 * ============================================================================
 *
 * [EllipseFitter] needs points that lie on one curve. Choosing which curve is
 * most of the problem, and two obvious choices were tried and measured before
 * this one:
 *
 *   1. RAY CASTING to the first threshold crossing along each bearing. Fails
 *      because the crossing radius drifts with illumination: on a real
 *      photograph this invented an axis ratio of 1.027 on a target whose
 *      rings, measured properly, were circular to 0.3 per cent. It produced a
 *      confident 72 per cent cross-validated gain for a distortion that was
 *      not there.
 *
 *   2. RAY CASTING to the darkest point within a band around each fitted ring
 *      radius. Fails differently: where the band contains no printed ring it
 *      locks onto whatever shading gradient is present, and because the same
 *      gradient runs across all the bands, several rings then AGREE with each
 *      other on a false ellipse. Mutual agreement between rings looked like
 *      strong evidence and was an artefact.
 *
 * What is used instead is the aiming mark as a CONNECTED COMPONENT containing
 * the centre, and then its outline. A connected region cannot wander onto a
 * different feature the way a ray can: it is the mark, or it is nothing.
 *
 * It has one failure of its own, and it is checked for rather than hoped
 * away. A bullet hole breaking the edge of the mark bridges it to a printed
 * ring line, and the fill then escapes along that line and swallows the ring.
 * The result is a component with a plausible size and an entirely wrong
 * shape. [FILL_RATIO_MIN] catches it: a disc fills its own circumscribed
 * circle, and a leaked component does not — on the target where this happened
 * the fill ratio was 0.46 against 1.0 for a clean mark.
 */
object MarkOutline {

    /** Area divided by the area of the circle that just contains it. A solid
     *  disc scores 1.0; the leaked component measured on a real target scored
     *  0.46. */
    private const val FILL_RATIO_MIN = 0.55

    private const val MIN_AREA_PX = 300
    private const val MAX_AREA_FRACTION = 0.60
    private const val MIN_OUTLINE_POINTS = 60

    /** Morphological closing radius, px. Large enough to bridge a printed
     *  ring line inside the mark, far too small to reach the rings outside. */
    private const val CLOSE_RADIUS = 3

    /** Thresholds tried in order, as a fraction of the way from the darkest
     *  part of the mark to the paper. */
    private val THRESHOLD_FRACTIONS = doubleArrayOf(0.45, 0.50, 0.55, 0.40, 0.60)

    /**
     * Outline of the aiming mark around ([seedX], [seedY]), source pixels,
     * or null if no compact mark is there.
     */
    fun extract(frame: LumaFrame, seedX: Double, seedY: Double): List<EdgePoint>? {
        val w = frame.width
        val h = frame.height
        val sx = seedX.toInt()
        val sy = seedY.toInt()
        if (sx !in 0 until w || sy !in 0 until h) return null

        val dark = percentile(frame, 0.03)
        val light = percentile(frame, 0.80)
        if (light - dark < 12) {
            Logger.i("MarkOutline", "contrast between mark and paper is only ${light - dark}; no outline")
            return null
        }

        // Try EVERY threshold and keep the largest compact region, rather
        // than the first one that passes.
        //
        // First-match was not deterministic in the way that matters: which
        // threshold happens to yield a compact blob depends on the exposure
        // and the shading of the particular photograph, so the same target
        // photographed at two angles returned two different features. Measured
        // on one real card, the mark radius came out as 39.6 px at one tilt
        // and 114.0 px at another — and since that radius is now used to
        // cross-check the ring pitch, an unstable mark poisons the ladder as
        // well. The aiming mark is the LARGEST compact dark region containing
        // the centre; leaked regions are excluded by the fill-ratio test
        // rather than by hoping a threshold avoids them.
        var bestOutline: List<EdgePoint>? = null
        var bestArea = 0
        var bestCut = -1
        var bestFill = 0.0
        var bestRadius = 0.0
        for (f in THRESHOLD_FRACTIONS) {
            val cut = dark + ((light - dark) * f).toInt()
            val mask = closedDarkMask(frame, w, h, cut)
            val blob = floodFillMask(mask, w, h, sx, sy) ?: continue
            val area0 = blob.count { it }
            if (area0 < MIN_AREA_PX || area0 > MAX_AREA_FRACTION * w * h) continue

            fillInteriorHoles(blob, w, h)

            var cx = 0.0
            var cy = 0.0
            var n = 0
            for (y in 0 until h) for (x in 0 until w) if (blob[y * w + x]) { cx += x; cy += y; n++ }
            if (n == 0) continue
            cx /= n; cy /= n

            var rMax = 0.0
            val outline = ArrayList<EdgePoint>()
            for (y in 0 until h) {
                for (x in 0 until w) {
                    if (!blob[y * w + x]) continue
                    val r = hypot(x - cx, y - cy)
                    if (r > rMax) rMax = r
                    val edge = x == 0 || y == 0 || x == w - 1 || y == h - 1 ||
                        !blob[y * w + x - 1] || !blob[y * w + x + 1] ||
                        !blob[(y - 1) * w + x] || !blob[(y + 1) * w + x]
                    if (edge) outline += EdgePoint(x.toDouble(), y.toDouble())
                }
            }
            if (rMax <= 1.0) continue
            val fill = n / (Math.PI * rMax * rMax)
            if (fill < FILL_RATIO_MIN) {
                Logger.i(
                    "MarkOutline",
                    ("at threshold %d the region fills only %.2f of its circumscribed circle — " +
                        "it has leaked out of the aiming mark, probably along a ring line through " +
                        "a bullet hole").format(cut, fill)
                )
                continue
            }
            if (outline.size < MIN_OUTLINE_POINTS) continue
            if (n > bestArea) {
                bestArea = n; bestOutline = outline; bestCut = cut; bestFill = fill; bestRadius = rMax
            }
        }
        if (bestOutline != null) {
            Logger.i(
                "MarkOutline",
                "aiming mark at threshold %d: %d px, radius %.0f, fill %.2f, %d outline points"
                    .format(bestCut, bestArea, bestRadius, bestFill, bestOutline.size)
            )
            return bestOutline
        }
        Logger.i("MarkOutline", "no compact aiming mark found at any threshold")
        return null
    }

    /**
     * The dark mask, morphologically CLOSED.
     *
     * Rings 7 to 10 are printed INSIDE the black aiming mark, as light lines.
     * A flood fill of dark pixels starting at the centre is stopped dead by
     * the innermost of them, and returns the ten-ring disc instead of the
     * mark — on a synthetic face with crisp lines this reported a radius of
     * 22 px where the mark is 88, which then made the ring-pitch cross-check
     * reject the true ladder and accept one at a third of the pitch.
     *
     * It went unnoticed on real photographs only because thin anti-aliased
     * lines, softened further by JPEG, do not quite reach the threshold
     * everywhere, so the fill leaked past them. That is luck, not robustness,
     * and it would run out on a sharper photograph or a cleaner scan.
     *
     * Closing by [CLOSE_RADIUS] bridges lines a few pixels wide and leaves
     * the outer edge of the mark where it was. It cannot bridge the mark to
     * the rings outside it: those are a whole ring pitch away, which is an
     * order of magnitude more than this radius.
     */
    private fun closedDarkMask(frame: LumaFrame, w: Int, h: Int, cut: Int): BooleanArray {
        val m = BooleanArray(w * h)
        for (y in 0 until h) for (x in 0 until w) m[y * w + x] = frame.at(x, y) < cut
        val dil = dilate(m, w, h, CLOSE_RADIUS)
        return erode(dil, w, h, CLOSE_RADIUS)
    }

    /** Separable square dilation: horizontal pass then vertical. */
    private fun dilate(src: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        val tmp = BooleanArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var v = false
                var i = maxOf(0, x - r)
                val e = minOf(w - 1, x + r)
                while (i <= e) { if (src[row + i]) { v = true; break }; i++ }
                tmp[row + x] = v
            }
        }
        val out = BooleanArray(w * h)
        for (x in 0 until w) {
            for (y in 0 until h) {
                var v = false
                var j = maxOf(0, y - r)
                val e = minOf(h - 1, y + r)
                while (j <= e) { if (tmp[j * w + x]) { v = true; break }; j++ }
                out[y * w + x] = v
            }
        }
        return out
    }

    private fun erode(src: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        val inv = BooleanArray(w * h) { !src[it] }
        val d = dilate(inv, w, h, r)
        return BooleanArray(w * h) { !d[it] }
    }

    private fun floodFillMask(
        mask: BooleanArray, w: Int, h: Int, sx: Int, sy: Int
    ): BooleanArray? {
        if (!mask[sy * w + sx]) return null
        val seen = BooleanArray(w * h)
        val stack = IntArray(w * h)
        var top = 0
        val limit = (w * h * MAX_AREA_FRACTION).toInt()
        var count = 0
        seen[sy * w + sx] = true
        stack[top++] = sy * w + sx
        while (top > 0) {
            val p = stack[--top]
            val x = p % w
            val y = p / w
            count++
            if (count > limit) return null
            if (x > 0) { val i = p - 1; if (!seen[i] && mask[i]) { seen[i] = true; stack[top++] = i } }
            if (x < w - 1) { val i = p + 1; if (!seen[i] && mask[i]) { seen[i] = true; stack[top++] = i } }
            if (y > 0) { val i = p - w; if (!seen[i] && mask[i]) { seen[i] = true; stack[top++] = i } }
            if (y < h - 1) { val i = p + w; if (!seen[i] && mask[i]) { seen[i] = true; stack[top++] = i } }
        }
        return seen
    }

    /**
     * Fills holes so that the white aiming dot, the printed ring lines inside
     * the mark and the ring numerals do not each contribute an inner outline.
     * Done by flooding the OUTSIDE from the border and inverting: anything the
     * outside cannot reach is interior.
     */
    private fun fillInteriorHoles(blob: BooleanArray, w: Int, h: Int) {
        val outside = BooleanArray(w * h)
        val stack = IntArray(w * h)
        var top = 0
        fun seed(x: Int, y: Int) {
            val i = y * w + x
            if (!blob[i] && !outside[i]) { outside[i] = true; stack[top++] = i }
        }
        for (x in 0 until w) { seed(x, 0); seed(x, h - 1) }
        for (y in 0 until h) { seed(0, y); seed(w - 1, y) }
        while (top > 0) {
            val p = stack[--top]
            val x = p % w
            val y = p / w
            if (x > 0) seed(x - 1, y)
            if (x < w - 1) seed(x + 1, y)
            if (y > 0) seed(x, y - 1)
            if (y < h - 1) seed(x, y + 1)
        }
        for (i in blob.indices) if (!outside[i]) blob[i] = true
    }

    private fun percentile(frame: LumaFrame, q: Double): Int {
        val hist = IntArray(256)
        val step = max(1, max(frame.width, frame.height) / 500)
        var n = 0
        var y = 0
        while (y < frame.height) {
            var x = 0
            while (x < frame.width) {
                hist[frame.at(x, y).coerceIn(0, 255)]++
                n++
                x += step
            }
            y += step
        }
        val want = (n * q).toInt().coerceAtLeast(1)
        var acc = 0
        for (v in 0..255) {
            acc += hist[v]
            if (acc >= want) return v
        }
        return 255
    }
}
