package com.rfsat.sts.targets

/**
 * ============================================================================
 *  PRACTICAL-SHOOTING FACE GEOMETRY
 * ============================================================================
 *
 * Ring targets are specified by a dimension table. Practical silhouettes are
 * specified by a DRAWING, which is a much weaker guarantee for a program: no
 * published list of vertices exists, and the several revisions in circulation
 * differ in the shoulder cut and the neck. So these faces are built from
 * named dimensions rather than hard-coded vertex lists — correcting one
 * figure rebuilds the polygon, instead of requiring a coordinate list to be
 * retyped and re-checked.
 *
 * Every face here is [TargetFace.verified] = false. The zone AREAS and their
 * point values are right, which is what scoring depends on; the exact
 * shoulder angle is cosmetic. Treat the outlines as a good working model and
 * measure your own cardboard if a hit near the perimeter matters.
 *
 * ORIGIN. The centre of the body A-zone (the usual point of aim), +y up.
 */
object PracticalGeometry {

    private fun rect(x0: Double, y0: Double, x1: Double, y1: Double): List<Pair<Double, Double>> =
        listOf(x0 to y0, x1 to y0, x1 to y1, x0 to y1)

    /** Regular polygon approximation of a circle, for the IDPA round zones. */
    private fun circle(cx: Double, cy: Double, r: Double, segments: Int = 48):
        List<Pair<Double, Double>> = (0 until segments).map { i ->
        val a = 2.0 * Math.PI * i / segments
        (cx + r * Math.cos(a)) to (cy + r * Math.sin(a))
    }

    // =====================================================================
    //  IPSC Classic
    // =====================================================================
    //
    //  Body 450 x 585 mm with 75 mm 45-degree shoulder cuts, a 15 mm neck,
    //  and a 150 x 150 mm head. Body A-zone 150 x 300 mm centred on the
    //  origin; head A-zone 100 x 100 mm. C is the body inside |x| <= 150
    //  outside A; D is the outer strips.
    //
    //  Scoring values are the ones that actually matter and are not in
    //  doubt: Minor A5 C3 D1, Major A5 C4 D2.

    private const val IPSC_BODY_HALF_W = 225.0
    private const val IPSC_BODY_TOP = 285.0
    private const val IPSC_BODY_BOTTOM = -300.0
    private const val IPSC_SHOULDER_CUT = 75.0
    private const val IPSC_A_HALF_W = 75.0
    private const val IPSC_A_HALF_H = 150.0
    private const val IPSC_C_HALF_W = 150.0
    private const val IPSC_NECK = 15.0
    private const val IPSC_HEAD_HALF_W = 75.0
    private const val IPSC_HEAD_H = 150.0
    private const val IPSC_HEAD_A_HALF_W = 50.0
    private const val IPSC_HEAD_A_HALF_H = 50.0

    /** Body outline with the shoulder cuts, anticlockwise from bottom left. */
    private fun ipscBodyOutline(scale: Double = 1.0): List<Pair<Double, Double>> {
        fun s(v: Double) = v * scale
        return listOf(
            -s(IPSC_BODY_HALF_W) to s(IPSC_BODY_BOTTOM),
            s(IPSC_BODY_HALF_W) to s(IPSC_BODY_BOTTOM),
            s(IPSC_BODY_HALF_W) to s(IPSC_BODY_TOP - IPSC_SHOULDER_CUT),
            s(IPSC_BODY_HALF_W - IPSC_SHOULDER_CUT) to s(IPSC_BODY_TOP),
            -s(IPSC_BODY_HALF_W - IPSC_SHOULDER_CUT) to s(IPSC_BODY_TOP),
            -s(IPSC_BODY_HALF_W) to s(IPSC_BODY_TOP - IPSC_SHOULDER_CUT)
        )
    }

    private fun ipscZones(scale: Double): List<Zone> {
        fun s(v: Double) = v * scale
        val headBottom = IPSC_BODY_TOP + IPSC_NECK
        val headTop = headBottom + IPSC_HEAD_H
        val headMidY = (headBottom + headTop) / 2.0
        return listOf(
            // D first in the list, lowest priority: it is the whole body
            // outline, and A and C are drawn on top of it. Without the
            // priorities the containment test would be order-dependent.
            Zone("D", ipscBodyOutline(scale), minorPoints = 1.0, majorPoints = 2.0, priority = 0),
            Zone(
                "C",
                rect(-s(IPSC_C_HALF_W), s(IPSC_BODY_BOTTOM), s(IPSC_C_HALF_W), s(IPSC_BODY_TOP)),
                minorPoints = 3.0, majorPoints = 4.0, priority = 1
            ),
            Zone(
                "A",
                rect(-s(IPSC_A_HALF_W), -s(IPSC_A_HALF_H), s(IPSC_A_HALF_W), s(IPSC_A_HALF_H)),
                minorPoints = 5.0, majorPoints = 5.0, priority = 3
            ),
            Zone(
                "C-head",
                rect(-s(IPSC_HEAD_HALF_W), s(headBottom), s(IPSC_HEAD_HALF_W), s(headTop)),
                minorPoints = 3.0, majorPoints = 4.0, priority = 2
            ),
            Zone(
                "A-head",
                rect(
                    -s(IPSC_HEAD_A_HALF_W), s(headMidY - IPSC_HEAD_A_HALF_H),
                    s(IPSC_HEAD_A_HALF_W), s(headMidY + IPSC_HEAD_A_HALF_H)
                ),
                minorPoints = 5.0, majorPoints = 5.0, priority = 4
            )
        )
    }

    fun ipscClassic(): TargetFace = TargetFace(
        id = "ipsc_classic",
        name = "IPSC Classic target",
        governingBody = "IPSC",
        discipline = "Practical Pistol",
        nominalDistanceM = 0.0, // engaged at whatever range the stage sets
        faceWidthMm = 450.0,
        faceHeightMm = 750.0,
        // The outline runs from y = -300 (body base) to y = +450 (head top),
        // so the cardboard's own centre sits 75 mm ABOVE the A-zone centre
        // that everything here is measured from.
        cardCentreOffsetYMm = 75.0,
        zones = ipscZones(1.0),
        scoringMode = ScoringMode.ZONE_POINTS,
        verified = false,
        notes = "Minor A5 C3 D1, Major A5 C4 D2. Hit factor = points / time. Zone outlines are " +
            "a working model built from named dimensions in PracticalGeometry.kt — the areas and " +
            "values are right, the shoulder cut is approximate."
    )

    /** IPSC Mini: the classic outline at one third scale, used at short range
     *  to present the same angular size as a full target further out. */
    fun ipscMini(): TargetFace = ipscClassic().copy(
        id = "ipsc_mini",
        name = "IPSC Mini target (1/3 scale)",
        faceWidthMm = 150.0,
        faceHeightMm = 250.0,
        cardCentreOffsetYMm = 25.0,
        zones = ipscZones(1.0 / 3.0),
        notes = "The Classic outline at 1/3 linear scale. Same zone values."
    )

    // =====================================================================
    //  IDPA
    // =====================================================================
    //
    //  IDPA scores POINTS DOWN, added to the raw time as a penalty: -0 costs
    //  nothing, -1 costs one second, -3 costs three. Those are stored here as
    //  NEGATIVE point values (0, -1, -3) so that "higher is better" holds
    //  everywhere in the scoring code, and RuleSet.timePlusPenalty tells the
    //  session to convert the magnitude into seconds rather than reporting a
    //  hit factor.
    //
    //  Cardboard 457 x 762 mm (18 x 30 in). Down-zero body circle 203 mm
    //  (8 in) and head circle 102 mm (4 in); the -1 region is the bottle
    //  body; everything else on the cardboard is -3.

    private const val IN = 25.4

    fun idpa(): TargetFace {
        val bodyHalfW = 9.0 * IN
        val downZeroR = 4.0 * IN          // 8 in diameter
        val headR = 2.0 * IN              // 4 in diameter
        val headCentreY = 11.0 * IN
        // -1 "bottle": shoulders 11 in wide narrowing to the neck.
        val oneOutline = listOf(
            -6.0 * IN to -9.0 * IN,
            6.0 * IN to -9.0 * IN,
            6.0 * IN to 5.5 * IN,
            3.0 * IN to 8.0 * IN,
            -3.0 * IN to 8.0 * IN,
            -6.0 * IN to 5.5 * IN
        )
        val cardboard = listOf(
            -bodyHalfW to -15.0 * IN,
            bodyHalfW to -15.0 * IN,
            bodyHalfW to 15.0 * IN,
            -bodyHalfW to 15.0 * IN
        )
        return TargetFace(
            id = "idpa_target",
            name = "IDPA target",
            governingBody = "IDPA",
            discipline = "Practical Pistol",
            nominalDistanceM = 0.0,
            faceWidthMm = 18.0 * IN,
            faceHeightMm = 30.0 * IN,
            zones = listOf(
                Zone("-3", cardboard, minorPoints = -3.0, majorPoints = -3.0, priority = 0),
                Zone("-1", oneOutline, minorPoints = -1.0, majorPoints = -1.0, priority = 1),
                Zone("-0", circle(0.0, 0.0, downZeroR), minorPoints = 0.0, majorPoints = 0.0, priority = 2),
                Zone("-0 head", circle(0.0, headCentreY, headR), minorPoints = 0.0, majorPoints = 0.0, priority = 3)
            ),
            scoringMode = ScoringMode.ZONE_POINTS,
            verified = false,
            notes = "Points DOWN are stored as negative values so that higher is better throughout " +
                "the scoring code; the rule set converts the magnitude into penalty seconds. " +
                "Cardboard 18 x 30 in, down-zero 8 in body / 4 in head."
        )
    }
}
