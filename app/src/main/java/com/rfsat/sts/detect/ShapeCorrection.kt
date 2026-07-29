package com.rfsat.sts.detect

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * The affine map that turns the fitted ellipse back into a circle.
 *
 * Stretching along the MINOR axis by the axis ratio, about the ellipse
 * centre, in the ellipse's own frame. Everything else about the image is left
 * alone, which matters: this is applied before the ring family is fitted, so
 * any distortion it introduced of its own would be measured as pitch and
 * silently become scale.
 *
 * WHY AN AFFINE MAP AND NOT THE FULL HOMOGRAPHY. A circle photographed at an
 * angle projects to an ellipse, and undoing the ellipse removes the
 * foreshortening — but not the perspective SCALE GRADIENT across the face,
 * because that part of the projection is not affine. One ellipse gives four
 * usable numbers and a homography needs eight, so a single ring cannot in
 * principle recover it.
 *
 * That residual was measured rather than assumed, by scoring simulated shots
 * on a target warped by known angles. Mean absolute score error per shot,
 * ISSF 10 m air rifle face:
 *
 *      tilt     circle    this      full homography
 *      10 deg    0.092    0.083     0.056
 *      20 deg    0.201    0.154     0.122
 *      30 deg    0.366    0.216     0.161
 *      40 deg    0.565    0.282     0.220
 *
 * So this recovers roughly half of what a circle throws away at 30 to 40
 * degrees, and the remaining projective term is worth about a further
 * quarter. The full homography is not implemented here for a reason worth
 * recording: recovering it needs the TRUE RADII of two or more rings, which
 * means trusting the face identification. Get the face wrong and the
 * homography absorbs the error as geometry and returns a confident wrong
 * score. This correction needs no knowledge of the face at all.
 */
data class ShapeCorrection(
    val centreXPx: Double,
    val centreYPx: Double,
    val orientationRad: Double,
    /** Major over minor: how much the minor axis is stretched. */
    val stretch: Double
) {

    /** Source pixel -> corrected pixel. */
    fun forward(x: Double, y: Double): Pair<Double, Double> {
        val dx = x - centreXPx
        val dy = y - centreYPx
        val c = cos(orientationRad)
        val s = sin(orientationRad)
        val u = dx * c + dy * s
        val v = (-dx * s + dy * c) * stretch
        return (centreXPx + u * c - v * s) to (centreYPx + u * s + v * c)
    }

    /** Corrected pixel -> source pixel. */
    fun inverse(x: Double, y: Double): Pair<Double, Double> {
        val dx = x - centreXPx
        val dy = y - centreYPx
        val c = cos(orientationRad)
        val s = sin(orientationRad)
        val u = dx * c + dy * s
        val v = (-dx * s + dy * c) / stretch
        return (centreXPx + u * c - v * s) to (centreYPx + u * s + v * c)
    }

    /**
     * Resamples a frame into corrected coordinates.
     *
     * The canvas grows to hold the stretched content — clipping it to the
     * original size would cut off the outer rings on exactly the oblique
     * photographs this exists to rescue, and the outer rings are where the
     * pitch is measured most precisely.
     */
    fun apply(source: LumaFrame, maxPixels: Long = 12_000_000L): CorrectedFrame? {
        if (isIdentity) return null
        var minX = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        for (cx in listOf(0.0, source.width.toDouble())) {
            for (cy in listOf(0.0, source.height.toDouble())) {
                val (fx, fy) = forward(cx, cy)
                if (fx < minX) minX = fx
                if (fx > maxX) maxX = fx
                if (fy < minY) minY = fy
                if (fy > maxY) maxY = fy
            }
        }
        val w = ceil(maxX - minX).toInt()
        val h = ceil(maxY - minY).toInt()
        if (w < 8 || h < 8) return null
        if (w.toLong() * h.toLong() > maxPixels) return null

        val out = ByteArray(w * h)
        var o = 0
        for (j in 0 until h) {
            val cy = minY + j + 0.5
            for (i in 0 until w) {
                val cx = minX + i + 0.5
                val (sx, sy) = inverse(cx, cy)
                val v = source.sampleBilinear(sx, sy)
                out[o++] = if (v.isNaN()) TargetRegistration.OUT_OF_FRAME
                           else v.toInt().coerceIn(0, 255).toByte()
            }
        }
        return CorrectedFrame(LumaFrame(w, h, out), minX, minY, this)
    }

    val isIdentity: Boolean get() = abs(stretch - 1.0) < 1e-9
}

/**
 * A frame resampled into corrected coordinates, together with everything
 * needed to get back to the source.
 *
 * The origin is carried explicitly rather than stored on [ShapeCorrection]
 * because the correction is a value: two callers may share one and copy it,
 * and a canvas origin hidden inside it would silently belong to whichever
 * call ran last.
 */
class CorrectedFrame(
    val frame: LumaFrame,
    val originX: Double,
    val originY: Double,
    val correction: ShapeCorrection
) {
    /** Corrected-canvas pixel -> source pixel. */
    fun toSource(i: Double, j: Double): Pair<Double, Double> =
        correction.inverse(originX + i, originY + j)
}
