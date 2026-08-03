package com.rfsat.sts.results

import android.content.Intent
import android.graphics.Bitmap
import android.util.Base64
import android.os.Bundle
import android.view.View
import com.rfsat.sts.R
import com.rfsat.sts.databinding.ActivityResultsBinding
import com.rfsat.sts.cloud.CloudSettings
import com.rfsat.sts.cloud.OpinionReconciler
import com.rfsat.sts.cloud.SecondOpinion
import com.rfsat.sts.detect.DetectedHole
import com.rfsat.sts.detect.FocusedRemeasure
import com.rfsat.sts.detect.LumaFrame
import com.rfsat.sts.detect.SessionActivity
import com.rfsat.sts.profiles.ProfileRepository
import com.rfsat.sts.scoring.ScoringEngine
import com.rfsat.sts.scoring.ScoredPhoto
import com.rfsat.sts.scoring.ScoringSession
import com.rfsat.sts.scoring.Shot
import com.rfsat.sts.scoring.ShotDistribution
import com.rfsat.sts.ui.BaseActivity
import com.rfsat.sts.ui.UnitsManager

/**
 * The scored session: plot, total, group, and the correction.
 *
 * ORDER OF THE SCREEN IS AN OPINION. The total is at the top because it is
 * what the shooter wants first, but the CORRECTION sits above the group
 * statistics and the shot list, ahead of everything except the plot. That is
 * deliberate: the total describes what already happened and cannot be
 * changed, while the correction is the only thing on the screen that can
 * improve the next string. Burying it under a statistics table would be
 * putting the least actionable content first.
 */
class ResultsActivity : BaseActivity() {

    private lateinit var binding: ActivityResultsBinding
    private var addMode = false
    private var moveMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityResultsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ScoringSession.attach(this)

        binding.btnAddMode.setOnClickListener {
            addMode = !addMode
            if (addMode) { moveMode = false; binding.plot.editMode = false; binding.btnMoveMode.text = "Move" }
            binding.btnAddMode.text = if (addMode) "Placing" else "Add"
            notifyUser(
                if (addMode) "Tap where the hole is. The app scores it exactly as it would a detected one."
                else "Tap-to-add is off."
            )
        }
        binding.btnMoveMode.setOnClickListener {
            moveMode = !moveMode
            if (moveMode) addMode = false
            binding.btnAddMode.text = "Add"
            binding.btnMoveMode.text = if (moveMode) "Done" else "Move"
            binding.plot.editMode = moveMode
            notifyUser(
                if (moveMode) "Drag any shot to reposition it. It is rescored where you drop it."
                else "Move mode off."
            )
        }
        // Deleting needs a shot chosen first, and the plot already has a
        // selection: a tap outside add- and move-mode picks the nearest shot.
        // Deleting "the last one" instead would be the wrong shot as often as
        // not, since detection order has nothing to do with shooting order.
        binding.btnDeleteShot.setOnClickListener {
            val shots = ScoringSession.state.shots
            val idx = binding.plot.selectedShotIndex
            if (idx == null || idx !in shots.indices) {
                notifyUser(
                    "Tap a shot on the plot first — with Add and Move both off — and it will be " +
                        "highlighted. Delete then removes that one."
                )
                return@setOnClickListener
            }
            val shot = shots[idx]
            ScoringSession.removeShot(shot)
            binding.plot.selectedShotIndex = null
            refresh()
            notifyUser("Removed shot %d, worth %s, at %.1f, %.1f mm."
                .format(shot.index, shot.displayValue, shot.xMm, shot.yMm))
        }

        binding.plot.onShotMoved = { shot, x, y -> moveShot(shot, x, y) }

        binding.btnFullCard.setOnClickListener {
            binding.plot.fitScoringAreaOnly = !binding.plot.fitScoringAreaOnly
            binding.btnFullCard.text =
                if (binding.plot.fitScoringAreaOnly) "Whole card" else "Scoring area"
        }
        binding.btnResetZoom.setOnClickListener { binding.plot.resetZoom() }

        // The photograph is the only view in which a MISSED shot is visible.
        // On the template an undetected hole leaves nothing behind — there is
        // simply no marker where one should be, and nothing to notice. Over
        // the shooter's own card the hole is plainly there with no marker on
        // it, which is what makes adding it by hand practical.
        binding.btnShowPhoto.setOnClickListener {
            if (!ScoredPhoto.available) {
                notifyUser(
                    "No photograph is stored for this session. Score a target from a photo " +
                        "under Import and it will be kept for this view."
                )
                return@setOnClickListener
            }
            binding.plot.showPhoto = !binding.plot.showPhoto
            binding.btnShowPhoto.text =
                if (binding.plot.showPhoto) "Template" else "My photo"
            notifyUser(
                if (binding.plot.showPhoto)
                    "Showing your photograph with the shots on it. The printed template is " +
                        "hidden so the holes themselves stay visible."
                else "Showing the target template."
            )
        }
        binding.btnShowPhoto.isEnabled = ScoredPhoto.available

        binding.plot.onTapMm = { u, v -> onPlotTap(u, v) }

        binding.btnSecondOpinion.setOnClickListener { askSecondOpinion() }
        binding.btnShare.setOnClickListener { share(ScoringSession.asReport(this), "STS session report") }
        binding.btnCsv.setOnClickListener { share(ScoringSession.asCsv(), "STS shots CSV") }
        binding.btnClearShots.setOnClickListener { confirmClearShots() }
        binding.btnNewSession.setOnClickListener {
            ScoringSession.finish()
            ScoringSession.clear()
            startActivity(Intent(this, SessionActivity::class.java).putExtra(SessionActivity.EXTRA_NEW, true))
            finish()
        }

        binding.etTime.setText(
            if (ScoringSession.state.stageSeconds > 0) "%.2f".format(ScoringSession.state.stageSeconds) else ""
        )
        binding.etNotes.setText(ScoringSession.state.notes)

        setupBottomNav(R.id.nav_results)
        runCatching { refresh() }.onFailure { notifyUser("Could not render the results: ${it.message}") }
    }

    override fun onResume() {
        super.onResume()
        runCatching { refresh() }
        setupBottomNav(R.id.nav_results)
    }

    override fun onPause() {
        super.onPause()
        binding.etTime.text.toString().toDoubleOrNull()?.let { ScoringSession.setStageSeconds(it) }
        ScoringSession.setNotes(binding.etNotes.text.toString())
    }

    /**
     * A tap on the plot either adds a shot or selects the nearest one. Which,
     * depends on the mode toggle — an unmoded plot where a stray tap silently
     * added a tenth shot to a nine-shot series would be worse than useless.
     */
    private fun onPlotTap(uMm: Double, vMm: Double) {
        val face = ScoringSession.face(this)
        val rules = ScoringSession.rules(this)

        if (!addMode) {
            val nearest = ScoringSession.state.shots.minByOrNull {
                Math.hypot(it.xMm - uMm, it.yMm - vMm)
            }
            binding.plot.selectedShotIndex = nearest?.index
            if (nearest != null) offerShotActions(nearest)
            return
        }

        val shot = placeManualShot(uMm, vMm)
        notifyUser("Shot ${shot.index}: ${shot.displayValue}")
    }


    /**
     * Takes each suggestion as a PLACE TO LOOK and measures what is actually
     * there, so a score never carries a position that came off a picture.
     *
     * Claude places a hole to within a few per cent of the image — several
     * millimetres on a 170 mm card, up to a whole ring. The app measures a
     * hole it can see to between 0.2 and 1.7 mm. So the suggestion decides
     * WHERE TO MEASURE and the measurement decides where the shot goes.
     *
     * A suggestion that lands on blank paper measures nothing, and is then
     * reported as unconfirmed rather than quietly placed. That is the whole
     * point of doing this: the model can be wrong about a hole existing, and
     * when it is, the app should say so instead of scoring it.
     */
    private fun addSuggested(suggestions: List<OpinionReconciler.Suggestion>) {
        val photo = ScoredPhoto.bitmap
        val rules = ScoringSession.rules(this)
        val face = ScoringSession.face(this)
        val blackR = face.blackDiameterMm / 2.0
        val ringRadii = face.rings.map { it.diameterMm / 2.0 }.toDoubleArray()
        val frame = photo?.let { LumaFrame.fromBitmap(it) }

        var measured = 0
        var placedAsGiven = 0
        var refused = 0
        var duplicates = 0
        var worstDrift = 0.0
        for (s in suggestions) {
            val found = if (frame == null) null else FocusedRemeasure.at(
                frame, ScoredPhoto.uMinMm, ScoredPhoto.uMaxMm,
                ScoredPhoto.vMinMm, ScoredPhoto.vMaxMm,
                blackR, rules.gaugeDiameterMm, ringRadii, s.xMm, s.yMm
            )
            when {
                found != null -> {
                    // ALREADY THERE? A measured position is the right thing to
                    // compare, not the model's. Claude places a hole several
                    // millimetres out, so a suggestion for a shot the app HAD
                    // already found can miss it on raw coordinates and be
                    // added a second time — which is how a card with seven
                    // shots ends up with seventeen marks. Once re-measured,
                    // the suggestion has snapped onto the real hole, and two
                    // marks on the same hole are unmistakable.
                    val dup = ScoringSession.state.shots.any {
                        // 1.5 gauges, not 1: the re-measure can settle a
                        // millimetre or two from where the app's own sweep put
                        // the same hole, and at exactly one gauge that gap was
                        // enough to add a second mark to a shot already there.
                        Math.hypot(it.xMm - found.xMm, it.yMm - found.yMm) <= rules.gaugeDiameterMm * 1.5
                    }
                    if (dup) { duplicates++ } else {
                        placeManualShot(found.xMm, found.yMm)
                        worstDrift = maxOf(worstDrift, found.driftMm)
                        measured++
                    }
                }
                frame == null -> { placeManualShot(s.xMm, s.yMm); placedAsGiven++ }
                else -> refused++
            }
        }

        val msg = buildString {
            if (measured > 0) {
                append("%d measured on the photograph and placed".format(measured))
                append(" — the suggestion pointed, the app measured")
                if (worstDrift > 0.05) append(", moving it by up to %.1f mm".format(worstDrift))
                append(". ")
            }
            if (duplicates > 0) {
                append("%d were the shots the app had ALREADY found, once measured, and were ".format(duplicates))
                append("not added twice. ")
            }
            if (refused > 0) {
                append("%d could NOT be confirmed: nothing hole-like was found where it pointed, ".format(refused))
                append("so nothing was placed. Add them by hand from the plot if you can see them. ")
            }
            if (placedAsGiven > 0) {
                append("%d placed at the suggested position because this session has no ".format(placedAsGiven))
                append("photograph to measure on — check those against the card. ")
            }
            if (measured + placedAsGiven > 0) {
                append("All are marked MANUAL, because it was not this app that found them.")
            }
        }
        notifyUser(msg.ifBlank { "Nothing to add." })
    }


    /**
     * Offers to remove the marks Claude did not see.
     *
     * NOT automatic, and the wording says why: Claude missing a real shot and
     * the app inventing one look identical from here. But over-detection is
     * this app's measured failure — printing outside the rings read as shots —
     * so refusing to offer removal at all left the second opinion able only
     * to make an over-detected card worse.
     */
    private fun offerRemoval(unsupported: List<DetectedHole>) {
        val face = ScoringSession.face(this)
        val outer = face.outerRadiusMm
        // EVERYTHING OUTSIDE THE RINGS IS A CANDIDATE, not only what Claude
        // failed to mention.
        //
        // The first version offered only the marks with no Claude spot within
        // twelve millimetres. On a card with fourteen marks and seven real
        // shots, a false mark that happened to sit near a real one counted as
        // "supported" and was never offered — so accepting every removal
        // still left most of them, which is what was reported. A mark beyond
        // the scoring rings cannot score whatever else is true of it, and if
        // Claude has counted fewer shots than the app has marked, those are
        // the ones to put in front of the shooter.
        // Exactly the set the reconciler decided and the button counted.
        val victims = ScoringSession.state.shots.filter { shot ->
            unsupported.any { Math.hypot(it.xMm - shot.xMm, it.yMm - shot.yMm) < 0.01 }
        }
        if (victims.isEmpty()) { notifyUser("Nothing left to remove."); return }
        val outside = victims.count { Math.hypot(it.xMm, it.yMm) > outer }
        val msg = buildString {
            append("Remove ${victims.size} mark(s) Claude does not see?")
            if (outside > 0) append("\n\n$outside lie outside the scoring rings.")
            append("\n\nA shot Claude missed looks the same as one the app invented — check the plot first.")
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Remove unsupported marks")
            .setMessage(msg)
            .setPositiveButton("Remove all ${victims.size}") { _, _ ->
                ScoringSession.removeShots(victims)
                refresh()
                notifyUser("Removed ${victims.size}. Undo by adding them back from the plot.")
            }
            .setNeutralButton(
                if (outside > 0) "Remove only the $outside outside the rings" else "Cancel"
            ) { _, _ ->
                if (outside > 0) {
                    val out = victims.filter { Math.hypot(it.xMm, it.yMm) > outer }
                    ScoringSession.removeShots(out)
                    refresh()
                    notifyUser("Removed $outside outside the scoring rings.")
                }
            }
            .setNegativeButton("Keep them", null)
            .show()
    }

    /** Places a shot the shooter asked for, at millimetre coordinates, marked
     *  MANUAL. Shared by a tap on the plot and by accepting a suggestion from
     *  the second opinion, so the two cannot drift apart — and so that a
     *  suggested shot is recorded as hand-placed, which is what it is. */
    private fun placeManualShot(uMm: Double, vMm: Double): Shot {
        val face = ScoringSession.face(this)
        val rules = ScoringSession.rules(this)
        val hole = DetectedHole(
            xMm = uMm, yMm = vMm,
            diameterMm = rules.gaugeDiameterMm,
            contrast = 0.0, confidence = 1.0, elongation = 1.0
        )
        val nextIndex = ScoringSession.state.shots.count { !it.sighter } + 1
        val shot = ScoringEngine.scoreHole(
            hole, face, rules, ProfileRepository(this).getBullet(),
            index = nextIndex,
            series = ScoringEngine.seriesFor(nextIndex, rules),
            manual = true
        )
        ScoringSession.addShot(shot, rules)
        refresh()
        return shot
    }

    /** Wipes the recorded shots but keeps the target, rules and distance, so
     *  a target can be re-scored without setting the session up again. */
    private fun confirmClearShots() {
        if (ScoringSession.state.shots.isEmpty()) {
            notifyUser("There are no shots to clear.")
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Clear all shots?")
            .setMessage(
                "${ScoringSession.state.shots.size} shot(s) will be removed. The target, rules and " +
                    "distance stay as they are, so you can score the same target again."
            )
            .setPositiveButton("Clear") { _, _ ->
                ScoringSession.clearShots()
                refresh()
                notifyUser("Cleared.")
            }
            .setNegativeButton("Keep", null)
            .show()
    }

    /**
     * Rescores a shot at the position it was dragged to.
     *
     * Re-run through the scoring engine rather than merely moving the marker:
     * the value, the inner-ten flag and the zone all depend on where it is,
     * and a moved shot that kept its old value would be the plot and the
     * total disagreeing with each other.
     */
    private fun moveShot(shot: Shot, xMm: Double, yMm: Double) {
        val face = ScoringSession.face(this)
        val rules = ScoringSession.rules(this)
        val hole = DetectedHole(xMm, yMm, rules.gaugeDiameterMm, 0.0, 1.0, 1.0)
        val rescored = ScoringEngine.scoreHole(
            hole, face, rules, ProfileRepository(this).getBullet(),
            index = shot.index, series = shot.series, sighter = shot.sighter, manual = true
        ).copy(timestampMs = shot.timestampMs)
        ScoringSession.replaceShot(shot, rescored)
        refresh()
        notifyUser("Shot ${shot.index} moved: ${shot.displayValue} → ${rescored.displayValue}")
    }

    private fun offerShotActions(shot: Shot) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Shot ${shot.index} — ${shot.displayValue}")
            .setMessage(
                "%.2f mm right, %.2f mm up (%.2f mm from centre, %s)\n%s, confidence %.2f".format(
                    shot.xMm, shot.yMm, shot.radiusMm, shot.clockPosition,
                    if (shot.manual) "placed by hand" else "detected", shot.confidence
                )
            )
            .setPositiveButton("Delete") { _, _ ->
                ScoringSession.removeShot(shot); refresh()
            }
            .setNeutralButton(if (shot.sighter) "Make it count" else "Mark as sighter") { _, _ ->
                ScoringSession.replaceShot(shot, shot.copy(sighter = !shot.sighter))
                refresh()
            }
            .setNegativeButton("Close", null)
            .show()
    }


    /**
     * Asks Claude what it sees, then puts that beside what was measured.
     *
     * NOTHING HERE CHANGES A SCORE BY ITSELF. The model's positions are used
     * only to point at somewhere the detector may have missed, and each one
     * is offered to the shooter to accept or reject. A shot accepted this way
     * is marked MANUAL, exactly as a tap on the plot is, so a report can
     * never present a suggested shot as a measured one.
     */
    private fun askSecondOpinion() {
        if (!CloudSettings.configured(this)) {
            notifyUser("Set a Claude API key in Settings and switch the second opinion on.")
            return
        }
        val bmp = ScoredPhoto.bitmap
        if (bmp == null) {
            notifyUser(
                "There is no photograph in this session to look at. The second opinion reads the " +
                    "picture, so it has nothing to work from on a session scored by hand."
            )
            return
        }
        val fullDelegation = CloudSettings.fullDelegation(this)
        val key = CloudSettings.apiKey(this)
        val model = CloudSettings.model(this)
        val measured = ScoringSession.state.shots.map {
            DetectedHole(xMm = it.xMm, yMm = it.yMm, diameterMm = 0.0, contrast = 0.0,
                confidence = it.confidence, elongation = 1.0)
        }
        val faceName = ScoringSession.face(this).name
        val outerMm = ScoringSession.face(this).outerRadiusMm
        val uMin = ScoredPhoto.uMinMm; val uMax = ScoredPhoto.uMaxMm
        val vMin = ScoredPhoto.vMinMm; val vMax = ScoredPhoto.vMaxMm

        binding.btnSecondOpinion.isEnabled = false
        notifyUser("Asking Claude…")
        Thread {
            // Re-encoded rather than sent at full size: a phone photograph can
            // run to several megabytes, the model gains nothing past a couple
            // of thousand pixels, and the shooter is paying per pixel.
            val scaled = scaleForUpload(bmp)
            val out = java.io.ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            val result = SecondOpinion.ask(key, model, b64, scoreToo = fullDelegation)
            runOnUiThread {
                binding.btnSecondOpinion.isEnabled = true
                when (result) {
                    is SecondOpinion.Result.Failed -> notifyUser(result.message)
                    is SecondOpinion.Result.Ok -> {
                        if (fullDelegation) scoreFromClaude(result.opinion, uMin, uMax, vMin, vMax)
                        else {
                            val rec = OpinionReconciler.reconcile(
                                result.opinion, measured, faceName, outerMm, uMin, uMax, vMin, vMax
                            )
                            showOpinion(rec, result.inputTokens, result.outputTokens)
                        }
                    }
                }
            }
        }.start()
    }

    /** Longest edge to 1568 px: past roughly this the model is charged for
     *  detail it does not use. */
    private fun scaleForUpload(bmp: Bitmap): Bitmap {
        val longest = maxOf(bmp.width, bmp.height)
        if (longest <= 1568) return bmp
        val f = 1568.0 / longest
        return Bitmap.createScaledBitmap(
            bmp, (bmp.width * f).toInt(), (bmp.height * f).toInt(), true)
    }


    /**
     * Replaces the app's answer with Claude's, existence AND position.
     *
     * The position is taken as Claude gives it, which is what the setting
     * asks for and which costs accuracy: several millimetres against the
     * 0.2 to 1.7 mm the app measures for a hole it can see. Every shot placed
     * here is therefore marked hand-placed, so the Results list and any
     * report show it as a position that was not measured.
     */

    /**
     * Throws away the app's shots and takes Claude's, positions and all.
     *
     * The picture sent is the RECTIFIED card, which is already on the
     * millimetre grid the plot draws in — so a fraction of that image maps
     * back with one linear step and the marks land exactly where the shooter
     * sees them on the photograph. That is the whole reason for sending the
     * rectified copy rather than the original.
     *
     * The app still scores each position from the ring geometry, and reports
     * where that disagrees with the ring Claude gave. It does not silently
     * pick a winner: a disagreement means the position or the ring is wrong,
     * and which shots those are is the useful thing to know.
     */
    private fun scoreFromClaude(
        opinion: SecondOpinion.Opinion,
        uMin: Double, uMax: Double, vMin: Double, vMax: Double
    ) {
        val face = ScoringSession.face(this)
        val gauge = ScoringSession.rules(this).gaugeDiameterMm
        ScoringSession.removeShots(ScoringSession.state.shots.toList())
        var disagreed = 0
        for (sp in opinion.spots) {
            val u = uMin + sp.xFrac * (uMax - uMin)
            val v = vMax - sp.yFrac * (vMax - vMin)
            val shot = placeManualShot(u, v)
            if (sp.ring >= 0) {
                val ours = face.scoreInteger(Math.hypot(u, v), gauge / 2.0)
                if (ours != sp.ring) disagreed++
            }
        }
        refresh()
        notifyUser(buildString {
            append("Scored by Claude: ${opinion.spots.size} shots. ")
            if (disagreed > 0) {
                append("$disagreed disagree with the ring the geometry gives at that position — ")
                append("check those on the plot. ")
            }
            append("All are marked hand-placed: the positions were not measured.")
            if (opinion.comment.isNotBlank()) append(" It notes: ${opinion.comment}")
        })
    }

    private fun applyOverride(rec: OpinionReconciler.Reconciliation) {
        val gauge = ScoringSession.rules(this).gaugeDiameterMm
        val victims = ScoringSession.state.shots.filter { shot ->
            rec.unsupported.any { Math.hypot(it.xMm - shot.xMm, it.yMm - shot.yMm) < 0.01 }
        }
        ScoringSession.removeShots(victims)
        var added = 0
        for (s in rec.unconfirmed) {
            val dup = ScoringSession.state.shots.any {
                Math.hypot(it.xMm - s.xMm, it.yMm - s.yMm) <= gauge * 1.5
            }
            if (!dup) { placeManualShot(s.xMm, s.yMm); added++ }
        }
        refresh()
        notifyUser(buildString {
            append("Claude's answer applied: ${victims.size} removed, $added added. ")
            append("Added shots use Claude's positions, which carry several millimetres — ")
            append("they are marked hand-placed and are worth checking on the plot.")
        })
    }

    private fun showOpinion(rec: OpinionReconciler.Reconciliation, inTok: Int, outTok: Int) {
        val msg = buildString {
            append(rec.summary)
            append("\n\n")
            append("Nothing here has changed your score yet.")
        }
        // ---- Claude wins, if that is what the shooter asked for ----
        //
        // Applied without a prompt, because a setting that then asks every
        // time is not a setting. What it is NOT allowed to do is pretend the
        // result was measured: everything it places is marked hand-placed.
        if (CloudSettings.overrideApp(this)) {
            applyOverride(rec)
            return
        }

        val b = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Second opinion")
            .setMessage(msg)
            .setNegativeButton("Close", null)
        // The primary button follows the direction of the disagreement. On an
        // over-detected card the useful action is removal, and offering "add"
        // first is how the second opinion made such a card worse.
        if (rec.overDetected) {
            b.setPositiveButton("Review %d to remove".format(rec.unsupported.size)) { _, _ ->
                offerRemoval(rec.unsupported)
            }
            if (rec.unconfirmed.isNotEmpty()) {
                b.setNeutralButton("Add %d missed".format(rec.unconfirmed.size)) { _, _ ->
                    addSuggested(rec.unconfirmed)
                }
            }
        } else {
            if (rec.unconfirmed.isNotEmpty()) {
                b.setPositiveButton("Add %d suggested".format(rec.unconfirmed.size)) { _, _ ->
                    addSuggested(rec.unconfirmed)
                }
            }
            if (rec.unsupported.isNotEmpty()) {
                b.setNeutralButton("Review %d to remove".format(rec.unsupported.size)) { _, _ ->
                    offerRemoval(rec.unsupported)
                }
            }
        }
        b.show()
    }

    private fun refresh() {
        ScoringSession.adoptSelectionIfEmpty(this)
        val face = ScoringSession.face(this)
        val rules = ScoringSession.rules(this)
        val res = ScoringSession.result(this)
        val grp = ScoringSession.group()
        val corr = ScoringSession.correction(this)
        val distance = ScoringSession.state.distanceM

        binding.plot.face = face
        binding.plot.shots = ScoringSession.state.shots
        binding.plot.group = grp
        binding.plot.poaXMm = ScoringSession.state.poaXMm
        binding.plot.poaYMm = ScoringSession.state.poaYMm

        binding.tvHeadline.text = buildString {
            append(res.displayTotal)
            if (res.maxScore > 0) append(" / ${"%.0f".format(res.maxScore)}")
            if (rules.countInnerTens && res.innerTens > 0) append("  ·  ${res.innerTens}×")
        }
        binding.tvSubhead.text = buildString {
            append("${rules.name} · ${face.name} · ${UnitsManager.formatDistance(distance)}")
            append(" · gauge ${rules.gaugeDiameterMm} mm")
            if (ScoringSession.state.shots.isNotEmpty()) {
                append("\n(the face and rules a session was scored with are kept; " +
                    "clear the shots to follow a new selection)")
            }
            if (res.derivedFigureLabel.isNotEmpty())
                append("\n${res.derivedFigureLabel}: ${"%.4f".format(res.derivedFigure)}")
            if (res.lowerIsBetter) append("   (lower is better)")
        }

        // ---- correction ----
        binding.tvCorrection.text = corr.instruction
        binding.tvCorrectionDetail.text = if (!corr.valid) "" else buildString {
            appendLine("Move the point of impact %.1f mm %s and %.1f mm %s.".format(
                Math.abs(corr.moveImpactYMm), if (corr.moveImpactYMm > 0) "up" else "down",
                Math.abs(corr.moveImpactXMm), if (corr.moveImpactXMm > 0) "right" else "left"
            ))
            appendLine("Angular: %.2f MRAD elevation, %.2f MRAD windage".format(
                corr.elevationMrad, corr.windageMrad
            ))
            append("         %.2f MOA elevation, %.2f MOA windage".format(
                corr.elevationMoa, corr.windageMoa
            ))
        }

        // ---- group ----
        binding.tvGroup.text = if (grp.shotCount == 0) "No shots yet." else buildString {
            appendLine("Shots           ${grp.shotCount}")
            appendLine("Centre offset   ${UnitsManager.formatSize(grp.mpiOffsetMm)}  " +
                "(${"%.1f".format(grp.mpiXMm)}, ${"%.1f".format(grp.mpiYMm)} mm)")
            appendLine("Centre known to ±${UnitsManager.formatSize(grp.mpiUncertaintyMm)}")
            appendLine("Extreme spread  ${UnitsManager.formatSize(grp.extremeSpreadMm)}  " +
                "(${"%.2f".format(grp.extremeSpreadMoa(distance))} MOA, " +
                "${"%.2f".format(grp.extremeSpreadMrad(distance))} MRAD)")
            appendLine("Mean radius     ${UnitsManager.formatSize(grp.meanRadiusMm)}")
            appendLine("R50             ${UnitsManager.formatSize(grp.r50Mm)}")
            appendLine("Horizontal SD   ${UnitsManager.formatSize(grp.horizontalSdMm)}")
            append("Vertical SD     ${UnitsManager.formatSize(grp.verticalSdMm)}")
        }

        // ---- distribution ----
        val dist = ShotDistribution.of(ScoringSession.state.shots, face, rules)
        binding.tvDistribution.text = dist.summary()
        binding.histogram.hideEmptyBuckets = face.zones.isNotEmpty()
        binding.histogram.distribution = dist

        // ---- series ----
        binding.tvSeries.text = if (res.series.isEmpty()) "—" else buildString {
            res.series.forEach { s ->
                appendLine("%2d  %6s   %s".format(
                    s.number, s.displayTotal, s.shots.joinToString(" ") { it.displayValue }
                ))
            }
        }

        // ---- warnings ----
        val allWarnings = res.warnings + corr.warnings +
            (if (!face.verified) listOf(
                "The ${face.name} face uses commonly published dimensions rather than a governing " +
                    "body's own table. Verify before quoting this score."
            ) else emptyList())
        if (allWarnings.isEmpty()) {
            binding.hdrWarnings.visibility = View.GONE
            binding.tvWarnings.visibility = View.GONE
        } else {
            binding.hdrWarnings.visibility = View.VISIBLE
            binding.tvWarnings.visibility = View.VISIBLE
            binding.tvWarnings.text = allWarnings.joinToString("\n\n") { "• $it" }
        }

        // ---- shot list ----
        binding.tvShots.text = if (ScoringSession.state.shots.isEmpty()) "None." else buildString {
            appendLine("  #  val      x       y       r    src")
            ScoringSession.state.shots.forEach { s ->
                appendLine("%3s  %-5s %7.1f %7.1f %6.1f  %s".format(
                    if (s.sighter) "S" else s.index.toString(),
                    s.displayValue, s.xMm, s.yMm, s.radiusMm,
                    if (s.manual) "hand" else "auto"
                ))
            }
        }
    }

    private fun share(text: String, subject: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, subject))
    }

    override fun swipeExemptViews(): List<View> = listOf(binding.plot)
}
