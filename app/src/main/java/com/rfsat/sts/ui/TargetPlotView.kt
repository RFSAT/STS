package com.rfsat.sts.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.content.ContextCompat
import com.rfsat.sts.R
import com.rfsat.sts.scoring.GroupStatistics
import com.rfsat.sts.scoring.Shot
import com.rfsat.sts.targets.TargetFace
import kotlin.math.hypot
import kotlin.math.min

/**
 * Draws the target face and the shots on it.
 *
 * DELIBERATELY NOT THEMED. Every other surface in the app follows the four
 * themes; this one does not. A scoring plot is a document — it gets
 * screenshotted, shared with a coach, attached to a club return — and a red
 * shot marker rendered in night-red mode on a black background is not a
 * document, it is an unreadable smear. So the plot always uses the fixed
 * paper/ink palette in colors.xml. The surrounding chrome still follows the
 * theme, which is the right compromise: the app stays dark-adapted, the
 * evidence stays legible.
 *
 * COORDINATES. Input is target-plane millimetres (+x right, +y up); the view
 * maps them to pixels with a single uniform scale and a y flip, so the
 * rendering is never anisotropic no matter how the view is laid out.
 */
class TargetPlotView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    // ---- data ----
    var face: TargetFace? = null
        set(v) { field = v; resetView(); invalidate() }

    var shots: List<Shot> = emptyList()
        set(v) { field = v; invalidate() }

    var group: GroupStatistics? = null
        set(v) { field = v; invalidate() }

    /** Point of aim, target-plane mm. Drawn when it is not the centre. */
    var poaXMm: Double = 0.0
    var poaYMm: Double = 0.0

    /** Highlighted shot, drawn larger — used to tie the plot to the shot list. */
    var selectedShotIndex: Int? = null
        set(v) { field = v; invalidate() }

    /** Called with target-plane mm when the user taps the plot. */
    var onTapMm: ((Double, Double) -> Unit)? = null

    /**
     * Drag-to-move. In [editMode] a touch that starts on a shot picks it up
     * and follows the finger; releasing reports where it ended in target-plane
     * millimetres, and the caller rescores it there.
     *
     * Worth having because the detector's centroid is right far more often
     * than it is wrong, but when it is wrong it is usually only wrong by a
     * millimetre or two — a hole partly hidden behind an earlier one, or a
     * torn edge pulling the weighted centre off. Deleting and re-tapping such
     * a shot loses the good estimate; nudging it keeps it.
     */
    var editMode: Boolean = false
        set(v) { field = v; draggingIndex = null; invalidate() }

    var onShotMoved: ((Shot, Double, Double) -> Unit)? = null

    private var draggingIndex: Int? = null
    private var dragMm: Pair<Double, Double>? = null

    /** When true the view fills with the SCORING area rather than the whole
     *  card. On a 700 mm rapid-fire card with a 500 mm scoring circle, or any
     *  practical silhouette, showing the whole card wastes most of the screen
     *  on blank cardboard. */
    var fitScoringAreaOnly: Boolean = true
        set(v) { field = v; resetView(); invalidate() }

    // ---- view transform ----
    private var userScale = 1.0f
    private var panX = 0f
    private var panY = 0f

    // ---- paints ----
    private val paper = paint(ContextCompat.getColor(context, R.color.plot_paper), Paint.Style.FILL)
    private val ink = paint(ContextCompat.getColor(context, R.color.plot_ink), Paint.Style.STROKE, 1.5f)
    private val blackArea = paint(ContextCompat.getColor(context, R.color.plot_black_area), Paint.Style.FILL)
    private val shotPaint = paint(ContextCompat.getColor(context, R.color.plot_shot), Paint.Style.FILL)
    private val shotLastPaint = paint(ContextCompat.getColor(context, R.color.plot_shot_last), Paint.Style.FILL)
    private val shotEdge = paint(Color.BLACK, Paint.Style.STROKE, 1f)
    private val groupPaint = paint(ContextCompat.getColor(context, R.color.plot_group), Paint.Style.STROKE, 2f)
    private val poaPaint = paint(ContextCompat.getColor(context, R.color.plot_poa), Paint.Style.STROKE, 2f)
    private val labelOnPaper = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.plot_ink)
        textAlign = Paint.Align.CENTER
    }
    private val labelOnBlack = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.plot_paper)
        textAlign = Paint.Align.CENTER
    }
    private val shotLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private fun paint(colour: Int, style: Paint.Style, width: Float = 0f) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colour
            this.style = style
            strokeWidth = width
        }

    // ---- gestures ----
    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                userScale = (userScale * detector.scaleFactor).coerceIn(0.5f, 20f)
                invalidate()
                return true
            }
        }
    )
    private var lastX = 0f
    private var lastY = 0f
    private var dragged = false

    private fun resetView() {
        userScale = 1f; panX = 0f; panY = 0f
    }

    // ------------------------------------------------------------------
    //  Transform
    // ------------------------------------------------------------------

    /** Millimetres visible across the shorter view dimension at scale 1. */
    private fun baseSpanMm(f: TargetFace): Double {
        val span = if (fitScoringAreaOnly) {
            when {
                f.rings.isNotEmpty() -> f.outerRadiusMm * 2.15
                f.zones.isNotEmpty() -> maxOf(f.faceWidthMm, f.faceHeightMm) * 1.05
                else -> maxOf(f.faceWidthMm, f.faceHeightMm)
            }
        } else {
            maxOf(f.faceWidthMm, f.faceHeightMm) * 1.05
        }
        return span.coerceAtLeast(1.0)
    }

    private fun pxPerMm(f: TargetFace): Float =
        (min(width, height) / baseSpanMm(f)).toFloat() * userScale

    private fun cxPx() = width / 2f + panX
    private fun cyPx() = height / 2f + panY

    private fun toPxX(f: TargetFace, mm: Double) = cxPx() + (mm * pxPerMm(f)).toFloat()
    private fun toPxY(f: TargetFace, mm: Double) = cyPx() - (mm * pxPerMm(f)).toFloat()

    private fun toMm(f: TargetFace, px: Float, py: Float): Pair<Double, Double> {
        val s = pxPerMm(f)
        if (s <= 0f) return 0.0 to 0.0
        return ((px - cxPx()) / s).toDouble() to ((cyPx() - py) / s).toDouble()
    }

    // ------------------------------------------------------------------
    //  Drawing
    // ------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val f = face ?: return
        val s = pxPerMm(f)
        if (s <= 0f) return

        drawCard(canvas, f, s)
        if (f.zones.isNotEmpty()) drawZones(canvas, f, s) else drawRings(canvas, f, s)
        drawGroup(canvas, f, s)
        drawPoa(canvas, f, s)
        drawShots(canvas, f, s)
    }

    private fun drawCard(canvas: Canvas, f: TargetFace, s: Float) {
        val hw = (f.faceWidthMm / 2.0)
        val hh = (f.faceHeightMm / 2.0)
        val cx = f.cardCentreOffsetXMm
        val cy = f.cardCentreOffsetYMm
        canvas.drawRect(
            toPxX(f, cx - hw), toPxY(f, cy + hh),
            toPxX(f, cx + hw), toPxY(f, cy - hh),
            paper
        )
        ink.strokeWidth = 1.5f
        canvas.drawRect(
            toPxX(f, cx - hw), toPxY(f, cy + hh),
            toPxX(f, cx + hw), toPxY(f, cy - hh),
            ink
        )
    }

    private fun drawRings(canvas: Canvas, f: TargetFace, s: Float) {
        // Black aiming mark first, so the ring lines drawn afterwards show on
        // top of it — which is how the real card is printed.
        if (f.blackDiameterMm > 0) {
            canvas.drawCircle(toPxX(f, 0.0), toPxY(f, 0.0), (f.blackDiameterMm / 2.0 * s).toFloat(), blackArea)
        }

        val blackR = f.blackDiameterMm / 2.0
        for (ring in f.ringsByValue.sortedBy { it.value }) {
            val r = ring.radiusMm
            val onBlack = blackR > 0 && r <= blackR
            ink.color = if (onBlack)
                ContextCompat.getColor(context, R.color.plot_paper)
            else
                ContextCompat.getColor(context, R.color.plot_ink)
            ink.strokeWidth = if (ring.value == 10) 2f else 1.2f
            canvas.drawCircle(toPxX(f, 0.0), toPxY(f, 0.0), (r * s).toFloat(), ink)

            drawRingNumerals(canvas, f, s, ring, onBlack)
        }
        ink.color = ContextCompat.getColor(context, R.color.plot_ink)

        // Inner ten, dashed so it cannot be mistaken for a scoring ring.
        if (f.hasInnerTen) {
            val dash = Paint(ink).apply {
                pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f, 6f), 0f)
                strokeWidth = 1f
                color = if (f.blackDiameterMm / 2.0 >= f.innerTenDiameterMm / 2.0)
                    ContextCompat.getColor(context, R.color.plot_paper)
                else ContextCompat.getColor(context, R.color.plot_ink)
            }
            canvas.drawCircle(
                toPxX(f, 0.0), toPxY(f, 0.0),
                (f.innerTenDiameterMm / 2.0 * s).toFloat(), dash
            )
        }
    }

    /**
     * The ring's own value, printed on the target the way a real card prints
     * it: at all four cardinal points, in the middle of the annulus, in ink
     * that reverses to white inside the black aiming mark.
     *
     * WHY FOUR AND NOT ONE. A single numeral below centre made the plot ask
     * to be rotated in the reader's head to check a shot on the left. Four
     * costs nothing at the sizes where they fit and makes a marginal call
     * legible wherever it lands.
     *
     * AND WHY THEY ARE GATED. On a 10 m air rifle face the rings are 2.5 mm
     * apart, and at thumbnail scale that annulus is thinner than the glyph.
     * Drawing anyway gives overlapping numerals that obscure the very rings
     * they label — so a numeral appears only when its annulus is comfortably
     * wider than the text, which means dense faces label their outer rings
     * and drop the inner ones automatically as the view shrinks.
     */
    private fun drawRingNumerals(
        canvas: Canvas, f: TargetFace, s: Float, ring: com.rfsat.sts.targets.Ring, onBlack: Boolean
    ) {
        // The ten is never numbered on a real face: it is the middle, and
        // there is rarely room. Neither is anything outside the printed set.
        if (ring.value !in 1..9) return

        val inner = f.ringsByValue.firstOrNull { it.value == ring.value + 1 }?.radiusMm ?: 0.0
        val annulusMm = ring.radiusMm - inner
        if (annulusMm <= 0.0) return

        val paint = if (onBlack) labelOnBlack else labelOnPaper
        val annulusPx = (annulusMm * s).toFloat()

        // SIZE THE GLYPH TO THE ANNULUS, rather than gating a fixed size
        // against it. The previous version asked for 12dp of text and skipped
        // the numeral unless the annulus was 1.7 times that — which on the
        // 230dp preview in the targets database is about 60 px against an
        // annulus of 33, so the numbers were skipped on every face at every
        // screen density and never appeared at all. Scaling instead means
        // they always appear at whatever size fits, and drop out only when
        // that size would genuinely be unreadable.
        val fitted = annulusPx * 0.62f
        val maxSize = 13 * resources.displayMetrics.density
        val minSize = 6.5f * resources.displayMetrics.density
        if (fitted < minSize) return
        paint.textSize = min(fitted, maxSize)

        // Midway across the annulus, so the numeral sits between its own ring
        // and the next one in rather than crowding either.
        val rMid = inner + annulusMm / 2.0
        val dy = paint.textSize / 3f
        val label = ring.value.toString()

        canvas.drawText(label, toPxX(f, 0.0), toPxY(f, rMid) + dy, paint)
        canvas.drawText(label, toPxX(f, 0.0), toPxY(f, -rMid) + dy, paint)
        canvas.drawText(label, toPxX(f, -rMid), toPxY(f, 0.0) + dy, paint)
        canvas.drawText(label, toPxX(f, rMid), toPxY(f, 0.0) + dy, paint)
    }

    private fun drawZones(canvas: Canvas, f: TargetFace, s: Float) {
        // Lowest priority first, so higher-value zones draw over the ones
        // they sit inside — matching how the real target is printed and how
        // TargetFace.zoneAt resolves an overlap.
        for (zone in f.zones.sortedBy { it.priority }) {
            if (zone.points.size < 3) continue
            val path = Path()
            zone.points.forEachIndexed { i, (u, v) ->
                val x = toPxX(f, u); val y = toPxY(f, v)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            ink.strokeWidth = 1.6f
            canvas.drawPath(path, ink)

            labelOnPaper.textSize = 11 * resources.displayMetrics.density
            val cxMm = zone.points.sumOf { it.first } / zone.points.size
            val cyMm = zone.points.sumOf { it.second } / zone.points.size
            canvas.drawText(zone.name, toPxX(f, cxMm), toPxY(f, cyMm), labelOnPaper)
        }
    }

    private fun drawGroup(canvas: Canvas, f: TargetFace, s: Float) {
        val g = group ?: return
        if (g.shotCount < 2) return
        val cx = toPxX(f, g.mpiXMm)
        val cy = toPxY(f, g.mpiYMm)
        // Group centre: a cross, plus the R50 circle. The circle is the
        // useful part — it shows the dispersion at a glance in a way no
        // number does.
        val arm = 10 * resources.displayMetrics.density
        canvas.drawLine(cx - arm, cy, cx + arm, cy, groupPaint)
        canvas.drawLine(cx, cy - arm, cx, cy + arm, groupPaint)
        if (g.r50Mm > 0) canvas.drawCircle(cx, cy, (g.r50Mm * s).toFloat(), groupPaint)
    }

    private fun drawPoa(canvas: Canvas, f: TargetFace, s: Float) {
        if (hypot(poaXMm, poaYMm) < 1e-6) return
        val x = toPxX(f, poaXMm)
        val y = toPxY(f, poaYMm)
        val r = 8 * resources.displayMetrics.density
        canvas.drawCircle(x, y, r, poaPaint)
        canvas.drawLine(x - r * 1.6f, y, x + r * 1.6f, y, poaPaint)
        canvas.drawLine(x, y - r * 1.6f, x, y + r * 1.6f, poaPaint)
    }

    private fun drawShots(canvas: Canvas, f: TargetFace, s: Float) {
        if (shots.isEmpty()) return
        val last = shots.maxByOrNull { it.timestampMs }
        // A shot marker is drawn at the SCORING GAUGE size where that is
        // legible, and at a fixed minimum otherwise. Drawing it at true size
        // matters: on a 10 m air rifle face a 4.5 mm pellet is most of the
        // nine ring, and a plot that drew it as a dot would make marginal
        // calls look far more clear-cut than they are.
        val minPx = 5 * resources.displayMetrics.density
        for (shot in shots) {
            if (shot.miss && hypot(shot.xMm, shot.yMm) < 1e-6) continue
            val live = if (shot.index == draggingIndex) dragMm else null
            val x = toPxX(f, live?.first ?: shot.xMm)
            val y = toPxY(f, live?.second ?: shot.yMm)
            val rPx = maxOf(minPx, (shot.diameterEstimateMm(f) / 2.0 * s).toFloat())
            val fill = if (shot === last) shotLastPaint else shotPaint
            // Low-confidence detections are drawn hollow, so a doubtful shot
            // is visibly doubtful rather than silently counted.
            if (!shot.manual && shot.confidence < 0.4) {
                canvas.drawCircle(x, y, rPx, Paint(fill).apply { style = Paint.Style.STROKE; strokeWidth = 2f })
            } else {
                canvas.drawCircle(x, y, rPx, fill)
            }
            canvas.drawCircle(x, y, rPx, shotEdge)

            if (shot.index == selectedShotIndex) {
                canvas.drawCircle(x, y, rPx * 2.0f, groupPaint)
            }
            if (rPx > minPx * 1.3f && !shot.sighter) {
                shotLabel.textSize = rPx * 1.1f
                canvas.drawText(shot.index.toString(), x, y + shotLabel.textSize / 3f, shotLabel)
            }
        }
    }

    /** Marker size: the gauge if the face implies one, else a sane default. */
    private fun Shot.diameterEstimateMm(f: TargetFace): Double =
        when {
            f.innerTenDiameterMm > 0 -> maxOf(4.5, f.outerRadiusMm * 0.02)
            f.outerRadiusMm > 0 -> maxOf(4.5, f.outerRadiusMm * 0.02)
            else -> 4.5
        }

    // ------------------------------------------------------------------
    //  Touch
    // ------------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x; lastY = event.y; dragged = false
                parent?.requestDisallowInterceptTouchEvent(true)
                draggingIndex = null
                if (editMode) {
                    val f = face
                    if (f != null) {
                        val grabPx = 28f * resources.displayMetrics.density
                        val hit = shots.filter { !it.sighter || true }.minByOrNull {
                            hypot(toPxX(f, it.xMm) - event.x, toPxY(f, it.yMm) - event.y).toDouble()
                        }
                        if (hit != null &&
                            hypot(toPxX(f, hit.xMm) - event.x, toPxY(f, hit.yMm) - event.y) <= grabPx
                        ) {
                            draggingIndex = hit.index
                            dragMm = hit.xMm to hit.yMm
                        }
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val di = draggingIndex
                if (di != null) {
                    val f = face
                    if (f != null) {
                        dragMm = toMm(f, event.x, event.y)
                        dragged = true
                        invalidate()
                    }
                    return true
                }
                if (!scaleDetector.isInProgress && event.pointerCount == 1) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    if (hypot(dx, dy) > 6f) dragged = true
                    panX += dx; panY += dy
                    lastX = event.x; lastY = event.y
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                val di = draggingIndex
                if (di != null) {
                    val moved = shots.firstOrNull { it.index == di }
                    val to = dragMm
                    draggingIndex = null; dragMm = null
                    if (moved != null && to != null) onShotMoved?.invoke(moved, to.first, to.second)
                    parent?.requestDisallowInterceptTouchEvent(false)
                    invalidate()
                    return true
                }
                if (!dragged) {
                    val f = face
                    if (f != null) {
                        val (u, v) = toMm(f, event.x, event.y)
                        onTapMm?.invoke(u, v)
                    }
                }
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    fun resetZoom() {
        resetView()
        invalidate()
    }
}
