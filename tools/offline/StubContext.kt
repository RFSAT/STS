package android.content

/** Enough of Context for the settings objects to compile offline. The
 *  preference store is in-memory: the harness only needs the mode to be
 *  settable, and nothing here should touch a real device file. */
class Context {
    fun getSharedPreferences(name: String, mode: Int): SharedPreferences = SharedPreferences()
    companion object { const val MODE_PRIVATE = 0 }
}

class SharedPreferences {
    private val map = HashMap<String, String?>()
    fun getString(k: String, d: String?): String? = map[k] ?: d
    fun edit(): Editor = Editor(map)
    class Editor(private val map: HashMap<String, String?>) {
        fun putString(k: String, v: String?): Editor { map[k] = v; return this }
        fun apply() {}
    }
}
