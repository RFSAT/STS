package com.rfsat.sts.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.rfsat.sts.targets.TargetFace
import kotlin.math.max
import kotlin.math.min

/**
 * ============================================================================
 *  LINING THE PHONE UP WITH THE CARD
 * ============================================================================
 *
 * A crosshair says where the middle of the frame is. The RINGS of the selected
 * face say something far more useful, and it is worth being precise about
 * what:
 *
 *  - THEY VERIFY THE FACE, which is the thing the app most needs and cannot
 *    do for itself. The guide is drawn at the face's own RATIOS, so a card
 *    whose rings sit at different proportions will not line up however far
 *    the shooter moves. Scaling changes the size of every circle together;
 *    it cannot change the spacing between them. A mismatch is therefore
 *    visible through the viewfinder, before a shot is fired, rather than
 *    afterwards as a score that is quietly wrong. Selecting the wrong face is
 *    the single largest cause of nothing being detected at all.
 *
 *  - THEY REDUCE PERSPECTIVE at capture, which is the one error the scorer
 *    cannot fully undo. De-foreshortening recovers about half of what a
 *    square-on view would have given at 30 to 40 degrees; the rest is a
 *    projective term that a single affine correction has no way to represent.
 *    Not taking the error in the first place beats correcting it.
 *
 *  - THEY FIX THE FRAMING, so the card occupies a known fraction of the
 *    picture and a pellet hole lands on a predictable number of pixels.
 *
 * Aligning concentric circles is a NULLING task, which people are good at and
 * cameras are not. It is a guide and not a requirement: the app corrects
 * modest tilt perfectly well, and a shooter should not be made to fuss.
 */
class CrosshairView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    var guide: AimGuide = AimGuide.CROSS
        set(value) { field = value; invalidate() }

    /** Which reticle is drawn. Independent of [guide], which draws the
     *  selected face's rings and is a measurement aid rather than a sight. */
    var reticle: Reticle = Reticle.CROSS
        set(value) { field = value; invalidate() }

    /** The shooter's own reticle, when [reticle] is CUSTOM. */
    var customReticle: android.graphics.Bitmap? = null
        set(value) { field = value; invalidate() }

    /** The face whose rings are drawn. Null falls back to a plain crosshair. */
    var face: TargetFace? = null
        set(value) { field = value; invalidate() }

    /**
     * Whether the card in view matches [face], shown continuously.
     *
     * Encoded THREE ways at once — colour, line style and a word — and not by
     * colour alone. Under the night-red theme a green line would be the one
     * bright non-red thing on a screen whose whole purpose is preserving dark
     * adaptation, so under that theme the hue is left alone and the state is
     * carried by the solid-or-dashed line and the label. That also happens to
     * be what a colour-blind shooter needs, in daylight, on a small screen.
     */
    var match: GuideMatch = GuideMatch.UNKNOWN
        set(value) { field = value; invalidate() }

    /** True when the active theme must not gain a colour of its own. */
    var preserveNightVision: Boolean = false
        set(value) { field = value; invalidate() }

    /**
     * Size of the guide as a fraction of the smaller screen dimension.
     *
     * Adjustable because the distance to the card is fixed by the range, not
     * by the app: at 10 m a target fills the frame and at 50 m it does not.
     * The shooter sizes the guide to the card rather than walking to suit the
     * guide.
     */
    var sizeFraction: Float = 0.80f
        set(value) { field = value.coerceIn(0.15f, 1.0f); invalidate() }

    private val armFraction = 0.11f
    private val gapFraction = 0.018f

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
        color = withAlpha(themeAccent(context), 255)
    }

    /**
     * A bright core inside the accent line. On a phone held up in sunlight the
     * screen competes with the sun and a thin line simply disappears against
     * a white card. The core is a LIGHTENED accent rather than white, so the
     * night-red theme stays red — a white core would put back the one bright
     * thing that theme exists to remove.
     */
    private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f
        color = lighten(themeAccent(context), 0.55f)
    }

    /** Under the bright line, so the guide reads on white paper and on a
     *  black aiming mark alike. */
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6.5f
        color = Color.argb(170, 0, 0, 0)
    }

    private val ringHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4.5f
        color = Color.argb(150, 0, 0, 0)
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f
        color = withAlpha(themeAccent(context), 255)
    }
    /** The aiming mark's edge, drawn heavier: it is the easiest circle to
     *  match by eye and the one the scorer leans on hardest. */
    private val markRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
        color = lighten(themeAccent(context), 0.35f)
    }

    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = withAlpha(themeAccent(context), 255)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 12f, context.resources.displayMetrics
        )
    }
    private val labelHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 0, 0, 0)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        style = Paint.Style.STROKE
        strokeWidth = 4f
        textSize = label.textSize
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

        val f = face
        val wantRings = guide == AimGuide.RINGS || guide == AimGuide.RINGS_AND_CROSS
        if (guide != AimGuide.NONE) {
            if (wantRings && f != null && f.outerRadiusMm > 0.0) drawRings(canvas, f, cx, cy, d)
            // The plain cross belongs to the RETICLE now, not to the guide.
            // The old code drew one whenever there was no face, which put a
            // second crosshair over a scope camera that already shows one.
            if (guide == AimGuide.CROSS || guide == AimGuide.RINGS_AND_CROSS) {
                if (reticle == Reticle.NONE) drawCross(canvas, cx, cy, d)
            }
            if (wantRings && f != null) drawBadge(canvas)
        }
        drawReticle(canvas, cx, cy, d)
    }

    /**
     * The chosen reticle, drawn to the guide's size setting.
     *
     * Line work rather than bitmaps for the built-in ones: a drawn reticle
     * takes the theme's colour, so it stays red under the night-red theme
     * where an imported PNG would be the one bright thing on the screen. A
     * custom image is drawn as it comes, which is the point of it, and the
     * shooter is told as much in Settings.
     */
    private fun drawReticle(canvas: Canvas, cx: Float, cy: Float, d: Float) {
        if (reticle == Reticle.NONE) return
        val r = d * sizeFraction * 0.5f
        if (reticle == Reticle.CUSTOM) {
            val bmp = customReticle ?: return
            val w = bmp.width.toFloat()
            val h = bmp.height.toFloat()
            if (w < 1f || h < 1f) return
            val scale = (2f * r) / max(w, h)
            val dst = android.graphics.RectF(
                cx - w * scale / 2f, cy - h * scale / 2f,
                cx + w * scale / 2f, cy + h * scale / 2f
            )
            canvas.drawBitmap(bmp, null, dst, null)
            return
        }
        val c = stateColour()
        val thin = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(c, 220); style = Paint.Style.STROKE
            strokeWidth = max(1.5f, d * 0.0035f)
        }
        val thick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(c, 220); style = Paint.Style.STROKE
            strokeWidth = max(3.5f, d * 0.011f)
        }
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(c, 235); style = Paint.Style.FILL
        }
        when (reticle) {
            Reticle.CROSS -> {
                canvas.drawLine(cx - r, cy, cx + r, cy, thin)
                canvas.drawLine(cx, cy - r, cx, cy + r, thin)
            }
            Reticle.DUPLEX -> {
                // Thick to the shoulder, thin to the middle: the shape that
                // draws the eye in without covering the aiming mark.
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
                val step = r / 5f
                val rad = max(2f, d * 0.006f)
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
                val faint = Paint(thin).apply { color = withAlpha(c, 110) }
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
                // Three heavy posts and an open top, which is what makes it
                // a #4 rather than a duplex.
                val gap = r * 0.16f
                canvas.drawLine(cx - r, cy, cx - gap, cy, thick)
                canvas.drawLine(cx + gap, cy, cx + r, cy, thick)
                canvas.drawLine(cx, cy + gap, cx, cy + r, thick)
                canvas.drawLine(cx, cy - r * 0.55f, cx, cy - gap, thin)
            }
            Reticle.CIRCLE_DOT -> {
                canvas.drawCircle(cx, cy, r * 0.55f, thin)
                canvas.drawCircle(cx, cy, max(2.5f, d * 0.008f), dot)
            }
            else -> Unit
        }
    }

    /** The colour the guide draws in for the current state. */
    private fun stateColour(): Int {
        if (preserveNightVision) return themeAccent(context)
        return when (match) {
            GuideMatch.MATCH -> MATCH_GREEN
            GuideMatch.MISMATCH -> MISMATCH_AMBER
            else -> themeAccent(context)
        }
    }

    /** Dashed while the match is doubtful, solid once it is not — so the
     *  state reads without colour at all. */
    private fun stateDash(): android.graphics.DashPathEffect? = when (match) {
        GuideMatch.MATCH -> null
        GuideMatch.UNKNOWN, GuideMatch.CHECKING -> android.graphics.DashPathEffect(floatArrayOf(10f, 8f), 0f)
        GuideMatch.MISMATCH -> android.graphics.DashPathEffect(floatArrayOf(4f, 7f), 0f)
    }

    /** A word in the corner, because a colour alone is not a message. */
    private fun drawBadge(canvas: Canvas) {
        val text = match.label
        val pad = label.textSize * 0.6f
        val x = pad + label.textSize * 0.2f
        val y = pad + label.textSize
        val c = stateColour()
        label.textAlign = Paint.Align.LEFT
        labelHalo.textAlign = Paint.Align.LEFT
        val was = label.color
        label.color = withAlpha(c, 255)
        canvas.drawText(text, x, y, labelHalo)
        canvas.drawText(text, x, y, label)
        label.color = was
        label.textAlign = Paint.Align.CENTER
        labelHalo.textAlign = Paint.Align.CENTER
    }

    private fun drawCross(canvas: Canvas, cx: Float, cy: Float, d: Float) {
        val arm = d * armFraction
        val gap = d * gapFraction
        for (p in listOf(halo, stroke, core)) {
            canvas.drawLine(cx - gap - arm, cy, cx - gap, cy, p)
            canvas.drawLine(cx + gap, cy, cx + gap + arm, cy, p)
            canvas.drawLine(cx, cy - gap - arm, cx, cy - gap, p)
            canvas.drawLine(cx, cy + gap, cx, cy + gap + arm, p)
            canvas.drawCircle(cx, cy, gap * 0.55f, p)
        }
    }

    /**
     * The face's scoring rings, at their true RATIOS.
     *
     * Everything is scaled by the outermost ring, so the whole family grows
     * and shrinks together and the SPACING between circles is fixed by the
     * face. That is what makes this a check on the face and not merely a
     * framing aid.
     */
    private fun drawRings(canvas: Canvas, f: TargetFace, cx: Float, cy: Float, d: Float) {
        val outerMm = f.outerRadiusMm
        val pxPerMm = (d * sizeFraction / 2f) / outerMm.toFloat()

        ring.color = withAlpha(stateColour(), 255)
        ring.pathEffect = stateDash()
        markRing.color = lighten(stateColour(), 0.35f)
        markRing.pathEffect = stateDash()

        for (r in f.rings.sortedByDescending { it.radiusMm }) {
            val rPx = (r.radiusMm * pxPerMm).toFloat()
            if (rPx < 4f) continue
            canvas.drawCircle(cx, cy, rPx, ringHalo)
            canvas.drawCircle(cx, cy, rPx, ring)
        }
        if (f.blackDiameterMm > 0.0) {
            val rPx = (f.blackDiameterMm / 2.0 * pxPerMm).toFloat()
            if (rPx >= 4f) {
                canvas.drawCircle(cx, cy, rPx, ringHalo)
                canvas.drawCircle(cx, cy, rPx, markRing)
            }
        }
        val name = f.name
        val y = cy + (d * sizeFraction / 2f) + label.textSize * 1.6f
        if (y < height - 4) {
            canvas.drawText(name, cx, y, labelHalo)
            canvas.drawText(name, cx, y, label)
        }
    }

    private companion object {
        /** Only used where the theme is not preserving night vision. */
        val MATCH_GREEN = Color.rgb(76, 209, 100)
        val MISMATCH_AMBER = Color.rgb(255, 156, 46)

        fun themeAccent(context: Context): Int {
            val tv = TypedValue()
            return if (context.theme.resolveAttribute(
                    androidx.appcompat.R.attr.colorAccent, tv, true)) tv.data
            else 0xFFC9A24B.toInt()
        }

        fun withAlpha(colour: Int, alpha: Int): Int =
            Color.argb(alpha, Color.red(colour), Color.green(colour), Color.blue(colour))

        /** Moves a colour [f] of the way toward white, keeping its hue. */
        fun lighten(colour: Int, f: Float): Int = Color.argb(
            255,
            (Color.red(colour) + (255 - Color.red(colour)) * f).toInt().coerceIn(0, 255),
            (Color.green(colour) + (255 - Color.green(colour)) * f).toInt().coerceIn(0, 255),
            (Color.blue(colour) + (255 - Color.blue(colour)) * f).toInt().coerceIn(0, 255)
        )
    }
}
