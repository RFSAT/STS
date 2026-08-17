package com.rfsat.sts.capture

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import com.rfsat.sts.log.Logger
import java.io.File
import java.io.FileOutputStream

/**
 * Takes a still out of a clip downloaded from the camera, for scoring.
 *
 * WHY THE LAST FRAME. A card is scored from the holes in it, and every hole
 * that was made during the string is present at the end and only at the end.
 * The first frame shows a clean card; the middle shows some of the shots. So
 * the default is the last frame that decodes, and the alternative offered is
 * a time the shooter names — for a clip that carries on past the string, or
 * one where the camera was knocked at the end.
 *
 * NOT THE TRAIL DECODER. VTB decodes every frame of a clip to follow a vapour
 * trail; nothing here needs that. One frame, through the platform's own
 * retriever, is a fraction of the code and cannot lose an analysis it never
 * had.
 */
object ClipFrame {

    private const val TAG = "ClipFrame"

    /** Duration in seconds, or 0 when the clip will not say. */
    fun durationS(path: String): Double = runCatching {
        MediaMetadataRetriever().use { r ->
            r.setDataSource(path)
            (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L) / 1000.0
        }
    }.getOrDefault(0.0)

    /**
     * @param atS where to take the frame, or null for the end of the clip
     * @return the file the still was written to, inside [cacheDir]
     */
    fun grab(path: String, cacheDir: File, atS: Double? = null): File? = runCatching {
        MediaMetadataRetriever().use { r ->
            r.setDataSource(path)
            val durS = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L) / 1000.0
            // A hair before the end: asking for the very last microsecond
            // returns nothing on some devices, which reads as a broken clip.
            val whenS = atS ?: (durS - 0.10).coerceAtLeast(0.0)
            val bmp: Bitmap = r.getFrameAtTime(
                (whenS * 1_000_000L).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            ) ?: return@runCatching null
            val out = File(cacheDir, "clip_frame.jpg")
            FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            Logger.i(TAG, "took a frame at %.2f s of %.2f s: %d x %d -> %s"
                .format(whenS, durS, bmp.width, bmp.height, out.name))
            out
        }
    }.onFailure { Logger.w(TAG, "could not take a frame from $path: ${it.message}") }
        .getOrNull()

    /** The retriever holds a file handle and a codec; both leak without this,
     *  and it is not AutoCloseable before API 29. */
    private inline fun <T> MediaMetadataRetriever.use(block: (MediaMetadataRetriever) -> T): T =
        try { block(this) } finally { runCatching { release() } }
}
