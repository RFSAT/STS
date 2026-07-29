package com.rfsat.sts.scoring

import android.graphics.Bitmap

/**
 * The shooter's own photograph, rectified onto the scoring grid.
 *
 * Held here rather than in [ScoringSession.state] because that is persisted
 * as JSON, and a several-megapixel bitmap has no business in a preferences
 * file. It is deliberately transient: losing it across a process death costs
 * the background of a plot, not any score.
 *
 * The bounds are the millimetre extent the bitmap covers, so a view drawing
 * in millimetres can place it with one rectangle.
 */
object ScoredPhoto {

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

    val available: Boolean get() = bitmap != null

    fun set(bmp: Bitmap?, uMin: Double, uMax: Double, vMin: Double, vMax: Double) {
        bitmap = bmp
        uMinMm = uMin; uMaxMm = uMax; vMinMm = vMin; vMaxMm = vMax
    }

    fun clear() {
        bitmap = null
    }
}
