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
    private const val KEY_WEDGE = "axis_wedge"
    private const val KEY_FAMILY = "ring_family_fit"
    private const val KEY_PUNCTURE = "puncture_check"
    private const val KEY_OUTSIDE = "score_outside_area"
    private const val KEY_GUIDE = "aim_guide"
    private const val KEY_GUIDE_SIZE = "aim_guide_size"

    private var mode: ScaleMode = ScaleMode.CROSS_CHECK
    private var wedge: Boolean = false
    private var family: Boolean = false
    private var puncture: Boolean = false
    private var outside: Boolean = false
    private var guide: com.rfsat.sts.ui.AimGuide = com.rfsat.sts.ui.AimGuide.CROSS
    private var guideSize: Float = 0.80f

    fun init(context: Context) {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODE, null)
        mode = ScaleMode.values().firstOrNull { it.name == saved } ?: ScaleMode.CROSS_CHECK
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        wedge = p.getBoolean(KEY_WEDGE, false)
        family = p.getBoolean(KEY_FAMILY, false)
        puncture = p.getBoolean(KEY_PUNCTURE, false)
        outside = p.getBoolean(KEY_OUTSIDE, false)
        guide = com.rfsat.sts.ui.AimGuide.values()
            .firstOrNull { it.name == p.getString(KEY_GUIDE, null) }
            ?: com.rfsat.sts.ui.AimGuide.CROSS
        guideSize = p.getFloat(KEY_GUIDE_SIZE, 0.80f)
    }

    /** What is drawn over the viewfinder to line the phone up with the card. */
    fun aimGuide(): com.rfsat.sts.ui.AimGuide = guide

    fun setAimGuide(context: Context, value: com.rfsat.sts.ui.AimGuide) {
        guide = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_GUIDE, value.name).apply()
    }

    /** Guide size as a fraction of the shorter screen dimension. */
    fun aimGuideSize(): Float = guideSize

    fun setAimGuideSize(context: Context, value: Float) {
        guideSize = value.coerceIn(0.15f, 1.0f)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_GUIDE_SIZE, guideSize).apply()
    }

    /**
     * Measure the ring pitch only in a wedge about the fitted major axis.
     *
     * The radial profile averages over every bearing, including the ones a
     * residual perspective distorts. Along the TILT AXIS depth does not change
     * at all, so the scale there is exactly uniform — which is why the fitted
     * pitch drifts monotonically with tilt while the true pitch cannot.
     * Restricting the profile to that direction should remove the drift, at
     * the cost of fewer pixels per radius bin and so a noisier profile.
     *
     * Off by default until it is measured on real range photographs.
     */
    fun wedgeEnabled(): Boolean = wedge

    fun setWedge(context: Context, value: Boolean) {
        wedge = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_WEDGE, value).apply()
    }

    /** Test hook: no Context, no storage. */
    fun forceWedge(value: Boolean) { wedge = value }

    /**
     * Take the scale from circles fitted to the printed ring lines rather
     * than from the radial-profile ladder.
     *
     * On a flat scan this makes almost no difference: measured on the user's
     * card it moved the reported radii by under a tenth of a millimetre, and
     * both methods recover the 8 mm pitch to within 0.001 mm. An earlier
     * version of this note claimed a 2.1 per cent gain; that was two
     * measurements taken in different coordinate frames and is retracted —
     * see [RingFamilyFit].
     *
     * It is kept, off, because it reports a residual PER RING rather than one
     * averaged figure, which is a direct measure of whether a card is flat.
     * On angled photographs, where the ladder has always been weakest, that
     * may matter. Off by default until the range corpus says whether it does.
     */
    fun ringFamilyFit(): Boolean = family

    fun setRingFamilyFit(context: Context, value: Boolean) {
        family = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_FAMILY, value).apply()
    }

    fun forceRingFamilyFit(value: Boolean) { family = value }

    /**
     * Require a candidate to have the radial profile of a PUNCTURE before it
     * is accepted as a shot.
     *
     * A hole removes the most material at its centre, so its brightness
     * changes monotonically outwards; a printed roundel or a letter does not.
     * On the user's card this admitted all seven real holes and refused the
     * ISSF roundel, the club crest and the footer text — one of which the
     * shipped detector was reporting as a shot.
     */
    fun punctureCheck(): Boolean = puncture

    fun setPunctureCheck(context: Context, value: Boolean) {
        puncture = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_PUNCTURE, value).apply()
    }

    fun forcePunctureCheck(value: Boolean) { puncture = value }

    /**
     * Look for shots OUTSIDE the outermost scoring ring as well.
     *
     * They score nothing, so this cannot change a total. It exists because a
     * shooter who has thrown one wants to see where it went, and a plot that
     * silently omits the worst shots of a string misrepresents the group.
     * On the user's card two of the seven shots were outside the rings.
     *
     * Requires the puncture test, because everything out there is print, and
     * applies it at a stricter setting for the same reason.
     */
    fun scoreOutsideArea(): Boolean = outside

    fun setScoreOutsideArea(context: Context, value: Boolean) {
        outside = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_OUTSIDE, value).apply()
    }

    fun forceScoreOutsideArea(value: Boolean) { outside = value }

    /**
     * Half-width of the wedge, as cos(2*angle). 0.17 is +-35 degrees either
     * side of the axis, which keeps about 40 per cent of each ring's
     * circumference — enough for a stable percentile, narrow enough to
     * exclude the bearings the gradient is worst on.
     */
    const val WEDGE_COS2 = 0.17

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
