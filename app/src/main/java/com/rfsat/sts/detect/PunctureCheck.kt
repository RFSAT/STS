package com.rfsat.sts.detect

import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Tells a hole from a picture of one.
 *
 * THE PROBLEM THIS SOLVES. Every measure of darkness, size and roundness that
 * accepts a real shot also accepts the ISSF roundel printed in the corner of
 * an approved card: it is dark, compact, circular, and almost exactly a
 * pellet across at normal photographing distance. On the user's own card the
 * shipped detector reported a region of the footer text as a shot with
 * confidence 0.08, and no threshold on contrast could have refused it without
 * also refusing the faintest real hole.
 *
 * WHAT SEPARATES THEM. A puncture removes the most material at its CENTRE, so
 * its brightness changes MONOTONICALLY outwards until it reaches the paper. A
 * printed device is a picture and has internal structure — the roundel has a
 * light centre, the club crest has a pale field, text has counters and gaps.
 * Measured on T0002, in bands out to 1.25 gauges:
 *
 *      all seven real holes    monotonic 1.00   contrast  57 to 145
 *      ISSF roundel            monotonic 0.71   contrast  15
 *      club crest              monotonic 0.43   contrast -29
 *      footer text             monotonic 0.57   contrast   1
 *
 * The gap is wide enough that the thresholds are not delicate.
 *
 * AN EARLIER VERSION OF THIS TEST WAS WRONG, and it is worth recording why.
 * It looked for torn fibres standing proud of the sheet and catching the
 * light, on the reasoning that a hole is ringed by paper brighter than its
 * surroundings. The radial profiles say otherwise: on a flatbed scan NOTHING
 * within two gauges of a hole is brighter than the intact paper. The test had
 * been built from what a photograph LOOKED like rather than from what the
 * numbers said, and it threw away six of the seven real holes.
 */
object PunctureCheck {

    /** Bands sampled from the centre outwards. */
    const val BANDS = 8

    /** Pixels a band must hold before its median means anything. Four is low,
     *  and deliberately so: the rectified plane runs at eight pixels per
     *  gauge, so the entire profile is about ten pixels across and a stricter
     *  floor would decline every candidate rather than judge it. */
    private const val MIN_BAND_PIXELS = 4

    /** How far out the profile is read, in gauges. Past about 1.25 the
     *  profile has reached the paper and adds nothing but neighbours. */
    const val SPAN_GAUGES = 1.25

    /** A step may fall this many levels against the trend and still count as
     *  monotonic; JPEG blocking and paper grain both cost a level or two. */
    private const val STEP_SLACK = 3.0

    /** Fraction of steps that must follow the trend. */
    private const val MIN_MONOTONIC = 0.85

    /** Levels between the centre band and the outermost. Below this the
     *  candidate has no core worth speaking of. */
    private const val MIN_CONTRAST = 40.0

    /** Outside the scoring area the prior is quite different: that region is
     *  ALL print — logos, score boxes, the maker's name, the shooter's own
     *  handwriting — and a false shot there is pure noise in the plot. A
     *  candidate found out there has to look considerably more like a hole. */
    private const val MIN_MONOTONIC_STRICT = 0.95

    data class Profile(
        val bands: DoubleArray,
        val monotonic: Double,
        val contrastLevels: Double
    ) {
        override fun equals(other: Any?): Boolean =
            other is Profile && bands.contentEquals(other.bands) &&
                monotonic == other.monotonic && contrastLevels == other.contrastLevels
        override fun hashCode(): Int =
            bands.contentHashCode() * 31 + monotonic.hashCode() * 31 + contrastLevels.hashCode()
    }

    /**
     * Reads the radial brightness profile about [x],[y].
     *
     * [inBlack] flips the expected direction: a hole in the aiming mark is
     * BRIGHTER than the mark, and a hole in the paper is darker than the
     * paper, so the same shape of evidence arrives with opposite sign. A
     * single global rule finds one and misses the other — and the one it
     * misses is the one that scores nine or ten. On T0002 the shipped
     * detector found four shots and missed exactly one inside the scoring
     * area: the 9, in the black.
     *
     * Returns null when the window does not hold enough real pixels, which is
     * how a candidate hard against the frame edge is declined rather than
     * described by pixels that were never sampled.
     */
    fun profile(frame: LumaFrame, x: Double, y: Double, gaugePx: Double, inBlack: Boolean): Profile? {
        if (gaugePx <= 2.0) return null
        val reach = gaugePx * SPAN_GAUGES
        val r0 = (y - reach).toInt().coerceAtLeast(0)
        val r1 = (y + reach).toInt().coerceAtMost(frame.height - 1)
        val c0 = (x - reach).toInt().coerceAtLeast(0)
        val c1 = (x + reach).toInt().coerceAtMost(frame.width - 1)
        if (r1 - r0 < 8 || c1 - c0 < 8) return null

        val buckets = Array(BANDS) { ArrayList<Int>() }
        for (j in r0..r1) {
            for (i in c0..c1) {
                val d = hypot(i - x, j - y) / gaugePx
                if (d >= SPAN_GAUGES) continue
                // EQUAL-AREA bands, not equal-width.
                //
                // The detector works in a rectified plane fixed at eight
                // pixels per gauge, so the whole profile spans about ten
                // pixels. Equal-width bands give the innermost one a handful
                // of pixels and the outermost a hundred, and the inner band —
                // the one carrying the evidence — is then too small to have a
                // median worth trusting. Splitting by AREA gives every band
                // the same pixel count at any resolution.
                val frac = (d / SPAN_GAUGES)
                val b = (frac * frac * BANDS).toInt().coerceIn(0, BANDS - 1)
                buckets[b].add(frame.at(i, j))
            }
        }
        val bands = DoubleArray(BANDS)
        for (b in 0 until BANDS) {
            if (buckets[b].size < MIN_BAND_PIXELS) return null
            buckets[b].sort()
            bands[b] = buckets[b][buckets[b].size / 2].toDouble()
        }

        var followed = 0
        for (b in 0 until BANDS - 1) {
            val step = if (inBlack) bands[b] - bands[b + 1] else bands[b + 1] - bands[b]
            if (step >= -STEP_SLACK) followed++
        }
        val monotonic = followed.toDouble() / (BANDS - 1)
        val contrast = if (inBlack) bands[0] - bands[BANDS - 1] else bands[BANDS - 1] - bands[0]
        return Profile(bands, monotonic, contrast)
    }

    /** True when the profile is that of a puncture rather than of print. */
    fun isPuncture(
        frame: LumaFrame,
        x: Double,
        y: Double,
        gaugePx: Double,
        inBlack: Boolean,
        outsideScoringArea: Boolean = false
    ): Boolean {
        val p = profile(frame, x, y, gaugePx, inBlack) ?: return false
        val floor = if (outsideScoringArea) MIN_MONOTONIC_STRICT else MIN_MONOTONIC
        return p.monotonic >= floor && p.contrastLevels >= MIN_CONTRAST
    }

    /** Rounded band medians, for logs and tests. */
    fun bandsOf(frame: LumaFrame, x: Double, y: Double, gaugePx: Double, inBlack: Boolean): IntArray? =
        profile(frame, x, y, gaugePx, inBlack)?.bands?.map { it.roundToInt() }?.toIntArray()
}
