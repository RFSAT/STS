package android.graphics

class Bitmap {
    enum class CompressFormat { JPEG, PNG }
    val width: Int = 0
    val height: Int = 0
    val isRecycled: Boolean = false
    fun getPixels(p: IntArray, o: Int, s: Int, x: Int, y: Int, w: Int, h: Int) {}
    fun getPixel(x: Int, y: Int): Int = 0
    fun recycle() {}
    fun compress(f: CompressFormat, q: Int, out: java.io.OutputStream): Boolean = true
    enum class Config { ARGB_8888, RGB_565 }
    companion object {
        @JvmStatic fun createBitmap(px: IntArray, w: Int, h: Int, c: Config): Bitmap = Bitmap()
        @JvmStatic fun createBitmap(w: Int, h: Int, c: Config): Bitmap = Bitmap()
        @JvmStatic fun createBitmap(src: Bitmap, x: Int, y: Int, w: Int, h: Int, m: Matrix?, filter: Boolean): Bitmap = Bitmap()
        @JvmStatic fun createScaledBitmap(src: Bitmap, w: Int, h: Int, filter: Boolean): Bitmap = Bitmap()
    }
}

object BitmapFactory {
    class Options { var inJustDecodeBounds = false; var inSampleSize = 1
        var outWidth = 0; var outHeight = 0; var inPreferredConfig: Bitmap.Config? = null }
    @JvmStatic fun decodeByteArray(b: ByteArray, o: Int, l: Int): Bitmap? = Bitmap()
    @JvmStatic fun decodeStream(s: java.io.InputStream?): Bitmap? = Bitmap()
    @JvmStatic fun decodeStream(s: java.io.InputStream?, r: Rect?, o: Options?): Bitmap? = Bitmap()
    @JvmStatic fun decodeFile(path: String): Bitmap? = Bitmap()
}

class SurfaceTexture(id: Int)

class Matrix { fun postRotate(d: Float) {}; fun postScale(x: Float, y: Float) {} }
class Rect(l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0)
class RectF(var left: Float = 0f, var top: Float = 0f, var right: Float = 0f, var bottom: Float = 0f) {
    fun width(): Float = right - left
    fun height(): Float = bottom - top
}

class Paint(flags: Int = 0) {
    constructor(other: Paint) : this(0)
    var color: Int = 0
    var style: Style = Style.FILL
    var strokeWidth: Float = 0f
    var textSize: Float = 12f
    var textAlign: Align = Align.LEFT
    var isFakeBoldText: Boolean = false
    var pathEffect: Any? = null
    var alpha: Int = 255
    fun measureText(s: String): Float = 0f
    fun getTextBounds(s: String, start: Int, end: Int, r: Rect) {}
    fun setShadowLayer(a: Float, b: Float, c: Float, d: Int) {}
    enum class Style { FILL, STROKE, FILL_AND_STROKE }
    enum class Align { LEFT, CENTER, RIGHT }
    companion object { const val ANTI_ALIAS_FLAG = 1 }
}

class Canvas(bitmap: Bitmap? = null) {
    val width: Int = 0
    val height: Int = 0
    fun drawLine(a: Float, b: Float, c: Float, d: Float, p: Paint) {}
    fun drawCircle(x: Float, y: Float, r: Float, p: Paint) {}
    fun drawRect(r: RectF, p: Paint) {}
    fun drawRect(a: Float, b: Float, c: Float, d: Float, p: Paint) {}
    fun drawOval(r: RectF, p: Paint) {}
    fun drawRoundRect(r: RectF, rx: Float, ry: Float, p: Paint) {}
    fun drawRoundRect(l: Float, t: Float, r: Float, b: Float, rx: Float, ry: Float, p: Paint) {}
    fun drawText(s: String, x: Float, y: Float, p: Paint) {}
    fun drawPath(path: Path, p: Paint) {}
    fun drawBitmap(b: Bitmap, src: Rect?, dst: RectF, p: Paint?) {}
    fun drawColor(c: Int) {}
    fun save(): Int = 0
    fun restore() {}
    fun translate(x: Float, y: Float) {}
    fun rotate(d: Float) {}
    fun scale(x: Float, y: Float) {}
    fun clipRect(r: RectF): Boolean = true
}

class Path {
    fun moveTo(x: Float, y: Float) {}
    fun lineTo(x: Float, y: Float) {}
    fun close() {}
    fun reset() {}
    fun addCircle(x: Float, y: Float, r: Float, d: Any?) {}
    enum class Direction { CW, CCW }
}

class DashPathEffect(intervals: FloatArray, phase: Float)
class Typeface {
    companion object {
        @JvmStatic val DEFAULT_BOLD: Typeface = Typeface()
        @JvmStatic val MONOSPACE: Typeface = Typeface()
        @JvmStatic val DEFAULT: Typeface = Typeface()
    }
}

object Color {
    const val WHITE = -1
    const val BLACK = -16777216
    const val RED = -65536
    const val TRANSPARENT = 0
    @JvmStatic fun argb(a: Int, r: Int, g: Int, b: Int): Int = 0
    @JvmStatic fun rgb(r: Int, g: Int, b: Int): Int = 0
    @JvmStatic fun parseColor(s: String): Int = 0
    @JvmStatic fun red(c: Int): Int = 0
    @JvmStatic fun green(c: Int): Int = 0
    @JvmStatic fun blue(c: Int): Int = 0
    @JvmStatic fun alpha(c: Int): Int = 0
}
