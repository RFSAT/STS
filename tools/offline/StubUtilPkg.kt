package android.util

class TypedValue { var data: Int = 0; var resourceId: Int = 0
    companion object { const val COMPLEX_UNIT_SP = 2; const val COMPLEX_UNIT_DIP = 1
        @JvmStatic fun applyDimension(unit: Int, value: Float, m: Any?): Float = value } }
class AttributeSet
class Size(private val w: Int, private val h: Int) {
    val width: Int get() = w
    val height: Int get() = h
}
object Log {
    @JvmStatic fun i(t: String, m: String): Int = 0
    @JvmStatic fun w(t: String, m: String): Int = 0
    @JvmStatic fun e(t: String, m: String): Int = 0
    @JvmStatic fun e(t: String, m: String, e: Throwable): Int = 0
    @JvmStatic fun d(t: String, m: String): Int = 0
    @JvmStatic fun getStackTraceString(t: Throwable?): String = ""
}

object Base64 {
    const val NO_WRAP = 2
    const val DEFAULT = 0
    fun encodeToString(input: ByteArray, flags: Int): String = ""
    fun decode(s: String, flags: Int): ByteArray = ByteArray(0)
}
