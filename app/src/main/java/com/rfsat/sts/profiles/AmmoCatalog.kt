package com.rfsat.sts.profiles

/**
 * Built-in ammunition and pellet catalogue.
 *
 * Entries carry MANUFACTURER-PUBLISHED weight, muzzle velocity and G1
 * ballistic coefficient. Those figures come from test barrels: real firearms
 * commonly run 20-50 fps different, and BCs vary by lot — so a catalogue pick
 * is a SEED for the editable bullet fields, to be refined by chronograph data
 * and by drop calibration, not gospel. Selecting an entry resets the drag
 * calibration factor to 1.0 and the MV temperature coefficient to 0, because
 * calibration belongs to a load in a particular barrel, not to a listing.
 *
 * On pellet BCs specifically: airgun BCs are small, strongly velocity
 * dependent, and published inconsistently. Treat the pellet figures as
 * order-of-magnitude. They matter only for correction advice beyond 25 m; at
 * the 10 m ISSF distance the trajectory is flat enough that the correction is
 * pure geometry and the BC does not enter.
 */
object AmmoCatalog {

    const val SUBSONIC_LIMIT_FPS = 1125.0 // sea-level speed of sound at 15 degC

    data class Entry(
        val manufacturer: String,
        val product: String,
        val caliber: String,
        val diameterIn: Double,
        val weightGr: Double,
        val mvFps: Double,
        val bcG1: Double,
        val type: String,             // Match, HP, FMJ, OTM, WC, Pellet, Slug
        val pellet: Boolean = false
    ) {
        val subsonic: Boolean get() = mvFps < SUBSONIC_LIMIT_FPS
        val velocityClass: String get() = if (subsonic) "Subsonic" else "Supersonic"
        val powerFactor: Double get() = weightGr * mvFps / 1000.0

        fun label(): String = String.format(
            "%s %s — %s %.2fgr %s, %d fps, BC %.3f",
            manufacturer, product, caliber, weightGr, type, mvFps.toInt(), bcG1
        )

        fun toBulletProfile(): BulletProfile = BulletProfile(
            name = "$manufacturer $product $caliber ${trimGr()}gr",
            caliberDiameterIn = diameterIn,
            weightGrains = weightGr,
            muzzleVelocityFps = mvFps,
            ballisticCoefficientG1 = bcG1,
            dragCalibrationFactor = 1.0,
            isTracer = false,
            isPellet = pellet,
            mvTempCoeffMpsPerC = 0.0,
            mvRefTempC = 15.0
        )

        private fun trimGr(): String =
            if (weightGr == weightGr.toLong().toDouble()) weightGr.toLong().toString()
            else String.format("%.2f", weightGr)
    }

    val entries: List<Entry> = listOf(
        // ---- 4.5 mm / .177 match pellets ----
        Entry("JSB", "Exact Diabolo 4.50", "4.5 mm", 0.177, 8.44, 570.0, 0.021, "Pellet", true),
        Entry("H&N", "Finale Match Light", "4.5 mm", 0.177, 7.87, 570.0, 0.018, "Pellet", true),
        Entry("RWS", "R10 Match Pistol", "4.5 mm", 0.177, 7.00, 500.0, 0.016, "Pellet", true),
        Entry("RWS", "R10 Match Rifle", "4.5 mm", 0.177, 8.20, 570.0, 0.020, "Pellet", true),
        Entry("Qiang Yuan", "Olympic", "4.5 mm", 0.177, 8.20, 570.0, 0.020, "Pellet", true),

        // ---- 5.5 mm / .22 field pellets and slugs ----
        Entry("JSB", "Exact Jumbo 5.52", ".22 (5.5 mm)", 0.217, 15.89, 900.0, 0.032, "Pellet", true),
        Entry("H&N", "Baracuda Match 5.53", ".22 (5.5 mm)", 0.218, 21.14, 830.0, 0.036, "Pellet", true),
        Entry("FX", "Hybrid Slug .22", ".22 (5.5 mm)", 0.217, 22.00, 900.0, 0.075, "Slug", true),

        // ---- .22 LR match ----
        Entry("Eley", "Tenex", ".22 LR", 0.223, 40.0, 1085.0, 0.172, "Match"),
        Entry("Lapua", "Center-X", ".22 LR", 0.223, 40.0, 1073.0, 0.172, "Match"),
        Entry("Lapua", "Midas+", ".22 LR", 0.223, 40.0, 1073.0, 0.172, "Match"),
        Entry("RWS", "R50", ".22 LR", 0.223, 40.0, 1073.0, 0.172, "Match"),
        Entry("SK", "Rifle Match", ".22 LR", 0.223, 40.0, 1073.0, 0.170, "Match"),
        Entry("CCI", "Standard Velocity", ".22 LR", 0.223, 40.0, 1070.0, 0.138, "RN"),
        Entry("Federal", "Gold Medal Target", ".22 LR", 0.223, 40.0, 1200.0, 0.139, "Match"),
        Entry("Eley", "Pistol Match", ".22 LR", 0.223, 40.0, 1050.0, 0.165, "Match"),

        // ---- .223 Rem / 5.56 ----
        Entry("Hornady", "ELD Match 75gr", ".223 Rem", 0.224, 75.0, 2790.0, 0.467, "OTM"),
        Entry("Sierra", "MatchKing 77gr", ".223 Rem", 0.224, 77.0, 2750.0, 0.362, "HPBT"),
        Entry("Hornady", "BTHP Match 68gr", ".223 Rem", 0.224, 68.0, 2960.0, 0.355, "OTM"),
        Entry("Berger", "BT Target 73gr", ".223 Rem", 0.224, 73.0, 2900.0, 0.365, "OTM"),
        Entry("Federal", "American Eagle 55gr FMJ", ".223 Rem", 0.224, 55.0, 3240.0, 0.243, "FMJ"),

        // ---- .308 Win ----
        Entry("Sierra", "MatchKing 175gr", ".308 Win", 0.308, 175.0, 2600.0, 0.505, "HPBT"),
        Entry("Federal", "Gold Medal Match 168gr", ".308 Win", 0.308, 168.0, 2650.0, 0.462, "HPBT"),
        Entry("Berger", "Juggernaut OTM 185gr", ".308 Win", 0.308, 185.0, 2600.0, 0.552, "OTM"),
        Entry("Hornady", "ELD Match 178gr", ".308 Win", 0.308, 178.0, 2600.0, 0.547, "OTM"),
        Entry("Lapua", "Scenar 155gr", ".308 Win", 0.308, 155.0, 2900.0, 0.508, "HPBT"),

        // ---- 6.5 Creedmoor: same F-Class and PRS matches ----
        Entry("Hornady", "ELD Match 140gr", "6.5 Creedmoor", 0.264, 140.0, 2710.0, 0.646, "OTM"),
        Entry("Berger", "Hybrid Target 140gr", "6.5 Creedmoor", 0.264, 140.0, 2700.0, 0.618, "OTM"),

        // ---- Competition handgun ----
        Entry("Fiocchi", "Wadcutter 98gr", ".32 S&W Long", 0.312, 98.0, 750.0, 0.070, "WC"),
        Entry("Sellier & Bellot", "FMJ 124gr", "9x19", 0.355, 124.0, 1150.0, 0.148, "FMJ"),
        Entry("Geco", "FMJ 115gr", "9x19", 0.355, 115.0, 1230.0, 0.140, "FMJ")
    )

    const val ALL = "All"

    fun manufacturers(): List<String> = listOf(ALL) + entries.map { it.manufacturer }.distinct().sorted()
    fun calibers(): List<String> = listOf(ALL) + entries.map { it.caliber }.distinct().sorted()

    fun filter(manufacturer: String, caliber: String): List<Entry> =
        entries.filter {
            (manufacturer == ALL || it.manufacturer == manufacturer) &&
                (caliber == ALL || it.caliber == caliber)
        }
}
