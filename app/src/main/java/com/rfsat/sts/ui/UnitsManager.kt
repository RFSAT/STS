package com.rfsat.sts.ui

import android.content.Context

enum class UnitSystem(val label: String) {
    METRIC("Metric (m, mm, cm)"),
    IMPERIAL("Imperial (yd, in)")
}

/**
 * App-wide measurement system for DISPLAY and INPUT. All internal geometry
 * stays SI — target faces are defined in millimetres and distances in
 * metres, because that is how every governing body except the American
 * ones publishes them. This object converts only at the UI boundary.
 *
 * METRIC is the default here (the opposite of VTB's imperial default),
 * because ISSF target dimensions — the ones this app is built around — are
 * metric by definition.
 */
object UnitsManager {
    private const val PREFS = "sts_units"
    private const val KEY = "system"
    private var current: UnitSystem = UnitSystem.METRIC

    private const val M_PER_YD = 0.9144
    private const val MM_PER_IN = 25.4

    fun init(context: Context) {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        current = UnitSystem.values().firstOrNull { it.name == saved } ?: UnitSystem.METRIC
    }

    fun system(): UnitSystem = current
    fun isImperial(): Boolean = current == UnitSystem.IMPERIAL

    fun setSystem(context: Context, system: UnitSystem) {
        current = system
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, system.name).apply()
    }

    // ---- input: value typed by the user in the active unit -> SI ----

    /** Range-distance field (yd or m depending on setting) -> metres. */
    fun inputDistanceToMeters(value: Double): Double =
        if (isImperial()) value * M_PER_YD else value

    /** Group/offset field (in or mm) -> millimetres. */
    fun inputSizeToMm(value: Double): Double =
        if (isImperial()) value * MM_PER_IN else value

    fun distanceUnitLabel(): String = if (isImperial()) "yd" else "m"

    /** Target-plane sizes: group diameter, POI offset, ring dimensions. */
    fun sizeUnitLabel(): String = if (isImperial()) "in" else "mm"

    // ---- output: SI -> display value in the active unit ----

    fun displayDistance(meters: Double): Double =
        if (isImperial()) meters / M_PER_YD else meters

    fun displaySize(mm: Double): Double =
        if (isImperial()) mm / MM_PER_IN else mm

    /** Formatted "12.3 mm" / "0.48 in" — sizes need more decimals in inches. */
    fun formatSize(mm: Double): String =
        if (isImperial()) String.format("%.2f in", mm / MM_PER_IN)
        else String.format("%.1f mm", mm)

    fun formatDistance(meters: Double): String =
        if (isImperial()) String.format("%.0f yd", meters / M_PER_YD)
        else String.format("%.0f m", meters)
}
