package com.rfsat.sts.detect

import com.rfsat.sts.log.Logger
import java.util.Random
import kotlin.math.max
import kotlin.math.sqrt

/**
 * ============================================================================
 *  CHOOSING BETWEEN A CIRCLE AND AN ELLIPSE
 * ============================================================================
 *
 * An ellipse has two more free parameters than a circle, so on the points it
 * was fitted to it can never do worse. Comparing the two on their own
 * residuals therefore always picks the ellipse, including on a target
 * photographed perfectly square-on where the extra freedom is fitting nothing
 * but noise — and a spurious ellipse is not harmless. Applying a 3 per cent
 * correction that is not there skews every radius by 3 per cent, which near
 * the outer rings of a 10 m air rifle face is about half a ring.
 *
 * The question that can actually be answered is whether the ellipse predicts
 * points it has NOT SEEN better than a circle does. So: fit both on 70 per
 * cent of the outline, score both on the untouched 30, repeat, average.
 * Overfitting shows up as a NEGATIVE gain, which is the signal that the
 * simpler model should win.
 *
 * Two details in this that were bugs first. The trimming happens INSIDE each
 * training fold — trimming the whole set first and then cross-validating lets
 * the cleaning leak into the comparison, which in an early measurement turned
 * a real 6.5 per cent gain into an apparent 21. And the folds are drawn from
 * a fixed seed, so re-opening a photograph cannot produce a different score.
 *
 * MEASURED BEHAVIOUR, on four real targets warped by tilts chosen in advance:
 *
 *   - Where the ellipse was right, the gain ran 50 to 98 per cent and the
 *     recovered axis ratio was within 0.02 to 1.5 per cent of the true one.
 *   - On a square-on target the gain was -57 per cent, and the circle won.
 *   - On a target whose aiming mark was fused with bullet holes, the ellipse
 *     fit was badly wrong (axis ratio out by 20 to 26 per cent) and the gain
 *     went NEGATIVE, so it was rejected. That case is the important one: the
 *     selector caught a failure that no amount of cleaning had managed to
 *     prevent, without needing to know what had gone wrong.
 */
object RingShapeSelector {

    /** Held-out gain the ellipse must show before it is used, per cent. */
    const val MIN_GAIN_PERCENT = 25.0

    /** Below this angular coverage the fit is not trustworthy at all: at 0.39
     *  the axis ratio was out by 4.5 per cent and at 0.14 by 113. */
    const val MIN_COVERAGE = 0.45

    /** Ellipticity below this is not worth correcting even if it is real —
     *  and at this level it usually is not. */
    const val MIN_AXIS_RATIO = 1.015

    /** Beyond this the view is too oblique for the far side of the face to
     *  carry usable resolution; 1.4 is about 44 degrees of tilt. */
    const val MAX_AXIS_RATIO = 1.40

    private const val MIN_POINTS = 60
    private const val FOLDS = 12
    private const val TRAIN_FRACTION = 0.7

    /** Fraction of test-fold residuals kept when scoring, so that a few
     *  outliers surviving into the test split cannot swamp the comparison.
     *  Applied identically to both models. */
    private const val SCORE_QUANTILE = 0.8

    private const val SEED = 0x5715L

    fun choose(rawPoints: List<EdgePoint>): RingShapeChoice? {
        if (rawPoints.size < MIN_POINTS) {
            Logger.i("RingShape", "only ${rawPoints.size} outline points; not fitting a shape")
            return null
        }
        val points = EllipseFitter.radialPreClean(rawPoints)
        if (points.size < MIN_POINTS) {
            Logger.i("RingShape", "pre-clean left ${points.size} points; not fitting a shape")
            return null
        }

        val (ellipse, kept) = EllipseFitter.fitTrimmed(points)
        val (circle, _) = EllipseFitter.fitTrimmed(points, EllipseFitter::fitCircle)
        if (circle == null) return null
        if (ellipse == null) {
            return RingShapeChoice(circle, false, 0.0, 1.0, points.size, "no ellipse could be fitted")
        }

        val coverage = EllipseFitter.angularCoverage(kept, ellipse.centreXPx, ellipse.centreYPx)
        val gain = crossValidatedGain(points)

        val reason: String
        val useEllipse: Boolean
        when {
            coverage < MIN_COVERAGE -> {
                useEllipse = false
                reason = "outline covers only %.0f%% of the circumference (needs %.0f%%)"
                    .format(coverage * 100, MIN_COVERAGE * 100)
            }
            ellipse.axisRatio < MIN_AXIS_RATIO -> {
                useEllipse = false
                reason = "axis ratio %.4f is within the noise of a circle".format(ellipse.axisRatio)
            }
            ellipse.axisRatio > MAX_AXIS_RATIO -> {
                useEllipse = false
                reason = ("axis ratio %.3f implies a view too oblique to score reliably; " +
                    "re-shoot the photograph squarer or register from the card corners")
                    .format(ellipse.axisRatio)
            }
            gain < MIN_GAIN_PERCENT -> {
                useEllipse = false
                reason = ("the ellipse predicts unseen outline points only %.1f%% better than a " +
                    "circle, so its extra freedom is fitting noise").format(gain)
            }
            else -> {
                useEllipse = true
                reason = ("axis ratio %.4f at %.0f deg, %.0f%% better than a circle on held-out points")
                    .format(ellipse.axisRatio, ellipse.orientationDeg, gain)
            }
        }
        val choice = RingShapeChoice(
            model = if (useEllipse) ellipse else circle,
            usedEllipse = useEllipse,
            gainPercent = gain,
            coverage = coverage,
            pointCount = points.size,
            reason = reason
        )
        Logger.i(
            "RingShape",
            "%s from %d outline points: %s".format(
                if (useEllipse) "ELLIPSE" else "circle", points.size, reason
            )
        )
        return choice
    }

    /**
     * Percentage by which the ellipse beats the circle on held-out points.
     * Negative means the circle generalised better and should be used.
     */
    fun crossValidatedGain(points: List<EdgePoint>): Double {
        val rng = Random(SEED)
        val n = points.size
        val trainSize = (n * TRAIN_FRACTION).toInt()
        if (trainSize < 20 || n - trainSize < 10) return 0.0
        var sumE = 0.0
        var sumC = 0.0
        var folds = 0
        val idx = IntArray(n) { it }
        repeat(FOLDS) {
            for (i in n - 1 downTo 1) {
                val j = rng.nextInt(i + 1)
                val t = idx[i]; idx[i] = idx[j]; idx[j] = t
            }
            val train = ArrayList<EdgePoint>(trainSize)
            val test = ArrayList<EdgePoint>(n - trainSize)
            for (k in 0 until n) (if (k < trainSize) train else test).add(points[idx[k]])
            val (fe, _) = EllipseFitter.fitTrimmed(train)
            val (fc, _) = EllipseFitter.fitTrimmed(train, EllipseFitter::fitCircle)
            if (fe == null || fc == null) return@repeat
            // DISTANCES, not squared distances. Squaring inflates the gain
            // non-linearly, and the 25 per cent threshold was calibrated
            // against distances.
            val de = test.map { sqrt(EllipseFitter.pointResidualSq(it, fe)) }.sorted()
            val dc = test.map { sqrt(EllipseFitter.pointResidualSq(it, fc)) }.sorted()
            val take = max(1, (de.size * SCORE_QUANTILE).toInt())
            sumE += de.take(take).average()
            sumC += dc.take(take).average()
            folds++
        }
        if (folds == 0) return 0.0
        val e = sumE / folds
        val c = sumC / folds
        if (c <= 1e-12) return 0.0
        return (c - e) / c * 100.0
    }
}
