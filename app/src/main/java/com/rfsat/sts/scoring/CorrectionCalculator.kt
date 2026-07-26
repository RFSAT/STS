package com.rfsat.sts.scoring

import com.rfsat.sts.profiles.ScopeProfile
import com.rfsat.sts.profiles.SightType
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * ============================================================================
 *  SIGHT CORRECTION
 * ============================================================================
 *
 * What to turn, and how far, to put the next group where the last one was
 * aimed.
 *
 * THE GEOMETRY, WHICH IS SIMPLER THAN IT LOOKS. A group whose centre is
 * [offsetMm] away from the point of aim, at distance D metres, is off by
 *
 *      theta = offset_mm / (D * 1000)  radians  =  offset_mm / D  milliradians
 *
 * because a milliradian subtends one millimetre per metre — that identity is
 * the whole reason mil-based sights are pleasant to use. Convert to the
 * sight's own click through [ScopeProfile.clickMrad], and the click count is
 * a division. The correction is the NEGATIVE of the offset: a group high and
 * right needs the sight moved down and left.
 *
 * WHAT THIS DELIBERATELY DOES NOT DO. It does not re-solve the trajectory.
 * The correction returned centres the group AT THE DISTANCE IT WAS SHOT, and
 * that is the honest answer to the question the shooter asked. If that
 * distance is not the zero distance, the shooter has just changed their zero
 * — [zeroWarning] says so explicitly rather than leaving it to be discovered
 * at the next match. A ballistic solver that silently folded in the drop
 * difference would produce a different number, correct for a question nobody
 * asked, and indistinguishable from this one on the screen.
 *
 * DIRECTION CONVENTIONS. Turret markings are not universal, and a diopter's
 * elevation drum frequently runs the opposite way to a scope's. So the class
 * reports the direction the POINT OF IMPACT must move (unambiguous, physical)
 * AND the turret instruction derived from it, with the per-profile inversion
 * flags applied. When the sight has no clicks the turret instruction is
 * replaced by a physical rear-sight movement, or by an explanation of why one
 * cannot be given.
 */
data class SightCorrection(
    /** How far the point of impact must move, mm on the target, +right/+up. */
    val moveImpactXMm: Double,
    val moveImpactYMm: Double,
    /** The same, as an angle in the sight's natural terms. */
    val windageMrad: Double,
    val elevationMrad: Double,
    val windageMoa: Double,
    val elevationMoa: Double,
    /** Click counts, already signed away and expressed with a direction word.
     *  0 when the sight has no clicks. */
    val windageClicks: Int,
    val elevationClicks: Int,
    val windageDirection: String,   // "LEFT" / "RIGHT" / ""
    val elevationDirection: String, // "UP" / "DOWN" / ""
    /** Physical rear-sight movement, mm, when the sight radius is known. */
    val rearSightMoveXMm: Double = 0.0,
    val rearSightMoveYMm: Double = 0.0,
    val hasRearSightAdvice: Boolean = false,
    /** One-line instruction ready to display. */
    val instruction: String,
    val warnings: List<String> = emptyList(),
    /** False when the inputs could not support a correction at all. */
    val valid: Boolean = true
)

object CorrectionCalculator {

    private const val MOA_PER_MRAD = ScopeProfile.MOA_PER_MRAD

    /**
     * Below this many shots the group centre is not known well enough to
     * chase. Three shots put the standard error of the mean at over half the
     * group's own radius: a correction dialled off three shots is more likely
     * than not to be moving the group in a direction the next three would
     * contradict.
     */
    private const val MIN_SHOTS_FOR_CONFIDENCE = 5

    /**
     * If the group centre is closer to the point of aim than this multiple of
     * its own uncertainty, there is no measurable error to correct. Chasing
     * it is the classic way to turn a good zero into a bad one.
     */
    private const val SIGNIFICANCE_FACTOR = 1.0

    /**
     * @param group        statistics over the shots to correct from
     * @param scope        the sight in the active profile set
     * @param distanceM    distance the group was shot at
     * @param zeroDistanceM the sight's current zero, for the warning only
     * @param poaXMm,poaYMm the point of aim in target-plane mm. Normally the
     *        scoring centre (0,0), but not always: a shooter holding off for
     *        wind, or aiming at a separate aiming mark on a group card, has a
     *        point of aim that is not the point the rings are drawn around.
     */
    fun compute(
        group: GroupStatistics,
        scope: ScopeProfile,
        distanceM: Double,
        zeroDistanceM: Double = 0.0,
        poaXMm: Double = 0.0,
        poaYMm: Double = 0.0
    ): SightCorrection {
        val warnings = mutableListOf<String>()

        if (group.shotCount == 0) {
            return invalid("No shots to correct from.")
        }
        if (distanceM <= 0.0) {
            return invalid("The distance to the target is not set, so an angular correction cannot be computed.")
        }

        // Offset of the group centre from where it should be, and the
        // correction, which is its negative.
        val offX = group.mpiXMm - poaXMm
        val offY = group.mpiYMm - poaYMm
        val moveX = -offX
        val moveY = -offY

        val windageMrad = moveX / distanceM
        val elevationMrad = moveY / distanceM

        // ---- is the correction statistically worth making? ----
        if (group.shotCount < MIN_SHOTS_FOR_CONFIDENCE) {
            warnings += "Only ${group.shotCount} shot(s). The group centre is known to about " +
                "${fmtMm(group.mpiUncertaintyMm)}, which is a large fraction of the correction below. " +
                "Fire at least $MIN_SHOTS_FOR_CONFIDENCE before dialling."
        }
        val offsetMag = kotlin.math.hypot(offX, offY)
        if (group.mpiUncertaintyMm > 0 && offsetMag < SIGNIFICANCE_FACTOR * group.mpiUncertaintyMm) {
            warnings += "The group centre is ${fmtMm(offsetMag)} from the point of aim, which is inside " +
                "its own uncertainty of ${fmtMm(group.mpiUncertaintyMm)}. There is no measurable zero " +
                "error here — leave the sight alone."
        }

        // ---- zero distance ----
        if (zeroDistanceM > 0.0 && abs(zeroDistanceM - distanceM) > 0.5) {
            warnings += "This correction centres the group at ${fmtM(distanceM)}, but the profile records " +
                "the sight as zeroed at ${fmtM(zeroDistanceM)}. Applying it re-zeros the rifle for " +
                "${fmtM(distanceM)} — note the click count so you can put it back."
        }

        // ---- turret travel ----
        val neededElevMoa = abs(elevationMrad) * MOA_PER_MRAD
        val neededWindMoa = abs(windageMrad) * MOA_PER_MRAD
        if (scope.maxElevationTravelMoa > 0 && neededElevMoa > scope.maxElevationTravelMoa / 2.0) {
            warnings += "The elevation correction is ${"%.1f".format(neededElevMoa)} MOA, more than half " +
                "the sight's total travel. Check the mount and the base before assuming the turret can " +
                "take it."
        }
        if (scope.maxWindageTravelMoa > 0 && neededWindMoa > scope.maxWindageTravelMoa / 2.0) {
            warnings += "The windage correction is ${"%.1f".format(neededWindMoa)} MOA, more than half the " +
                "sight's total travel."
        }

        // ---- clicks ----
        val clickMrad = scope.clickMrad
        var windClicks = 0
        var elevClicks = 0
        var windDir = ""
        var elevDir = ""

        if (scope.hasClicks && clickMrad > 0.0) {
            windClicks = (abs(windageMrad) / clickMrad).roundToInt()
            elevClicks = (abs(elevationMrad) / clickMrad).roundToInt()

            // Direction the TURRET must be turned. The physical requirement is
            // "move the impact this way"; the inversion flags let a profile
            // describe a sight whose engraving disagrees with the usual
            // convention, so the printed instruction matches the drum the
            // shooter is actually looking at.
            val windRight = windageMrad > 0
            val elevUp = elevationMrad > 0
            windDir = if (windRight != scope.invertWindageDirection) "RIGHT" else "LEFT"
            elevDir = if (elevUp != scope.invertElevationDirection) "UP" else "DOWN"

            if (windClicks == 0 && elevClicks == 0) {
                warnings += "The correction is smaller than one click. The sight is as close as it can be set."
            }
        }

        // ---- iron sights with no clicks ----
        var rearX = 0.0
        var rearY = 0.0
        var hasRear = false
        if (!scope.hasClicks) {
            if (scope.sightRadiusMm > 0.0) {
                // Similar triangles: moving the rear sight by r over a sight
                // radius R swings the line of sight by r/R radians, which puts
                // the impact r/R * D metres downrange. So the rear sight must
                // move by (offset / D) * R, in the SAME direction as the
                // desired impact movement.
                rearX = moveX / (distanceM * 1000.0) * scope.sightRadiusMm
                rearY = moveY / (distanceM * 1000.0) * scope.sightRadiusMm
                hasRear = true
            } else {
                warnings += "This sight has no click value and no sight radius recorded, so the app cannot " +
                    "say how far to move it. Enter the sight radius in Settings and the movement will be " +
                    "given in millimetres."
            }
        }

        val instruction = buildInstruction(
            scope, windClicks, elevClicks, windDir, elevDir,
            moveX, moveY, rearX, rearY, hasRear
        )

        return SightCorrection(
            moveImpactXMm = moveX, moveImpactYMm = moveY,
            windageMrad = windageMrad, elevationMrad = elevationMrad,
            windageMoa = windageMrad * MOA_PER_MRAD,
            elevationMoa = elevationMrad * MOA_PER_MRAD,
            windageClicks = windClicks, elevationClicks = elevClicks,
            windageDirection = windDir, elevationDirection = elevDir,
            rearSightMoveXMm = rearX, rearSightMoveYMm = rearY,
            hasRearSightAdvice = hasRear,
            instruction = instruction,
            warnings = warnings,
            valid = true
        )
    }

    private fun buildInstruction(
        scope: ScopeProfile,
        windClicks: Int, elevClicks: Int,
        windDir: String, elevDir: String,
        moveX: Double, moveY: Double,
        rearX: Double, rearY: Double, hasRear: Boolean
    ): String {
        if (scope.hasClicks) {
            if (windClicks == 0 && elevClicks == 0) return "No adjustment — the sight is already centred."
            val parts = mutableListOf<String>()
            if (elevClicks != 0) parts += "$elevClicks click${plural(elevClicks)} $elevDir"
            if (windClicks != 0) parts += "$windClicks click${plural(windClicks)} $windDir"
            return parts.joinToString(", ") + " (${scope.clickDescription()})"
        }
        if (hasRear) {
            val parts = mutableListOf<String>()
            if (abs(rearY) >= 0.01) parts += "rear sight ${fmtMm(abs(rearY))} ${if (rearY > 0) "UP" else "DOWN"}"
            if (abs(rearX) >= 0.01) parts += "rear sight ${fmtMm(abs(rearX))} ${if (rearX > 0) "RIGHT" else "LEFT"}"
            if (parts.isEmpty()) return "No adjustment needed."
            return parts.joinToString(", ") +
                (if (scope.sightType == SightType.OPEN_SIGHTS)
                    " — or move the FRONT sight the same amount the other way." else "")
        }
        return "Move the point of impact ${fmtMm(abs(moveY))} ${if (moveY > 0) "up" else "down"} and " +
            "${fmtMm(abs(moveX))} ${if (moveX > 0) "right" else "left"}."
    }

    private fun plural(n: Int) = if (abs(n) == 1) "" else "s"
    private fun fmtMm(v: Double) = "%.1f mm".format(v)
    private fun fmtM(v: Double) = "%.0f m".format(v)

    private fun invalid(reason: String) = SightCorrection(
        moveImpactXMm = 0.0, moveImpactYMm = 0.0,
        windageMrad = 0.0, elevationMrad = 0.0, windageMoa = 0.0, elevationMoa = 0.0,
        windageClicks = 0, elevationClicks = 0,
        windageDirection = "", elevationDirection = "",
        instruction = reason, warnings = listOf(reason), valid = false
    )
}
