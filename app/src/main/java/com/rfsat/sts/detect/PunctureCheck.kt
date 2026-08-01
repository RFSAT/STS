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

    /** How far out the profile is read, in gauges.
     *
     *  1.25 reached PRINTED FEATURES. A shot in the middle of an ISSF air
     *  pistol face sits 1.28 gauges from the 10-ring line, so the outermost
     *  band of a centre hole was sampling the printed circle and reading it
     *  as structure. On the punched test card that alone took the 10 from
     *  1.00 monotonic to 0.71 and threw it out. */
    const val SPAN_GAUGES = 1.0

    /** A step may fall this many levels against the trend and still count as
     *  monotonic; JPEG blocking and paper grain both cost a level or two. */
    private const val STEP_SLACK = 3.0

    /**
     * Fraction of steps that must follow the trend — now a weak sanity floor
     * rather than the discriminator it was built to be.
     *
     * MEASURED, and it does not do the job it was added for. Sampled over
     * blank paper, printed ring lines, ring numerals and the black field, the
     * negatives score 1.00 monotonic as often as real holes do: monotonicity
     * separates a hole from PRINT hardly at all. What it reliably did was
     * reject real shots — on the punched card three of six, because a punched
     * hole has a bright burr around it that a pellet hole does not, and a
     * five-level wobble from paper grain costs a whole step out of seven.
     *
     * The CONTRAST floor was doing the work all along. On the original card
     * the ISSF roundel, the club crest and the footer text measured 15, -29
     * and 1 levels against 57 to 145 for real holes; on the punched card the
     * six holes measure 44 to 98 while every printed feature measures under
     * 5. So contrast is kept as the test and this is reduced to catching
     * something wildly structured.
     */
    private const val MIN_MONOTONIC = 0.6

    /** Levels between the centre band and the outermost. Below this the
     *  candidate has no core worth speaking of. */
    private const val MIN_CONTRAST = 40.0

    /** Outside the scoring area the prior is quite different: that region is
     *  ALL print — logos, score boxes, the maker's name, the shooter's own
     *  handwriting — and a false shot there is pure noise in the plot. A
     *  candidate found out there has to look considerably more like a hole. */
    /** Outside the rings, where every candidate competes with print rather
     *  than with paper.
     *
     *  Two ways of tightening this were tried and NEITHER worked, which is
     *  worth recording so they are not tried again. Contrast does not
     *  separate them: on the user's card the false marks ran 94, 110 and 139
     *  levels while two genuine shots ran 113 and 169. Nor does demanding a
     *  perfect profile: the false marks out there are perfectly monotonic
     *  too, so raising this to 1.0 changed nothing at all.
     *
     *  What survives is therefore a KNOWN limitation, not a solved problem:
     *  spurious marks can still appear beyond the outermost ring. They score
     *  zero and cannot move a total — on every configuration measured the
     *  score came out exactly right — but they put marks on the plot where
     *  no shot was fired. */
    private const val MIN_MONOTONIC_STRICT = 0.75

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
