package com.rfsat.sts.detect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.rfsat.sts.log.Logger

/**
 * Loads a user-supplied photograph into a bitmap the detector can work with.
 *
 * TWO THINGS THAT BITE, both of which fail silently rather than crashing:
 *
 *  1. ORIENTATION. Phone cameras record the sensor readout and an EXIF tag
 *     saying which way up it was, so a portrait photograph decodes as a
 *     landscape bitmap. The registration would still succeed — the user taps
 *     four corners of a rotated card and gets a valid homography — and every
 *     score would be right while the PLOT came out on its side. Applying the
 *     tag here means nothing downstream has to know about it.
 *
 *  2. SIZE. A modern phone photograph is 50 megapixels or more. Decoded at
 *     full size that is 200 MB as ARGB_8888, which is an OutOfMemoryError on
 *     most devices, and the rectified working image would be no better for
 *     it: detection resolution is set by the rectified grid, not by the
 *     source. So the decode is subsampled to [maxDimension], which is chosen
 *     to leave a scoring gauge comfortably more than the three pixels below
 *     which nothing can be found.
 *
 * RGB_565 rather than ARGB_8888: the detector reads luminance only, the
 * source has no alpha, and halving the memory halves the chance of an OOM on
 * the mid-range phones this app is most likely to run on.
 */
object ImageLoader {

    /**
     * Longest edge of the returned bitmap. 3000 px across a 500 mm target
     * face is 6 px per millimetre, so even a 4.5 mm air pellet spans 27 px —
     * far more than detection needs, and the rectifier subsamples from here
     * anyway.
     */
    const val DEFAULT_MAX_DIMENSION = 3000

    fun load(context: Context, uri: Uri, maxDimension: Int = DEFAULT_MAX_DIMENSION): Bitmap? {
        return try {
            // Pass one: bounds only, no pixels allocated.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri).use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                Logger.w("ImageLoader", "Could not read the image dimensions from $uri")
                return null
            }

            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxDimension)
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val decoded = context.contentResolver.openInputStream(uri).use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return null

            val rotation = context.contentResolver.openInputStream(uri).use { stream ->
                if (stream == null) 0f else runCatching {
                    when (ExifInterface(stream).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                    )) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                        else -> 0f
                    }
                }.getOrDefault(0f)
            }

            val out = if (rotation == 0f) decoded else {
                val m = Matrix().apply { postRotate(rotation) }
                val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
                if (rotated !== decoded) decoded.recycle()
                rotated
            }
            Logger.i(
                "ImageLoader",
                "Loaded ${bounds.outWidth}x${bounds.outHeight} as ${out.width}x${out.height} " +
                    "(sample ${opts.inSampleSize}, rotated ${rotation.toInt()} deg)"
            )
            out
        } catch (t: Throwable) {
            Logger.e("ImageLoader", "Could not load $uri", t)
            null
        }
    }

    /**
     * Smallest power of two that brings BOTH edges to [maxDimension] or
     * under. BitmapFactory rounds a non-power-of-two down to one anyway, so
     * computing it explicitly is the difference between asking for what you
     * get and being surprised by it.
     *
     * NOTE the direction, because this is where the usual copy-and-paste
     * version of this function is wrong for our purposes. The idiom found in
     * most sample code tests `width / (sample * 2) >= max` and therefore
     * stops one step EARLY, deliberately returning an image at least as large
     * as requested — right for a thumbnail that must not look soft, wrong
     * here, where the whole point is an upper bound on the allocation. On a
     * 50 MP photograph that idiom returns 2 and a 4080 px bitmap, not the
     * 3000 px cap it appears to promise.
     */
    fun sampleSizeFor(width: Int, height: Int, maxDimension: Int): Int {
        require(maxDimension > 0) { "maxDimension must be positive" }
        var sample = 1
        while (width / sample > maxDimension || height / sample > maxDimension) {
            sample *= 2
        }
        return sample
    }
}
