package com.rfsat.sts.detect

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Stretching the PICTURE so the printed rings come out round.
 *
 * WHY THE PICTURE AND NOT THE CIRCLES. The obvious fix for rings that do not
 * sit over the printing is to move or reshape the drawn circles. That leaves
 * an ELLIPTICAL scoring geometry in the pipeline, and everything downstream —
 * the hole size gates, the gauge, the ring radii, the score of a hole that
 * touches a line — is written in terms of one millimetres-per-pixel number.
 * An ellipse gives it two, and every stage then has to know which one applies
 * in which direction. Correcting the image instead means the rings ARE round
 * by the time any of that runs, and the rest of the app can go on believing
 * what it already believes.
 *
 * WHAT IT CANNOT DO. A card photographed off to one side is foreshortened
 * along an axis that is not the picture's own. That is a projective problem,
 * it needs the tilt controls or corner registration, and no amount of
 * horizontal-and-vertical stretching will fix it. So a suggestion is offered
 * ONLY when the measured long axis lies within [AXIS_TOLERANCE_DEG] of
 * horizontal or vertical, which is the case this can actually correct: a lens
 * or a sensor with non-square pixels, a photograph resized unevenly, a
 * screenshot from a display with the wrong aspect.
 *
 * The suggestion is never applied by itself. The ring fit measures a few per
 * cent out of round on a square-on card from segmentation noise alone, and
 * applying that would distort a picture that was already right. Same rule as
 * the tilt estimate, for the same reason.
 */
object AspectCorrection {

    /** How close to horizontal or vertical the long axis must lie before a
     *  stretch can express the distortion at all. */
    const val AXIS_TOLERANCE_DEG = 20.0

    /** Below this the reading is inside its own noise. Measured on
     *  square-on cards, where the fitted family runs 1–2% out of round with
     *  nothing wrong with the photograph. */
    const val NOISE_FLOOR = 0.03

    /** Beyond this the picture is not merely stretched — something else is
     *  wrong, and quietly resampling by half again would hide it. */
    const val MAX_STRETCH = 1.60

    /**
     * @param scaleX multiplier for the image width, 1.0 = unchanged
     * @param scaleY multiplier for the image height
     * @param outOfRoundFraction how far from round the rings measured, for
     *        the message: 0.08 is "8% out of round"
     */
    data class Suggestion(
        val scaleX: Double,
        val scaleY: Double,
        val outOfRoundFraction: Double
    ) {
        val percentX: Double get() = scaleX * 100.0
        val percentY: Double get() = scaleY * 100.0
    }

    /**
     * @param axisRatio minor axis over major, 1.0 = round
     * @param orientationDeg direction of the MAJOR axis, degrees, 0 = along
     *        the image's x axis
     *
     * Returns null when there is nothing worth doing, or when the distortion
     * is of a kind this cannot express.
     *
     * The SHORT axis is stretched rather than the long one shortened: a hole
     * is a handful of pixels across at the scale that matters, and throwing
     * away a fifth of them to make the arithmetic tidier would cost real
     * detections.
     */
    fun suggest(axisRatio: Double, orientationDeg: Double): Suggestion? {
        if (axisRatio <= 0.0 || axisRatio > 1.0) return null
        val outOfRound = 1.0 - axisRatio
        if (outOfRound < NOISE_FLOOR) return null
        if (1.0 / axisRatio > MAX_STRETCH) return null

        // Fold the orientation into 0–180 and ask which axis it is near.
        var a = orientationDeg % 180.0
        if (a < 0) a += 180.0
        val nearHorizontal = a <= AXIS_TOLERANCE_DEG || a >= 180.0 - AXIS_TOLERANCE_DEG
        val nearVertical = abs(a - 90.0) <= AXIS_TOLERANCE_DEG

        return when {
            // Long axis across the picture: the picture is squashed
            // vertically, so the height is stretched back out.
            nearHorizontal -> Suggestion(1.0, 1.0 / axisRatio, outOfRound)
            nearVertical -> Suggestion(1.0 / axisRatio, 1.0, outOfRound)
            else -> null
        }
    }

    /** True when a stretch is worth carrying out at all — used to decide
     *  whether Apply does anything, so pressing it on 100/100 does not
     *  resample the photograph for nothing. */
    fun worthApplying(scaleX: Double, scaleY: Double): Boolean =
        abs(scaleX - 1.0) >= 0.005 || abs(scaleY - 1.0) >= 0.005

    /** Percentages as typed, in the range the app will act on. Returns null
     *  when the text is not a number or is outside it. */
    fun parsePercent(text: String): Double? {
        val v = text.trim().toDoubleOrNull() ?: return null
        val f = v / 100.0
        if (f < 1.0 / MAX_STRETCH || f > MAX_STRETCH) return null
        return f
    }

    /**
     * What the rings would measure AFTER a given stretch — used by the tests
     * to prove the suggestion actually rounds them, rather than trusting the
     * arithmetic to be right by inspection.
     *
     * An ellipse with semi-axes a (at orientation θ) and b, scaled by
     * (sx, sy) along the picture's axes, is still an ellipse; this returns
     * its new axis ratio.
     */
    fun ratioAfter(axisRatio: Double, orientationDeg: Double, sx: Double, sy: Double): Double {
        val th = Math.toRadians(orientationDeg)
        // Points at the ends of each semi-axis, scaled.
        val ax = cos(th) * sx
        val ay = sin(th) * sy
        val bx = -sin(th) * axisRatio * sx
        val by = cos(th) * axisRatio * sy
        val la = Math.hypot(ax, ay)
        val lb = Math.hypot(bx, by)
        // Only valid where the scaled axes stay perpendicular, which is the
        // case this class restricts itself to: θ at 0 or 90 degrees.
        return minOf(la, lb) / maxOf(la, lb)
    }
}
