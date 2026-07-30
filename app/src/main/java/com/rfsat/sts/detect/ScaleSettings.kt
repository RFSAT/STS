package com.rfsat.sts.detect

import android.content.Context

/**
 * Where the millimetres-per-pixel scale comes from.
 *
 * Every scoring error is proportional to this number, so it is the single
 * most important quantity the app measures — a one per cent scale error is a
 * one per cent radius error on every shot, which flips any shot sitting
 * within one per cent of a ring boundary.
 */
enum class ScaleMode(val label: String) {

    /** Ring pitch from the fitted ladder alone. The behaviour up to 1.15. */
    LADDER_ONLY("Ring spacing only (before 1.16)"),

    /**
     * Scale from the measured aiming-mark radius and the face's own
     * black-to-pitch ratio. Needs no ladder at all, so it does not degrade
     * when rings are lost.
     */
    MARK_ONLY("Aiming mark only"),

    /** Both, cross-checked. */
    CROSS_CHECK("Cross-check both (recommended)")
}

/**
 * ============================================================================
 *  TWO INDEPENDENT WAYS TO MEASURE THE SCALE
 * ============================================================================
 *
 * The ladder measures the spacing between printed rings and multiplies up.
 * It is precise when the rings are found — 0 to 1.5 per cent on a square-on
 * photograph — and it degrades badly when they are not: on a card at 20 to 25
 * degrees the ring count falls and the fitted pitch drifted by 15 per cent.
 *
 * The aiming mark gives a completely separate reading. Its radius, divided by
 * the black-to-pitch ratio the catalogue states for that face, is a pitch. It
 * uses one high-contrast boundary rather than a family of faint lines, and
 * measured across tilts from 0 to 25 degrees the mark radius moved by 1.5 and
 * 2.5 per cent on the two real cards tested, against the ladder's 15 and 8.8.
 *
 * Neither is reliable enough alone to trust silently. Two independent
 * measurements that AGREE are worth far more than either, and when they
 * disagree that is the most useful signal in the app: it means one of them is
 * wrong, and the score should not be quoted as if nothing were amiss.
 *
 * This needs the face, so it cannot IDENTIFY one — used that way it would be
 * circular, since identification already uses the mark. It verifies a face
 * that has already been chosen.
 */
object ScaleSettings {

    private const val PREFS = "sts_algorithms"
    private const val KEY_MODE = "scale_mode"

    private var mode: ScaleMode = ScaleMode.CROSS_CHECK

    fun init(context: Context) {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODE, null)
        mode = ScaleMode.values().firstOrNull { it.name == saved } ?: ScaleMode.CROSS_CHECK
    }

    fun mode(): ScaleMode = mode

    /** Sets the mode without touching storage. For tests and for the offline
     *  measurement harness, which has no Context and no business writing
     *  preferences. */
    fun forceMode(value: ScaleMode) { mode = value }

    fun setMode(context: Context, value: ScaleMode) {
        mode = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MODE, value.name).apply()
    }

    /**
     * Fraction by which the two readings may differ before the scale is
     * called untrustworthy.
     *
     * 6 per cent: comfortably above the 0.8 to 1.5 per cent each method shows
     * when it is working, and well below the 8 to 15 per cent seen when the
     * ladder loses rings on an angled card — which is the case this exists to
     * catch.
     */
    const val AGREEMENT_TOLERANCE = 0.06
}
