package com.rfsat.sts.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.rfsat.sts.R

/**
 * Shared chrome for every screen: theme application, immersive full screen,
 * IME insets, themed notifications, the bottom tab bar and swipe navigation.
 *
 * Every one of these is wrapped in runCatching: shared startup code must
 * never be able to kill a screen it merely decorates.
 */
open class BaseActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        runCatching { ThemeManager.apply(this) } // BEFORE super/setContentView
        super.onCreate(savedInstanceState)
        runCatching { enterFullScreen() }
    }

    override fun onResume() {
        super.onResume()
        runCatching { applyImeInsets() }
    }

    private var imeInsetsAttached = false

    /**
     * targetSdk 36: Android 15+ ENFORCES edge-to-edge, so the framework no
     * longer fits content inside the system window and the default "resize
     * for the keyboard" behaviour does not apply. Without this, the numeric
     * fields on Session and Settings end up underneath the IME.
     *
     * Padding by the IME inset only — NOT by systemBars. The bars are hidden
     * in immersive mode, and padding by them would make the layout jump
     * whenever a transient swipe revealed them.
     */
    private fun applyImeInsets() {
        if (imeInsetsAttached) return
        val content = findViewById<android.view.View>(android.R.id.content) ?: return
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
            val imeBottom = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, imeBottom)
            insets
        }
        androidx.core.view.ViewCompat.requestApplyInsets(content)
        imeInsetsAttached = true
    }

    /**
     * Themed replacement for Toast. System Toasts render in the OS palette —
     * bright white text regardless of the app theme, which in the night
     * modes destroys the very dark adaptation those themes exist to protect.
     * This Snackbar is recoloured from the ACTIVE theme.
     */
    fun notifyUser(message: String) {
        val root = findViewById<android.view.View>(android.R.id.content) ?: return
        val sb = com.google.android.material.snackbar.Snackbar.make(
            root, message, com.google.android.material.snackbar.Snackbar.LENGTH_LONG
        )
        fun attrColor(attr: Int, fallback: Int): Int {
            val tv = android.util.TypedValue()
            return if (theme.resolveAttribute(attr, tv, true)) tv.data else fallback
        }
        sb.view.setBackgroundColor(
            attrColor(com.google.android.material.R.attr.colorSurface, 0xFF202020.toInt())
        )
        sb.setTextColor(attrColor(android.R.attr.textColorPrimary, 0xFFF2F7F0.toInt()))
        sb.setTextMaxLines(4)
        sb.show()
    }

    /**
     * As [notifyUser], with an UNDO that restores the shots as they were
     * before the change that produced this message.
     *
     * Deliberately the same Snackbar and not a confirmation dialog. A shot is
     * deleted because the detector misread the card, and the shooter is
     * usually right that it should go — so asking first is friction on the
     * common case. Offering the reversal AFTER the fact costs nothing when
     * the deletion was correct, and rescues the case where it was not,
     * including the one no confirmation can catch: deleting the wrong shot
     * and only noticing when the score changes.
     */
    fun notifyUndoable(message: String, onUndone: () -> Unit) {
        val root = findViewById<android.view.View>(android.R.id.content) ?: return
        val sb = com.google.android.material.snackbar.Snackbar.make(
            root, message, com.google.android.material.snackbar.Snackbar.LENGTH_LONG
        )
        fun attrColor(attr: Int, fallback: Int): Int {
            val tv = android.util.TypedValue()
            return if (theme.resolveAttribute(attr, tv, true)) tv.data else fallback
        }
        sb.view.setBackgroundColor(
            attrColor(com.google.android.material.R.attr.colorSurface, 0xFF202020.toInt())
        )
        sb.setTextColor(attrColor(android.R.attr.textColorPrimary, 0xFFF2F7F0.toInt()))
        sb.setTextMaxLines(4)
        sb.setActionTextColor(
            attrColor(com.google.android.material.R.attr.colorPrimary, 0xFF7FD1A4.toInt()))
        sb.setAction("UNDO") {
            val what = com.rfsat.sts.scoring.ScoringSession.undo()
            onUndone()
            if (what != null) notifyUser("Undone: $what.")
        }
        sb.show()
    }

    /** Bars can transiently reappear (keyboard dismiss, edge swipe, dialog)
     *  — re-hide whenever the window regains focus so the app STAYS full
     *  screen, not merely starts that way. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) runCatching { enterFullScreen() }
    }

    fun fullScreenEnabled(): Boolean =
        getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("full_screen", true)

    private fun enterFullScreen() {
        val controller = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
        if (fullScreenEnabled()) {
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            // Bars visible: the app still draws edge to edge (enforced from
            // targetSdk 35), so applyImeInsets keeps text fields clear of
            // the keyboard exactly as in full-screen mode.
            controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
        // Draw into the camera-cutout strip too — otherwise a letterboxed
        // black band remains where the status bar used to be. ALWAYS rather
        // than SHORT_EDGES: the latter is deprecated in Android 15 (Play
        // flags it) and ALWAYS matches what 15+ does implicitly, while still
        // doing the work on Android 9-14.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
    }

    /**
     * Wires the shared bottom toolbar if this screen's layout includes one.
     * Call AFTER setContentView, with this screen's own nav item id (or 0
     * for screens outside the tab set, which deselects the whole group).
     */
    protected fun setupBottomNav(selectedItemId: Int) {
        // Exit lives OUTSIDE the BottomNavigationView because Material caps
        // its menu at 5 items (a 6th throws at inflate time).
        findViewById<android.view.View>(R.id.btnExit)?.setOnClickListener {
            finishAffinity() // close the whole task, not just this screen
        }
        val nav = findViewById<BottomNavigationView>(R.id.bottomNav) ?: return
        if (nav.menu.findItem(selectedItemId) != null) {
            nav.selectedItemId = selectedItemId // set BEFORE the listener, to avoid a callback loop
        } else {
            nav.menu.setGroupCheckable(0, true, false)
            for (i in 0 until nav.menu.size()) nav.menu.getItem(i).isChecked = false
            nav.menu.setGroupCheckable(0, true, true)
        }
        navSelectedItemId = selectedItemId
        nav.setOnItemSelectedListener { item ->
            if (item.itemId == selectedItemId) return@setOnItemSelectedListener true
            openTab(item.itemId)
            false
        }
    }

    /** Opens a bottom-nav tab — shared by taps and swipe navigation. */
    private fun openTab(itemId: Int) {
        val target = when (itemId) {
            R.id.nav_home -> MainActivity::class.java
            R.id.nav_session -> com.rfsat.sts.detect.SessionActivity::class.java
            R.id.nav_results -> com.rfsat.sts.results.ResultsActivity::class.java
            R.id.nav_targets -> com.rfsat.sts.targets.TargetActivity::class.java
            R.id.nav_settings -> com.rfsat.sts.profiles.ProfileActivity::class.java
            else -> return
        }
        val intent = Intent(this, target)
        if (target == MainActivity::class.java) intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(intent)
        if (this !is MainActivity) finish() // keep the back stack flat when hopping tabs
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0) // no tab-hop animation
    }

    // ---- Swipe navigation: slide left/right to switch tabs ----
    //
    // A manual fling check in dispatchTouchEvent rather than a
    // GestureDetector: deterministic thresholds, no listener-signature
    // coupling, and it observes without consuming, so child views behave
    // exactly as before. A swipe must be long (>=100 dp), fast (>=500 dp/s)
    // and strongly horizontal (|dx| > 2.5|dy|); swipe left opens the tab to
    // the RIGHT (pager convention). Screens can exempt interactive
    // horizontal controls — the Session screen's zoom slider and the
    // Results target plot both do — via swipeExemptViews().

    private var navSelectedItemId: Int = 0

    private val tabOrder: IntArray = intArrayOf(
        R.id.nav_home, R.id.nav_session, R.id.nav_results, R.id.nav_targets, R.id.nav_settings
    )
    private var swipeDownX = 0f
    private var swipeDownY = 0f
    private var swipeDownT = 0L
    private var swipeBlocked = false

    /** Views whose touches must never become tab swipes. */
    protected open fun swipeExemptViews(): List<android.view.View> = emptyList()

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                swipeDownX = ev.rawX; swipeDownY = ev.rawY; swipeDownT = ev.eventTime
                swipeBlocked = runCatching {
                    swipeExemptViews().any { it.isShown && hitInside(it, ev) }
                }.getOrDefault(false)
            }
            android.view.MotionEvent.ACTION_UP -> if (!swipeBlocked) runCatching { maybeTabSwipe(ev) }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun hitInside(v: android.view.View, ev: android.view.MotionEvent): Boolean {
        val loc = IntArray(2); v.getLocationOnScreen(loc)
        return ev.rawX >= loc[0] && ev.rawX <= loc[0] + v.width &&
            ev.rawY >= loc[1] && ev.rawY <= loc[1] + v.height
    }

    private fun maybeTabSwipe(ev: android.view.MotionEvent) {
        val d = resources.displayMetrics.density
        val dx = ev.rawX - swipeDownX
        val dy = ev.rawY - swipeDownY
        if (kotlin.math.abs(dx) < 100f * d) return
        if (kotlin.math.abs(dx) < 2.5f * kotlin.math.abs(dy)) return
        val dtMs = (ev.eventTime - swipeDownT).coerceAtLeast(1)
        if (kotlin.math.abs(dx) * 1000f / dtMs < 500f * d) return
        val idx = tabOrder.indexOf(navSelectedItemId)
        if (idx < 0) return
        val next = idx + if (dx < 0) 1 else -1 // finger left => next tab
        if (next in tabOrder.indices) openTab(tabOrder[next])
    }

    companion object {
        const val PREFS = "sts_prefs"
    }
}
