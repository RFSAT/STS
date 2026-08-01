package com.rfsat.sts.detect

import com.rfsat.sts.log.Logger
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Finds shots in the SOURCE photograph, at whatever resolution it arrived in.
 *
 * WHY A SECOND DETECTOR. [HoleDetector.detectAbsolute] works in the rectified
 * plane and misses shots inside the aiming mark. On the user's card it found
 * four of five scoring hits and lost the 9 — nearly half the score. Raising
 * the rectified resolution does not recover it: measured at 8, 12, 16, 24 and
 * 32 pixels per gauge the answer is identical, so resolution was not the
 * cause and neither was the colour channel, which carries 156 levels of
 * contrast at that hole. The loss is in the rectified detector's own stages.
 *
 * This is a port of the pipeline that scored that card correctly by hand,
 * kept deliberately close to it rather than improved on the way across,
 * because the thing being ported is the only version measured to work.
 *
 *   1. Two backgrounds, not one. The paper has a level and the aiming mark
 *      has a level, and a single global background makes the mark itself the
 *      biggest anomaly on the card.
 *   2. Deviation from that background, box-smoothed over half a gauge. This
 *      is the size filter and it does the work no threshold on darkness can:
 *      a printed ring line is a few pixels wide and a hole is tens, so
 *      smoothing at half a hole annihilates the lines and barely touches the
 *      holes — even though the line is exactly as dark as the hole.
 *   3. Shape gates, relaxed where the frame is clipped, because half a hole
 *      at the edge of a scan is half the area and twice as elongated.
 *   4. The centre refined on the CORE, with the polarity of the zone it sits
 *      in: darker than paper out on the card, brighter than the mark inside
 *      it. This is the step whose absence loses the 9.
 *   5. [PunctureCheck], to refuse the print that survives all of the above.
 *
 * Coordinates in, coordinates out: it reads the source frame and reports
 * millimetres, mapping through the registration's homography, so nothing
 * downstream needs to know it works in a different space.
 */
object SourceHoleDetector {

    /** Smoothed deviation a pixel must show to be worth grouping. */
    private const val DEVIATION_THRESHOLD = 28.0

    /** Box side for the smoothing, in gauges. */
    private const val SMOOTH_GAUGES = 0.5

    /** Opening kernel, pixels. Removes the one-pixel speckle that survives
     *  the smoothing without eroding a hole. */
    private const val OPEN_PX = 7

    /** Accepted equivalent diameters, in gauges. */
    private const val MIN_DIA = 0.55
    private const val MAX_DIA = 1.9

    /** …and at a clipped frame edge, where only part of the hole is present. */
    private const val MIN_DIA_EDGE = 0.30

    /** Accepted elongation, and the same at a clipped edge. A half disc
     *  measures about 1.97, which a limit of 1.9 threw out — and the shot it
     *  threw out was one of the two that missed the card's rings entirely. */
    private const val MAX_ELONGATION = 1.9
    private const val MAX_ELONGATION_EDGE = 2.8

    /** How far past the outermost ring to search, as a multiple of it. */
    private const val OUTSIDE_LIMIT = 1.35

    /** Levels from the zone background before a pixel counts as core. */
    private const val CORE_MARGIN = 55

    /** A region this many times a gauge's area is asked whether it holds more
     *  than one shot. Matches [MergedHoles]' own floor, so a region is never
     *  offered to the splitter that the splitter would refuse. */
    private const val MERGED_AREA_RATIO = 1.55

    /** Confidence given to a shot recovered by splitting. Deliberately modest:
     *  it is an inference about where two shots were, not a measurement of
     *  one, and the Results plot shows it as such. */
    private const val MERGED_CONFIDENCE = 0.40

    /** Mean deviation a region must carry before it may be split. Twice the
     *  detection threshold: a shadow or a crease clears the threshold and
     *  little more, a hole clears it several times over. */
    private const val MERGED_MIN_MEAN_DEV = 56.0

    /** Beyond this many gauges' worth of area, a region is NOT split and NOT
     *  reported. [MergedHoles] separates along one axis, which is right for a
     *  pair and meaningless for a rosette of eight; and reporting three shots
     *  where there are eight is worse than reporting none, because a count
     *  that looks plausible will be believed. The log says what was seen. */
    private const val MAX_MERGED_PARTS = 3

    /**
     * [frame] is the colour detection channel; [luma] is the plain luminance
     * of the same picture, and is used INSIDE THE AIMING MARK.
     *
     * The colour channel measures how far each pixel is from the paper's own
     * colour, which is what makes a hole on the card stand out however the
     * card is tinted. Inside the black it measures nothing at all: black ink
     * is far from buff paper, and so are the grey torn fibres of a hole
     * through it, so both saturate at zero and the hole disappears. Measured
     * on the user's card, the 9 in the black occupies 8 per cent of its own
     * window in the colour channel, with the widest solid run six pixels
     * across — against a gauge of forty-one. In luminance the same hole is a
     * 38.2 px core. That is the whole reason it was never found.
     *
     * So each zone is read with the signal that carries information there.
     * Passing null for [luma] keeps the old behaviour and loses the same shot.
     */
    fun detect(
        reg: TargetRegistration,
        frame: LumaFrame,
        gaugeDiameterMm: Double,
        luma: LumaFrame? = null,
        includeMisses: Boolean = false,
        maxHoles: Int = 200
    ): List<DetectedHole> {
        val w = frame.width
        val h = frame.height
        val n = w * h
        if (n <= 0 || gaugeDiameterMm <= 0.0) return emptyList()

        // ---- millimetres for every source pixel, once ----
        //
        // Through the homography rather than by assuming a scale: the whole
        // point of working in the source frame is that it may be perspective
        // distorted, and the mapping already knows how.
        val outer = reg.face.outerRadiusMm
        val limit = outer * (if (includeMisses) OUTSIDE_LIMIT else 1.10)
        val blackR = reg.face.blackDiameterMm / 2.0

        val rMm = DoubleArray(n)
        val inScope = BooleanArray(n)
        val inBlack = BooleanArray(n)
        for (j in 0 until h) {
            for (i in 0 until w) {
                val idx = j * w + i
                val (u, v) = reg.homography.pxToMm(i + 0.5, j + 0.5)
                if (u.isNaN() || v.isNaN()) { rMm[idx] = Double.NaN; continue }
                val r = hypot(u, v)
                rMm[idx] = r
                inScope[idx] = r <= limit
                inBlack[idx] = r <= blackR
            }
        }
        val scopeCount = inScope.count { it }
        if (scopeCount < 100) {
            Logger.w("SourceHoleDetector", "almost none of the face is in view; nothing to score")
            return emptyList()
        }

        // ---- gauge, in SOURCE pixels ----
        //
        // Measured off the mapping rather than assumed, by walking one gauge
        // in millimetres and seeing how far that is in pixels.
        val (x0, y0) = reg.homography.mmToPx(0.0, 0.0)
        val (x1, y1) = reg.homography.mmToPx(gaugeDiameterMm, 0.0)
        val gaugePx = hypot(x1 - x0, y1 - y0)
        if (!gaugePx.isFinite() || gaugePx < 3.0) {
            Logger.w("SourceHoleDetector", "a gauge is only %.1f source px; too coarse to detect".format(gaugePx))
            return emptyList()
        }

        // ---- one working image, each zone read from the right signal ----
        val work = if (luma != null && luma.width == w && luma.height == h) {
            val merged = ByteArray(n)
            for (idx in 0 until n) merged[idx] = if (inBlack[idx]) luma.data[idx] else frame.data[idx]
            LumaFrame(w, h, merged)
        } else frame

        // ---- what the card would look like with no holes in it ----
        //
        // Every judgement is made against the background of the zone the pixel
        // is in, and after the merge above the two zones are not even on the
        // same scale — which does not matter, because nothing compares them.
        //
        // LOCAL by preference. One level for the whole of the paper is exact
        // only on an evenly lit card, and a card photographed on a table never
        // is: measured on card B, unretouched, the paper reads 28 to 40 levels
        // darker at the foot of the sheet than at the head, against a
        // detection threshold of 28. See [LocalBackground].
        val paperLevel = medianOf(work, inScope, inBlack, wantBlack = false) ?: return emptyList()
        val blackLevel = medianOf(work, inScope, inBlack, wantBlack = true) ?: paperLevel

        val local = if (ScaleSettings.localBackground())
            LocalBackground.estimate(work, inScope, inBlack, gaugePx) else null
        if (ScaleSettings.localBackground() && local == null) {
            Logger.w("SourceHoleDetector", "local background could not be estimated; one level per zone")
        }

        val dev = DoubleArray(n)
        for (idx in 0 until n) {
            if (!inScope[idx]) continue
            val bg = local?.get(idx) ?: (if (inBlack[idx]) blackLevel else paperLevel)
            dev[idx] = abs((work.data[idx].toInt() and 0xFF) - bg).toDouble()
        }
        // ---- remove everything THINNER than a hole ----
        //
        // A grey-scale opening, not a blur. The first version of this smoothed
        // the deviation over half a gauge, on the reasoning that a printed
        // ring line is three pixels wide and a hole is forty, so averaging
        // over twenty would crush the line and spare the hole. That reasoning
        // came from the ORIGINAL luminance, where a ring line stands about 90
        // levels off the paper. This detector reads the COLOUR DETECTION
        // CHANNEL, where the same line stands about 220 off, and three parts
        // in twenty-one of 220 is 31 — over the threshold. The lines survived,
        // fused with every hole they touched, and the fused blob was then
        // thrown out as too big: at full size 41 of 56 blobs were rejected on
        // size and not one shot inside the rings was reported.
        //
        // An opening cannot fail that way. Eroding by a window half a gauge
        // across deletes ANY structure narrower than the window outright,
        // whatever its contrast, and the dilation then restores what is left
        // to its original extent. Contrast and width stop being traded off
        // against each other, which is what let a strong thin line imitate a
        // weak wide one.
        val box = maxOf(5, (gaugePx * SMOOTH_GAUGES).roundToInt() or 1)
        val smooth = greyOpen(dev, w, h, box)

        val hot = BooleanArray(n)
        for (idx in 0 until n) hot[idx] = inScope[idx] && smooth[idx] > DEVIATION_THRESHOLD
        val opened = open(hot, w, h, OPEN_PX)

        // ---- group, gate, refine ----
        val out = ArrayList<DetectedHole>()
        val labels = IntArray(n) { -1 }
        val stack = IntArray(n)
        var blobs = 0
        var rejSize = 0
        var rejShape = 0
        var rejProfile = 0
        var splitInto = 0
        var responseInt: IntArray? = null
        val minArea = 0.15 * Math.PI * gaugePx * gaugePx

        for (seed in 0 until n) {
            if (!opened[seed] || labels[seed] >= 0) continue
            var sp = 0
            stack[sp++] = seed
            labels[seed] = blobs
            var count = 0
            var sumX = 0.0; var sumY = 0.0
            var minX = w; var maxX = -1; var minY = h; var maxY = -1
            var sxx = 0.0; var syy = 0.0; var sxy = 0.0
            val members = ArrayList<Int>()
            while (sp > 0) {
                val p = stack[--sp]
                val px = p % w; val py = p / w
                members.add(p)
                count++
                sumX += px; sumY += py
                if (px < minX) minX = px; if (px > maxX) maxX = px
                if (py < minY) minY = py; if (py > maxY) maxY = py
                if (px > 0 && opened[p - 1] && labels[p - 1] < 0) { labels[p - 1] = blobs; stack[sp++] = p - 1 }
                if (px < w - 1 && opened[p + 1] && labels[p + 1] < 0) { labels[p + 1] = blobs; stack[sp++] = p + 1 }
                if (py > 0 && opened[p - w] && labels[p - w] < 0) { labels[p - w] = blobs; stack[sp++] = p - w }
                if (py < h - 1 && opened[p + w] && labels[p + w] < 0) { labels[p + w] = blobs; stack[sp++] = p + w }
            }
            blobs++
            if (count < minArea) { rejSize++; continue }

            val cx = sumX / count; val cy = sumY / count
            for (p in members) {
                val dx = (p % w) - cx; val dy = (p / w) - cy
                sxx += dx * dx; syy += dy * dy; sxy += dx * dy
            }
            sxx /= count; syy /= count; sxy /= count
            val t = sqrt(maxOf(0.0, (sxx - syy) * (sxx - syy) + 4 * sxy * sxy))
            val l1 = (sxx + syy + t) / 2.0
            val l2 = (sxx + syy - t) / 2.0
            val elong = if (l2 > 1e-9) sqrt(l1 / l2) else Double.MAX_VALUE
            val dia = 2.0 * sqrt(count / Math.PI)

            // ---- IS THIS ONE SHOT, OR SEVERAL RUN TOGETHER? ----
            //
            // Asked BEFORE the single-shot gates, because those gates are what
            // destroys a merged group. Measured on the punched cards: from the
            // second shot onwards on card C the cluster is found as ONE blob
            // every time and then thrown away — on profile, on size, on shape,
            // a different gate each frame depending on the shape it happened
            // to have. Card C scored 0 against a truth of 79, card B 10
            // against 41, and in every one of those frames the region was
            // sitting there in the blob list.
            //
            // A split is an INFERENCE about where two shots were, not a
            // measurement of one, and it is marked as such: merged = true and
            // a deliberately modest confidence. The puncture test is not
            // applied to the parts — a part of a peanut does not have the
            // radial profile of an isolated hole, and demanding one is
            // precisely what rejected the two-shot frame on card C.
            val expectedArea = Math.PI * (gaugePx / 2.0) * (gaugePx / 2.0)

            // ONLY A REGION THAT IS STRONGLY A HOLE MAY BE SPLIT.
            //
            // The parts of a split are emitted without the puncture test —
            // deliberately, because a part of a peanut has no isolated
            // profile — so nothing else stands between a blob and being
            // reported as several shots. Without this line the first frame of
            // card C turned one real hole and two patches of shadow into SIX
            // shots and scored 45 where the truth was 10. A crease sits just
            // over the detection threshold; a hole sits far above it.
            var devSum = 0.0
            for (p in members) devSum += dev[p]
            val meanDev = devSum / count
            val plausibleParts = count / expectedArea

            if (count >= expectedArea * MERGED_AREA_RATIO &&
                meanDev >= MERGED_MIN_MEAN_DEV &&
                plausibleParts <= MAX_MERGED_PARTS + 0.75
            ) {
                if (responseInt == null) {
                    responseInt = IntArray(n) { dev[it].roundToInt() }
                }
                val parts = MergedHoles.split(
                    members.toIntArray(), count, responseInt!!, w, gaugePx, expectedArea
                )
                if (parts.size > 1) {
                    var placed = 0
                    for (part in parts) {
                        val (pu, pv) = reg.homography.pxToMm(part.x, part.y)
                        if (pu.isNaN() || pv.isNaN()) continue
                        val pDia = 2.0 * sqrt(part.pixels / Math.PI)
                        if (pDia < MIN_DIA * gaugePx * 0.7) continue
                        out.add(
                            DetectedHole(
                                xMm = pu, yMm = pv,
                                diameterMm = pDia * gaugeDiameterMm / gaugePx,
                                contrast = 0.0,
                                confidence = MERGED_CONFIDENCE,
                                elongation = 1.0,
                                merged = true
                            )
                        )
                        placed++
                    }
                    if (placed > 0) {
                        Logger.i(
                            "SourceHoleDetector",
                            ("a region of %d px, %.1f gauges' worth, split into %d shots — the " +
                                "size, roundness and profile gates would have thrown it away whole")
                                .format(count, count / expectedArea, placed)
                        )
                        splitInto += placed
                        continue
                    }
                }
            }

            val clipped = minX == 0 || minY == 0 || maxX == w - 1 || maxY == h - 1
            val maxE = if (clipped) MAX_ELONGATION_EDGE else MAX_ELONGATION
            val minD = if (clipped) MIN_DIA_EDGE else MIN_DIA
            if (elong > maxE) { rejShape++; continue }
            if (dia < minD * gaugePx || dia > MAX_DIA * gaugePx) { rejSize++; continue }

            val black = inBlack[(cy.toInt().coerceIn(0, h - 1)) * w + cx.toInt().coerceIn(0, w - 1)]
            // NOT `?: run { rejShape++; continue }`: continue inside an inline
            // lambda needs Kotlin 2.2 and this project builds on 2.1, where it
            // compiles locally against a newer compiler and fails in CI.
            val refined = refineCore(
                work, cx, cy, gaugePx,
                if (black) blackLevel else paperLevel, black,
                local, inBlack, w, h
            )
            if (refined == null) { rejShape++; continue }
            val rx = refined.first
            val ry = refined.second

            val (mu, mv) = reg.homography.pxToMm(rx, ry)
            if (mu.isNaN() || mv.isNaN()) continue
            val outside = hypot(mu, mv) > outer
            if (!PunctureCheck.isPuncture(work, rx, ry, gaugePx, black, outside)) {
                rejProfile++; continue
            }

            val p = PunctureCheck.profile(work, rx, ry, gaugePx, black)
            val contrast = p?.contrastLevels ?: 0.0
            out.add(
                DetectedHole(
                    xMm = mu, yMm = mv,
                    diameterMm = dia * gaugeDiameterMm / gaugePx,
                    contrast = contrast,
                    confidence = confidenceOf(dia, gaugePx, elong, contrast),
                    elongation = elong
                )
            )
        }

        Logger.i(
            "SourceHoleDetector",
            ("source %dx%d, gauge %.1f mm = %.1f px, smoothing box %d, limit %.1f mm | " +
                "%d blobs -> %d holes (rejected %d on size, %d on shape, %d on profile" +
                (if (splitInto > 0) ", %d recovered from merged groups)".format(splitInto) else ")")).format(
                w, h, gaugeDiameterMm, gaugePx, box, limit, blobs, out.size, rejSize, rejShape, rejProfile
            )
        )
        return out.sortedByDescending { it.confidence }.take(maxHoles)
    }

    /** Median level over the pixels of one zone. */
    private fun medianOf(
        frame: LumaFrame, inScope: BooleanArray, inBlack: BooleanArray, wantBlack: Boolean
    ): Double? {
        val hist = IntArray(256)
        var total = 0
        for (idx in inScope.indices) {
            if (!inScope[idx] || inBlack[idx] != wantBlack) continue
            hist[frame.data[idx].toInt() and 0xFF]++
            total++
        }
        if (total < 50) return null
        var acc = 0
        for (level in 0 until 256) {
            acc += hist[level]
            if (acc * 2 >= total) return level.toDouble()
        }
        return null
    }

    /**
     * Weighted centroid of the hole's CORE.
     *
     * The polarity follows the zone. Out on the card a hole is darker than
     * the paper; inside the aiming mark it is brighter than the mark. This is
     * where the 9 was being lost: the shot in the black is a bright spot in a
     * field that reads 0, and any rule written for dark spots on light paper
     * is looking for the opposite of what is there.
     */
    private fun refineCore(
        frame: LumaFrame, cx: Double, cy: Double, gaugePx: Double, level: Double, black: Boolean,
        local: DoubleArray?, inBlack: BooleanArray, w: Int, h: Int
    ): Pair<Double, Double>? {
        val r = (gaugePx * 1.4).toInt().coerceAtLeast(3)
        var sw = 0.0; var sx = 0.0; var sy = 0.0
        for (j in (cy.toInt() - r)..(cy.toInt() + r)) {
            for (i in (cx.toInt() - r)..(cx.toInt() + r)) {
                if (i < 0 || j < 0 || i >= frame.width || j >= frame.height) continue
                val idx = j * w + i
                val v = frame.at(i, j).toDouble()
                // EACH PIXEL against its OWN zone and its own local level.
                // Deciding the polarity of a whole hole from the zone its
                // CENTRE landed in is what lost card A's shot straddling the
                // black edge: half of it was measured against the wrong side.
                val pixBlack = if (local != null) inBlack[idx] else black
                val bg = local?.get(idx) ?: level
                val isCore = if (pixBlack) v > bg + CORE_MARGIN else v < bg - CORE_MARGIN
                if (!isCore) continue
                val wgt = abs(v - bg)
                sw += wgt; sx += i * wgt; sy += j * wgt
            }
        }
        if (sw <= 0.0) return null
        return (sx / sw) to (sy / sw)
    }

    private fun confidenceOf(dia: Double, gaugePx: Double, elong: Double, contrast: Double): Double {
        val size = 1.0 - (abs(dia - gaugePx) / gaugePx).coerceIn(0.0, 1.0)
        val round = (1.0 - (elong - 1.0)).coerceIn(0.0, 1.0)
        val strong = (contrast / 120.0).coerceIn(0.0, 1.0)
        return (0.4 * size + 0.3 * round + 0.3 * strong).coerceIn(0.0, 1.0)
    }

    /** Grey-scale opening: erosion then dilation, separable, square window.
     *  Deletes anything narrower than the window and leaves the rest at full
     *  height — which is exactly the distinction between a printed line and a
     *  hole, and is not a distinction a linear filter can make. */
    private fun greyOpen(src: DoubleArray, w: Int, h: Int, k: Int): DoubleArray =
        greyMorph(greyMorph(src, w, h, k, erode = true), w, h, k, erode = false)

    private fun greyMorph(src: DoubleArray, w: Int, h: Int, k: Int, erode: Boolean): DoubleArray {
        val rad = k / 2
        val a = DoubleArray(src.size)
        val out = DoubleArray(src.size)
        for (j in 0 until h) for (i in 0 until w) {
            var best = src[j * w + i.coerceIn(0, w - 1)]
            for (d in -rad..rad) {
                val v = src[j * w + (i + d).coerceIn(0, w - 1)]
                best = if (erode) minOf(best, v) else maxOf(best, v)
            }
            a[j * w + i] = best
        }
        for (j in 0 until h) for (i in 0 until w) {
            var best = a[j * w + i]
            for (d in -rad..rad) {
                val v = a[(j + d).coerceIn(0, h - 1) * w + i]
                best = if (erode) minOf(best, v) else maxOf(best, v)
            }
            out[j * w + i] = best
        }
        return out
    }

    /** Separable box blur; the two passes are what keep this linear in the
     *  box size rather than quadratic. */
    private fun boxBlur(src: DoubleArray, w: Int, h: Int, box: Int): DoubleArray {
        val rad = box / 2
        val tmp = DoubleArray(src.size)
        val out = DoubleArray(src.size)
        for (j in 0 until h) {
            var sum = 0.0
            val base = j * w
            for (i in -rad..rad) sum += src[base + i.coerceIn(0, w - 1)]
            for (i in 0 until w) {
                tmp[base + i] = sum / box
                sum -= src[base + (i - rad).coerceIn(0, w - 1)]
                sum += src[base + (i + rad + 1).coerceIn(0, w - 1)]
            }
        }
        for (i in 0 until w) {
            var sum = 0.0
            for (j in -rad..rad) sum += tmp[j.coerceIn(0, h - 1) * w + i]
            for (j in 0 until h) {
                out[j * w + i] = sum / box
                sum -= tmp[(j - rad).coerceIn(0, h - 1) * w + i]
                sum += tmp[(j + rad + 1).coerceIn(0, h - 1) * w + i]
            }
        }
        return out
    }

    /** Erosion then dilation, separable, on a square kernel. */
    private fun open(src: BooleanArray, w: Int, h: Int, k: Int): BooleanArray =
        dilate(erode(src, w, h, k), w, h, k)

    private fun erode(src: BooleanArray, w: Int, h: Int, k: Int): BooleanArray {
        val rad = k / 2
        val a = BooleanArray(src.size)
        val b = BooleanArray(src.size)
        for (j in 0 until h) for (i in 0 until w) {
            var keep = true
            for (d in -rad..rad) if (!src[j * w + (i + d).coerceIn(0, w - 1)]) { keep = false; break }
            a[j * w + i] = keep
        }
        for (j in 0 until h) for (i in 0 until w) {
            var keep = true
            for (d in -rad..rad) if (!a[(j + d).coerceIn(0, h - 1) * w + i]) { keep = false; break }
            b[j * w + i] = keep
        }
        return b
    }

    private fun dilate(src: BooleanArray, w: Int, h: Int, k: Int): BooleanArray {
        val rad = k / 2
        val a = BooleanArray(src.size)
        val b = BooleanArray(src.size)
        for (j in 0 until h) for (i in 0 until w) {
            var any = false
            for (d in -rad..rad) if (src[j * w + (i + d).coerceIn(0, w - 1)]) { any = true; break }
            a[j * w + i] = any
        }
        for (j in 0 until h) for (i in 0 until w) {
            var any = false
            for (d in -rad..rad) if (a[(j + d).coerceIn(0, h - 1) * w + i]) { any = true; break }
            b[j * w + i] = any
        }
        return b
    }
}
