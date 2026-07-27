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
    private var boxMeaning = TargetRegistration.BoxMeaning.OUTER_SCORING_RING
    private var markEllipticity = 1.0
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
        binding.btnAutoDetect.setOnClickListener { doAutoDetect() }
        binding.btnRegister.setOnClickListener { doRegister() }
        binding.btnUndoCorner.setOnClickListener {
            binding.overlay.clearAll(); registration = null; refreshStatus()
        }
        binding.cbCornerMode.setOnCheckedChangeListener { _, corners ->
            binding.overlay.mode =
                if (corners) RegistrationOverlayView.Mode.CORNERS
                else RegistrationOverlayView.Mode.BOX
            binding.overlay.clearAll()
            binding.btnAutoDetect.isEnabled = !corners
            setTransformControlsEnabled(!corners)
            registration = null
            refreshStatus()
        }
        binding.btnDetect.setOnClickListener { doDetect() }
        binding.btnResults.setOnClickListener {
            startActivity(android.content.Intent(this, ResultsActivity::class.java)); finish()
        }
        binding.overlay.onCornersChanged = { refreshStatus() }
        wireTransformControls()

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
            binding.overlay.clearAll()
            registration = null
            // Detection is cheap and almost always right, so run it the
            // moment a photograph arrives rather than making the user ask.
            // A box they merely have to check is a different task from a box
            // they have to place.
            doAutoDetect(silent = true)
        }
        refreshStatus()
    }

    /**
     * Finds the aiming mark and places the registration box around it.
     *
     * [silent] suppresses the "nothing found" complaint for the automatic
     * run on load: a face with no black mark at all is a perfectly ordinary
     * thing to open, and being told off for it would be noise.
     */
    private fun doAutoDetect(silent: Boolean = false) {
        val bmp = shotBitmap ?: run {
            if (!silent) notifyUser("Choose a photo of the shot target first.")
            return
        }
        if (binding.cbCornerMode.isChecked) return

        val face = currentFace()
        val frame = LumaFrame.fromBitmap(bmp)
        val disc = BlackMarkDetector.detect(frame)

        if (disc == null) {
            binding.overlay.setDefaultBox()
            boxMeaning = TargetRegistration.BoxMeaning.OUTER_SCORING_RING
            markEllipticity = 1.0
            suggestedTilt = BoxTransform.NONE
            applyTransform(BoxTransform.NONE)
            if (!silent) {
                notifyUser(
                    "No black aiming mark could be found, so a box has been placed in the middle " +
                        "for you to drag onto the scoring area."
                )
            }
            refreshStatus()
            return
        }

        markEllipticity = disc.ellipticity

        // Does the picture actually look like the face that is selected? A
        // wrong face does not fail — it registers the wrong circle and
        // produces a complete, confident, wrong score sheet. See
        // TargetGeometryCheck for the failure this was written for.
        val geometry = TargetGeometryCheck.analyse(
            frame, disc, face, com.rfsat.sts.targets.TargetRepository(this).allFaces()
        )

        val (box, meaning) = BlackMarkDetector.boxFor(disc, face, bmp.width, bmp.height)
        boxMeaning = meaning
        binding.overlay.setBoxInSource(box[0], box[1], box[2], box[3])
        binding.overlay.detectedMarkers =
            listOf(Triple(disc.centreXPx.toFloat(), disc.centreYPx.toFloat(), disc.radiusPx.toFloat()))
        // NOT applied automatically, and this is the correction that
        // mattered most in the field. The tilt is inferred from how
        // elliptical the aiming mark measures, a shot-up mark measures a few
        // percent elliptical from segmentation noise alone, and acos turns
        // three percent into fourteen degrees. Users were opening a
        // photograph of a square-on target and being shown a visibly skewed
        // box. On top of that the SIGN is a guess, so half of those skews
        // pointed the wrong way. The estimate is worth offering and is not
        // worth imposing.
        suggestedTilt = BlackMarkDetector.suggestedTransform(disc)
        applyTransform(BoxTransform.NONE)
        binding.btnApplyTilt.isEnabled = !suggestedTilt.isIdentity

        if (geometry.looksWrong) {
            // Louder than the tilt advice and shown first, because a wrong
            // face makes every other consideration irrelevant.
            notifyUser(geometry.warning ?: "")
        } else if (BlackMarkDetector.looksOblique(disc)) {
            notifyUser(
                ("The aiming mark is %.2f times longer one way than the other, which suggests about " +
                    "%.0f° of tilt. Nothing has been changed — tap “Apply estimated tilt” to try it, " +
                    "and check the dashed outline against the rings.")
                    .format(disc.ellipticity, kotlin.math.hypot(suggestedTilt.tiltXDeg, suggestedTilt.tiltYDeg))
            )
        } else if (!silent) {
            notifyUser("Found the target. Check the box, adjust the handles if needed, then Register.")
        }
        refreshStatus()
    }

    private fun doRegister() {
        if (shotBitmap == null) {
            notifyUser("Choose a photo of the shot target first.")
            return
        }
        val face = currentFace()
        val rules = currentRules()

        val reg = if (binding.cbCornerMode.isChecked) {
            val corners = binding.overlay.cornersInSource() ?: run {
                notifyUser("Tap all four corners of the card — ${binding.overlay.cornerCount()} of 4 so far.")
                return
            }
            TargetRegistration.fromCardCorners(face, corners, rules.gaugeDiameterMm)
                ?: run {
                    notifyUser("Those four taps do not form a quadrilateral. Tap the corners in order, going clockwise.")
                    return
                }
        } else {
            val box = binding.overlay.boxInSource() ?: run {
                notifyUser("Tap “Auto-detect the target” first, or drag a box around the scoring area.")
                return
            }
            TargetRegistration.fromBoundingBox(
                face, box, boxMeaning, rules.gaugeDiameterMm, markEllipticity, transform
            ) ?: run {
                notifyUser(
                    "This face has no ${boxMeaning.label.lowercase()} for a box to measure. " +
                        "Use corner registration instead."
                )
                return
            }
        }
        registration = reg
        Logger.i(
            "ImportActivity",
            "registered %s: face '%s', rules '%s', gauge %.2f mm, %s".format(
                if (binding.cbCornerMode.isChecked) "by card corners" else "by box",
                face.name, rules.name, rules.gaugeDiameterMm, transform.summary()
            )
        )
        reg.warnings.forEach { Logger.w("Registration", it) }
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
            if (registration == null) {
                if (binding.cbCornerMode.isChecked) {
                    val n = binding.overlay.cornerCount()
                    if (n in 1..3) append("\n$n of 4 corners tapped")
                } else if (binding.overlay.hasBox()) {
                    append("\nbox around: ${boxMeaning.label.lowercase()}, ${transform.summary()}")
                }
            }
        }
    }


    // ---- tilt and rotation -------------------------------------------
    //
    // Three sliders, laid out the way a phone camera app lays them out,
    // because that is where everyone has already learnt what they do. They
    // take the box registration from four degrees of freedom to seven, which
    // is every one a flat target photographed through a normal lens needs.

    private var transform = BoxTransform.NONE

    /** What the detector thinks the tilt is. Offered, never applied on its
     *  own — see the note where it is set. */
    private var suggestedTilt = BoxTransform.NONE

    private fun wireTransformControls() {
        binding.sbRotation.setOnSeekBarChangeListener(seekListener { p ->
            transform = transform.withRotation(p / 2.0 - BoxTransform.MAX_ROTATION_DEG)
            onTransformChanged()
        })
        binding.sbTiltX.setOnSeekBarChangeListener(seekListener { p ->
            transform = transform.withTiltX(p / 2.0 - BoxTransform.MAX_TILT_DEG)
            onTransformChanged()
        })
        binding.sbTiltY.setOnSeekBarChangeListener(seekListener { p ->
            transform = transform.withTiltY(p / 2.0 - BoxTransform.MAX_TILT_DEG)
            onTransformChanged()
        })
        binding.btnResetTilt.setOnClickListener { applyTransform(BoxTransform.NONE) }
        binding.btnApplyTilt.setOnClickListener {
            if (suggestedTilt.isIdentity) {
                notifyUser("The aiming mark looks round, so there is no tilt to apply.")
            } else {
                applyTransform(suggestedTilt)
                notifyUser(
                    "Applied the estimated tilt. If the dashed outline now fits the rings worse, " +
                        "drag a tilt slider the other way — the mark cannot say which way it leans."
                )
            }
        }
        binding.btnApplyTilt.isEnabled = false
        applyTransform(transform)
    }

    /** Pushes a transform into both the sliders and the overlay. Setting the
     *  slider positions re-enters the listeners, which is harmless because
     *  the value they compute is the one just set — but it does mean this is
     *  the only place allowed to move them programmatically. */
    private fun applyTransform(t: BoxTransform) {
        transform = t
        binding.sbRotation.progress = ((t.rotationDeg + BoxTransform.MAX_ROTATION_DEG) * 2).toInt()
        binding.sbTiltX.progress = ((t.tiltXDeg + BoxTransform.MAX_TILT_DEG) * 2).toInt()
        binding.sbTiltY.progress = ((t.tiltYDeg + BoxTransform.MAX_TILT_DEG) * 2).toInt()
        onTransformChanged()
    }

    private fun onTransformChanged() {
        binding.overlay.transform = transform
        binding.lblRotation.text = "Rotation  %+.1f°".format(transform.rotationDeg)
        binding.lblTiltX.text = "Horizontal tilt  %+.1f°".format(transform.tiltXDeg)
        binding.lblTiltY.text = "Vertical tilt  %+.1f°".format(transform.tiltYDeg)
        // The registration was built from the old shape, so it is stale now.
        registration = null
        refreshStatus()
    }

    private fun seekListener(onValue: (Int) -> Unit) =
        object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) =
                onValue(progress)
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) = Unit
        }

    private fun setTransformControlsEnabled(enabled: Boolean) {
        binding.sbRotation.isEnabled = enabled
        binding.sbTiltX.isEnabled = enabled
        binding.sbTiltY.isEnabled = enabled
        binding.btnResetTilt.isEnabled = enabled
        binding.btnApplyTilt.isEnabled = enabled && !suggestedTilt.isIdentity
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

    override fun swipeExemptViews(): List<View> = listOf(
        binding.overlay, binding.spRules, binding.spTarget, binding.histogram,
        binding.sbRotation, binding.sbTiltX, binding.sbTiltY
    )

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
