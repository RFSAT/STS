package android.widget

import android.view.View
import android.view.ViewGroup
import android.util.AttributeSet

open class TextView(c: android.content.Context? = null, a: AttributeSet? = null, d: Int = 0) : View(c, a, d) {
    // The real TextView is Java: a getText/setText PAIR, which Kotlin sees
    // as both a `text` property and a callable setText. A Kotlin property
    // alone generates setText itself and clashes with an explicit one, so the
    // accessors are renamed on the JVM to let both exist.
    var text: CharSequence = ""
        @JvmName("getTextProperty") get
        @JvmName("setTextProperty") set
    fun setText(t: CharSequence) {}
    var textSize: Float = 14f
    var hint: CharSequence = ""
    var maxLines: Int = 1
    var gravity: Int = 0
    var inputType: Int = 0
    val paint: android.graphics.Paint = android.graphics.Paint()
    var movementMethod: Any? = null
    fun setTextColor(c: Int) {}
    fun setTextAppearance(res: Int) {}
    fun setText(res: Int) {}
    fun setSingleLine() {}
    fun setSingleLine(b: Boolean) {}
    fun setLines(n: Int) {}
    fun setHintTextColor(c: Int) {}
    fun setTextIsSelectable(b: Boolean) {}
    var typeface: Any? = null
}

open class Button(c: android.content.Context? = null, a: AttributeSet? = null, d: Int = 0) : TextView(c, a, d)
open class EditText(c: android.content.Context? = null, a: AttributeSet? = null, d: Int = 0) : TextView(c, a, d) {
    fun setSelection(i: Int) {}
    // The real signature: (view, actionId, KeyEvent?) -> Boolean. Stated in
    // full so a lambda with the wrong arity fails here and not in CI.
    fun setOnEditorActionListener(l: ((TextView, Int, android.view.KeyEvent?) -> Boolean)?) {}
}
open class CheckBox(c: android.content.Context? = null, a: AttributeSet? = null, d: Int = 0) : Button(c, a, d) {
    var isChecked: Boolean = false
    fun setOnCheckedChangeListener(l: ((View, Boolean) -> Unit)?) {}
}
open class RadioButton(c: android.content.Context? = null, a: AttributeSet? = null, d: Int = 0) : CheckBox(c, a, d)
open class Switch(c: android.content.Context? = null, a: AttributeSet? = null, d: Int = 0) : CheckBox(c, a, d)
open class ImageView(c: android.content.Context? = null, a: AttributeSet? = null, d: Int = 0) : View(c, a, d) {
    fun setImageBitmap(b: android.graphics.Bitmap?) {}
    fun setImageResource(r: Int) {}
    fun setImageDrawable(d: Any?) {}
}
open class ImageButton(c: android.content.Context? = null, a: AttributeSet? = null, d: Int = 0) : ImageView(c, a, d)
open class ProgressBar(c: android.content.Context? = null, a: AttributeSet? = null, d: Int = 0) : View(c, a, d) { var progress: Int = 0 }
open class SeekBar(c: android.content.Context? = null, a: AttributeSet? = null, d: Int = 0) : ProgressBar(c, a, d) {
    var max: Int = 100
    fun setOnSeekBarChangeListener(l: OnSeekBarChangeListener?) {}
    interface OnSeekBarChangeListener {
        fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean)
        fun onStartTrackingTouch(seekBar: SeekBar?)
        fun onStopTrackingTouch(seekBar: SeekBar?)
    }
}
open class LinearLayout(c: android.content.Context? = null, a: AttributeSet? = null, d: Int = 0) : ViewGroup(c, a, d) {
    var orientation: Int = 0
    class LayoutParams(w: Int, h: Int) { var weight: Float = 0f
        companion object { const val MATCH_PARENT = -1; const val WRAP_CONTENT = -2 } }
    companion object { const val VERTICAL = 1; const val HORIZONTAL = 0 }
}
open class FrameLayout(c: android.content.Context? = null, a: AttributeSet? = null, d: Int = 0) : ViewGroup(c, a, d)
open class RelativeLayout(c: android.content.Context? = null, a: AttributeSet? = null, d: Int = 0) : ViewGroup(c, a, d)
open class ScrollView(c: android.content.Context? = null, a: AttributeSet? = null, d: Int = 0) : ViewGroup(c, a, d) {
    fun fullScroll(direction: Int): Boolean = true
}
open class TableLayout(c: android.content.Context? = null, a: AttributeSet? = null, d: Int = 0) : ViewGroup(c, a, d)
open class TableRow(c: android.content.Context? = null, a: AttributeSet? = null, d: Int = 0) : ViewGroup(c, a, d)
open class GridLayout(c: android.content.Context? = null, a: AttributeSet? = null, d: Int = 0) : ViewGroup(c, a, d)

abstract class BaseAdapter {
    abstract fun getCount(): Int
    abstract fun getItem(i: Int): Any?
    abstract fun getItemId(i: Int): Long
    abstract fun getView(position: Int, convertView: View?, parent: ViewGroup): View
    open fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
        getView(position, convertView, parent)
    fun notifyDataSetChanged() {}
}

open class ArrayAdapter<T>(c: android.content.Context, res: Int, items: List<T>) {
    constructor(c: android.content.Context, res: Int) : this(c, res, emptyList<T>())
    open fun getCount(): Int = 0
    open fun getItem(i: Int): T? = null
    open fun getView(position: Int, convertView: View?, parent: ViewGroup): View = View()
    fun setDropDownViewResource(res: Int) {}
    fun add(item: T) {}
}

open class AdapterView<T>(c: android.content.Context? = null) : ViewGroup(c) {
    var adapter: Any? = null
    var onItemSelectedListener: OnItemSelectedListener? = null
    val selectedItemPosition: Int = 0
    val selectedItem: Any? = null
    fun setSelection(i: Int) {}
    fun setOnItemClickListener(l: OnItemClickListener?) {}
    interface OnItemSelectedListener {
        fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long)
        fun onNothingSelected(parent: AdapterView<*>?)
    }
    fun interface OnItemClickListener {
        fun onItemClick(parent: AdapterView<*>?, view: View?, position: Int, id: Long)
    }
}
open class Spinner(c: android.content.Context? = null) : AdapterView<Any>()
open class ListView(c: android.content.Context? = null) : AdapterView<Any>() { var choiceMode: Int = 0 }

class Toast {
    companion object {
        const val LENGTH_SHORT = 0
        const val LENGTH_LONG = 1
        @JvmStatic fun makeText(c: android.content.Context, s: CharSequence, d: Int): Toast = Toast()
    }
    fun show() {}
}
