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
    /**
     * Percentiles the radial profile is read at.
     *
     * ONE PERCENTILE CANNOT SEE EVERY RING, and assuming it could was the
     * cause of the pitch instability on angled photographs. At a given radius
     * the circle crosses a thin printed line at only a few per cent of its
     * circumference, so the line only shows up in a percentile that the rest
     * of the circumference does not dominate:
     *
     *   - A DARK ring on light paper pulls a LOW percentile down.
     *   - A WHITE ring inside the black aiming mark — which is where rings 7
     *     to 10 live on an air rifle face — pulls a HIGH percentile up, and is
     *     completely invisible to a low one. Measured on a real target, the
     *     25th-percentile profile read exactly 0 for every radius from 0 to
     *     124 px: the whole aiming mark, flat, with three rings in it.
     *   - On a photograph with shading across the card, the 25th percentile
     *     of the paper tracks the shadow rather than the ring lines, and the
     *     MEDIAN separates them better.
     *
     * The comment this code used to carry claimed the detector was
     * sign-agnostic and so handled both. It is — but only on the DEVIATION of
     * the profile, computed after a low percentile has already discarded the
     * white lines. Sign-agnostic arithmetic cannot recover a feature that the
     * statistic before it threw away.
     *
     * The histogram is built once and read three times, so this costs a few
     * hundred microseconds, not three passes over the image.
     */
    private val PROFILE_PERCENTILES = doubleArrayOf(0.25, 0.50, 0.90)
    private const val SMOOTH = 7
    private const val MIN_DEVIATION = 10

    /** Radii closer than this, found at different percentiles, are the same
     *  printed ring. Deliberately far below any real ring spacing. */
    private const val DUPLICATE_LIMIT = 3.0

    /**
     * Run the whole fit a SECOND time without the shape correction, purely so
     * the log can compare them.
     *
     * Off by default. It was worth its cost while the correction was new and
     * unproven; it doubles the work of every registration, which is a poor
     * trade now that the correction is measured. Turn it on when a field log
     * needs to explain a bad fit.
     */
    var compareWithUncorrected = false

    /** How far a face's nominal distance may sit from the session's before it
     *  is ruled out. Wide enough for 50 ft against 15 m, and for a face used
     *  a little off its drawn distance. */
    private const val DISTANCE_WINDOW_LO = 0.6
    private const val DISTANCE_WINDOW_HI = 1.7

    /** A face already in use is kept unless a rival beats it by more than
     *  this. Set at the scale the fitted pitch itself wanders by. */
    private const val STICKY_MARGIN = 0.03
    private const val MIN_RINGS = 4
    /**
     * How far off its rung a candidate may sit and still count, as a fraction
     * of the pitch.
     *
     * Was 0.16, which at a 37 px pitch admits a peak nearly 6 px out. That
     * mattered because residual perspective — the part an affine correction
     * cannot remove — SPLITS an outer ring into two shoulder peaks either
     * side of where it should be. At 0.16 both shoulders were admitted and
     * the least-squares refit was dragged between them; measured on a real
     * target at 25 degrees this turned a true 37 px pitch into 66.5.
     */
    private const val INLIER_TOLERANCE = 0.10

    /**
     * Weight of the aiming-mark constraint in the ladder score.
     *
     * An independent check that costs nothing and needs no knowledge of which
     * face this is: on every face in the catalogue the black covers a whole
     * number of rings, so the mark's own radius must land ON a rung of
     * whatever ladder is correct. A ladder that explains the ring peaks but
     * puts the aiming mark half way between two rungs is explaining the wrong
     * peaks. On a real target at 20 degrees this recovered a 30.6 px pitch
     * where the unconstrained search returned 32.8 from five rings — and it
     * found seven.
     */
    private const val MARK_RUNG_WEIGHT = 1.5

    /**
     * How far the aiming mark may sit from a rung before confidence starts to
     * fall, and where it bottoms out. Fractions of a ring pitch.
     *
     * A plateau rather than a ramp from zero, because locating the edge of
     * the mark to better than a pixel or two is not realistic and a fixed
     * fraction is tight at small pitches: at a 22 px pitch, 0.13 of a ring is
     * under 3 px, so a 2 px offset consumed almost the whole allowance.
     * Measured on real targets, fits that were accurate missed by 0.02 to
     * 0.11 and fits that were badly wrong missed by 0.30 or more, so the two
     * are separated comfortably by a plateau to 0.10 and a fall to 0.30.
     */
    const val MARK_RUNG_TOLERANCE = 0.10
    private const val MARK_RUNG_LIMIT = 0.30

    /**
     * Confidence is multiplied by no less than this when the aiming mark
     * misses a rung.
     *
     * It is a floor rather than zero because the premise is not universal,
     * and that was checked rather than assumed. Across the catalogue the
     * black edge lands exactly on a ring boundary on ten of the twelve faces
     * that have both a black and an even pitch — and on the ISSF 50 m Rifle
     * face, and the German 50 m Kleinkaliber face that copies it, it sits
     * 0.375 of a ring away by design. Rejecting off-rung ladders outright
     * would have made those two faces unscoreable.
     */
    private const val MARK_AGREEMENT_FLOOR = 0.45

    /**
     * Permitted range of aiming-mark radius divided by ring pitch.
     *
     * This is the constraint that actually catches a ladder at half or twice
     * the true pitch, and unlike the rung test it holds for every face in the
     * catalogue: measured across all of them the ratio spans 3.00 to 7.03.
     * The bounds below carry roughly a 20 per cent margin on each side. A
     * half-pitch ladder doubles the ratio and a double-pitch ladder halves
     * it, so either lands outside on any face whose true ratio is not close
     * to the middle of the range.
     *
     * A custom face with an unusual black could fall outside and be refused.
     * That is the intended direction of failure: no fit, rather than a
     * confident pitch that is out by a factor of two.
     */
    private const val MIN_MARK_RATIO = 2.4
    private const val MAX_MARK_RATIO = 8.6

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
        // The mark's semi-MAJOR axis is its radius in corrected coordinates:
        // the correction stretches the minor axis up to the major and leaves
        // the major alone, so this is the right number whether or not a
        // correction was applied.
        val markRadiusPx = choice?.model?.semiMajorPx ?: 0.0
        // Log the uncorrected fit alongside the corrected one. The two should
        // agree on pitch to within the overall scale change of the warp, and
        // when they do not, the shared field log is the only place that is
        // visible. Cheap, and it costs one extra fit per registration rather
        // than per frame.
        if (corrected != null && compareWithUncorrected) {
            val plain = fitOn(frame, seedX, seedY, markRadiusPx)
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
            if (corrected != null) -1.0 else seedY,
            markRadiusPx
        ) ?: return null
        return raw.copy(
            shape = choice,
            correction = if (corrected != null) choice?.correction else null,
            correctedFrame = corrected
        )
    }

    /** The ring fit with no shape correction, for diagnostics and tests. */
    fun findWithoutShapeCorrection(frame: LumaFrame): RingFit? = fitOn(frame, -1.0, -1.0, 0.0)

    private fun fitOn(frame: LumaFrame, seedX: Double, seedY: Double, markRadiusPx: Double): RingFit? {
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

        val rh = radialHistogram(small, w, h, cx, cy) ?: return null
        val peaks = pooledCandidates(rh)
        if (peaks.size < MIN_RINGS) {
            Logger.i("RingFinder", "only ${peaks.size} ring candidates; not enough to fit")
            return null
        }
        val fit = fitLadder(peaks, if (markRadiusPx > 0) markRadiusPx / step else 0.0) ?: run {
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
    fun identify(
        fit: RingFit,
        blackRadiusPx: Double,
        candidates: List<TargetFace>,
        sessionDistanceM: Double = 0.0,
        stickyFaceId: String? = null
    ): List<FaceMatch> {
        if (blackRadiusPx <= 0.0) return emptyList()

        // THE RATIO ALONE CANNOT SEPARATE THE CATALOGUE, at any precision.
        // Black radius over ring pitch is 4.00 for ISSF 25/50 m Precision
        // Pistol, 4.00 for the German 100 m face and 4.01 for the NRA A-23/5;
        // 6.10 for 10 m Air Rifle against 6.00 for 300 m Rifle. Those are not
        // measurement problems, they are the same shape at different sizes.
        //
        // Distance separates every one of those collisions — 25 m against
        // 100 m against 50 yd; 10 m against 300 m — and the session already
        // knows its distance from the rule set. Filtering by it first turns
        // an impossible discrimination into an easy one. Measured before
        // this, the identified face changed up to four times across six tilt
        // angles of the SAME card.
        //
        // The window is generous, and falls back to the whole catalogue when
        // nothing survives, because a shooter using a face at a distance it
        // was not drawn for should get a worse answer, not no answer.
        val byDistance = if (sessionDistanceM > 0.0) {
            candidates.filter {
                val d = it.nominalDistanceM
                d <= 0.0 || (d >= sessionDistanceM * DISTANCE_WINDOW_LO &&
                             d <= sessionDistanceM * DISTANCE_WINDOW_HI)
            }
        } else candidates
        val pool = if (byDistance.isNotEmpty()) byDistance else candidates

        val ranked = pool.mapNotNull { face ->
            val pitchMm = face.ringPitchMm ?: return@mapNotNull null
            if (pitchMm <= 0.0 || face.blackDiameterMm <= 0.0) return@mapNotNull null
            val mmPerPx = pitchMm / fit.pitchPx
            val predictedBlackPx = (face.blackDiameterMm / 2.0) / mmPerPx
            FaceMatch(face, abs(predictedBlackPx - blackRadiusPx) / blackRadiusPx, mmPerPx)
        }.sortedBy { it.relativeError }

        // HYSTERESIS. The measured ratio rests on the fitted pitch, which
        // drifts as a card tilts, so a face already in use should not be
        // displaced by a rival that is merely a shade closer this frame —
        // that is what made the answer flap between frames of one target.
        // A challenger has to be clearly better, not marginally.
        val best = ranked.firstOrNull() ?: return ranked
        val sticky = ranked.firstOrNull { it.face.id == stickyFaceId }
        if (sticky != null && sticky !== best &&
            sticky.relativeError <= best.relativeError + STICKY_MARGIN
        ) {
            Logger.i(
                "RingFinder",
                ("keeping %s (%.1f%% off) rather than switching to %s (%.1f%%): the difference " +
                    "is inside the margin the fitted pitch itself moves by")
                    .format(sticky.face.name, sticky.relativeError * 100,
                            best.face.name, best.relativeError * 100)
            )
            return listOf(sticky) + ranked.filter { it !== sticky }
        }
        return ranked
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

    private class RadialHistogram(val hist: Array<IntArray>, val count: IntArray, val maxR: Int)

    private fun radialHistogram(img: IntArray, w: Int, h: Int, cx: Double, cy: Double): RadialHistogram? {
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
        return RadialHistogram(hist, count, maxR)
    }

    private fun percentileProfile(rh: RadialHistogram, q: Double): IntArray {
        val out = IntArray(rh.maxR + 1) { 255 }
        for (r in 0..rh.maxR) {
            val total = rh.count[r]
            if (total == 0) continue
            val target = (total * q).toInt().coerceAtLeast(1)
            var acc = 0
            for (level in 0 until 256) {
                acc += rh.hist[r][level]
                if (acc >= target) { out[r] = level; break }
            }
        }
        return out
    }

    /**
     * Ring radii pooled over every percentile.
     *
     * Pooling rather than picking: which percentile shows a given ring
     * depends on whether it is printed dark on paper or white inside the
     * aiming mark, and one target has both. A ring seen at two percentiles
     * appears twice at nearly the same radius and is collapsed by
     * [mergeClose], which already had to do that for the two edges of a
     * single printed line.
     */
    private fun pooledCandidates(rh: RadialHistogram): List<Double> {
        val perProfile = ArrayList<Double>()
        for (q in PROFILE_PERCENTILES) {
            // mergeClose FIRST, per profile. Its threshold is a fraction of
            // the median gap between peaks, which is calibrated for the peaks
            // of one profile. Pooling before merging would fill that gap
            // distribution with near-duplicates of the same ring seen at two
            // percentiles, drag the median to about a pixel, and leave the
            // threshold too small to merge anything at all.
            perProfile += mergeClose(ringCandidates(percentileProfile(rh, q)))
        }
        // Then collapse only what is unambiguously the same ring. Two
        // percentiles locate one printed line within a pixel or two of each
        // other; DUPLICATE_LIMIT is well under any real ring spacing, so this
        // cannot merge two genuine neighbouring rings.
        val sorted = perProfile.sorted()
        if (sorted.isEmpty()) return sorted
        val out = ArrayList<Double>()
        var group = mutableListOf(sorted[0])
        for (r in sorted.drop(1)) {
            if (r - group.last() <= DUPLICATE_LIMIT) group.add(r)
            else { out.add(group.average()); group = mutableListOf(r) }
        }
        out.add(group.average())
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

    private fun fitLadder(radii: List<Double>, markRadius: Double): Ladder? {
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
                    var score = slots.size - (span - slots.size) + p * 0.02
                    // The aiming mark REJECTS ladders, it does not merely
                    // nudge them.
                    //
                    // As a bonus this was too weak to matter. A ladder at half
                    // the true pitch fits every real ring plus the second edge
                    // of every printed line, so it carries roughly twice the
                    // rungs and wins the inlier count by about three points —
                    // far more than any sane bonus could offset. Measured on a
                    // real card the search returned 18.6 px where the mark
                    // implied 37. The p * 0.02 preference for coarser pitches
                    // was put there for exactly this failure and is not
                    // remotely large enough.
                    //
                    // As a filter it is decisive and principled: the outer
                    // edge of the black IS a printed ring boundary on every
                    // face in the catalogue, so a ladder that cannot place it
                    // on a rung is not describing this target's rings. Ladders
                    // are discarded rather than down-weighted, and if that
                    // leaves none the app reports no fit — which is the right
                    // outcome, because a confident wrong pitch mis-scores
                    // every shot on the card.
                    if (markRadius > 0.0) {
                        // The anchor already defines the line: this ladder
                        // passes through radii[i] with spacing p, so r0 is
                        // radii[i] and no fit is needed to test it.
                        //
                        // This used to call leastSquares here, inside the
                        // innermost loop of an O(n^3) search, each call
                        // sorting a map and allocating. With candidates pooled
                        // from three percentiles that dominated registration —
                        // it was the main reason a frame took some 20 seconds
                        // in the measurement harness. The least-squares fit
                        // now runs once, on the winner.
                        val ratio = markRadius / p
                        if (ratio < MIN_MARK_RATIO || ratio > MAX_MARK_RATIO) continue
                        val k = (markRadius - radii[i]) / p
                        score += MARK_RUNG_WEIGHT * (0.5 - abs(k - k.roundToInt())) * 2.0
                    }
                    if (score > bestScore) { bestScore = score; best = slots }
                }
            }
        }
        val slots = best ?: return null

        val (pitch, r0) = leastSquares(slots) ?: return null
        if (pitch <= 0.0) return null
        val ks = slots.keys.sorted()
        val residual = ks.map { abs(slots[it]!! - (r0 + pitch * it)) }.average()

        val tightness = (1.0 - residual / pitch * 3.0).coerceIn(0.0, 1.0)
        val plenty = min(1.0, slots.size / 6.0)

        // How far the aiming mark ends up from a rung. This is the single
        // best predictor of a wrong pitch that this code has: across real
        // targets at tilts from 0 to 25 degrees it flagged every fit that was
        // more than 6 per cent out, and cleared every fit that was accurate.
        var agreement = 1.0
        if (markRadius > 0.0) {
            val k = (markRadius - r0) / pitch
            val miss = abs(k - k.roundToInt())
            agreement = when {
                miss <= MARK_RUNG_TOLERANCE -> 1.0
                else -> (1.0 - (miss - MARK_RUNG_TOLERANCE) / (MARK_RUNG_LIMIT - MARK_RUNG_TOLERANCE))
                    .coerceIn(0.0, 1.0)
                    .coerceAtLeast(MARK_AGREEMENT_FLOOR)
            }
            if (miss > MARK_RUNG_LIMIT) {
                Logger.w(
                    "RingFinder",
                    ("the aiming mark sits %.2f of a ring from the nearest rung of the fitted " +
                        "ladder (limit %.2f) — the pitch of %.2f px does not explain it, so the " +
                        "scale is not trustworthy").format(miss, MARK_RUNG_LIMIT, pitch)
                )
            }
        }
        return Ladder(pitch, ks.map { slots[it]!! }, residual, tightness * plenty * agreement)
    }

    /** Fits r_k = r0 + k*pitch by least squares. Returns pitch to r0. */
    private fun leastSquares(slots: Map<Int, Double>): Pair<Double, Double>? {
        val ks = slots.keys.sorted()
        if (ks.size < 2) return null
        val kBar = ks.average()
        val rBar = ks.map { slots[it]!! }.average()
        var num = 0.0; var den = 0.0
        for (k in ks) { num += (k - kBar) * (slots[k]!! - rBar); den += (k - kBar) * (k - kBar) }
        if (den == 0.0) return null
        val pitch = num / den
        return pitch to (rBar - pitch * kBar)
    }
}
