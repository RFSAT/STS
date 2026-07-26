package com.rfsat.sts.profiles

/**
 * Built-in firearm catalogue — the STS extension of the VTB/DBM list to
 * pistols and to the centrefire chamberings this app has to score
 * (.223 Rem, .308 Win and the 6.5 mm class that shares their competitions).
 *
 * Barrel length and twist are manufacturer-published where published, and
 * the class-typical figure where a model ships in several barrel options.
 * A catalogue pick is a SEED for the editable fields, not an assertion about
 * the specific rifle in the rack: barrels get cut, twists vary by production
 * run, and the zero distance is whatever the owner set. Zero distances below
 * are the DISCIPLINE'S distance, which is the only defensible default.
 */
object RifleCatalog {

    data class Entry(
        val brand: String,
        val model: String,
        val type: FirearmType,
        val caliber: String,
        val barrelLengthIn: Double,
        val twistRateInPerTurn: Double,
        val sightHeightIn: Double,
        val zeroDistanceM: Double
    ) {
        fun label(): String =
            "$brand $model — $caliber, ${barrelLengthIn}\" barrel, 1:${fmt(twistRateInPerTurn)}\""

        private fun fmt(v: Double) = if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

        fun toRifleProfile(): RifleProfile = RifleProfile(
            name = "$brand $model",
            barrelLengthIn = barrelLengthIn,
            twistRateInPerTurn = twistRateInPerTurn,
            sightHeightIn = sightHeightIn,
            zeroDistanceM = zeroDistanceM,
            boresightOffsetXNorm = 0.0,
            boresightOffsetYNorm = 0.0,
            firearmTypeName = type.name,
            caliberLabel = caliber
        )
    }

    val entries: List<Entry> = listOf(
        // ---- 10 m air rifle (ISSF) ----
        Entry("Anschütz", "9015", FirearmType.AIR_RIFLE, "4.5 mm", 16.5, 16.0, 1.2, 10.0),
        Entry("Walther", "LG400 Alutec", FirearmType.AIR_RIFLE, "4.5 mm", 16.5, 16.0, 1.2, 10.0),
        Entry("Feinwerkbau", "800X", FirearmType.AIR_RIFLE, "4.5 mm", 16.5, 16.0, 1.2, 10.0),
        Entry("Steyr", "LG110", FirearmType.AIR_RIFLE, "4.5 mm", 16.5, 16.0, 1.2, 10.0),

        // ---- 10 m air pistol (ISSF) ----
        Entry("Steyr", "LP50", FirearmType.AIR_PISTOL, "4.5 mm", 9.0, 16.0, 0.6, 10.0),
        Entry("Morini", "CM162EI", FirearmType.AIR_PISTOL, "4.5 mm", 9.0, 16.0, 0.6, 10.0),
        Entry("Walther", "LP500", FirearmType.AIR_PISTOL, "4.5 mm", 9.0, 16.0, 0.6, 10.0),
        Entry("Feinwerkbau", "P8X", FirearmType.AIR_PISTOL, "4.5 mm", 9.0, 16.0, 0.6, 10.0),

        // ---- Field / hunting air rifles (bigger bores, longer range) ----
        Entry("AEA", "Element .22", FirearmType.AIR_RIFLE, ".22 (5.5 mm)", 16.0, 16.0, 1.7, 45.0),
        Entry("AEA", "Element .25", FirearmType.AIR_RIFLE, ".25 (6.35 mm)", 16.0, 16.0, 1.7, 45.0),
        Entry("FX", "Impact M3 .22", FirearmType.AIR_RIFLE, ".22 (5.5 mm)", 24.0, 18.0, 1.9, 45.0),

        // ---- 50 m / smallbore rifle ----
        Entry("Anschütz", "1913 Super Match", FirearmType.RIMFIRE_RIFLE, ".22 LR", 27.2, 16.5, 1.4, 50.0),
        Entry("Anschütz", "1907", FirearmType.RIMFIRE_RIFLE, ".22 LR", 26.0, 16.5, 1.4, 50.0),
        Entry("Walther", "KK500", FirearmType.RIMFIRE_RIFLE, ".22 LR", 26.0, 16.5, 1.4, 50.0),
        Entry("Feinwerkbau", "2800", FirearmType.RIMFIRE_RIFLE, ".22 LR", 26.0, 16.5, 1.4, 50.0),
        Entry("CZ", "457 Varmint", FirearmType.RIMFIRE_RIFLE, ".22 LR", 20.5, 16.0, 1.7, 50.0),
        Entry("Ruger", "Precision Rimfire", FirearmType.RIMFIRE_RIFLE, ".22 LR", 18.0, 16.0, 1.7, 50.0),
        Entry("Tikka", "T1x MTR", FirearmType.RIMFIRE_RIFLE, ".22 LR", 20.0, 16.5, 1.7, 50.0),

        // ---- 25 m / 50 m pistol ----
        Entry("Pardini", "SP Rapid Fire", FirearmType.RIMFIRE_PISTOL, ".22 Short/LR", 5.5, 16.0, 0.6, 25.0),
        Entry("Walther", "SSP-E", FirearmType.RIMFIRE_PISTOL, ".22 LR", 5.5, 16.0, 0.6, 25.0),
        Entry("Morini", "CM22M", FirearmType.RIMFIRE_PISTOL, ".22 LR", 5.5, 16.0, 0.6, 25.0),
        Entry("Hämmerli", "X-esse Sport", FirearmType.RIMFIRE_PISTOL, ".22 LR", 6.0, 16.0, 0.6, 25.0),
        Entry("Pardini", "K12 Free Pistol", FirearmType.RIMFIRE_PISTOL, ".22 LR", 11.0, 16.0, 0.6, 50.0),

        // ---- Centrefire rifle: .223 ----
        Entry("Generic", "AR-15 20\" 1:7", FirearmType.CENTREFIRE_RIFLE, ".223 Rem / 5.56", 20.0, 7.0, 2.6, 100.0),
        Entry("Generic", "AR-15 18\" 1:8", FirearmType.CENTREFIRE_RIFLE, ".223 Rem / 5.56", 18.0, 8.0, 2.6, 100.0),
        Entry("Tikka", "T3x Varmint .223", FirearmType.CENTREFIRE_RIFLE, ".223 Rem", 23.7, 8.0, 1.8, 100.0),
        Entry("Savage", "12 F/TR .223", FirearmType.CENTREFIRE_RIFLE, ".223 Rem", 30.0, 7.0, 2.0, 300.0),

        // ---- Centrefire rifle: .308 ----
        Entry("Tikka", "T3x TAC A1 .308", FirearmType.CENTREFIRE_RIFLE, ".308 Win", 20.0, 11.0, 1.9, 100.0),
        Entry("Remington", "700 Police .308", FirearmType.CENTREFIRE_RIFLE, ".308 Win", 26.0, 12.0, 1.8, 100.0),
        Entry("Accuracy Intl.", "AT .308", FirearmType.CENTREFIRE_RIFLE, ".308 Win", 24.0, 11.0, 2.0, 100.0),
        Entry("Savage", "12 F/TR .308", FirearmType.CENTREFIRE_RIFLE, ".308 Win", 30.0, 10.0, 2.0, 300.0),
        Entry("Bergara", "B-14 HMR .308", FirearmType.CENTREFIRE_RIFLE, ".308 Win", 24.0, 10.0, 1.8, 100.0),

        // ---- Centrefire rifle: 6.5 mm class, shares F-Class and PRS ----
        Entry("Ruger", "Precision Rifle 6.5 CM", FirearmType.CENTREFIRE_RIFLE, "6.5 Creedmoor", 24.0, 8.0, 2.0, 100.0),
        Entry("Tikka", "T3x CTR 6.5 CM", FirearmType.CENTREFIRE_RIFLE, "6.5 Creedmoor", 24.0, 8.0, 1.8, 100.0),

        // ---- Centrefire pistol ----
        Entry("Pardini", "HP .32", FirearmType.CENTREFIRE_PISTOL, ".32 S&W Long WC", 5.5, 16.0, 0.6, 25.0),
        Entry("CZ", "Shadow 2", FirearmType.CENTREFIRE_PISTOL, "9x19", 4.9, 9.7, 0.9, 25.0),
        Entry("Tanfoglio", "Stock II", FirearmType.CENTREFIRE_PISTOL, "9x19", 4.5, 9.8, 0.9, 25.0)
    )

    const val ALL = "All"

    fun brands(): List<String> = listOf(ALL) + entries.map { it.brand }.distinct().sorted()
    fun types(): List<String> = listOf(ALL) + FirearmType.values().map { it.label }

    fun filter(brand: String, typeLabel: String): List<Entry> =
        entries.filter {
            (brand == ALL || it.brand == brand) &&
                (typeLabel == ALL || it.type.label == typeLabel)
        }
}
