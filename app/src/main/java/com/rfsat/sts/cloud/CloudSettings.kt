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
/** What actually scores a card when a photograph is imported.
 *
 *  NOT named ScoringEngine: com.rfsat.sts.scoring.ScoringEngine already is
 *  the object that turns a hole into a score, and two types a letter apart
 *  in the same file is a bug waiting to be written. */
enum class ScoringSource(val label: String) {
    EMBEDDED("Embedded — the app's own algorithms"),
    CLOUD("Cloud AI — Claude finds and scores")
}

object CloudSettings {

    private const val FILE = "sts_cloud"
    private const val KEY_API = "api_key"
    private const val KEY_MODEL = "model"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_OVERRIDE = "override_app"
    private const val KEY_ENGINE = "engine"
    private const val KEY_PROVIDER = "provider"

    /** Offered in the picker. Vision-capable models only.
     *
     *  Deliberately short lists rather than everything either service has
     *  published: a model that cannot see an image, or cannot be held to a
     *  schema, fails in a way the shooter cannot diagnose. "Other" lets a
     *  newer identifier be typed in, so a list going stale strands nobody. */
    val MODELS: Map<AiProvider, List<Pair<String, String>>> = mapOf(
        AiProvider.ANTHROPIC to listOf(
            "claude-haiku-4-5-20251001" to "Haiku 4.5 — cheapest, fastest",
            "claude-sonnet-5" to "Sonnet 5 — balanced (recommended)",
            "claude-opus-5" to "Opus 5 — most capable, dearest"
        ),
        AiProvider.OPENAI to listOf(
            "gpt-4o-mini" to "GPT-4o mini — cheapest, fastest",
            "gpt-4o" to "GPT-4o — balanced (recommended)"
        )
    )

    val DEFAULT_MODEL: Map<AiProvider, String> = mapOf(
        AiProvider.ANTHROPIC to "claude-sonnet-5",
        AiProvider.OPENAI to "gpt-4o"
    )

    private var prefs: SharedPreferences? = null

    /**
     * True when the stored keys were found unreadable and thrown away. The
     * caller shows this once so the shooter knows to enter a key again rather
     * than assuming the feature is broken.
     */
    var keysWereReset: Boolean = false
        private set

    private fun store(context: Context): SharedPreferences? {
        prefs?.let { return it }
        open(context)?.let { prefs = it; return it }

        // ---- IT COULD NOT BE OPENED, so it is thrown away and remade ----
        //
        // The file is encrypted with a master key in the Android Keystore, and
        // the Keystore does not survive a restore onto another phone, a
        // factory reset of credentials, or certain OS upgrades. When it goes,
        // the file becomes ciphertext nobody can read — and every later
        // attempt fails the same way, so the AI features would be dead for
        // good with no explanation.
        //
        // Deleting it costs the user re-entering a key, which takes ten
        // seconds. Not deleting it costs them the feature permanently. The
        // file is excluded from backup precisely so this should not happen,
        // but "should not" is not a recovery plan.
        Logger.w("CloudSettings", "the stored keys could not be decrypted; discarding them")
        runCatching {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().clear().commit()
            context.deleteSharedPreferences(FILE)
        }
        val fresh = open(context)
        if (fresh != null) { prefs = fresh; keysWereReset = true }
        return fresh
    }

    private fun open(context: Context): SharedPreferences? = runCatching {
        val master = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context, FILE, master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }.onFailure {
        Logger.e("CloudSettings", "encrypted storage unavailable: ${it.message}")
    }.getOrNull()

    fun clearResetFlag() { keysWereReset = false }

    fun provider(context: Context): AiProvider =
        store(context)?.getString(KEY_PROVIDER, null)
            ?.let { n -> AiProvider.values().firstOrNull { it.name == n } }
            ?: AiProvider.ANTHROPIC

    fun setProvider(context: Context, value: AiProvider) {
        store(context)?.edit()?.putString(KEY_PROVIDER, value.name)?.apply()
    }

    /** The key for whichever provider is chosen. Each is kept separately, so
     *  switching back and forth does not mean pasting a key in again. */
    fun apiKey(context: Context): String = apiKey(context, provider(context))

    fun apiKey(context: Context, p: AiProvider): String =
        sanitise(store(context)?.getString(KEY_API + "_" + p.name, "").orEmpty())

    /** False when the key could not be stored SAFELY, which the caller must
     *  report rather than pretend succeeded. */
    fun setApiKey(context: Context, value: String): Boolean =
        setApiKey(context, provider(context), value)

    fun setApiKey(context: Context, p: AiProvider, value: String): Boolean {
        val st = store(context) ?: return false
        st.edit().putString(KEY_API + "_" + p.name, sanitise(value)).apply()
        return true
    }

    /**
     * Strips EVERY whitespace character, not just the ends.
     *
     * A key pasted from a wrapped display carries a line break in the MIDDLE
     * of it, and trim() leaves that where it is. It then goes into an HTTP
     * header, where a newline is illegal, and the request dies before it is
     * sent — "unexpected char 0x0a at 83 in header value", the 83rd character
     * of "Bearer sk-proj-...", which is a newline 76 characters into the key.
     * No API key of either service contains whitespace, so removing all of it
     * can only help.
     *
     * Applied on the way OUT as well as in, so a key stored by an earlier
     * version is repaired rather than failing for ever.
     */
    private fun sanitise(v: String): String = v.filterNot { it.isWhitespace() }

    fun model(context: Context): String {
        val p = provider(context)
        val fallback = DEFAULT_MODEL[p].orEmpty()
        return store(context)?.getString(KEY_MODEL + "_" + p.name, fallback) ?: fallback
    }

    fun setModel(context: Context, value: String) {
        store(context)?.edit()
            ?.putString(KEY_MODEL + "_" + provider(context).name, value)?.apply()
    }

    fun models(context: Context): List<Pair<String, String>> =
        MODELS[provider(context)].orEmpty()

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
     * Which engine runs when a photograph is imported.
     *
     * This replaced three overlapping checkboxes — "enable the button",
     * "override the app" and "find and score outright" — that had accumulated
     * one request at a time and between them described states nobody wanted,
     * such as overriding an engine that was not running. There is one choice
     * now, and [overrideApp] is what it means under EMBEDDED only.
     *
     *   EMBEDDED runs the app's own detection. Claude is available on the
     *   Results screen as a second opinion, advisory unless [overrideApp].
     *
     *   CLOUD does not run the app's hole finding at all. Registration is
     *   still the app's, because without knowing where the card is and how
     *   big it is there is no millimetre grid and nothing can be drawn in the
     *   right place. The picture sent is the RECTIFIED card, already on that
     *   grid, so the marks land exactly where the shooter sees them.
     *
     * Falls back to EMBEDDED when no key is set, rather than importing a
     * photograph and scoring nothing.
     */
    fun engine(context: Context): ScoringSource {
        val want = store(context)?.getString(KEY_ENGINE, null)
            ?.let { name -> ScoringSource.values().firstOrNull { it.name == name } }
            ?: ScoringSource.EMBEDDED
        return if (want == ScoringSource.CLOUD && apiKey(context).isBlank())
            ScoringSource.EMBEDDED else want
    }

    fun setEngine(context: Context, value: ScoringSource) {
        store(context)?.edit()?.putString(KEY_ENGINE, value.name)?.apply()
    }

    fun configured(context: Context): Boolean = enabled(context) && apiKey(context).isNotBlank()

    /** For display. The key itself is never shown or logged. */
    fun maskedKey(context: Context): String = maskedKey(context, provider(context))

    fun maskedKey(context: Context, p: AiProvider): String {
        val k = apiKey(context, p)
        return when {
            k.isBlank() -> "not set"
            k.length < 12 -> "set"
            else -> k.take(7) + "…" + k.takeLast(4)
        }
    }
}
