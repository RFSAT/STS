package com.rfsat.sts.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.max

/**
 * Shared reticle rendering, so the scoring viewfinder ([CrosshairView]) and
 * the ballistics viewfinder ([com.rfsat.sts.capture.CrosshairOverlayView])
 * draw the SAME reticle the shooter picked in Settings. [r] is the reticle's
 * half-extent in pixels; [strokeRef] a reference dimension for line weights.
 */
object ReticleDrawer {

    /** The reticle colour for a context's active theme — one source, so the
     *  ballistics and scoring viewfinders can never drift apart again. */
    fun colorFor(context: android.content.Context): Int {
        val tv = android.util.TypedValue()
        return if (context.theme.resolveAttribute(com.rfsat.sts.R.attr.stsReticleColor, tv, true)) {
            if (tv.resourceId != 0) context.getColor(tv.resourceId) else tv.data
        } else 0xFFFFC400.toInt()
    }

    fun draw(
        canvas: Canvas, cx: Float, cy: Float, r: Float, strokeRef: Float,
        reticle: Reticle, color: Int, custom: Bitmap?
    ) {
        if (reticle == Reticle.NONE) return
        if (reticle == Reticle.CUSTOM) {
            val bmp = custom ?: return
            val w = bmp.width.toFloat(); val h = bmp.height.toFloat()
            if (w < 1f || h < 1f) return
            val scale = (2f * r) / max(w, h)
            canvas.drawBitmap(bmp, null, RectF(
                cx - w * scale / 2f, cy - h * scale / 2f,
                cx + w * scale / 2f, cy + h * scale / 2f), null)
            return
        }
        // A dark halo under every line: the reticle sits over a photograph,
        // and a single colour cannot be contrasty on both white paper and a
        // black aiming mark. The halo makes it readable on either.
        val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = withAlpha(0x000000, 130); style = Paint.Style.STROKE
            strokeWidth = max(3.0f, strokeRef * 0.0075f)
        }
        val thin = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = withAlpha(color, 220); style = Paint.Style.STROKE
            strokeWidth = max(1.5f, strokeRef * 0.0035f)
        }
        val thick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = withAlpha(color, 220); style = Paint.Style.STROKE
            strokeWidth = max(3.5f, strokeRef * 0.011f)
        }
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = withAlpha(color, 235); style = Paint.Style.FILL
        }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = withAlpha(color, 235); textSize = max(9f, strokeRef * 0.02f)
        }
        // halo pass for the cross-type reticles
        if (reticle == Reticle.CROSS || reticle == Reticle.MIL_DOT ||
            reticle == Reticle.MOA_GRID || reticle == Reticle.MOA_TREE || reticle == Reticle.MRAD_TREE) {
            canvas.drawLine(cx - r, cy, cx + r, cy, halo)
            canvas.drawLine(cx, cy - r, cx, cy + r, halo)
        }
        when (reticle) {
            Reticle.CROSS -> {
                canvas.drawLine(cx - r, cy, cx + r, cy, thin)
                canvas.drawLine(cx, cy - r, cx, cy + r, thin)
            }
            Reticle.DUPLEX -> {
                val shoulder = r * 0.45f
                canvas.drawLine(cx - r, cy, cx - shoulder, cy, thick)
                canvas.drawLine(cx + shoulder, cy, cx + r, cy, thick)
                canvas.drawLine(cx, cy - r, cx, cy - shoulder, thick)
                canvas.drawLine(cx, cy + shoulder, cx, cy + r, thick)
                canvas.drawLine(cx - shoulder, cy, cx + shoulder, cy, thin)
                canvas.drawLine(cx, cy - shoulder, cx, cy + shoulder, thin)
            }
            Reticle.MIL_DOT -> {
                canvas.drawLine(cx - r, cy, cx + r, cy, thin)
                canvas.drawLine(cx, cy - r, cx, cy + r, thin)
                val step = r / 5f; val rad = max(2f, strokeRef * 0.006f)
                for (i in 1..4) {
                    val o = step * i
                    canvas.drawCircle(cx - o, cy, rad, dot)
                    canvas.drawCircle(cx + o, cy, rad, dot)
                    canvas.drawCircle(cx, cy - o, rad, dot)
                    canvas.drawCircle(cx, cy + o, rad, dot)
                }
            }
            Reticle.MOA_GRID -> {
                val step = r / 4f
                val faint = Paint(thin).apply { this.color = withAlpha(color, 110) }
                for (i in -4..4) {
                    val o = step * i
                    if (i != 0) {
                        canvas.drawLine(cx - r, cy + o, cx + r, cy + o, faint)
                        canvas.drawLine(cx + o, cy - r, cx + o, cy + r, faint)
                    }
                }
                canvas.drawLine(cx - r, cy, cx + r, cy, thin)
                canvas.drawLine(cx, cy - r, cx, cy + r, thin)
            }
            Reticle.GERMAN_4 -> {
                val gap = r * 0.16f
                canvas.drawLine(cx - r, cy, cx - gap, cy, thick)
                canvas.drawLine(cx + gap, cy, cx + r, cy, thick)
                canvas.drawLine(cx, cy + gap, cx, cy + r, thick)
                canvas.drawLine(cx, cy - r * 0.55f, cx, cy - gap, thin)
            }
            Reticle.CIRCLE_DOT -> {
                canvas.drawCircle(cx, cy, r * 0.55f, thin)
                canvas.drawCircle(cx, cy, max(2.5f, strokeRef * 0.008f), dot)
            }
            Reticle.MOA_TREE -> drawTree(canvas, cx, cy, r, thin, dot, text, mrad = false)
            Reticle.MRAD_TREE -> drawTree(canvas, cx, cy, r, thin, dot, text, mrad = true)
            else -> Unit
        }
    }

    /** A holdover "tree": a central cross, then windage bars stepping down,
     *  widening, with tick marks and a holdover NUMBER in MOA or MRAD. This is
     *  a lining-up guide, not a calibrated scope — the numbers illustrate the
     *  scale, they are not tied to the load. */
    private fun drawTree(
        canvas: Canvas, cx: Float, cy: Float, r: Float,
        thin: Paint, dot: Paint, text: Paint, mrad: Boolean
    ) {
        canvas.drawLine(cx, cy - r, cx, cy + r, thin)
        canvas.drawLine(cx - r, cy, cx + r, cy, thin)
        val steps = 5
        val stepY = r / (steps + 0.5f)
        val tickH = max(2f, r * 0.012f)
        for (i in 1..steps) {
            val y = cy + stepY * i
            val w = r * (0.10f + 0.065f * i)
            canvas.drawLine(cx - w, y, cx + w, y, thin)
            val ticks = 3
            for (t in 1..ticks) {
                val tx = w * t / ticks
                canvas.drawLine(cx + tx, y - tickH, cx + tx, y + tickH, thin)
                canvas.drawLine(cx - tx, y - tickH, cx - tx, y + tickH, thin)
            }
            canvas.drawCircle(cx, y, tickH, dot)
            val value = if (mrad) i else i * 2
            canvas.drawText(value.toString(), cx + w + text.textSize * 0.4f, y + text.textSize * 0.35f, text)
        }
        canvas.drawText(if (mrad) "MRAD" else "MOA", cx + r * 0.55f, cy - r * 0.85f, text)
    }

    private fun withAlpha(colour: Int, alpha: Int): Int =
        (colour and 0x00FFFFFF) or (alpha shl 24)
}
