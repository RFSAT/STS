package com.rfsat.sts.detect

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * ============================================================================
 *  REGISTRATION OVERLAY
 * ============================================================================
 *
 * Sits on top of the camera preview or the photograph and collects the input
 * that ties image pixels to millimetres. Two ways to give it, because they
 * suit different pictures:
 *
 *   [Mode.BOX] — a SQUARE box around a concentric feature of the face, with
 *   draggable top-left and bottom-right handles. Two handles instead of four
 *   taps, and the app can place it for you from the detected aiming mark. It
 *   expresses position and scale, and nothing else.
 *
 *   [Mode.CORNERS] — four taps on the corners of the card, which give a full
 *   projective transform and can undo the keystoning of a target photographed
 *   from an angle. More work, and the only correct choice when the view is
 *   oblique.
 *
 * TAPS ARE IN VIEW PIXELS, AND THAT IS NOT WHAT THE DETECTOR NEEDS. The
 * analysis frame is a different size from the preview and usually a different
 * aspect ratio, and a still photograph is letterboxed by an ImageView while a
 * camera preview is centre-cropped. Every coordinate that leaves this view is
 * converted to SOURCE pixels through [setSourceGeometry] first. Getting that
 * wrong gives a registration that is plausibly close and consistently skewed
 * — every shot wrong by a smoothly varying amount, the hardest kind of error
 * to see.
 *
 * The box is likewise STORED in source coordinates, so it survives a screen
 * rotation, a view resize, or the keyboard opening, none of which move the
 * target.
 */
class RegistrationOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    enum class Mode { BOX, CORNERS }

    /**
     * How the source is fitted into this view. A camera preview is
     * centre-cropped (PreviewView's default); a still photograph is
     * letterboxed by an ImageView set to fitCenter. The two give DIFFERENT
     * mappings from a finger to a source pixel.
     */
    enum class SourceFit { CENTER_CROP, FIT_CENTER }

    val cornerNames = listOf("top-left", "top-right", "bottom-right", "bottom-left")

    var mode: Mode = Mode.BOX
        set(v) { field = v; invalidate() }

    var sourceFit: SourceFit = SourceFit.CENTER_CROP
        set(v) { field = v; invalidate() }

    /** Fires with the number of corners tapped (CORNERS mode), or with 4 when
     *  a box exists (BOX mode), so hosts can enable a Register button. */
    var onCornersChanged: ((Int) -> Unit)? = null

    /** Fires whenever the box is moved or resized. */
    var onBoxChanged: (() -> Unit)? = null

    private val taps = mutableListOf<Pair<Float, Float>>()

    /** [left, top, right, bottom] in SOURCE pixels. Always square. */
    private var box: FloatArray? = null

    private var srcWidth = 0
    private var srcHeight = 0

    private val density = resources.displayMetrics.density
    private val handleRadius = 13f * density
    private val touchSlop = 26f * density
    private val minBoxSourcePx get() = 24.0

    // ---- paints ----
    /**
     * Overlay chrome follows the THEME, not a fixed gold.
     *
     * This is drawn over the live viewfinder, and under the night-red theme a
     * gold box with white handles and white labels is the brightest thing on
     * a screen whose entire purpose is to preserve dark adaptation. The
     * detection markers below keep their own colour: those encode a meaning
     * — what the app found, as against what the user is placing — and
     * recolouring them to match the box would lose that distinction.
     */
    private val accent = themeAccent()

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent; style = Paint.Style.STROKE; strokeWidth = 3f * density
    }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent; style = Paint.Style.FILL
    }
    /** A dark ring round each handle, not a white one: it separates the
     *  handle from a pale card without adding a bright source. */
    private val handleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 0, 0, 0); style = Paint.Style.STROKE; strokeWidth = 2f * density
    }
    private val scrim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#66000000") }
    private val frameGhost = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = alpha(accent, 0x55); style = Paint.Style.STROKE; strokeWidth = 2f * density
    }
    private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = alpha(accent, 0x88); style = Paint.Style.STROKE; strokeWidth = 1f * density
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f, 8f), 0f)
    }
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent; style = Paint.Style.STROKE; strokeWidth = 4f
    }
    private val markFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = alpha(accent, 0x66); style = Paint.Style.FILL
    }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent; style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent; textSize = 34f; isFakeBoldText = true
    }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AA000000"); style = Paint.Style.FILL
    }
    private val unusedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8878909A"); style = Paint.Style.STROKE; strokeWidth = 2f
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f, 6f), 0f)
    }
    private val detPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD32F2F"); style = Paint.Style.STROKE; strokeWidth = 4f
    }

    /**
     * Tilt and rotation applied on top of the box. The overlay draws the
     * transformed outline — which is what the user is actually matching to
     * the target — while the handles keep operating on the plain square
     * underneath. Separating them that way means dragging a handle never has
     * to invert the transform, and the transform never has to worry about
     * where the fingers are.
     */
    var transform: BoxTransform = BoxTransform.NONE
        set(v) { field = v; invalidate() }

    /** Detections drawn back onto the preview, in source pixels. */
    var detectedMarkers: List<Triple<Float, Float, Float>> = emptyList()
        set(v) { field = v; invalidate() }

    /**
     * Rings that were FOUND but left out of the fitted family.
     *
     * Drawn thinner and dashed, because "detected and not used" and "never
     * detected" look identical otherwise and mean quite different things: the
     * first is a ladder that chose a subset, the second is a ring the
     * detector could not see at all. Told apart, a user can say which of the
     * two is happening on their card.
     */
    var unusedMarkers: List<Triple<Float, Float, Float>> = emptyList()
        set(v) { field = v; invalidate() }

    // ------------------------------------------------------------------

    fun setSourceGeometry(sourceW: Int, sourceH: Int, fit: SourceFit = SourceFit.CENTER_CROP) {
        val changed = sourceW != srcWidth || sourceH != srcHeight
        srcWidth = sourceW
        srcHeight = sourceH
        sourceFit = fit
        // A different source is a different picture, so anything registered
        // against the old one is meaningless now.
        if (changed) { box = null; taps.clear() }
        invalidate()
    }

    fun cornerCount(): Int = taps.size

    fun hasBox(): Boolean = box != null

    fun clearAll() {
        taps.clear(); box = null
        onCornersChanged?.invoke(0); onBoxChanged?.invoke()
        invalidate()
    }

    fun clearCorners() = clearAll()

    fun undoCorner() {
        if (mode == Mode.CORNERS && taps.isNotEmpty()) {
            taps.removeAt(taps.size - 1)
            onCornersChanged?.invoke(taps.size)
            invalidate()
        } else {
            clearAll()
        }
    }

    /** The four taps in SOURCE pixels, or null until there are four. */
    fun cornersInSource(): List<Pair<Double, Double>>? {
        if (taps.size != 4 || srcWidth <= 0 || srcHeight <= 0) return null
        return taps.map { (vx, vy) -> viewToSource(vx, vy) }
    }

    fun setCornersFromSource(corners: List<Pair<Double, Double>>) {
        if (corners.size != 4 || srcWidth <= 0 || srcHeight <= 0) return
        taps.clear()
        corners.forEach { (sx, sy) -> taps.add(sourceToView(sx, sy)) }
        onCornersChanged?.invoke(taps.size)
        invalidate()
    }

    /** The square box in SOURCE pixels, or null. */
    fun boxInSource(): FloatArray? = box?.copyOf()

    /** Places the box, in SOURCE pixels. Squared off defensively — a caller
     *  computing it from a detected ellipse could otherwise hand over a
     *  rectangle and silently change what the registration means. */
    fun setBoxInSource(l: Float, t: Float, r: Float, b: Float) {
        val side = max(minBoxSourcePx, max((r - l).toDouble(), (b - t).toDouble())).toFloat()
        val cx = (l + r) / 2f
        val cy = (t + b) / 2f
        box = floatArrayOf(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f)
        mode = Mode.BOX
        onBoxChanged?.invoke()
        onCornersChanged?.invoke(4)
        invalidate()
    }

    /** A default box covering the middle of the frame, for when detection
     *  finds nothing and the user has to place it themselves. */
    fun setDefaultBox() {
        if (srcWidth <= 0 || srcHeight <= 0) return
        val side = min(srcWidth, srcHeight) * 0.6f
        setBoxInSource(
            srcWidth / 2f - side / 2f, srcHeight / 2f - side / 2f,
            srcWidth / 2f + side / 2f, srcHeight / 2f + side / 2f
        )
    }

    // ---- source <-> view mapping, both directions and both fits ----

    /** colorAccent from the active theme, falling back to the dark theme's
     *  gold if the attribute cannot be resolved. */
    private fun themeAccent(): Int {
        val tv = android.util.TypedValue()
        return if (context.theme.resolveAttribute(
                androidx.appcompat.R.attr.colorAccent, tv, true)) tv.data
        else Color.parseColor("#FFC107")
    }

    private fun alpha(colour: Int, a: Int): Int =
        Color.argb(a, Color.red(colour), Color.green(colour), Color.blue(colour))

    private fun srcScale(): Float = when (sourceFit) {
        SourceFit.CENTER_CROP -> max(width.toFloat() / srcWidth, height.toFloat() / srcHeight)
        SourceFit.FIT_CENTER -> min(width.toFloat() / srcWidth, height.toFloat() / srcHeight)
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

    // ------------------------------------------------------------------
    //  Drawing
    // ------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (mode == Mode.BOX) drawBox(canvas) else drawCorners(canvas)
        drawDetections(canvas)
    }

    private fun drawBox(canvas: Canvas) {
        val b = box
        if (b == null || srcWidth <= 0) {
            prompt(canvas, "Tap “Auto-detect”, or drag a box around the target")
            return
        }
        val (l, t) = sourceToView(b[0].toDouble(), b[1].toDouble())
        val (r, bt) = sourceToView(b[2].toDouble(), b[3].toDouble())

        // Dim everything outside the box, which is far more legible than an
        // outline alone once the picture behind it is busy.
        canvas.drawRect(0f, 0f, width.toFloat(), t, scrim)
        canvas.drawRect(0f, bt, width.toFloat(), height.toFloat(), scrim)
        canvas.drawRect(0f, t, l, bt, scrim)
        canvas.drawRect(r, t, width.toFloat(), bt, scrim)

        val cx = (b[0] + b[2]) / 2.0
        val cy = (b[1] + b[3]) / 2.0
        val half = (b[2] - b[0]) / 2.0

        if (transform.isIdentity) {
            canvas.drawRect(l, t, r, bt, boxPaint)
        } else {
            // The plain square stays visible but faint: it is the frame the
            // handles move, and hiding it would make them look detached from
            // anything. The transformed quad is drawn solid, because that is
            // the shape being matched to the target.
            canvas.drawRect(l, t, r, bt, frameGhost)
            drawSourcePolygon(canvas, transform.cornersFor(cx, cy, half), boxPaint, close = true)
        }

        // The inscribed circle is what the box actually MEANS: the feature
        // being measured is round, and showing the square alone invites
        // people to fit it to the card instead. Under tilt it becomes the
        // ellipse the aiming mark should already look like, which turns
        // setting the sliders into matching one outline to another.
        drawSourcePolygon(canvas, transform.circleFor(cx, cy, half), guide, close = true)
        drawSourcePolygon(
            canvas, listOf(transform.mapNorm(0.0, 1.0, cx, cy, half), transform.mapNorm(0.0, -1.0, cx, cy, half)),
            guide, close = false
        )
        drawSourcePolygon(
            canvas, listOf(transform.mapNorm(-1.0, 0.0, cx, cy, half), transform.mapNorm(1.0, 0.0, cx, cy, half)),
            guide, close = false
        )

        for (p in listOf(l to t, r to bt)) {
            canvas.drawCircle(p.first, p.second, handleRadius, handleFill)
            canvas.drawCircle(p.first, p.second, handleRadius, handleRing)
        }
    }

    /** Draws a polyline given in SOURCE pixels. */
    private fun drawSourcePolygon(
        canvas: Canvas, pts: List<Pair<Double, Double>>, paint: Paint, close: Boolean
    ) {
        if (pts.size < 2) return
        val path = Path()
        pts.forEachIndexed { i, (sx, sy) ->
            val (x, y) = sourceToView(sx, sy)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        if (close) path.close()
        canvas.drawPath(path, paint)
    }

    private fun drawCorners(canvas: Canvas) {
        if (taps.size >= 2) {
            val path = Path()
            taps.forEachIndexed { i, (x, y) -> if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
            if (taps.size == 4) path.close()
            canvas.drawPath(path, edgePaint)
        }
        taps.forEachIndexed { i, (x, y) ->
            canvas.drawCircle(x, y, 22f, markFill)
            canvas.drawCircle(x, y, 22f, markPaint)
            canvas.drawText("${i + 1}", x + 28f, y - 8f, textPaint)
        }
        if (taps.size < 4) prompt(canvas, "Tap the ${cornerNames[taps.size]} corner of the card")
    }

    private fun drawDetections(canvas: Canvas) {
        if (srcWidth <= 0) return
        val s = srcScale()
        // The unused ones first, so a ring that is both found and used shows
        // its solid marker rather than the dashed one.
        unusedMarkers.forEach { (sx, sy, r) ->
            val (x, y) = sourceToView(sx.toDouble(), sy.toDouble())
            canvas.drawCircle(x, y, (r * s).coerceAtLeast(8f), unusedPaint)
        }
        detectedMarkers.forEach { (sx, sy, r) ->
            val (x, y) = sourceToView(sx.toDouble(), sy.toDouble())
            canvas.drawCircle(x, y, (r * s).coerceAtLeast(8f), detPaint)
        }
    }

    private fun prompt(canvas: Canvas, message: String) {
        val w = textPaint.measureText(message)
        val pad = 18f
        canvas.drawRoundRect(
            20f, 20f, 20f + w + pad * 2, 20f + textPaint.textSize + pad * 2, 12f, 12f, shadow
        )
        canvas.drawText(message, 20f + pad, 20f + pad + textPaint.textSize * 0.8f, textPaint)
    }

    // ------------------------------------------------------------------
    //  Touch
    // ------------------------------------------------------------------

    private enum class Grab { NONE, TOP_LEFT, BOTTOM_RIGHT, INSIDE }

    private var grab = Grab.NONE
    private var lastX = 0f
    private var lastY = 0f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (mode == Mode.CORNERS) return cornerTouch(event)
        return boxTouch(event)
    }

    private fun cornerTouch(event: MotionEvent): Boolean {
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

    private fun boxTouch(event: MotionEvent): Boolean {
        val b = box ?: return super.onTouchEvent(event)
        val (l, t) = sourceToView(b[0].toDouble(), b[1].toDouble())
        val (r, bt) = sourceToView(b[2].toDouble(), b[3].toDouble())

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                grab = when {
                    near(event.x, event.y, l, t) -> Grab.TOP_LEFT
                    near(event.x, event.y, r, bt) -> Grab.BOTTOM_RIGHT
                    event.x in l..r && event.y in t..bt -> Grab.INSIDE
                    else -> Grab.NONE
                }
                lastX = event.x; lastY = event.y
                if (grab != Grab.NONE) {
                    // The host activity is a scroll view; without this the
                    // first drag scrolls the page instead of moving the box.
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (grab == Grab.NONE) return false
                val (sx, sy) = viewToSource(event.x, event.y)
                when (grab) {
                    // Resizing keeps the OPPOSITE corner pinned and takes the
                    // side from the larger of the two deltas. Using one axis
                    // would make the box impossible to grow diagonally; using
                    // the smaller would make it feel stuck.
                    Grab.TOP_LEFT -> {
                        val side = max(minBoxSourcePx, max(b[2] - sx, b[3] - sy))
                        b[0] = (b[2] - side).toFloat(); b[1] = (b[3] - side).toFloat()
                    }
                    Grab.BOTTOM_RIGHT -> {
                        val side = max(minBoxSourcePx, max(sx - b[0], sy - b[1]))
                        b[2] = (b[0] + side).toFloat(); b[3] = (b[1] + side).toFloat()
                    }
                    Grab.INSIDE -> {
                        val s = srcScale()
                        val dx = (event.x - lastX) / s
                        val dy = (event.y - lastY) / s
                        b[0] += dx; b[2] += dx; b[1] += dy; b[3] += dy
                    }
                    Grab.NONE -> Unit
                }
                lastX = event.x; lastY = event.y
                clampCentreIntoFrame(b)
                onBoxChanged?.invoke()
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (grab != Grab.NONE) { grab = Grab.NONE; performClick(); return true }
                grab = Grab.NONE
            }
        }
        return super.onTouchEvent(event)
    }

    private fun near(x: Float, y: Float, hx: Float, hy: Float) =
        abs(x - hx) <= touchSlop && abs(y - hy) <= touchSlop

    /**
     * The box may hang off the edge of the picture — a target can genuinely
     * be cropped, and the outer ring of a large face often is — but its
     * CENTRE must stay inside, because a centre outside the frame means the
     * scoring origin was never photographed and nothing can be scored.
     */
    private fun clampCentreIntoFrame(b: FloatArray) {
        if (srcWidth <= 0 || srcHeight <= 0) return
        val cx = (b[0] + b[2]) / 2f
        val cy = (b[1] + b[3]) / 2f
        val dx = cx.coerceIn(0f, srcWidth.toFloat()) - cx
        val dy = cy.coerceIn(0f, srcHeight.toFloat()) - cy
        if (dx != 0f) { b[0] += dx; b[2] += dx }
        if (dy != 0f) { b[1] += dy; b[3] += dy }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
