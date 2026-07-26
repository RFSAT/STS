package com.rfsat.sts.targets

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.rfsat.sts.log.Logger

/**
 * Storage for target faces: the built-in catalogue plus the user's own.
 *
 * A built-in is NEVER mutated. Editing one produces a custom copy with a new
 * id, so a dimension corrected today cannot silently change what a session
 * scored last month was measured against — the old face is still there,
 * under its original id, and the stored session still points at it.
 *
 * JSON-in-SharedPreferences rather than Room, matching the rest of the app:
 * a target library is tens of records, not thousands, and a single JSON blob
 * makes the backup/restore path trivially correct.
 *
 * WARNING for anyone refactoring: the persisted form is Gson reflection over
 * FIELD NAMES in [TargetFace], [Ring] and [Zone]. Renaming a field silently
 * changes a stored key — no crash, no build error, just faces that stop
 * loading. proguard-rules.pro keeps the whole package for the same reason.
 */
class TargetRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val gson = Gson()

    /** Custom faces only — the built-ins are code, not storage. */
    fun customFaces(): List<TargetFace> {
        val json = prefs.getString(KEY_CUSTOM, null) ?: return emptyList()
        return runCatching {
            gson.fromJson<List<TargetFace>>(
                json, TypeToken.getParameterized(List::class.java, TargetFace::class.java).type
            )
        }.onFailure { Logger.e("TargetRepository", "Custom target list unreadable; ignoring", it) }
            .getOrNull()
            ?.filter { it.id.isNotBlank() && it.name.isNotBlank() }
            ?: emptyList()
    }

    /** Everything the user can pick from, built-ins first. */
    fun allFaces(): List<TargetFace> = TargetCatalog.builtIns + customFaces()

    fun byId(id: String): TargetFace? = allFaces().firstOrNull { it.id == id }

    /**
     * Saves [face] as a custom entry. If the id collides with a built-in, a
     * fresh id is minted so the built-in survives; if it collides with an
     * existing custom entry, that entry is replaced (an edit, not a copy).
     */
    fun saveCustom(face: TargetFace): TargetFace {
        val collidesWithBuiltIn = TargetCatalog.byId(face.id) != null
        val stored = if (collidesWithBuiltIn || face.id.isBlank()) {
            face.copy(id = mintId(face.name), custom = true, verified = false)
        } else {
            face.copy(custom = true)
        }
        val updated = customFaces().filter { it.id != stored.id } + stored
        prefs.edit().putString(KEY_CUSTOM, gson.toJson(updated)).apply()
        Logger.i("TargetRepository", "Saved custom target '${stored.name}' (${stored.id})")
        return stored
    }

    fun deleteCustom(id: String) {
        if (TargetCatalog.byId(id) != null) return // built-ins are not deletable
        prefs.edit().putString(KEY_CUSTOM, gson.toJson(customFaces().filter { it.id != id })).apply()
        if (activeFaceId() == id) clearActiveFace()
    }

    private fun mintId(name: String): String {
        val base = "custom_" + name.lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .take(28)
            .ifBlank { "target" }
        val taken = allFaces().map { it.id }.toSet()
        if (base !in taken) return base
        var n = 2
        while ("${base}_$n" in taken) n++
        return "${base}_$n"
    }

    // ---- active selection ----

    fun activeFaceId(): String? = prefs.getString(KEY_ACTIVE, null)?.ifBlank { null }

    fun activeFace(): TargetFace =
        activeFaceId()?.let { byId(it) } ?: TargetCatalog.ISSF_AR10

    fun setActiveFace(id: String) = prefs.edit().putString(KEY_ACTIVE, id).apply()

    fun clearActiveFace() = prefs.edit().remove(KEY_ACTIVE).apply()

    /**
     * Nothing to seed: the built-in catalogue is code and is always present.
     * This exists so StsApp has one uniform "prepare storage" call per
     * repository, and so a future migration has an obvious place to live.
     */
    fun seedBuiltInsIfEmpty() {
        if (prefs.getBoolean(KEY_SEEDED, false)) return
        prefs.edit()
            .putBoolean(KEY_SEEDED, true)
            .putString(KEY_ACTIVE, TargetCatalog.ISSF_AR10.id)
            .apply()
        Logger.i("TargetRepository", "First run: active target defaulted to ${TargetCatalog.ISSF_AR10.name}")
    }

    companion object {
        private const val PREFS = "sts_targets"
        private const val KEY_CUSTOM = "custom_faces"
        private const val KEY_ACTIVE = "active_face"
        private const val KEY_SEEDED = "seeded"
    }
}
