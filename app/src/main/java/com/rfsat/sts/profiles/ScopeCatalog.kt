package com.rfsat.sts.profiles

/*
 * PORTED VERBATIM FROM VTB v1.20.38.
 *
 * Entries, field order, filter helpers and the ALL sentinel are exactly as
 * VTB has them, so a load selected in one app means the same thing in the
 * other and a profile set moves between them unchanged. Where STS needs
 * something VTB does not have, it is added BELOW the ported block rather than
 * folded into it, so the next VTB revision can be dropped in by replacing the
 * entry list alone.
 */
/**
 * Built-in riflescope catalogue (v20.12) — the scope analogue of
 * [AmmoCatalog]. Entries carry manufacturer-published optical and turret
 * specs; selecting one PRE-FILLS the editable scope fields (the custom
 * define-and-save flow is untouched — a catalogue pick is a seed).
 *
 * The two turret-travel figures are stored in MOA internally (matching
 * ScopeProfile); MRAD-scope specs are converted on entry (1 mrad = 3.43775
 * MOA). Where a manufacturer quotes only a total elevation/windage travel,
 * that value is used; figures are approximate and vary by tube/reticle, so
 * they seed the fields for review rather than claiming authority.
 */
object ScopeCatalog {

    private const val MRAD_MOA = 3.43775

    data class Entry(
        val brand: String,
        val model: String,
        val zoomMin: Double,
        val zoomMax: Double,
        val objectiveMm: Double,
        val clickUnit: ClickUnit,
        val elevTravelMoa: Double,
        val windTravelMoa: Double,
        val focalLengthMm: Double,
        val heightAboveBarrelIn: Double = 1.5,
        /** Optical FOV (deg) at zoomMin for video-recording digital scopes;
         *  0 for traditional optics. Verified from manufacturer specs. */
        val baseFovDeg: Double = 0.0,
        /** Digital day/night or thermal (affects the catalogue filter). */
        val family: String = "Optical",
        /** Streams live video over its own Wi-Fi AP (all ATN digital models). */
        val streamCapable: Boolean = false
    ) {
        val magClass: String get() = when {
            zoomMax <= 9.0 -> "Low (\u2264 9\u00d7)"
            zoomMax <= 20.0 -> "Mid (10\u201320\u00d7)"
            else -> "High (> 20\u00d7)"
        }
        // VTB's ClickUnit has three values; STS's has six, because match
        // diopters and target-pistol sights are quoted in millimetres at a
        // stated distance rather than as an angle. The ported branch is kept
        // and the STS units added, rather than an else, so that adding a
        // seventh unit is a compile error here instead of a sight that
        // silently labels itself wrongly.
        val clickLabel: String get() = when (clickUnit) {
            ClickUnit.MRAD_TENTH -> "0.1 MRAD"
            ClickUnit.MOA_QUARTER -> "1/4 MOA"
            ClickUnit.MOA_EIGHTH -> "1/8 MOA"
            ClickUnit.MOA_HALF -> "1/2 MOA"
            ClickUnit.MRAD_HUNDREDTH -> "0.01 MRAD"
            ClickUnit.MM_AT_REFERENCE -> "mm at a reference distance"
            ClickUnit.NONE -> "not adjustable"
        }
        /** A diopter, an open rear sight and a bare barrel have no
         *  magnification and no objective, and printing "1-1x0" for them was
         *  noise in every picker row. Optics keep the full description. */
        fun label(): String {
            val optics = if (zoomMax > 1.0 || objectiveMm > 0.0)
                " \u2014 ${fmt(zoomMin)}-${fmt(zoomMax)}\u00d7${objectiveMm.toInt()}, $clickLabel"
            else if (clickUnit == ClickUnit.NONE) ""
            else " \u2014 $clickLabel"
            return "$brand $model$optics"
        }
        private fun fmt(v: Double) = if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

        fun toScopeProfile(): ScopeProfile = ScopeProfile(
            name = "$brand $model",
            fovAtBaseDeg = baseFovDeg,
            streamCapable = streamCapable,
            clickUnit = clickUnit,
            maxElevationTravelMoa = elevTravelMoa,
            maxWindageTravelMoa = windTravelMoa,
            zoomMin = zoomMin,
            zoomMax = zoomMax,
            objectiveDiameterMm = objectiveMm,
            focalLengthMm = focalLengthMm,
            heightAboveBarrelIn = heightAboveBarrelIn,
            // WHAT KIND OF SIGHT THIS ACTUALLY IS.
            //
            // Every VTB entry is a telescopic sight, and this line used to say
            // SCOPE for all of them — including the STS additions appended
            // below, which are diopters, target-pistol rear sights and red
            // dots. Nothing depended on it until a sight arrived that cannot
            // be adjusted at all, at which point calling it a telescope would
            // have had the app offer turret clicks for a bare barrel.
            sightTypeName = sightType().name,
            clickMmAtReference = if (clickUnit == ClickUnit.MM_AT_REFERENCE) 2.0 else 0.0,
            clickReferenceDistanceM = if (clickUnit == ClickUnit.MM_AT_REFERENCE) 10.0 else 0.0
        )

        /** The catalogue groups by [family] for the filter; this maps that
         *  grouping onto the type the correction advice reads. */
        fun sightType(): SightType = when (family) {
            "Diopter" -> SightType.DIOPTER
            "Open sights", "Iron sights" -> SightType.OPEN_SIGHTS
            "Red dot" -> SightType.RED_DOT
            "None" -> SightType.NONE
            else -> SightType.SCOPE
        }
    }

    private fun mrad(mrads: Double) = mrads * MRAD_MOA

    val entries: List<Entry> = listOf(
        // ---- Vector Optics ----
        Entry("Vector Optics", "Continental 5-30x56", 5.0, 30.0, 56.0, ClickUnit.MRAD_TENTH, mrad(26.0), mrad(16.0), 112.0, 1.97),
        Entry("Vector Optics", "Continental 3-18x50", 3.0, 18.0, 50.0, ClickUnit.MRAD_TENTH, mrad(30.0), mrad(15.0), 100.0, 1.85),
        Entry("Vector Optics", "Continental 1-6x28", 1.0, 6.0, 28.0, ClickUnit.MRAD_TENTH, mrad(32.0), mrad(32.0), 90.0, 1.5),
        Entry("Vector Optics", "Marksman 6-24x50", 6.0, 24.0, 50.0, ClickUnit.MRAD_TENTH, mrad(24.0), mrad(12.0), 100.0, 1.7),
        Entry("Vector Optics", "Taurus 5-30x56", 5.0, 30.0, 56.0, ClickUnit.MRAD_TENTH, mrad(26.0), mrad(14.0), 112.0, 1.9),
        // ---- Vortex ----
        Entry("Vortex", "Viper PST Gen II 5-25x50", 5.0, 25.0, 50.0, ClickUnit.MRAD_TENTH, 65.0, 33.0, 100.0, 1.6),
        Entry("Vortex", "Strike Eagle 5-25x56", 5.0, 25.0, 56.0, ClickUnit.MRAD_TENTH, mrad(29.0), mrad(29.0), 110.0, 1.7),
        Entry("Vortex", "Diamondback Tactical 6-24x50", 6.0, 24.0, 50.0, ClickUnit.MRAD_TENTH, 65.0, 65.0, 100.0, 1.6),
        Entry("Vortex", "Razor HD Gen III 6-36x56", 6.0, 36.0, 56.0, ClickUnit.MRAD_TENTH, mrad(41.0), mrad(20.0), 120.0, 1.9),
        Entry("Vortex", "Crossfire II 4-12x40", 4.0, 12.0, 40.0, ClickUnit.MOA_QUARTER, 60.0, 60.0, 90.0, 1.5),
        // ---- Burris ----
        Entry("Burris", "XTR III 5.5-30x56", 5.5, 30.0, 56.0, ClickUnit.MRAD_TENTH, mrad(31.0), mrad(20.0), 112.0, 1.9),
        Entry("Burris", "Veracity PH 4-20x50", 4.0, 20.0, 50.0, ClickUnit.MRAD_TENTH, 70.0, 50.0, 100.0, 1.7),
        Entry("Burris", "Fullfield IV 6-24x50", 6.0, 24.0, 50.0, ClickUnit.MOA_QUARTER, 55.0, 55.0, 100.0, 1.6),
        // ---- Steiner ----
        Entry("Steiner", "T6Xi 5-30x56", 5.0, 30.0, 56.0, ClickUnit.MRAD_TENTH, mrad(28.5), mrad(14.0), 112.0, 1.9),
        Entry("Steiner", "M7Xi 4-28x56", 4.0, 28.0, 56.0, ClickUnit.MRAD_TENTH, mrad(26.0), mrad(12.0), 110.0, 1.9),
        Entry("Steiner", "P4Xi 4-16x56", 4.0, 16.0, 56.0, ClickUnit.MRAD_TENTH, mrad(27.0), mrad(14.0), 100.0, 1.7),
        // ---- Schmidt & Bender ----
        Entry("Schmidt & Bender", "PM II 5-25x56", 5.0, 25.0, 56.0, ClickUnit.MRAD_TENTH, mrad(26.0), mrad(15.0), 112.0, 1.97),
        Entry("Schmidt & Bender", "PM II 3-20x50", 3.0, 20.0, 50.0, ClickUnit.MRAD_TENTH, mrad(26.0), mrad(15.0), 100.0, 1.9),
        Entry("Schmidt & Bender", "Exos 3-21x56", 3.0, 21.0, 56.0, ClickUnit.MRAD_TENTH, mrad(30.0), mrad(16.0), 110.0, 1.9),
        Entry("Schmidt & Bender", "Polar T96 2.5-10x50", 2.5, 10.0, 50.0, ClickUnit.MRAD_TENTH, mrad(20.0), mrad(16.0), 90.0, 1.6),
        // ---- Bushnell ----
        Entry("Bushnell", "Elite Tactical XRS3 6-36x56", 6.0, 36.0, 56.0, ClickUnit.MRAD_TENTH, mrad(40.0), mrad(20.0), 120.0, 1.9),
        Entry("Bushnell", "Match Pro ED 5-30x56", 5.0, 30.0, 56.0, ClickUnit.MRAD_TENTH, mrad(31.0), mrad(15.0), 112.0, 1.85),
        Entry("Bushnell", "Engage 4-16x44", 4.0, 16.0, 44.0, ClickUnit.MOA_QUARTER, 70.0, 70.0, 95.0, 1.6),
        // ---- Nightforce (bonus common brand) ----
        Entry("Nightforce", "ATACR 5-25x56", 5.0, 25.0, 56.0, ClickUnit.MRAD_TENTH, mrad(27.0), mrad(14.0), 112.0, 1.97),
        Entry("Nightforce", "NX8 4-32x50", 4.0, 32.0, 50.0, ClickUnit.MRAD_TENTH, mrad(30.0), mrad(20.0), 100.0, 1.9),
        // ---- ATN digital day/night (X-Sight 5 family; FOV verified: 9.0deg
        //      at 3x base, 6.3deg at 5x base; record to microSD up to 4K,
        //      WiFi streaming via ATN Connect 5, Recoil Activated Video) ----
        // X-Sight LTV: FOV 240 ft @ 5x base = 5.0deg (verified; 720p record).
        // NO Wi-Fi streaming: the low-power Obsidian LT core drops Wi-Fi
        // entirely — LTV records to its SD card only (spec sheets list no
        // Wi-Fi row; user-confirmed). All other ATN generations stream.
        Entry("ATN", "X-Sight LTV 5-15x", 5.0, 15.0, 50.0, ClickUnit.MRAD_TENTH, mrad(30.0), mrad(30.0), 100.0, 2.2, baseFovDeg = 5.0, family = "Digital day/night", streamCapable = false),
        Entry("ATN", "X-Sight LTV 3-9x", 3.0, 9.0, 30.0, ClickUnit.MRAD_TENTH, mrad(30.0), mrad(30.0), 90.0, 2.2, baseFovDeg = 8.0, family = "Digital day/night", streamCapable = false),
        // X-Sight 4K Pro: FOV VERIFIED — 3-14x = 460 ft/9.0deg @3x base,
        // 5-20x = 240 ft/5.0deg @5x base (ATN site + multiple resellers).
        Entry("ATN", "X-Sight 4K Pro 3-14x", 3.0, 14.0, 50.0, ClickUnit.MRAD_TENTH, mrad(30.0), mrad(30.0), 100.0, 2.2, baseFovDeg = 9.0, family = "Digital day/night", streamCapable = true),
        Entry("ATN", "X-Sight 4K Pro 5-20x", 5.0, 20.0, 70.0, ClickUnit.MRAD_TENTH, mrad(30.0), mrad(30.0), 110.0, 2.2, baseFovDeg = 5.0, family = "Digital day/night", streamCapable = true),
        // X-Sight 4K Buckhunter: 5-20x FOV VERIFIED 240 ft/5.0deg; 3-14x
        // follows the same optics as the Pro 3-14x (9.0deg).
        Entry("ATN", "X-Sight 4K Buckhunter 3-14x", 3.0, 14.0, 50.0, ClickUnit.MRAD_TENTH, mrad(30.0), mrad(30.0), 100.0, 2.2, baseFovDeg = 9.0, family = "Digital day/night", streamCapable = true),
        Entry("ATN", "X-Sight 4K Buckhunter 5-20x", 5.0, 20.0, 70.0, ClickUnit.MRAD_TENTH, mrad(30.0), mrad(30.0), 110.0, 2.2, baseFovDeg = 5.0, family = "Digital day/night", streamCapable = true),
        // X-Sight II HD: 3-14x FOV VERIFIED 9.0deg (OpticsPlanet spec); 5-20x
        // pattern-derived at 5.0deg (same optics class as later 5-20x models,
        // unconfirmed) -> user should verify against the scope's own FOV.
        Entry("ATN", "X-Sight II HD 3-14x", 3.0, 14.0, 50.0, ClickUnit.MRAD_TENTH, mrad(30.0), mrad(30.0), 100.0, 2.2, baseFovDeg = 9.0, family = "Digital day/night", streamCapable = true),
        Entry("ATN", "X-Sight II HD 5-20x", 5.0, 20.0, 70.0, ClickUnit.MRAD_TENTH, mrad(30.0), mrad(30.0), 110.0, 2.2, baseFovDeg = 5.0, family = "Digital day/night", streamCapable = true),
        Entry("ATN", "X-Sight 5 3-15x", 3.0, 15.0, 50.0, ClickUnit.MRAD_TENTH, mrad(30.0), mrad(30.0), 100.0, 2.2, baseFovDeg = 9.0, family = "Digital day/night", streamCapable = true),
        Entry("ATN", "X-Sight 5 5-25x", 5.0, 25.0, 50.0, ClickUnit.MRAD_TENTH, mrad(30.0), mrad(30.0), 110.0, 2.2, baseFovDeg = 6.3, family = "Digital day/night", streamCapable = true),
        Entry("ATN", "X-Sight 5 LRF 3-15x", 3.0, 15.0, 50.0, ClickUnit.MRAD_TENTH, mrad(30.0), mrad(30.0), 100.0, 2.2, baseFovDeg = 9.0, family = "Digital day/night", streamCapable = true),
        Entry("ATN", "X-Sight 5 LRF 5-25x", 5.0, 25.0, 50.0, ClickUnit.MRAD_TENTH, mrad(30.0), mrad(30.0), 110.0, 2.2, baseFovDeg = 6.3, family = "Digital day/night", streamCapable = true),
        // ---- ATN thermal (ThOR 5; FOV verified for the 640 2-16x25 (17.6deg)
        //      and 640 5-40x75 (5.9deg); 320-sensor FOVs unverified -> 0) ----
        Entry("ATN", "ThOR 5 320 3-12x", 3.0, 12.0, 35.0, ClickUnit.MRAD_TENTH, mrad(30.0), mrad(30.0), 90.0, 2.2, baseFovDeg = 0.0, family = "Thermal", streamCapable = true),
        Entry("ATN", "ThOR 5 LRF 320 5-20x", 5.0, 20.0, 50.0, ClickUnit.MRAD_TENTH, mrad(30.0), mrad(30.0), 100.0, 2.2, baseFovDeg = 0.0, family = "Thermal", streamCapable = true),
        Entry("ATN", "ThOR 5 640 2-16x", 2.0, 16.0, 25.0, ClickUnit.MRAD_TENTH, mrad(30.0), mrad(30.0), 90.0, 2.2, baseFovDeg = 17.6, family = "Thermal", streamCapable = true),
        Entry("ATN", "ThOR 5 LRF 640 2-16x", 2.0, 16.0, 25.0, ClickUnit.MRAD_TENTH, mrad(30.0), mrad(30.0), 90.0, 2.2, baseFovDeg = 17.6, family = "Thermal", streamCapable = true),
        Entry("ATN", "ThOR 5 640 5-40x", 5.0, 40.0, 75.0, ClickUnit.MRAD_TENTH, mrad(30.0), mrad(30.0), 120.0, 2.2, baseFovDeg = 5.9, family = "Thermal", streamCapable = true)
    )

    /**
     * Sights STS needs and VTB has no reason to carry: the match diopters and
     * target-pistol rear sights of 10 m and 50 m competition, and red dots.
     *
     * Kept in a separate list, appended to [entries] below, so the ported VTB
     * block stays a verbatim copy that the next VTB revision can replace
     * wholesale. These are the entries that need [ClickUnit.MM_AT_REFERENCE]:
     * their makers publish a millimetre of point-of-impact movement at a
     * stated distance and no angular figure at all.
     */
    private val stsAdditions: List<Entry> = listOf(
        Entry("Anschütz", "6834 diopter", 1.0, 1.0, 0.0, ClickUnit.MM_AT_REFERENCE,
            60.0, 60.0, 0.0, 1.2, family = "Diopter"),
        Entry("Anschütz", "7002/20 diopter (50 m)", 1.0, 1.0, 0.0, ClickUnit.MM_AT_REFERENCE,
            60.0, 60.0, 0.0, 1.4, family = "Diopter"),
        Entry("Gehmann", "520 diopter", 1.0, 1.0, 0.0, ClickUnit.MM_AT_REFERENCE,
            60.0, 60.0, 0.0, 1.2, family = "Diopter"),
        Entry("Centra", "Duplex diopter", 1.0, 1.0, 0.0, ClickUnit.MM_AT_REFERENCE,
            60.0, 60.0, 0.0, 1.2, family = "Diopter"),
        Entry("Morini", "CM162 rear sight", 1.0, 1.0, 0.0, ClickUnit.MM_AT_REFERENCE,
            60.0, 60.0, 0.0, 0.6, family = "Open sights"),
        Entry("Pardini", "SP rear sight (25 m)", 1.0, 1.0, 0.0, ClickUnit.MM_AT_REFERENCE,
            60.0, 60.0, 0.0, 0.6, family = "Open sights"),
        Entry("Walther", "SSP rear sight (25 m)", 1.0, 1.0, 0.0, ClickUnit.MM_AT_REFERENCE,
            60.0, 60.0, 0.0, 0.6, family = "Open sights"),
        Entry("Aimpoint", "Micro T-2 (2 MOA)", 1.0, 1.0, 18.0, ClickUnit.MOA_HALF,
            50.0, 50.0, 0.0, 1.4, family = "Red dot"),
        Entry("Holosun", "507C", 1.0, 1.0, 16.0, ClickUnit.MOA_HALF,
            50.0, 50.0, 0.0, 0.9, family = "Red dot"),

        // ---- sights that cannot be clicked ----
        //
        // Both carry no travel and no click, which is the point of them. The
        // SIGHT RADIUS is deliberately left unset rather than given a
        // plausible default: it runs from about 150 mm on a service pistol to
        // 700 mm on an air rifle, and a wrong one would produce a confident
        // instruction to move the rear sight by the wrong amount. With it
        // unset the app says so and asks for the measurement, which is a
        // ruler's work and then exact.
        Entry("Generic", "Built-in iron sight", 1.0, 1.0, 0.0, ClickUnit.NONE,
            0.0, 0.0, 0.0, 0.9, family = "Iron sights"),
        Entry("Generic", "No sight", 1.0, 1.0, 0.0, ClickUnit.NONE,
            0.0, 0.0, 0.0, 0.0, family = "None")
    )

    const val ALL = "All"

    /** The VTB block plus the STS additions. Every helper below works on
     *  this, so the pickers show the whole catalogue. */
    val all: List<Entry> get() = entries + stsAdditions

    fun brands(): List<String> = listOf(ALL) + all.map { it.brand }.distinct().sorted()
    fun families(): List<String> = listOf(ALL) + all.map { it.family }.distinct()
    fun clickUnits(): List<String> = listOf(ALL, "0.1 MRAD", "1/4 MOA", "1/8 MOA",
        "1/2 MOA", "mm at a reference distance", "not adjustable")
    fun magClasses(): List<String> = listOf(ALL, "Low (\u2264 9\u00d7)", "Mid (10\u201320\u00d7)", "High (> 20\u00d7)")

    fun filter(brand: String, click: String, mag: String, family: String = ALL): List<Entry> =
        all.filter {
            (brand == ALL || it.brand == brand) &&
            (click == ALL || it.clickLabel == click) &&
            (mag == ALL || it.magClass == mag) &&
            (family == ALL || it.family == family)
        }
}
