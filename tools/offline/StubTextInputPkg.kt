package android.text

// Only the two flags the API-key dialog sets.
object InputType {
    const val TYPE_CLASS_TEXT = 1
    const val TYPE_TEXT_VARIATION_VISIBLE_PASSWORD = 144
}

// Spannable text, enough of it for the hanging-indent bullet lists.
interface Spanned { companion object { const val SPAN_EXCLUSIVE_EXCLUSIVE = 33 } }

class SpannableStringBuilder : CharSequence {
    private val sb = StringBuilder()
    override val length: Int get() = sb.length
    override fun get(index: Int): Char = sb[index]
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        sb.subSequence(startIndex, endIndex)
    override fun toString(): String = sb.toString()
    fun append(t: CharSequence): SpannableStringBuilder { sb.append(t); return this }
    fun setSpan(what: Any?, start: Int, end: Int, flags: Int) {}
}
