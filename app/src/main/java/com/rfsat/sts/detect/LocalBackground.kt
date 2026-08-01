package com.rfsat.sts.detect

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * What the card would look like with no holes in it — estimated LOCALLY, and
 * separately on each side of the aiming mark's edge.
 *
 * WHY A GLOBAL LEVEL IS NOT ENOUGH. [SourceHoleDetector] took one median for
 * the paper and one for the black and measured every pixel against those. That
 * is exact only if the card is lit evenly, and a card photographed on a table
 * never is. Measured on the user's card B, unretouched: the paper reads 28 to
 * 40 levels darker at the foot of the sheet than at the head. The detection
 * threshold is 28. So at one end of the card the background is wrong by more
 * than the whole threshold — the top of the sheet reads as a hole everywhere
 * and the bottom reads as nothing at all.
 *
 * WHY THE TWO ZONES SURVIVE. The obvious fix, a single local estimate, would
 * smear the black mark's edge across a window several holes wide and turn
 * that edge into the strongest "hole" on the card. The zones are therefore
 * kept — but they come from the REGISTRATION, which knows in millimetres
 * exactly where the black is, so the boundary is a known fact rather than
 * something inferred from the pixels. Each side is estimated without ever
 * looking at the other, and the step at the edge is never averaged across.
 *
 * WHAT THIS FIXES BESIDES THE GRADIENT. A hole that straddles the boundary —
 * card A's shot at 3.5 mm inside the black edge, which the app never found —
 * now deviates on BOTH sides at once, because each of its pixels is compared
 * against the background of the zone that pixel is actually in, rather than
 * the whole hole being assigned to one zone by where its centre happens to
 * fall.
 */
object LocalBackground {

    /** Block side, in gauges. Two gauges is four times a hole's area, so a
     *  hole moves a block's median by little, and the 3x3 pass below removes
     *  what is left. */
    private const val BLOCK_GAUGES = 2.0

    /** A block needs this many pixels of a zone before its median means
     *  anything; below it the block is left empty and filled from its
     *  neighbours. */
    private const val MIN_BLOCK_PIXELS = 24

    private const val BLACK = 0
    private const val PAPER = 1

    /**
     * Returns a background level for every pixel, or null when the frame
     * holds too little of the card to estimate one.
     *
     * [inScope] marks the pixels worth looking at; [inBlack] marks which of
     * those are inside the aiming mark.
     */
    fun estimate(
        frame: LumaFrame,
        inScope: BooleanArray,
        inBlack: BooleanArray,
        gaugePx: Double
    ): DoubleArray? {
        val w = frame.width
        val h = frame.height
        val n = w * h
        if (gaugePx <= 2.0 || n <= 0) return null

        val block = max(8, (gaugePx * BLOCK_GAUGES).roundToInt())
        val gw = ceil(w.toDouble() / block).toInt()
        val gh = ceil(h.toDouble() / block).toInt()
        if (gw < 1 || gh < 1) return null

        // ---- one histogram per block per zone ----
        val cells = gw * gh
        val hist = Array(2) { IntArray(cells * 256) }
        val count = Array(2) { IntArray(cells) }
        for (j in 0 until h) {
            val gy = j / block
            val rowBase = j * w
            for (i in 0 until w) {
                val idx = rowBase + i
                if (!inScope[idx]) continue
                val z = if (inBlack[idx]) BLACK else PAPER
                val cell = gy * gw + (i / block)
                hist[z][cell * 256 + (frame.data[idx].toInt() and 0xFF)]++
                count[z][cell]++
            }
        }

        val level = Array(2) { DoubleArray(cells) { Double.NaN } }
        for (z in 0..1) {
            for (c in 0 until cells) {
                val total = count[z][c]
                if (total < MIN_BLOCK_PIXELS) continue
                var acc = 0
                val base = c * 256
                for (v in 0 until 256) {
                    acc += hist[z][base + v]
                    if (acc * 2 >= total) { level[z][c] = v.toDouble(); break }
                }
            }
        }

        // ---- median of the 3x3 block neighbourhood ----
        //
        // A block that a hole happens to fill still reads as the hole. Taking
        // the median of nine block medians throws that block out without
        // blurring the level, which an average would not: an average lets one
        // bad block pull its neighbours with it.
        val smoothed = Array(2) { DoubleArray(cells) { Double.NaN } }
        val nine = DoubleArray(9)
        for (z in 0..1) {
            for (gy in 0 until gh) {
                for (gx in 0 until gw) {
                    var k = 0
                    for (dy in -1..1) for (dx in -1..1) {
                        val yy = gy + dy; val xx = gx + dx
                        if (yy < 0 || xx < 0 || yy >= gh || xx >= gw) continue
                        val v = level[z][yy * gw + xx]
                        if (!v.isNaN()) nine[k++] = v
                    }
                    if (k == 0) continue
                    val slice = nine.copyOf(k)
                    slice.sort()
                    smoothed[z][gy * gw + gx] = slice[k / 2]
                }
            }
        }

        // ---- fill the blocks that hold none of their zone ----
        for (z in 0..1) if (!fillGaps(smoothed[z], gw, gh)) return null

        // ---- bilinear back to full resolution ----
        val out = DoubleArray(n)
        for (j in 0 until h) {
            val fy = (j + 0.5) / block - 0.5
            val y0 = kotlin.math.floor(fy).toInt()
            val ty = fy - y0
            for (i in 0 until w) {
                val idx = j * w + i
                if (!inScope[idx]) continue
                val fx = (i + 0.5) / block - 0.5
                val x0 = kotlin.math.floor(fx).toInt()
                val tx = fx - x0
                val g = smoothed[if (inBlack[idx]) BLACK else PAPER]
                out[idx] =
                    at(g, gw, gh, x0, y0) * (1 - tx) * (1 - ty) +
                    at(g, gw, gh, x0 + 1, y0) * tx * (1 - ty) +
                    at(g, gw, gh, x0, y0 + 1) * (1 - tx) * ty +
                    at(g, gw, gh, x0 + 1, y0 + 1) * tx * ty
            }
        }
        return out
    }

    private fun at(g: DoubleArray, gw: Int, gh: Int, x: Int, y: Int): Double =
        g[y.coerceIn(0, gh - 1) * gw + x.coerceIn(0, gw - 1)]

    /**
     * Spreads known levels into the empty blocks, nearest first. The paper
     * grid has a hole in the middle where the black is, and the black grid is
     * empty everywhere else; both must be filled before interpolation, or a
     * pixel near the boundary would read a level of NaN.
     *
     * Returns false when the grid holds no level at all, which means the zone
     * is not present in this frame.
     */
    private fun fillGaps(g: DoubleArray, gw: Int, gh: Int): Boolean {
        if (g.all { it.isNaN() }) return false
        var remaining = g.count { it.isNaN() }
        var guard = gw + gh + 2
        while (remaining > 0 && guard-- > 0) {
            val next = g.copyOf()
            for (gy in 0 until gh) {
                for (gx in 0 until gw) {
                    val c = gy * gw + gx
                    if (!g[c].isNaN()) continue
                    var sum = 0.0
                    var k = 0
                    for (dy in -1..1) for (dx in -1..1) {
                        val yy = gy + dy; val xx = gx + dx
                        if (yy < 0 || xx < 0 || yy >= gh || xx >= gw) continue
                        val v = g[yy * gw + xx]
                        if (!v.isNaN()) { sum += v; k++ }
                    }
                    if (k > 0) next[c] = sum / k
                }
            }
            next.copyInto(g)
            remaining = g.count { it.isNaN() }
        }
        return remaining == 0
    }
}
