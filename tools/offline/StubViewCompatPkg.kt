package androidx.core.view

import android.view.View

object ViewCompat {
    @JvmStatic fun setOnApplyWindowInsetsListener(v: View, l: (View, WindowInsetsCompat) -> WindowInsetsCompat) {}
    @JvmStatic fun requestApplyInsets(v: View) {}
}

class WindowInsetsCompat {
    fun getInsets(type: Int): Insets = Insets()
    class Insets { val bottom: Int = 0; val top: Int = 0; val left: Int = 0; val right: Int = 0 }
    object Type { @JvmStatic fun ime(): Int = 0; @JvmStatic fun systemBars(): Int = 0 }
}

class WindowInsetsControllerCompat(window: Any?, view: View) {
    var systemBarsBehavior: Int = 0
    fun hide(types: Int) {}
    fun show(types: Int) {}
    companion object { const val BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE = 2 }
}

object WindowCompat {
    @JvmStatic fun setDecorFitsSystemWindows(window: Any?, fits: Boolean) {}
    @JvmStatic fun getInsetsController(window: Any?, view: View): WindowInsetsControllerCompat =
        WindowInsetsControllerCompat(window, view)
}
