package android.net

class Uri {
    override fun toString(): String = ""
    val lastPathSegment: String? = null
    companion object {
        @JvmStatic fun parse(s: String): Uri = Uri()
        @JvmStatic fun fromFile(f: java.io.File): Uri = Uri()
    }
}
