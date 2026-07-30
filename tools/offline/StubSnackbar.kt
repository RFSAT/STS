package com.google.android.material.snackbar

class Snackbar private constructor() {
    fun show() {}
    fun setAction(t: CharSequence, l: ((android.view.View) -> Unit)?): Snackbar = this
    fun setTextMaxLines(n: Int): Snackbar = this
    val view: android.view.View = android.view.View()
    fun setTextColor(c: Int): Snackbar = this
    fun setBackgroundTint(c: Int): Snackbar = this
    companion object {
        const val LENGTH_SHORT = 0
        const val LENGTH_LONG = 0
        const val LENGTH_INDEFINITE = -2
        @JvmStatic fun make(v: android.view.View, t: CharSequence, d: Int): Snackbar = Snackbar()
    }
}
