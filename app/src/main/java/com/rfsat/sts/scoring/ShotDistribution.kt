package com.rfsat.sts.scoring

import com.rfsat.sts.rules.RuleSet
import com.rfsat.sts.targets.ScoringMode
import com.rfsat.sts.targets.TargetFace
import kotlin.math.sqrt

/** One bar of the distribution: a score value and how many shots took it. */
data class ScoreBucket(
    /** What the shooter calls it: "10", "9", "A", "-1", "hit", "miss". */
    val label: String,
    val count: Int,
    /** Points a shot in this bucket is worth, for the mean. */
    val value: Double,
    /** True for the miss bucket, which is drawn differently. */
    val isMiss: Boolean = false
) {
    fun percentOf(total: Int): Double = if (total > 0) 100.0 * count / total else 0.0
}

/**
 * ============================================================================
 *  THE DISTRIBUTION OF A STRING
 * ============================================================================
 *
 * A total tells you what happened. The distribution tells you what KIND of
 * shooting produced it, and the two can differ sharply: 95 out of 100 as ten
 * 9s and 10s is a shooter who needs a sight correction, while the same 95 as
 * eight 10s and two 7s is a shooter throwing the occasional flyer. The first
 * is fixed by turning a turret, the second by working on the shot process,
 * and the total alone cannot tell them apart.
 *
 * BUCKETING. Shots are grouped by the WHOLE NUMBER of points they scored,
 * because "how many tens did I shoot" is the question people actually ask.
 * That matters for decimal disciplines: a 10.9 and a 10.1 are very different
 * shots but they both go in the "10" bucket, and the mean — which is computed
 * from the true decimal values, not the bucket labels — is where the
 * difference between them shows up. Reporting a bar per tenth would produce
 * a hundred buckets of mostly zero and answer nobody's question.
 *
 * Every value the face can produce gets a bucket, INCLUDING the ones with no
 * shots in them. A histogram with the empty rings silently dropped hides the
 * shape of the distribution, which is the only thing it exists to show.
 *
 * Sighters are excluded throughout: they are not part of the result and
 * including them would flatter it.
 */
data class ShotDistribution(
    /** Best-first, and complete: buckets with a zero count are kept. */
    val buckets: List<ScoreBucket>,
    val shotCount: Int,
    /** Mean of the TRUE shot values, decimals included. */
    val mean: Double,
    /** Sample standard deviation of those values. */
    val sd: Double,
    val innerTens: Int,
    val innerTenLabel: String,
    val misses: Int,
    val bestLabel: String,
    val worstLabel: String,
    /** Label of the most frequent bucket; empty when there are no shots. */
    val modeLabel: String,
    /** Largest count in any bucket — the axis scale for the histogram. */
    val peak: Int
) {
    val isEmpty: Boolean get() = shotCount == 0

    /** One-line summary for the report and the screen. */
    fun summary(): String {
        if (isEmpty) return "No shots to summarise."
        val inner = if (innerTens > 0) ", $innerTens $innerTenLabel" else ""
        return "%d shots, mean %.2f, SD %.2f, best %s, worst %s%s".format(
            shotCount, mean, sd, bestLabel, worstLabel, inner
        )
    }

    companion object {

        val EMPTY = ShotDistribution(
            emptyList(), 0, 0.0, 0.0, 0, "inner 10", 0, "—", "—", "", 0
        )

        fun of(shots: List<Shot>, face: TargetFace, rules: RuleSet?): ShotDistribution {
            val scored = shots.filter { !it.sighter }
            if (scored.isEmpty()) return EMPTY

            // The full set of labels the face can produce, best first. Built
            // from the FACE rather than from the shots, so a ring nobody hit
            // still appears as an empty bar.
            val template: List<Pair<String, Double>> = when (face.scoringMode) {
                ScoringMode.HIT_MISS -> listOf("hit" to 1.0)

                ScoringMode.ZONE_POINTS -> {
                    val major = rules != null && rules.majorPowerFactor > 0.0
                    face.zones
                        .sortedByDescending { if (major) it.majorPoints else it.minorPoints }
                        .map { z -> z.name to (if (major) z.majorPoints else z.minorPoints) }
                }

                ScoringMode.RING_INTEGER, ScoringMode.RING_DECIMAL ->
                    face.rings.sortedByDescending { it.value }
                        .map { it.value.toString() to it.value.toDouble() }
            }

            // Bucket by the label the engine already assigned where it is a
            // plain ring number, and by the whole-number value otherwise.
            // Reading the label back is what keeps a decimal 10.4 and an
            // integer 10 in the same bucket without a second scoring pass.
            val counts = LinkedHashMap<String, Int>()
            template.forEach { (label, _) -> counts[label] = 0 }
            var misses = 0
            for (s in scored) {
                if (s.miss) { misses++; continue }
                val key = when (face.scoringMode) {
                    ScoringMode.HIT_MISS -> "hit"
                    ScoringMode.ZONE_POINTS -> s.zoneName.ifBlank { s.displayValue }
                    else -> Math.floor(s.value + 1e-9).toInt().toString()
                }
                counts[key] = (counts[key] ?: 0) + 1
            }

            val buckets = template.map { (label, value) ->
                ScoreBucket(label, counts[label] ?: 0, value)
            } + ScoreBucket("miss", misses, 0.0, isMiss = true)

            val values = scored.map { it.value }
            val mean = values.average()
            // Sample standard deviation. With one shot there is no dispersion
            // information and the honest answer is zero, not a division by
            // zero dressed up as a number.
            val sd = if (values.size > 1)
                sqrt(values.sumOf { (it - mean) * (it - mean) } / (values.size - 1))
            else 0.0

            val bestShot = scored.maxByOrNull { it.value }
            val worstShot = scored.minByOrNull { it.value }
            val mode = buckets.filter { it.count > 0 }.maxByOrNull { it.count }

            return ShotDistribution(
                buckets = buckets,
                shotCount = scored.size,
                mean = mean,
                sd = sd,
                innerTens = if (rules?.countInnerTens != false) scored.count { it.innerTen } else 0,
                innerTenLabel = face.innerTenLabel,
                misses = misses,
                bestLabel = bestShot?.displayValue ?: "—",
                worstLabel = worstShot?.displayValue ?: "—",
                modeLabel = mode?.label ?: "",
                peak = buckets.maxOfOrNull { it.count } ?: 0
            )
        }
    }
}
