package com.rfsat.sts.detect

import com.rfsat.sts.log.Logger
import kotlin.math.hypot

/**
 * ============================================================================
 *  LIVE DETECTION
 * ============================================================================
 *
 * Turns a stream of frames into shot EVENTS.
 *
 * The hard part of live detection is not finding the hole — [HoleDetector]
 * does that against the stored reference. It is deciding that what you found
 * is a shot rather than a fly, a shadow, a gust moving the target frame, or
 * the same hole seen again on the next frame. Four rules do that work, and
 * each exists because the alternative fails in a specific way:
 *
 *  1. PERSISTENCE. A candidate must appear in [requiredFrames] consecutive
 *     frames before it is reported. A hole is permanent; an insect, a blade
 *     of grass and a rolling shadow are not. This is the single most
 *     effective filter and it costs only the frames it waits.
 *
 *  2. STABILITY. Across those frames the candidate must stay within
 *     [stabilityToleranceMm]. A hole does not move. A shadow crawls.
 *
 *  3. GLOBAL-CHANGE VETO. If a large fraction of the face changed at once,
 *     the frame is discarded entirely. That is the target being replaced, the
 *     camera being knocked, or the sun coming out — never a shot. Without
 *     this veto a single bump reports thirty simultaneous hits.
 *
 *  4. DEDUPLICATION AGAINST THE ACCEPTED SET. Once a shot is accepted the
 *     reference is UPDATED to include it, so it stops being a difference at
 *     all. The explicit radius check is the belt to that braces: it catches
 *     the case where a second shot lands close enough to the first to merge
 *     with it, which must be reported as one new hole and flagged, not as two.
 *
 * ACOUSTIC GATING. When the caller feeds shot timestamps from the microphone
 * via [noteReport], candidates arriving outside a short window after a report
 * are held to a higher confidence bar. That is a large gain in a noisy visual
 * environment for almost no cost — and it is a preference, not a requirement,
 * because on an indoor 10 m range with several shooters the reports do not
 * belong to one firing point and the gate would do harm.
 */
class LiveHitDetector(
    private val reg: TargetRegistration,
    private val gaugeDiameterMm: Double,
    /** Consecutive frames a candidate must survive. Three is about 0.4 s at
     *  a typical analysis rate: long enough to reject a passing shadow,
     *  short enough that rapid fire at 4 s per shot still resolves. */
    private val requiredFrames: Int = 3,
    /** How far a candidate may wander between frames and still count as the
     *  same one. A tenth of the gauge is comfortably inside the centroid's
     *  own repeatability. */
    private val stabilityToleranceMm: Double = 0.5,
    /** Minimum confidence for acceptance without acoustic support. */
    private val minConfidence: Double = 0.35,
    /** Minimum confidence when a report was heard just before. */
    private val minConfidenceGated: Double = 0.20,
    /** Fraction of the face that may change before the frame is discarded
     *  as a disturbance rather than read as shots. */
    private val globalChangeVeto: Double = 0.08,
    private val acousticGateMs: Long = 1500L
) {

    /** A hole seen but not yet believed. */
    private data class Candidate(
        var xMm: Double,
        var yMm: Double,
        var bestConfidence: Double,
        var seenFrames: Int,
        var lastFrameIndex: Int
    )

    private var reference: LumaFrame? = null
    private val candidates = mutableListOf<Candidate>()
    private val accepted = mutableListOf<DetectedHole>()
    private var frameIndex = 0
    private var lastReportMs = 0L
    private var discardedFrames = 0

    /** Frames analysed since the reference was set. */
    var framesProcessed: Int = 0
        private set

    val acceptedHoles: List<DetectedHole> get() = accepted.toList()

    /** True once a clean reference exists and detection can begin. */
    val isArmed: Boolean get() = reference != null

    /**
     * Stores the clean target as the baseline. Everything reported afterwards
     * is measured against this frame, so it must be taken with the target in
     * its final position and nothing in front of it.
     */
    fun setReference(raw: LumaFrame) {
        reference = reg.rectify(raw)
        candidates.clear()
        framesProcessed = 0
        discardedFrames = 0
        Logger.i("LiveHitDetector", "Reference set (${reg.rectWidth}x${reg.rectHeight} rectified)")
    }

    /**
     * Re-baselines to the CURRENT state of the target without forgetting the
     * shots already accepted. Use it after the target has been bumped and
     * re-registered, or after a mid-string exposure change.
     */
    fun rebaseline(raw: LumaFrame) {
        reference = reg.rectify(raw)
        candidates.clear()
        Logger.i("LiveHitDetector", "Re-baselined; ${accepted.size} accepted shots retained")
    }

    /** Records that the microphone heard a report. */
    fun noteReport(atMs: Long = System.currentTimeMillis()) {
        lastReportMs = atMs
    }

    /**
     * Feeds one frame. Returns the shots newly ACCEPTED on this frame —
     * usually empty, occasionally one, rarely more than one.
     */
    fun onFrame(raw: LumaFrame, nowMs: Long = System.currentTimeMillis()): List<DetectedHole> {
        val ref = reference ?: return emptyList()
        frameIndex++
        framesProcessed++

        val current = reg.rectify(raw)
        val found = HoleDetector.detectByDifference(reg, ref, current, gaugeDiameterMm)

        // ---- rule 3: veto a frame in which too much changed at once ----
        val faceArea = Math.PI * (reg.face.outerRadiusMm.coerceAtLeast(1.0)).let { it * it }
        val holeArea = Math.PI * (gaugeDiameterMm / 2.0) * (gaugeDiameterMm / 2.0)
        val changedArea = found.sumOf { Math.PI * (it.diameterMm / 2.0) * (it.diameterMm / 2.0) }
        if (faceArea > 0 && changedArea / faceArea > globalChangeVeto && found.size > 3) {
            discardedFrames++
            if (discardedFrames % 10 == 1) {
                Logger.w(
                    "LiveHitDetector",
                    "Frame discarded: ${found.size} simultaneous changes covering " +
                        "${"%.1f".format(100 * changedArea / faceArea)}% of the face — " +
                        "the target moved, or the light changed"
                )
            }
            candidates.clear() // whatever was building is no longer trustworthy
            return emptyList()
        }
        if (holeArea <= 0.0) return emptyList()

        val gated = nowMs - lastReportMs <= acousticGateMs
        val bar = if (gated) minConfidenceGated else minConfidence

        val newlyAccepted = mutableListOf<DetectedHole>()

        for (hole in found) {
            // ---- rule 4: already known? ----
            if (accepted.any { hypot(it.xMm - hole.xMm, it.yMm - hole.yMm) < gaugeDiameterMm * 0.6 }) continue
            if (hole.confidence < bar * 0.5) continue // never worth tracking

            // ---- rules 1 and 2: match to a candidate, or open one ----
            val match = candidates.firstOrNull {
                hypot(it.xMm - hole.xMm, it.yMm - hole.yMm) <= stabilityToleranceMm
            }
            if (match == null) {
                candidates += Candidate(hole.xMm, hole.yMm, hole.confidence, 1, frameIndex)
                continue
            }
            if (match.lastFrameIndex == frameIndex) continue // two blobs, one candidate
            // Running mean position: averaging across the frames it survived
            // is a free improvement on the centroid, since the detection
            // noise is independent frame to frame.
            val n = match.seenFrames.toDouble()
            match.xMm = (match.xMm * n + hole.xMm) / (n + 1)
            match.yMm = (match.yMm * n + hole.yMm) / (n + 1)
            match.bestConfidence = maxOf(match.bestConfidence, hole.confidence)
            match.seenFrames++
            match.lastFrameIndex = frameIndex

            if (match.seenFrames >= requiredFrames && match.bestConfidence >= bar) {
                val shot = hole.copy(
                    xMm = match.xMm,
                    yMm = match.yMm,
                    confidence = match.bestConfidence
                )
                accepted += shot
                newlyAccepted += shot
                candidates.remove(match)
                Logger.i(
                    "LiveHitDetector",
                    "Shot accepted at (%.2f, %.2f) mm, confidence %.2f%s".format(
                        shot.xMm, shot.yMm, shot.confidence, if (gated) " [report-gated]" else ""
                    )
                )
            }
        }

        // Candidates not seen this frame decay: two missed frames and they
        // are dropped. Keeping them alive longer lets a slow-moving shadow
        // accumulate the persistence count it must not be allowed to reach.
        candidates.removeAll { frameIndex - it.lastFrameIndex > 2 }

        // Fold the new shots into the reference so they stop being
        // differences. Cheaper and more robust than maintaining an exclusion
        // list: the detector simply never sees them again.
        if (newlyAccepted.isNotEmpty()) {
            reference = current
        }

        return newlyAccepted
    }

    /** Removes an accepted shot — the user rejecting a false positive. The
     *  reference is NOT rewound, so the removed hole will not be re-reported;
     *  that is deliberate, since re-reporting exactly what the user has just
     *  rejected would be the more annoying failure. */
    fun rejectAccepted(hole: DetectedHole) {
        accepted.remove(hole)
    }

    fun reset() {
        accepted.clear()
        candidates.clear()
        framesProcessed = 0
        discardedFrames = 0
    }
}
