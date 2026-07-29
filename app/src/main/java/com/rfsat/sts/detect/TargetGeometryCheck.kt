package com.rfsat.sts.detect

import com.rfsat.sts.log.Logger
import com.rfsat.sts.targets.TargetFace
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * Does the target in the picture actually look like the face that is selected?
 *
 * WHY THIS EXISTS. Auto-detection finds the black aiming mark and then
 * expands the registration box to the outer ring using the SELECTED face's
 * published black-to-outer ratio. That ratio differs enormously between
 * faces — 2.61 on the ISSF air pistol target, 1.49 on the air rifle one — so
 * choosing the wrong face does not fail, it silently registers the wrong
 * circle. Photograph an air-pistol card, select air rifle, and the box lands
 * on the FIVE ring: every distance then comes out half its true size, the
 * centre shot reads as a ten, and everything past the misplaced outer ring is
 * not even looked at and comes back as a miss. That is a complete, confident,
 * entirely wrong score sheet, produced by a wrong menu selection, with
 * nothing on screen to suggest anything was amiss.
 *
 * The picture itself contains the evidence. If the box has been drawn around
 * the outer ring, there should be no more printed rings outside it. When
 * there are, the face is wrong — and the ratio the image actually shows says
 * which face would have been right.
 */
data class GeometryCheck(
    /** Outermost printed ring found in the picture, source pixels. 0 if none. */
    val observedOuterRadiusPx: Double,
    /** Where the selected face says the outer ring should be. */
    val expectedOuterRadiusPx: Double,
    /** Black diameter over outer diameter, as the picture shows it. */
    val observedRatio: Double,
    /** The catalogue face whose proportions best match the picture. */
    val bestMatch: TargetFace?,
    val warning: String?
) {
    val looksWrong: Boolean get() = warning != null
}

object TargetGeometryCheck {

    /** Ring structure this much beyond the box means the box is too small. */
    private const val TOLERANCE = 1.15

    /** How far below the paper level a radius has to dip to count as a
     *  printed line. Generous, because an outer ring on a phone photograph is
     *  a thin grey line, not a black one. */
    private const val LINE_DEPTH = 22

    fun analyse(
        frame: LumaFrame,
        disc: DetectedDisc,
        face: TargetFace,
        candidates: List<TargetFace>
    ): GeometryCheck {
        val expected = disc.radiusPx * expectedRatioOf(face)

        val profile = radialProfile(frame, disc.centreXPx, disc.centreYPx)
        if (profile.isEmpty()) {
            return GeometryCheck(0.0, expected, 0.0, null, null)
        }
        // Paper level from the outer third, which on any sane framing is
        // mostly blank card.
        val paper = profile.drop(profile.size * 2 / 3).sorted().let {
            if (it.isEmpty()) 255 else it[it.size / 2]
        }

        var outermost = 0.0
        val start = (disc.radiusPx * 1.08).toInt().coerceAtLeast(3)
        for (r in start until profile.size - 2) {
            val v = profile[r]
            if (v <= paper - LINE_DEPTH && v <= profile[r - 1] && v <= profile[r + 1]) {
                outermost = r.toDouble()
            }
        }

        val observedRatio = if (outermost > 0) (disc.radiusPx * 2) / (outermost * 2) else 0.0
        val best = if (observedRatio > 0) {
            candidates
                .filter { it.blackDiameterMm > 0 && it.outerRadiusMm > 0 }
                .minByOrNull { abs(it.blackDiameterMm / (it.outerRadiusMm * 2) - observedRatio) }
        } else null

        val warning = when {
            outermost <= 0 -> null   // nothing found; say nothing rather than guess
            outermost > expected * TOLERANCE -> buildString {
                append("The picture has printed rings out to %.0f px, but the %s face puts its "
                    .format(outermost, face.name))
                append("outermost ring at %.0f px. The registration box is too small for this "
                    .format(expected))
                append("target — the wrong face is almost certainly selected.")
                if (best != null && best.id != face.id) {
                    append(" Its proportions match %s.".format(best.name))
                }
            }
            else -> null
        }

        if (warning != null) Logger.w("TargetGeometryCheck", warning)
        else Logger.i(
            "TargetGeometryCheck",
            "geometry consistent: outermost ring %.0f px, expected %.0f px, ratio %.3f"
                .format(outermost, expected, observedRatio)
        )

        return GeometryCheck(outermost, expected, observedRatio, best, warning)
    }

    /**
     * After registration, check that the face's PRINTED RINGS are actually
     * where the registration says they are.
     *
     * The outer-radius test above catches a box that is too small. It cannot
     * catch the other half of the problem: a box placed perfectly on the
     * outermost circle of a target whose RING SPACING does not match the
     * selected face. A six-ring 5-to-10 card registered as a ten-ring ISSF
     * face has its box in exactly the right place and every ring boundary in
     * the wrong one, so every score is wrong and nothing looks amiss.
     *
     * The test is direct: walk out along the radius and check that each ring
     * the face claims to have is a dark line in the picture. A ring that is
     * printed shows as a local minimum; one that only exists in the face
     * definition does not. Fewer than half of them present means the face
     * does not describe this target.
     *
     * Rings closer together than the profile can resolve are skipped rather
     * than counted as absent — on a 10 m air rifle face at a modest photo
     * size the rings are genuinely below one pixel apart, and failing that
     * card for it would be the check crying wolf.
     */
    fun verifyRings(frame: LumaFrame, reg: TargetRegistration, face: TargetFace): String? {
        if (face.rings.size < 3) return null
        val (cx, cy) = reg.homography.mmToPx(0.0, 0.0)
        if (cx.isNaN() || cy.isNaN()) return null

        val profile = radialProfile(frame, cx, cy)
        if (profile.size < 40) return null
        val paper = profile.drop(profile.size / 2).sorted().let { it[it.size / 2] }

        var testable = 0
        var present = 0
        for (ring in face.rings.sortedBy { it.radiusMm }) {
            val (px, py) = reg.homography.mmToPx(ring.radiusMm, 0.0)
            if (px.isNaN()) continue
            val r = hypot(px - cx, py - cy).toInt()
            if (r < 6 || r >= profile.size - 3) continue
            // Skip rings the profile cannot separate from their neighbours.
            val pitchPx = face.ringPitchMm?.let {
                val (qx, qy) = reg.homography.mmToPx(it, 0.0)
                if (qx.isNaN()) 0.0 else hypot(qx - cx, qy - cy)
            } ?: 4.0
            if (pitchPx < 3.0) continue
            testable++
            val local = (r - 2..r + 2).minOf { profile[it.coerceIn(0, profile.size - 1)] }
            if (local <= paper - LINE_DEPTH) present++
        }

        if (testable < 3) return null
        val fraction = present.toDouble() / testable
        Logger.i(
            "TargetGeometryCheck",
            "ring verification: %d of %d expected rings found in the picture (%.0f%%)"
                .format(present, testable, 100 * fraction)
        )
        if (fraction >= 0.5) return null

        return "Only %d of the %d rings the %s face expects are actually printed where the ".format(
            present, testable, face.name
        ) + "registration puts them. The box may be in the right place, but this target's ring " +
            "spacing does not match that face — scores computed from it will be wrong. Pick the " +
            "face that matches the card, or add it under Targets."
    }

    /**
     * The aiming mark's radius expressed in RING PITCHES, for a catalogue
     * face. Null when the face has no black or no even pitch.
     *
     * Scale-free on purpose. Comparing a photograph to a face normally needs
     * millimetres per pixel — which comes from the face, so a wrong face
     * produces a wrong scale that then makes the wrong face look consistent.
     * This ratio is a pure number measurable from the picture alone, so it
     * can judge the face without first trusting it.
     */
    fun blackInPitchesOf(face: TargetFace): Double? {
        val pitch = face.ringPitchMm ?: return null
        val black = face.blackDiameterMm / 2.0
        if (pitch <= 0.0 || black <= 0.0) return null
        return black / pitch
    }

    /**
     * Complains when the selected face does not match what the picture shows.
     *
     * THIS IS THE FAILURE THAT LOOKS LIKE A BROKEN DETECTOR. The face sets
     * millimetres per pixel, the radius of the scoring area, and which region
     * counts as black. Choose the wrong one and the rectified card comes out
     * at the wrong scale, so every hole falls outside the detector's size
     * gates and NOTHING is found — with no error anywhere, because each stage
     * did exactly what it was told.
     *
     * Measured on a real club target: registered against its own face the
     * detector found all five shots, and against ISSF 10 m Air Rifle it found
     * none. The two disagree on this ratio by 32 per cent.
     */
    fun faceMismatch(
        face: TargetFace,
        markRadiusPx: Double,
        pitchPx: Double,
        candidates: List<TargetFace>
    ): String? {
        if (markRadiusPx <= 0.0 || pitchPx <= 0.0) return null
        val expected = blackInPitchesOf(face) ?: return null
        val observed = markRadiusPx / pitchPx
        val error = abs(observed - expected) / expected
        if (error <= RATIO_TOLERANCE) return null

        val better = candidates
            .mapNotNull { c -> blackInPitchesOf(c)?.let { c to abs(observed - it) / it } }
            .filter { it.second <= RATIO_TOLERANCE }
            .minByOrNull { it.second }

        return buildString {
            append(("This does not look like %s. Its aiming mark should be %.1f ring widths " +
                "across; the photograph shows %.1f, which is %.0f%% out.")
                .format(face.name, expected, observed, error * 100))
            if (better != null) {
                append(" %s matches to %.0f%% — select it under Target, or tap ".format(
                    better.first.name, 100 * (1 - better.second)))
                append("\u201cIdentify and register\u201d to have it chosen for you.")
            } else {
                append(" No catalogue face matches these proportions. If this is a club or " +
                    "custom card, add it under Targets, or the score will be wrong.")
            }
            append(" Scoring against the wrong face usually finds no hits at all.")
        }
    }

    /** How far the measured black-to-pitch ratio may sit from the face's own
     *  before the face is called wrong. Real faces in the catalogue span 3.0
     *  to 7.03, so 12 per cent separates neighbours without flagging honest
     *  measurement error. */
    private const val RATIO_TOLERANCE = 0.12

    /** Outer diameter over black diameter for a face; 1.0 when it has no black. */
    fun expectedRatioOf(face: TargetFace): Double =
        if (face.blackDiameterMm > 0.0) (face.outerRadiusMm * 2.0) / face.blackDiameterMm else 1.0

    /**
     * Median brightness at each radius about a centre. A LOW percentile would
     * find thin lines more readily, but the median is what makes the paper
     * level meaningful, and an outer ring is thick enough on a phone
     * photograph to move it.
     */
    private fun radialProfile(frame: LumaFrame, cx: Double, cy: Double): IntArray {
        val maxR = min(min(cx, cy), min(frame.width - cx, frame.height - cy)).toInt()
        if (maxR < 20) return IntArray(0)
        val hist = Array(maxR + 1) { IntArray(256) }
        val count = IntArray(maxR + 1)
        // Every third pixel: the profile is an average over a whole
        // circumference, so it converges long before the full image does.
        var y = 0
        while (y < frame.height) {
            var x = 0
            while (x < frame.width) {
                val r = hypot(x - cx, y - cy).toInt()
                if (r <= maxR) { hist[r][frame.at(x, y)]++; count[r]++ }
                x += 3
            }
            y += 3
        }
        val out = IntArray(maxR + 1) { 255 }
        for (r in 0..maxR) {
            val total = count[r]
            if (total == 0) continue
            var acc = 0
            for (level in 0 until 256) {
                acc += hist[r][level]
                if (acc * 2 >= total) { out[r] = level; break }
            }
        }
        return out
    }
}
