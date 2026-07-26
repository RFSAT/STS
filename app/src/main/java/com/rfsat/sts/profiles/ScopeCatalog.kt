package com.rfsat.sts.profiles

/**
 * Built-in sight catalogue: telescopic sights, match diopters, target-pistol
 * rear sights and red dots.
 *
 * Turret travel is stored in MOA internally (matching [ScopeProfile]);
 * MRAD-spec scopes are converted on entry at 1 mrad = 3.43775 MOA. Where a
 * manufacturer quotes only a total travel, that value is used. Figures vary
 * by tube and reticle, so they seed the fields for review rather than
 * claiming authority.
 *
 * The diopter and pistol-sight entries are the reason [ClickUnit] gained
 * MM_AT_REFERENCE: their makers publish a millimetre displacement at a stated
 * distance and nothing else. Those numbers go in verbatim.
 */
object ScopeCatalog {

    private const val MRAD_MOA = ScopeProfile.MOA_PER_MRAD

    data class Entry(
        val brand: String,
        val model: String,
        val sightType: SightType,
        val clickUnit: ClickUnit,
        val zoomMin: Double = 1.0,
        val zoomMax: Double = 1.0,
        val objectiveMm: Double = 0.0,
        val elevTravelMoa: Double = 60.0,
        val windTravelMoa: Double = 60.0,
        val focalLengthMm: Double = 0.0,
        val heightAboveBarrelIn: Double = 1.5,
        /** Only for MM_AT_REFERENCE entries. */
        val clickMm: Double = 0.0,
        val clickRefM: Double = 0.0,
        val baseFovDeg: Double = 0.0,
        val streamCapable: Boolean = false
    ) {
        val clickLabel: String
            get() = if (clickUnit == ClickUnit.MM_AT_REFERENCE)
                String.format("%.1f mm @ %.0f m", clickMm, clickRefM)
            else clickUnit.label

        fun label(): String {
            val optics = if (zoomMax > 1.0)
                " ${fmt(zoomMin)}-${fmt(zoomMax)}×${objectiveMm.toInt()},"
            else ""
            return "$brand $model —$optics $clickLabel"
        }

        private fun fmt(v: Double) = if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

        fun toScopeProfile(): ScopeProfile = ScopeProfile(
            name = "$brand $model",
            clickUnit = clickUnit,
            maxElevationTravelMoa = elevTravelMoa,
            maxWindageTravelMoa = windTravelMoa,
            zoomMin = zoomMin,
            zoomMax = zoomMax,
            objectiveDiameterMm = objectiveMm,
            focalLengthMm = focalLengthMm,
            heightAboveBarrelIn = heightAboveBarrelIn,
            fovAtBaseDeg = baseFovDeg,
            streamCapable = streamCapable,
            sightTypeName = sightType.name,
            clickMmAtReference = if (clickMm > 0) clickMm else 2.0,
            clickReferenceDistanceM = if (clickRefM > 0) clickRefM else 10.0
        )
    }

    val entries: List<Entry> = listOf(
        // ---- Match diopters. Quoted as mm of POI movement at the
        //      discipline distance, which is exactly how the makers spec them.
        Entry("Anschütz", "6834 diopter", SightType.DIOPTER, ClickUnit.MM_AT_REFERENCE,
            clickMm = 2.0, clickRefM = 10.0, heightAboveBarrelIn = 1.2),
        Entry("Anschütz", "7002/20 diopter (50 m)", SightType.DIOPTER, ClickUnit.MM_AT_REFERENCE,
            clickMm = 4.5, clickRefM = 50.0, heightAboveBarrelIn = 1.4),
        Entry("Gehmann", "520 diopter", SightType.DIOPTER, ClickUnit.MM_AT_REFERENCE,
            clickMm = 2.0, clickRefM = 10.0, heightAboveBarrelIn = 1.2),
        Entry("Centra", "Duplex diopter", SightType.DIOPTER, ClickUnit.MM_AT_REFERENCE,
            clickMm = 2.0, clickRefM = 10.0, heightAboveBarrelIn = 1.2),

        // ---- Target pistol rear sights ----
        Entry("Morini", "CM162 rear sight", SightType.OPEN_SIGHTS, ClickUnit.MM_AT_REFERENCE,
            clickMm = 2.0, clickRefM = 10.0, heightAboveBarrelIn = 0.6),
        Entry("Pardini", "SP rear sight (25 m)", SightType.OPEN_SIGHTS, ClickUnit.MM_AT_REFERENCE,
            clickMm = 5.0, clickRefM = 25.0, heightAboveBarrelIn = 0.6),
        Entry("Walther", "SSP rear sight (25 m)", SightType.OPEN_SIGHTS, ClickUnit.MM_AT_REFERENCE,
            clickMm = 5.0, clickRefM = 25.0, heightAboveBarrelIn = 0.6),

        // ---- Telescopic sights ----
        Entry("Vector Optics", "Continental 5-30x56", SightType.SCOPE, ClickUnit.MRAD_TENTH,
            5.0, 30.0, 56.0, 26.0 * MRAD_MOA, 16.0 * MRAD_MOA, 112.0, 1.97),
        Entry("Vector Optics", "Continental 3-18x50", SightType.SCOPE, ClickUnit.MRAD_TENTH,
            3.0, 18.0, 50.0, 30.0 * MRAD_MOA, 15.0 * MRAD_MOA, 100.0, 1.85),
        Entry("Nightforce", "Competition 15-55x52", SightType.SCOPE, ClickUnit.MOA_EIGHTH,
            15.0, 55.0, 52.0, 40.0, 30.0, 110.0, 1.9),
        Entry("March", "10-60x56 High Master", SightType.SCOPE, ClickUnit.MOA_EIGHTH,
            10.0, 60.0, 56.0, 40.0, 30.0, 112.0, 1.9),
        Entry("Sightron", "SIII 10-50x60", SightType.SCOPE, ClickUnit.MOA_EIGHTH,
            10.0, 50.0, 60.0, 45.0, 35.0, 115.0, 1.9),
        Entry("Vortex", "Golden Eagle 15-60x52", SightType.SCOPE, ClickUnit.MOA_EIGHTH,
            15.0, 60.0, 52.0, 45.0, 40.0, 110.0, 1.9),
        Entry("Vortex", "Viper PST Gen II 5-25x50", SightType.SCOPE, ClickUnit.MRAD_TENTH,
            5.0, 25.0, 50.0, 27.0 * MRAD_MOA, 12.0 * MRAD_MOA, 100.0, 1.9),
        Entry("Generic", "Hunting scope 3-9x40", SightType.SCOPE, ClickUnit.MOA_QUARTER,
            3.0, 9.0, 40.0, 60.0, 60.0, 80.0, 1.5),
        Entry("ATN", "X-Sight 5 LRF 5-25x", SightType.SCOPE, ClickUnit.MOA_QUARTER,
            5.0, 25.0, 50.0, 60.0, 60.0, 100.0, 1.9, baseFovDeg = 6.6, streamCapable = true),
        Entry("ATN", "X-Sight LTV 5-15x", SightType.SCOPE, ClickUnit.MOA_QUARTER,
            5.0, 15.0, 40.0, 60.0, 60.0, 90.0, 1.9, baseFovDeg = 6.2, streamCapable = false),

        // ---- Red dots ----
        Entry("Aimpoint", "Micro T-2 (2 MOA)", SightType.RED_DOT, ClickUnit.MOA_HALF,
            elevTravelMoa = 50.0, windTravelMoa = 50.0, objectiveMm = 18.0, heightAboveBarrelIn = 1.4),
        Entry("Holosun", "507C", SightType.RED_DOT, ClickUnit.MOA_HALF,
            elevTravelMoa = 50.0, windTravelMoa = 50.0, objectiveMm = 16.0, heightAboveBarrelIn = 0.9)
    )

    const val ALL = "All"

    fun brands(): List<String> = listOf(ALL) + entries.map { it.brand }.distinct().sorted()
    fun types(): List<String> = listOf(ALL) + SightType.values().map { it.label }

    fun filter(brand: String, typeLabel: String): List<Entry> =
        entries.filter {
            (brand == ALL || it.brand == brand) &&
                (typeLabel == ALL || it.sightType.label == typeLabel)
        }
}
