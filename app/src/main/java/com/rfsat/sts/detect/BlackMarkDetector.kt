package com.rfsat.sts.detect

import com.rfsat.sts.log.Logger
import com.rfsat.sts.targets.TargetFace
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A dark disc found in the image: the target's aiming mark.
 *
 * [ellipticity] is the longer bounding extent over the shorter. A circle
 * photographed square-on gives 1.0, and anything much above it means the
 * camera was off axis and the mark is projecting to an ellipse. After the
 * position, that is the most useful number this detector produces, because
 * it is what tells the app when a square bounding box — which can express
 * only scale and translation — is the wrong registration model.
 */
data class DetectedDisc(
    val centreXPx: Double,
    val centreYPx: Double,
    val radiusPx: Double,
    val ellipticity: Double,
    /** Fraction of its bounding box the component fills. A disc fills
     *  pi/4 = 0.785; much less and it is not a disc. */
    val fillRatio: Double,
    val confidence: Double
)

/**
 * ============================================================================
 *  FINDING THE AIMING MARK
 * ============================================================================
 *
 * Registration used to begin with four accurate taps. It does not have to.
 * On almost every competition face there is a large, very dark, very round
 * thing in the middle, and it is the easiest feature in the whole image to
 * find. Finding it automatically turns registration from "tap four corners
 * carefully" into "check the box the app drew", which is a different task.
 *
 * THRESHOLD BY OTSU. An aiming mark against paper is the textbook bimodal
 * histogram: a large dark population and a large light one. Otsu's method
 * picks the split minimising the variance within each, and needs no magic
 * constant — which matters, because the alternative is a fixed grey level
 * that works indoors under fluorescent tubes and fails outdoors in sun.
 *
 * WHY THE RADIUS COMES FROM THE BOUNDING BOX AND NOT THE AREA. A shot-up
 * target has holes in its aiming mark, and on a good string it has a lot of
 * them. Area shrinks with every hole, so sqrt(area/pi) under-reads the radius
 * by MORE the better the shooter is — a bias that would tighten registration
 * and inflate every score, worst for exactly the people most likely to
 * notice. Half the mean bounding extent does not care about holes at all.
 *
 * WHAT IT REFUSES, and why refusing matters. A component touching the image
 * border (the mark is cut off, so its true extent is unknown), one filling
 * too little of its bounding box (not a disc), one too small or too large to
 * be an aiming mark. A wrong box that looks plausible is worse than no box,
 * because the user will accept it.
 */
object BlackMarkDetector {

    /** Work at roughly this size. Otsu and connected components over a full
     *  3000 px frame is wasted effort: the aiming mark is hundreds of pixels
     *  across even after this reduction. */
    private const val WORK_MAX = 640

    /** A disc fills pi/4 of its bounding box. The allowance below that covers
     *  shot holes and a slightly ragged threshold. */
    private const val MIN_FILL = 0.60

    /** Below this fraction of the frame's shorter side it is not the aiming
     *  mark — it is a shadow, a staple, or printed text. */
    private const val MIN_RADIUS_FRACTION = 0.03

    /** Above this the "mark" is most of the picture, which means the
     *  threshold split the scene rather than finding anything on it. */
    private const val MAX_RADIUS_FRACTION = 0.48

    /** Past this the target is angled enough that a square box will mis-score
     *  it and corner registration is wanted instead. */
    const val OBLIQUE_ELLIPTICITY = 1.15

    /**
     * Finds the aiming mark, or returns null when nothing convincing is
     * there. Coordinates come back in [frame]'s own full-resolution pixels.
     */
    fun detect(frame: LumaFrame): DetectedDisc? {
        val step = max(1, max(frame.width, frame.height) / WORK_MAX)
        val w = frame.width / step
        val h = frame.height / step
        if (w < 16 || h < 16) return null

        val small = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) small[y * w + x] = frame.at(x * step, y * step)

        val threshold = otsu(small)
        val dark = BooleanArray(w * h) { small[it] <= threshold }

        val best = largestDisc(dark, w, h) ?: run {
            Logger.i("BlackMarkDetector", "No disc-like dark region found")
            return null
        }

        val shortSide = min(w, h).toDouble()
        val rSmall = (best.width + best.height) / 4.0
        if (rSmall < shortSide * MIN_RADIUS_FRACTION || rSmall > shortSide * MAX_RADIUS_FRACTION) {
            Logger.i(
                "BlackMarkDetector",
                "Rejected a dark region of radius %.1f px (%.0f%% of the frame) as not an aiming mark"
                    .format(rSmall, 100 * rSmall / shortSide)
            )
            return null
        }

        val ellipticity = max(best.width, best.height).toDouble() /
            max(1.0, min(best.width, best.height).toDouble())
        val fill = best.count.toDouble() / max(1, best.width * best.height)

        // Three independent agreements, multiplied rather than averaged, so
        // failing any one pulls the result down instead of being masked by
        // the other two.
        val roundness = (1.0 - (ellipticity - 1.0) / 0.5).coerceIn(0.0, 1.0)
        val fillScore = ((fill - MIN_FILL) / (0.785 - MIN_FILL)).coerceIn(0.0, 1.0)
        val sizeScore = (rSmall / (shortSide * 0.25)).coerceIn(0.0, 1.0)
        val confidence = roundness * (0.5 + 0.5 * fillScore) * (0.5 + 0.5 * sizeScore)

        val disc = DetectedDisc(
            centreXPx = best.centreX * step,
            centreYPx = best.centreY * step,
            radiusPx = rSmall * step,
            ellipticity = ellipticity,
            fillRatio = fill,
            confidence = confidence
        )
        Logger.i(
            "BlackMarkDetector",
            "Aiming mark at (%.0f, %.0f) r=%.0f px, ellipticity %.2f, fill %.2f, confidence %.2f"
                .format(disc.centreXPx, disc.centreYPx, disc.radiusPx, ellipticity, fill, confidence)
        )
        return disc
    }

    /**
     * The square box that should be registered, given a detected mark and the
     * face being shot: [left, top, right, bottom] in full-resolution pixels,
     * with what the box means.
     *
     * PREFER THE OUTER SCORING RING, AND EXPAND TO IT GEOMETRICALLY. Deriving
     * the scale of a whole face from a small central feature multiplies any
     * error in that radius by the ratio between them — 1.5x on the ISSF air
     * rifle face, 1.37x at 50 m. So the mark is used to LOCATE the target and
     * the face's own published ratio expands the box out to the outer ring,
     * which is a far longer baseline and therefore a far better scale
     * reference.
     *
     * The expansion is arithmetic, not detection, on purpose: the outermost
     * ring is a thin line on white paper and finding it directly is much less
     * reliable than knowing what multiple of the black it is. Falls back to
     * the mark itself when the expanded box would not fit in the picture.
     */
    fun boxFor(
        disc: DetectedDisc,
        face: TargetFace,
        frameWidth: Int,
        frameHeight: Int
    ): Pair<FloatArray, TargetRegistration.BoxMeaning> {
        val blackDia = face.blackDiameterMm
        val outerDia = face.outerRadiusMm * 2.0

        if (blackDia > 0.0 && outerDia > blackDia) {
            val rOuter = disc.radiusPx * (outerDia / blackDia)
            val box = square(disc.centreXPx, disc.centreYPx, rOuter)
            if (fitsIn(box, frameWidth, frameHeight)) {
                return box to TargetRegistration.BoxMeaning.OUTER_SCORING_RING
            }
            Logger.i(
                "BlackMarkDetector",
                "The whole scoring area runs outside the picture; registering on the aiming mark instead"
            )
        }
        return square(disc.centreXPx, disc.centreYPx, disc.radiusPx) to
            TargetRegistration.BoxMeaning.BLACK_AIMING_MARK
    }

    private fun square(cx: Double, cy: Double, r: Double) = floatArrayOf(
        (cx - r).toFloat(), (cy - r).toFloat(), (cx + r).toFloat(), (cy + r).toFloat()
    )

    private fun fitsIn(box: FloatArray, w: Int, h: Int) =
        box[0] >= 0f && box[1] >= 0f && box[2] <= w.toFloat() && box[3] <= h.toFloat()

    /** True when the mark is elliptical enough that a square box will not do. */
    fun looksOblique(disc: DetectedDisc): Boolean = disc.ellipticity > OBLIQUE_ELLIPTICITY

    // ------------------------------------------------------------------

    private class Blob {
        var count = 0
        var minX = Int.MAX_VALUE; var maxX = Int.MIN_VALUE
        var minY = Int.MAX_VALUE; var maxY = Int.MIN_VALUE
        var sumX = 0L; var sumY = 0L
        var touchesBorder = false
        val width get() = maxX - minX + 1
        val height get() = maxY - minY + 1
        val centreX get() = sumX.toDouble() / count
        val centreY get() = sumY.toDouble() / count
    }

    /** Largest four-connected dark component that could be a disc. */
    private fun largestDisc(dark: BooleanArray, w: Int, h: Int): Blob? {
        val seen = BooleanArray(dark.size)
        val stack = ArrayDeque<Int>()
        var best: Blob? = null
        for (start in dark.indices) {
            if (!dark[start] || seen[start]) continue
            val b = Blob()
            stack.addLast(start); seen[start] = true
            while (stack.isNotEmpty()) {
                val p = stack.removeLast()
                val x = p % w; val y = p / w
                b.count++; b.sumX += x; b.sumY += y
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1) b.touchesBorder = true
                if (x < b.minX) b.minX = x
                if (x > b.maxX) b.maxX = x
                if (y < b.minY) b.minY = y
                if (y > b.maxY) b.maxY = y
                if (x > 0 && !seen[p - 1] && dark[p - 1]) { seen[p - 1] = true; stack.addLast(p - 1) }
                if (x < w - 1 && !seen[p + 1] && dark[p + 1]) { seen[p + 1] = true; stack.addLast(p + 1) }
                if (y > 0 && !seen[p - w] && dark[p - w]) { seen[p - w] = true; stack.addLast(p - w) }
                if (y < h - 1 && !seen[p + w] && dark[p + w]) { seen[p + w] = true; stack.addLast(p + w) }
            }
            // A mark running off the edge of the picture has an unknown
            // extent, and guessing it would put the scale silently wrong.
            if (b.touchesBorder) continue
            if (b.count < 32) continue
            if (b.count.toDouble() / max(1, b.width * b.height) < MIN_FILL) continue
            val current = best
            if (current == null || b.count > current.count) best = b
        }
        return best
    }

    /**
     * Otsu's threshold: the grey level minimising the variance within the two
     * populations it splits the histogram into. Computed in the usual single
     * pass by maximising between-class variance, which is equivalent and
     * avoids recomputing the within-class term at every level.
     */
    fun otsu(values: IntArray): Int {
        val hist = IntArray(256)
        for (v in values) hist[v.coerceIn(0, 255)]++
        val total = values.size
        if (total == 0) return 127
        var sum = 0.0
        for (i in 0 until 256) sum += i.toDouble() * hist[i]
        var sumB = 0.0
        var wB = 0
        var best = -1.0
        var threshold = 127
        for (t in 0 until 256) {
            wB += hist[t]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += t.toDouble() * hist[t]
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > best) { best = between; threshold = t }
        }
        return threshold
    }

    /** Equivalent radius from an area, for callers that want it. */
    fun radiusFromArea(area: Int): Double = sqrt(area / Math.PI)
}
