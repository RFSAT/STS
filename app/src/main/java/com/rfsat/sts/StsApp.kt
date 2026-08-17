package com.rfsat.sts

import android.app.Application
import com.rfsat.sts.log.Logger
import com.rfsat.sts.ui.ThemeManager
import com.rfsat.sts.ui.UnitsManager

/**
 * Startup hardening, carried over from VTB where a startup-crash saga
 * proved every one of these points the hard way:
 *
 *  1. The uncaught-exception handler installs FIRST. If it is installed
 *     after Logger/Theme/Units/session init, a crash in any of those is
 *     never recorded anywhere and the app just dies at the splash.
 *  2. The handler writes the stack with commit() (synchronous). The process
 *     is about to die, so apply() and even the file log may never flush.
 *  3. SAFE MODE: if the previous launch crashed, restoring the stored
 *     session is skipped this launch. A corrupt stored payload can then
 *     never kill the app twice, and MainActivity shows the recorded stack
 *     in a shareable dialog — crash diagnosis with no adb.
 */
class StsApp : Application() {

    override fun onCreate() {
        super.onCreate()

        val crashPrefs = getSharedPreferences(CRASH_PREFS, MODE_PRIVATE)
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                crashPrefs.edit()
                    .putString(
                        KEY_STACK,
                        "thread ${thread.name}\n" + android.util.Log.getStackTraceString(throwable)
                    )
                    .putLong(KEY_TIME, System.currentTimeMillis())
                    .commit() // synchronous — the process dies next
            }
            runCatching { Logger.e("CRASH", "Uncaught exception on thread ${thread.name}", throwable) }
            previousHandler?.uncaughtException(thread, throwable)
        }

        val lastCrashed = crashPrefs.contains(KEY_STACK)

        runCatching { Logger.init(this) }
        runCatching { ThemeManager.init(this) }
        runCatching { UnitsManager.init(this) }
        // A copy of every preference file, taken once per version change and
        // BEFORE anything reads or migrates them. An upgrade that mangles a
        // stored profile is otherwise unrecoverable: the old data is gone by
        // the time anyone notices.
        runCatching { com.rfsat.sts.backup.UpgradeSnapshot.maybeTake(this) }
        runCatching { com.rfsat.sts.detect.ScaleSettings.init(this) }
        runCatching { com.rfsat.sts.targets.TargetRepository(this).seedBuiltInsIfEmpty() }
        runCatching { com.rfsat.sts.profiles.ProfileRepository(this).seedDefaultSetsIfEmpty() }

        if (lastCrashed) {
            runCatching {
                Logger.w("StsApp", "Previous launch crashed — skipping stored-session restore (safe mode)")
            }
            // Skipping the restore is the right call: the stored session may
            // be what crashed. Leaving it WRITABLE was not — an unrestored
            // session is empty, and the first save wrote that emptiness over
            // the shooter's card. Safe mode now sets the payload aside and
            // refuses to write until it has successfully read.
            runCatching { com.rfsat.sts.scoring.ScoringSession.enterSafeMode(this) }
        } else {
            runCatching { com.rfsat.sts.scoring.ScoringSession.restore(this) }
        }

        runCatching {
            Logger.i(
                "StsApp",
                "Application started, STS ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})"
            )
        }
    }

    companion object {
        const val CRASH_PREFS = "sts_crash"
        const val KEY_STACK = "stack"
        const val KEY_TIME = "time"
    }
}
