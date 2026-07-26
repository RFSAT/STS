package com.rfsat.sts.backup

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.rfsat.sts.BuildConfig
import com.rfsat.sts.log.Logger
import com.rfsat.sts.profiles.ProfileRepository
import com.rfsat.sts.profiles.ProfileSet
import com.rfsat.sts.rules.RuleRepository
import com.rfsat.sts.rules.RuleSet
import com.rfsat.sts.targets.TargetFace
import com.rfsat.sts.targets.TargetRepository

/**
 * Export and restore everything the user built up: profile sets, custom
 * target faces and custom rule sets.
 *
 * WHAT IS DELIBERATELY NOT IN THE BACKUP. Recorded sessions. A backup is for
 * moving a setup to a new phone, and restoring it must never overwrite what
 * is on the target in front of you — a restore that silently replaced an
 * in-progress 60-shot match would be a catastrophe with no undo. Sessions are
 * exported individually as a report or CSV from the Results screen, which is
 * also the form anyone actually wants to receive them in.
 *
 * FORMAT. Plain JSON, pretty-printed, with a version field. Pretty-printed
 * because a shooter emailing a backup to themselves should be able to open it
 * and see that it contains what they think it does — and because a format
 * nobody can read is a format nobody can repair.
 */
object AppBackup {

    private const val FORMAT_VERSION = 1

    data class Payload(
        val formatVersion: Int = FORMAT_VERSION,
        val appVersion: String = "",
        val exportedAtMs: Long = 0L,
        val profileSets: List<ProfileSet> = emptyList(),
        val customTargets: List<TargetFace> = emptyList(),
        val customRules: List<RuleSet> = emptyList()
    )

    fun export(context: Context): String {
        val payload = Payload(
            formatVersion = FORMAT_VERSION,
            appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            exportedAtMs = System.currentTimeMillis(),
            profileSets = ProfileRepository(context).getSets(),
            customTargets = TargetRepository(context).customFaces(),
            customRules = RuleRepository(context).customSets()
        )
        Logger.i(
            "AppBackup",
            "Exported ${payload.profileSets.size} set(s), ${payload.customTargets.size} target(s), " +
                "${payload.customRules.size} rule set(s)"
        )
        return GsonBuilder().setPrettyPrinting().create().toJson(payload)
    }

    /**
     * Restores a backup. Returns a human-readable summary — the caller shows
     * it verbatim, so a partial restore is reported as a partial restore
     * rather than as success.
     */
    fun import(context: Context, json: String): String {
        if (json.isBlank()) return "Nothing was pasted."

        val payload = runCatching { Gson().fromJson(json, Payload::class.java) }
            .onFailure { Logger.e("AppBackup", "Backup would not parse", it) }
            .getOrNull()
            ?: return "That is not a readable STS backup — the JSON could not be parsed."

        if (payload.formatVersion > FORMAT_VERSION) {
            return "This backup was written by a newer version of STS (format ${payload.formatVersion}, " +
                "this build understands $FORMAT_VERSION). Update the app and try again."
        }

        var sets = 0
        var targets = 0
        var rules = 0
        val problems = mutableListOf<String>()

        val profiles = ProfileRepository(context)
        payload.profileSets.forEach { s ->
            runCatching { profiles.saveSet(s); sets++ }
                .onFailure { problems += "profile set '${s.name}'" }
        }

        val targetRepo = TargetRepository(context)
        payload.customTargets.forEach { t ->
            runCatching { targetRepo.saveCustom(t); targets++ }
                .onFailure { problems += "target '${t.name}'" }
        }

        val ruleRepo = RuleRepository(context)
        payload.customRules.forEach { r ->
            runCatching { ruleRepo.saveCustom(r); rules++ }
                .onFailure { problems += "rule set '${r.name}'" }
        }

        Logger.i("AppBackup", "Restored $sets set(s), $targets target(s), $rules rule set(s)")

        return buildString {
            append("Restored $sets profile set(s), $targets custom target(s) and $rules custom rule set(s).")
            if (problems.isNotEmpty()) {
                append(" These could not be restored: ${problems.joinToString(", ")}.")
            }
        }
    }

    /** Type token kept alive for the list deserialisations R8 cannot see. */
    @Suppress("unused")
    private val keepTokens = listOf(
        TypeToken.getParameterized(List::class.java, ProfileSet::class.java).type,
        TypeToken.getParameterized(List::class.java, TargetFace::class.java).type,
        TypeToken.getParameterized(List::class.java, RuleSet::class.java).type
    )
}
