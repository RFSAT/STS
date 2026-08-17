package com.rfsat.sts.rules

import com.rfsat.sts.targets.TargetCatalog

/**
 * ============================================================================
 *  BUILT-IN COMPETITION RULE SETS
 * ============================================================================
 *
 * Organised by governing body. As with the target catalogue, [RuleSet.verified]
 * distinguishes the entries taken from a published rule number (quoted in
 * [RuleSet.ruleReference]) from the commonly cited figures, and only the ISSF
 * block qualifies for the former.
 *
 * A NOTE ON THE EUROPEAN FEDERATIONS. PZSS, DSB and BDS shoot ISSF target
 * faces for the Olympic disciplines, so their entries here point at the ISSF
 * face ids. What is national is the course of fire — shot counts and time
 * limits differ, and the Auflage (rested) and Auflage-adjacent classes have
 * no ISSF equivalent at all. Those are the entries worth having.
 */
object RuleCatalog {

    // =====================================================================
    //  ISSF
    // =====================================================================

    val ISSF_AR60 = RuleSet(
        id = "issf_ar60",
        name = "10 m Air Rifle — 60 shots",
        governingBody = "ISSF",
        discipline = "Air Rifle",
        targetFaceId = TargetCatalog.ISSF_AR10.id,
        distanceM = 10.0,
        positionName = Position.STANDING.name,
        matchShots = 60, shotsPerSeries = 10, sighters = -1,
        timeLimitSec = 75 * 60,
        gaugeDiameterMm = Gauge.AIR_4_5,
        decimalScoring = true,
        countInnerTens = true,
        matchScoring = MatchScoring.SUM_OF_SHOTS,
        ruleReference = "ISSF Rules, 10 m Air Rifle qualification",
        verified = true,
        notes = "75 minutes including unlimited sighters within the preparation and sighting time. " +
            "Decimal scoring; maximum 654.0."
    )

    val ISSF_AP60 = RuleSet(
        id = "issf_ap60",
        name = "10 m Air Pistol — 60 shots",
        governingBody = "ISSF",
        discipline = "Air Pistol",
        targetFaceId = TargetCatalog.ISSF_AP10.id,
        distanceM = 10.0,
        positionName = Position.STANDING.name,
        matchShots = 60, shotsPerSeries = 10, sighters = -1,
        timeLimitSec = 75 * 60,
        gaugeDiameterMm = Gauge.AIR_4_5,
        decimalScoring = true,
        matchScoring = MatchScoring.SUM_OF_SHOTS,
        ruleReference = "ISSF Rules, 10 m Air Pistol qualification",
        verified = true,
        notes = "75 minutes. Decimal scoring; maximum 654.0."
    )

    val ISSF_AR_FINAL = RuleSet(
        id = "issf_ar_final",
        name = "10 m Air Rifle — Final",
        governingBody = "ISSF",
        discipline = "Air Rifle",
        targetFaceId = TargetCatalog.ISSF_AR10.id,
        distanceM = 10.0,
        matchShots = 24, shotsPerSeries = 2, sighters = -1,
        timeLimitSec = 0, seriesTimeLimitSec = 50,
        gaugeDiameterMm = Gauge.AIR_4_5,
        decimalScoring = true,
        matchScoring = MatchScoring.SUM_OF_SHOTS,
        ruleReference = "ISSF Rules, 10 m Air Rifle final",
        verified = true,
        notes = "Two five-shot series then single shots on command, with eliminations. " +
            "Modelled here as 24 shots in pairs so the running total tracks the broadcast score."
    )

    val ISSF_R50_PRONE = RuleSet(
        id = "issf_r50_prone",
        name = "50 m Rifle Prone — 60 shots",
        governingBody = "ISSF",
        discipline = "Rimfire Rifle",
        targetFaceId = TargetCatalog.ISSF_R50.id,
        distanceM = 50.0,
        positionName = Position.PRONE.name,
        matchShots = 60, shotsPerSeries = 10, sighters = -1,
        timeLimitSec = 50 * 60,
        gaugeDiameterMm = Gauge.RIMFIRE_5_6,
        decimalScoring = true,
        matchScoring = MatchScoring.SUM_OF_SHOTS,
        ruleReference = "ISSF Rules, 50 m Rifle Prone",
        verified = true,
        notes = "50 minutes. Decimal scoring on electronic targets, integer on paper."
    )

    val ISSF_R50_3P = RuleSet(
        id = "issf_r50_3x40",
        name = "50 m Rifle 3 Positions — 3x40",
        governingBody = "ISSF",
        discipline = "Rimfire Rifle",
        targetFaceId = TargetCatalog.ISSF_R50.id,
        distanceM = 50.0,
        positionName = Position.THREE_POSITION.name,
        matchShots = 120, shotsPerSeries = 10, sighters = -1,
        timeLimitSec = 165 * 60,
        gaugeDiameterMm = Gauge.RIMFIRE_5_6,
        decimalScoring = false,
        matchScoring = MatchScoring.SUM_OF_SHOTS,
        ruleReference = "ISSF Rules, 50 m Rifle 3 Positions",
        verified = true,
        notes = "Kneeling, prone, standing — 40 shots each. 2 h 45 min total including changeovers. " +
            "Integer scoring in qualification; maximum 1200."
    )

    val ISSF_P50 = RuleSet(
        id = "issf_p50",
        name = "50 m Pistol — 60 shots",
        governingBody = "ISSF",
        discipline = "Rimfire Pistol",
        targetFaceId = TargetCatalog.ISSF_P25_PRECISION.id,
        distanceM = 50.0,
        matchShots = 60, shotsPerSeries = 10, sighters = -1,
        timeLimitSec = 120 * 60,
        gaugeDiameterMm = Gauge.RIMFIRE_5_6,
        decimalScoring = false,
        matchScoring = MatchScoring.SUM_OF_SHOTS,
        ruleReference = "ISSF Rules, 50 m Pistol",
        verified = true,
        notes = "Free Pistol. Maximum 600."
    )

    val ISSF_P25_SPORT = RuleSet(
        id = "issf_p25_sport",
        name = "25 m Pistol — 30 precision + 30 rapid",
        governingBody = "ISSF",
        discipline = "Rimfire Pistol",
        targetFaceId = TargetCatalog.ISSF_P25_PRECISION.id,
        distanceM = 25.0,
        matchShots = 60, shotsPerSeries = 5, sighters = 5,
        timeLimitSec = 0, seriesTimeLimitSec = 300,
        gaugeDiameterMm = Gauge.RIMFIRE_5_6,
        decimalScoring = false,
        matchScoring = MatchScoring.SUM_OF_SHOTS,
        ruleReference = "ISSF Rules, 25 m Pistol",
        verified = true,
        notes = "Precision stage: six 5-shot series, 5 minutes each. Rapid stage: six 5-shot " +
            "series, 3 seconds per shot. Maximum 600."
    )

    val ISSF_P25_RAPIDFIRE = RuleSet(
        id = "issf_p25_rapidfire",
        name = "25 m Rapid Fire Pistol — 60 shots",
        governingBody = "ISSF",
        discipline = "Rimfire Pistol",
        targetFaceId = TargetCatalog.ISSF_P25_RAPID.id,
        distanceM = 25.0,
        matchShots = 60, shotsPerSeries = 5, sighters = 5,
        timeLimitSec = 0, seriesTimeLimitSec = 8,
        gaugeDiameterMm = Gauge.RIMFIRE_5_6,
        decimalScoring = false,
        matchScoring = MatchScoring.SUM_OF_SHOTS,
        ruleReference = "ISSF Rules, 25 m Rapid Fire Pistol",
        verified = true,
        notes = "Twelve 5-shot series across five targets: four series each at 8, 6 and 4 seconds. " +
            "The series time here is the slowest stage; edit per stage. Maximum 600."
    )

    val ISSF_P25_CENTREFIRE = RuleSet(
        id = "issf_p25_centrefire",
        name = "25 m Centre Fire Pistol — 60 shots",
        governingBody = "ISSF",
        discipline = "Centrefire Pistol",
        targetFaceId = TargetCatalog.ISSF_P25_PRECISION.id,
        distanceM = 25.0,
        matchShots = 60, shotsPerSeries = 5, sighters = 5,
        seriesTimeLimitSec = 300,
        gaugeDiameterMm = Gauge.CENTREFIRE_7_62,
        decimalScoring = false,
        matchScoring = MatchScoring.SUM_OF_SHOTS,
        ruleReference = "ISSF Rules, 25 m Centre Fire Pistol",
        verified = true,
        notes = "Calibres .30 to .38. Note the 7.62 mm gauge — a marginal call is decided with " +
            "the centrefire gauge, not the bullet diameter."
    )

    val ISSF_R300_PRONE = RuleSet(
        id = "issf_r300_prone",
        name = "300 m Rifle Prone — 60 shots",
        governingBody = "ISSF",
        discipline = "Centrefire Rifle",
        targetFaceId = TargetCatalog.ISSF_R300.id,
        distanceM = 300.0,
        positionName = Position.PRONE.name,
        matchShots = 60, shotsPerSeries = 10, sighters = -1,
        timeLimitSec = 60 * 60,
        gaugeDiameterMm = Gauge.CENTREFIRE_7_62,
        decimalScoring = false,
        matchScoring = MatchScoring.SUM_OF_SHOTS,
        ruleReference = "ISSF Rules, 300 m Rifle Prone",
        verified = true,
        notes = "The ISSF home of .223 and .308 target shooting. Maximum 600."
    )

    // =====================================================================
    //  NRA / CMP
    // =====================================================================

    val NRA_NMC = RuleSet(
        id = "nra_national_match_course",
        name = "NRA National Match Course — 50 shots",
        governingBody = "NRA",
        discipline = "Centrefire Rifle",
        targetFaceId = TargetCatalog.NRA_SR_200.id,
        distanceM = 182.88,
        positionName = Position.THREE_POSITION.name,
        matchShots = 50, shotsPerSeries = 10, sighters = 2,
        gaugeDiameterMm = Gauge.CENTREFIRE_7_62,
        decimalScoring = false,
        matchScoring = MatchScoring.SUM_OF_SHOTS,
        tieBreak = listOf("X count", "Last stage", "Countback"),
        verified = false,
        notes = "10 slow standing at 200, 10 rapid sitting at 200, 10 rapid prone at 300, " +
            "20 slow prone at 600. Modelled at the 200 yd face; change the face per stage. " +
            "Maximum 500-50X."
    )

    val NRA_SMALLBORE_PRONE = RuleSet(
        id = "nra_smallbore_prone_40",
        name = "NRA Smallbore Prone — 40 shots",
        governingBody = "NRA",
        discipline = "Rimfire Rifle",
        targetFaceId = TargetCatalog.NRA_A23_50YD.id,
        distanceM = 45.72,
        positionName = Position.PRONE.name,
        matchShots = 40, shotsPerSeries = 10, sighters = -1,
        timeLimitSec = 30 * 60,
        gaugeDiameterMm = Gauge.RIMFIRE_5_6,
        matchScoring = MatchScoring.SUM_OF_SHOTS,
        tieBreak = listOf("X count", "Countback"),
        verified = false,
        notes = "Maximum 400-40X."
    )

    val CMP_GAMES = RuleSet(
        id = "cmp_games_rifle",
        name = "CMP Games Rifle Match — 30 shots",
        governingBody = "CMP",
        discipline = "Centrefire Rifle",
        targetFaceId = TargetCatalog.NRA_SR_200.id,
        distanceM = 182.88,
        positionName = Position.THREE_POSITION.name,
        matchShots = 30, shotsPerSeries = 10, sighters = 5,
        gaugeDiameterMm = Gauge.CENTREFIRE_7_62,
        matchScoring = MatchScoring.SUM_OF_SHOTS,
        verified = false,
        notes = "5 sighters plus 30 for record at 200 yards. Maximum 300."
    )

    // =====================================================================
    //  F-Class (ICFRA / NRA)
    // =====================================================================

    val FCLASS_600 = RuleSet(
        id = "fclass_600_20",
        name = "F-Class 600 yd — 20 shots",
        governingBody = "ICFRA",
        discipline = "Centrefire Rifle",
        targetFaceId = TargetCatalog.FCLASS_600.id,
        distanceM = 548.64,
        positionName = Position.BIPOD.name,
        matchShots = 20, shotsPerSeries = 20, sighters = 2,
        timeLimitSec = 22 * 60,
        gaugeDiameterMm = Gauge.CENTREFIRE_7_62,
        matchScoring = MatchScoring.SUM_OF_SHOTS,
        tieBreak = listOf("X count", "Countback"),
        verified = false,
        notes = "F-Open and F-TR shoot the same face; F-TR is restricted to .223 and .308 " +
            "from a bipod. Maximum 200-20X."
    )

    val FCLASS_1000 = RuleSet(
        id = "fclass_1000_20",
        name = "F-Class 1000 yd — 20 shots",
        governingBody = "ICFRA",
        discipline = "Centrefire Rifle",
        targetFaceId = TargetCatalog.FCLASS_1000.id,
        distanceM = 914.4,
        positionName = Position.BIPOD.name,
        matchShots = 20, shotsPerSeries = 20, sighters = 2,
        timeLimitSec = 25 * 60,
        gaugeDiameterMm = Gauge.CENTREFIRE_7_62,
        matchScoring = MatchScoring.SUM_OF_SHOTS,
        tieBreak = listOf("X count", "Countback"),
        verified = false,
        notes = "Maximum 200-20X."
    )

    // =====================================================================
    //  Practical rifle: PRS / NRL22
    // =====================================================================

    val NRL22_BASE = RuleSet(
        id = "nrl22_base_stage",
        name = "NRL22 Base stage — 10 rounds",
        governingBody = "NRL22",
        discipline = "Rimfire Rifle",
        targetFaceId = TargetCatalog.STEEL_2MOA_100.id,
        distanceM = 100.0,
        positionName = Position.POSITIONAL.name,
        matchShots = 10, shotsPerSeries = 10, sighters = 0,
        timeLimitSec = 120,
        gaugeDiameterMm = Gauge.RIMFIRE_5_6,
        countInnerTens = false,
        matchScoring = MatchScoring.HIT_COUNT,
        tieBreak = listOf("Time remaining"),
        verified = false,
        notes = "One point per impact, 120 seconds. Distances and plate sizes are set by the " +
            "monthly course of fire; edit the target face to match."
    )

    val PRS_STAGE = RuleSet(
        id = "prs_stage",
        name = "PRS stage — 10 rounds",
        governingBody = "PRS",
        discipline = "Centrefire Rifle",
        targetFaceId = TargetCatalog.STEEL_IPSC_FULL.id,
        distanceM = 400.0,
        positionName = Position.POSITIONAL.name,
        matchShots = 10, shotsPerSeries = 10, sighters = 0,
        timeLimitSec = 120,
        gaugeDiameterMm = Gauge.CENTREFIRE_7_62,
        countInnerTens = false,
        matchScoring = MatchScoring.HIT_COUNT,
        verified = false,
        notes = "Impact count within the par time. Stage points are usually normalised against " +
            "the stage winner at the match level, which is outside what one shooter's phone can see."
    )

    // =====================================================================
    //  Practical pistol: IPSC / IDPA
    // =====================================================================

    val IPSC_COMSTOCK = RuleSet(
        id = "ipsc_comstock",
        name = "IPSC Comstock stage",
        governingBody = "IPSC",
        discipline = "Practical Pistol",
        targetFaceId = TargetCatalog.IPSC_CLASSIC.id,
        distanceM = 15.0,
        positionName = Position.FREESTYLE.name,
        matchShots = 0, shotsPerSeries = 0, sighters = 0,
        timeLimitSec = 0,
        gaugeDiameterMm = Gauge.PISTOL_9_65,
        countInnerTens = false,
        matchScoring = MatchScoring.HIT_FACTOR,
        majorPowerFactor = 320.0,
        penaliseExcessShots = false,
        tieBreak = listOf("Hit factor", "Raw time"),
        verified = false,
        notes = "Unlimited shots, best two per target count. Minor A5 C3 D1, Major A5 C4 D2; " +
            "the app picks the column from the load's power factor against the 320 threshold. " +
            "Misses and procedurals are entered by hand — a camera cannot see a shot that " +
            "went into the berm."
    )

    val IDPA_STAGE = RuleSet(
        id = "idpa_stage",
        name = "IDPA stage",
        governingBody = "IDPA",
        discipline = "Practical Pistol",
        targetFaceId = TargetCatalog.IDPA_TARGET.id,
        distanceM = 10.0,
        positionName = Position.FREESTYLE.name,
        matchShots = 0, shotsPerSeries = 0, sighters = 0,
        gaugeDiameterMm = Gauge.PISTOL_9_65,
        countInnerTens = false,
        matchScoring = MatchScoring.TIME_PLUS_PENALTY,
        penaliseExcessShots = false,
        tieBreak = listOf("Total time"),
        verified = false,
        notes = "Raw time plus one second per point down. LOWER is better — the only discipline " +
            "in the catalogue where that is true."
    )

    // =====================================================================
    //  Poland — PZSS
    //
    //  The Olympic disciplines are ISSF and are covered above. What is
    //  distinctly Polish is the short classification course used for the
    //  patent strzelecki and for club classification, shot on ISSF faces.
    // =====================================================================

    private fun pzss(
        id: String, name: String, discipline: String, faceId: String,
        distance: Double, shots: Int, gauge: Double, timeSec: Int, note: String
    ) = RuleSet(
        id = id, name = name, governingBody = "PZSS", discipline = discipline,
        targetFaceId = faceId, distanceM = distance,
        matchShots = shots, shotsPerSeries = shots, sighters = 3,
        timeLimitSec = timeSec,
        gaugeDiameterMm = gauge,
        decimalScoring = false,
        matchScoring = MatchScoring.SUM_OF_SHOTS,
        verified = false,
        notes = note
    )

    val PZSS_PN10 = pzss(
        "pzss_pn10", "Pn-10 — pistolet pneumatyczny, 10 strzałów", "Air Pistol",
        TargetCatalog.ISSF_AP10.id, 10.0, 10, Gauge.AIR_4_5, 15 * 60,
        "Classification course on the ISSF air pistol face. Maximum 100. " +
            "Verify shot count and time against the current PZSS regulations."
    )
    val PZSS_KPN10 = pzss(
        "pzss_kpn10", "Kpn-10 — karabin pneumatyczny, 10 strzałów", "Air Rifle",
        TargetCatalog.ISSF_AR10.id, 10.0, 10, Gauge.AIR_4_5, 15 * 60,
        "Classification course on the ISSF air rifle face. Maximum 100."
    )
    val PZSS_PSP20 = pzss(
        "pzss_psp20", "Psp-20 — pistolet sportowy, 20 strzałów", "Rimfire Pistol",
        TargetCatalog.ISSF_P25_PRECISION.id, 25.0, 20, Gauge.RIMFIRE_5_6, 30 * 60,
        "Maximum 200."
    )
    val PZSS_KSP20 = pzss(
        "pzss_ksp20", "Ksp-20 — karabin sportowy, 20 strzałów", "Rimfire Rifle",
        TargetCatalog.ISSF_R50.id, 50.0, 20, Gauge.RIMFIRE_5_6, 30 * 60,
        "Maximum 200."
    )
    val PZSS_PCZ20 = pzss(
        "pzss_pcz20", "Pcz-20 — pistolet centralnego zapłonu, 20 strzałów", "Centrefire Pistol",
        TargetCatalog.ISSF_P25_PRECISION.id, 25.0, 20, Gauge.CENTREFIRE_7_62, 30 * 60,
        "Maximum 200."
    )

    // =====================================================================
    //  Germany — DSB / BDS
    //
    //  The Auflage (rested) classes are the genuinely national ones and have
    //  no ISSF equivalent. They are shot on ISSF faces but score only the
    //  inner rings, because a rested rifle makes the outer rings trivial.
    // =====================================================================

    val DSB_LG40 = RuleSet(
        id = "dsb_lg_40",
        name = "DSB 1.10 Luftgewehr — 40 Schuss",
        governingBody = "DSB",
        discipline = "Air Rifle",
        targetFaceId = TargetCatalog.ISSF_AR10.id,
        distanceM = 10.0,
        matchShots = 40, shotsPerSeries = 10, sighters = -1,
        timeLimitSec = 75 * 60,
        gaugeDiameterMm = Gauge.AIR_4_5,
        matchScoring = MatchScoring.SUM_OF_SHOTS,
        verified = false,
        notes = "Standing, ISSF air rifle face. Maximum 400. Verify against the current Sportordnung."
    )

    val DSB_LP40 = RuleSet(
        id = "dsb_lp_40",
        name = "DSB 2.10 Luftpistole — 40 Schuss",
        governingBody = "DSB",
        discipline = "Air Pistol",
        targetFaceId = TargetCatalog.ISSF_AP10.id,
        distanceM = 10.0,
        matchShots = 40, shotsPerSeries = 10, sighters = -1,
        timeLimitSec = 75 * 60,
        gaugeDiameterMm = Gauge.AIR_4_5,
        matchScoring = MatchScoring.SUM_OF_SHOTS,
        verified = false,
        notes = "Maximum 400."
    )

    val DSB_LG_AUFLAGE = RuleSet(
        id = "dsb_lg_auflage_30",
        name = "DSB Luftgewehr Auflage — 30 Schuss",
        governingBody = "DSB",
        discipline = "Air Rifle",
        targetFaceId = TargetCatalog.ISSF_AR10.id,
        distanceM = 10.0,
        positionName = Position.BIPOD.name,
        matchShots = 30, shotsPerSeries = 10, sighters = -1,
        timeLimitSec = 50 * 60,
        gaugeDiameterMm = Gauge.AIR_4_5,
        matchScoring = MatchScoring.SUM_OF_SHOTS,
        countInnerTens = true,
        verified = false,
        notes = "Rested. Scored on the ISSF face but with the inner-ten count as the practical " +
            "discriminator, since a rested air rifle puts almost everything in the ten ring. " +
            "Maximum 300 with 30 inner tens."
    )

    val BDS_KK100 = RuleSet(
        id = "bds_kk_100m",
        name = "BDS 100 m Kleinkaliber — 20 Schuss",
        governingBody = "BDS",
        discipline = "Rimfire Rifle",
        targetFaceId = TargetCatalog.DE_100M.id,
        distanceM = 100.0,
        positionName = Position.PRONE.name,
        matchShots = 20, shotsPerSeries = 10, sighters = 3,
        timeLimitSec = 30 * 60,
        gaugeDiameterMm = Gauge.RIMFIRE_5_6,
        matchScoring = MatchScoring.SUM_OF_SHOTS,
        verified = false,
        notes = "Maximum 200."
    )

    val BDS_ZF100 = RuleSet(
        id = "bds_zf_100m",
        name = "BDS 100 m Zielfernrohr — 20 Schuss",
        governingBody = "BDS",
        discipline = "Centrefire Rifle",
        targetFaceId = TargetCatalog.DE_100M.id,
        distanceM = 100.0,
        positionName = Position.PRONE.name,
        matchShots = 20, shotsPerSeries = 10, sighters = 3,
        timeLimitSec = 30 * 60,
        gaugeDiameterMm = Gauge.CENTREFIRE_7_62,
        matchScoring = MatchScoring.SUM_OF_SHOTS,
        verified = false,
        notes = ".223 / .308 with a telescopic scope at 100 m. Maximum 200."
    )

    // =====================================================================
    //  Training
    // =====================================================================

    val TRAINING_FREE = RuleSet(
        id = "training_free",
        name = "Training — free practice",
        governingBody = "Custom",
        discipline = "Any",
        targetFaceId = TargetCatalog.ISSF_AR10.id,
        distanceM = 10.0,
        positionName = Position.FREESTYLE.name,
        matchShots = 0, shotsPerSeries = 10, sighters = 0,
        timeLimitSec = 0,
        gaugeDiameterMm = Gauge.AIR_4_5,
        decimalScoring = true,
        matchScoring = MatchScoring.SUM_OF_SHOTS,
        penaliseExcessShots = false,
        verified = false,
        notes = "No shot limit and no time limit. Everything is scored and nothing is penalised — " +
            "the mode to use when the point is the group and the correction, not the total."
    )

    val TRAINING_GROUP = RuleSet(
        id = "training_group",
        name = "Training — group and zero",
        governingBody = "Custom",
        discipline = "Any",
        targetFaceId = TargetCatalog.ISSF_R50.id,
        distanceM = 100.0,
        positionName = Position.BIPOD.name,
        matchShots = 5, shotsPerSeries = 5, sighters = 0,
        timeLimitSec = 0,
        gaugeDiameterMm = Gauge.CENTREFIRE_7_62,
        matchScoring = MatchScoring.SUM_OF_SHOTS,
        penaliseExcessShots = false,
        verified = false,
        notes = "Five-shot group for zeroing. The score is incidental; what matters is the group " +
            "centre and the click correction the Results screen derives from it."
    )

    // =====================================================================

    val builtIns: List<RuleSet> = listOf(
        ISSF_AR60, ISSF_AP60, ISSF_AR_FINAL,
        ISSF_R50_PRONE, ISSF_R50_3P, ISSF_P50,
        ISSF_P25_SPORT, ISSF_P25_RAPIDFIRE, ISSF_P25_CENTREFIRE,
        ISSF_R300_PRONE,
        NRA_NMC, NRA_SMALLBORE_PRONE, CMP_GAMES,
        FCLASS_600, FCLASS_1000,
        NRL22_BASE, PRS_STAGE,
        IPSC_COMSTOCK, IDPA_STAGE,
        PZSS_PN10, PZSS_KPN10, PZSS_PSP20, PZSS_KSP20, PZSS_PCZ20,
        DSB_LG40, DSB_LP40, DSB_LG_AUFLAGE, BDS_KK100, BDS_ZF100,
        TRAINING_FREE, TRAINING_GROUP
    )

    const val ALL = "All"

    fun byId(id: String): RuleSet? = builtIns.firstOrNull { it.id == id }

    fun bodies(): List<String> = listOf(ALL) + builtIns.map { it.governingBody }.distinct().sorted()
    fun disciplines(): List<String> = listOf(ALL) + builtIns.map { it.discipline }.distinct().sorted()

    fun filter(body: String, discipline: String, sets: List<RuleSet> = builtIns): List<RuleSet> =
        sets.filter {
            (body == ALL || it.governingBody == body) &&
                (discipline == ALL || it.discipline == discipline)
        }
}
