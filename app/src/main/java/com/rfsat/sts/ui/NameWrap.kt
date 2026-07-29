package com.rfsat.sts.ui

import android.graphics.Paint

/**
 * Breaks a catalogue name at its dash when it will not fit on one line.
 *
 * Catalogue names are written "thing — qualifiers": "Anschutz 1907 — match
 * rifle, 22 LR, 26in barrel". Left alone, a TextView wraps them wherever the
 * line happens to run out, which lands mid-qualifier and makes a list of them
 * hard to scan. Broken at the dash, the name sits on the first line and
 * everything describing it underneath, so the eye can run down the names.
 *
 * The break is only made when it is needed. A name that already fits gets no
 * second line, because a list of one-line entries is easier to read than a
 * list of two-line ones, and most names do fit.
 */
object NameWrap {

    /** The em dash the catalogues separate a name from its qualifiers with. */
    private const val DASH = '—'

    /**
     * [text] with the part after its first dash moved to its own line, if it
     * does not fit on one.
     *
     * Takes a PREDICATE rather than a Paint and a width. The decision is pure
     * string logic and the measurement is the only part that needs Android,
     * so keeping them apart lets the logic be tested for real. It had to be:
     * under plain unit tests android.jar is stubbed and Paint.measureText
     * returns 0.0f, so a test written against the Paint overload silently
     * concluded that every string fits and asserted nothing at all.
     */
    fun wrapAtDash(text: String, fitsOnOneLine: (String) -> Boolean): String {
        if (fitsOnOneLine(text)) return text
        val cut = text.indexOf(DASH)
        if (cut <= 0) return text                      // nothing to break at
        val head = text.substring(0, cut).trimEnd()
        val tail = text.substring(cut).trimStart()     // keeps the dash
        if (head.isEmpty() || tail.isEmpty()) return text
        return "$head\n$tail"
    }

    /** As above, measuring with a real [paint] against a real row width. */
    fun wrapAtDash(text: String, paint: Paint, availableWidthPx: Float): String =
        if (availableWidthPx <= 0f) text
        else wrapAtDash(text) { paint.measureText(it) <= availableWidthPx }

    /**
     * Just the part before the dash.
     *
     * For the home screen, where the setup is a column of aligned rows and a
     * full catalogue name wraps into an unreadable block. The qualifiers are
     * always visible one tap away under Settings.
     */
    fun shortName(text: String): String {
        val cut = text.indexOf(DASH)
        return if (cut <= 0) text.trim() else text.substring(0, cut).trimEnd()
    }
}
