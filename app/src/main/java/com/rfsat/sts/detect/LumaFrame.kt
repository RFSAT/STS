package com.rfsat.sts.detect

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy

/**
 * A single-channel 8-bit luminance image.
 *
 * WHY LUMA AND NOT COLOUR. Every cue this app needs is a brightness cue: a
 * hole is a local darkening on white paper, or a local lightening inside the
 * black aiming mark. Chroma adds three quarters of the memory traffic and
 * nothing that helps, and on a range under sodium lighting or a low sun it
 * actively hurts — white balance drifts frame to frame while luminance ratios
 * do not. Working in luma also means the camera path is free: YUV_420_888
 * hands over the Y plane directly, with no colour conversion at all.
 */
class LumaFrame(val width: Int, val height: Int, val data: ByteArray) {

    init {
        require(data.size >= width * height) {
            "LumaFrame buffer too small: ${data.size} < ${width * height}"
        }
    }

    /** Unsigned sample, or 0 outside the frame. Bounds-checked because the
     *  detectors deliberately sample windows that can hang over an edge. */
    fun at(x: Int, y: Int): Int {
        if (x < 0 || y < 0 || x >= width || y >= height) return 0
        return data[y * width + x].toInt() and 0xFF
    }

    /** Bilinear sample; used when resampling into the rectified plane, where
     *  nearest-neighbour would alias a 2 px pellet hole into invisibility. */
    fun sampleBilinear(fx: Double, fy: Double): Double {
        if (fx < 0 || fy < 0 || fx > width - 1.0 || fy > height - 1.0) return Double.NaN
        val x0 = fx.toInt(); val y0 = fy.toInt()
        val x1 = (x0 + 1).coerceAtMost(width - 1)
        val y1 = (y0 + 1).coerceAtMost(height - 1)
        val tx = fx - x0; val ty = fy - y0
        val p00 = at(x0, y0).toDouble(); val p10 = at(x1, y0).toDouble()
        val p01 = at(x0, y1).toDouble(); val p11 = at(x1, y1).toDouble()
        return (p00 * (1 - tx) + p10 * tx) * (1 - ty) + (p01 * (1 - tx) + p11 * tx) * ty
    }

    /** Mean sample value; used to normalise exposure drift between frames. */
    fun mean(): Double {
        var sum = 0L
        val n = width * height
        for (i in 0 until n) sum += (data[i].toInt() and 0xFF)
        return sum.toDouble() / n
    }

    companion object {

        /**
         * Scales the distance-from-paper into the 0..255 the rest of the
         * pipeline works in. 0.85 puts a brown pellet hole on white card at
         * the bottom of the range while leaving the paper's own variation
         * near the top.
         */
        const val COLOUR_SCALE = 0.85

        /**
         * The DETECTION channel: how far each pixel is from THE PAPER.
         *
         * WHY NOT LUMINANCE. Everything before this read brightness only, and
         * on a real card that discards most of the signal. A pellet hole in
         * white paper is not mainly darker than the paper — it is BROWN, and
         * the torn fibres and the backing behind it are warm grey. Measured on
         * a club target, holes came through at 115 luma levels of separation;
         * in this channel the same holes give 242, more than twice as far from
         * the paper, which is the difference between a faint fifth hole being
         * found and being missed.
         *
         * WHY DISTANCE FROM PAPER AND NOT FROM NEUTRAL. The obvious channel is
         * Y minus chroma, and it is WRONG, quietly and catastrophically, on
         * any card that is not white. An ISSF 10 m card is yellow: its paper
         * has a chroma of 121, so Y minus twice chroma sends the paper itself
         * to black and takes every hole down with it. The paper colour is
         * therefore MEASURED — the per-channel median over the image, which on
         * any target is the card, since the card is most of it — and the
         * channel reports distance from that. White card, yellow card, buff
         * card: all become bright, and everything that is not card becomes
         * dark.
         *
         * The printed rings and the aiming mark are also far from paper and
         * also come out dark. That is fine and intended: they are removed
         * afterwards by the radial median in [HoleDetector], which is a far
         * better tool for the job than colour.
         */
        fun fromBitmapForDetection(bmp: Bitmap, scale: Double = COLOUR_SCALE): LumaFrame {
            val w = bmp.width
            val h = bmp.height
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)

            val (pr, pg, pb) = paperColourOf(px)
            val out = ByteArray(w * h)
            for (i in px.indices) {
                val p = px[i]
                val d = kotlin.math.abs(((p shr 16) and 0xFF) - pr) +
                    kotlin.math.abs(((p shr 8) and 0xFF) - pg) +
                    kotlin.math.abs((p and 0xFF) - pb)
                out[i] = (255.0 - d * scale).toInt().coerceIn(0, 255).toByte()
            }
            return LumaFrame(w, h, out)
        }

        /**
         * The card's own colour: the per-channel median, sampled sparsely.
         *
         * The median rather than the mean, because a large black aiming mark
         * would drag a mean well off the paper and shrink every hole's
         * distance from it. With the card occupying more than half the frame —
         * true of any usable photograph of a target — the median IS the paper.
         */
        fun paperColourOf(px: IntArray): Triple<Int, Int, Int> {
            val hr = IntArray(256); val hg = IntArray(256); val hb = IntArray(256)
            var n = 0
            var i = 0
            val stride = maxOf(1, px.size / 40000)   // ~40k samples is plenty for a median
            while (i < px.size) {
                val p = px[i]
                hr[(p shr 16) and 0xFF]++
                hg[(p shr 8) and 0xFF]++
                hb[p and 0xFF]++
                n++
                i += stride
            }
            fun median(hist: IntArray): Int {
                var acc = 0
                for (v in 0 until 256) { acc += hist[v]; if (acc * 2 >= n) return v }
                return 255
            }
            return Triple(median(hr), median(hg), median(hb))
        }

        /**
         * The detection channel from a camera frame.
         *
         * The camera path keeps the luma channel: YUV would need the same
         * paper-colour estimate in a different colour space, and a live
         * preview is differenced against a reference frame anyway, which
         * already cancels the paper whatever colour it is. Colour earns its
         * keep on the single-photograph path, where there is nothing to
         * difference against.
         */
        fun fromImageProxyForDetection(image: ImageProxy): LumaFrame? = fromImageProxy(image)

        fun fromBitmap(bmp: Bitmap): LumaFrame {
            val w = bmp.width
            val h = bmp.height
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            val out = ByteArray(w * h)
            for (i in px.indices) {
                val p = px[i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                // Rec. 601 luma. Integer arithmetic: the weights are exact
                // enough at 8 bits and this runs over several megapixels.
                out[i] = (((r * 299 + g * 587 + b * 114) / 1000).coerceIn(0, 255)).toByte()
            }
            return LumaFrame(w, h, out)
        }

        /**
         * Copies the Y plane out of a CameraX frame.
         *
         * The row stride is NOT the width — hardware encoders align rows, and
         * on several devices the Y plane arrives padded. Ignoring that
         * produces an image that shears progressively down the frame, which
         * looks enough like motion blur that it is easy to misdiagnose.
         */
        fun fromImageProxy(image: ImageProxy): LumaFrame? {
            if (image.planes.isEmpty()) return null
            val plane = image.planes[0]
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val w = image.width
            val h = image.height
            val out = ByteArray(w * h)
            val row = ByteArray(rowStride)
            var o = 0
            for (y in 0 until h) {
                val remaining = buffer.remaining()
                if (remaining <= 0) break
                val toRead = minOf(rowStride, remaining)
                buffer.get(row, 0, toRead)
                if (pixelStride == 1) {
                    System.arraycopy(row, 0, out, o, minOf(w, toRead))
                } else {
                    var s = 0
                    for (x in 0 until w) {
                        if (s >= toRead) break
                        out[o + x] = row[s]
                        s += pixelStride
                    }
                }
                o += w
            }
            return LumaFrame(w, h, out)
        }
    }
}

/**
 * Summed-area table over a [LumaFrame], so the mean of any axis-aligned
 * rectangle is four array reads regardless of its size.
 *
 * The hole detector evaluates a disc-versus-annulus contrast at every
 * candidate pixel on the face. Done directly that is O(r^2) per pixel and
 * unusable on a 12 MP photo; through an integral image it is O(1), and the
 * whole pass runs in well under a second. Long accumulator because a 12 MP
 * frame of mid-grey overflows Int at around a sixth of the image.
 */
class IntegralImage(frame: LumaFrame) {
    private val w = frame.width
    private val h = frame.height
    private val sat = LongArray((w + 1) * (h + 1))

    init {
        for (y in 0 until h) {
            var rowSum = 0L
            for (x in 0 until w) {
                rowSum += frame.at(x, y)
                sat[(y + 1) * (w + 1) + (x + 1)] = sat[y * (w + 1) + (x + 1)] + rowSum
            }
        }
    }

    /** Sum over [x0,x1) x [y0,y1), clamped to the frame. */
    fun sum(x0: Int, y0: Int, x1: Int, y1: Int): Long {
        val a = x0.coerceIn(0, w); val b = y0.coerceIn(0, h)
        val c = x1.coerceIn(0, w); val d = y1.coerceIn(0, h)
        if (c <= a || d <= b) return 0
        return sat[d * (w + 1) + c] - sat[b * (w + 1) + c] - sat[d * (w + 1) + a] + sat[b * (w + 1) + a]
    }

    fun count(x0: Int, y0: Int, x1: Int, y1: Int): Int {
        val a = x0.coerceIn(0, w); val b = y0.coerceIn(0, h)
        val c = x1.coerceIn(0, w); val d = y1.coerceIn(0, h)
        return ((c - a).coerceAtLeast(0)) * ((d - b).coerceAtLeast(0))
    }

    /** Mean over the rectangle, or NaN if it is empty after clamping. */
    fun mean(x0: Int, y0: Int, x1: Int, y1: Int): Double {
        val n = count(x0, y0, x1, y1)
        return if (n == 0) Double.NaN else sum(x0, y0, x1, y1).toDouble() / n
    }
}

/**
 * An integral image that knows which pixels carry data.
 *
 * WHY THIS EXISTS. Rectifying a photograph fills everything the camera did
 * not cover with [TargetRegistration.OUT_OF_FRAME], which is very nearly
 * black. A plain integral image averages that in, so a window straddling the
 * edge of the covered area reads as much darker than the paper around it —
 * a window 25% outside the photograph reads 150 instead of 200, which is 50
 * levels of apparent contrast against a detector threshold of about 8. The
 * result was a rim of invented "holes" wherever the photograph did not cover
 * the whole card, which is most photographs.
 *
 * So sums and counts are both taken over VALID pixels only, and a window
 * without enough of them reports NaN rather than a confident wrong number.
 */
class MaskedIntegralImage(frame: LumaFrame, private val valid: BooleanArray) {
    private val w = frame.width
    private val h = frame.height
    private val satSum = LongArray((w + 1) * (h + 1))
    private val satCount = IntArray((w + 1) * (h + 1))

    init {
        require(valid.size >= w * h) { "validity mask smaller than the frame" }
        for (y in 0 until h) {
            var rowSum = 0L
            var rowCount = 0
            for (x in 0 until w) {
                val i = y * w + x
                if (valid[i]) { rowSum += frame.at(x, y); rowCount++ }
                satSum[(y + 1) * (w + 1) + (x + 1)] = satSum[y * (w + 1) + (x + 1)] + rowSum
                satCount[(y + 1) * (w + 1) + (x + 1)] = satCount[y * (w + 1) + (x + 1)] + rowCount
            }
        }
    }

    private fun rect(a: IntArray, x0: Int, y0: Int, x1: Int, y1: Int): Int {
        val l = x0.coerceIn(0, w); val t = y0.coerceIn(0, h)
        val r = x1.coerceIn(0, w); val b = y1.coerceIn(0, h)
        if (r <= l || b <= t) return 0
        return a[b * (w + 1) + r] - a[t * (w + 1) + r] - a[b * (w + 1) + l] + a[t * (w + 1) + l]
    }

    private fun rectL(a: LongArray, x0: Int, y0: Int, x1: Int, y1: Int): Long {
        val l = x0.coerceIn(0, w); val t = y0.coerceIn(0, h)
        val r = x1.coerceIn(0, w); val b = y1.coerceIn(0, h)
        if (r <= l || b <= t) return 0
        return a[b * (w + 1) + r] - a[t * (w + 1) + r] - a[b * (w + 1) + l] + a[t * (w + 1) + l]
    }

    fun validCount(x0: Int, y0: Int, x1: Int, y1: Int): Int = rect(satCount, x0, y0, x1, y1)

    fun sum(x0: Int, y0: Int, x1: Int, y1: Int): Long = rectL(satSum, x0, y0, x1, y1)

    /** Mean over valid pixels, or NaN when fewer than [minValid] of them. */
    fun mean(x0: Int, y0: Int, x1: Int, y1: Int, minValid: Int = 1): Double {
        val n = validCount(x0, y0, x1, y1)
        return if (n < minValid || n == 0) Double.NaN else sum(x0, y0, x1, y1).toDouble() / n
    }
}
