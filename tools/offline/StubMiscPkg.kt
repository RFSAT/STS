package android.os

class Bundle {
    fun putString(k: String, v: String?) {}
    fun getString(k: String): String? = null
}
class Handler(looper: Looper?) {
    constructor() : this(null)
    fun post(r: Runnable): Boolean = true
    fun postDelayed(r: Runnable, ms: Long): Boolean = true
    fun removeCallbacksAndMessages(t: Any?) {}
    fun removeCallbacks(r: Runnable) {}
}
class Looper { companion object { @JvmStatic fun getMainLooper(): Looper = Looper() } }
class Environment
