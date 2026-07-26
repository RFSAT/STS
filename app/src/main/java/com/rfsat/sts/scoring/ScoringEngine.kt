package com.rfsat.sts.scoring

import com.rfsat.sts.detect.DetectedHole
import com.rfsat.sts.profiles.BulletProfile
import com.rfsat.sts.rules.MatchScoring
import com.rfsat.sts.rules.RuleSet
import com.rfsat.sts.targets.ScoringMode
import com.rfsat.sts.targets.TargetFace

/**
 * ============================================================================
 *  SCORING
 * ============================================================================
 *
 * Turns positions into a result. The geometry lives in [TargetFace] and the
 * conventions in [RuleSet]; this object is the join between them, plus the
 * aggregation rules that only make sense once you have the whole string.
 *
 * WHAT THE ENGINE WILL NOT DO. It will not silently substitute one scoring
 * convention for another. If a rule set asks for decimal scoring on a face
 * whose rings are not evenly pitched, the engine scores integers and SAYS SO
 * in the warnings — because a decimal figure derived from an assumed pitch
 * would look exactly like a real one and be wrong by up to a full point.
 */
object ScoringEngine {

    /**
     * Scores one hole.
     *
     * The gauge comes from the rule set, not from the bullet: see the note on
     * [com.rfsat.sts.rules.Gauge] for why that distinction is worth the extra
     * parameter. [bullet] is consulted only as a last resort, when a session
     * is being scored with no rule set at all.
     */
    fun scoreHole(
        hole: DetectedHole,
        face: TargetFace,
        rules: RuleSet?,
        bullet: BulletProfile?,
        index: Int,
        series: Int = 1,
        sighter: Boolean = false,
        manual: Boolean = false
    ): Shot {
        val gaugeRadius = (rules?.gaugeRadiusMm
            ?: bullet?.diameterMm?.div(2.0)
            ?: 2.25)
        val d = hole.distanceFromCentreMm

        return when (face.scoringMode) {
            ScoringMode.HIT_MISS -> {
                val hit = face.scoreInteger(d, gaugeRadius) > 0
                Shot(
                    index = index, xMm = hole.xMm, yMm = hole.yMm,
                    value = if (hit) 1.0 else 0.0,
                    displayValue = if (hit) "HIT" else "MISS",
                    timestampMs = System.currentTimeMillis(),
                    confidence = hole.confidence, manual = manual,
                    sighter = sighter, series = series, miss = !hit
                )
            }

            ScoringMode.ZONE_POINTS -> {
                val zone = face.zoneAt(hole.xMm, hole.yMm, gaugeRadius)
                val major = rules != null && rules.majorPowerFactor > 0.0 &&
                    (bullet?.powerFactor ?: 0.0) >= rules.majorPowerFactor
                val pts = when {
                    zone == null -> 0.0
                    major -> zone.majorPoints
                    else -> zone.minorPoints
                }
                Shot(
                    index = index, xMm = hole.xMm, yMm = hole.yMm,
                    value = pts,
                    displayValue = zone?.name ?: "M",
                    zoneName = zone?.name ?: "",
                    confidence = hole.confidence, manual = manual,
                    sighter = sighter, series = series, miss = zone == null
                )
            }

            ScoringMode.RING_INTEGER, ScoringMode.RING_DECIMAL -> {
                val wantDecimal = rules?.decimalScoring
                    ?: (face.scoringMode == ScoringMode.RING_DECIMAL)
                val decimal = if (wantDecimal) face.scoreDecimal(d, gaugeRadius) else null
                val integer = face.scoreInteger(d, gaugeRadius)
                val inner = face.isInnerTen(d, gaugeRadius)
                val value = decimal ?: integer.toDouble()
                Shot(
                    index = index, xMm = hole.xMm, yMm = hole.yMm,
                    value = value,
                    displayValue = when {
                        integer == 0 -> "M"
                        decimal != null -> "%.1f".format(decimal)
                        inner && face.innerTenLabel == "X" -> "X"
                        else -> integer.toString()
                    },
                    innerTen = inner,
                    confidence = hole.confidence, manual = manual,
                    sighter = sighter, series = series, miss = integer == 0
                )
            }
        }
    }

    /**
     * Aggregates scored shots into a result.
     *
     * [elapsedSeconds] is required by the time-based disciplines and ignored
     * by the rest; pass 0 when it is not known.
     */
    fun aggregate(
        shots: List<Shot>,
        face: TargetFace,
        rules: RuleSet,
        elapsedSeconds: Double = 0.0
    ): ScoringResult {
        val warnings = mutableListOf<String>()
        val scored = shots.filter { !it.sighter }

        // ---- decimal request the face cannot honour ----
        if (rules.decimalScoring && face.ringPitchMm == null && face.rings.isNotEmpty()) {
            warnings += "The rule set asks for decimal scoring, but the ${face.name} face does not " +
                "have evenly pitched rings, so a decimal value is not defined for it. Scored as " +
                "whole rings."
        }

        // ---- excess shots ----
        if (rules.matchShots > 0 && scored.size > rules.matchShots) {
            val excess = scored.size - rules.matchShots
            warnings += if (rules.penaliseExcessShots)
                "$excess shot(s) more than the course of fire allows. Under the usual convention " +
                    "the LOWEST values on the target are the ones annulled; the total below applies " +
                    "that, but a jury may rule differently."
            else
                "$excess shot(s) more than the course of fire nominally allows; none were annulled."
        }

        // The shots that actually count. When there are too many and the rule
        // set penalises them, keep the best [matchShots] — which is the same
        // thing as annulling the lowest, and is the convention that does not
        // depend on knowing which physical hole arrived last.
        val counting = if (rules.penaliseExcessShots && rules.matchShots > 0 && scored.size > rules.matchShots)
            scored.sortedByDescending { it.value }.take(rules.matchShots)
        else scored

        val innerTens = if (rules.countInnerTens) counting.count { it.innerTen } else 0
        val misses = counting.count { it.miss }

        // ---- series breakdown ----
        val series = if (rules.shotsPerSeries > 0) {
            counting.sortedBy { it.index }
                .chunked(rules.shotsPerSeries)
                .mapIndexed { i, chunk ->
                    val t = chunk.sumOf { it.value }
                    ScoredSeries(
                        number = i + 1,
                        shots = chunk,
                        total = t,
                        innerTens = chunk.count { it.innerTen },
                        displayTotal = formatTotal(t, rules)
                    )
                }
        } else {
            val t = counting.sumOf { it.value }
            listOf(ScoredSeries(1, counting, t, innerTens, formatTotal(t, rules)))
        }

        val rawPoints = counting.sumOf { it.value }

        // ---- match-level aggregation ----
        var total = rawPoints
        var derived = 0.0
        var derivedLabel = ""

        when (rules.matchScoring) {
            MatchScoring.SUM_OF_SHOTS, MatchScoring.HIT_COUNT -> Unit

            MatchScoring.HIT_FACTOR -> {
                if (elapsedSeconds > 0.0) {
                    derived = rawPoints / elapsedSeconds
                    derivedLabel = "Hit factor"
                } else {
                    warnings += "Hit factor needs a stage time. Enter the time on the Results screen " +
                        "to get one; the points total is shown meanwhile."
                }
            }

            MatchScoring.TIME_PLUS_PENALTY -> {
                // Zone values are stored negative (points down), so the
                // penalty is their magnitude. See PracticalGeometry.
                val down = -counting.sumOf { minOf(0.0, it.value) }
                derived = elapsedSeconds + down
                derivedLabel = "Total time (s)"
                total = derived
                if (elapsedSeconds <= 0.0) {
                    warnings += "IDPA scoring is raw time plus one second per point down. Without a " +
                        "stage time only the ${"%.0f".format(down)} s of penalties can be shown."
                }
            }
        }

        // ---- low-confidence flag ----
        val doubtful = counting.count { !it.manual && it.confidence < 0.4 }
        if (doubtful > 0) {
            warnings += "$doubtful shot(s) were detected with low confidence. Check them on the plot " +
                "before relying on this total."
        }

        val maxScore = rules.maxScore()
        if (rules.matchShots > 0 && counting.size < rules.matchShots) {
            warnings += "${rules.matchShots - counting.size} shot(s) of the course of fire are still " +
                "to be fired."
        }

        return ScoringResult(
            shots = shots,
            series = series,
            total = total,
            displayTotal = formatTotal(total, rules),
            innerTens = innerTens,
            misses = misses,
            maxScore = maxScore,
            lowerIsBetter = rules.lowerIsBetter,
            derivedFigure = derived,
            derivedFigureLabel = derivedLabel,
            warnings = warnings
        )
    }

    /** Whole numbers for integer disciplines, one decimal where the rules ask
     *  for tenths, two for a hit factor. Formatting lives here so that a
     *  total and a series subtotal can never disagree about it. */
    fun formatTotal(value: Double, rules: RuleSet): String = when {
        rules.matchScoring == MatchScoring.HIT_FACTOR -> "%.4f".format(value)
        rules.matchScoring == MatchScoring.TIME_PLUS_PENALTY -> "%.2f s".format(value)
        rules.decimalScoring -> "%.1f".format(value)
        else -> "%.0f".format(value)
    }

    /** Which series a shot with this 1-based index falls into. */
    fun seriesFor(index: Int, rules: RuleSet): Int =
        if (rules.shotsPerSeries <= 0) 1 else (index - 1) / rules.shotsPerSeries + 1
}
