package com.rfsat.sts.ui

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.LeadingMarginSpan

/**
 * Bullet lists whose wrapped lines line up under the TEXT, not under the
 * bullet.
 *
 * Written because every list in the app was a plain string starting "• ", and
 * a plain string has no idea a bullet is there: the second line of any item
 * that wrapped ran back to the left margin, under the dot, and a four-item
 * list read as eight items of which four began with a dot. The warnings on
 * Results and the "why registration failed" dialog on Import both did it.
 *
 * A hanging indent cannot be expressed in the text itself — spaces do not
 * survive word wrap. It needs a paragraph span, which is what this is: first
 * line at the margin, every later line of the SAME item indented by the width
 * of the bullet and its gap.
 *
 * The indent is measured from the text size rather than fixed in dp, so it
 * stays right on a screen whose font scale the shooter has changed.
 */
object Bullets {

    private const val MARK = "•  "

    /** Indent as a multiple of the text size: the bullet plus its two spaces
     *  come to a little over one-and-a-half characters in every font shipped
     *  with Android. */
    private const val INDENT_EMS = 1.6f

    fun list(items: List<String>, textSizePx: Float, gap: String = "\n\n"): CharSequence {
        val indent = (textSizePx * INDENT_EMS).toInt().coerceAtLeast(1)
        val sb = SpannableStringBuilder()
        for ((i, item) in items.withIndex()) {
            val start = sb.length
            sb.append(MARK).append(item)
            sb.setSpan(
                LeadingMarginSpan.Standard(0, indent),
                start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            if (i < items.size - 1) sb.append(gap)
        }
        return sb
    }
}
