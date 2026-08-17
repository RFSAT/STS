package com.rfsat.sts.ui

import android.app.Activity
import android.content.Context
import androidx.appcompat.app.AlertDialog

/**
 * Canned labels for a string of shots, so naming one does not mean typing.
 *
 * Typing on a phone while lying behind a rifle is genuinely awkward: the
 * keyboard covers the screen, the hand that would hold the phone is on the
 * stock, and the note is usually four words that recur every session. So the
 * common cases are a tap, and the keyboard remains for everything else.
 *
 * The list is not fixed. Whatever the shooter types by hand is remembered and
 * offered next time, most recent first, because the labels that matter to one
 * discipline are not the ones that matter to another and no built-in list
 * could cover both a 10 m air rifle match and a 1000 m F-class relay.
 */
object StringLabels {

    private const val PREFS = "bas_labels"
    private const val KEY_RECENT = "recent"
    private const val KEEP = 12

    /** Offered before the shooter has taught it anything. */
    private val BUILT_IN = listOf(
        "Sighters", "Match", "Practice", "Zeroing", "Load development",
        "Cold bore", "String 1", "String 2", "String 3"
    )

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun recent(c: Context): List<String> =
        prefs(c).getString(KEY_RECENT, null)
            ?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()

    fun remember(c: Context, label: String) {
        val clean = label.trim()
        if (clean.isEmpty()) return
        // Most recent first, no duplicates: a label used twice should rise,
        // not appear twice.
        val list = (listOf(clean) + recent(c).filterNot { it.equals(clean, true) }).take(KEEP)
        prefs(c).edit().putString(KEY_RECENT, list.joinToString("\n")).apply()
    }

    fun forget(c: Context) {
        prefs(c).edit().remove(KEY_RECENT).apply()
    }

    /** What to show: everything learned, then the built-ins not already there. */
    fun options(c: Context): List<String> {
        val learned = recent(c)
        return learned + BUILT_IN.filterNot { b -> learned.any { it.equals(b, true) } }
    }

    /**
     * Offers the list and hands back the choice. [onPicked] receives the
     * label; remembering it is done here so every caller cannot forget to.
     */
    fun choose(a: Activity, onPicked: (String) -> Unit) {
        val items = options(a)
        AlertDialog.Builder(a)
            .setTitle("Label this string")
            .setItems(items.toTypedArray()) { _, which ->
                val chosen = items[which]
                remember(a, chosen)
                onPicked(chosen)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
