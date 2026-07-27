package com.rfsat.sts.detect

import kotlin.math.cos
import kotlin.math.sin

/**
 * ============================================================================
 *  TILT AND ROTATION ON TOP OF THE BOX
 * ============================================================================
 *
 * A square bounding box carries four degrees of freedom — two of position,
 * one of size, and the constraint that it is square. A plane-to-plane
 * homography has eight. The five missing ones are what a target photographed
 * from anywhere except dead square-on needs, and until now the only way to
 * supply them was to tap four corners.
 *
 * This adds three of them, as the three controls a phone camera app already
 * teaches everybody: an in-plane ROTATION, and a TILT about each of the two
 * image axes. Box plus these is seven degrees of freedom. The eighth is
 * shear, and it is genuinely not needed here: pixels are square and the
 * target is flat, so nothing in the optical path can shear a circle into
 * anything but an ellipse.
 *
 * THE TILT MODEL, AND WHY IT IS NOT JUST A PERSPECTIVE TERM. Tilting a plane
 * away from the camera does two things at once, and modelling only one of
 * them looks convincing and scores wrong:
 *
 *   FORESHORTENING — the whole face shrinks along the tilt direction by
 *   cos(alpha). This is the part that turns the aiming mark from a circle
 *   into an ellipse, and it is the part a bare projective term does NOT
 *   produce. A transform with only a perspective term makes the shape wider,
 *   never narrower, whatever sign you give it.
 *
 *   KEYSTONING — the near edge is magnified relative to the far one, because
 *   it is closer. This is the projective term proper, and its strength
 *   depends on how close the camera is relative to the size of the target.
 *
 * The keystone strength is fixed at [PERSPECTIVE_STRENGTH] rather than being
 * a fourth slider. It is the ratio of the target's half-size to the camera
 * distance, and at any normal framing — a card filling a decent part of the
 * frame — it sits near a quarter. Exposing it would mean a control whose
 * effect almost nobody could judge by eye, to buy a correction that is second
 * order next to the foreshortening. The two tilt sliders carry the first
 * order term, which is the one that matters.
 *
 * THE IDENTITY TRANSFORM REPRODUCES THE PLAIN BOX EXACTLY, corner for
 * corner. That is deliberate and is what lets this be added without changing
 * the behaviour or the tests of every square-on registration that already
 * worked.
 */
data class BoxTransform(
    /** In-plane rotation, degrees, positive anticlockwise on the target. */
    val rotationDeg: Double = 0.0,
    /** Tilt about the image's VERTICAL axis: foreshortens horizontally, as
     *  when the target is turned away to the left or right. */
    val tiltXDeg: Double = 0.0,
    /** Tilt about the image's HORIZONTAL axis: foreshortens vertically, as
     *  when the target leans back or the camera looks up at it. */
    val tiltYDeg: Double = 0.0
) {

    val isIdentity: Boolean
        get() = rotationDeg == 0.0 && tiltXDeg == 0.0 && tiltYDeg == 0.0

    /**
     * Maps a point on the reference square — normalised to -1..+1 with +y UP,
     * the way the target plane runs — to source pixels, given where the box
     * is and how big it is.
     */
    fun mapNorm(nx: Double, ny: Double, cx: Double, cy: Double, half: Double): Pair<Double, Double> {
        val ax = Math.toRadians(tiltXDeg.coerceIn(-MAX_TILT_DEG, MAX_TILT_DEG))
        val ay = Math.toRadians(tiltYDeg.coerceIn(-MAX_TILT_DEG, MAX_TILT_DEG))

        // 1. foreshorten along each tilted axis
        val fx = nx * cos(ax)
        val fy = ny * cos(ay)

        // 2. keystone: the near side is magnified. Floored well above zero,
        //    because a w that reaches zero is a point at infinity and would
        //    throw the corner off the screen rather than merely distort it.
        val w = (1.0 + PERSPECTIVE_STRENGTH * (sin(ax) * nx + sin(ay) * ny))
            .coerceAtLeast(MIN_W)
        val px = fx / w
        val py = fy / w

        // 3. rotate in the image plane, which is where a rotation about the
        //    optical axis acts, so it comes last
        val th = Math.toRadians(rotationDeg)
        val rx = px * cos(th) - py * sin(th)
        val ry = px * sin(th) + py * cos(th)

        // 4. scale, flip y (millimetres run up, pixel rows run down), place
        return (cx + half * rx) to (cy - half * ry)
    }

    /**
     * The reference square's four corners in source pixels, in the order
     * top-left, top-right, bottom-right, bottom-left AS SEEN ON THE TARGET.
     * That order has to match the millimetre corners it will be paired with
     * in [TargetRegistration.fromBoundingBox]; a transposition here would
     * mirror every score about a diagonal.
     */
    fun cornersFor(cx: Double, cy: Double, half: Double): List<Pair<Double, Double>> = listOf(
        mapNorm(-1.0, 1.0, cx, cy, half),
        mapNorm(1.0, 1.0, cx, cy, half),
        mapNorm(1.0, -1.0, cx, cy, half),
        mapNorm(-1.0, -1.0, cx, cy, half)
    )

    /** The reference circle as a polyline, for drawing the preview outline.
     *  This is the shape the user actually matches to the target, because the
     *  thing being measured is round. */
    fun circleFor(
        cx: Double, cy: Double, half: Double, segments: Int = 72
    ): List<Pair<Double, Double>> = (0 until segments).map { i ->
        val a = 2.0 * Math.PI * i / segments
        mapNorm(cos(a), sin(a), cx, cy, half)
    }

    fun withRotation(deg: Double) = copy(rotationDeg = deg.coerceIn(-MAX_ROTATION_DEG, MAX_ROTATION_DEG))
    fun withTiltX(deg: Double) = copy(tiltXDeg = deg.coerceIn(-MAX_TILT_DEG, MAX_TILT_DEG))
    fun withTiltY(deg: Double) = copy(tiltYDeg = deg.coerceIn(-MAX_TILT_DEG, MAX_TILT_DEG))

    fun summary(): String =
        if (isIdentity) "square-on, no correction"
        else "rotation %.0f°, tilt %.0f° / %.0f°".format(rotationDeg, tiltXDeg, tiltYDeg)

    companion object {
        val NONE = BoxTransform()

        /** Past 45 degrees of in-plane rotation it is quicker to rotate the
         *  photograph than to chase the slider. */
        const val MAX_ROTATION_DEG = 45.0

        /** Beyond about 40 degrees of tilt the far edge of the face is
         *  resolved so much worse than the near one that scoring it is
         *  optimistic whatever the geometry says. */
        const val MAX_TILT_DEG = 40.0

        /** Target half-size over camera distance. See the note above on why
         *  this is a constant and not a fourth control. */
        const val PERSPECTIVE_STRENGTH = 0.25

        private const val MIN_W = 0.2
    }
}
