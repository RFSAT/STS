package com.rfsat.sts.detect

import com.rfsat.sts.log.Logger
import com.rfsat.sts.targets.TargetFace
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/** A fitted ring family: where the target is, how big it is, how sure we are. */
data class RingFit(
    val centreXPx: Double,
    val centreYPx: Double,
    /** Radial spacing of the printed rings, source pixels. THE key number:
     *  it sets the scale, and it is measured over the whole family rather
     *  than from any single feature. */
    val pitchPx: Double,
    /** Radii actually found on the ladder, source pixels. */
    val ringsPx: List<Double>,
    /** Mean absolute departure from a perfect progression, pixels. */
    val residualPx: Double,
    val confidence: Double,
    /** Semi-minor over semi-major of the ring family, from [HoughCentre].
     *  1.0 is circular. Reported, and used to SEED the tilt controls — never
     *  applied on its own; see HoughCentre.TILT_NOISE_FLOOR_DEG. */
    val axisRatio: Double = 1.0,
    val orientationDeg: Double = 0.0,
    /** Which shape model won on the aiming-mark outline, and why. Null when
     *  no outline could be extracted. */
    val shape: RingShapeChoice? = null,
    /** The de-foreshortening actually applied before this fit was made. When
     *  non-null, every pixel coordinate in this RingFit is in CORRECTED
     *  coordinates, and [correctedFrame] maps them back to the source. */
    val correction: ShapeCorrection? = null,
    val correctedFrame: CorrectedFrame? = null
) {
    /** The tilt the ring ellipticity implies, degrees. */
    val impliedTiltDeg: Double
        get() = Math.toDegrees(kotlin.math.acos(axisRatio.coerceIn(0.0, 1.0)))

    /** True only when the ellipticity is clear of its own noise floor. */
    val tiltWorthSuggesting: Boolean
        get() = impliedTiltDeg > HoughCentre.TILT_NOISE_FLOOR_DEG

    val ringCount: Int get() = ringsPx.size
    val outermostPx: Double get() = ringsPx.maxOrNull() ?: 0.0
}

/** A face the fit could belong to, best first. */
data class FaceMatch(val face: TargetFace, val relativeError: Double, val mmPerPx: Double)

/**
 * ============================================================================
 *  FITTING THE RING FAMILY
 * ============================================================================
 *
 * Every registration failure this app has had came from the same place:
 * deriving the scale from ONE feature — the black aiming mark — multiplied by
 * a ratio taken from whichever face happened to be selected in a menu. Choose
 * the wrong face and the box lands on the wrong circle, silently, and every
 * score after it is wrong.
 *
 * The picture contains far better evidence. A competition target is a family
 * of concentric, EVENLY SPACED rings, and that spacing is the scale. Measured
 * across six or eight rings it is precise to a fraction of a percent, where
 * the black-mark ratio was out by six. And because the spacing is a physical
 * property of the card rather than of the menu, it also says WHICH card it is.
 *
 * Four steps:
 *
 *   1. CENTRE by rotational symmetry. A concentric target is the same
 *      brightness all the way round at any given radius, so the true centre is
 *      the point that minimises the variance within each radius band. No
 *      feature detection, no assumptions about what is printed.
 *
 *   2. RADIAL PROFILE at a low percentile, so a thin printed line pulls the
 *      statistic down even though it is a small minority of its circumference.
 *
 *   3. RING RADII as local departures from the local trend, of EITHER sign.
 *      Sign-agnostic on purpose: rings are dark on the paper and WHITE inside
 *      the black aiming mark, and one detector has to find both.
 *
 *   4. FIT r_k = r0 + k·pitch, then refit by least squares over every ring
 *      found. The hypothesis pitch comes from one pair and carries that pair's
 *      error; refitting over eight rings divides it by roughly the square root
 *      of eight. Measured on four real targets this took the error from 1.5–7.7%
 *      down to 0.0–1.5%.
 *
 * WHERE IT DOES NOT WORK, and the app has to know. The method assumes the
 * rings are CIRCLES, which is true of a scan or a square-on photograph and
 * false of one taken at an angle, where they project to ellipses and the
 * radial profile smears them. [RingFit.confidence] falls when the ladder fits
 * badly, and the caller falls back to the aiming mark when it does.
 */
object RingFinder {

    private const val WORK_MAX = 700
    private const val PROFILE_PERCENTILE = 0.25
    private const val SMOOTH = 7
    private const val MIN_DEVIATION = 10
    private const val MIN_RINGS = 4
    private const val INLIER_TOLERANCE = 0.16

    /**
     * Fits the ring family, correcting the foreshortening first when the
     * evidence supports it.
     *
     * ORDER MATTERS. The shape is decided from the aiming-mark outline BEFORE
     * the radial profile runs, because the radial profile is the thing that
     * perspective breaks: at an angle a ring is at a different radius on
     * every bearing, so the profile smears the ring lines and the ladder fit
     * degrades. De-foreshortening first hands the existing, measured pitch
     * fit an image where its circular assumption holds — rather than trying
     * to compensate a pitch that was already smeared, which would need the
     * correction algebra to be right in a place where being wrong is silent.
     */
    fun find(frame: LumaFrame, seedX: Double = -1.0, seedY: Double = -1.0): RingFit? {
        val vote0 = HoughCentre.find(frame)
        val markX = vote0?.xPx ?: (if (seedX >= 0) seedX else frame.width / 2.0)
        val markY = vote0?.yPx ?: (if (seedY >= 0) seedY else frame.height / 2.0)
        val outline = MarkOutline.extract(frame, markX, markY)
        val choice = outline?.let { RingShapeSelector.choose(it) }
        val corrected = choice?.correction?.apply(frame)
        if (choice != null && choice.usedEllipse && corrected == null) {
            Logger.w("RingFinder", "shape correction was selected but could not be applied; using the source frame")
        }
        val working = corrected?.frame ?: frame
        // Log the uncorrected fit alongside the corrected one. The two should
        // agree on pitch to within the overall scale change of the warp, and
        // when they do not, the shared field log is the only place that is
        // visible. Cheap, and it costs one extra fit per registration rather
        // than per frame.
        if (corrected != null) {
            val plain = fitOn(frame, seedX, seedY)
            Logger.i(
                "RingFinder",
                "uncorrected fit for comparison: %s".format(
                    if (plain == null) "none"
                    else "pitch %.2f px over %d rings, residual %.2f, confidence %.2f"
                        .format(plain.pitchPx, plain.ringCount, plain.residualPx, plain.confidence)
                )
            )
        }
        val raw = fitOn(
            working,
            if (corrected != null) -1.0 else seedX,
            if (corrected != null) -1.0 else seedY
        ) ?: return null
        return raw.copy(
            shape = choice,
            correction = if (corrected != null) choice?.correction else null,
            correctedFrame = corrected
        )
    }

    /** The ring fit with no shape correction, for diagnostics and tests. */
    fun findWithoutShapeCorrection(frame: LumaFrame): RingFit? = fitOn(frame, -1.0, -1.0)

    private fun fitOn(frame: LumaFrame, seedX: Double, seedY: Double): RingFit? {
        val step = maxOf(1, maxOf(frame.width, frame.height) / WORK_MAX)
        val w = frame.width / step
        val h = frame.height / step
        if (w < 60 || h < 60) return null

        val small = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) small[y * w + x] = frame.at(x * step, y * step)

        // Seed from the Hough vote where it succeeds. Voting works from edge
        // normals, so it copes with a thumb over a corner, a club logo, or a
        // target filling only part of the frame — all of which mislead a
        // symmetry search that assumes the target dominates the picture. The
        // symmetry search then refines it, because it is the more accurate of
        // the two once it is looking in the right place.
        val vote = HoughCentre.find(frame)
        val sx = when {
            vote != null -> vote.xPx / step
            seedX >= 0 -> seedX / step
            else -> w / 2.0
        }
        val sy = when {
            vote != null -> vote.yPx / step
            seedY >= 0 -> seedY / step
            else -> h / 2.0
        }
        val (cx, cy) = symmetryCentre(small, w, h, sx, sy) ?: return null

        val profile = radialProfile(small, w, h, cx, cy) ?: return null
        val peaks = mergeClose(ringCandidates(profile))
        if (peaks.size < MIN_RINGS) {
            Logger.i("RingFinder", "only ${peaks.size} ring candidates; not enough to fit")
            return null
        }
        val fit = fitLadder(peaks) ?: run {
            Logger.i("RingFinder", "ring candidates do not form an even progression")
            return null
        }

        val result = RingFit(
            centreXPx = cx * step,
            centreYPx = cy * step,
            pitchPx = fit.pitch * step,
            ringsPx = fit.rings.map { it * step },
            residualPx = fit.residual * step,
            confidence = fit.confidence,
            axisRatio = vote?.axisRatio ?: 1.0,
            orientationDeg = vote?.orientationDeg ?: 0.0
        )
        Logger.i(
            "RingFinder",
            "centre (%.0f, %.0f), pitch %.2f px over %d rings, residual %.2f px, confidence %.2f"
                .format(result.centreXPx, result.centreYPx, result.pitchPx,
                    result.ringCount, result.residualPx, result.confidence)
        )
        return result
    }

    /**
     * Which catalogue face this fit belongs to.
     *
     * The fitted pitch sets the scale; the aiming mark is then an INDEPENDENT
     * measurement that discriminates between faces. On the two real targets
     * tested, the correct face scored within 1.3% while the runner-up was 8%
     * or worse — a margin wide enough to act on rather than merely report.
     */
    fun identify(fit: RingFit, blackRadiusPx: Double, candidates: List<TargetFace>): List<FaceMatch> {
        if (blackRadiusPx <= 0.0) return emptyList()
        return candidates.mapNotNull { face ->
            val pitchMm = face.ringPitchMm ?: return@mapNotNull null
            if (pitchMm <= 0.0 || face.blackDiameterMm <= 0.0) return@mapNotNull null
            val mmPerPx = pitchMm / fit.pitchPx
            val predictedBlackPx = (face.blackDiameterMm / 2.0) / mmPerPx
            FaceMatch(face, abs(predictedBlackPx - blackRadiusPx) / blackRadiusPx, mmPerPx)
        }.sortedBy { it.relativeError }
    }

    // ------------------------------------------------------------------

    private fun symmetryCentre(
        img: IntArray, w: Int, h: Int, seedX: Double, seedY: Double
    ): Pair<Double, Double>? {
        var bx = seedX; var by = seedY
        var span = min(w, h) / 8
        var stride = maxOf(2, span / 10)
        // Coarse to fine: a full search at the final resolution would be
        // thousands of passes over the image for no extra accuracy.
        while (stride >= 1) {
            var best: Pair<Double, Double>? = null
            var bestCost = Double.MAX_VALUE
            var dy = -span
            while (dy <= span) {
                var dx = -span
                while (dx <= span) {
                    val cost = asymmetry(img, w, h, bx + dx, by + dy)
                    if (cost < bestCost) { bestCost = cost; best = (bx + dx) to (by + dy) }
                    dx += stride
                }
                dy += stride
            }
            if (best == null) return null
            bx = best.first; by = best.second
            span = stride
            stride /= 2
        }
        return bx to by
    }

    /** Mean within-radius variance: zero for a perfectly concentric target. */
    private fun asymmetry(img: IntArray, w: Int, h: Int, cx: Double, cy: Double): Double {
        val maxR = min(min(cx, cy), min(w - cx, h - cy)).toInt()
        if (maxR < 30) return Double.MAX_VALUE
        val sum = DoubleArray(maxR + 1)
        val sq = DoubleArray(maxR + 1)
        val n = IntArray(maxR + 1)
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val r = hypot(x - cx, y - cy).toInt()
                if (r <= maxR) {
                    val v = img[y * w + x].toDouble()
                    sum[r] += v; sq[r] += v * v; n[r]++
                }
                x += 3
            }
            y += 3
        }
        var cost = 0.0; var total = 0
        for (r in 12..maxR) {
            if (n[r] < 8) continue
            val m = sum[r] / n[r]
            cost += (sq[r] / n[r] - m * m) * n[r]
            total += n[r]
        }
        return if (total == 0) Double.MAX_VALUE else cost / total
    }

    private fun radialProfile(img: IntArray, w: Int, h: Int, cx: Double, cy: Double): IntArray? {
        val maxR = min(min(cx, cy), min(w - cx, h - cy)).toInt()
        if (maxR < 30) return null
        val hist = Array(maxR + 1) { IntArray(256) }
        val count = IntArray(maxR + 1)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val r = hypot(x - cx, y - cy).toInt()
                if (r <= maxR) { hist[r][img[y * w + x]]++; count[r]++ }
            }
        }
        val out = IntArray(maxR + 1) { 255 }
        for (r in 0..maxR) {
            val total = count[r]
            if (total == 0) continue
            val target = (total * PROFILE_PERCENTILE).toInt().coerceAtLeast(1)
            var acc = 0
            for (level in 0 until 256) {
                acc += hist[r][level]
                if (acc >= target) { out[r] = level; break }
            }
        }
        return out
    }

    private fun ringCandidates(profile: IntArray): List<Double> {
        val n = profile.size
        val dev = DoubleArray(n)
        for (r in 0 until n) {
            val lo = maxOf(0, r - SMOOTH); val hi = minOf(n, r + SMOOTH + 1)
            val window = profile.copyOfRange(lo, hi).sortedArray()
            dev[r] = abs(profile[r] - window[window.size / 2]).toDouble()
        }
        val peaks = mutableListOf<Double>()
        for (r in 3 until n - 3) {
            if (dev[r] < MIN_DEVIATION) continue
            var isPeak = true
            for (k in 1..3) if (dev[r] < dev[r - k] || dev[r] < dev[r + k]) { isPeak = false; break }
            if (!isPeak) continue
            if (peaks.isNotEmpty() && r - peaks.last() < 5) {
                if (dev[r] > dev[peaks.last().toInt()]) peaks[peaks.size - 1] = r.toDouble()
                continue
            }
            peaks.add(r.toDouble())
        }
        return peaks
    }

    /** Collapse the two edges of one printed line into a single radius. */
    private fun mergeClose(peaks: List<Double>): List<Double> {
        if (peaks.size < 2) return peaks
        val diffs = (1 until peaks.size).map { peaks[it] - peaks[it - 1] }.sorted()
        val limit = diffs[diffs.size / 2] * 0.45
        val out = mutableListOf<Double>()
        var group = mutableListOf(peaks[0])
        for (r in peaks.drop(1)) {
            if (r - group.last() <= limit) group.add(r)
            else { out.add(group.average()); group = mutableListOf(r) }
        }
        out.add(group.average())
        return out
    }

    private class Ladder(
        val pitch: Double, val rings: List<Double>, val residual: Double, val confidence: Double
    )

    private fun fitLadder(radii: List<Double>): Ladder? {
        var best: Map<Int, Double>? = null
        var bestScore = -1e9
        for (i in radii.indices) {
            for (j in i + 1 until radii.size) {
                val gap = radii[j] - radii[i]
                for (k in 1..9) {
                    val p = gap / k
                    if (p < 6) continue
                    val slots = HashMap<Int, Double>()
                    for (r in radii) {
                        val kk = (r - radii[i]) / p
                        if (abs(kk - kk.roundToInt()) < INLIER_TOLERANCE) slots[kk.roundToInt()] = r
                    }
                    if (slots.size < MIN_RINGS) continue
                    val span = (slots.keys.max() - slots.keys.min() + 1)
                    // Empty rungs cost as much as filled ones earn, and a mild
                    // preference for coarser pitches breaks the tie that made
                    // an earlier version return exactly half the true pitch:
                    // a half-pitch fits every real ring PLUS the spurious
                    // second edge of every printed line, and so scored higher
                    // on inlier count alone.
                    val score = slots.size - (span - slots.size) + p * 0.02
                    if (score > bestScore) { bestScore = score; best = slots }
                }
            }
        }
        val slots = best ?: return null

        // Least squares over the whole ladder.
        val ks = slots.keys.sorted()
        val kBar = ks.average()
        val rBar = ks.map { slots[it]!! }.average()
        var num = 0.0; var den = 0.0
        for (k in ks) { num += (k - kBar) * (slots[k]!! - rBar); den += (k - kBar) * (k - kBar) }
        if (den == 0.0) return null
        val pitch = num / den
        if (pitch <= 0.0) return null
        val r0 = rBar - pitch * kBar
        val residual = ks.map { abs(slots[it]!! - (r0 + pitch * it)) }.average()

        val tightness = (1.0 - residual / pitch * 3.0).coerceIn(0.0, 1.0)
        val plenty = min(1.0, slots.size / 6.0)
        return Ladder(pitch, ks.map { slots[it]!! }, residual, tightness * plenty)
    }
}
