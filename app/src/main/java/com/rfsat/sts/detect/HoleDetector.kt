package com.rfsat.sts.detect

import com.rfsat.sts.log.Logger
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A hole found in the rectified face, in target-plane millimetres.
 *
 * [confidence] is a 0..1 summary of how hole-like the detection was —
 * contrast against the local background, roundness, and size agreement with
 * the gauge. It is used to order detections, to mark the doubtful ones on the
 * plot, and to decide what a live detector will accept without a second look.
 * It is NOT a probability; it is a monotone score, and the thresholds it is
 * compared against were chosen by what they reject, not by calibration.
 */
data class DetectedHole(
    val xMm: Double,
    val yMm: Double,
    /** Apparent diameter of the dark (or, inside the black, light) region, mm. */
    val diameterMm: Double,
    /** Local contrast that produced the detection, in luma levels. */
    val contrast: Double,
    val confidence: Double,
    /** Longer extent / shorter extent of the region. Near 1 for a hole; large
     *  for a stretch of printed ring line that survived the other tests. */
    val elongation: Double
) {
    val distanceFromCentreMm: Double get() = hypot(xMm, yMm)
}

/**
 * ============================================================================
 *  FINDING HOLES
 * ============================================================================
 *
 * Two detectors, because they solve genuinely different problems and the
 * better one is not always available.
 *
 *  [detectByDifference] — a rectified BEFORE frame and a rectified AFTER
 *      frame. What changed is what was shot. This is the accurate path: it is
 *      immune to the printed rings, to paper texture, to staple shadows and
 *      to the black aiming mark, because all of those are in both frames. It
 *      is what the live session uses continuously, and what the photo
 *      workflow uses whenever the shooter remembered to photograph the clean
 *      target first. Use it whenever you can.
 *
 *  [detectAbsolute] — one frame, nothing to compare against. Now the printed
 *      geometry IS the difficulty: a ring line is dark, a hole is dark, and
 *      the aiming mark is darker than either. This detector separates them on
 *      shape and scale rather than on brightness, and it is honest about
 *      being the weaker of the two. Use it for a target someone hands you at
 *      the end of a relay.
 *
 * Both work in the rectified plane, so a hole is the same size in pixels
 * wherever it lands and the scale is known exactly — see [TargetRegistration].
 */
object HoleDetector {

    /** Robust noise scaling: 1 MAD = 0.6745 sigma for Gaussian noise. */
    private const val MAD_TO_SIGMA = 1.4826

    /** How many robust sigmas above the background a response must sit. Six
     *  is deliberately conservative: a false hole is worse than a missed one,
     *  because a missed one is visibly missing from the count and a false one
     *  quietly costs the shooter a point. */
    private const val SIGMA_THRESHOLD = 6.0

    /** Absolute floor, luma levels. Under about 8 levels of contrast the
     *  detection is inside the sensor's own noise on a phone at ISO 400. */
    private const val MIN_CONTRAST = 8.0

    /** A hole may measure between these fractions of the gauge and still be
     *  accepted. The generous upper bound covers overlapping shots and torn
     *  paper; anything above it is a tear or a shadow, not a hole. */
    private const val MIN_SIZE_RATIO = 0.45
    private const val MAX_SIZE_RATIO = 2.6

    /** Above this ratio of long to short extent the region is a line, not a
     *  hole — which is what a printed ring looks like to a blob detector. */
    private const val MAX_ELONGATION = 2.2

    /** A rotational partner counts as a twin at this fraction of the
     *  candidate's own contrast. The result is insensitive to it: anything
     *  from 0.35 to 0.65 gave identical answers on both test targets. */
    private const val TWIN_STRENGTH = 0.45

    /** How many of the three partners must look alike before the candidate is
     *  treated as printing. Two, so that a single coincidence cannot discard
     *  a real shot. */
    private const val TWINS_TO_REJECT = 2

    // =====================================================================
    //  Differential detection
    // =====================================================================

    /**
     * Finds what appeared in [after] that was not in [before]. Both must be
     * rectified through the SAME [reg], which is what makes them comparable
     * pixel for pixel even if the camera moved between them.
     *
     * EXPOSURE NORMALISATION. A cloud crossing the sun shifts every pixel at
     * once, and a naive subtraction then reports the whole target as new.
     * Before differencing, the median of (after - before) is removed. The
     * median rather than the mean, because a handful of genuinely new holes
     * must not be allowed to drag the correction: with fewer than half the
     * pixels changed — always true — the median is exactly the illumination
     * shift and nothing else.
     */
    fun detectByDifference(
        reg: TargetRegistration,
        before: LumaFrame,
        after: LumaFrame,
        gaugeDiameterMm: Double,
        maxHoles: Int = 200
    ): List<DetectedHole> {
        if (before.width != after.width || before.height != after.height) {
            Logger.w("HoleDetector", "Rectified frames differ in size; refusing to difference them")
            return emptyList()
        }
        val w = before.width
        val h = before.height
        val n = w * h

        // ---- illumination shift, from the median of the differences ----
        val offset = medianDifference(before, after)

        // ---- signed difference, with the shift removed ----
        val diff = IntArray(n)
        var valid = 0
        for (i in 0 until n) {
            val b = before.data[i].toInt() and 0xFF
            val a = after.data[i].toInt() and 0xFF
            if (b == OUT || a == OUT) { diff[i] = 0; continue }
            diff[i] = (a - b - offset).roundToInt()
            valid++
        }
        if (valid < n / 20) {
            Logger.w("HoleDetector", "Almost the whole rectified frame is out of view; nothing to compare")
            return emptyList()
        }

        // ---- robust threshold from the difference field itself ----
        val sigma = robustSigma(diff)
        val threshold = max(MIN_CONTRAST, SIGMA_THRESHOLD * sigma)

        // A new hole is a LOCAL change of either sign: darker on white paper,
        // lighter inside the black aiming mark where the pellet exposes the
        // backing. Magnitude is the right test here precisely because the
        // reference removes everything that was legitimately dark already.
        val mask = BooleanArray(n)
        for (i in 0 until n) mask[i] = abs(diff[i]) >= threshold

        val gaugePx = gaugeDiameterMm / reg.mmPerPx
        val expectedArea = Math.PI * (gaugePx / 2.0) * (gaugePx / 2.0)

        return components(mask, w, h, maxComponents = maxHoles * 4)
            .mapNotNull { comp -> holeFromComponent(comp, diff, w, reg, gaugePx, expectedArea) }
            .sortedByDescending { it.confidence }
            .take(maxHoles)
    }

    // =====================================================================
    //  Absolute (single-frame) detection
    // =====================================================================

    /**
     * Finds holes in a single rectified frame, with no clean reference.
     *
     * Two stages, and the first is what makes the second workable.
     *
     * FIRST the printed target is subtracted from itself by radial median —
     * see [radiallyNormalise]. Every ring line and the aiming mark are
     * rotationally symmetric and vanish; the holes, which are not, remain.
     * Relying on the centre-surround operator alone to reject printing was
     * not good enough: it rejects a ring line only for being long and thin,
     * which fails wherever rings run close together or a numeral is printed.
     *
     * THEN a centre-surround contrast on what is left: for every pixel,
     * compare the mean over a disc of the gauge's own size against the mean
     * over the annulus immediately outside it. That is scale-selective by
     * construction — a feature much smaller than the disc barely moves the
     * inner mean, and a feature much larger moves inner and outer together
     * and cancels.
     *
     * The disc and annulus are evaluated as squares through an integral
     * image. A square is a poor circle, but it is a 4-array-read circle, and
     * the shape error is a constant bias on both means that largely cancels
     * in the difference. Exactness comes later, from the weighted centroid.
     *
     * SIGN. On white paper a hole is dark, so the contrast is
     * annulus-minus-disc and positive. Inside the black aiming mark the paper
     * is already at floor and a hole exposes the lighter backing, so the sign
     * flips. The detector therefore looks for a dark spot outside the black
     * and for a spot of EITHER sign inside it, which is the honest reading of
     * the physics: what a hole in the black looks like depends on what is
     * behind the target, and the app does not know that.
     */
    fun detectAbsolute(
        reg: TargetRegistration,
        frame: LumaFrame,
        gaugeDiameterMm: Double,
        maxHoles: Int = 200
    ): List<DetectedHole> {
        val w = frame.width
        val h = frame.height
        val n = w * h

        // ---- which pixels the camera covered AND are worth looking at ----
        //
        // Absolute detection is confined to the SCORING AREA. Everything
        // outside the outermost ring is card furniture — a club logo, a score
        // box, the shooter's name, the edge of the paper, a thumb holding it
        // down — and none of it can be scored even if found. Tested on a real
        // club target, the unrestricted detector reported the association's
        // logo as a shot. Excluding that region also drops the noise floor it
        // was contributing: on the same photograph the robust sigma fell from
        // 4.4 to 3.0 and the threshold with it, which was enough to find a
        // faint fifth hole that had been missed.
        //
        // Deliberately NOT done in [detectByDifference]: there the reference
        // cancels every static feature already, so a mark outside the rings
        // really is a shot, and it should be reported as the miss it is.
        val outerLimit = reg.face.outerRadiusMm * 1.02
        val valid = BooleanArray(n)
        for (idx in 0 until n) {
            if ((frame.data[idx].toInt() and 0xFF) == OUT) continue
            val (u, v) = reg.rectToMm(idx % frame.width, idx / frame.width)
            if (outerLimit > 0.0 && hypot(u, v) > outerLimit) continue
            valid[idx] = true
        }
        val validFraction = valid.count { it }.toDouble() / n
        if (validFraction < 0.05) {
            Logger.w("HoleDetector", "Almost none of the rectified face is in view; nothing to score")
            return emptyList()
        }

        // ---- remove the printed target ----
        val normalised = radiallyNormalise(reg, frame, valid)

        val integral = MaskedIntegralImage(normalised, valid)

        val gaugePx = gaugeDiameterMm / reg.mmPerPx
        val rIn = max(1, (gaugePx / 2.0).roundToInt())
        val rOut = max(rIn + 2, (gaugePx * 1.6).roundToInt())
        // A window must be mostly real data before its mean means anything.
        val minInner = max(3, ((2 * rIn + 1) * (2 * rIn + 1) * 0.6).roundToInt())
        val minOuter = max(6, (((2 * rOut + 1) * (2 * rOut + 1) - (2 * rIn + 1) * (2 * rIn + 1)) * 0.6).roundToInt())

        val blackRadiusMm = reg.face.blackDiameterMm / 2.0

        val response = IntArray(n)
        for (j in 0 until h) {
            for (i in 0 until w) {
                val idx = j * w + i
                if (!valid[idx]) continue

                val inner = integral.mean(i - rIn, j - rIn, i + rIn + 1, j + rIn + 1, minInner)
                if (inner.isNaN()) continue

                val outerSum = integral.sum(i - rOut, j - rOut, i + rOut + 1, j + rOut + 1) -
                    integral.sum(i - rIn, j - rIn, i + rIn + 1, j + rIn + 1)
                val outerN = integral.validCount(i - rOut, j - rOut, i + rOut + 1, j + rOut + 1) -
                    integral.validCount(i - rIn, j - rIn, i + rIn + 1, j + rIn + 1)
                if (outerN < minOuter) continue
                val annulus = outerSum.toDouble() / outerN

                val darkSpot = annulus - inner   // positive when the centre is darker
                val (u, v) = reg.rectToMm(i, j)
                val inBlack = blackRadiusMm > 0.0 && hypot(u, v) <= blackRadiusMm
                response[idx] = (if (inBlack) abs(darkSpot) else darkSpot).roundToInt()
            }
        }

        val sigma = robustSigma(response)
        val threshold = max(MIN_CONTRAST, SIGMA_THRESHOLD * sigma)
        val mask = BooleanArray(n)
        for (i in 0 until n) mask[i] = response[i] >= threshold

        val expectedArea = Math.PI * (gaugePx / 2.0) * (gaugePx / 2.0)

        val candidates = components(mask, w, h, maxComponents = maxHoles * 4)
            .mapNotNull { comp -> holeFromComponent(comp, response, w, reg, gaugePx, expectedArea) }

        return candidates
            .filterNot { hasRotationalTwins(it, response, w, h, reg, gaugePx) }
            .sortedByDescending { it.confidence }
            .take(maxHoles)
    }

    /**
     * True when the same feature appears at the other three quarter-turns
     * about the scoring centre — which means it is printed, not shot.
     *
     * WHAT THIS CATCHES that the radial median cannot. Radial normalisation
     * removes anything ROTATIONALLY symmetric, which is every ring line. It
     * does nothing about the ring NUMERALS, because a numeral occupies four
     * angles out of three hundred and sixty and barely moves a median taken
     * around the whole circumference. On a synthetic ISSF-style face with its
     * rings numbered at north, south, east and west, the detector was
     * returning twenty-two candidates for five real shots, and seventeen of
     * them were printed digits.
     *
     * WHY AS A PER-CANDIDATE TEST rather than a four-fold median over the
     * whole image. The four-fold median was tried first and is beautiful on
     * synthetic data — it removed every numeral perfectly. On a real
     * photograph it LOST two of five genuine shots, because the four rotated
     * samples only correspond when registration is exact and the lighting is
     * flat, and on a hand-held photograph of a card on a range neither holds.
     * Testing one candidate at a time is far more forgiving: a real shot is
     * discarded only if two of its three rotational partners independently
     * look like shots too, which needs a coincidence rather than a gradient.
     *
     * Measured on both test targets: the synthetic face goes from 22
     * candidates to exactly 5 with none lost, and the photograph is
     * untouched.
     */
    private fun hasRotationalTwins(
        hole: DetectedHole,
        response: IntArray,
        w: Int,
        h: Int,
        reg: TargetRegistration,
        gaugePx: Double
    ): Boolean {
        val (cx, cy) = reg.mmToRect(0.0, 0.0)
        val (hx, hy) = reg.mmToRect(hole.xMm, hole.yMm)
        val dx = hx - cx
        val dy = hy - cy
        val search = gaugePx * 0.7
        val bar = TWIN_STRENGTH * hole.contrast

        var twins = 0
        for ((ex, ey) in listOf(-dy to dx, -dx to -dy, dy to -dx)) {
            if (peakNear(response, w, h, cx + ex, cy + ey, search) >= bar) twins++
        }
        return twins >= TWINS_TO_REJECT
    }

    /** Strongest response within [radius] pixels of a point. */
    private fun peakNear(response: IntArray, w: Int, h: Int, x: Double, y: Double, radius: Double): Int {
        var best = 0
        val r2 = radius * radius
        val j0 = max(0, (y - radius).toInt())
        val j1 = min(h - 1, (y + radius).toInt())
        val i0 = max(0, (x - radius).toInt())
        val i1 = min(w - 1, (x + radius).toInt())
        for (j in j0..j1) {
            for (i in i0..i1) {
                val ddx = i - x
                val ddy = j - y
                if (ddx * ddx + ddy * ddy <= r2) {
                    val v = response[j * w + i]
                    if (v > best) best = v
                }
            }
        }
        return best
    }

    /**
     * Subtracts the printed target from itself.
     *
     * THE PROBLEM THIS SOLVES. Without a clean reference frame the detector
     * has to tell a hole from the printing, and on a competition face the
     * printing is the same colour as a hole: ring lines are black, the aiming
     * mark is blacker, and the boundary between them is the strongest edge in
     * the picture. A centre-surround operator alone rejects a ring line only
     * because it is long and thin, which fails wherever two rings run close
     * together or a numeral is printed.
     *
     * THE OBSERVATION THAT FIXES IT. Everything printed on a ringed target is
     * ROTATIONALLY SYMMETRIC about the scoring centre. A ring line is dark at
     * every angle at its own radius; the aiming mark is dark at every angle
     * inside its radius. A shot hole is dark at ONE angle only. So taking the
     * median brightness around each radius and subtracting it removes every
     * printed ring exactly, at every radius, with no threshold to tune — and
     * leaves the holes standing, because a handful of holes cannot move a
     * median taken over a whole circumference.
     *
     * The median, not the mean: with a mean, a big enough group would drag
     * the baseline toward itself and start suppressing the very holes being
     * looked for.
     *
     * Output is centred on 128 so a hole on paper is still a DARK spot and a
     * hole in the aiming mark is still a light one, which is what the rest of
     * the detector already expects.
     */
    private fun radiallyNormalise(
        reg: TargetRegistration,
        frame: LumaFrame,
        valid: BooleanArray
    ): LumaFrame {
        val w = frame.width
        val h = frame.height
        val (ci, cj) = reg.mmToRect(0.0, 0.0)

        var maxR = 0
        val binOf = IntArray(w * h)
        for (j in 0 until h) {
            for (i in 0 until w) {
                val idx = j * w + i
                val r = hypot(i - ci, j - cj).toInt()
                binOf[idx] = r
                if (r > maxR) maxR = r
            }
        }

        val bins = maxR + 1
        val hist = IntArray(bins * 256)
        val counts = IntArray(bins)
        for (idx in 0 until w * h) {
            if (!valid[idx]) continue
            val b = binOf[idx]
            hist[b * 256 + (frame.data[idx].toInt() and 0xFF)]++
            counts[b]++
        }

        val median = IntArray(bins)
        for (b in 0 until bins) {
            val total = counts[b]
            if (total == 0) { median[b] = 128; continue }
            var acc = 0
            for (level in 0 until 256) {
                acc += hist[b * 256 + level]
                if (acc * 2 >= total) { median[b] = level; break }
            }
        }

        val out = ByteArray(w * h)
        for (idx in 0 until w * h) {
            if (!valid[idx]) { out[idx] = TargetRegistration.OUT_OF_FRAME; continue }
            val v = (frame.data[idx].toInt() and 0xFF) - median[binOf[idx]] + 128
            // Never let a normalised value collide with the out-of-frame
            // marker, or a legitimately dark pixel would be treated as
            // missing data by everything downstream.
            out[idx] = v.coerceIn(2, 255).toByte()
        }
        return LumaFrame(w, h, out)
    }

    // =====================================================================
    //  Shared machinery
    // =====================================================================

    private const val OUT = 1 // TargetRegistration.OUT_OF_FRAME as an Int

    /** One connected run of above-threshold pixels. */
    private class Component {
        var count = 0
        var minX = Int.MAX_VALUE; var maxX = Int.MIN_VALUE
        var minY = Int.MAX_VALUE; var maxY = Int.MIN_VALUE
        val pixels = ArrayList<Int>()
    }

    /**
     * Four-connected labelling with an explicit stack.
     *
     * Explicit rather than recursive: a large blown-out region on a
     * 2000-pixel-wide rectified frame can chain hundreds of thousands of
     * pixels deep, and recursion there is a StackOverflowError on a real
     * device — reliably, and only for the users with the worst lighting.
     */
    private fun components(mask: BooleanArray, w: Int, h: Int, maxComponents: Int): List<Component> {
        val seen = BooleanArray(mask.size)
        val out = ArrayList<Component>()
        val stack = ArrayDeque<Int>()
        for (start in mask.indices) {
            if (!mask[start] || seen[start]) continue
            val comp = Component()
            stack.addLast(start)
            seen[start] = true
            while (stack.isNotEmpty()) {
                val p = stack.removeLast()
                val x = p % w
                val y = p / w
                comp.count++
                comp.pixels.add(p)
                if (x < comp.minX) comp.minX = x
                if (x > comp.maxX) comp.maxX = x
                if (y < comp.minY) comp.minY = y
                if (y > comp.maxY) comp.maxY = y
                if (x > 0) push(p - 1, mask, seen, stack)
                if (x < w - 1) push(p + 1, mask, seen, stack)
                if (y > 0) push(p - w, mask, seen, stack)
                if (y < h - 1) push(p + w, mask, seen, stack)
            }
            out.add(comp)
            if (out.size >= maxComponents) {
                Logger.w("HoleDetector", "Component cap ($maxComponents) reached — the frame is very noisy")
                break
            }
        }
        return out
    }

    private fun push(p: Int, mask: BooleanArray, seen: BooleanArray, stack: ArrayDeque<Int>) {
        if (!seen[p] && mask[p]) { seen[p] = true; stack.addLast(p) }
    }

    /**
     * Turns a connected component into a hole, or rejects it.
     *
     * The centroid is weighted by the response magnitude rather than being
     * the plain pixel mean. That matters: a hole is a smooth blob, its
     * response peaks at the centre, and weighting recovers the centre to
     * roughly a fifth of a pixel — which at the working resolution is a
     * couple of tenths of a millimetre, comfortably finer than the ring
     * boundaries it is compared against.
     */
    private fun holeFromComponent(
        comp: Component,
        response: IntArray,
        w: Int,
        reg: TargetRegistration,
        gaugePx: Double,
        expectedArea: Double
    ): DetectedHole? {
        if (comp.count < 3) return null

        val areaRatio = comp.count / expectedArea
        if (areaRatio < MIN_SIZE_RATIO * MIN_SIZE_RATIO) return null
        if (areaRatio > MAX_SIZE_RATIO * MAX_SIZE_RATIO) return null

        val bw = (comp.maxX - comp.minX + 1).toDouble()
        val bh = (comp.maxY - comp.minY + 1).toDouble()
        val elongation = max(bw, bh) / max(1.0, min(bw, bh))
        if (elongation > MAX_ELONGATION) return null

        var wsum = 0.0
        var xs = 0.0
        var ys = 0.0
        var peak = 0.0
        for (p in comp.pixels) {
            val v = abs(response[p]).toDouble()
            if (v > peak) peak = v
            wsum += v
            xs += (p % w) * v
            ys += (p / w) * v
        }
        if (wsum <= 0.0) return null
        val ci = xs / wsum
        val cj = ys / wsum

        // Sub-pixel rect coordinates back to millimetres. rectToMm takes
        // integers, so the fractional part is applied afterwards by hand
        // rather than by rounding away the precision just recovered.
        val (u0, v0) = reg.rectToMm(ci.toInt(), cj.toInt())
        val uMm = u0 + (ci - ci.toInt()) * reg.mmPerPx
        val vMm = v0 - (cj - cj.toInt()) * reg.mmPerPx

        // Equivalent-circle diameter of the component, in millimetres.
        val diameterPx = 2.0 * sqrt(comp.count / Math.PI)
        val diameterMm = diameterPx * reg.mmPerPx

        // Confidence: three independent agreements, multiplied so that
        // failing any one of them pulls the result down rather than being
        // averaged away by the other two.
        val contrastScore = (peak / (4.0 * MIN_CONTRAST)).coerceIn(0.0, 1.0)
        val sizeScore = 1.0 - (abs(diameterPx - gaugePx) / gaugePx).coerceIn(0.0, 1.0)
        val roundScore = (1.0 - (elongation - 1.0) / (MAX_ELONGATION - 1.0)).coerceIn(0.0, 1.0)
        val confidence = contrastScore * sizeScore * roundScore

        return DetectedHole(
            xMm = uMm,
            yMm = vMm,
            diameterMm = diameterMm,
            contrast = peak,
            confidence = confidence,
            elongation = elongation
        )
    }

    /** Median of (after - before) over pixels valid in both frames. */
    private fun medianDifference(before: LumaFrame, after: LumaFrame): Double {
        val n = before.width * before.height
        // A histogram rather than a sort: the values live in [-255, 255], so
        // this is a 511-bucket count and one pass, instead of sorting several
        // million elements on the UI thread's timescale.
        val hist = IntArray(511)
        var total = 0
        for (i in 0 until n) {
            val b = before.data[i].toInt() and 0xFF
            val a = after.data[i].toInt() and 0xFF
            if (b == OUT || a == OUT) continue
            hist[a - b + 255]++
            total++
        }
        if (total == 0) return 0.0
        var acc = 0
        for (k in hist.indices) {
            acc += hist[k]
            if (acc * 2 >= total) return (k - 255).toDouble()
        }
        return 0.0
    }

    /**
     * Robust standard deviation of a field, from the median absolute
     * deviation about zero.
     *
     * The plain standard deviation is useless here: the holes themselves are
     * large outliers, so including them inflates the estimate and raises the
     * threshold until the holes no longer pass it — the detector suppresses
     * exactly what it is looking for, more strongly the more there is to
     * find. The MAD ignores anything past the median and is unmoved by up to
     * half the field being signal.
     */
    private fun robustSigma(field: IntArray): Double {
        val hist = IntArray(512)
        var total = 0
        for (v in field) {
            val a = abs(v)
            if (a == 0) continue          // untouched pixels carry no information
            hist[min(a, 511)]++
            total++
        }
        if (total == 0) return 0.0
        var acc = 0
        var mad = 0
        for (k in hist.indices) {
            acc += hist[k]
            if (acc * 2 >= total) { mad = k; break }
        }
        return mad * MAD_TO_SIGMA
    }
}
