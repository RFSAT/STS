package com.rfsat.sts.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.rfsat.sts.targets.TargetFace
import kotlin.math.max
import kotlin.math.min

/**
 * A small drawing of a target face, for the picker lists.
 *
 * WHY. A shooter knows their card by sight and often not by name: "ISSF
 * 25/50 m Precision Pistol" and "ISSF 10 m Air Pistol" are both black
 * circles with rings, and choosing the wrong one has been the single most
 * expensive mistake in this app's history — it silently rescales everything.
 * A picture beside the name settles it in a glance, which is the point at
 * which the mistake is cheapest to catch.
 *
 * Drawn rather than shipped as assets: the geometry is already in
 * [TargetFace], twenty-odd bitmaps would be twenty-odd things to keep in step
 * with it, and a custom face the user added would have no asset at all. The
 * same code therefore draws the built-ins and anything added later.
 *
 * Deliberately NOT a scaled-down [TargetPlotView]: at 40 dp the ring numerals
 * and shot markers are illegible noise, and the aspect handling and caching
 * wanted here would clutter a view that has a different job.
 */
object TargetThumbnail {

    private val cache = LinkedHashMap<String, Bitmap>(24, 0.75f, true)
    private const val MAX_CACHED = 40

    fun of(face: TargetFace, sizePx: Int): Bitmap {
        val key = "${face.id}@$sizePx"
        cache[key]?.let { if (!it.isRecycled) return it }
        val bmp = draw(face, sizePx)
        cache[key] = bmp
        while (cache.size > MAX_CACHED) {
            val oldest = cache.keys.firstOrNull() ?: break
            cache.remove(oldest)
        }
        return bmp
    }

    private fun draw(face: TargetFace, size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paper = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FAFAF5") }
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1A1A1A"); style = Paint.Style.STROKE
            strokeWidth = max(1f, size / 60f)
        }
        val black = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#111111") }

        c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paper)

        val cx = size / 2f
        val cy = size / 2f

        if (face.zones.isNotEmpty()) {
            // Practical silhouettes are recognised by their outline, so draw
            // the zones and nothing else.
            val halfW = max(1.0, face.faceWidthMm / 2)
            val halfH = max(1.0, face.faceHeightMm / 2)
            val s = (size * 0.42 / max(halfW, halfH)).toFloat()
            for (zone in face.zones.sortedBy { it.priority }) {
                if (zone.points.size < 3) continue
                val path = Path()
                zone.points.forEachIndexed { i, (u, v) ->
                    val x = cx + (u * s).toFloat()
                    val y = cy - ((v - face.cardCentreOffsetYMm) * s).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                c.drawPath(path, ink)
            }
            return bmp
        }

        val outer = face.outerRadiusMm
        if (outer <= 0.0) return bmp
        val s = (size * 0.45 / outer).toFloat()

        if (face.blackDiameterMm > 0) {
            c.drawCircle(cx, cy, (face.blackDiameterMm / 2 * s).toFloat(), black)
        }
        // Only as many rings as can be told apart at this size; drawing all
        // ten of a dense face gives a grey disc that says nothing.
        val rings = face.ringsByValue.sortedBy { it.radiusMm }
        val minGapPx = size / 22f
        var lastDrawn = -1f
        for (ring in rings) {
            val r = (ring.radiusMm * s).toFloat()
            if (r - lastDrawn < minGapPx) continue
            ink.color = if (face.blackDiameterMm > 0 && ring.radiusMm <= face.blackDiameterMm / 2)
                Color.parseColor("#FAFAF5") else Color.parseColor("#1A1A1A")
            c.drawCircle(cx, cy, r, ink)
            lastDrawn = r
        }
        return bmp
    }

    /** Free the cache when a face is edited, so a changed target does not go
     *  on showing the picture it used to have. */
    fun invalidate(faceId: String) {
        cache.keys.filter { it.startsWith("$faceId@") }.forEach { cache.remove(it) }
    }

    fun invalidateAll() = cache.clear()
}
