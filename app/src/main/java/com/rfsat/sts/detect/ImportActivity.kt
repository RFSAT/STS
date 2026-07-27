package com.rfsat.sts.detect

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import com.rfsat.sts.R
import com.rfsat.sts.databinding.ActivityImportBinding
import com.rfsat.sts.log.Logger
import com.rfsat.sts.profiles.ProfileRepository
import com.rfsat.sts.results.ResultsActivity
import com.rfsat.sts.rules.RuleRepository
import com.rfsat.sts.rules.RuleSet
import com.rfsat.sts.scoring.ScoringEngine
import com.rfsat.sts.scoring.ScoringSession
import com.rfsat.sts.scoring.ShotDistribution
import com.rfsat.sts.targets.TargetFace
import com.rfsat.sts.targets.TargetRepository
import com.rfsat.sts.ui.BaseActivity
import com.rfsat.sts.ui.UnitsManager

/**
 * ============================================================================
 *  SCORING A PHOTOGRAPH
 * ============================================================================
 *
 * Score a target after the fact, from a picture of it — the relay is over,
 * the card is in your hand, and nobody filmed it. This is how most people
 * will actually use the app, and it is the one path that needs no camera
 * pointed downrange, no tripod and no reference frame captured in advance.
 *
 * TWO MODES, and the screen is explicit about which one it is in, because
 * their failure modes are completely different:
 *
 *   WITH A CLEAN PHOTOGRAPH of the same target before it was shot, scoring is
 *   a difference. The printed rings, the paper grain, the staple shadows and
 *   the black aiming mark are all in both images and all cancel. What is left
 *   is holes. This is the accurate path and it is worth the extra photograph.
 *
 *   WITHOUT one, the printed geometry IS the difficulty: a ring line is dark,
 *   a hole is dark, and the aiming mark is darker than either. The absolute
 *   detector separates them on scale and shape rather than on brightness and
 *   does a respectable job, but it can be fooled, and its characteristic
 *   failure — a printed feature counted as a shot — looks exactly like a real
 *   detection on the plot. So the screen says so, plainly, every time.
 *
 * The two photographs do NOT have to be taken from the same position. Both
 * are registered independently through their own four tapped corners, so they
 * are compared in the target's own coordinate frame rather than in pixels.
 * That is the whole point of rectifying before differencing — see
 * [TargetRegistration].
 */
class ImportActivity : BaseActivity() {

    private lateinit var binding: ActivityImportBinding

    private var shotBitmap: Bitmap? = null
    private var cleanBitmap: Bitmap? = null
    private var cleanUri: Uri? = null

    private var registration: TargetRegistration? = null
    private var faces: List<TargetFace> = emptyList()
    private var ruleSets: List<RuleSet> = emptyList()

    /** Which picker is running, so one callback can serve both buttons. */
    private var pickingClean = false

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onImagePicked(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImportBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ScoringSession.attach(this)

        runCatching { initScreen() }.onFailure {
            Logger.e("ImportActivity", "Screen init failed", it)
            notifyUser("This screen failed to start: ${it.message}")
        }
        setupBottomNav(R.id.nav_session)
    }

    private fun initScreen() {
        faces = TargetRepository(this).allFaces()
        ruleSets = RuleRepository(this).allSets()

        binding.spRules.adapter = adapter(ruleSets.map { "${it.name}  (${it.governingBody})" })
        binding.spRules.setSelection(
            ruleSets.indexOfFirst { it.id == RuleRepository(this).activeSet().id }.coerceAtLeast(0)
        )
        binding.spRules.onItemSelectedListener = onSelected { i ->
            val r = ruleSets.getOrNull(i) ?: return@onSelected
            RuleRepository(this).setActiveSet(r.id)
            faces.indexOfFirst { it.id == r.targetFaceId }.takeIf { it >= 0 }
                ?.let { binding.spTarget.setSelection(it) }
            binding.etDistance.setText(fmt(UnitsManager.displayDistance(r.distanceM)))
            registration = null      // the gauge changed, so the rectification did too
            refreshStatus()
        }

        binding.spTarget.adapter = adapter(faces.map { it.name + if (it.verified) "" else "  (unverified)" })
        binding.spTarget.setSelection(
            faces.indexOfFirst { it.id == TargetRepository(this).activeFace().id }.coerceAtLeast(0)
        )
        binding.spTarget.onItemSelectedListener = onSelected { i ->
            faces.getOrNull(i)?.let { TargetRepository(this).setActiveFace(it.id) }
            registration = null      // a different face means a different mapping
            binding.overlay.clearCorners()
            refreshStatus()
        }

        binding.lblDistance.text = "Distance (${UnitsManager.distanceUnitLabel()})"
        binding.etDistance.setText(fmt(UnitsManager.displayDistance(ScoringSession.state.distanceM)))

        binding.btnPickShot.setOnClickListener { pickingClean = false; pickImage.launch("image/*") }
        binding.btnPickClean.setOnClickListener { pickingClean = true; pickImage.launch("image/*") }
        binding.btnRegister.setOnClickListener { doRegister() }
        binding.btnUndoCorner.setOnClickListener { binding.overlay.undoCorner() }
        binding.btnDetect.setOnClickListener { doDetect() }
        binding.btnResults.setOnClickListener {
            startActivity(android.content.Intent(this, ResultsActivity::class.java)); finish()
        }
        binding.overlay.onCornersChanged = { refreshStatus() }

        refreshStatus()
    }

    // ------------------------------------------------------------------

    private fun onImagePicked(uri: Uri) {
        val bmp = ImageLoader.load(this, uri)
        if (bmp == null) {
            notifyUser("That image could not be read. Try a JPEG or PNG from the gallery.")
            return
        }
        if (pickingClean) {
            cleanBitmap?.recycle()
            cleanBitmap = bmp
            cleanUri = uri
            notifyUser("Clean target loaded. Register the SHOT target below; the clean one is " +
                "registered from the same corners, so photograph both from roughly the same place.")
        } else {
            shotBitmap?.recycle()
            shotBitmap = bmp
            binding.image.setImageBitmap(bmp)
            // The overlay maps taps through the bitmap's own dimensions and
            // the ImageView's fitCenter letterboxing. Both have to be right or
            // the registration is silently skewed.
            binding.overlay.setSourceGeometry(
                bmp.width, bmp.height, RegistrationOverlayView.SourceFit.FIT_CENTER
            )
            binding.overlay.clearCorners()
            registration = null
        }
        refreshStatus()
    }

    private fun doRegister() {
        if (shotBitmap == null) {
            notifyUser("Choose a photo of the shot target first.")
            return
        }
        val corners = binding.overlay.cornersInSource() ?: run {
            notifyUser("Tap all four corners of the card — ${binding.overlay.cornerCount()} of 4 so far.")
            return
        }
        val face = currentFace()
        val rules = currentRules()
        val reg = TargetRegistration.fromCardCorners(face, corners, rules.gaugeDiameterMm)
        if (reg == null) {
            notifyUser("Those four taps do not form a quadrilateral. Tap the corners in order, going clockwise.")
            return
        }
        registration = reg
        reg.warnings.forEach { Logger.w("ImportActivity", it) }
        if (reg.warnings.isNotEmpty()) notifyUser(reg.warnings.joinToString("\n\n"))
        else notifyUser("Registered. Now detect the hits.")
        refreshStatus()
    }

    private fun doDetect() {
        val reg = registration ?: run { notifyUser("Register the card first."); return }
        val shot = shotBitmap ?: return
        val rules = currentRules()
        val face = currentFace()

        val shotFrame = LumaFrame.fromBitmap(shot)
        val clean = cleanBitmap

        val holes = if (clean != null) {
            // Both images are rectified through the SAME registration, which
            // assumes the two photographs were taken from roughly the same
            // place. That is a real constraint and the picker says so; a
            // second set of taps for the clean image would lift it, at the
            // cost of asking for eight taps instead of four.
            HoleDetector.detectByDifference(
                reg, reg.rectify(LumaFrame.fromBitmap(clean)), reg.rectify(shotFrame),
                rules.gaugeDiameterMm
            )
        } else {
            HoleDetector.detectAbsolute(reg, reg.rectify(shotFrame), rules.gaugeDiameterMm)
        }

        if (holes.isEmpty()) {
            binding.tvResult.text = "No holes were found. Check that the card is registered to its " +
                "corners and that the photograph is sharp enough to resolve a " +
                "${rules.gaugeDiameterMm} mm hole."
            return
        }

        if (binding.cbReplace.isChecked) {
            ScoringSession.startNew(face, rules, distanceFromField())
        } else {
            ScoringSession.setDistance(distanceFromField())
        }

        val bullet = ProfileRepository(this).getBullet()
        // Best shots first, so the shot NUMBERS run inward. A photograph has
        // no firing order — every hole arrived at once as far as the app is
        // concerned — and pretending otherwise by using detection order would
        // put an arbitrary sequence on the report.
        holes.sortedBy { it.distanceFromCentreMm }.forEach { hole ->
            val index = ScoringSession.state.shots.count { !it.sighter } + 1
            val s = ScoringEngine.scoreHole(
                hole, face, rules, bullet,
                index = index,
                series = ScoringEngine.seriesFor(index, rules)
            )
            ScoringSession.addShot(s, rules)
        }

        val res = ScoringSession.result(this)
        val dist = ShotDistribution.of(ScoringSession.state.shots, face, rules)

        binding.hdrDistribution.visibility = View.VISIBLE
        binding.histogram.hideEmptyBuckets = face.zones.isNotEmpty()
        binding.histogram.distribution = dist

        binding.tvResult.text = buildString {
            appendLine("${holes.size} hole(s) found and scored.")
            appendLine()
            appendLine("Total ${res.displayTotal}" + if (res.maxScore > 0) " / ${"%.0f".format(res.maxScore)}" else "")
            if (rules.countInnerTens && res.innerTens > 0) appendLine("Inner tens ${res.innerTens}")
            appendLine(dist.summary())
            if (clean == null) {
                appendLine()
                appendLine("Scored from a single photograph, with no clean reference. That is the " +
                    "weaker mode — check every hole on the Results plot before trusting the total, " +
                    "and delete anything the detector invented.")
            }
            val doubtful = ScoringSession.state.shots.count { it.confidence < 0.4 }
            if (doubtful > 0) appendLine("$doubtful hole(s) were detected with low confidence.")
        }
        refreshStatus()
    }

    // ------------------------------------------------------------------

    private fun distanceFromField(): Double =
        binding.etDistance.text.toString().toDoubleOrNull()
            ?.let { UnitsManager.inputDistanceToMeters(it) }
            ?: currentRules().distanceM

    private fun currentFace(): TargetFace =
        faces.getOrNull(binding.spTarget.selectedItemPosition) ?: TargetRepository(this).activeFace()

    private fun currentRules(): RuleSet =
        ruleSets.getOrNull(binding.spRules.selectedItemPosition) ?: RuleRepository(this).activeSet()

    private fun refreshStatus() {
        binding.tvStatus.text = buildString {
            append(if (shotBitmap == null) "No target photo" else "Target photo loaded")
            append("  |  ")
            append(if (cleanBitmap == null) "no clean reference (weaker mode)" else "clean reference loaded")
            append("  |  ")
            append(if (registration == null) "not registered" else "registered")
            val n = binding.overlay.cornerCount()
            if (registration == null && n in 1..3) append("\n$n of 4 corners tapped")
        }
    }

    private fun adapter(items: List<String>) =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, items).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    private fun onSelected(block: (Int) -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) = block(position)
        override fun onNothingSelected(p: AdapterView<*>?) = Unit
    }

    private fun fmt(v: Double) = if (v == Math.floor(v)) "%.0f".format(v) else "%.1f".format(v)

    override fun swipeExemptViews(): List<View> =
        listOf(binding.overlay, binding.spRules, binding.spTarget, binding.histogram)

    override fun onDestroy() {
        super.onDestroy()
        // These are multi-megabyte allocations and the activity can be
        // recreated on a theme change; leaking one is a real problem on a
        // phone that is also running a camera.
        binding.image.setImageDrawable(null)
        shotBitmap?.recycle(); shotBitmap = null
        cleanBitmap?.recycle(); cleanBitmap = null
    }
}
