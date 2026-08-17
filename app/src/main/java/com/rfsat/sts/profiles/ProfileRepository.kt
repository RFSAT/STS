package com.rfsat.sts.profiles

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.rfsat.sts.log.Logger

/** A named firearm + load + sight combination. Same shape as VTB/DBM. */
data class ProfileSet(
    val name: String,
    val rifle: RifleProfile,
    val bullet: BulletProfile,
    val scope: ScopeProfile
)

/**
 * JSON-in-SharedPreferences store for the active rifle/bullet/scope profile
 * and for the user's library of named sets. Identical storage shape to
 * VTB/DBM so a backup moves between the apps.
 *
 * WARNING for anyone refactoring: every persisted format here is Gson
 * reflection over FIELD NAMES. Renaming a field silently changes a stored
 * key — no crash, no build error, just profiles that stop loading.
 */
class ProfileRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val gson = Gson()

    // ---- active profiles ----

    fun getRifle(): RifleProfile {
        val parsed = prefs.getString(KEY_RIFLE, null)
            ?.let { runCatching { gson.fromJson(it, RifleProfile::class.java) }.getOrNull() }
            ?: return RifleProfile.DEFAULT
        // MIGRATION from pre-v9 VTB/DBM exports: zero distance moved from
        // yards to metres. Gson bypasses Kotlin constructor defaults, so an
        // absent zeroDistanceM arrives as 0.0 — which means "migrate from the
        // legacy yards value, or fall back to the default".
        @Suppress("DEPRECATION")
        if (parsed.zeroDistanceM <= 0.0) {
            val migrated = parsed.copy(
                zeroDistanceM = if (parsed.zeroDistanceYards > 0.0) parsed.zeroDistanceYards * 0.9144
                else RifleProfile.DEFAULT.zeroDistanceM
            )
            saveRifle(migrated)
            return migrated
        }
        // An imported VTB/DBM profile has no firearmTypeName. Gson leaves it
        // null despite the non-null Kotlin type, so this check is NOT
        // redundant — it is exactly the case the compiler cannot see.
        @Suppress("SENSELESS_COMPARISON")
        if (parsed.firearmTypeName == null || parsed.firearmTypeName.isBlank()) {
            val guessed = parsed.copy(firearmTypeName = FirearmType.CENTREFIRE_RIFLE.name)
            saveRifle(guessed)
            return guessed
        }
        return parsed
    }

    fun saveRifle(profile: RifleProfile) {
        prefs.edit().putString(KEY_RIFLE, gson.toJson(profile)).apply()
        clearActiveSetName() // the active profiles no longer match a snapshot
    }

    fun getBullet(): BulletProfile =
        prefs.getString(KEY_BULLET, null)
            ?.let { runCatching { gson.fromJson(it, BulletProfile::class.java) }.getOrNull() }
            ?: BulletProfile.DEFAULT

    fun saveBullet(profile: BulletProfile) {
        prefs.edit().putString(KEY_BULLET, gson.toJson(profile)).apply()
        clearActiveSetName()
    }

    fun getScope(): ScopeProfile {
        val json = prefs.getString(KEY_SCOPE, null) ?: return ScopeProfile.DEFAULT
        val parsed = runCatching { gson.fromJson(json, ScopeProfile::class.java) }.getOrNull()
            ?: return ScopeProfile.DEFAULT
        // Detect a stale or damaged profile — the same guard VTB needed when
        // new optical fields arrived and Gson left them at 0.0, silently
        // overriding the new default.
        @Suppress("SENSELESS_COMPARISON")
        val stale = parsed.name == null || parsed.name.isBlank() ||
            (parsed.clickUnit as ClickUnit?) == null
        if (stale) {
            saveScopeInternal(ScopeProfile.DEFAULT)
            return ScopeProfile.DEFAULT
        }
        // An imported VTB/DBM scope has no sightTypeName. Every VTB scope was
        // a telescopic sight, so that is the correct backfill.
        @Suppress("SENSELESS_COMPARISON")
        if (parsed.sightTypeName == null || parsed.sightTypeName.isBlank()) {
            val fixed = parsed.copy(sightTypeName = SightType.SCOPE.name)
            saveScopeInternal(fixed)
            return fixed
        }
        // MM_AT_REFERENCE with a zero reference distance would divide by zero
        // in clickMrad. Repair it to a harmless 10 m rather than propagating
        // an infinity into the correction advice.
        if (parsed.clickUnit == ClickUnit.MM_AT_REFERENCE && parsed.clickReferenceDistanceM <= 0.0) {
            val fixed = parsed.copy(clickReferenceDistanceM = 10.0)
            saveScopeInternal(fixed)
            Logger.w("ProfileRepository", "Scope '${parsed.name}' had a zero click reference distance; set to 10 m")
            return fixed
        }
        return parsed
    }

    fun saveScope(profile: ScopeProfile) {
        saveScopeInternal(profile)
        clearActiveSetName()
    }

    private fun saveScopeInternal(profile: ScopeProfile) {
        prefs.edit().putString(KEY_SCOPE, gson.toJson(profile)).apply()
    }

    fun resetToDefaults() {
        // Only the ACTIVE profiles reset. Saved sets are a library the user
        // built up; a full clear() would wipe them.
        prefs.edit().remove(KEY_RIFLE).remove(KEY_BULLET).remove(KEY_SCOPE).remove(KEY_ACTIVE_SET).apply()
    }

    // ---- named profile sets ----

    fun getSets(): List<ProfileSet> {
        val json = prefs.getString(KEY_SETS, null) ?: return emptyList()
        return runCatching {
            gson.fromJson<List<ProfileSet>>(
                json, TypeToken.getParameterized(List::class.java, ProfileSet::class.java).type
            )
        }.getOrNull()
            ?.filter { it.name.isNotBlank() }
            ?.map { s ->
                // Guard against pre-v9 zero semantics arriving via a
                // hand-edited or cross-app JSON.
                if (s.rifle.zeroDistanceM <= 0.0)
                    s.copy(rifle = s.rifle.copy(zeroDistanceM = RifleProfile.DEFAULT.zeroDistanceM))
                else s
            }
            ?: emptyList()
    }

    fun saveSet(set: ProfileSet) {
        val updated = getSets().filter { it.name != set.name } + set
        prefs.edit().putString(KEY_SETS, gson.toJson(updated)).apply()
    }

    fun deleteSet(name: String) {
        prefs.edit().putString(KEY_SETS, gson.toJson(getSets().filter { it.name != name })).apply()
        if (getActiveSetName() == name) clearActiveSetName()
    }

    /** Makes [set] the active rifle/bullet/scope and records it as active. */
    fun applySet(set: ProfileSet) {
        prefs.edit()
            .putString(KEY_RIFLE, gson.toJson(set.rifle))
            .putString(KEY_BULLET, gson.toJson(set.bullet))
            .putString(KEY_SCOPE, gson.toJson(set.scope))
            .putString(KEY_ACTIVE_SET, set.name)
            .apply()
        Logger.i("ProfileRepository", "Applied profile set '${set.name}'")
    }

    fun getActiveSetName(): String? = prefs.getString(KEY_ACTIVE_SET, null)?.ifBlank { null }
    fun setActiveSetName(name: String) = prefs.edit().putString(KEY_ACTIVE_SET, name).apply()
    fun clearActiveSetName() = prefs.edit().remove(KEY_ACTIVE_SET).apply()

    /**
     * Seeds ready-made sets on first run (once; deleting them later does not
     * resurrect them). Built from the catalogues, so the data matches what a
     * manual catalogue pick would produce, and covering the four equipment
     * families the app is for: 10 m air, 50 m smallbore, 25 m pistol and
     * centrefire precision.
     */
    fun seedDefaultSetsIfEmpty() {
        if (prefs.getBoolean(KEY_SETS_SEEDED, false) || getSets().isNotEmpty()) return

        // Searched over the WHOLE catalogue — the ported VTB block plus the
        // STS additions — so a seed can name either.
        fun rifle(brand: String, model: String) =
            RifleCatalog.all.firstOrNull { it.brand == brand && it.model == model }?.toRifleProfile()

        fun ammo(mfr: String, product: String) =
            AmmoCatalog.all.firstOrNull { it.manufacturer == mfr && it.product == product }?.toBulletProfile()

        fun sight(brand: String, model: String) =
            ScopeCatalog.all.firstOrNull { it.brand == brand && it.model == model }?.toScopeProfile()

        val seeds = listOfNotNull(
            build("10 m Air Rifle — Anschütz",
                rifle("Anschütz", "9015 Air Rifle"),
                ammo("JSB", "Exact Diabolo 4.50"),
                sight("Anschütz", "6834 diopter")),
            build("10 m Air Pistol — Steyr",
                rifle("Steyr", "LP50 Air Pistol"),
                ammo("RWS", "R10 Match Pistol"),
                sight("Morini", "CM162 rear sight")),
            build("50 m Smallbore — Anschütz 1913",
                rifle("Anschütz", "1913 Super Match .22LR"),
                ammo("Lapua", "Center-X"),
                sight("Anschütz", "7002/20 diopter (50 m)")),
            build("25 m Pistol — Pardini SP",
                rifle("Pardini", "SP Rapid Fire Pistol .22LR"),
                ammo("Eley", "Pistol Match"),
                sight("Pardini", "SP rear sight (25 m)")),
            build(".308 F-TR — Savage 12",
                rifle("Savage", "12 F/TR .308 Win"),
                ammo("Federal", "Gold Medal 175 SMK"),
                sight("Vector Optics", "Continental 5-30x56")),
            build(".223 Service Rifle — AR-15",
                rifle("Generic", "AR-15 20in 1:7 .223 Rem"),
                ammo("Federal", "Gold Medal 77 SMK"),
                sight("Vector Optics", "Continental 3-18x50"))
        )
        if (seeds.isEmpty()) return

        prefs.edit()
            .putString(KEY_SETS, gson.toJson(seeds))
            .putBoolean(KEY_SETS_SEEDED, true)
            .apply()

        // The first set is the DEFAULT — applied on a fresh install so the
        // app starts on a complete, self-consistent rig rather than on a
        // half-filled form.
        seeds.first().let { applySet(it) }
        Logger.i("ProfileRepository", "Seeded ${seeds.size} default profile sets")
    }

    private fun build(name: String, r: RifleProfile?, b: BulletProfile?, s: ScopeProfile?): ProfileSet? =
        if (r == null || b == null || s == null) {
            Logger.w("ProfileRepository", "Seed '$name' skipped: a catalogue entry it names is missing")
            null
        } else ProfileSet(name, r, b, s)

    companion object {
        private const val PREFS = "sts_profiles"
        private const val KEY_RIFLE = "rifle_profile"
        private const val KEY_BULLET = "bullet_profile"
        private const val KEY_SCOPE = "scope_profile"
        private const val KEY_SETS = "profile_sets"
        private const val KEY_ACTIVE_SET = "active_set_name"
        private const val KEY_SETS_SEEDED = "sets_seeded"
    }
}
