package com.rfsat.sts.profiles

/**
 * Turret click granularity.
 *
 * The first three values are byte-for-byte the VTB/DBM set and MUST keep
 * their names and their order-independent identity — ClickUnit is persisted
 * by NAME through Gson, so a profile set from either app still resolves.
 *
 * The last three are STS additions:
 *
 *   MOA_HALF / MRAD_HUNDREDTH cover the coarse hunting turret and the fine
 *   benchrest turret respectively.
 *
 *   MM_AT_REFERENCE is the important one, and it is why this enum could not
 *   simply be reused as it stood. Match iron sights — the ISSF rifle
 *   diopter, and most air-pistol rear sights — do not publish an angular
 *   click at all. Their manufacturers quote displacement of the point of
 *   impact at a stated distance: "1 click = 2 mm at 10 m", "1 click = 4.5 mm
 *   at 50 m". Forcing that into MOA at profile-entry time would bake in a
 *   conversion the shooter did not make and could not check. So the profile
 *   stores the manufacturer's own two numbers and STS converts once, at the
 *   point of use, where the actual target distance is known.
 */
enum class ClickUnit(val label: String) {
    MOA_QUARTER("1/4 MOA"),
    MOA_EIGHTH("1/8 MOA"),
    MRAD_TENTH("0.1 MRAD"),
    MOA_HALF("1/2 MOA"),
    MRAD_HUNDREDTH("0.01 MRAD"),
    MM_AT_REFERENCE("mm at a reference distance"),

    /**
     * The sight cannot be adjusted by counting anything.
     *
     * A fixed factory iron sight, or no sight at all. This is NOT the same as
     * a missing click value: it is a positive statement that no click exists,
     * and it is what stops the app inventing "0 clicks up" for a sight with
     * no turrets on it. The correction is then reported as the movement the
     * point of impact needs, and — where the sight radius is known — as how
     * far the rear sight must physically move.
     */
    NONE("No adjustment")
}

/** What the shooter is actually aiming with. Decides which fields matter and
 *  what the correction advice should say. */
enum class SightType(val label: String) {
    SCOPE("Telescopic scope"),
    DIOPTER("Diopter / match iron sights"),
    OPEN_SIGHTS("Open iron sights"),
    RED_DOT("Red dot / reflex"),

    /** A fixed sight the shooter cannot adjust, or none at all. The group is
     *  still worth measuring — it is the only way to know the hold-off — but
     *  no adjustment can be advised. */
    NONE("No scope / fixed scope")
}

/**
 * Sight description: the optical parameters relevant to the correction
 * calculation and to record-keeping. Class name and the original field set
 * are VTB/DBM-compatible; everything STS adds has a safe default so an
 * imported profile that lacks them still works.
 */
data class ScopeProfile(
    val name: String = "Anschütz 6834 diopter",
    val clickUnit: ClickUnit = ClickUnit.MM_AT_REFERENCE,
    val maxElevationTravelMoa: Double = 60.0,
    val maxWindageTravelMoa: Double = 60.0,
    val zoomMin: Double = 1.0,
    val zoomMax: Double = 1.0,
    val objectiveDiameterMm: Double = 0.0,
    val focalLengthMm: Double = 0.0,
    /** Sight optical centreline above the bore axis, inches. */
    val heightAboveBarrelIn: Double = 1.5,
    /** VTB: optical FOV in degrees at base magnification for digital scopes
     *  that record video. 0 = not a video scope. */
    val fovAtBaseDeg: Double = 0.0,
    /** VTB: streams live video over its own Wi-Fi. STS uses this to offer
     *  the scope as an external frame source on the Session screen. */
    val streamCapable: Boolean = false,
    // ---- STS additions ----
    val sightTypeName: String = SightType.DIOPTER.name,
    /** For [ClickUnit.MM_AT_REFERENCE]: the manufacturer's quoted point-of-
     *  impact movement per click, in millimetres... */
    val clickMmAtReference: Double = 2.0,
    /** ...at this distance, in metres. */
    val clickReferenceDistanceM: Double = 10.0,
    /** Some diopters and most target pistol sights reverse one axis relative
     *  to the usual "turn towards the desired impact" convention. Set true
     *  and the app inverts the direction words it prints, so the advice
     *  matches the marking actually engraved on the sight. */
    val invertElevationDirection: Boolean = false,
    val invertWindageDirection: Boolean = false,
    /** Sight radius: the distance from the front sight to the rear sight, in
     *  millimetres. Only meaningful for iron sights, and only used to convert
     *  an angular correction into how far the rear sight must physically
     *  move — which is the only actionable instruction for a sight that has
     *  no clicks. 0 = unknown, and the app then reports the correction as an
     *  angle and as a distance on the target, and says why it cannot give a
     *  sight movement. */
    val sightRadiusMm: Double = 0.0
) {
    val sightType: SightType
        get() = runCatching { SightType.valueOf(sightTypeName) }.getOrDefault(SightType.SCOPE)

    /** Numeric click size in the unit's own terms. Meaningless for
     *  MM_AT_REFERENCE — use [clickMrad] instead, which is well defined for
     *  every unit. */
    val clickValue: Double
        get() = when (clickUnit) {
            ClickUnit.MOA_QUARTER -> 0.25
            ClickUnit.MOA_EIGHTH -> 0.125
            ClickUnit.MOA_HALF -> 0.5
            ClickUnit.MRAD_TENTH -> 0.1
            ClickUnit.MRAD_HUNDREDTH -> 0.01
            ClickUnit.MM_AT_REFERENCE -> clickMmAtReference
            ClickUnit.NONE -> 0.0
        }

    val clickUnitIsMoa: Boolean
        get() = clickUnit == ClickUnit.MOA_QUARTER ||
            clickUnit == ClickUnit.MOA_EIGHTH ||
            clickUnit == ClickUnit.MOA_HALF

    /**
     * ONE click expressed in milliradians — the common currency every other
     * part of the app converts through.
     *
     *   MOA: 1 MOA = 1/60 degree; 1 mrad = 3.43775 MOA, so mrad = MOA/3.43775.
     *   MRAD: already there.
     *   MM_AT_REFERENCE: a displacement m millimetres at distance D metres
     *     subtends m/(1000 D) radians = m/D milliradians. (1 mrad puts 100 mm
     *     on the target at 100 m, which is the check.)
     *
     * Returns 0 for a nonsensical profile — an unclicked open sight, or a
     * reference distance of zero — and callers must treat 0 as "this sight
     * has no clicks", reporting the correction as a distance on the target
     * instead of a click count. That is the honest output for open sights,
     * which are adjusted by drifting, not by counting.
     */
    val clickMrad: Double
        get() = when (clickUnit) {
            ClickUnit.MOA_QUARTER, ClickUnit.MOA_EIGHTH, ClickUnit.MOA_HALF ->
                clickValue / MOA_PER_MRAD
            ClickUnit.MRAD_TENTH, ClickUnit.MRAD_HUNDREDTH -> clickValue
            ClickUnit.MM_AT_REFERENCE ->
                if (clickReferenceDistanceM > 0.0) clickMmAtReference / clickReferenceDistanceM else 0.0
            ClickUnit.NONE -> 0.0
        }

    /**
     * THE CLICK VALUE DECIDES, NOT THE KIND OF SIGHT.
     *
     * This used to read `clickMrad > 0 && sightType != OPEN_SIGHTS`, which
     * says that no open sight has clicks. That is false of exactly the sights
     * this app is for: the Morini, Pardini and Walther target-pistol rear
     * sights in the catalogue are open sights and every one of them is
     * click-adjustable, to a published millimetre at a stated distance. The
     * rule went unnoticed only because catalogue picks were all being labelled
     * telescopic — see ScopeCatalog.toScopeProfile, fixed at the same time.
     *
     * A sight with no clicks now says so with [ClickUnit.NONE].
     */
    val hasClicks: Boolean get() = clickUnit != ClickUnit.NONE && clickMrad > 0.0

    /** How a click should be described back to the shooter. */
    fun clickDescription(): String = when (clickUnit) {
        ClickUnit.NONE -> "no click adjustment"
        ClickUnit.MM_AT_REFERENCE ->
            String.format("%.2f mm @ %.0f m", clickMmAtReference, clickReferenceDistanceM)
        else -> clickUnit.label
    }

    fun label(): String {
        val optics = if (zoomMax > 1.0)
            " ${fmt(zoomMin)}-${fmt(zoomMax)}x${objectiveDiameterMm.toInt()}" else ""
        return "$name —$optics ${clickDescription()}"
    }

    private fun fmt(v: Double) = if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    companion object {
        const val MOA_PER_MRAD = 3.43775

        val DEFAULT = ScopeProfile()

        /** Pull-down presets; "Custom" leaves the fields editable as they are. */
        val PRESETS: List<ScopeProfile> = listOf(
            DEFAULT,
            ScopeProfile(
                name = "Match air-pistol rear sight",
                clickUnit = ClickUnit.MM_AT_REFERENCE,
                clickMmAtReference = 2.0, clickReferenceDistanceM = 10.0,
                sightTypeName = SightType.OPEN_SIGHTS.name,
                heightAboveBarrelIn = 0.6
            ),
            ScopeProfile(
                name = "Vector Optics Continental 5-30x56",
                clickUnit = ClickUnit.MRAD_TENTH,
                maxElevationTravelMoa = 26.0 * MOA_PER_MRAD,
                maxWindageTravelMoa = 16.0 * MOA_PER_MRAD,
                zoomMin = 5.0, zoomMax = 30.0, objectiveDiameterMm = 56.0,
                focalLengthMm = 112.0, heightAboveBarrelIn = 1.97,
                sightTypeName = SightType.SCOPE.name
            ),
            ScopeProfile(
                name = "Generic 1/4-MOA hunting scope 3-9x40",
                clickUnit = ClickUnit.MOA_QUARTER,
                maxElevationTravelMoa = 60.0, maxWindageTravelMoa = 60.0,
                zoomMin = 3.0, zoomMax = 9.0, objectiveDiameterMm = 40.0,
                focalLengthMm = 80.0, heightAboveBarrelIn = 1.5,
                sightTypeName = SightType.SCOPE.name
            ),
            ScopeProfile(
                name = "Generic 1/8-MOA target scope 8-32x56",
                clickUnit = ClickUnit.MOA_EIGHTH,
                maxElevationTravelMoa = 40.0, maxWindageTravelMoa = 40.0,
                zoomMin = 8.0, zoomMax = 32.0, objectiveDiameterMm = 56.0,
                focalLengthMm = 110.0, heightAboveBarrelIn = 1.9,
                sightTypeName = SightType.SCOPE.name
            ),
            ScopeProfile(
                name = "2 MOA red dot",
                clickUnit = ClickUnit.MOA_HALF,
                maxElevationTravelMoa = 50.0, maxWindageTravelMoa = 50.0,
                zoomMin = 1.0, zoomMax = 1.0, objectiveDiameterMm = 25.0,
                heightAboveBarrelIn = 1.4,
                sightTypeName = SightType.RED_DOT.name
            ),
            ScopeProfile(name = "Custom…")
        )
    }
}
