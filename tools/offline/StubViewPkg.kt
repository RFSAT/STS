/*
 * A hand-written slice of the Android framework, enough to TYPE-CHECK the
 * activities offline. One package per file: Kotlin allows a single package
 * declaration per file, and concatenating stubs that each declare their own
 * silently puts every later class in the first one's package.
 *
 * Deliberately NARROW. Every class carries only members the app actually
 * uses, because a stub that answered to anything would resolve a typo as
 * readily as a real name — the failure this harness exists to catch. Using a
 * genuinely new framework API means adding a line here; that is the intended
 * cost, and it is small next to finding out from CI.
 */
package android.view

import android.util.AttributeSet

open class View(
    context: android.content.Context? = null,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) {
    var visibility: Int = 0
    var isEnabled: Boolean = true
    var isClickable: Boolean = false
    var isFocusable: Boolean = false
    var alpha: Float = 1f
    open val width: Int = 0
    open val height: Int = 0
    val paddingLeft: Int = 0
    val paddingRight: Int = 0
    val paddingTop: Int = 0
    val paddingBottom: Int = 0
    val context: android.content.Context = context ?: android.content.Context()
    fun <T : View> findViewById(id: Int): T = @Suppress("UNCHECKED_CAST") (View() as T)
    // A real View.layoutParams is a settable property whose value carries a
    // height. The stub had it as a read-only Any?, which compiled anything
    // that only READ it and rejected the first code that resized a view —
    // exactly the case it exists to check.
    open class LayoutParams(var width: Int = 0, var height: Int = 0)
    var layoutParams: LayoutParams = LayoutParams()
    val resources: android.content.Resources = android.content.Resources()
    val parent: ViewParent? = null
    val suggestedMinimumWidth: Int = 0
    val suggestedMinimumHeight: Int = 0
    fun setMeasuredDimension(w: Int, h: Int) {}
    val isShown: Boolean = true
    fun post(r: Runnable): Boolean = true
    fun postDelayed(r: Runnable, delayMs: Long): Boolean = true
    fun setBackgroundColor(c: Int) {}
    fun dispatchTouchEvent(e: MotionEvent): Boolean = false

    fun setOnClickListener(l: ((View) -> Unit)?) {}
    fun setOnLongClickListener(l: ((View) -> Boolean)?) {}
    fun setOnTouchListener(l: ((View, MotionEvent) -> Boolean)?) {}
    fun invalidate() {}
    fun requestLayout() {}
    fun postInvalidate() {}
    fun setPadding(l: Int, t: Int, r: Int, b: Int) {}
    fun getLocationOnScreen(a: IntArray) {}
    open fun onDraw(canvas: android.graphics.Canvas) {}
    open fun onTouchEvent(e: MotionEvent): Boolean = false
    open fun performClick(): Boolean = false
    open fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {}
    open fun onMeasure(w: Int, h: Int) {}

    companion object {
        const val VISIBLE = 0; const val INVISIBLE = 4; const val GONE = 8
        const val FOCUS_DOWN = 130; const val FOCUS_UP = 33
        @JvmStatic fun resolveSize(size: Int, spec: Int): Int = size
        @JvmStatic fun getDefaultSize(size: Int, spec: Int): Int = size
    }
}

open class ViewGroup(
    context: android.content.Context? = null,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    fun addView(v: View) {}
    fun addView(v: View, i: Int) {}
    fun removeAllViews() {}
    fun getChildAt(i: Int): View = View()
    val childCount: Int = 0
}

class LayoutInflater {
    companion object { @JvmStatic fun from(c: android.content.Context): LayoutInflater = LayoutInflater() }
    fun inflate(res: Int, root: ViewGroup?, attach: Boolean): View = View()
    fun inflate(res: Int, root: ViewGroup?): View = View()
}

class MotionEvent {
    val action: Int = 0
    val x: Float = 0f
    val y: Float = 0f
    val pointerCount: Int = 0
    val rawX: Float = 0f
    val rawY: Float = 0f
    val actionMasked: Int = 0
    val eventTime: Long = 0L
    val actionIndex: Int = 0
    fun getX(i: Int): Float = 0f
    fun getY(i: Int): Float = 0f
    companion object {
        const val ACTION_DOWN = 0; const val ACTION_UP = 1; const val ACTION_MOVE = 2
        const val ACTION_CANCEL = 3; const val ACTION_POINTER_DOWN = 5; const val ACTION_POINTER_UP = 6
    }
}

class MenuItem { val itemId: Int = 0; var isChecked: Boolean = false }
class Menu {
    fun findItem(id: Int): MenuItem? = null
    fun size(): Int = 0
    fun getItem(i: Int): MenuItem = MenuItem()
    fun setGroupCheckable(g: Int, c: Boolean, e: Boolean) {}
}
open class ScaleGestureDetector(c: android.content.Context, l: Any) {
    val scaleFactor: Float = 1f
    fun onTouchEvent(e: MotionEvent): Boolean = false
    val isInProgress: Boolean = false
    interface OnScaleGestureListener
    open class SimpleOnScaleGestureListener : OnScaleGestureListener {
        open fun onScale(detector: ScaleGestureDetector): Boolean = false
    }
}
class TextureView(c: android.content.Context? = null, a: AttributeSet? = null, d: Int = 0) : View(c, a, d) {
    var surfaceTextureListener: SurfaceTextureListener? = null
    val bitmap: android.graphics.Bitmap? = null
    val surfaceTexture: android.graphics.SurfaceTexture? = null
    interface SurfaceTextureListener {
        fun onSurfaceTextureAvailable(s: android.graphics.SurfaceTexture, w: Int, h: Int)
        fun onSurfaceTextureSizeChanged(s: android.graphics.SurfaceTexture, w: Int, h: Int)
        fun onSurfaceTextureDestroyed(s: android.graphics.SurfaceTexture): Boolean
        fun onSurfaceTextureUpdated(s: android.graphics.SurfaceTexture)
    }
}
interface ViewParent { fun requestDisallowInterceptTouchEvent(b: Boolean) }

class WindowManager {
    class LayoutParams {
        var layoutInDisplayCutoutMode: Int = 0
        companion object {
            const val LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES = 1
            const val LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS = 3
        }
    }
}

class Surface(t: Any?) { fun release() {} }

/** Only ever received and ignored, but the type has to exist for the
 *  editor-action listener to carry its real signature. */
class KeyEvent
object Gravity { const val CENTER = 17; const val START = 8388611; const val END = 8388613
                 const val TOP = 48; const val BOTTOM = 80; const val CENTER_VERTICAL = 16 }
