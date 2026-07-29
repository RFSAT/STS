package android.graphics
class Bitmap {
    val width: Int = 0
    val height: Int = 0
    val isRecycled: Boolean = false
    fun getPixels(p: IntArray, o: Int, s: Int, x: Int, y: Int, w: Int, h: Int) {}
    enum class Config { ARGB_8888 }
    companion object {
        @JvmStatic fun createBitmap(px: IntArray, w: Int, h: Int, c: Config): Bitmap = Bitmap()
        @JvmStatic fun createBitmap(w: Int, h: Int, c: Config): Bitmap = Bitmap()
    }
}

// Measures one unit per character, which is all the wrapping logic needs:
// "does it fit" is then a question of length, and the tests can state widths
// in characters.
class Paint {
    fun measureText(s: String): Float = s.length.toFloat()
}
