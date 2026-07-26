package com.rfsat.sts.profiles

/**
 * The projectile half of a profile set. Field names and units are identical
 * to VTB/DBM (diameter and length in inches, weight in grains, muzzle
 * velocity in fps, G1 ballistic coefficient) so profile sets move between
 * the apps unchanged — see the note on [RifleProfile].
 *
 * WHY STS CARES ABOUT BALLISTICS AT ALL. Scoring itself does not need a
 * drag model: a hole is where it is. But the correction the app suggests
 * does. Two of the three quantities STS reports back to the shooter depend
 * on the load:
 *
 *   - the SCORING GAUGE radius, which decides marginal ring calls, is set by
 *     the projectile diameter (via the rule set's gauge, not the bullet
 *     itself — see RuleSet.gaugeDiameterMm);
 *   - the sight correction at a distance OTHER than the zero distance needs
 *     the drop difference between the two, which needs the load.
 *
 * At 10 m with a pellet, the second term is negligible and the correction is
 * pure geometry. At 600 yards with a .308 it is the whole problem.
 */
data class BulletProfile(
    val name: String = "JSB Exact 4.50 mm 8.44gr",
    val caliberDiameterIn: Double = 0.177,
    val weightGrains: Double = 8.44,
    val muzzleVelocityFps: Double = 570.0,
    val ballisticCoefficientG1: Double = 0.021,
    /** Local calibration multiplier on drag; 1.0 = trust the BC as given.
     *  Tune against your own chronograph and drop data. */
    val dragCalibrationFactor: Double = 1.0,
    /** VTB compatibility: tracer round. Irrelevant to target scoring, kept
     *  so a round-trip through STS does not strip the flag. */
    val isTracer: Boolean = false,
    /** VTB compatibility: airgun projectile. Despite the legacy name this
     *  means "track the projectile in flight"; STS reads it only to decide
     *  the default gauge when a rule set does not specify one. The stored
     *  JSON key stays "isPellet" because renaming it would break every
     *  saved profile, CSV and backup. */
    val isPellet: Boolean = true,
    /** Powder is temperature sensitive: MV shifts roughly 0.5-1 m/s per degC
     *  for rimfire, more for some centrefire loads. 0.0 = correction off. */
    val mvTempCoeffMpsPerC: Double = 0.0,
    val mvRefTempC: Double = 15.0
) {
    /**
     * Power factor for practical shooting: bullet weight (gr) x muzzle
     * velocity (fps) / 1000. IPSC Major starts at 320 for handgun, Minor at
     * 125, and the declared division decides which column of
     * [com.rfsat.sts.targets.Zone] values a hit is scored from.
     *
     * Derived, never stored — so it cannot drift out of step with the weight
     * and velocity it is computed from, and so it is absent from the JSON
     * (where a stale copy would be actively misleading).
     */
    val powerFactor: Double get() = weightGrains * muzzleVelocityFps / 1000.0

    val massKg: Double get() = weightGrains * 0.00006479891
    val diameterM: Double get() = caliberDiameterIn * 0.0254
    val diameterMm: Double get() = caliberDiameterIn * 25.4
    val crossSectionalAreaM2: Double get() = Math.PI * (diameterM / 2.0).let { it * it }
    val muzzleVelocityMps: Double get() = muzzleVelocityFps * 0.3048

    /** Copy with MV corrected to the given ambient temperature. Returns this
     *  unchanged when the coefficient is unset, so every downstream consumer
     *  stays consistent with zero special-casing. */
    fun adjustedForTemperature(ambientC: Double): BulletProfile {
        if (mvTempCoeffMpsPerC == 0.0) return this
        val newMps = muzzleVelocityMps + mvTempCoeffMpsPerC * (ambientC - mvRefTempC)
        return copy(muzzleVelocityFps = (newMps / 0.3048).coerceAtLeast(1.0))
    }

    /** Intent-revealing alias for the legacy isPellet key. */
    val trackProjectile: Boolean get() = isPellet || isTracer

    companion object {
        val DEFAULT = BulletProfile()
    }
}
