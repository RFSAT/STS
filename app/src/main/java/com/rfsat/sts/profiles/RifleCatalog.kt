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
 * Built-in rifle catalogue (v20.22) — the third leg beside AmmoCatalog and
 * ScopeCatalog, so a full equipment change never leaves a stale rifle
 * profile behind (the gap that had Home showing the old rifle while bullet
 * and scope had moved on). Air rifles included per user priority: AEA
 * Element (.22/.25), Element Max big-bores (barrel/twist AEA-published),
 * SF Sniper, plus the common rimfire trainers.
 */
object RifleCatalog {

    data class Entry(
        val brand: String,
        val model: String,
        val type: String,             // "Air (PCP)" or "Rimfire"
        val barrelLengthIn: Double,
        val twistRateInPerTurn: Double,
        val zeroDistanceM: Double
    ) {
        fun label(): String = "$brand $model \u2014 $type, ${barrelLengthIn}\" barrel, 1:${twistRateInPerTurn.toInt()}\""
        /** VTB carries the type as a display string; STS additionally keeps
         *  a [FirearmType] and a calibre label, both derived here so the
         *  ported entry list needs no edits. */
        val firearmType: FirearmType
            get() = when {
                type.contains("Air", true) && model.contains("pistol", true) -> FirearmType.AIR_PISTOL
                type.contains("Air", true) -> FirearmType.AIR_RIFLE
                type.contains("Rimfire", true) && model.contains("pistol", true) -> FirearmType.RIMFIRE_PISTOL
                type.contains("Rimfire", true) -> FirearmType.RIMFIRE_RIFLE
                model.contains("pistol", true) -> FirearmType.CENTREFIRE_PISTOL
                else -> FirearmType.CENTREFIRE_RIFLE
            }

        /** The calibre as it appears in the model name, e.g. ".22LR". */
        val caliber: String
            get() = Regex("\\.\\d+\\s*(LR|WMR|Rem|Win)?|\\d+\\.\\d+\\s*mm", RegexOption.IGNORE_CASE)
                .find(model)?.value?.trim() ?: type

        fun toRifleProfile(): RifleProfile = RifleProfile(
            name = "$brand $model",
            barrelLengthIn = barrelLengthIn,
            twistRateInPerTurn = twistRateInPerTurn,
            sightHeightIn = RifleProfile.DEFAULT.sightHeightIn,
            zeroDistanceM = zeroDistanceM,
            boresightOffsetXNorm = 0.0,
            boresightOffsetYNorm = 0.0,
            firearmTypeName = firearmType.name,
            caliberLabel = caliber
        )
    }

    val entries: List<Entry> = listOf(
        // ---- Air rifles (AEA specs; Element Max twist 1:28 verified) ----
        Entry("AEA", "Element .22", "Air (PCP)", 16.0, 16.0, 45.0),
        Entry("AEA", "Element .25", "Air (PCP)", 16.0, 16.0, 45.0),
        Entry("AEA", "Element Max .45", "Air (PCP)", 20.0, 28.0, 50.0),
        Entry("AEA", "Element Max .50", "Air (PCP)", 20.0, 28.0, 50.0),
        Entry("AEA", "Element Max .510", "Air (PCP)", 20.0, 28.0, 50.0),
        Entry("AEA", "Element Max .58", "Air (PCP)", 22.0, 28.0, 50.0),
        Entry("AEA", "SF Sniper .22", "Air (PCP)", 18.0, 16.0, 45.0),
        Entry("AEA", "SF Sniper .25", "Air (PCP)", 18.0, 16.0, 45.0),
        Entry("AEA", "SF Sniper .30", "Air (PCP)", 18.0, 16.0, 45.0),
        // ---- Rimfire trainers ----
        Entry("Ruger", "Precision Rimfire .22LR", "Rimfire", 18.0, 16.0, 50.0),
        Entry("CZ", "457 Varmint .22LR", "Rimfire", 20.5, 16.0, 50.0),
        Entry("Tikka", "T1x MTR .22LR", "Rimfire", 20.0, 16.5, 50.0)
    )

    /**
     * Firearms STS needs beyond VTB's air and rimfire rifles: the 10 m and
     * 25 m match pistols, and the centrefire rifles of 300 m, F-Class and
     * service-rifle competition. Appended rather than merged, so the VTB
     * block above stays a verbatim copy.
     */
    private val stsAdditions: List<Entry> = listOf(
        Entry("Anschütz", "9015 Air Rifle", "Air (PCP)", 16.5, 16.0, 10.0),
        Entry("Walther", "LG400 Alutec Air Rifle", "Air (PCP)", 16.5, 16.0, 10.0),
        Entry("Feinwerkbau", "800X Air Rifle", "Air (PCP)", 16.5, 16.0, 10.0),
        Entry("Steyr", "LP50 Air Pistol", "Air (PCP)", 9.0, 16.0, 10.0),
        Entry("Morini", "CM162EI Air Pistol", "Air (PCP)", 9.0, 16.0, 10.0),
        Entry("Walther", "LP500 Air Pistol", "Air (PCP)", 9.0, 16.0, 10.0),
        Entry("Feinwerkbau", "P8X Air Pistol", "Air (PCP)", 9.0, 16.0, 10.0),
        Entry("Anschütz", "1913 Super Match .22LR", "Rimfire", 27.2, 16.5, 50.0),
        Entry("Anschütz", "1907 .22LR", "Rimfire", 26.0, 16.5, 50.0),
        Entry("Walther", "KK500 .22LR", "Rimfire", 26.0, 16.5, 50.0),
        Entry("Feinwerkbau", "2800 .22LR", "Rimfire", 26.0, 16.5, 50.0),
        Entry("Pardini", "SP Rapid Fire Pistol .22LR", "Rimfire", 5.5, 16.0, 25.0),
        Entry("Walther", "SSP-E Pistol .22LR", "Rimfire", 5.5, 16.0, 25.0),
        Entry("Morini", "CM22M Pistol .22LR", "Rimfire", 5.5, 16.0, 25.0),
        Entry("Pardini", "K12 Free Pistol .22LR", "Rimfire", 11.0, 16.0, 50.0),
        Entry("Generic", "AR-15 20in 1:7 .223 Rem", "Centrefire", 20.0, 7.0, 100.0),
        Entry("Generic", "AR-15 18in 1:8 .223 Rem", "Centrefire", 18.0, 8.0, 100.0),
        Entry("Tikka", "T3x Varmint .223 Rem", "Centrefire", 23.7, 8.0, 100.0),
        Entry("Savage", "12 F/TR .223 Rem", "Centrefire", 30.0, 7.0, 300.0),
        Entry("Tikka", "T3x TAC A1 .308 Win", "Centrefire", 20.0, 11.0, 100.0),
        Entry("Remington", "700 Police .308 Win", "Centrefire", 26.0, 12.0, 100.0),
        Entry("Accuracy Intl.", "AT .308 Win", "Centrefire", 24.0, 11.0, 100.0),
        Entry("Savage", "12 F/TR .308 Win", "Centrefire", 30.0, 10.0, 300.0),
        Entry("Bergara", "B-14 HMR .308 Win", "Centrefire", 24.0, 10.0, 100.0),
        Entry("Ruger", "Precision Rifle 6.5 Creedmoor", "Centrefire", 24.0, 8.0, 100.0),
        Entry("Tikka", "T3x CTR 6.5 Creedmoor", "Centrefire", 24.0, 8.0, 100.0),
        Entry("Pardini", "HP Pistol .32 S&W Long", "Centrefire", 5.5, 16.0, 25.0),
        Entry("CZ", "Shadow 2 Pistol 9x19", "Centrefire", 4.9, 9.7, 25.0),
        Entry("Tanfoglio", "Stock II Pistol 9x19", "Centrefire", 4.5, 9.8, 25.0)
    )

    /** The VTB block plus the STS additions. */
    val all: List<Entry> get() = entries + stsAdditions

    const val ALL = "All"
    fun brands(): List<String> = listOf(ALL) + all.map { it.brand }.distinct().sorted()
    fun types(): List<String> = listOf(ALL) + all.map { it.type }.distinct()

    fun filter(brand: String, type: String): List<Entry> =
        all.filter { (brand == ALL || it.brand == brand) && (type == ALL || it.type == type) }
}
