package com.rfsat.sts.detect

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Transparent overlay for registering the target: the user taps the four
 * corners of the card, and this view collects them and draws what it has.
 *
 * TAPS ARE IN VIEW PIXELS, AND THAT IS NOT WHAT THE DETECTOR NEEDS. The
 * analysis frame is a different size from the preview, and usually a
 * different aspect ratio, because CameraX picks an analysis resolution
 * independently of the preview's. So every tap is converted through
 * [setSourceGeometry] into coordinates in the ANALYSIS frame before it
 * leaves this view. Getting that wrong produces a registration that is
 * plausibly close and consistently skewed — the hardest kind of bug to see,
 * because every shot is wrong by a smoothly varying amount rather than
 * obviously wrong.
 *
 * The corners are collected in a fixed order — top-left, top-right,
 * bottom-right, bottom-left — because [TargetRegistration] pairs them
 * positionally with the card's own corners and cannot recover from a
 * transposition. The prompt text names the corner being asked for.
 */
class RegistrationOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    val cornerNames = listOf("top-left", "top-right", "bottom-right", "bottom-left")

    private val taps = mutableListOf<Pair<Float, Float>>()

    /**
     * How the source is fitted into this view. The camera preview is
     * centre-cropped (PreviewView's default), a still photograph is
     * letterboxed by an ImageView set to fitCenter. The two produce
     * DIFFERENT mappings from a finger tap to a source pixel, and using the
     * wrong one gives a registration that is plausibly close and
     * consistently skewed — every shot wrong by a smoothly varying amount,
     * which is the hardest kind of error to notice.
     */
    enum class SourceFit { CENTER_CROP, FIT_CENTER }

    var sourceFit: SourceFit = SourceFit.CENTER_CROP
        set(v) { field = v; invalidate() }

    /** Source frame size, and how it is fitted into this view. */
    private var srcWidth = 0
    private var srcHeight = 0

    var onCornersChanged: ((Int) -> Unit)? = null

    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFC107"); style = Paint.Style.STROKE; strokeWidth = 4f
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#66FFC107"); style = Paint.Style.FILL
    }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFC107"); style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 34f; isFakeBoldText = true
    }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AA000000"); style = Paint.Style.FILL
    }

    /** Detections drawn back onto the preview, in analysis-frame pixels. */
    var detectedMarkers: List<Triple<Float, Float, Float>> = emptyList()
        set(v) { field = v; invalidate() }

    private val detPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD32F2F"); style = Paint.Style.STROKE; strokeWidth = 4f
    }

    /**
     * Tells the overlay how big the source frame is and how it is being
     * displayed. If the host ever changes the ImageView scale type or the
     * PreviewView scale type, [fit] must change with it or the registration
     * silently shifts.
     */
    fun setSourceGeometry(
        sourceW: Int,
        sourceH: Int,
        fit: SourceFit = SourceFit.CENTER_CROP
    ) {
        srcWidth = sourceW
        srcHeight = sourceH
        sourceFit = fit
        invalidate()
    }

    fun cornerCount(): Int = taps.size

    fun clearCorners() {
        taps.clear()
        onCornersChanged?.invoke(0)
        invalidate()
    }

    fun undoCorner() {
        if (taps.isNotEmpty()) {
            taps.removeAt(taps.size - 1)
            onCornersChanged?.invoke(taps.size)
            invalidate()
        }
    }

    /** The four taps in ANALYSIS-frame pixels, or null until there are four. */
    fun cornersInSource(): List<Pair<Double, Double>>? {
        if (taps.size != 4 || srcWidth <= 0 || srcHeight <= 0) return null
        return taps.map { (vx, vy) -> viewToSource(vx, vy) }
    }

    /** Restores a previously stored registration, in analysis-frame pixels. */
    fun setCornersFromSource(corners: List<Pair<Double, Double>>) {
        if (corners.size != 4 || srcWidth <= 0 || srcHeight <= 0) return
        taps.clear()
        corners.forEach { (sx, sy) -> taps.add(sourceToView(sx, sy)) }
        onCornersChanged?.invoke(taps.size)
        invalidate()
    }

    // ---- source <-> view mapping, both directions and both fits ----

    private fun srcScale(): Float = when (sourceFit) {
        SourceFit.CENTER_CROP -> maxOf(width.toFloat() / srcWidth, height.toFloat() / srcHeight)
        SourceFit.FIT_CENTER  -> minOf(width.toFloat() / srcWidth, height.toFloat() / srcHeight)
    }

    private fun viewToSource(vx: Float, vy: Float): Pair<Double, Double> {
        val s = srcScale()
        val dx = (width - srcWidth * s) / 2f
        val dy = (height - srcHeight * s) / 2f
        return ((vx - dx) / s).toDouble() to ((vy - dy) / s).toDouble()
    }

    private fun sourceToView(sx: Double, sy: Double): Pair<Float, Float> {
        val s = srcScale()
        val dx = (width - srcWidth * s) / 2f
        val dy = (height - srcHeight * s) / 2f
        return (sx * s + dx).toFloat() to (sy * s + dy).toFloat()
    }

    // ---- drawing ----

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (taps.size >= 2) {
            val path = Path()
            taps.forEachIndexed { i, (x, y) -> if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
            if (taps.size == 4) path.close()
            canvas.drawPath(path, edgePaint)
        }
        taps.forEachIndexed { i, (x, y) ->
            canvas.drawCircle(x, y, 22f, fillPaint)
            canvas.drawCircle(x, y, 22f, markPaint)
            canvas.drawText("${i + 1}", x + 28f, y - 8f, textPaint)
        }

        if (srcWidth > 0 && detectedMarkers.isNotEmpty()) {
            val s = srcScale()
            detectedMarkers.forEach { (sx, sy, r) ->
                val (x, y) = sourceToView(sx.toDouble(), sy.toDouble())
                canvas.drawCircle(x, y, (r * s).coerceAtLeast(8f), detPaint)
            }
        }

        if (taps.size < 4) {
            val prompt = "Tap the ${cornerNames[taps.size]} corner of the target card"
            val w = textPaint.measureText(prompt)
            val pad = 18f
            canvas.drawRoundRect(
                20f, 20f, 20f + w + pad * 2, 20f + textPaint.textSize + pad * 2, 12f, 12f, shadow
            )
            canvas.drawText(prompt, 20f + pad, 20f + pad + textPaint.textSize * 0.8f, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            if (taps.size >= 4) taps.clear()
            taps.add(event.x to event.y)
            onCornersChanged?.invoke(taps.size)
            invalidate()
            performClick()
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
