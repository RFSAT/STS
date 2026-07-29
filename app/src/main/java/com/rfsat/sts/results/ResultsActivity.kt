package com.rfsat.sts.results

import android.content.Intent
import android.os.Bundle
import android.view.View
import com.rfsat.sts.R
import com.rfsat.sts.databinding.ActivityResultsBinding
import com.rfsat.sts.detect.DetectedHole
import com.rfsat.sts.detect.SessionActivity
import com.rfsat.sts.profiles.ProfileRepository
import com.rfsat.sts.scoring.ScoringEngine
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityResultsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ScoringSession.attach(this)

        binding.btnAddMode.setOnClickListener {
            addMode = !addMode
            binding.btnAddMode.text = if (addMode) "Tap the plot to place a shot" else "Add shot by tapping"
            notifyUser(
                if (addMode) "Tap where the hole is. The app scores it exactly as it would a detected one."
                else "Tap-to-add is off."
            )
        }
        binding.btnFullCard.setOnClickListener {
            binding.plot.fitScoringAreaOnly = !binding.plot.fitScoringAreaOnly
            binding.btnFullCard.text =
                if (binding.plot.fitScoringAreaOnly) "Show whole card" else "Show scoring area"
        }
        binding.btnResetZoom.setOnClickListener { binding.plot.resetZoom() }

        binding.plot.onTapMm = { u, v -> onPlotTap(u, v) }

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
        notifyUser("Shot ${shot.index}: ${shot.displayValue}")
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
