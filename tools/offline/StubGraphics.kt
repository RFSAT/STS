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

/**
 * Returns 0.0f, exactly as the real android.jar does under plain unit tests
 * with unitTests.isReturnDefaultValues = true.
 *
 * Deliberately useless, for the same reason the JUnit shim carries no
 * convenience overloads: a stub more capable than the environment it stands
 * in for hides the failures it exists to catch. An earlier version of this
 * one measured text properly, so a test of the name wrapping passed here and
 * failed in CI — the logic under test never ran, because measureText said
 * every string fitted.
 *
 * Anything that needs real measurement does not belong in a unit test.
 */
class Paint {
    fun measureText(s: String): Float = 0f
}
