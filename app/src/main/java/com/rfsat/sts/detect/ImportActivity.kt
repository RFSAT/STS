package com.rfsat.sts.detect

import android.graphics.Bitmap
import com.rfsat.sts.cloud.SecondOpinion
import com.rfsat.sts.cloud.ScoringSource
import com.rfsat.sts.cloud.CloudSettings
import android.util.Base64
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
import com.rfsat.sts.scoring.ScoredPhoto
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
/** Hands a file to the import screen: the frame taken out of a downloaded
 *  clip, or any other picture another screen has produced. */
const val IMPORT_EXTRA_IMAGE_PATH = "image_path"

class ImportActivity : BaseActivity() {

    private lateinit var binding: ActivityImportBinding

    private var shotBitmap: Bitmap? = null
    /**
     * The picture as it arrived, kept so an aspect correction is always
     * applied to the ORIGINAL. Stretching an already-stretched copy resamples
     * twice and loses a little of the card each time, and a shooter adjusting
     * a percentage up and down to find the right one would do it repeatedly.
     */
    private var sourceShotBitmap: Bitmap? = null
    private var aspectX = 1.0
    private var aspectY = 1.0
    private var lensK = 0.0
    private var cleanBitmap: Bitmap? = null
    private var cleanUri: Uri? = null
    private var shotUri: Uri? = null

    private var registration: TargetRegistration? = null

    /** Last ring fit and aiming-mark radius measured from the shot photo, in
     *  SOURCE pixels. Cached so that registering by hand can check the
     *  selected face without repeating a second's work. */
    private var lastFit: RingFit? = null
    private var lastMarkRadiusPx = 0.0
    /**
     * Positions this screen selected programmatically, so the callback the
     * Spinner posts for them can be told apart from a real user choice.
     *
     * THIS WAS THE BUG BEHIND THREE SEPARATE COMPLAINTS. A Spinner delivers
     * its first onItemSelected on the layout pass AFTER onCreate, which is
     * after the listener has been attached — so merely OPENING this screen
     * ran the rules listener, which forces the target face to the rule set's
     * default. Choose a custom target, leave the screen, come back, and it
     * had silently reverted to ISSF 10 m Air Rifle. Results then showed that
     * face, and detection ran against it, which is why a card that scores
     * correctly in testing produced no hits at all on the phone.
     */
    private var pendingRulesSelection = -1
    private var pendingTargetSelection = -1

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
        ScoringSession.adoptSelectionIfEmpty(this)

        runCatching { initScreen() }.onFailure {
            Logger.e("ImportActivity", "Screen init failed", it)
            notifyUser("This screen failed to start: ${it.message}")
        }
        setupBottomNav(R.id.nav_session)
        // A picture handed over by another screen — the frame taken out of a
        // clip downloaded from the camera, for instance. Loaded exactly as a
        // gallery pick is, so everything downstream is the ordinary path.
        intent?.getStringExtra(IMPORT_EXTRA_IMAGE_PATH)?.let { path ->
            runCatching { onImagePicked(android.net.Uri.fromFile(java.io.File(path))) }
                .onFailure {
                    Logger.e("ImportActivity", "handed-over image failed to load", it)
                }
        }
    }

    private fun initScreen() {
        faces = TargetRepository(this).allFaces()
        ruleSets = RuleRepository(this).allSets()

        // Both spinners are populated and pointed at the stored selection
        // BEFORE their listeners can do anything, and the position we set is
        // remembered so the callback the Spinner posts for it is recognised
        // as ours rather than treated as a user choice.
        val ruleIndex = ruleSets.indexOfFirst { it.id == RuleRepository(this).activeSet().id }
            .coerceAtLeast(0)
        val faceIndex = faces.indexOfFirst { it.id == TargetRepository(this).activeFace().id }
            .coerceAtLeast(0)

        binding.spRules.adapter = adapter(ruleSets.map { "${it.name}  (${it.governingBody})" })
        pendingRulesSelection = ruleIndex
        binding.spRules.setSelection(ruleIndex)
        binding.spRules.onItemSelectedListener = onSelected { i ->
            if (i == pendingRulesSelection) { pendingRulesSelection = -1; return@onSelected }
            val r = ruleSets.getOrNull(i) ?: return@onSelected
            RuleRepository(this).setActiveSet(r.id)
            // A course of fire implies its face, so changing the rules moves
            // the target with it — but only when the user actually changed
            // them, never on the way in.
            faces.indexOfFirst { it.id == r.targetFaceId }.takeIf { it >= 0 }?.let { idx ->
                pendingTargetSelection = idx
                binding.spTarget.setSelection(idx)
                TargetRepository(this).setActiveFace(faces[idx].id)
                notifyUser("Target face switched to ${faces[idx].name} to match the rules.")
            }
            binding.etDistance.setText(fmt(UnitsManager.displayDistance(r.distanceM)))
            registration = null      // the gauge changed, so the rectification did too
            refreshStatus()
        }

        // A picture of each face, because this is the spinner where the
        // choice is actually made.
        binding.spTarget.adapter = com.rfsat.sts.ui.TargetSpinnerAdapter(this, faces)
        pendingTargetSelection = faceIndex
        binding.spTarget.setSelection(faceIndex)
        binding.spTarget.onItemSelectedListener = onSelected { i ->
            if (i == pendingTargetSelection) { pendingTargetSelection = -1; return@onSelected }
            faces.getOrNull(i)?.let { TargetRepository(this).setActiveFace(it.id) }
            registration = null      // a different face means a different mapping
            binding.overlay.clearCorners()
            refreshStatus()
        }

        binding.lblDistance.text = "Distance (${UnitsManager.distanceUnitLabel()})"
        binding.etDistance.setText(fmt(UnitsManager.displayDistance(ScoringSession.state.distanceM)))

        binding.btnPickShot.setOnClickListener { pickingClean = false; pickImage.launch("image/*") }
        binding.btnPickClean.setOnClickListener { pickingClean = true; pickImage.launch("image/*") }
        binding.btnIdentify.setOnClickListener { doIdentifyTarget() }
        binding.btnAutoDetect.setOnClickListener { doAutoDetect() }
        binding.btnRegister.setOnClickListener { doRegister() }
        binding.btnUndoCorner.setOnClickListener {
            binding.overlay.clearAll(); registration = null; lastFit = null; lastMarkRadiusPx = 0.0; refreshStatus()
        }
        binding.cbCornerMode.setOnCheckedChangeListener { _, corners ->
            binding.overlay.mode =
                if (corners) RegistrationOverlayView.Mode.CORNERS
                else RegistrationOverlayView.Mode.BOX
            binding.overlay.clearAll()
            binding.btnAutoDetect.isEnabled = !corners
            binding.btnIdentify.isEnabled = !corners
            setTransformControlsEnabled(!corners)
            registration = null
            refreshStatus()
        }
        binding.btnDetect.setOnClickListener { doDetect() }
        binding.btnClearShots.setOnClickListener {
            ScoringSession.clearShots()
            refreshAfterClear()
            notifyUser("All recorded shots cleared.")
        }
        binding.btnResults.setOnClickListener {
            startActivity(android.content.Intent(this, ResultsActivity::class.java)); finish()
        }
        binding.overlay.onCornersChanged = { refreshStatus() }
        binding.btnAspectApply.setOnClickListener { applyAspect() }
        binding.btnLensApply.setOnClickListener { applyLens() }
        moreInfo(binding.infoLens, "Lens distortion",
            "Everything the app measures geometrically assumes a pinhole camera, in which a " +
            "straight line stays straight. A short-focus action camera is not one: near the " +
            "edges of the frame a wide lens pulls the picture inward, so a ring that should sit " +
            "at 40 mm measures short, and the error grows with the square of the distance from " +
            "the centre.\n\n" +
            "No action camera publishes the figure — Tactacam's specification lists zoom, " +
            "resolution and battery life and no optics at all — and one number would not do " +
            "anyway, because a zoom lens distorts differently at each focal length. So it is " +
            "measured from your own card: the printed rings are concentric circles at equal " +
            "spacing, so their radii should be evenly spaced in pixels, and the departure from " +
            "that is the distortion. No calibration target, no calibration session.\n\n" +
            "It matters at close range and hardly at all down a range. Filling the frame with a " +
            "card at arm's length is the case that needs it; the camera's zoom, used from " +
            "further back, mostly avoids it.")
        binding.btnAspectReset.setOnClickListener { resetAspect() }
        moreInfo(binding.infoAspect, "Stretching the picture",
            "The scale, the gauge, the ring radii and every hole-size gate are written in terms " +
            "of ONE millimetres-per-pixel number. Reshaping the drawn circles instead would " +
            "leave an elliptical geometry in the pipeline and give every one of those stages a " +
            "second number to know about. Stretching the picture means the rings are round " +
            "before any of it runs.\n\n" +
            "It can only correct a distortion along the picture's own axes — a lens, a sensor " +
            "with non-square pixels, a photograph resized unevenly. A card photographed from " +
            "one side is foreshortened along some other axis; that needs the tilt controls or " +
            "corner registration, and no stretch will fix it. The app offers a figure only when " +
            "the rings measure out of round along the width or the height, and never applies " +
            "one by itself: a good photograph of a flat card measures one or two per cent out " +
            "on noise alone.")
        wireTransformControls()

        restoreLastImage()
        refreshStatus()
    }

    /**
     * Puts the last photo scored back on screen when this page reopens.
     *
     * Opening to a black rectangle gives no hint that anything was ever done
     * here, and someone checking a score they have just taken has to find the
     * same file in the gallery again. Only the URI is stored, so nothing is
     * duplicated and no space is used.
     */
    private fun restoreLastImage() {
        if (shotBitmap != null) return
        val stored = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_LAST_IMAGE, null)
            ?: return
        val uri = runCatching { Uri.parse(stored) }.getOrNull() ?: return
        // The permission behind a gallery URI does not always outlive the
        // process, and the file itself may simply be gone. Either way this is
        // a convenience, so it fails quietly and leaves an empty screen.
        val bmp = runCatching { ImageLoader.load(this, uri) }.getOrNull()
        if (bmp == null) {
            Logger.i("ImportActivity", "the last image could not be reopened; starting empty")
            return
        }
        shotBitmap = bmp
        sourceShotBitmap = bmp
        aspectX = 1.0; aspectY = 1.0
        shotUri = uri
        binding.image.setImageBitmap(bmp)
        binding.overlay.setSourceGeometry(
            bmp.width, bmp.height, RegistrationOverlayView.SourceFit.FIT_CENTER
        )
        binding.overlay.clearAll()
        registration = null
        notifyUser(
            "Showing the last photo you scored. Pick another to replace it, or register and " +
                "score this one again."
        )
    }

    private fun moreInfo(view: android.widget.TextView, title: String, body: String) {
        view.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(body)
                .setPositiveButton("Close", null)
                .show()
        }
    }

    // ------------------------------------------------------------------
    //  Image aspect
    // ------------------------------------------------------------------

    /**
     * Offers a stretch when the fitted rings say one would help.
     *
     * Filled in, not applied. Called from both registration routes, because
     * both fit the rings and the shooter should not have to run a particular
     * one of them to be told the picture is squashed.
     */
    private fun offerAspect(fit: RingFit) {
        val s = AspectCorrection.suggest(fit.axisRatio, fit.orientationDeg)
        if (s == null) {
            binding.tvAspect.text =
                if (fit.axisRatio >= 1.0 - AspectCorrection.NOISE_FLOOR)
                    "The rings measure round; no stretch needed."
                else
                    ("The rings measure %.0f%% out of round, but along a diagonal — that is a " +
                        "tilt rather than a stretch. Use the tilt controls or corner " +
                        "registration.").format(100 * (1 - fit.axisRatio))
            return
        }
        binding.etAspectX.setText("%.1f".format(s.percentX))
        binding.etAspectY.setText("%.1f".format(s.percentY))
        binding.tvAspect.text =
            ("The rings measure %.0f%% out of round along the picture's own axes. Width %.1f%%, " +
                "height %.1f%% would make them round — press Apply to try it.")
                .format(100 * s.outOfRoundFraction, s.percentX, s.percentY)
        notifyUser(
            ("The printed rings measure %.0f%% out of round. Under “Image aspect” there is now a " +
                "stretch that would correct it — nothing has been applied.")
                .format(100 * s.outOfRoundFraction)
        )
    }

    /**
     * Rescales the picture and starts the registration again from scratch.
     *
     * EVERYTHING measured from the old pixels is dropped, not adjusted: the
     * box, the ring fit, the mark radius and the registration itself are all
     * in units of a picture that no longer exists. Carrying any of them over
     * would put the scoring geometry a few per cent out with nothing on
     * screen to show it.
     */
    private fun applyAspect() {
        val src = sourceShotBitmap ?: shotBitmap ?: run {
            notifyUser("Choose a photo first."); return
        }
        val sx = AspectCorrection.parsePercent(binding.etAspectX.text.toString())
        val sy = AspectCorrection.parsePercent(binding.etAspectY.text.toString())
        if (sx == null || sy == null) {
            notifyUser(
                "Both figures must be percentages between %.0f and %.0f."
                    .format(100.0 / AspectCorrection.MAX_STRETCH, 100.0 * AspectCorrection.MAX_STRETCH)
            )
            return
        }
        if (!AspectCorrection.worthApplying(sx, sy)) {
            resetAspect(); return
        }
        val w = (src.width * sx).toInt().coerceAtLeast(1)
        val h = (src.height * sy).toInt().coerceAtLeast(1)
        val scaled = runCatching { Bitmap.createScaledBitmap(src, w, h, true) }.getOrNull() ?: run {
            notifyUser("The picture could not be resized — it may be too large for this device.")
            return
        }
        aspectX = sx; aspectY = sy
        useBitmap(scaled)
        binding.tvAspect.text =
            "Stretched to width %.1f%%, height %.1f%% of the original.".format(sx * 100, sy * 100)
        notifyUser(
            "Picture stretched to %.1f%% by %.1f%%. Registering again from the new pixels."
                .format(sx * 100, sy * 100)
        )
        doIdentifyTarget(silent = true)
    }

    /**
     * Straightens the picture with the coefficient in the box, then starts
     * the registration again.
     *
     * Applied to the ORIGINAL together with any aspect stretch, in that
     * order: the lens acted on the light before anything else did, so its
     * correction belongs first, and applying it to an already-stretched copy
     * would be correcting a radial error that is no longer radial.
     */
    private fun applyLens() {
        val src = sourceShotBitmap ?: run { notifyUser("Choose a photo first."); return }
        val k = LensDistortion.parse(binding.etLensK.text.toString())
        if (k == null) {
            notifyUser(
                "The coefficient must be a number between %.2f and %.2f. Negative straightens a " +
                    "barrel-shaped bulge, which is what a wide lens gives."
                        .format(-LensDistortion.MAX_K, LensDistortion.MAX_K)
            )
            return
        }
        lensK = k
        val corrected = runCatching { LensCorrection.apply(src, k) }.getOrNull() ?: run {
            notifyUser("The picture could not be corrected — it may be too large for this device.")
            return
        }
        val stretched = if (AspectCorrection.worthApplying(aspectX, aspectY)) {
            val w = (corrected.width * aspectX).toInt().coerceAtLeast(1)
            val h = (corrected.height * aspectY).toInt().coerceAtLeast(1)
            runCatching { Bitmap.createScaledBitmap(corrected, w, h, true) }.getOrNull() ?: corrected
        } else corrected
        useBitmap(stretched)
        binding.tvLens.text =
            if (LensDistortion.worthApplying(k)) "Corrected with k = %.3f.".format(k)
            else "No lens correction applied."
        notifyUser("Lens correction applied. Registering again from the corrected picture.")
        doIdentifyTarget(silent = true)
    }

    /**
     * Offers a coefficient measured from the ring family that was just
     * fitted. Filled in, never applied: the same rule as the tilt and the
     * aspect, and for the same reason — a fit has a residual of its own and
     * imposing a correction derived from it would resample a picture that
     * was already straight.
     */
    private fun offerLens(fit: RingFit) {
        if (lensK != 0.0) return          // already corrected; do not re-measure the result
        val frame = shotBitmap ?: return
        val norm = Math.hypot((frame.width - 1) / 2.0, (frame.height - 1) / 2.0)
        // The fit's radii are in the frame it was measured in, which is the
        // detection frame rather than the photograph; the ratio of the two
        // cancels because the coefficient is normalised by the same length.
        val fitNorm = fit.correctedFrame
            ?.let { Math.hypot(it.frame.width / 2.0, it.frame.height / 2.0) } ?: norm
        val k = LensDistortion.estimate(fit.ringsPx, fitNorm)
        if (k == null) {
            binding.tvLens.text = "The rings are evenly spaced; no lens correction needed."
            return
        }
        binding.etLensK.setText("%.3f".format(k))
        binding.tvLens.text =
            ("The ring spacing is uneven in the way a %s lens makes it. k = %.3f would " +
                "straighten it — press Apply to try.")
                .format(if (k < 0) "barrel-distorting" else "pincushion", k)
        notifyUser(
            ("The printed rings are not evenly spaced across the picture, which is what a wide " +
                "lens does at close range. A correction of %.3f is offered under “Lens " +
                "distortion” — nothing has been applied.").format(k)
        )
    }

    private fun resetAspect() {
        val src = sourceShotBitmap ?: run { notifyUser("Choose a photo first."); return }
        if (aspectX == 1.0 && aspectY == 1.0) {
            notifyUser("The picture is already the original.")
            return
        }
        aspectX = 1.0; aspectY = 1.0
        lensK = 0.0
        binding.etLensK.setText("0")
        binding.tvLens.text = ""
        binding.etAspectX.setText("100")
        binding.etAspectY.setText("100")
        useBitmap(src)
        binding.tvAspect.text = "Original picture."
        notifyUser("Back to the original picture. Registering it again.")
        doIdentifyTarget(silent = true)
    }

    /**
     * Puts a bitmap on screen as the one being worked on, and throws away
     * everything measured from the previous one.
     */
    private fun useBitmap(bmp: Bitmap) {
        if (shotBitmap !== bmp && shotBitmap !== sourceShotBitmap) shotBitmap?.recycle()
        shotBitmap = bmp
        binding.image.setImageBitmap(bmp)
        binding.overlay.setSourceGeometry(
            bmp.width, bmp.height, RegistrationOverlayView.SourceFit.FIT_CENTER
        )
        binding.overlay.clearAll()
        registration = null
        lastFit = null
        lastMarkRadiusPx = 0.0
        refreshStatus()
    }

    private fun rememberLastImage(uri: Uri) {
        runCatching {
            // Ask for durable read access where the picker can grant it, so
            // the photo survives a reboot rather than only this session.
            contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_LAST_IMAGE, uri.toString()).apply()
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
            if (shotBitmap !== sourceShotBitmap) shotBitmap?.recycle()
            sourceShotBitmap?.recycle()
            shotBitmap = bmp
            sourceShotBitmap = bmp
            aspectX = 1.0; aspectY = 1.0
            binding.etAspectX.setText("100")
            binding.etAspectY.setText("100")
            binding.tvAspect.text = ""
            shotUri = uri
            rememberLastImage(uri)
            binding.image.setImageBitmap(bmp)
            // The overlay maps taps through the bitmap's own dimensions and
            // the ImageView's fitCenter letterboxing. Both have to be right or
            // the registration is silently skewed.
            binding.overlay.setSourceGeometry(
                bmp.width, bmp.height, RegistrationOverlayView.SourceFit.FIT_CENTER
            )
            binding.overlay.clearAll()
            registration = null
            // IDENTIFY, not merely detect.
            //
            // This ran the aiming-mark path on load, which takes the scale
            // from the black mark multiplied by the ratio of whichever face
            // happens to be SELECTED — so it is only as right as that
            // selection. Fitting the ring family instead measures the pitch
            // over five to nine rings, says which face it is rather than
            // assuming, and cross-checks the two against each other.
            //
            // It falls back to the mark on its own when the rings cannot be
            // fitted, or when the face has no even pitch to fit, so the
            // weaker method is still there — as a fallback rather than as the
            // default, which is the right way round.
            doIdentifyTarget(silent = true)
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

        // ---- THE RINGS, FOR THE FACE ALREADY SELECTED ----
        //
        // This route does NOT identify the face and does not need to: the
        // shooter chose one above, and searching the catalogue for a better
        // fit is what the other button is for. What it was also not doing was
        // fitting the rings AT ALL, so it drew one circle round the aiming
        // mark, derived its box from that alone, and left the shooter with no
        // way to see whether the scoring geometry lined up with the printing.
        // The same photograph then gave two different boxes depending on
        // which button was pressed.
        //
        // The fit is seeded from the mark that has just been found, so it
        // costs one pass and cannot wander off to some other circular thing
        // in the picture.
        val fit = runCatching {
            RingFinder.find(
                LumaFrame.fromBitmapForDetection(bmp),
                seedX = disc.centreXPx, seedY = disc.centreYPx
            )
        }.getOrNull()
        if (fit != null) {
            lastFit = fit
            lastMarkRadiusPx = disc.radiusPx
            if (showRingFamily(fit, face)) {
                boxMeaning = TargetRegistration.BoxMeaning.OUTER_SCORING_RING
            }
            offerAspect(fit)
            offerLens(fit)
        }
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
            notifyUser(buildString {
                append("Found the target")
                if (lastFit != null) append(" and fitted ${lastFit?.ringCount ?: 0} rings")
                append(", taking ${face.name} as selected — this route does not identify the face. ")
                append("Check the box, adjust the handles if needed, then Register.")
            })
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

        // THE FACE, BEFORE ANYTHING ELSE. A box in the right place on the
        // wrong face scores everything wrongly and looks completely normal
        // while doing it — and far more often finds nothing at all, because
        // the scale is wrong and every hole then fails the detector's size
        // gates. This is the single largest cause of "detection does not
        // work" and it is not a detector fault.
        //
        // The one-button route already identifies the face from the picture.
        // Registering by hand did not check it at all, which is exactly why
        // the two routes gave such different results.
        runCatching { measureGeometryIfNeeded() }
        lastFit?.let { f ->
            val all = TargetRepository(this).allFaces()

            // Two checks, because they fail on different things.
            //
            // The ratio test is scale-free — black width in ring widths — so
            // it works with no idea how big the card is, and it catches the
            // gross mismatch that finds NOTHING: a 10 m air rifle face on a
            // 50 yd smallbore card is 32 per cent out on this ratio and
            // detects zero holes out of five.
            //
            // What it cannot do is separate two faces of similar PROPORTIONS
            // at different sizes. ISSF 25/50 m Precision Pistol is within 7
            // per cent of both cards tested here and sails through, then
            // finds 22 holes where there are 4, because its ring pitch is
            // three times larger and the size gates open far too wide.
            // Ranking every face by its fitted pitch does separate them —
            // measured on those cards, the right face scored 0.12 per cent
            // and Precision Pistol 7.44 — so both run.
            TargetGeometryCheck.faceMismatch(face, lastMarkRadiusPx, f.pitchPx, all)
                ?.let { problem ->
                    Logger.w("Registration", problem)
                    notifyUser(problem)
                }

            if (lastMarkRadiusPx > 0.0) {
                val ranked = RingFinder.identify(f, lastMarkRadiusPx, all, distanceFromField(), face.id)
                val best = ranked.firstOrNull()
                val mine = ranked.firstOrNull { it.face.id == face.id }
                if (best != null && best.face.id != face.id &&
                    best.relativeError < IDENTIFY_TOLERANCE &&
                    (mine == null || mine.relativeError > best.relativeError * 3)
                ) {
                    val problem = ("The printed rings match %s to %.0f%%, but %s is selected%s. " +
                        TargetGeometryCheck.WRONG_FACE_COST)
                        .format(
                            best.face.name, 100 * (1 - best.relativeError), face.name,
                            mine?.let { " and only fits to %.0f%%".format(100 * (1 - it.relativeError)) }
                                ?: " and does not fit these proportions"
                        )
                    Logger.w("Registration", problem)
                    notifyUser(problem)
                }
            }
        }

        runCatching {
            shotBitmap?.let { LumaFrame.fromBitmap(it) }?.let { f ->
                TargetGeometryCheck.verifyRings(f, reg, face)?.let { problem ->
                    Logger.w("Registration", problem)
                    notifyUser(problem)
                }
            }
        }
        reg.warnings.forEach { Logger.w("ImportActivity", it) }
        if (reg.warnings.isNotEmpty()) notifyUser(reg.warnings.joinToString("\n\n"))
        else notifyUser("Registered. Now detect the hits.")
        refreshStatus()
    }

    /**
     * Measures the ring pitch and the aiming mark from the shot photo if that
     * has not already been done. Costs about a second, once per photograph.
     */
    private fun measureGeometryIfNeeded() {
        if (lastFit != null) return
        val bmp = shotBitmap ?: return
        val frame = LumaFrame.fromBitmapForDetection(bmp)
        lastFit = RingFinder.find(frame)
        if (lastMarkRadiusPx <= 0.0) {
            val cx = lastFit?.let { fit ->
                fit.correctedFrame?.toSource(fit.centreXPx, fit.centreYPx)?.first ?: fit.centreXPx
            } ?: (frame.width / 2.0)
            val cy = lastFit?.let { fit ->
                fit.correctedFrame?.toSource(fit.centreXPx, fit.centreYPx)?.second ?: fit.centreYPx
            } ?: (frame.height / 2.0)
            lastMarkRadiusPx = MarkOutline.extract(frame, cx, cy)
                ?.let { RingShapeSelector.choose(it)?.model?.semiMajorPx } ?: 0.0
        }
    }


    /**
     * Sends the rectified card to the chosen AI service and takes its answer
     * whole. WHICH service is the import setting's, not the second opinion's.
     *
     * The RECTIFIED copy, not the original, and that is what makes the marks
     * land where the shooter sees them: it is already on the millimetre grid
     * the plot draws in, so a fraction of the image maps back with one linear
     * step and no projection in between.
     */
    private fun scoreWithCloud(reg: TargetRegistration, shot: Bitmap) {
        val rect = runCatching { reg.rectifyColour(shot) }.getOrNull()
        if (rect == null) {
            notifyUser("The card could not be flattened for scoring; falling back to the app's own detection.")
            return
        }
        ScoredPhoto.set(this, rect, reg.uMinMm, reg.uMaxMm, reg.vMinMm, reg.vMaxMm)
        val face = currentFace()
        val rules = currentRules()
        val provider = CloudSettings.importProvider(this)
        val key = CloudSettings.apiKey(this, provider)
        val model = CloudSettings.model(this, provider)
        val gauge = rules.gaugeDiameterMm
        val uMin = reg.uMinMm; val uMax = reg.uMaxMm
        val vMin = reg.vMinMm; val vMax = reg.vMaxMm
        notifyUser("Scoring with ${provider.label}…")
        Thread {
            val out = java.io.ByteArrayOutputStream()
            val longest = maxOf(rect.width, rect.height)
            val send = if (longest <= 1568) rect else Bitmap.createScaledBitmap(
                rect, rect.width * 1568 / longest, rect.height * 1568 / longest, true)
            send.compress(Bitmap.CompressFormat.JPEG, 85, out)
            val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            val result = SecondOpinion.ask(provider, key, model, b64, scoreToo = true)
            runOnUiThread {
                when (result) {
                    is SecondOpinion.Result.Failed -> notifyUser(result.message)
                    is SecondOpinion.Result.Ok -> {
                        if (binding.cbReplace.isChecked) {
                            ScoringSession.startNew(face, rules, distanceFromField())
                        }
                        var disagreed = 0
                        for (sp in result.opinion.spots) {
                            val u = uMin + sp.xFrac * (uMax - uMin)
                            val v = vMax - sp.yFrac * (vMax - vMin)
                            val hole = DetectedHole(
                                xMm = u, yMm = v, diameterMm = gauge,
                                contrast = 0.0, confidence = 1.0, elongation = 1.0
                            )
                            val idx = ScoringSession.state.shots.count { !it.sighter } + 1
                            ScoringSession.addShot(
                                ScoringEngine.scoreHole(
                                    hole, face, rules, ProfileRepository(this).getBullet(),
                                    index = idx,
                                    series = ScoringEngine.seriesFor(idx, rules),
                                    manual = true
                                ), rules
                            )
                            if (sp.ring >= 0 &&
                                face.scoreInteger(Math.hypot(u, v), gauge / 2.0) != sp.ring
                            ) disagreed++
                        }
                        refreshStatus()
                        notifyUser(buildString {
                            append("${provider.label} scored ${result.opinion.spots.size} shots. ")
                            if (disagreed > 0) {
                                append("$disagreed disagree with the ring the geometry gives at ")
                                append("that position — check those on the plot. ")
                            }
                            append("All are marked hand-placed: no position here was measured.")
                        })
                    }
                }
            }
        }.start()
    }

    private fun doDetect() {
        val reg = registration ?: run { notifyUser("Register the card first."); return }
        val shot = shotBitmap ?: return

        // ---- CLOUD AI: the embedded detection does not run at all ----
        //
        // Here rather than behind a button on Results, because the setting
        // says "on import" and a setting that needs a second action somewhere
        // else is not the setting it claims to be. Registration above has
        // already happened, and it must: without the millimetre grid there is
        // nothing to draw shots on and no way to map what comes back.
        if (CloudSettings.engine(this) == ScoringSource.CLOUD) {
            scoreWithCloud(reg, shot)
            return
        }
        val rules = currentRules()
        val face = currentFace()

        // The COLOUR channel for hole finding — on a real card a hole is
        // brown rather than merely dark, and measuring distance from the
        // paper colour roughly doubles its separation from the paper.
        val shotFrame = LumaFrame.fromBitmapForDetection(shot)
        val clean = cleanBitmap

        val holes = if (clean != null) {
            // Both images are rectified through the SAME registration, which
            // assumes the two photographs were taken from roughly the same
            // place. That is a real constraint and the picker says so; a
            // second set of taps for the clean image would lift it, at the
            // cost of asking for eight taps instead of four.
            HoleDetector.detectByDifference(
                reg, reg.rectify(LumaFrame.fromBitmapForDetection(clean)), reg.rectify(shotFrame),
                rules.gaugeDiameterMm
            )
        } else if (ScaleSettings.sourceDetector()) {
            // Detects in the SOURCE photograph at whatever resolution it
            // arrived in, and reads luminance inside the aiming mark where
            // the colour channel carries nothing. On the user's card this is
            // the difference between 10 points and the correct 19.
            SourceHoleDetector.detect(
                reg, shotFrame, rules.gaugeDiameterMm,
                luma = LumaFrame.fromBitmap(shot),
                includeMisses = ScaleSettings.scoreOutsideArea()
            )
        } else {
            HoleDetector.detectAbsolute(reg, reg.rectify(shotFrame), rules.gaugeDiameterMm)
        }

        // Keep the shooter's own card, rectified onto the scoring grid, so
        // the Results plot can show the hits ON THE PHOTOGRAPH. That is the
        // only view in which a MISSED hole is obvious — on the template a
        // hole that was never detected leaves nothing behind to notice.
        runCatching {
            ScoredPhoto.set(
                this, reg.rectifyColour(shot),
                reg.uMinMm, reg.uMaxMm, reg.vMinMm, reg.vMaxMm
            )
        }.onFailure {
            Logger.w("ImportActivity", "could not rectify the photo for display: ${it.message}")
            ScoredPhoto.clear(this)
        }

        // Replace FIRST, before knowing whether anything was found. Doing it
        // afterwards meant a photograph that detected nothing left the
        // previous session untouched, and the Results screen went on showing
        // an older target's shots as though they belonged to this one.
        if (binding.cbReplace.isChecked) {
            ScoringSession.startNew(face, rules, distanceFromField())
        } else {
            ScoringSession.setDistance(distanceFromField())
        }

        runCatching {
            val c = com.rfsat.sts.scoring.ShotCountCheck.check(rules, holes.size)
            c.message?.let { Logger.w("ImportActivity", it); notifyUser(it) }
        }

        if (holes.isEmpty()) {
            binding.hdrDistribution.visibility = View.GONE
            binding.histogram.distribution = com.rfsat.sts.scoring.ShotDistribution.EMPTY
            // Assembled as spanned text rather than a plain string so the
            // three causes get a hanging indent: each of them wraps, and a
            // wrapped line running back under the bullet made a three-item
            // list read as six.
            val why = android.text.SpannableStringBuilder()
            why.append(buildString {
                appendLine("No holes were found, and any previously recorded shots have been cleared.")
                appendLine()
                appendLine("Scored against: ${face.name}")
                appendLine("Ring spacing ${face.ringPitchMm?.let { "%.2f mm".format(it) } ?: "uneven"}, " +
                    "outer ring ${"%.1f".format(face.outerRadiusMm * 2)} mm, " +
                    "gauge ${rules.gaugeDiameterMm} mm")
                appendLine()
                appendLine("The commonest causes, in order:")
            })
            why.append(com.rfsat.sts.ui.Bullets.list(listOf(
                "the wrong target face — its ring spacing and its scoring gauge " +
                    "(${rules.gaugeDiameterMm} mm) have to match the card you actually shot",
                "the box not on the outermost ring",
                "a photograph too small or too soft to resolve a ${rules.gaugeDiameterMm} mm hole"
            ), binding.tvResult.textSize, gap = "\n"))
            why.append("\n\nThe diagnostic log on the Home screen records what the detector saw; " +
                "its Full report button shares that together with the face and gauge in use.")
            binding.tvResult.text = why
            Logger.w("ImportActivity", "Detection found nothing; session cleared to avoid stale results")
            return
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

    private fun refreshAfterClear() {
        binding.hdrDistribution.visibility = View.GONE
        binding.histogram.distribution = com.rfsat.sts.scoring.ShotDistribution.EMPTY
        binding.tvResult.text = "No shots recorded."
        refreshStatus()
    }

    private fun distanceFromField(): Double =
        binding.etDistance.text.toString().toDoubleOrNull()
            ?.let { UnitsManager.inputDistanceToMeters(it) }
            ?: currentRules().distanceM

    private fun currentFace(): TargetFace =
        faces.getOrNull(binding.spTarget.selectedItemPosition) ?: TargetRepository(this).activeFace()

    private fun currentRules(): RuleSet =
        ruleSets.getOrNull(binding.spRules.selectedItemPosition) ?: RuleRepository(this).activeSet()


    /**
     * Identifies the target from the picture and registers it in one step.
     *
     * Replaces "find the black mark, multiply by the selected face's ratio,
     * hope the face is right" with "fit the ring family, let the fit say
     * which face it is, and take the scale from the ring pitch". Everything
     * that went wrong before — the box on the 9 ring, the box on the 5 ring,
     * every distance half its true size — came from the old order.
     */
    private fun doIdentifyTarget(silent: Boolean = false) {
        val frame = detectionFrame() ?: run {
            if (!silent) notifyUser("No picture to work from yet.")
            return
        }
        val mark = BlackMarkDetector.detect(frame)
        val fit = RingFinder.find(
            frame,
            seedX = mark?.centreXPx ?: -1.0,
            seedY = mark?.centreYPx ?: -1.0
        )
        if (fit == null) {
            notifyUser(
                "The printed rings could not be fitted — the target may be cropped, very low " +
                    "contrast, or photographed at too steep an angle. Falling back to the aiming mark."
            )
            doAutoDetect()
            return
        }

        lastFit = fit
        lastMarkRadiusPx = mark?.radiusPx ?: 0.0
        val matches = if (mark != null)
            RingFinder.identify(
                fit, mark.radiusPx, TargetRepository(this).allFaces(),
                distanceFromField(), currentFace().id
            )
        else emptyList()
        val best = matches.firstOrNull()

        if (best != null && best.relativeError < IDENTIFY_TOLERANCE) {
            faces.indexOfFirst { it.id == best.face.id }.takeIf { it >= 0 }?.let { idx ->
                pendingTargetSelection = idx
                binding.spTarget.setSelection(idx)
                TargetRepository(this).setActiveFace(best.face.id)
            }
            val runnerUp = matches.getOrNull(1)
            notifyUser(buildString {
                append("Identified as %s (%.0f%% agreement".format(best.face.name, 100 * (1 - best.relativeError)))
                if (runnerUp != null) append(", next best %s at %.0f%%".format(
                    runnerUp.face.name, 100 * (1 - runnerUp.relativeError)))
                append("). Scale from %d fitted rings.".format(fit.ringCount))
            })
        } else {
            notifyUser(
                "The rings were fitted, but no catalogue face matches these proportions. " +
                    "Registering against the selected face — check it is the right one, or add " +
                    "this target under Targets."
            )
        }

        // The ring family's ellipticity is an INDEPENDENT read on the tilt,
        // from thousands of edge samples rather than the outline of one
        // aiming mark. Offered on the same terms: above the noise floor it
        // seeds the sliders, and it is never applied by itself, because the
        // sign is still a guess and 4 degrees of the reading is noise.
        if (fit.tiltWorthSuggesting) {
            suggestedTilt = BoxTransform(
                rotationDeg = 0.0,
                tiltXDeg = (fit.impliedTiltDeg * -kotlin.math.sin(Math.toRadians(fit.orientationDeg)))
                    .coerceIn(-BoxTransform.MAX_TILT_DEG, BoxTransform.MAX_TILT_DEG),
                tiltYDeg = (fit.impliedTiltDeg * -kotlin.math.cos(Math.toRadians(fit.orientationDeg)))
                    .coerceIn(-BoxTransform.MAX_TILT_DEG, BoxTransform.MAX_TILT_DEG)
            )
            binding.btnApplyTilt.isEnabled = true
            notifyUser(
                ("The rings measure %.0f%% out of round, which suggests about %.0f° of tilt. " +
                    "Tap “Apply estimated tilt” to try it — below roughly %.0f° the measurement is " +
                    "inside its own noise, so nothing is applied for you.")
                    .format(100 * (1 - fit.axisRatio), fit.impliedTiltDeg,
                        HoughCentre.TILT_NOISE_FLOOR_DEG)
            )
        }

        val face = currentFace()
        val rules = currentRules()
        val reg = TargetRegistration.fromRingFit(
            face, fit, rules.gaugeDiameterMm, transform, frame,
            markRadiusPx = mark?.radiusPx ?: 0.0
        )
        if (reg == null) {
            // Not a failure of the fit — some faces simply have no single
            // ring pitch to scale from. The practical and service faces put
            // their scoring zones at unequal spacings by design, and for
            // those the aiming mark is the only measurement there is.
            notifyUser(
                "${face.name} has unevenly pitched rings, so a fitted pitch cannot set its " +
                    "scale. Registering from the aiming mark instead."
            )
            doAutoDetect(silent = true)
            return
        }
        registration = reg
        boxMeaning = TargetRegistration.BoxMeaning.OUTER_SCORING_RING
        showRingFamily(fit, face)
        offerAspect(fit)
        offerLens(fit)
        Logger.i(
            "ImportActivity",
            "%d ring candidates found, %d used by the fitted family".format(
                fit.candidatesPx.size, fit.ringCount
            )
        )
        reg.warnings.forEach { Logger.w("Registration", it) }
        refreshStatus()
    }

    private companion object {
        /** Beyond this the best-matching face is not convincing. On the two
         *  real targets tested the right face agreed to within 1.3% while the
         *  runner-up was 8% out, so 5% separates them comfortably. */
        const val IDENTIFY_TOLERANCE = 0.05
        private const val PREFS = "sts_import"
        private const val KEY_LAST_IMAGE = "last_image_uri"
    }

    /** The picture the detector should work on: the colour channel, which is
     *  where a brown hole in white paper actually stands out. */
    /**
     * Draws a fitted ring family on the photograph and takes the registration
     * box from it. Returns false when this face cannot be scaled from a ring
     * pitch, which is not a failure — the practical faces put their zones at
     * unequal spacings by design.
     *
     * SHARED BY BOTH REGISTRATION ROUTES, and that is the point. They used to
     * derive the box differently: one from the fitted rings, the other from
     * the black aiming mark, so the same photograph gave two different boxes
     * and only one of the two routes ever drew the circles it had measured.
     * A shooter comparing them had no way to tell which was right, or that
     * the difference was a difference of method rather than of accuracy.
     */
    private fun showRingFamily(fit: RingFit, face: TargetFace): Boolean {
        val pitchMm = face.ringPitchMm ?: return false
        if (fit.pitchPx <= 0.0) return false
        // BACK TO SOURCE PIXELS FIRST. Every coordinate in a RingFit is in
        // the de-foreshortened frame; the overlay draws on the photograph.
        val cf = fit.correctedFrame
        val (srcCx, srcCy) = cf?.toSource(fit.centreXPx, fit.centreYPx)
            ?: (fit.centreXPx to fit.centreYPx)
        val outerPx = (face.outerRadiusMm / (pitchMm / fit.pitchPx)).toFloat()
        binding.overlay.setBoxInSource(
            (srcCx - outerPx).toFloat(), (srcCy - outerPx).toFloat(),
            (srcCx + outerPx).toFloat(), (srcCy + outerPx).toFloat()
        )
        binding.overlay.detectedMarkers = fit.ringsPx.map {
            Triple(srcCx.toFloat(), srcCy.toFloat(), it.toFloat())
        }
        // Everything else the detector saw, dashed. A ring inside the black
        // that is found but not part of the fitted family looks exactly like
        // one that was never seen, and the two mean different things.
        val used = fit.ringsPx.toSet()
        binding.overlay.unusedMarkers = fit.candidatesPx
            .filter { c -> used.none { kotlin.math.abs(it - c) < 1.0 } }
            .map { Triple(srcCx.toFloat(), srcCy.toFloat(), it.toFloat()) }
        return true
    }

    private fun detectionFrame(): LumaFrame? =
        shotBitmap?.let { LumaFrame.fromBitmapForDetection(it) }

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
        ArrayAdapter(this, R.layout.spinner_item, items).also {
            it.setDropDownViewResource(R.layout.spinner_dropdown_item)
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
        if (shotBitmap !== sourceShotBitmap) shotBitmap?.recycle()
        shotBitmap = null
        sourceShotBitmap?.recycle(); sourceShotBitmap = null
        cleanBitmap?.recycle(); cleanBitmap = null
    }
}
