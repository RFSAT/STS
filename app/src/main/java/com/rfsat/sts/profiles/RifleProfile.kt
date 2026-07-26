package com.rfsat.sts.profiles

/**
 * The firearm half of a profile set.
 *
 * NAMING, DELIBERATELY. The class is called RifleProfile and its fields carry
 * the VTB/DBM names even though STS also covers pistols. That is not
 * carelessness: the persisted format across all three apps is Gson reflection
 * over field names, so keeping RifleProfile / barrelLengthIn /
 * twistRateInPerTurn / sightHeightIn / zeroDistanceM identical means a
 * profile set exported from VTB or DBM imports into STS unchanged, and back
 * again. Renaming to "FirearmProfile" would have bought a nicer identifier at
 * the cost of every existing user's saved equipment. [firearmType] carries
 * the rifle/pistol distinction instead.
 */
enum class FirearmType(val label: String, val isPistol: Boolean, val isAir: Boolean) {
    AIR_RIFLE("Air rifle", false, true),
    AIR_PISTOL("Air pistol", true, true),
    RIMFIRE_RIFLE("Rimfire rifle", false, false),
    RIMFIRE_PISTOL("Rimfire pistol", true, false),
    CENTREFIRE_RIFLE("Centrefire rifle", false, false),
    CENTREFIRE_PISTOL("Centrefire pistol", true, false);

    companion object {
        fun fromLabel(label: String): FirearmType =
            values().firstOrNull { it.label.equals(label, ignoreCase = true) } ?: CENTREFIRE_RIFLE
    }
}

data class RifleProfile(
    val name: String = "Anschütz 9015 Air Rifle",
    val barrelLengthIn: Double = 16.5,
    /** Inches per turn. Air rifles are commonly 1:16 to 1:18; .223 varies
     *  1:7 to 1:12 and decides which bullet weights stabilise; .308 is
     *  usually 1:10 to 1:12. Informational for scoring, but carried so a
     *  profile set is a complete description of the rig. */
    val twistRateInPerTurn: Double = 16.0,
    /** Sight centreline over the bore, inches. Used to convert a sight
     *  correction into the right direction at very short range, where the
     *  bore and the line of sight have not yet converged. */
    val sightHeightIn: Double = 1.5,
    /** Distance the sight was zeroed at, metres. */
    val zeroDistanceM: Double = 10.0,
    /** LEGACY (pre-VTB v9.0) zero distance in yards. Kept ONLY so Gson can
     *  read profiles exported by old builds of VTB/DBM; migrated to
     *  [zeroDistanceM] in ProfileRepository. 0.0 means "not present". */
    @Deprecated("Use zeroDistanceM") val zeroDistanceYards: Double = 0.0,
    /** Fine calibration for a phone or spotting camera whose optical axis is
     *  not exactly on the target centre. Normalised to frame width/height
     *  (-0.5..0.5); 0,0 = dead centre. */
    val boresightOffsetXNorm: Double = 0.0,
    val boresightOffsetYNorm: Double = 0.0,
    // ---- fields added by STS. All have safe defaults, so a profile set
    // written by VTB or DBM (which lack them) loads without special-casing:
    // Gson leaves an absent field at its JVM default, and the getters below
    // treat blank/zero as "not specified".
    /** Stored as the enum NAME, not the ordinal — reordering the enum must
     *  not silently reinterpret saved data. */
    val firearmTypeName: String = FirearmType.AIR_RIFLE.name,
    /** Display chambering, e.g. ".22 LR", ".223 Rem", ".308 Win", "4.5 mm". */
    val caliberLabel: String = "4.5 mm",
    /** Trigger weight, grams. Several disciplines impose a minimum (ISSF
     *  Rapid Fire Pistol 1000 g, Free Pistol none, air 500 g). Recorded so
     *  the equipment-control note on the session report is complete. */
    val triggerWeightG: Double = 0.0
) {
    val firearmType: FirearmType
        get() = runCatching { FirearmType.valueOf(firearmTypeName) }.getOrDefault(FirearmType.AIR_RIFLE)

    val isPistol: Boolean get() = firearmType.isPistol

    fun label(): String = "$name — ${firearmType.label}, $caliberLabel"

    companion object {
        val DEFAULT = RifleProfile()
    }
}
