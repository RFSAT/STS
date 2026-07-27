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
    val confidence: Double,
    /** Semi-minor over semi-major from the blob's second moments. 1.0 is a
     *  circle. Measured this way rather than from the axis-aligned bounding
     *  box because a bounding box cannot tell a tilted ellipse from a bigger
     *  circle once the ellipse is rotated — which is exactly the case the
     *  tilt controls exist for. */
    val axisRatio: Double = 1.0,
    /** Direction of the MAJOR axis, degrees from the image's +x axis. */
    val orientationDeg: Double = 0.0
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

    /** A first pass this confident is accepted without trying the crop. */
    private const val GOOD_ENOUGH = 0.55

    /** Past this the target is angled enough that a square box will mis-score
     *  it and corner registration is wanted instead. */
    const val OBLIQUE_ELLIPTICITY = 1.15

    /**
     * Below this ellipticity no tilt is suggested at all: the mark is treated
     * as round and the box is left square-on.
     *
     * 1.02 is a tilt of 11.4 degrees, and the threshold is deliberately not
     * lower. Two reasons, both about not chasing noise:
     *
     *   WHAT IT COSTS TO IGNORE. At 11 degrees a shot on the OUTERMOST ring
     *   of a 50 m rifle face is misplaced by 1.5 mm — under a fifth of a ring
     *   pitch — and the error falls off toward the centre, where the shots
     *   actually are. Below that it is not measurable on a scored card.
     *
     *   WHAT IT COSTS TO ACT. The SIGN of a suggested tilt is a guess (see
     *   [suggestedTransform]), so a marginal ellipticity that is really
     *   segmentation noise on a shot-up aiming mark buys a correction as
     *   likely to be applied backwards as forwards. Doing nothing is strictly
     *   better than a coin flip.
     */
    const val MIN_ELLIPTICITY_TO_SUGGEST = 1.02

    /**
     * Finds the aiming mark, or returns null when nothing convincing is
     * there. Coordinates come back in [frame]'s own full-resolution pixels.
     */
    fun detect(frame: LumaFrame): DetectedDisc? {
        // Try the whole picture first, then just the middle of it.
        //
        // WHY THE RETRY. Otsu splits the image into a dark population and a
        // light one, which is exactly right when the frame is filled by a
        // white card with a black mark on it. Photograph that card lying on a
        // dark bench, though, and the biggest dark population is the BENCH:
        // the split lands between bench and card, the aiming mark ends up in
        // the light class, and nothing is found. Re-running on the central
        // 60% usually excludes the bench and puts the card back in charge of
        // the histogram.
        val full = detectIn(frame, 0, 0, frame.width, frame.height)
        if (full != null && full.confidence >= GOOD_ENOUGH) return full

        val insetX = (frame.width * 0.2).toInt()
        val insetY = (frame.height * 0.2).toInt()
        val cropped = detectIn(
            frame, insetX, insetY, frame.width - insetX, frame.height - insetY
        )
        return when {
            cropped == null -> full
            full == null -> cropped
            cropped.confidence > full.confidence -> cropped
            else -> full
        }
    }

    private fun detectIn(frame: LumaFrame, x0: Int, y0: Int, x1: Int, y1: Int): DetectedDisc? {
        val regionW = x1 - x0
        val regionH = y1 - y0
        if (regionW < 32 || regionH < 32) return null
        val step = max(1, max(regionW, regionH) / WORK_MAX)
        val w = regionW / step
        val h = regionH / step
        if (w < 16 || h < 16) return null

        val small = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) small[y * w + x] = frame.at(x0 + x * step, y0 + y * step)

        val threshold = otsu(small)
        val dark = BooleanArray(w * h) { small[it] <= threshold }

        val best = bestDisc(dark, w, h) ?: run {
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

        val (semiMajor, semiMinor, angle) = best.ellipse()
        // Shape from the second moments, SIZE from the bounding box. The
        // moments give a far better axis ratio for a rotated ellipse, but
        // they are not robust for size: shots cluster centrally, and holes
        // removed from the middle inflate a second moment while leaving the
        // outline — and therefore the bounding box — untouched.
        val axisRatio = if (semiMajor > 1e-6) (semiMinor / semiMajor).coerceIn(0.05, 1.0) else 1.0
        val ellipticity = 1.0 / axisRatio
        val fill = best.count.toDouble() / max(1, best.width * best.height)

        // Three independent agreements, multiplied rather than averaged, so
        // failing any one pulls the result down instead of being masked by
        // the other two.
        val roundness = (1.0 - (ellipticity - 1.0) / 0.5).coerceIn(0.0, 1.0)
        val fillScore = ((fill - MIN_FILL) / (0.785 - MIN_FILL)).coerceIn(0.0, 1.0)
        val sizeScore = (rSmall / (shortSide * 0.25)).coerceIn(0.0, 1.0)
        val confidence = roundness * (0.5 + 0.5 * fillScore) * (0.5 + 0.5 * sizeScore)

        val disc = DetectedDisc(
            centreXPx = x0 + best.centreX * step,
            centreYPx = y0 + best.centreY * step,
            radiusPx = rSmall * step,
            ellipticity = ellipticity,
            fillRatio = fill,
            confidence = confidence,
            axisRatio = axisRatio,
            orientationDeg = angle
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

    /** True when the mark is elliptical enough that a plain square box will
     *  not do and the tilt controls are needed. */
    fun looksOblique(disc: DetectedDisc): Boolean = disc.ellipticity > OBLIQUE_ELLIPTICITY

    /**
     * A starting tilt, inferred from how elliptical the aiming mark is.
     *
     * A circle tilted by alpha projects to an ellipse whose minor axis is
     * cos(alpha) of its major, so alpha = acos(minor / major). The
     * foreshortening acts ALONG THE MINOR AXIS — that is the direction the
     * target is receding in — so the total angle is split between the two
     * tilt controls by the components of the minor axis direction.
     *
     * THE SIGN IS A GUESS, AND KNOWINGLY SO. An ellipse is symmetric: it
     * tells you the target leans, and by how much, but not which way. Only
     * the keystone asymmetry distinguishes leaning towards the camera from
     * leaning away, and that is a much weaker signal than the foreshortening
     * — far too weak to read off a shot-up aiming mark. So the app picks a
     * sign, draws the outline, and lets the user flip the slider if it went
     * the wrong way. With the outline visible that is a one-second fix; a
     * confident wrong answer with no preview would not be.
     */
    fun suggestedTransform(disc: DetectedDisc): BoxTransform {
        if (disc.ellipticity <= MIN_ELLIPTICITY_TO_SUGGEST) return BoxTransform.NONE
        val alpha = Math.toDegrees(kotlin.math.acos(disc.axisRatio.coerceIn(0.0, 1.0)))
        // Minor axis direction: the major axis turned through a right angle.
        // The orientation is measured in IMAGE coordinates, where y runs
        // down, while the tilt controls are in target coordinates, where it
        // runs up — hence the negated y component. It only affects which way
        // the suggestion points, not how it splits between the two axes, but
        // an axis convention that is wrong on purpose is a trap for whoever
        // reads this next.
        val phi = Math.toRadians(disc.orientationDeg)
        val dx = -kotlin.math.sin(phi)
        val dy = -kotlin.math.cos(phi)
        return BoxTransform(
            rotationDeg = 0.0,
            tiltXDeg = (alpha * dx).coerceIn(-BoxTransform.MAX_TILT_DEG, BoxTransform.MAX_TILT_DEG),
            tiltYDeg = (alpha * dy).coerceIn(-BoxTransform.MAX_TILT_DEG, BoxTransform.MAX_TILT_DEG)
        )
    }

    // ------------------------------------------------------------------

    private class Blob {
        var count = 0
        var minX = Int.MAX_VALUE; var maxX = Int.MIN_VALUE
        var minY = Int.MAX_VALUE; var maxY = Int.MIN_VALUE
        var sumX = 0L; var sumY = 0L
        var sumXX = 0.0; var sumYY = 0.0; var sumXY = 0.0
        var touchesBorder = false
        val width get() = maxX - minX + 1
        val height get() = maxY - minY + 1
        val centreX get() = sumX.toDouble() / count
        val centreY get() = sumY.toDouble() / count

        /** Semi-major, semi-minor and the major axis angle in degrees, from
         *  the second central moments. For a uniform disc of radius R the
         *  central moment is R^2/4, so the semi-axis is 2*sqrt(lambda). */
        fun ellipse(): Triple<Double, Double, Double> {
            if (count < 8) return Triple(width / 2.0, height / 2.0, 0.0)
            val n = count.toDouble()
            val mx = sumX / n
            val my = sumY / n
            val sxx = sumXX / n - mx * mx
            val syy = sumYY / n - my * my
            val sxy = sumXY / n - mx * my
            val common = kotlin.math.sqrt((sxx - syy) * (sxx - syy) + 4.0 * sxy * sxy)
            val l1 = (sxx + syy + common) / 2.0
            val l2 = (sxx + syy - common) / 2.0
            val semiMajor = 2.0 * kotlin.math.sqrt(max(l1, 0.0))
            val semiMinor = 2.0 * kotlin.math.sqrt(max(l2, 0.0))
            val angle = Math.toDegrees(0.5 * kotlin.math.atan2(2.0 * sxy, sxx - syy))
            return Triple(semiMajor, semiMinor, angle)
        }
    }

    /**
     * The most aiming-mark-like four-connected dark component.
     *
     * Not simply the largest. A photograph of a target on a range often
     * contains something bigger and darker than the aiming mark — a shadow, a
     * frame, a doorway behind the butts — and picking on size alone walks
     * straight into it. The score multiplies area by how central the
     * component is, because the one thing that is reliably true of the mark
     * the user is trying to register is that they pointed the camera at it.
     */
    private fun bestDisc(dark: BooleanArray, w: Int, h: Int): Blob? {
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
                b.sumXX += (x * x).toDouble(); b.sumYY += (y * y).toDouble()
                b.sumXY += (x * y).toDouble()
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
            if (current == null || score(b, w, h) > score(current, w, h)) best = b
        }
        return best
    }

    /** Area weighted by centrality; a component at the very edge of the frame
     *  has to be four times the size of a central one to win. */
    private fun score(b: Blob, w: Int, h: Int): Double {
        val dx = (b.centreX - w / 2.0) / (w / 2.0)
        val dy = (b.centreY - h / 2.0) / (h / 2.0)
        val offCentre = min(1.0, kotlin.math.hypot(dx, dy))
        return b.count * (1.0 - 0.75 * offCentre)
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
