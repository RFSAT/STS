package androidx.core.content

object ContextCompat {
    @JvmStatic fun getColor(c: android.content.Context, res: Int): Int = 0
    @JvmStatic fun getMainExecutor(c: android.content.Context): java.util.concurrent.Executor =
        java.util.concurrent.Executor { it.run() }
    @JvmStatic fun checkSelfPermission(c: android.content.Context, p: String): Int = 0
    @JvmStatic fun getDrawable(c: android.content.Context, res: Int): Any? = null
}

class FileProvider {
    companion object {
        @JvmStatic fun getUriForFile(c: android.content.Context, a: String, f: java.io.File): android.net.Uri =
            android.net.Uri()
    }
}
