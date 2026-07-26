package com.rfsat.sts.rules

/**
 * ============================================================================
 *  COMPETITION RULES
 * ============================================================================
 *
 * A [TargetFace] says where the rings are. A [RuleSet] says what to do with
 * a hole once you have found one: which gauge decides a marginal call,
 * whether the value is a whole number or a tenth, how many shots make a
 * series, what the maximum is, and how the shots aggregate into a result.
 *
 * The two are deliberately separate. Poland, Germany and the ISSF all shoot
 * the SAME 10 m air-rifle face; what differs is the course of fire and the
 * classification table. Modelling the national federations as extra target
 * faces — the obvious first design — would have duplicated the geometry
 * three times and made a correction to the ring pitch a three-place edit.
 */

/** How individual shot values combine into a result. */
enum class MatchScoring(val label: String) {
    /** Precision disciplines: the result is the sum of the shot values. */
    SUM_OF_SHOTS("Sum of shot values"),

    /** IPSC and USPSA: hit factor = total points / total time in seconds.
     *  The stage winner's hit factor sets the scale for everyone else. */
    HIT_FACTOR("Hit factor (points / time)"),

    /** IDPA: raw time plus one second per point down, plus procedurals.
     *  LOWER is better, which is the one place in the app where that is
     *  true — [ScoringResult.lowerIsBetter] carries the flag so no display
     *  code has to special-case the discipline by name. */
    TIME_PLUS_PENALTY("Time plus points down"),

    /** PRS, NRL22 and steel practice: count the impacts. */
    HIT_COUNT("Impact count")
}

/** Firing position, recorded on the session report. */
enum class Position(val label: String) {
    STANDING("Standing"),
    PRONE("Prone"),
    KNEELING("Kneeling"),
    THREE_POSITION("3 positions"),
    FREESTYLE("Freestyle / any"),
    UNSUPPORTED("Unsupported"),
    BIPOD("Bipod / rest"),
    POSITIONAL("Positional (stage-defined)")
}

/**
 * Standard scoring-gauge diameters, millimetres.
 *
 * The gauge is what a jury physically pushes into the hole, and it is fixed
 * by the CALIBRE CLASS, not by the bullet actually fired: ISSF specifies
 * 4.5 mm for air, 5.6 mm for .22 rimfire, and 7.62 / 7.65 / 9.65 mm for the
 * centrefire classes. A .223 shot on a 300 m target is gauged at 7.62 mm
 * even though the bullet measures 5.69 mm, because that is what the rulebook
 * says — and scoring it at the bullet diameter would cost the shooter points
 * they are entitled to on every marginal call.
 *
 * This is why [RuleSet.gaugeDiameterMm] exists at all and why the scoring
 * engine never reads the bullet diameter directly.
 */
object Gauge {
    const val AIR_4_5 = 4.5
    const val RIMFIRE_5_6 = 5.6
    const val CENTREFIRE_7_62 = 7.62
    const val CENTREFIRE_7_65 = 7.65
    const val PISTOL_9_65 = 9.65

    /** Fallback when no rule set applies: gauge at the projectile itself. */
    fun fromBulletDiameterMm(mm: Double): Double = mm
}

/**
 * One competition, or one recognised course of fire within a competition.
 *
 * PROVENANCE, as with the target catalogue: [verified] is true only for the
 * ISSF entries, whose courses of fire are fixed by a published rule number
 * that is quoted in [ruleReference]. National federation and American
 * entries are the commonly published figures and are marked for checking.
 * Every field is editable and a user edit is saved as a custom copy.
 */
data class RuleSet(
    val id: String,
    val name: String,
    /** "ISSF", "NRA", "CMP", "ICFRA", "IPSC", "IDPA", "PRS", "NRL22",
     *  "PZSS", "DSB", "BDS", or "Custom". */
    val governingBody: String,
    val discipline: String,
    /** Id of the default [com.rfsat.sts.targets.TargetFace] for this course.
     *  The user can override it on the session screen — reduced-scale
     *  practice faces are the normal reason. */
    val targetFaceId: String,
    val distanceM: Double,
    val positionName: String = Position.STANDING.name,

    // ---- course of fire ----
    /** Competition shots, excluding sighters. 0 = stage-defined (practical). */
    val matchShots: Int = 60,
    /** Shots per scored series. Series boundaries drive the running total
     *  breakdown on the results screen. 0 = one continuous string. */
    val shotsPerSeries: Int = 10,
    /** Sighting shots allowed. -1 = unlimited within the preparation time,
     *  which is the ISSF 60-shot convention. */
    val sighters: Int = -1,
    /** Total time for the match, seconds. 0 = no limit. */
    val timeLimitSec: Int = 0,
    /** Per-series time for the rapid disciplines, seconds. 0 = not used. */
    val seriesTimeLimitSec: Int = 0,

    // ---- how a hole becomes a number ----
    val gaugeDiameterMm: Double = Gauge.AIR_4_5,
    /** Score to a tenth. Only legal where the face has evenly pitched rings;
     *  [com.rfsat.sts.targets.TargetFace.scoreDecimal] returns null otherwise
     *  and the engine falls back to integer scoring with a warning. */
    val decimalScoring: Boolean = false,
    /** Tally inner tens / X-count separately, for tie-breaks. */
    val countInnerTens: Boolean = true,
    val matchScoring: MatchScoring = MatchScoring.SUM_OF_SHOTS,
    /** IPSC: the power factor at or above which MAJOR values apply. Bullet
     *  weight (gr) x MV (fps) / 1000; 0 = the discipline has no minor/major
     *  distinction. */
    val majorPowerFactor: Double = 0.0,
    /** Excess shots on a target are scored as the LOWEST values present and
     *  the surplus deducted — the ISSF convention. When false, extra shots
     *  are simply flagged. */
    val penaliseExcessShots: Boolean = true,

    // ---- reporting ----
    /** Maximum attainable score, for the "x out of y" line. Computed from
     *  the course when 0. */
    val maxScoreOverride: Double = 0.0,
    /** Ordered tie-break criteria, best first. */
    val tieBreak: List<String> = listOf("Inner tens", "Last series", "Countback"),
    val ruleReference: String = "",
    val verified: Boolean = false,
    val custom: Boolean = false,
    val notes: String = ""
) {
    val position: Position
        get() = runCatching { Position.valueOf(positionName) }.getOrDefault(Position.STANDING)

    val gaugeRadiusMm: Double get() = gaugeDiameterMm / 2.0

    val seriesCount: Int
        get() = if (shotsPerSeries > 0 && matchShots > 0) matchShots / shotsPerSeries else 1

    /** Maximum attainable score for the whole course. */
    fun maxScore(): Double {
        if (maxScoreOverride > 0.0) return maxScoreOverride
        return when (matchScoring) {
            MatchScoring.SUM_OF_SHOTS ->
                matchShots * if (decimalScoring) 10.9 else 10.0
            MatchScoring.HIT_COUNT -> matchShots.toDouble()
            // A hit factor has no ceiling, and IDPA's "score" is a time.
            MatchScoring.HIT_FACTOR, MatchScoring.TIME_PLUS_PENALTY -> 0.0
        }
    }

    /** True where a LOWER result is the better one. */
    val lowerIsBetter: Boolean get() = matchScoring == MatchScoring.TIME_PLUS_PENALTY

    fun summary(): String {
        val t = when {
            timeLimitSec > 0 -> ", ${timeLimitSec / 60} min"
            seriesTimeLimitSec > 0 -> ", ${seriesTimeLimitSec}s per series"
            else -> ""
        }
        val shots = if (matchShots > 0) "$matchShots shots" else "stage-defined"
        return "$governingBody — $shots @ ${distanceM.toInt()} m, ${position.label}$t"
    }
}
