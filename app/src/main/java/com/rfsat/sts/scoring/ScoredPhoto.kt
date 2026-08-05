package com.rfsat.sts.scoring

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.rfsat.sts.log.Logger
import java.io.File
import java.io.FileOutputStream

/**
 * The shooter's own photograph, rectified onto the scoring grid.
 *
 * NOT held in [ScoringSession.state], which is persisted as JSON: a
 * several-megapixel bitmap has no business in a preferences file. It is kept
 * as a JPEG in the app's own files directory instead, with its millimetre
 * bounds beside it, and reloaded on demand.
 *
 * IT USED TO BE PURELY TRANSIENT, and the comment here said that losing it
 * across a process death cost the background of a plot and no score. That was
 * true and beside the point: the session itself survives a restart, so the
 * shooter came back to their score with "My photo" greyed out — the one view
 * in which a MISSED shot is visible, gone, while everything it belonged to
 * was still there. A photograph that outlives the app by one restart is worth
 * a quarter of a megabyte on disk.
 *
 * The saved copy is scaled to at most [MAX_SIDE] on its longest side. The
 * working copy in memory is left alone: it is what the detector measured
 * against, and rescaling that to save space would be paying in accuracy for
 * disk.
 *
 * The bounds are the millimetre extent the bitmap covers, so a view drawing
 * in millimetres can place it with one rectangle.
 */
object ScoredPhoto {

    private const val FILE = "scored_photo.jpg"
    private const val PREFS = "sts_photo"
    private const val KEY_U_MIN = "u_min"
    private const val KEY_U_MAX = "u_max"
    private const val KEY_V_MIN = "v_min"
    private const val KEY_V_MAX = "v_max"

    /** Longest side of the SAVED copy. Enough to see a 5 mm hole on a 170 mm
     *  card at four pixels across; small enough to decode in well under the
     *  time it takes to draw the screen. */
    private const val MAX_SIDE = 2048
    private const val QUALITY = 88

    var bitmap: Bitmap? = null
        private set
    var uMinMm = 0.0
        private set
    var uMaxMm = 0.0
        private set
    var vMinMm = 0.0
        private set
    var vMaxMm = 0.0
        private set

    /** Set once a restore has been attempted, so a session with genuinely no
     *  photograph does not hit the disk on every refresh. */
    private var restoreTried = false

    val available: Boolean get() = bitmap != null

    fun set(context: Context?, bmp: Bitmap?, uMin: Double, uMax: Double, vMin: Double, vMax: Double) {
        bitmap = bmp
        uMinMm = uMin; uMaxMm = uMax; vMinMm = vMin; vMaxMm = vMax
        restoreTried = true
        if (context == null || bmp == null) return
        val app = context.applicationContext
        // Off the main thread: this runs at the end of an import, where the
        // shooter is waiting to see their score, and a JPEG encode of a large
        // frame is not something to make them wait for.
        Thread {
            runCatching {
                val longest = maxOf(bmp.width, bmp.height)
                val save = if (longest <= MAX_SIDE) bmp else Bitmap.createScaledBitmap(
                    bmp, bmp.width * MAX_SIDE / longest, bmp.height * MAX_SIDE / longest, true)
                FileOutputStream(File(app.filesDir, FILE)).use {
                    save.compress(Bitmap.CompressFormat.JPEG, QUALITY, it)
                }
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putFloat(KEY_U_MIN, uMin.toFloat())
                    .putFloat(KEY_U_MAX, uMax.toFloat())
                    .putFloat(KEY_V_MIN, vMin.toFloat())
                    .putFloat(KEY_V_MAX, vMax.toFloat())
                    .apply()
            }.onFailure {
                Logger.w("ScoredPhoto", "could not keep the photograph: ${it.message}")
            }
        }.start()
    }

    /**
     * Brings the saved photograph back after a restart. Returns true when
     * one is available afterwards, whether it was already in memory or not.
     *
     * Bounds of zero width are treated as no photograph: without the
     * millimetre extent there is nowhere to draw it, and half a restore is
     * worse than none.
     */
    fun restore(context: Context): Boolean {
        if (bitmap != null) return true
        if (restoreTried) return false
        restoreTried = true
        val f = File(context.applicationContext.filesDir, FILE)
        if (!f.exists()) return false
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val uMin = p.getFloat(KEY_U_MIN, 0f).toDouble()
        val uMax = p.getFloat(KEY_U_MAX, 0f).toDouble()
        val vMin = p.getFloat(KEY_V_MIN, 0f).toDouble()
        val vMax = p.getFloat(KEY_V_MAX, 0f).toDouble()
        if (uMax - uMin <= 0.0 || vMax - vMin <= 0.0) return false
        val bmp = runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull() ?: return false
        bitmap = bmp
        uMinMm = uMin; uMaxMm = uMax; vMinMm = vMin; vMaxMm = vMax
        Logger.i("ScoredPhoto", "restored the photograph from the previous session")
        return true
    }

    /** Drops it from memory AND from disk. Anything less would leave the
     *  next session showing the previous card's photograph under its shots. */
    fun clear(context: Context?) {
        bitmap = null
        restoreTried = true
        val app = context?.applicationContext ?: return
        runCatching {
            File(app.filesDir, FILE).delete()
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        }
    }
}
