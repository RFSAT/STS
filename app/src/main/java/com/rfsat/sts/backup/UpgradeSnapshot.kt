package com.rfsat.sts.backup

import android.content.Context
import com.rfsat.sts.BuildConfig
import com.rfsat.sts.log.Logger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A copy of everything, taken automatically the first time a new build runs.
 *
 * Nothing here would have saved the session lost in 1.31.0 — by the time that
 * was noticed it had already been overwritten. That is exactly the argument
 * for it. The defects that destroy a shooter's data are the ones nobody
 * predicted, so the protection cannot depend on predicting them; it has to be
 * a copy taken before the new code has had a chance to do anything at all.
 *
 * TAKEN SYNCHRONOUSLY, on the main thread, during startup. That is a
 * deliberate choice against the usual advice. It runs once per version
 * change, reads only SharedPreferences and costs a few milliseconds — and
 * moving it to a background thread would race the very writes it exists to
 * come before. A snapshot that might be taken after the damage is not a
 * snapshot.
 */
object UpgradeSnapshot {

    private const val PREFS = "bas_upgrade"
    private const val KEY_LAST_CODE = "last_version_code"
    private const val DIR = "upgrade-snapshots"

    /** How many to keep. Enough to step back past a bad release without
     *  turning the app's storage into an archive. */
    private const val KEEP = 5

    private fun dir(c: Context) = File(c.filesDir, DIR).apply { mkdirs() }

    /**
     * Takes a snapshot if the running build differs from the one that ran
     * last. Returns the file written, or null when there was nothing to do.
     */
    fun maybeTake(context: Context): File? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = p.getInt(KEY_LAST_CODE, 0)
        val now = BuildConfig.VERSION_CODE
        if (last == now) return null

        // Record the new version FIRST. If the export throws, this must not
        // retry on every launch for ever — one failed snapshot is a missing
        // safety net, but an export that crashes the app on every start is
        // the app being unusable.
        p.edit().putInt(KEY_LAST_CODE, now).apply()

        // Nothing preceded this build, so there is nothing to protect.
        if (last == 0) {
            Logger.i("UpgradeSnapshot", "First run of build $now; nothing to snapshot")
            return null
        }

        return runCatching {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val file = File(dir(context), "STS-build$last-$stamp.json")
            file.writeText(AppBackup.export(context))
            prune(context)
            Logger.i("UpgradeSnapshot",
                "Upgrading build $last -> $now; snapshot written to ${file.name} " +
                "(${file.length()} bytes)")
            file
        }.onFailure {
            Logger.e("UpgradeSnapshot", "Could not take the pre-upgrade snapshot", it)
        }.getOrNull()
    }

    /** Newest first. */
    fun list(context: Context): List<File> =
        dir(context).listFiles()?.filter { it.isFile && it.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

    private fun prune(context: Context) {
        list(context).drop(KEEP).forEach { runCatching { it.delete() } }
    }

    /** Puts one back, returning whatever AppBackup reports. */
    fun restore(context: Context, file: File): String =
        AppBackup.import(context, file.readText())

    /** "before build 55, 14 Aug 16:20 (12 kB)" — enough to choose by. */
    fun describe(file: File): String {
        val build = Regex("build(\\d+)").find(file.name)?.groupValues?.get(1) ?: "?"
        val when_ = SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(file.lastModified()))
        return "before build $build, $when_ (${(file.length() + 512) / 1024} kB)"
    }
}
