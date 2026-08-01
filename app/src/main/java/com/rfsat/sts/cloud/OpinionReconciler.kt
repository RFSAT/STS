package com.rfsat.sts.cloud

import com.rfsat.sts.detect.DetectedHole
import kotlin.math.hypot

/**
 * Puts what the model saw beside what the app measured, and decides what — if
 * anything — the shooter should be asked about.
 *
 * THE RULE THIS ENFORCES: a position from the model never becomes a score. It
 * is only ever a place to look again. Every shot that ends up counted has had
 * its centre measured on the photograph by the local detector, because that
 * is the only estimate with the sub-millimetre accuracy scoring needs.
 */
object OpinionReconciler {

    /** How close a model spot must land to a measured hole to be the same
     *  shot. Generous: the model's positions carry a few per cent of the
     *  image, and the question here is only "did you already find this one?",
     *  where a false pairing costs nothing and a missed pairing invents a
     *  duplicate suggestion. */
    private const val SAME_SHOT_MM = 12.0

    data class Suggestion(
        val xMm: Double,
        val yMm: Double,
        val note: String
    )

    data class Reconciliation(
        val measured: Int,
        val claimed: Int,
        /** Places the model saw something and the detector did not. */
        val unconfirmed: List<Suggestion>,
        /** Holes the detector found that the model did not mention. Not an
         *  error — the model may simply have missed one — but worth showing,
         *  because it is also what a false positive looks like. */
        val unsupported: List<DetectedHole>,
        val faceAgrees: Boolean,
        /** True when the app found more than Claude did AND some of its finds
         *  are unsupported — the case where the useful action is removal. */
        val overDetected: Boolean,
        val summary: String
    )

    /**
     * [uMin]..[uMax] and [vMin]..[vMax] are the millimetre extent of the
     * image that was sent — which is the RECTIFIED photograph, already on the
     * scoring grid. Mapping a fraction of that image to millimetres is then
     * one linear step with no projection in it, so the model's coordinates
     * arrive in the same frame as the measured shots without passing through
     * anything that could add error of its own.
     */
    fun reconcile(
        opinion: SecondOpinion.Opinion,
        measured: List<DetectedHole>,
        faceName: String,
        /** Outermost scoring ring, millimetres. Used only to say WHERE the
         *  disputed marks are, because that is where the false ones live. */
        outerRadiusMm: Double,
        uMin: Double, uMax: Double, vMin: Double, vMax: Double
    ): Reconciliation {
        val spotsMm = opinion.spots.map { s ->
            Triple(uMin + s.xFrac * (uMax - uMin), vMax - s.yFrac * (vMax - vMin), s.note)
        }

        val unconfirmed = spotsMm.filter { (u, v, _) ->
            measured.none { hypot(it.xMm - u, it.yMm - v) <= SAME_SHOT_MM }
        }.map { (u, v, note) -> Suggestion(u, v, note) }

        val unsupported = measured.filter { m ->
            spotsMm.none { (u, v, _) -> hypot(m.xMm - u, m.yMm - v) <= SAME_SHOT_MM }
        }

        val faceAgrees = opinion.faceName.isBlank() ||
            opinion.faceName.equals("unknown", ignoreCase = true) ||
            opinion.faceName.equals(faceName, ignoreCase = true) ||
            faceName.contains(opinion.faceName, ignoreCase = true) ||
            opinion.faceName.contains(faceName, ignoreCase = true)

        // WHICH WAY THE DISAGREEMENT RUNS DECIDES WHAT TO OFFER.
        //
        // The first version of this only ever offered to ADD what Claude saw
        // and the app missed. On a card where the app had over-detected —
        // fourteen marks, several of them printing outside the scoring area,
        // against seven real shots — that made the plot worse, not better:
        // three more were added and nothing was taken away. Over-detection is
        // the app's measured failure mode, so an aid that can only add to it
        // is an aid pointed the wrong way.
        val overDetected = measured.size > opinion.holeCount && unsupported.isNotEmpty()

        val summary = buildString {
            append("Claude counted ${opinion.holeCount}; the app measured ${measured.size}.")
            if (overDetected) {
                append(" The app has found MORE than Claude can see, which usually means it has ")
                append("marked something printed as a shot — start by looking at what to remove, ")
                append("not at what to add.")
            }
            if (!opinion.usable) {
                append(" It also says this photograph is not really good enough to score — ")
                append("that is worth acting on before anything else.")
            }
            if (!faceAgrees) {
                append(" It reads the card as \"${opinion.faceName}\" where the app is scoring ")
                append("against $faceName; check the face before the shots.")
            }
            when {
                unconfirmed.isEmpty() && unsupported.isEmpty() ->
                    append(" The two agree on every shot.")
                unconfirmed.isNotEmpty() ->
                    append(" ${unconfirmed.size} place(s) it saw a hole and the app did not — " +
                        "these are offered as suggestions to accept or reject, not scored.")
            }
            if (unsupported.isNotEmpty()) {
                append(" ${unsupported.size} shot(s) the app found were not mentioned. Claude " +
                    "missing a real shot looks exactly the same as the app inventing one, so " +
                    "these are offered for removal rather than removed.")
                val outside = unsupported.count { hypot(it.xMm, it.yMm) > outerRadiusMm }
                if (outside > 0) {
                    append(" ${outside} of them lie outside the scoring rings, where every ")
                    append("false mark this app has ever produced has been found.")
                }
            }
            if (opinion.comment.isNotBlank()) append(" It notes: ${opinion.comment}")
        }
        return Reconciliation(measured.size, opinion.holeCount, unconfirmed, unsupported,
            faceAgrees, overDetected, summary)
    }
}
