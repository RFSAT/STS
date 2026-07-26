package com.rfsat.sts.targets

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * ============================================================================
 *  TARGET GEOMETRY AND THE SCORING RULE
 * ============================================================================
 *
 * COORDINATES. Everything on this page is in TARGET-PLANE MILLIMETRES with
 * the origin at the scoring centre, +x to the right and +y UP. A shot 12 mm
 * high and 3 mm left of centre is (-3.0, +12.0). Millimetres because every
 * governing body except the American ones publishes target dimensions that
 * way, and metric integers avoid the rounding drift that creeping through
 * inches would cause on a 0.5 mm ten-ring.
 *
 * THE TOUCH RULE. Rings are scored on the OUTWARD EDGE of the hole, not its
 * centre: a shot takes the highest ring value that the hole touches or
 * breaks (ISSF 6.3.4, and the same principle in NRA and CMP rulebooks —
 * which is what the physical scoring gauge mechanises). So with
 *
 *     d = distance of the hole CENTRE from the target centre
 *     c = scoring radius of the projectile (half the gauge diameter)
 *     R_v = printed radius of ring v
 *
 * the shot scores the highest v satisfying  (d - c) <= R_v.
 *
 * Note c is the GAUGE radius, not the bore diameter: ISSF specifies 4.5 mm
 * for air, 5.6 mm for .22, 7.62/7.65 mm for centrefire, and the gauge is
 * what the jury applies. [RuleSet.gaugeDiameterMm] carries it, so a .223
 * shot on a target scored under a 7.62 mm gauge convention is scored the
 * way the rulebook says, not the way the bullet measures.
 *
 * DECIMAL SCORING. Electronic targets score to a tenth. Derivation, done
 * once here so nothing downstream has to guess:
 *
 *   - Value 10.0 is reached when the hole edge just touches the ten-ring
 *     line, i.e. at centre distance d0 = R_10 + c.
 *   - Each further tenth of a point costs one tenth of a ring width, so
 *     with ring pitch s (the constant radial spacing of the printed rings)
 *     the boundary for value V is at d = d0 - (V - 10.0) * s.
 *   - Rearranged, and valid across the whole face, not just the ten ring:
 *
 *         V(d) = 10.0 - (d - d0) / s
 *
 *   - Truncated (never rounded — a tenth is only awarded once earned) to
 *     one decimal, and capped at the ISSF maximum of 10.9.
 *
 * Sanity check against published figures: 10 m air rifle has R_10 = 0.25 mm,
 * c = 2.25 mm, s = 2.5 mm, so 10.9 requires d <= 0.25 mm — the commonly
 * quoted quarter-millimetre. Air pistol (R_10 = 5.75, c = 2.25, s = 8.0)
 * gives 0.8 mm, and 50 m rifle (R_10 = 5.2, c = 2.8, s = 8.0) also 0.8 mm.
 * Those match the published inner-ten tolerances, which is the check that
 * the derivation is right rather than merely self-consistent.
 *
 * Decimal scoring therefore requires EVENLY PITCHED rings. [ringPitchMm]
 * returns null when the printed rings are not evenly spaced (every American
 * high-power face), and [scoreDecimal] refuses to invent a number in that
 * case rather than silently producing a plausible-looking wrong one.
 */

/** One printed scoring ring: its value and its printed DIAMETER in mm. */
data class Ring(val value: Int, val diameterMm: Double) {
    val radiusMm: Double get() = diameterMm / 2.0
}

/**
 * A polygonal scoring zone, for practical-shooting faces (IPSC, IDPA) whose
 * scoring areas are not concentric circles. Vertices are target-plane mm in
 * order around the boundary; the polygon may be concave.
 */
data class Zone(
    val name: String,          // "A", "C", "D", "-0", "-1", "-3", "head"
    val points: List<Pair<Double, Double>>,
    /** Points scored by a hit in this zone at MINOR power factor. */
    val minorPoints: Double,
    /** Points at MAJOR power factor. Equal to minorPoints where the
     *  discipline does not distinguish (IDPA does not). */
    val majorPoints: Double,
    /** Priority when zones overlap: the HIGHEST wins. A-zone geometry is
     *  drawn inside the C-zone outline on a real IPSC target, so without
     *  this the containment test would be order-dependent. */
    val priority: Int = 0
)

/** How a face converts a hit position into a number. */
enum class ScoringMode {
    /** Concentric rings, whole-number values (most paper competition). */
    RING_INTEGER,

    /** Concentric rings scored to a tenth — electronic-target equivalent.
     *  Requires evenly pitched rings; see the derivation above. */
    RING_DECIMAL,

    /** Polygonal zones with a points value, combined with elapsed time into
     *  a hit factor (IPSC) or added to time as a penalty (IDPA). */
    ZONE_POINTS,

    /** Steel and reactive targets: the hit either counts or it does not.
     *  PRS/NRL22 stage scoring. */
    HIT_MISS
}

/**
 * A competition target face: the printed geometry plus enough metadata to
 * find it in the catalogue and to render it.
 *
 * All dimensional fields are NOMINAL PUBLISHED figures. [verified] marks the
 * entries taken directly from a governing body's published dimension table
 * (the ISSF faces); the rest are the commonly cited figures and are flagged
 * in the UI as needing a check against the rulebook in force. Every field is
 * editable in-app, and a user copy is stored as a custom face rather than
 * overwriting the built-in — so a correction never silently changes what a
 * previously scored session was measured against.
 */
data class TargetFace(
    val id: String,
    val name: String,
    /** "ISSF", "NRA", "CMP", "ICFRA", "IPSC", "IDPA", "NRL22", "PZSS",
     *  "DSB", "BDS", or "Custom". */
    val governingBody: String,
    /** "Air Rifle", "Air Pistol", "Rimfire Rifle", "Rimfire Pistol",
     *  "Centrefire Rifle", "Practical Pistol", "Practical Rifle". */
    val discipline: String,
    /** Distance the face is designed for, metres. 0 for faces used at
     *  several distances (reduced-scale practice faces). */
    val nominalDistanceM: Double,
    /** Physical card size, mm — used to scale the plot and to size the
     *  registration outline the user aligns to. */
    val faceWidthMm: Double,
    val faceHeightMm: Double,
    /** Offset of the CARD's geometric centre from the SCORING centre, mm.
     *  Zero on the symmetric faces, non-zero wherever the scoring area sits
     *  low or high on the card: the ISSF 25 m Rapid Fire card is 700 mm tall
     *  with a 500 mm scoring circle, and every practical silhouette has its
     *  A-zone below the middle. Registration taps the CARD corners, so
     *  without this the whole face would be scored about the wrong point —
     *  a silent, uniform, entirely plausible-looking error. */
    val cardCentreOffsetXMm: Double = 0.0,
    val cardCentreOffsetYMm: Double = 0.0,
    /** Printed rings, any order on input; normalised to descending value. */
    val rings: List<Ring> = emptyList(),
    /** Polygonal zones for practical faces. Empty for ring targets. */
    val zones: List<Zone> = emptyList(),
    /** Diameter of the black aiming mark, mm. 0 = no black (all-white face
     *  or a practical silhouette). Drawn by the plot and used by the hole
     *  detector, which needs to know where low-contrast regions are. */
    val blackDiameterMm: Double = 0.0,
    /** Inner-ten / X-ring diameter, mm. Counts as a ten but is tallied
     *  separately for tie-breaks. 0 = the face has none. */
    val innerTenDiameterMm: Double = 0.0,
    val innerTenLabel: String = "inner 10",
    val scoringMode: ScoringMode = ScoringMode.RING_INTEGER,
    /** True only for figures taken from a governing body's published table. */
    val verified: Boolean = false,
    /** User-created or user-edited copy. */
    val custom: Boolean = false,
    /** content:// or file:// of a user-supplied face photograph or scan.
     *  Null for the built-in faces, which are drawn from [rings]/[zones]. */
    val imageUri: String? = null,
    /** Where the scoring centre sits in that image, as a fraction of image
     *  width/height (0..1). Only meaningful with [imageUri]. */
    val imageCentreXFrac: Double = 0.5,
    val imageCentreYFrac: Double = 0.5,
    /** Millimetres per image pixel, established during target calibration
     *  by the user identifying a ring of known diameter. 0 = not calibrated. */
    val imageMmPerPx: Double = 0.0,
    val notes: String = ""
) {

    /** Rings sorted best-first (highest value, smallest diameter). */
    val ringsByValue: List<Ring> by lazy { rings.sortedByDescending { it.value } }

    /** Outermost printed ring — beyond it, a hit is a miss. */
    val outerRadiusMm: Double get() = rings.maxOfOrNull { it.radiusMm } ?: 0.0

    val maxRingValue: Int get() = rings.maxOfOrNull { it.value } ?: 0

    val hasInnerTen: Boolean get() = innerTenDiameterMm > 0.0

    /**
     * Constant radial spacing of the printed rings, or null when they are
     * not evenly pitched. Computed from consecutive-value pairs and accepted
     * only if every gap agrees to within 0.05 mm — a tolerance tighter than
     * any printing variation but loose enough for the rounding in published
     * tables.
     */
    val ringPitchMm: Double? by lazy {
        val sorted = rings.sortedBy { it.diameterMm }
        if (sorted.size < 3) return@lazy null
        val gaps = sorted.zipWithNext { a, b -> b.radiusMm - a.radiusMm }
        val first = gaps.first()
        if (first <= 0.0) return@lazy null
        if (gaps.any { abs(it - first) > 0.05 }) null else first
    }

    // ------------------------------------------------------------------
    //  Scoring
    // ------------------------------------------------------------------

    /**
     * Integer ring value of a hole whose CENTRE is [distanceMm] from the
     * scoring centre, applying the touch rule with scoring radius
     * [gaugeRadiusMm]. Returns 0 for a hole outside the outermost ring.
     */
    fun scoreInteger(distanceMm: Double, gaugeRadiusMm: Double): Int {
        val edge = max(0.0, distanceMm - gaugeRadiusMm)
        // ringsByValue is best-first, so the first ring the edge falls
        // inside is by construction the highest value it touches.
        return ringsByValue.firstOrNull { edge <= it.radiusMm + EPS }?.value ?: 0
    }

    /**
     * True when the hole also breaks the inner-ten / X ring. Same touch rule.
     */
    fun isInnerTen(distanceMm: Double, gaugeRadiusMm: Double): Boolean {
        if (!hasInnerTen) return false
        return max(0.0, distanceMm - gaugeRadiusMm) <= innerTenDiameterMm / 2.0 + EPS
    }

    /**
     * Decimal value per the derivation at the top of this file, or null when
     * the face's rings are not evenly pitched (in which case a decimal
     * figure would be fabricated, and the caller must fall back to the
     * integer value).
     *
     * A hole outside the outermost ring scores 0.0, not a negative number.
     */
    fun scoreDecimal(distanceMm: Double, gaugeRadiusMm: Double): Double? {
        val s = ringPitchMm ?: return null
        val tenRing = rings.firstOrNull { it.value == 10 } ?: return null
        val d0 = tenRing.radiusMm + gaugeRadiusMm
        val raw = 10.0 - (distanceMm - d0) / s
        // Truncate towards zero to one decimal: a tenth is awarded only once
        // fully earned, never rounded up into.
        val truncated = floor(raw * 10.0 + EPS) / 10.0
        val capped = min(truncated, DECIMAL_MAX)
        // Below the lowest ring the formula keeps counting down past zero.
        if (scoreInteger(distanceMm, gaugeRadiusMm) == 0) return 0.0
        return max(0.0, capped)
    }

    /**
     * Zone containing (or touched by) a hole at [x],[y] target-plane mm,
     * highest priority first. Null when the hole misses every zone.
     *
     * The touch rule applies here too: a hole whose CENTRE lies outside a
     * zone but whose edge breaks the line scores that zone, which is exactly
     * the IPSC "shot touching the line scores the higher value" provision.
     */
    fun zoneAt(x: Double, y: Double, gaugeRadiusMm: Double): Zone? =
        zones.sortedByDescending { it.priority }
            .firstOrNull { z ->
                pointInPolygon(x, y, z.points) ||
                    distanceToPolygon(x, y, z.points) <= gaugeRadiusMm + EPS
            }

    // ------------------------------------------------------------------
    //  Geometry helpers
    // ------------------------------------------------------------------

    /** Standard ray-casting containment test; handles concave polygons. */
    private fun pointInPolygon(x: Double, y: Double, poly: List<Pair<Double, Double>>): Boolean {
        if (poly.size < 3) return false
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val (xi, yi) = poly[i]
            val (xj, yj) = poly[j]
            val crosses = (yi > y) != (yj > y) &&
                x < (xj - xi) * (y - yi) / ((yj - yi).takeIf { it != 0.0 } ?: EPS) + xi
            if (crosses) inside = !inside
            j = i
        }
        return inside
    }

    /** Shortest distance from a point to a polygon's boundary. */
    private fun distanceToPolygon(x: Double, y: Double, poly: List<Pair<Double, Double>>): Double {
        if (poly.isEmpty()) return Double.MAX_VALUE
        var best = Double.MAX_VALUE
        var j = poly.size - 1
        for (i in poly.indices) {
            best = min(best, distanceToSegment(x, y, poly[j].first, poly[j].second, poly[i].first, poly[i].second))
            j = i
        }
        return best
    }

    private fun distanceToSegment(
        px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double
    ): Double {
        val vx = bx - ax; val vy = by - ay
        val len2 = vx * vx + vy * vy
        if (len2 <= EPS) return hypot(px - ax, py - ay)
        val t = (((px - ax) * vx + (py - ay) * vy) / len2).coerceIn(0.0, 1.0)
        return hypot(px - (ax + t * vx), py - (ay + t * vy))
    }

    /** Half-diagonal of the card; the plot uses it to choose a view scale. */
    val faceRadiusMm: Double get() = sqrt(faceWidthMm * faceWidthMm + faceHeightMm * faceHeightMm) / 2.0

    fun summary(): String {
        val dist = if (nominalDistanceM > 0) " @ ${nominalDistanceM.toInt()} m" else ""
        val geom = when (scoringMode) {
            ScoringMode.RING_INTEGER, ScoringMode.RING_DECIMAL ->
                "${rings.size} rings, ${fmt(outerRadiusMm * 2)} mm outer"
            ScoringMode.ZONE_POINTS -> "${zones.size} zones"
            ScoringMode.HIT_MISS -> "hit/miss"
        }
        return "$governingBody — $discipline$dist, $geom"
    }

    private fun fmt(v: Double) = if (v == floor(v)) v.toInt().toString() else String.format("%.1f", v)

    companion object {
        /** Geometric slack, mm. Well below any printing or measurement
         *  tolerance, but enough that a shot landing exactly on a boundary
         *  is decided in the shooter's favour rather than by float noise. */
        const val EPS = 1e-6

        /** ISSF decimal maximum. */
        const val DECIMAL_MAX = 10.9

        /** Builds evenly pitched concentric rings from the ten-ring diameter
         *  and the ring pitch — which is how every ISSF face is specified,
         *  and far less error-prone than typing ten diameters. */
        fun evenRings(tenRingDiameterMm: Double, pitchMm: Double, lowestValue: Int = 1): List<Ring> =
            (10 downTo lowestValue).map { v ->
                Ring(v, tenRingDiameterMm + 2.0 * pitchMm * (10 - v))
            }
    }
}
