package com.rfsat.sts.detect

import kotlin.math.abs

/**
 * Barrel and pincushion distortion, measured from the target itself.
 *
 * WHY IT MATTERS HERE. Everything the app does geometrically — the homography
 * that flattens the card, the ring ladder that sets the scale, the radius each
 * hole is scored at — assumes a PINHOLE camera, in which a straight line stays
 * straight. A short-focus action camera is not one. Near the frame edges a
 * barrel lens pulls the image inward, so a ring that should sit at 40 mm
 * measures short, and the error grows with the square of the distance from
 * the centre. At the far end of a range it is invisible; filling the frame
 * with a card at arm's length it is not.
 *
 * NO CAMERA PUBLISHES THIS. The Tactacam 5.0 specification lists zoom,
 * resolution, autofocus and battery life, and no optics at all — no field of
 * view, let alone a distortion coefficient. Nor is one number right for a
 * camera with 8x zoom: the distortion of a lens changes with its focal
 * length. So it is measured rather than looked up.
 *
 * HOW IT IS MEASURED, WITHOUT A CALIBRATION TARGET. The printed rings are
 * concentric circles at a known EQUAL spacing. Under a pinhole camera their
 * radii in pixels are therefore an arithmetic progression. Under a radial
 * distortion they are not, and the departure is a direct measurement of it.
 * One parameter is fitted — the first-order term, which is the whole of the
 * effect at these apertures — by searching for the value that makes the
 * fitted radii most nearly a straight line against ring number.
 *
 * That is the classical plumb-line calibration, using the shooter's own
 * target as the plumb line. It needs no chequerboard, no calibration
 * session, and nothing the shooter has to be told to do.
 *
 * THE MODEL. r_distorted = r_true * (1 + k * (r_true / norm)^2), with `norm`
 * the distance from the centre to a corner, so k is dimensionless and
 * comparable between resolutions. Negative k is barrel (the common case,
 * and what a wide action camera does); positive is pincushion.
 */
object LensDistortion {

    /** Below this the correction is not worth the resampling: a tenth of a
     *  per cent at the corner, far under the ring fit's own residual. */
    const val NEGLIGIBLE = 0.004

    /** Beyond this the fit is not measuring a lens. A real action camera
     *  runs to about -0.25 at its widest; -0.6 is a fit that has locked on
     *  to something else, and applying it would wreck the picture. */
    const val MAX_K = 0.60

    /** Fewer than this and an arithmetic progression cannot be told from a
     *  distorted one — any three points lie on some curve. */
    const val MIN_RINGS = 4

    /**
     * @param radiiPx fitted ring radii, in pixels, in any order
     * @param normPx  centre-to-corner distance of the frame
     * @return the coefficient, or null when there is nothing to measure or
     *         the answer is not credible
     */
    fun estimate(radiiPx: List<Double>, normPx: Double): Double? {
        if (radiiPx.size < MIN_RINGS || normPx <= 0.0) return null
        val r = radiiPx.sorted()
        if (r.first() <= 0.0) return null

        var best = 0.0
        var bestErr = residual(r, normPx, 0.0)
        // THE FIRST PASS COVERS THE WHOLE ACCEPTED RANGE. It used to sweep
        // ten steps of 0.02 either side of zero — plus or minus 0.2 — so a
        // card shot through a lens at -0.25 could not be reached at all and
        // came back as -0.222: an answer that looked like a measurement and
        // was the edge of the search. A refinement can only sharpen a
        // minimum the coarse pass has already found.
        var step = 0.02
        var centre = 0.0
        var span = MAX_K
        repeat(3) {
            var k = centre - span
            while (k <= centre + span + 1e-12) {
                if (abs(k) <= MAX_K) {
                    val e = residual(r, normPx, k)
                    if (e < bestErr) { bestErr = e; best = k }
                }
                k += step
            }
            centre = best
            span = step
            step /= 10.0
        }
        if (abs(best) < NEGLIGIBLE) return null
        if (abs(best) >= MAX_K) return null
        return best
    }

    /**
     * How far the corrected radii are from an arithmetic progression, as a
     * fraction of the mean spacing.
     *
     * Deliberately NOT a fit through the origin: the ladder may start at ring
     * 6 rather than ring 1, so the progression has an offset as well as a
     * step, and forcing it through zero would read that offset as
     * distortion.
     */
    fun residual(radiiPx: List<Double>, normPx: Double, k: Double): Double {
        val u = radiiPx.map { undistort(it, normPx, k) }
        val n = u.size
        // Least squares of u_i against i.
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
        for (i in 0 until n) {
            val x = i.toDouble()
            sx += x; sy += u[i]; sxx += x * x; sxy += x * u[i]
        }
        val denom = n * sxx - sx * sx
        if (abs(denom) < 1e-9) return Double.MAX_VALUE
        val slope = (n * sxy - sx * sy) / denom
        val intercept = (sy - slope * sx) / n
        if (slope <= 0.0) return Double.MAX_VALUE
        var err = 0.0
        for (i in 0 until n) {
            val d = u[i] - (intercept + slope * i)
            err += d * d
        }
        return Math.sqrt(err / n) / slope
    }

    /**
     * The true radius of a point measured at [rPx].
     *
     * The model gives distorted from true, so this inverts it. NEWTON, not
     * the obvious fixed-point iteration r <- rd / (1 + k f^2): that one
     * converges slowly as |k| grows and was still eight pixels out at the
     * frame corner for k = -0.25, which is an ordinary action camera. It
     * showed up as the estimator reading -0.222 for a card built at -0.25 —
     * an error in the inverse masquerading as a measurement.
     *
     * Solving c*r^3 + r - rd = 0 with c = k / norm^2. The derivative is
     * 3c r^2 + 1, which is positive over the whole range this accepts, so
     * Newton from r = rd converges in three or four steps.
     */
    fun undistort(rPx: Double, normPx: Double, k: Double): Double {
        if (k == 0.0 || normPx <= 0.0) return rPx
        val c = k / (normPx * normPx)
        var r = rPx
        repeat(20) {
            val f = c * r * r * r + r - rPx
            val d = 3.0 * c * r * r + 1.0
            if (abs(d) < 1e-12) return r
            val next = r - f / d
            if (abs(next - r) < 1e-9) { r = next; return r }
            r = next
        }
        return r
    }

    /** True radius to measured radius: what the lens did. Used to build the
     *  sampling table, which asks "for this output pixel, where in the
     *  original was it?". */
    fun distort(rPx: Double, normPx: Double, k: Double): Double {
        if (k == 0.0 || normPx <= 0.0) return rPx
        val f = rPx / normPx
        return rPx * (1.0 + k * f * f)
    }

    /** True when a coefficient is worth the work of applying. */
    fun worthApplying(k: Double): Boolean = abs(k) >= NEGLIGIBLE && abs(k) < MAX_K

    /** As typed by hand, or null when it is not a usable number. */
    fun parse(text: String): Double? {
        val v = text.trim().toDoubleOrNull() ?: return null
        if (abs(v) >= MAX_K) return null
        return v
    }
}

/**
 * Applying a measured coefficient to a picture.
 *
 * Separate from the measurement because this half needs Android's Bitmap and
 * the measurement does not — the estimator is pure arithmetic and is unit
 * tested as such, which is the whole reason the two are apart.
 */
object LensCorrection {

    /**
     * Returns a corrected copy, or the original when there is nothing to do.
     *
     * Bilinear, because nearest-neighbour on a 5 mm hole a few pixels across
     * moves its centre by up to half a pixel in a direction that varies
     * across the frame — which is precisely the error being corrected.
     *
     * The output keeps the input's size. Correcting barrel distortion pulls
     * the corners in, so the corners of the result are empty; they are left
     * black rather than the frame cropped, because cropping would change the
     * scale and every millimetre-per-pixel figure with it.
     */
    fun apply(src: android.graphics.Bitmap, k: Double): android.graphics.Bitmap {
        if (!LensDistortion.worthApplying(k)) return src
        val w = src.width
        val h = src.height
        if (w < 4 || h < 4) return src
        val cx = (w - 1) / 2.0
        val cy = (h - 1) / 2.0
        val norm = Math.hypot(cx, cy)
        val inPx = IntArray(w * h)
        src.getPixels(inPx, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val dy = y - cy
            for (x in 0 until w) {
                val dx = x - cx
                val r = Math.hypot(dx, dy)
                // Where in the ORIGINAL this output pixel came from: the
                // output is the undistorted picture, so the source radius is
                // that radius put back through the lens.
                val rSrc = LensDistortion.distort(r, norm, k)
                val scale = if (r < 1e-9) 1.0 else rSrc / r
                val sx = cx + dx * scale
                val sy = cy + dy * scale
                out[y * w + x] = sample(inPx, w, h, sx, sy)
            }
        }
        return android.graphics.Bitmap.createBitmap(out, w, h, android.graphics.Bitmap.Config.ARGB_8888)
    }

    /**
     * The same correction on a luma frame, which is what the LIVE path
     * carries.
     *
     * A separate routine rather than converting to a bitmap and back: a live
     * frame is one of fifteen a second, and the detector only ever reads the
     * luma plane. The sampling table is cached because the frame size and the
     * coefficient do not change between frames — rebuilding it every time
     * cost more than the correction itself.
     */
    fun apply(frame: LumaFrame, k: Double): LumaFrame {
        if (!LensDistortion.worthApplying(k)) return frame
        val w = frame.width
        val h = frame.height
        if (w < 4 || h < 4) return frame
        val map = tableFor(w, h, k)
        val out = ByteArray(w * h)
        for (i in 0 until w * h) {
            val src = map[i]
            out[i] = if (src < 0) 0 else frame.data[src]
        }
        return LumaFrame(w, h, out)
    }

    private var tableKey: String = ""
    private var table: IntArray = IntArray(0)

    /** Nearest source pixel for each output pixel, or -1 for "outside". */
    @Synchronized
    private fun tableFor(w: Int, h: Int, k: Double): IntArray {
        val key = "$w:$h:${"%.4f".format(k)}"
        if (key == tableKey && table.size == w * h) return table
        val cx = (w - 1) / 2.0
        val cy = (h - 1) / 2.0
        val norm = Math.hypot(cx, cy)
        val map = IntArray(w * h)
        for (y in 0 until h) {
            val dy = y - cy
            for (x in 0 until w) {
                val dx = x - cx
                val r = Math.hypot(dx, dy)
                val scale = if (r < 1e-9) 1.0 else LensDistortion.distort(r, norm, k) / r
                val sx = (cx + dx * scale).toInt()
                val sy = (cy + dy * scale).toInt()
                map[y * w + x] =
                    if (sx < 0 || sy < 0 || sx >= w || sy >= h) -1 else sy * w + sx
            }
        }
        tableKey = key
        table = map
        return map
    }

    private fun sample(px: IntArray, w: Int, h: Int, x: Double, y: Double): Int {
        if (x < 0 || y < 0 || x > w - 1.0 || y > h - 1.0) return 0xFF000000.toInt()
        val x0 = x.toInt(); val y0 = y.toInt()
        val x1 = if (x0 + 1 < w) x0 + 1 else x0
        val y1 = if (y0 + 1 < h) y0 + 1 else y0
        val fx = x - x0; val fy = y - y0
        val p00 = px[y0 * w + x0]; val p10 = px[y0 * w + x1]
        val p01 = px[y1 * w + x0]; val p11 = px[y1 * w + x1]
        var out = 0xFF shl 24
        for (shift in intArrayOf(16, 8, 0)) {
            val c00 = (p00 shr shift) and 0xFF
            val c10 = (p10 shr shift) and 0xFF
            val c01 = (p01 shr shift) and 0xFF
            val c11 = (p11 shr shift) and 0xFF
            val top = c00 + (c10 - c00) * fx
            val bot = c01 + (c11 - c01) * fx
            val v = (top + (bot - top) * fy).toInt().coerceIn(0, 255)
            out = out or (v shl shift)
        }
        return out
    }
}
