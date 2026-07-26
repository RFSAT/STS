package com.rfsat.sts.rules

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.rfsat.sts.log.Logger

/**
 * Storage for rule sets: the built-in catalogue plus the user's own.
 *
 * Same contract as [com.rfsat.sts.targets.TargetRepository]: a built-in is
 * never mutated, editing one produces a custom copy under a new id, and a
 * session records the id it was scored under. So amending a shot count today
 * cannot retroactively change what last month's session claimed to be.
 */
class RuleRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val gson = Gson()

    fun customSets(): List<RuleSet> {
        val json = prefs.getString(KEY_CUSTOM, null) ?: return emptyList()
        return runCatching {
            gson.fromJson<List<RuleSet>>(
                json, TypeToken.getParameterized(List::class.java, RuleSet::class.java).type
            )
        }.onFailure { Logger.e("RuleRepository", "Custom rule list unreadable; ignoring", it) }
            .getOrNull()
            ?.filter { it.id.isNotBlank() && it.name.isNotBlank() }
            ?: emptyList()
    }

    fun allSets(): List<RuleSet> = RuleCatalog.builtIns + customSets()

    fun byId(id: String): RuleSet? = allSets().firstOrNull { it.id == id }

    fun saveCustom(set: RuleSet): RuleSet {
        val collides = RuleCatalog.byId(set.id) != null
        val stored = if (collides || set.id.isBlank()) {
            set.copy(id = mintId(set.name), custom = true, verified = false)
        } else set.copy(custom = true)
        val updated = customSets().filter { it.id != stored.id } + stored
        prefs.edit().putString(KEY_CUSTOM, gson.toJson(updated)).apply()
        Logger.i("RuleRepository", "Saved custom rule set '${stored.name}' (${stored.id})")
        return stored
    }

    fun deleteCustom(id: String) {
        if (RuleCatalog.byId(id) != null) return
        prefs.edit().putString(KEY_CUSTOM, gson.toJson(customSets().filter { it.id != id })).apply()
        if (activeSetId() == id) prefs.edit().remove(KEY_ACTIVE).apply()
    }

    private fun mintId(name: String): String {
        val base = "custom_" + name.lowercase()
            .replace(Regex("[^a-z0-9]+"), "_").trim('_').take(28).ifBlank { "rules" }
        val taken = allSets().map { it.id }.toSet()
        if (base !in taken) return base
        var n = 2
        while ("${base}_$n" in taken) n++
        return "${base}_$n"
    }

    fun activeSetId(): String? = prefs.getString(KEY_ACTIVE, null)?.ifBlank { null }

    fun activeSet(): RuleSet = activeSetId()?.let { byId(it) } ?: RuleCatalog.TRAINING_FREE

    fun setActiveSet(id: String) = prefs.edit().putString(KEY_ACTIVE, id).apply()

    companion object {
        private const val PREFS = "sts_rules"
        private const val KEY_CUSTOM = "custom_rules"
        private const val KEY_ACTIVE = "active_rules"
    }
}
