package androidx.appcompat.app

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View

class Window {
    val decorView: View = View()
    var attributes: android.view.WindowManager.LayoutParams = android.view.WindowManager.LayoutParams()
    fun addFlags(f: Int) {}
    fun setFlags(a: Int, b: Int) {}
}

open class AppCompatActivity : android.app.Activity() {
    val layoutInflater: LayoutInflater = LayoutInflater()
    val window: Window = Window()
    val intent: android.content.Intent = android.content.Intent()
    open fun onCreate(savedInstanceState: Bundle?) {}
    open fun onResume() {}
    open fun onPause() {}
    open fun onDestroy() {}
    open fun onStart() {}
    open fun onStop() {}
    fun setContentView(v: View) {}
    fun setContentView(res: Int) {}
    fun <T : View> findViewById(id: Int): T? = null
    fun runOnUiThread(r: Runnable) {}
    fun finish() {}
    fun onBackPressed() {}
    fun recreate() {}
    fun finishAffinity() {}
    override fun setTheme(res: Int) {}
    val decorView: View get() = View()
    open fun onWindowFocusChanged(hasFocus: Boolean) {}
    open fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {}
    open fun dispatchTouchEvent(e: android.view.MotionEvent): Boolean = false
    val packageManager: Any? = null
    @Suppress("DEPRECATION") fun overridePendingTransition(a: Int, b: Int) {}
    fun requestPermissions(p: Array<String>, code: Int) {}
    fun checkSelfPermission(p: String): Int = 0
    fun shouldShowRequestPermissionRationale(p: String): Boolean = false
    fun <I, O> registerForActivityResult(
        contract: androidx.activity.result.contract.ActivityResultContract<I, O>,
        callback: (O) -> Unit
    ): androidx.activity.result.ActivityResultLauncher<I> =
        androidx.activity.result.ActivityResultLauncher()
}

class AlertDialog {
    fun show() {}
    fun dismiss() {}
    class Builder(c: Context) {
        fun setTitle(t: CharSequence): Builder = this
        fun setMessage(m: CharSequence): Builder = this
        fun setView(v: View): Builder = this
        fun setPositiveButton(t: CharSequence, l: ((Any?, Int) -> Unit)?): Builder = this
        fun setNegativeButton(t: CharSequence, l: ((Any?, Int) -> Unit)?): Builder = this
        fun setNeutralButton(t: CharSequence, l: ((Any?, Int) -> Unit)?): Builder = this
        fun setItems(items: Array<CharSequence>, l: ((Any?, Int) -> Unit)?): Builder = this
        fun setCancelable(b: Boolean): Builder = this
        fun create(): AlertDialog = AlertDialog()
        fun show(): AlertDialog = AlertDialog()
    }
}
