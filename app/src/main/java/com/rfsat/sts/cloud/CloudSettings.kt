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
