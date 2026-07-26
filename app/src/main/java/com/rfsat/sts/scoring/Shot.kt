package com.rfsat.sts.scoring

import kotlin.math.atan2
import kotlin.math.hypot

/**
 * One scored shot.
 *
 * Position is target-plane millimetres from the SCORING centre, +x right,
 * +y up — the same frame as everything in the targets package.
 *
 * [value] is a Double even for integer disciplines, because a single numeric
 * field that is sometimes 9 and sometimes 10.4 is far less trouble than two
 * fields where every consumer has to know which one applies. [displayValue]
 * carries the formatted form so the UI never has to re-derive the number of
 * decimals from the rule set.
 */
data class Shot(
    /** 1-based, in the order the shots were recorded. */
    val index: Int,
    val xMm: Double,
    val yMm: Double,
    val value: Double,
    val displayValue: String,
    val innerTen: Boolean = false,
    /** Zone name for practical faces ("A", "C", "-1"); empty for ring faces. */
    val zoneName: String = "",
    /** Wall-clock time the shot was recorded, for split times and for the
     *  time-based disciplines. */
    val timestampMs: Long = System.currentTimeMillis(),
    /** 0..1 from the detector; 1.0 for a shot the user placed by hand. */
    val confidence: Double = 1.0,
    /** True when the user added or moved this shot rather than the detector
     *  finding it. Kept because a report that mixes measured and hand-placed
     *  shots without saying so is not a report anyone should rely on. */
    val manual: Boolean = false,
    /** Sighting shots are recorded and plotted but excluded from the total. */
    val sighter: Boolean = false,
    /** Which scored series this shot belongs to, 1-based. 0 = sighters. */
    val series: Int = 1,
    /** Set when the shot could not be scored — outside the outermost ring or
     *  off the face entirely. Value is then 0. */
    val miss: Boolean = false
) {
    val radiusMm: Double get() = hypot(xMm, yMm)

    /**
     * Clock-face bearing of the shot from centre, in degrees, where 0 is
     * twelve o'clock and the angle runs clockwise. Shooters talk about a
     * group being "at four o'clock", not "at -60 degrees", and the conversion
     * belongs here rather than in three separate display sites.
     */
    val bearingDeg: Double
        get() {
            val a = Math.toDegrees(atan2(xMm, yMm))
            return if (a < 0) a + 360.0 else a
        }

    val clockPosition: String
        get() {
            if (radiusMm < 1e-6) return "centre"
            val hour = ((bearingDeg / 30.0).let { Math.round(it) }.toInt() + 11) % 12 + 1
            return "$hour o'clock"
        }
}

/** One scored series within a course of fire. */
data class ScoredSeries(
    val number: Int,
    val shots: List<Shot>,
    val total: Double,
    val innerTens: Int,
    val displayTotal: String
)

/**
 * The complete result of scoring a set of shots under a rule set.
 *
 * [lowerIsBetter] is carried explicitly rather than being re-derived from the
 * discipline name wherever a result is displayed. IDPA is the one discipline
 * in the catalogue where a bigger number is worse, and a flag on the result
 * is the only way to stop every display site from having to remember that.
 */
data class ScoringResult(
    val shots: List<Shot>,
    val series: List<ScoredSeries>,
    val total: Double,
    val displayTotal: String,
    val innerTens: Int,
    val misses: Int,
    val maxScore: Double,
    val lowerIsBetter: Boolean,
    /** Hit factor for IPSC, seconds for IDPA, otherwise 0. */
    val derivedFigure: Double = 0.0,
    val derivedFigureLabel: String = "",
    val warnings: List<String> = emptyList()
) {
    val scoredShots: List<Shot> get() = shots.filter { !it.sighter }
    val percentOfMax: Double get() = if (maxScore > 0) 100.0 * total / maxScore else 0.0
}
