package com.rfsat.sts.ui

import android.app.Activity
import android.content.Context
import com.rfsat.sts.R

enum class ThemeMode(val label: String, val styleRes: Int) {
    DARK("Dark (default)", R.style.Theme_STS_Dark),
    DAY("Day (high contrast)", R.style.Theme_STS_Day),
    NIGHT_GREEN("Night — Green", R.style.Theme_STS_NightGreen),
    NIGHT_RED("Night — Red", R.style.Theme_STS_NightRed)
}

/**
 * Dark is the default: on OLED panels dark pixels draw less power, and a
 * dim UI preserves the shooter's dark adaptation on an evening range. Day
 * mode flips to a bright high-contrast palette for direct-sunlight
 * legibility on the firing point. The two night modes render the whole UI
 * in green or red only — red in particular minimally impacts scotopic
 * (night) vision.
 *
 * NOTE for the Results screen: the target plot deliberately does NOT follow
 * the theme. A scoring plot is a document that gets screenshotted and
 * shared, so its paper/ink/shot colours are fixed (see plot_* in colors.xml).
 */
object ThemeManager {
    private const val PREFS = "sts_theme"
    private const val KEY = "mode"
    private var current: ThemeMode = ThemeMode.DARK

    fun init(context: Context) {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        current = ThemeMode.values().firstOrNull { it.name == saved } ?: ThemeMode.DARK
    }

    fun mode(): ThemeMode = current

    fun setMode(context: Context, mode: ThemeMode) {
        current = mode
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, mode.name).apply()
    }

    /** Must be called BEFORE setContentView. */
    fun apply(activity: Activity) {
        activity.setTheme(current.styleRes)
    }
}
