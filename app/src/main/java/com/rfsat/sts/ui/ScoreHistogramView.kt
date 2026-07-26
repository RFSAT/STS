package com.rfsat.sts.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.rfsat.sts.scoring.ShotDistribution

/**
 * Horizontal bar chart of how many shots took each score.
 *
 * HORIZONTAL, NOT VERTICAL. A histogram is conventionally drawn with columns,
 * and on a desktop that would be right. On a phone it is not: eleven ring
 * values across a 360dp screen leaves 30dp per column, which is too narrow
 * for a legible label and hopeless once the labels are zone names like
 * "A-head" rather than single digits. Rows scale with the label instead of
 * fighting it, and a phone has vertical space to spare.
 *
 * THEMED, unlike [TargetPlotView]. The target plot is a document — it gets
 * screenshotted and sent to a coach, so its colours are fixed. This is a
 * reading of that document, part of the app's own furniture, so it follows
 * the four themes and stays legible in the night modes where a fixed palette
 * would not.
 *
 * The view measures its own height from the number of bars, so a caller can
 * give it wrap_content and never think about it again.
 */
class ScoreHistogramView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    var distribution: ShotDistribution = ShotDistribution.EMPTY
        set(v) { field = v; requestLayout(); invalidate() }

    /** Hide rings nobody hit. Off by default: the empty rings are part of the
     *  shape of the distribution, and dropping them is how a chart quietly
     *  stops showing the thing it exists to show. Worth turning on only for
     *  the practical faces, where most zones are genuinely irrelevant. */
    var hideEmptyBuckets: Boolean = false
        set(v) { field = v; requestLayout(); invalidate() }

    private val d = resources.displayMetrics.density
    private val rowH = 26f * d
    private val gap = 5f * d
    private val labelW = 42f * d
    private val countW = 62f * d

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val missPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13f * d; textAlign = Paint.Align.RIGHT; isFakeBoldText = true
    }
    private val countPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f * d; textAlign = Paint.Align.LEFT
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 13f * d }

    init { applyTheme() }

    private fun attr(id: Int, fallback: Int): Int {
        val tv = TypedValue()
        return if (context.theme.resolveAttribute(id, tv, true)) tv.data else fallback
    }

    private fun applyTheme() {
        val accent = attr(androidx.appcompat.R.attr.colorAccent, 0xFFC9A24B.toInt())
        val primary = attr(android.R.attr.textColorPrimary, 0xFFF2F7F0.toInt())
        val secondary = attr(android.R.attr.textColorSecondary, 0xFFC9D4C6.toInt())
        barPaint.color = accent
        // The miss bar is the accent at a third alpha rather than a different
        // hue: two of the four themes are monochrome by design, and a "red for
        // misses" would come out as an indistinguishable second green.
        missPaint.color = (accent and 0x00FFFFFF) or 0x55000000
        trackPaint.color = (secondary and 0x00FFFFFF) or 0x22000000
        labelPaint.color = primary
        countPaint.color = secondary
        emptyPaint.color = secondary
    }

    private fun rows(): List<com.rfsat.sts.scoring.ScoreBucket> {
        val all = distribution.buckets
        // The miss bar is only shown when there is something in it; a row of
        // zero misses at the bottom of every chart is noise.
        val visible = all.filter { !it.isMiss || it.count > 0 }
        return if (hideEmptyBuckets) visible.filter { it.count > 0 } else visible
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = resolveSize(suggestedMinimumWidth, widthMeasureSpec)
        val n = rows().size
        val h = if (distribution.isEmpty || n == 0) (34f * d).toInt()
        else (n * rowH + (n - 1) * gap + 4f * d).toInt()
        setMeasuredDimension(w, resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        applyTheme()

        val rows = rows()
        if (distribution.isEmpty || rows.isEmpty()) {
            canvas.drawText("No shots recorded yet.", 0f, 20f * d, emptyPaint)
            return
        }

        val peak = distribution.peak.coerceAtLeast(1)
        val trackLeft = labelW + 8f * d
        val trackRight = width - countW
        val trackW = (trackRight - trackLeft).coerceAtLeast(1f)
        val radius = 3f * d

        var y = 2f * d
        for (b in rows) {
            val cy = y + rowH / 2f

            canvas.drawText(b.label, labelW, cy + labelPaint.textSize / 3f, labelPaint)

            // Full-width track behind every bar, so a zero count still reads
            // as "this ring exists and took nothing" rather than as a gap.
            canvas.drawRoundRect(
                RectF(trackLeft, y + 4f * d, trackRight, y + rowH - 4f * d), radius, radius, trackPaint
            )

            if (b.count > 0) {
                // A minimum visible length: a single shot out of sixty is
                // under two pixels of a proportional bar, and a bar you
                // cannot see is indistinguishable from one that is not there.
                val len = (trackW * b.count / peak).coerceAtLeast(3f * d)
                canvas.drawRoundRect(
                    RectF(trackLeft, y + 4f * d, trackLeft + len, y + rowH - 4f * d),
                    radius, radius, if (b.isMiss) missPaint else barPaint
                )
            }

            val pct = b.percentOf(distribution.shotCount)
            canvas.drawText(
                if (b.count == 0) "—" else "${b.count}  (${"%.0f".format(pct)}%)",
                trackRight + 6f * d, cy + countPaint.textSize / 3f, countPaint
            )

            y += rowH + gap
        }
    }
}
