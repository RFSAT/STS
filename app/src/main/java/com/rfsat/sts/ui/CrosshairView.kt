package com.rfsat.sts.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * A centred crosshair over the viewfinder.
 *
 * Aligning a phone with a target card is done by eye, and the centre of a
 * phone screen is surprisingly hard to judge — especially with the target's
 * own concentric rings pulling the eye toward whatever is nearest the middle.
 * A fixed reference costs nothing and makes squaring up markedly easier,
 * which matters here beyond tidiness: the flatter the card sits in frame, the
 * less the ring fit has to correct, and the residual perspective error is the
 * one thing the scorer cannot fully undo.
 *
 * Purely decorative — it takes no touches and reads no state, so it can sit
 * over the preview without interfering with the registration overlay beneath
 * it.
 */
class CrosshairView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    /** Fraction of the smaller screen dimension spanned by each arm. */
    private val armFraction = 0.09f

    /** Gap left open at the centre so the crosshair never hides the very
     *  thing being aimed at — a ten ring is a small dot on some faces. */
    private val gapFraction = 0.018f

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.argb(210, 255, 255, 255)
    }

    /** Drawn under the white line, one pixel wider, so the crosshair stays
     *  visible on a white card as well as on a black aiming mark. */
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.argb(110, 0, 0, 0)
    }

    init {
        isClickable = false
        isFocusable = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width < 8 || height < 8) return
        val cx = width / 2f
        val cy = height / 2f
        val d = min(width, height).toFloat()
        val arm = d * armFraction
        val gap = d * gapFraction

        for (p in listOf(halo, stroke)) {
            canvas.drawLine(cx - gap - arm, cy, cx - gap, cy, p)
            canvas.drawLine(cx + gap, cy, cx + gap + arm, cy, p)
            canvas.drawLine(cx, cy - gap - arm, cx, cy - gap, p)
            canvas.drawLine(cx, cy + gap, cx, cy + gap + arm, p)
            canvas.drawCircle(cx, cy, gap * 0.55f, p)
        }
    }
}
