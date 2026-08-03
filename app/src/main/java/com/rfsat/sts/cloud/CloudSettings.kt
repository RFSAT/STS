package com.rfsat.sts.cloud

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.rfsat.sts.log.Logger

/**
 * Where the shooter's Claude API key lives, and what it is allowed to do.
 *
 * THE KEY IS NOT A LOGIN. It is issued from the Anthropic Console, bills the
 * account it belongs to, and is not the same thing as a Claude.ai password —
 * consumer credentials do not work against the API at all. That distinction
 * is stated in the UI, because getting it wrong is the first thing anyone
 * will do.
 *
 * Stored through EncryptedSharedPreferences rather than the ordinary kind: a
 * credential that can spend the user's money should not sit in a plain XML
 * file that a device backup or a rooted process can read. If the keystore is
 * unavailable the key is NOT quietly written in the clear — storage fails,
 * the caller is told, and the feature stays off.
 */
object CloudSettings {

    private const val FILE = "sts_cloud"
    private const val KEY_API = "api_key"
    private const val KEY_MODEL = "model"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_OVERRIDE = "override_app"
    private const val KEY_FULL = "full_delegation"

    /** Offered in the picker. Vision-capable models only. */
    val MODELS = listOf(
        "claude-haiku-4-5-20251001" to "Haiku 4.5 — cheapest, fastest",
        "claude-sonnet-5" to "Sonnet 5 — balanced (recommended)",
        "claude-opus-5" to "Opus 5 — most capable, dearest"
    )
    const val DEFAULT_MODEL = "claude-sonnet-5"

    private var prefs: SharedPreferences? = null

    private fun store(context: Context): SharedPreferences? {
        prefs?.let { return it }
        return runCatching {
            val master = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context, FILE, master,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            ).also { prefs = it }
        }.onFailure {
            Logger.e("CloudSettings", "encrypted storage unavailable, so no key will be kept: ${it.message}")
        }.getOrNull()
    }

    fun apiKey(context: Context): String = store(context)?.getString(KEY_API, "").orEmpty()

    /** False when the key could not be stored SAFELY, which the caller must
     *  report rather than pretend succeeded. */
    fun setApiKey(context: Context, value: String): Boolean {
        val p = store(context) ?: return false
        p.edit().putString(KEY_API, value.trim()).apply()
        return true
    }

    fun model(context: Context): String =
        store(context)?.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL

    fun setModel(context: Context, value: String) {
        store(context)?.edit()?.putString(KEY_MODEL, value)?.apply()
    }

    fun enabled(context: Context): Boolean =
        store(context)?.getBoolean(KEY_ENABLED, false) ?: false

    fun setEnabled(context: Context, value: Boolean) {
        store(context)?.edit()?.putBoolean(KEY_ENABLED, value)?.apply()
    }

    /**
     * Let Claude's answer win outright — both WHETHER there is a shot and
     * WHERE it is.
     *
     * OFF by default, and the cost is worth stating plainly rather than
     * burying: a vision model places a hole to a few per cent of the image,
     * several millimetres on a 170 mm card, where the app measures one it can
     * see to between 0.2 and 1.7 mm. On a 10 m air pistol face the rings are
     * 8 mm apart, so a position taken from the model can be a ring out.
     *
     * What it buys is the other half: the app's measured failure is
     * over-detection — printing read as shots — and counting is the one thing
     * the model does better. With this on, that is fixed without asking.
     *
     * Shots placed this way are recorded as HAND-PLACED, because that is what
     * they are: their position was not measured. A report can then never show
     * them as though it had been.
     */
    fun overrideApp(context: Context): Boolean =
        store(context)?.getBoolean(KEY_OVERRIDE, false) ?: false

    fun setOverrideApp(context: Context, value: Boolean) {
        store(context)?.edit()?.putBoolean(KEY_OVERRIDE, value)?.apply()
    }

    /**
     * Let Claude find and score the shots outright, with the app's own hole
     * detection not run at all.
     *
     * WHAT IS STILL THE APP'S. Registration — where the card is, how big it
     * is, where the rings fall. That is not an omission: without it there is
     * no millimetre grid, and without a grid nothing can be drawn in the
     * right place on the photograph or compared with a catalogue face. The
     * picture sent is the RECTIFIED card, already on that grid, so a fraction
     * of the image maps back to millimetres in one linear step and the marks
     * land exactly where the shooter sees them.
     *
     * WHAT IT COSTS is the whole of the app's measurement accuracy: 0.2 to
     * 1.7 mm becomes several. Every shot is recorded as hand-placed.
     *
     * The app still scores the position it is given from the ring geometry,
     * and says so when that disagrees with the ring Claude reported — a
     * disagreement means one of the two is wrong and the shooter should know
     * which shots they are.
     */
    fun fullDelegation(context: Context): Boolean =
        store(context)?.getBoolean(KEY_FULL, false) ?: false

    fun setFullDelegation(context: Context, value: Boolean) {
        store(context)?.edit()?.putBoolean(KEY_FULL, value)?.apply()
    }

    fun configured(context: Context): Boolean = enabled(context) && apiKey(context).isNotBlank()

    /** For display. The key itself is never shown or logged. */
    fun maskedKey(context: Context): String {
        val k = apiKey(context)
        return when {
            k.isBlank() -> "not set"
            k.length < 12 -> "set"
            else -> k.take(7) + "…" + k.takeLast(4)
        }
    }
}
