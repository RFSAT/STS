package com.rfsat.sts.detect

import com.rfsat.sts.log.Logger
import com.rfsat.sts.targets.TargetFace

/**
 * ============================================================================
 *  REGISTRATION AND RECTIFICATION
 * ============================================================================
 *
 * Registration ties image pixels to target-plane millimetres. Everything
 * downstream — hole finding, scoring, the group statistics, the plot — works
 * in millimetres, and this class is the only place that knows about pixels.
 *
 * THE DESIGN DECISION THAT MATTERS. Detection could run in the raw image and
 * convert each hit afterwards. It does not. Instead the face is RECTIFIED
 * once into a fronto-parallel image at a fixed millimetres-per-pixel, and the
 * detectors run there. Three things fall out of that:
 *
 *   1. A pellet hole is the same size in pixels everywhere on the face. In
 *      the raw image, under perspective, it is not — so a single-scale blob
 *      detector is looking for the wrong thing over most of the target.
 *   2. Comparing a "before" frame against an "after" frame becomes a
 *      pixel-for-pixel subtraction, even when the phone was nudged between
 *      them, because both are rectified into the same canonical grid.
 *   3. The scoring geometry is circular in the target plane and an ellipse in
 *      the image. Rectifying first means the ring test stays a distance
 *      comparison rather than a conic one.
 *
 * The cost is one resampling pass, which is cheap next to the detection
 * itself, and a small loss of resolution where the face is foreshortened —
 * which is a real limit, and why [warnings] complains when the view is too
 * oblique to trust.
 */
class TargetRegistration private constructor(
    val face: TargetFace,
    val homography: Homography,
    /** Rectified-image resolution: how many millimetres one output pixel
     *  spans. Smaller is finer and slower. */
    val mmPerPx: Double,
    /** Bounds of the rectified area in target-plane mm. */
    val uMinMm: Double,
    val uMaxMm: Double,
    val vMinMm: Double,
    val vMaxMm: Double,
    val warnings: List<String>
) {

    val rectWidth: Int = Math.ceil((uMaxMm - uMinMm) / mmPerPx).toInt().coerceAtLeast(1)
    val rectHeight: Int = Math.ceil((vMaxMm - vMinMm) / mmPerPx).toInt().coerceAtLeast(1)

    /** Rectified pixel -> target-plane mm. Note the y flip: millimetres run
     *  upward, image rows run downward. */
    fun rectToMm(i: Int, j: Int): Pair<Double, Double> =
        (uMinMm + (i + 0.5) * mmPerPx) to (vMaxMm - (j + 0.5) * mmPerPx)

    fun mmToRect(u: Double, v: Double): Pair<Double, Double> =
        ((u - uMinMm) / mmPerPx - 0.5) to ((vMaxMm - v) / mmPerPx - 0.5)

    /**
     * Resamples [source] into the canonical rectified grid.
     *
     * Bilinear, not nearest-neighbour. At 10 m an air-rifle hole is 4.5 mm
     * across, and at a sane rectified resolution that is only a handful of
     * pixels; nearest-neighbour resampling of a 3 px feature loses enough of
     * its contrast to push it under the detector's threshold, intermittently,
     * which is the worst possible failure mode — it looks like the shooter
     * missed.
     *
     * Pixels whose source coordinate falls outside the frame are filled with
     * [OUT_OF_FRAME], a value the detectors treat as "no data" rather than as
     * a very dark pixel.
     */
    fun rectify(source: LumaFrame): LumaFrame {
        val out = ByteArray(rectWidth * rectHeight)
        var o = 0
        for (j in 0 until rectHeight) {
            val v = vMaxMm - (j + 0.5) * mmPerPx
            for (i in 0 until rectWidth) {
                val u = uMinMm + (i + 0.5) * mmPerPx
                val (x, y) = homography.mmToPx(u, v)
                val s = if (x.isNaN()) Double.NaN else source.sampleBilinear(x, y)
                out[o++] = if (s.isNaN()) OUT_OF_FRAME else s.toInt().coerceIn(0, 255).toByte()
            }
        }
        return LumaFrame(rectWidth, rectHeight, out)
    }

    companion object {

        /** Fill value for rectified pixels with no source data. Chosen at 1
         *  rather than 0 so that "no data" is distinguishable from a genuine
         *  black pixel, which on an aiming mark is a real and common value. */
        const val OUT_OF_FRAME: Byte = 1

        /** Rectified resolution as a fraction of the scoring gauge: the
         *  smallest feature that must be resolved is the hole itself, and
         *  eight pixels across it is comfortably enough for a sub-pixel
         *  centroid while keeping the rectified image small. */
        private const val PX_PER_GAUGE = 8.0

        /** Beyond this the view is too oblique for the far edge to carry
         *  usable resolution. Not an error — a warning, because a 3:1 scale
         *  ratio across the face is still perfectly scoreable near the
         *  centre, which is where the shots are. */
        private const val MAX_SCALE_RATIO = 3.0

        /**
         * Builds a registration from four tapped image points.
         *
         * [imageCorners] must be the CARD corners in the order top-left,
         * top-right, bottom-right, bottom-left as the user sees the target —
         * the registration UI enforces that order by prompting for one corner
         * at a time.
         *
         * [gaugeDiameterMm] comes from the rule set and sets the rectified
         * resolution, so a 4.5 mm air pellet and a 7.62 mm centrefire bullet
         * both end up about eight pixels across.
         */
        fun fromCardCorners(
            face: TargetFace,
            imageCorners: List<Pair<Double, Double>>,
            gaugeDiameterMm: Double,
            marginMm: Double = 0.0
        ): TargetRegistration? {
            if (imageCorners.size != 4) return null

            // Card corners in target-plane mm, remembering that the card's
            // centre is not necessarily the scoring centre.
            val cx = face.cardCentreOffsetXMm
            val cy = face.cardCentreOffsetYMm
            val hw = face.faceWidthMm / 2.0
            val hh = face.faceHeightMm / 2.0
            val cardCorners = listOf(
                (cx - hw) to (cy + hh),  // top-left
                (cx + hw) to (cy + hh),  // top-right
                (cx + hw) to (cy - hh),  // bottom-right
                (cx - hw) to (cy - hh)   // bottom-left
            )

            val h = Homography.fromCorrespondences(cardCorners, imageCorners) ?: run {
                Logger.w("TargetRegistration", "Homography is singular — the four taps are collinear or coincident")
                return null
            }

            val warnings = mutableListOf<String>()

            // Scale sampled at the four corners: the ratio between the
            // largest and smallest tells us how oblique the view is.
            val scales = cardCorners.map { (u, v) -> h.pxPerMmAt(u, v) }.filter { it > 0.0 }
            if (scales.isEmpty()) {
                Logger.w("TargetRegistration", "Degenerate transform: zero scale everywhere")
                return null
            }
            val sMin = scales.min()
            val sMax = scales.max()
            if (sMax / sMin > MAX_SCALE_RATIO) {
                // NOTE the parentheses. Without them .format binds to the
                // second literal only, and the %.1f in the first one is
                // printed verbatim — a bug that survives every test that
                // does not read the message.
                warnings += ("The target is viewed at a steep angle (scale varies %.1f\u00d7 across " +
                    "the face). Shots near the far edge are resolved less well; move square-on if " +
                    "you can.").format(sMax / sMin)
            }

            // Rectified resolution, then a cap on the total pixel count.
            // A 2200 mm NRA long-range face at eight pixels per 7.62 mm gauge
            // is 2300 px square, and its integral image is a LongArray of 5.3
            // million entries — 43 MB, on a device that also has a camera
            // pipeline running. The cap coarsens the grid rather than
            // allocating it, and warns, because a slightly coarse score beats
            // an OutOfMemoryError.
            var mmPerPx = (gaugeDiameterMm / PX_PER_GAUGE).coerceAtLeast(MIN_MM_PER_PX)
            val spanU = face.faceWidthMm + 2 * marginMm
            val spanV = face.faceHeightMm + 2 * marginMm
            val pixels = (spanU / mmPerPx) * (spanV / mmPerPx)
            if (pixels > MAX_RECT_PIXELS) {
                val factor = kotlin.math.sqrt(pixels / MAX_RECT_PIXELS)
                mmPerPx *= factor
                warnings += ("This face is large enough that the rectified image would not fit in " +
                    "memory at full resolution, so it is being sampled %.1f\u00d7 coarser. Centroids " +
                    "will be correspondingly less precise.").format(factor)
            }

            // Is the camera actually resolving the hole? sMin px/mm times the
            // gauge gives the hole's diameter in SOURCE pixels at the worst
            // corner. Below about three, no detector can separate a hole from
            // paper texture, and saying so up front is far better than
            // producing a plausible but wrong score.
            val holePxAtWorst = sMin * gaugeDiameterMm
            if (holePxAtWorst < 3.0) {
                warnings += ("A %.1f mm hole spans only %.1f pixels in this view. " +
                    "Move closer, zoom in, or use a longer lens — detection below about 3 pixels " +
                    "is guesswork.").format(gaugeDiameterMm, holePxAtWorst)
            } else if (holePxAtWorst < 6.0) {
                warnings += ("A %.1f mm hole spans about %.1f pixels. Detection will work but the " +
                    "centroid will be coarse; closer is better.").format(gaugeDiameterMm, holePxAtWorst)
            }

            return TargetRegistration(
                face = face,
                homography = h,
                mmPerPx = mmPerPx,
                uMinMm = cx - hw - marginMm,
                uMaxMm = cx + hw + marginMm,
                vMinMm = cy - hh - marginMm,
                vMaxMm = cy + hh + marginMm,
                warnings = warnings
            )
        }

        /**
         * Registration from four points on a known ring: its leftmost,
         * topmost, rightmost and bottommost points as they appear in the
         * image.
         *
         * Useful when the card edges are out of frame or obscured by the
         * frame holding it, which on a busy range is more often than not.
         * Less accurate than the corners for a strongly oblique view — the
         * extreme points of a projected circle are not the projections of the
         * circle's extreme points — so it is offered as the fallback, and the
         * error is second order for the near-square-on views it is meant for.
         */
        fun fromRingExtremes(
            face: TargetFace,
            ringValue: Int,
            imageLeftTopRightBottom: List<Pair<Double, Double>>,
            gaugeDiameterMm: Double
        ): TargetRegistration? {
            if (imageLeftTopRightBottom.size != 4) return null
            val ring = face.rings.firstOrNull { it.value == ringValue } ?: return null
            val r = ring.radiusMm
            val ringPoints = listOf(
                -r to 0.0,   // left
                0.0 to r,    // top
                r to 0.0,    // right
                0.0 to -r    // bottom
            )
            val h = Homography.fromCorrespondences(ringPoints, imageLeftTopRightBottom) ?: return null
            val hw = face.faceWidthMm / 2.0
            val hh = face.faceHeightMm / 2.0
            val cx = face.cardCentreOffsetXMm
            val cy = face.cardCentreOffsetYMm
            val warnings = mutableListOf(
                "Registered from the ${ringValue} ring rather than the card corners. This is exact " +
                    "for a square-on view and approximate for an oblique one; prefer the corners " +
                    "when the card edges are visible."
            )
            return TargetRegistration(
                face = face,
                homography = h,
                mmPerPx = (gaugeDiameterMm / PX_PER_GAUGE).coerceAtLeast(MIN_MM_PER_PX),
                uMinMm = cx - hw, uMaxMm = cx + hw,
                vMinMm = cy - hh, vMaxMm = cy + hh,
                warnings = warnings
            )
        }

        /** Floor on the rectified resolution. Without it a 0.5 mm-gauge face
         *  would ask for a rectified image hundreds of megapixels across. */
        private const val MIN_MM_PER_PX = 0.05

        /** Ceiling on the rectified pixel count. Four megapixels of Long in
         *  the integral image is 32 MB, which is as much as this app should
         *  ask for while CameraX is also running. */
        private const val MAX_RECT_PIXELS = 4_000_000.0
    }
}
