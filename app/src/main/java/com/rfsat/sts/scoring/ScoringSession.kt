package com.rfsat.sts.scoring

import android.content.Context
import com.google.gson.Gson
import com.rfsat.sts.log.Logger
import com.rfsat.sts.profiles.ProfileRepository
import com.rfsat.sts.rules.RuleRepository
import com.rfsat.sts.rules.RuleSet
import com.rfsat.sts.targets.TargetFace
import com.rfsat.sts.targets.TargetRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The shooting session in progress: which target, which rules, at what
 * distance, and every shot recorded so far.
 *
 * A SINGLETON, deliberately. Session state is shared by the Session screen
 * that produces it and the Results screen that reads it, and the two are
 * separate activities that finish each other on every tab hop (see
 * BaseActivity.openTab). Passing the shot list through Intent extras would
 * mean serialising it on every navigation and would lose it entirely if the
 * process were killed between screens. A singleton with an explicit persist
 * step is smaller, and the persist step is what makes it survive.
 *
 * PERSISTENCE IS SYNCHRONOUS AND EAGER. Every mutation writes through. A
 * session is minutes of a person's range time that cannot be re-shot, so the
 * cost of a few hundred bytes of JSON per shot is not worth arguing about
 * against the cost of losing a 60-shot match to a background kill.
 */
object ScoringSession {

    private const val PREFS = "sts_session"
    private const val KEY_STATE = "state"

    /** Everything that has to survive a process death, in one Gson-able lump. */
    data class State(
        var faceId: String = "",
        var rulesId: String = "",
        var distanceM: Double = 10.0,
        var shots: MutableList<Shot> = mutableListOf(),
        var startedAtMs: Long = 0L,
        var endedAtMs: Long = 0L,
        /** Stage time in seconds, for the practical disciplines. Entered by
         *  hand: a phone watching the target cannot see the start signal. */
        var stageSeconds: Double = 0.0,
        /** Point of aim in target-plane mm, when it is not the scoring centre. */
        var poaXMm: Double = 0.0,
        var poaYMm: Double = 0.0,
        var notes: String = "",
        /** Registration corners from the last successful registration, so a
         *  session resumed after a restart does not have to be re-registered
         *  as long as the phone has not moved. Flattened to a plain list
         *  because Gson handles Pair poorly across versions. */
        var registrationCorners: MutableList<Double> = mutableListOf()
    )

    var state: State = State()
        private set

    private var appContext: Context? = null

    // ------------------------------------------------------------------

    fun restore(context: Context) {
        appContext = context.applicationContext
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_STATE, null)
        if (json == null) {
            state = State()
            return
        }
        state = runCatching { Gson().fromJson(json, State::class.java) }
            .onFailure { Logger.e("ScoringSession", "Stored session unreadable; starting fresh", it) }
            .getOrNull() ?: State()
        // Gson bypasses Kotlin's constructor defaults, so a payload written by
        // an older build can arrive with nulls in fields the type says cannot
        // be null. Repairing them here is the difference between a working
        // restore and a NullPointerException at the first read.
        @Suppress("SENSELESS_COMPARISON")
        if (state.shots == null) state.shots = mutableListOf()
        @Suppress("SENSELESS_COMPARISON")
        if (state.registrationCorners == null) state.registrationCorners = mutableListOf()
        Logger.i("ScoringSession", "Restored session with ${state.shots.size} shot(s)")
    }

    private fun persist() {
        val ctx = appContext ?: return
        runCatching {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_STATE, Gson().toJson(state)).apply()
        }.onFailure { Logger.e("ScoringSession", "Could not persist the session", it) }
    }

    /** Called once from any screen before the session is used. */
    fun attach(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    // ------------------------------------------------------------------
    //  Lifecycle
    // ------------------------------------------------------------------

    fun startNew(face: TargetFace, rules: RuleSet, distanceM: Double) {
        state = State(
            faceId = face.id,
            rulesId = rules.id,
            distanceM = distanceM,
            startedAtMs = System.currentTimeMillis()
        )
        persist()
        Logger.i("ScoringSession", "New session: ${face.name} / ${rules.name} at ${distanceM} m")
    }

    fun finish() {
        state.endedAtMs = System.currentTimeMillis()
        persist()
    }

    fun clear() {
        state = State()
        persist()
    }

    val isActive: Boolean get() = state.startedAtMs > 0L && state.endedAtMs == 0L
    val hasShots: Boolean get() = state.shots.isNotEmpty()

    // ------------------------------------------------------------------
    //  Shots
    // ------------------------------------------------------------------

    /** Appends a shot, assigning its index and series from the rule set. */
    fun addShot(shot: Shot, rules: RuleSet): Shot {
        val index = state.shots.count { !it.sighter } + 1
        val placed = shot.copy(
            index = if (shot.sighter) 0 else index,
            series = if (shot.sighter) 0 else ScoringEngine.seriesFor(index, rules)
        )
        state.shots.add(placed)
        persist()
        return placed
    }

    fun removeShot(shot: Shot) {
        state.shots.remove(shot)
        reindex()
        persist()
    }

    fun replaceShot(old: Shot, new: Shot) {
        val i = state.shots.indexOf(old)
        if (i >= 0) {
            state.shots[i] = new
            persist()
        }
    }

    fun undoLast() {
        if (state.shots.isNotEmpty()) {
            state.shots.removeAt(state.shots.size - 1)
            reindex()
            persist()
        }
    }

    /** Renumbers after a deletion so the indices stay 1..n with no gaps —
     *  a gap would put every later shot in the wrong series. */
    private fun reindex() {
        var n = 0
        for (i in state.shots.indices) {
            val s = state.shots[i]
            if (s.sighter) continue
            n++
            state.shots[i] = s.copy(index = n)
        }
    }

    fun setStageSeconds(seconds: Double) {
        state.stageSeconds = seconds
        persist()
    }

    fun setPointOfAim(xMm: Double, yMm: Double) {
        state.poaXMm = xMm
        state.poaYMm = yMm
        persist()
    }

    fun setNotes(text: String) {
        state.notes = text
        persist()
    }

    fun setDistance(m: Double) {
        state.distanceM = m
        persist()
    }

    fun saveRegistrationCorners(corners: List<Pair<Double, Double>>) {
        state.registrationCorners = corners.flatMap { listOf(it.first, it.second) }.toMutableList()
        persist()
    }

    fun registrationCorners(): List<Pair<Double, Double>>? {
        val flat = state.registrationCorners
        if (flat.size != 8) return null
        return (0 until 4).map { flat[it * 2] to flat[it * 2 + 1] }
    }

    // ------------------------------------------------------------------
    //  Derived views
    // ------------------------------------------------------------------

    fun face(context: Context): TargetFace =
        TargetRepository(context).byId(state.faceId) ?: TargetRepository(context).activeFace()

    fun rules(context: Context): RuleSet =
        RuleRepository(context).byId(state.rulesId) ?: RuleRepository(context).activeSet()

    fun result(context: Context): ScoringResult =
        ScoringEngine.aggregate(state.shots, face(context), rules(context), state.stageSeconds)

    fun group(): GroupStatistics =
        GroupStatistics.of(state.shots.filter { !it.sighter })

    fun correction(context: Context): SightCorrection {
        val scope = ProfileRepository(context).getScope()
        val rifle = ProfileRepository(context).getRifle()
        return CorrectionCalculator.compute(
            group = group(),
            scope = scope,
            distanceM = state.distanceM,
            zeroDistanceM = rifle.zeroDistanceM,
            poaXMm = state.poaXMm,
            poaYMm = state.poaYMm
        )
    }

    // ------------------------------------------------------------------
    //  Export
    // ------------------------------------------------------------------

    /**
     * The session as a plain-text report, for sharing to a coach or a club
     * secretary. Plain text rather than a formatted document on purpose: it
     * pastes into an email, a message and a spreadsheet cell without anything
     * being installed at the other end.
     */
    fun asReport(context: Context): String {
        val face = face(context)
        val rules = rules(context)
        val res = result(context)
        val grp = group()
        val corr = correction(context)
        val profiles = ProfileRepository(context)
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

        return buildString {
            appendLine("STS Shooting Target Scorer — session report")
            appendLine("=".repeat(52))
            appendLine("Date        : ${stamp.format(Date(state.startedAtMs.takeIf { it > 0 } ?: System.currentTimeMillis()))}")
            appendLine("Rules       : ${rules.name} (${rules.governingBody})")
            appendLine("Target      : ${face.name}")
            appendLine("Distance    : ${"%.0f".format(state.distanceM)} m")
            appendLine("Firearm     : ${profiles.getRifle().label()}")
            appendLine("Load        : ${profiles.getBullet().name}")
            appendLine("Sight       : ${profiles.getScope().label()}")
            if (!face.verified || !rules.verified) {
                appendLine()
                appendLine("NOTE: this session used target or rule figures that are the commonly")
                appendLine("published ones rather than a governing body's published table. Check")
                appendLine("them against the rulebook in force before quoting this score.")
            }
            appendLine()
            appendLine("RESULT")
            appendLine("-".repeat(52))
            appendLine("Total       : ${res.displayTotal}" + if (res.maxScore > 0) " / ${"%.0f".format(res.maxScore)}" else "")
            if (rules.countInnerTens) appendLine("Inner tens  : ${res.innerTens}")
            if (res.misses > 0) appendLine("Misses      : ${res.misses}")
            if (res.derivedFigureLabel.isNotEmpty())
                appendLine("${res.derivedFigureLabel.padEnd(12)}: ${"%.4f".format(res.derivedFigure)}")
            appendLine()
            if (res.series.size > 1) {
                appendLine("SERIES")
                appendLine("-".repeat(52))
                res.series.forEach { s ->
                    appendLine("  ${s.number.toString().padStart(2)}  ${s.displayTotal.padStart(6)}   " +
                        s.shots.joinToString(" ") { it.displayValue })
                }
                appendLine()
            }
            appendLine("GROUP")
            appendLine("-".repeat(52))
            appendLine("Shots           : ${grp.shotCount}")
            appendLine("Centre offset   : ${"%.1f".format(grp.mpiXMm)} mm right, ${"%.1f".format(grp.mpiYMm)} mm up")
            appendLine("Extreme spread  : ${"%.1f".format(grp.extremeSpreadMm)} mm " +
                "(${"%.2f".format(grp.extremeSpreadMoa(state.distanceM))} MOA)")
            appendLine("Mean radius     : ${"%.1f".format(grp.meanRadiusMm)} mm")
            appendLine("R50             : ${"%.1f".format(grp.r50Mm)} mm")
            appendLine("Horizontal SD   : ${"%.1f".format(grp.horizontalSdMm)} mm")
            appendLine("Vertical SD     : ${"%.1f".format(grp.verticalSdMm)} mm")
            appendLine()
            appendLine("CORRECTION")
            appendLine("-".repeat(52))
            appendLine(corr.instruction)
            corr.warnings.forEach { appendLine("  ! $it") }
            if (res.warnings.isNotEmpty()) {
                appendLine()
                appendLine("NOTES")
                appendLine("-".repeat(52))
                res.warnings.forEach { appendLine("  ! $it") }
            }
            if (state.notes.isNotBlank()) {
                appendLine()
                appendLine("SHOOTER'S NOTES")
                appendLine("-".repeat(52))
                appendLine(state.notes)
            }
            appendLine()
            appendLine("SHOTS (x, y in mm from the scoring centre; + is right and up)")
            appendLine("-".repeat(52))
            appendLine("  #  value      x        y      r     conf  source")
            state.shots.forEach { s ->
                appendLine(
                    "%3s  %-6s %7.2f %8.2f %6.2f  %4.2f  %s".format(
                        if (s.sighter) "S" else s.index.toString(),
                        s.displayValue, s.xMm, s.yMm, s.radiusMm, s.confidence,
                        if (s.manual) "manual" else "detected"
                    )
                )
            }
        }
    }

    /** Comma-separated shots, for a spreadsheet. */
    fun asCsv(): String = buildString {
        appendLine("index,value,x_mm,y_mm,radius_mm,inner_ten,zone,series,sighter,confidence,manual,timestamp_ms")
        state.shots.forEach { s ->
            appendLine(
                listOf(
                    s.index, s.displayValue, "%.3f".format(s.xMm), "%.3f".format(s.yMm),
                    "%.3f".format(s.radiusMm), s.innerTen, s.zoneName, s.series,
                    s.sighter, "%.3f".format(s.confidence), s.manual, s.timestampMs
                ).joinToString(",")
            )
        }
    }
}
