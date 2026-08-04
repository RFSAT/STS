package android.content

open class Context {
    val applicationContext: Context = this
    val contentResolver: ContentResolver = ContentResolver()
    val packageName: String = "com.STS"
    val theme: Resources.Theme = Resources.Theme()
    val resources: Resources = Resources()
    val filesDir: java.io.File = java.io.File(".")
    val cacheDir: java.io.File = java.io.File(".")
    fun getSharedPreferences(name: String, mode: Int): SharedPreferences = SharedPreferences()
    fun deleteSharedPreferences(name: String): Boolean = true
    fun getString(res: Int): String = ""
    fun getString(res: Int, vararg args: Any?): String = ""
    fun startActivity(i: Intent) {}
    fun <T> getSystemService(c: Class<T>): T? = null
    companion object { const val MODE_PRIVATE = 0 }
}

class Resources {
    class Theme { fun resolveAttribute(id: Int, tv: android.util.TypedValue, resolve: Boolean): Boolean = true }
    val displayMetrics: DisplayMetrics = DisplayMetrics()
    class DisplayMetrics { val density: Float = 1f; val widthPixels: Int = 0; val heightPixels: Int = 0 }
    fun getColor(id: Int, theme: Theme?): Int = 0
}

class ContentResolver {
    fun openInputStream(uri: android.net.Uri): java.io.InputStream? = null
    fun takePersistableUriPermission(uri: android.net.Uri, flags: Int) {}
    fun query(uri: android.net.Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Any? = null
}

class SharedPreferences {
    fun getString(k: String, d: String?): String? = d
    fun getBoolean(k: String, d: Boolean): Boolean = d
    fun getInt(k: String, d: Int): Int = d
    fun getFloat(k: String, d: Float): Float = d
    fun getLong(k: String, d: Long): Long = d
    fun edit(): Editor = Editor()
    fun contains(k: String): Boolean = false
    val all: Map<String, Any?> = emptyMap()
    class Editor {
        fun putString(k: String, v: String?): Editor = this
        fun putBoolean(k: String, v: Boolean): Editor = this
        fun putInt(k: String, v: Int): Editor = this
        fun putFloat(k: String, v: Float): Editor = this
        fun putLong(k: String, v: Long): Editor = this
        fun remove(k: String): Editor = this
        fun clear(): Editor = this
        fun apply() {}
        fun commit(): Boolean = true
    }
}

class Intent {
    constructor()
    constructor(action: String)
    constructor(c: Context, cls: Class<*>)
    var type: String? = null
    var data: android.net.Uri? = null
    fun putExtra(k: String, v: String): Intent = this
    fun putExtra(k: String, v: Boolean): Intent = this
    fun putExtra(k: String, v: Int): Intent = this
    fun putExtra(k: String, v: android.net.Uri): Intent = this
    fun addFlags(f: Int): Intent = this
    fun setType(t: String): Intent = this
    fun getStringExtra(k: String): String? = null
    fun getBooleanExtra(k: String, d: Boolean): Boolean = d
    companion object {
        const val ACTION_SEND = "android.intent.action.SEND"
        const val EXTRA_TEXT = "android.intent.extra.TEXT"
        const val EXTRA_STREAM = "android.intent.extra.STREAM"
        const val EXTRA_SUBJECT = "android.intent.extra.SUBJECT"
        const val FLAG_ACTIVITY_CLEAR_TOP = 0x04000000
        const val FLAG_GRANT_READ_URI_PERMISSION = 1
        @JvmStatic fun createChooser(i: Intent, t: CharSequence): Intent = Intent()
    }
}

class ClipData
class ClipboardManager
