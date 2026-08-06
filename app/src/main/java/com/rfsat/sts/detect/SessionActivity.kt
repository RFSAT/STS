package com.rfsat.sts.detect

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.util.Size
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.rfsat.sts.R
import com.rfsat.sts.databinding.ActivitySessionBinding
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * The firing-point screen: choose the course of fire, register the target,
 * and record shots.
 *
 * THREE WAYS TO RECORD A SHOT, and the screen makes all three first class
 * because each is the right one somewhere:
 *
 *   LIVE — a reference frame is stored, then every analysis frame is
 *   differenced against it and new holes are reported as they appear. The
 *   best experience, and it needs the camera to stay pointed at the target
 *   for the whole string.
 *
 *   SINGLE FRAME — score whatever is on the target right now. With a stored
 *   reference this is a difference and is accurate; without one it falls back
 *   to absolute detection, which is weaker and says so. This is how you score
 *   a card someone hands you at the end of a relay.
 *
 *   BY HAND — tap the plot on the Results screen. Always available, and the
 *   final authority: a detector that cannot be overruled is a detector that
 *   is sometimes just wrong.
 *
 * THREADING. Frames arrive on a camera executor and are analysed there. The
 * most recent frame is published through an AtomicReference for the "score
 * now" button to grab, rather than being queued: analysis is slower than
 * capture, and a queue would build latency until the preview and the
 * detections were describing different moments.
 */
class SessionActivity : BaseActivity() {

    companion object {
        /** Beyond this the best-matching face is not convincing. On the two
         *  real targets tested the right face agreed to within 1.3% while the
         *  runner-up was 8% out, so 5% separates them comfortably. */
        const val IDENTIFY_TOLERANCE = 0.05

        /** How often the live face check may run. A full ring fit costs about
         *  a second, so this is a compromise between a badge that follows the
         *  card and a preview that stays smooth. */
        const val FACE_CHECK_INTERVAL_MS = 2500L

        /** How long to wait for the TextureView to hand over a surface
         *  before saying so. Two seconds is several layout passes. */
        const val SURFACE_WAIT_MS = 2000L

        const val EXTRA_NEW = "start_new"

        private const val PREFS = "sts_session"
        private const val KEY_STREAM_URL = "stream_url"
        private const val KEY_STREAM_SOURCE = "stream_source"
        private const val REQ_PERMISSIONS = 4711

        private val SOURCES = listOf(
            "Phone camera",
            "MJPEG stream (IP or action camera)",
            "RTSP stream (scope or action camera)"
        )
    }

    private lateinit var binding: ActivitySessionBinding
    private lateinit var analysisExecutor: ExecutorService

    private var provider: ProcessCameraProvider? = null
    private var camera: androidx.camera.core.Camera? = null
    private var stills: androidx.camera.core.ImageCapture? = null
    private var captureResolution: CaptureResolution = CaptureResolution.DEFAULT
    private var faceCheckRunning = false
    private var lastFaceCheckMs = 0L
    private var lastAnnouncedMatch: com.rfsat.sts.ui.GuideMatch? = null

    /**
     * Faces the guide has already moved AWAY from, automatically, in this
     * session. Two catalogue cards can sit close enough that consecutive
     * frames rank them differently, and without this the guide would swap
     * back and forth while the shooter watched. Once the app has decided a
     * face is not the one in front of the camera, it does not silently
     * decide the opposite a few seconds later; the user can still choose it
     * by hand, which clears the record.
     */
    private val rejectedFaceIds = HashSet<String>()
    private var externalSource: FrameSource? = null
    private val audio = AudioShotDetector()

    /** Most recent analysed frame, published for the on-demand paths. */
    private val latestFrame = AtomicReference<LumaFrame?>(null)
    private var analysisSize: Size? = null

    private var registration: TargetRegistration? = null
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
    private var live: LiveHitDetector? = null
    private var liveRunning = false

    /** Set from the moment a stream open is decided until the source is
     *  attached, so the several places that can ask cannot each start one. */
    private var opening = false

    private var faces: List<TargetFace> = emptyList()
    private var ruleSets: List<RuleSet> = emptyList()

    private var selectedFace: TargetFace? = null
    private var selectedRules: RuleSet? = null

    // ------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySessionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        analysisExecutor = Executors.newSingleThreadExecutor()
        ScoringSession.attach(this)
        ScoringSession.adoptSelectionIfEmpty(this)

        runCatching { initScreen() }.onFailure {
            Logger.e("SessionActivity", "Screen init failed", it)
            notifyUser("This screen failed to start: ${it.message}")
        }
        setupBottomNav(R.id.nav_session)
    }

    private fun initScreen() {
        faces = TargetRepository(this).allFaces()
        ruleSets = RuleRepository(this).allSets()

        // Both spinners are pointed at the stored selection BEFORE their
        // listeners can act, and the position set is remembered so the
        // callback the Spinner posts for it is recognised as ours rather than
        // treated as a user choice. See the note on pendingRulesSelection.
        val ruleIndex = ruleSets.indexOfFirst { it.id == RuleRepository(this).activeSet().id }
            .coerceAtLeast(0)
        val faceIndex = faces.indexOfFirst { it.id == TargetRepository(this).activeFace().id }
            .coerceAtLeast(0)
        selectedRules = ruleSets.getOrNull(ruleIndex)
        selectedFace = faces.getOrNull(faceIndex)

        // ---- rules spinner ----
        binding.spRules.adapter = adapter(ruleSets.map { "${it.name}  (${it.governingBody})" })
        pendingRulesSelection = ruleIndex
        binding.spRules.setSelection(ruleIndex)
        binding.spRules.onItemSelectedListener = onSelected { i ->
            if (i == pendingRulesSelection) { pendingRulesSelection = -1; return@onSelected }
            val r = ruleSets.getOrNull(i) ?: return@onSelected
            selectedRules = r
            RuleRepository(this).setActiveSet(r.id)
            // Changing the course of fire moves the target face with it —
            // but only on a real user change, never on the way into the
            // screen, which used to silently revert a chosen target.
            faces.indexOfFirst { it.id == r.targetFaceId }.takeIf { it >= 0 }?.let { idx ->
                pendingTargetSelection = idx
                binding.spTarget.setSelection(idx)
                selectedFace = faces[idx]
                refreshGuide()
                TargetRepository(this).setActiveFace(faces[idx].id)
                notifyUser("Target face switched to ${faces[idx].name} to match the rules.")
            }
            binding.etDistance.setText(fmt(UnitsManager.displayDistance(r.distanceM)))
            registration = null
            live = null
            refreshStatus()
        }

        // ---- target spinner ----
        // A picture of each face, because this is the spinner where the
        // choice is actually made.
        binding.spTarget.adapter = com.rfsat.sts.ui.TargetSpinnerAdapter(this, faces)
        pendingTargetSelection = faceIndex
        binding.spTarget.setSelection(faceIndex)
        binding.spTarget.onItemSelectedListener = onSelected { i ->
            if (i == pendingTargetSelection) { pendingTargetSelection = -1; return@onSelected }
            val f = faces.getOrNull(i) ?: return@onSelected
            selectedFace = f
            // Chosen by hand, so the app's own earlier refusals no longer
            // apply: the user may deliberately be going back to a face the
            // guide rejected, and it must not be overruled for that.
            rejectedFaceIds.clear()
            // A new face means the old verdict says nothing about this one.
            binding.crosshair.match = com.rfsat.sts.ui.GuideMatch.UNKNOWN
            lastAnnouncedMatch = null
            lastFaceCheckMs = 0L
            refreshGuide()
            checkFaceAgainstView()
            TargetRepository(this).setActiveFace(f.id)
            registration = null   // the face changed; the old mapping is void
            live = null
            refreshStatus()
        }

        binding.lblDistance.text = "Distance (${UnitsManager.distanceUnitLabel()})"

        // ---- frame source ----
        //
        // BOTH THE ADDRESS AND THE CHOICE ARE REMEMBERED. A stream address is
        // long, exact, and typed on a phone keyboard — rtsp://192.168.1.254:
        // 554/live — and it was being thrown away every time the screen
        // closed, so every session began by typing it again from memory or
        // hunting for it. Kept in ordinary preferences, which survive an app
        // upgrade, and restored below before the listener is attached so the
        // restore does not read as a fresh choice by the user.
        binding.spSource.adapter = adapter(SOURCES)
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val savedUrl = prefs.getString(KEY_STREAM_URL, "").orEmpty()
        val savedSource = prefs.getInt(KEY_STREAM_SOURCE, 0).coerceIn(0, SOURCES.size - 1)
        if (savedUrl.isNotEmpty()) binding.etStreamUrl.setText(savedUrl)
        // The phone camera whenever there is no address to go back to: a
        // stream source with an empty box is a screen that cannot start.
        val restoredSource = if (savedUrl.isEmpty()) 0 else savedSource
        applySourceVisibility(restoredSource)
        binding.spSource.setSelection(restoredSource)
        binding.spSource.onItemSelectedListener = onSelected { i ->
            applySourceVisibility(i)
            prefs.edit().putInt(KEY_STREAM_SOURCE, i).apply()
            stopAllSources()
            if (i == 0) startCameraIfPermitted() else ensureStreamConnected()
        }
        binding.btnStreamConnect.setOnClickListener {
            // Explicit reconnect: a stream that has dropped is the ordinary
            // reason to press this, so it stops whatever is nominally
            // attached rather than refusing because something is.
            stopAllSources()
            ensureStreamConnected(announce = true)
        }
        binding.etStreamUrl.setOnEditorActionListener { _, _, _ ->
            stopAllSources()
            ensureStreamConnected(announce = true)
            false
        }
        // The address may have been restored above, in which case the
        // shooter has already said what they want — on a previous run.
        if (restoredSource != 0) binding.etStreamUrl.post { ensureStreamConnected() }

        // ---- buttons ----
        binding.overlay.onCornersChanged = { refreshStatus() }
        wireTransformControls()
        binding.btnIdentify.setOnClickListener { doIdentifyTarget() }
        binding.btnAutoDetect.setOnClickListener { doAutoDetect() }
        binding.btnRegister.setOnClickListener { doRegister() }
        binding.btnUndoCorner.setOnClickListener {
            binding.overlay.clearAll(); registration = null; live = null; refreshStatus()
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
            live = null
            refreshStatus()
        }
        binding.btnReference.setOnClickListener { doSetReference() }
        binding.spGuide.adapter = adapter(com.rfsat.sts.ui.AimGuide.values().map { it.label })
        binding.spGuide.setSelection(
            com.rfsat.sts.ui.AimGuide.values().indexOf(ScaleSettings.aimGuide())
        )
        binding.spGuide.onItemSelectedListener = onSelected { i ->
            val chosen = com.rfsat.sts.ui.AimGuide.values()[i]
            if (chosen != ScaleSettings.aimGuide()) {
                ScaleSettings.setAimGuide(this, chosen)
                refreshGuide()
                checkFaceAgainstView()
            }
        }
        binding.sbGuideSize.progress = (ScaleSettings.aimGuideSize() * 100).toInt()
        binding.sbGuideSize.setOnSeekBarChangeListener(
            object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, user: Boolean) {
                    if (!user) return
                    ScaleSettings.setAimGuideSize(this@SessionActivity, p / 100f)
                    refreshGuide()
                }
                override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
            }
        )
        refreshGuide()

        binding.spResolution.adapter = adapter(CaptureResolution.values().map { it.label })
        binding.spResolution.setSelection(CaptureResolution.values().indexOf(captureResolution))
        binding.spResolution.onItemSelectedListener = onSelected { i ->
            val chosen = CaptureResolution.values()[i]
            if (chosen != captureResolution) {
                captureResolution = chosen
                // The registration was measured in the old frame's pixels.
                registration = null
                notifyUser(
                    "Analysis resolution set to ${chosen.label}. The camera has been rebound and " +
                        "the registration cleared — register the card again."
                )
                startCameraIfPermitted()
                refreshStatus()
            }
        }

        binding.btnLockCamera.setOnClickListener {
            if (CameraTuning.locked) {
                CameraTuning.unlock(camera)
                notifyUser("Camera released. It will meter and focus by itself again.")
            } else if (CameraTuning.lock(camera, centreMeteringPoint())) {
                notifyUser("Exposure, white balance and focus locked at the centre of frame.")
            } else {
                notifyUser("This camera would not accept the lock; carry on without it.")
            }
            refreshLockButton()
        }

        binding.btnLive.setOnClickListener { toggleLive() }
        binding.btnScoreNow.setOnClickListener { doScoreNow() }
        binding.btnUndoShot.setOnClickListener {
            ScoringSession.undoLast(); refreshShotList(); refreshStatus()
        }
        binding.btnImport.setOnClickListener {
            startActivity(Intent(this, ImportActivity::class.java))
        }
        binding.btnClearShots.setOnClickListener {
            ScoringSession.clearShots()
            refreshAfterClear()
            notifyUser("All recorded shots cleared.")
        }
        binding.btnResults.setOnClickListener {
            startActivity(Intent(this, ResultsActivity::class.java)); finish()
        }
        binding.cbAcoustic.setOnCheckedChangeListener { _, on -> if (on) startAudio() else audio.stop() }

        // ---- session ----
        if (intent.getBooleanExtra(EXTRA_NEW, false) && !ScoringSession.hasShots) {
            startFreshSession()
        }
        binding.etDistance.setText(fmt(UnitsManager.displayDistance(ScoringSession.state.distanceM)))

        // ONLY WHEN THE CAMERA IS THE SOURCE. It was started unconditionally
        // here, so a session restored to a stream had the phone camera bound
        // and running behind it — visible in the log as "Camera bound" one
        // millisecond before the stream opened. It costs power, it holds the
        // camera against any other app, and its frames went nowhere.
        if (binding.spSource.selectedItemPosition == 0) startCameraIfPermitted()
        refreshShotList()
        refreshStatus()
    }

    private fun startFreshSession() {
        val f = currentFace()
        val r = currentRules()
        // THE DISTANCE THE SHOOTER SET, where they have set one.
        //
        // This used to take the rule set's nominal distance every time, so
        // starting a new string silently threw away a distance typed for the
        // previous one — 200 m becoming 10 m because that is where an ISSF
        // air rifle match is shot. A competition face used at another
        // distance in training is ordinary, and the rule book's figure is a
        // sensible DEFAULT rather than a correction to be applied.
        val kept = ScoringSession.state.distanceM
        ScoringSession.startNew(f, r, if (kept > 0.0) kept else r.distanceM)
        // The kept photograph belonged to the session just replaced. A new
        // session showing the previous card underneath its shots would be
        // worse than showing no photograph at all.
        com.rfsat.sts.scoring.ScoredPhoto.clear(this)
    }

    // ------------------------------------------------------------------
    //  Camera
    // ------------------------------------------------------------------

    private fun startCameraIfPermitted() {
        if (binding.spSource.selectedItemPosition != 0) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), REQ_PERMISSIONS
            )
            return
        }
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            runCatching {
                val p = future.get()
                provider = p
                p.unbindAll()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.preview.surfaceProvider)
                }

                // A high analysis resolution is not a luxury here. A 4.5 mm
                // pellet hole at 10 m on a 640x480 analysis frame is under two
                // pixels across and simply cannot be found; the registration
                // check in TargetRegistration will say so, but it is better
                // not to provoke it. 1920x1080 is requested as the target and
                // CameraX falls back to the nearest supported size.
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    captureResolution.size,
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                )
                            ).build()
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()

                analysis.setAnalyzer(analysisExecutor) { image ->
                    try {
                        // The colour channel, so live detection sees a hole
                        // the same way the photograph path does.
                        val frame = LumaFrame.fromImageProxyForDetection(image)
                        if (frame != null) onFrame(frame)
                    } catch (t: Throwable) {
                        Logger.e("SessionActivity", "Frame analysis failed", t)
                    } finally {
                        image.close()
                    }
                }

                // A SEPARATE full-resolution still capture.
                //
                // The analysis stream is bounded by what the device will
                // deliver continuously — 1080p on most phones, 2 megapixels —
                // because it has to arrive thirty times a second. A still has
                // no such constraint and comes off the sensor at its full
                // size, commonly 12 megapixels. That is 2.4 times the linear
                // resolution on every hole, and holes are the smallest thing
                // this app has to measure. Scoring a card from a preview
                // frame when the camera could have given a photograph was
                // throwing most of the available detail away.
                val capture = androidx.camera.core.ImageCapture.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                            .build()
                    )
                    .setCaptureMode(
                        androidx.camera.core.ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
                    )
                    .build()
                stills = capture

                camera = p.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis, capture
                )
                // A rebind releases any lock, so the flag must not outlive it.
                CameraTuning.forget()
                refreshLockButton()
                Logger.i("SessionActivity", "Camera bound at ${captureResolution.label}")
            }.onFailure {
                Logger.e("SessionActivity", "Camera bind failed", it)
                notifyUser("The camera could not be started: ${it.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSIONS) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startCameraIfPermitted()
            else notifyUser("Without camera access the app can still score by hand on the Results screen.")
        }
    }

    /** One place deciding which viewfinder is on screen, so the restore and
     *  a user choice cannot drift apart. */
    private fun applySourceVisibility(i: Int) {
        binding.etStreamUrl.visibility = if (i == 0) View.GONE else View.VISIBLE
        binding.btnStreamConnect.visibility = if (i == 0) View.GONE else View.VISIBLE
        binding.preview.visibility = if (i == 0) View.VISIBLE else View.GONE
        binding.streamView.visibility = if (i == 2) View.VISIBLE else View.GONE
    }

    /**
     * Opens the stream as soon as there is a source and an address to open,
     * rather than waiting for something else to ask.
     *
     * THIS IS WHY A STREAM COULD NEVER APPEAR. Starting it was done in ONE
     * place — the live-detection button — and that button refuses until a
     * reference frame has been captured. A reference frame comes from the
     * source. So with a stream selected the app sat in a deadlock it could
     * not report: no frames until detection starts, no detection until a
     * frame arrives, and nothing in the log either way, because the code that
     * would have logged it never ran. The address was right and the decoder
     * work in 1.50.0 was necessary; neither was ever reached.
     *
     * The stream is now connected on choosing the source, on returning to the
     * screen, on pressing Done in the address box and on the Connect button —
     * every point at which the shooter has said what they want.
     */
    private fun ensureStreamConnected(announce: Boolean = false) {
        val i = binding.spSource.selectedItemPosition
        if (i == 0) return
        // ONE OPEN AT A TIME. Selecting the source, restoring a saved address
        // and returning to the screen can all fire within a few
        // milliseconds — the field log showed the same stream opened twice,
        // four milliseconds apart, which puts two decoders on one
        // SurfaceTexture and leaks the first. The flag is set before anything
        // slow starts, because "is it running yet" is answered too late to be
        // any use here.
        if (opening) {
            if (announce) notifyUser("Already connecting…")
            return
        }
        if (externalSource?.isRunning == true) {
            if (announce) notifyUser("Already connected to ${externalSource?.label}.")
            return
        }
        val url = binding.etStreamUrl.text.toString().trim()
        if (url.isEmpty()) {
            if (announce) notifyUser("Enter the stream address first.")
            return
        }
        opening = true
        startExternalSource()
    }

    private fun startExternalSource() {
        val url = binding.etStreamUrl.text.toString().trim()
        if (url.isEmpty()) { opening = false; notifyUser("Enter the stream address first."); return }
        // Remembered on USE rather than on every keystroke: a half-typed
        // address is not one worth coming back to, and this is the moment the
        // shooter has said it is the one they mean.
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_STREAM_URL, url)
            .putInt(KEY_STREAM_SOURCE, binding.spSource.selectedItemPosition)
            .apply()
        when (binding.spSource.selectedItemPosition) {
            1 -> {
                if (!url.startsWith("http", ignoreCase = true)) {
                    notifyUser(
                        "An MJPEG address starts with http. For an rtsp:// address choose " +
                            "“RTSP stream” as the source."
                    )
                    opening = false
                    return
                }
                begin(MjpegFrameSource(url))
            }
            2 -> {
                if (!url.startsWith("rtsp", ignoreCase = true)) {
                    notifyUser(
                        "An RTSP address starts with rtsp. For an http:// address choose " +
                            "“MJPEG stream” as the source."
                    )
                    opening = false
                    return
                }
                startRtsp(url)
            }
            else -> { opening = false; return }
        }
    }

    private fun begin(src: FrameSource) {
        // Anything still attached is stopped first: two sources feeding the
        // detector would interleave frames from different pictures.
        runCatching { externalSource?.stop() }
        externalSource = src
        opening = false
        // LOGGED BEFORE AND AFTER. The failure reported from the field left
        // no trace at all, and a log that only records success cannot
        // distinguish "it did not work" from "it was never attempted".
        Logger.i("SessionActivity", "Opening external source: ${src.label}")
        var first = true
        src.start(
            onFrame = {
                if (first) {
                    first = false
                    Logger.i("SessionActivity", "First frame from ${src.label}: ${it.width} x ${it.height}")
                    runOnUiThread { refreshStatus() }
                }
                onFrame(it)
            },
            onError = { msg ->
                Logger.w("SessionActivity", "External source: $msg")
                runOnUiThread { notifyUser(msg) }
            }
        )
    }

    /**
     * Starts RTSP once the stream view actually has a surface to decode into.
     *
     * A TextureView made visible a moment ago does NOT have a SurfaceTexture
     * until the next layout pass. Reading it straight out of the button
     * handler therefore returned null whenever the shooter typed an address
     * and pressed Start without pausing — and the failure was silent, so an
     * address that plays perfectly well in VLC did nothing at all here.
     */
    private fun startRtsp(url: String) {
        binding.streamView.visibility = View.VISIBLE
        val existing = binding.streamView.surfaceTexture
        if (existing != null) {
            begin(RtspFrameSource(this, url).also {
                it.attachTexture(existing) { binding.streamView.bitmap }
            })
            return
        }
        notifyUser("Waiting for the video surface…")
        binding.streamView.surfaceTextureListener =
            object : android.view.TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(t: SurfaceTexture, w: Int, h: Int) {
                    binding.streamView.surfaceTextureListener = null
                    begin(RtspFrameSource(this@SessionActivity, url).also {
                        it.attachTexture(t) { binding.streamView.bitmap }
                    })
                }
                override fun onSurfaceTextureSizeChanged(t: SurfaceTexture, w: Int, h: Int) = Unit
                override fun onSurfaceTextureDestroyed(t: SurfaceTexture): Boolean = true
                override fun onSurfaceTextureUpdated(t: SurfaceTexture) = Unit
            }
        // A view that never gets a surface would otherwise wait for ever.
        binding.streamView.postDelayed({
            if (externalSource == null && binding.streamView.surfaceTextureListener != null) {
                binding.streamView.surfaceTextureListener = null
                opening = false
                notifyUser(
                    "The stream view never became ready, so nothing could be decoded. " +
                        "Leave the Session tab open and press Connect to the stream again."
                )
            }
        }, SURFACE_WAIT_MS)
    }

    private fun stopAllSources() {
        runCatching { externalSource?.stop() }
        externalSource = null
        runCatching { provider?.unbindAll() }
    }

    // ------------------------------------------------------------------
    //  Frame handling
    // ------------------------------------------------------------------

    private fun onFrame(frame: LumaFrame) {
        latestFrame.set(frame)
        // Re-check periodically while aiming, so the corner badge follows the
        // card rather than reporting what was true a minute ago.
        runOnUiThread { checkFaceAgainstView() }
        if (analysisSize?.width != frame.width || analysisSize?.height != frame.height) {
            analysisSize = Size(frame.width, frame.height)
            runOnUiThread { binding.overlay.setSourceGeometry(frame.width, frame.height) }
        }
        val detector = live
        if (liveRunning && detector != null && detector.isArmed) {
            val newShots = runCatching { detector.onFrame(frame) }.getOrDefault(emptyList())
            if (newShots.isNotEmpty()) {
                runOnUiThread { recordShots(newShots) }
            }
        }
    }

    // ------------------------------------------------------------------
    //  Actions
    // ------------------------------------------------------------------

    /**
     * Finds the aiming mark in the latest frame and places the box round it.
     *
     * Runs on the CURRENT frame rather than continuously: the target does not
     * move, so re-detecting every frame would burn battery to redraw the same
     * box, and a box that twitched under the user's finger while they were
     * adjusting it would be worse than no automation at all.
     */
    private fun doAutoDetect() {
        if (binding.cbCornerMode.isChecked) return
        val frame = latestFrame.get() ?: run {
            notifyUser("No frame yet — is the camera or stream running?")
            return
        }
        val face = currentFace()
        val disc = BlackMarkDetector.detect(frame)

        if (disc == null) {
            binding.overlay.setDefaultBox()
            boxMeaning = TargetRegistration.BoxMeaning.OUTER_SCORING_RING
            markEllipticity = 1.0
            suggestedTilt = BoxTransform.NONE
            applyTransform(BoxTransform.NONE)
            notifyUser(
                "No black aiming mark could be found, so a box has been placed in the middle for " +
                    "you to drag onto the scoring area."
            )
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

        val (box, meaning) = BlackMarkDetector.boxFor(disc, face, frame.width, frame.height)
        boxMeaning = meaning
        binding.overlay.setBoxInSource(box[0], box[1], box[2], box[3])
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
        } else {
            notifyUser("Found the target. Check the box, then Register.")
        }
        refreshStatus()
    }

    private fun doRegister() {
        val face = currentFace()
        val rules = currentRules()

        val reg = if (binding.cbCornerMode.isChecked) {
            val corners = binding.overlay.cornersInSource() ?: run {
                notifyUser("Tap all four corners of the card first — ${binding.overlay.cornerCount()} of 4 so far.")
                return
            }
            val r = TargetRegistration.fromCardCorners(face, corners, rules.gaugeDiameterMm)
            if (r == null) {
                notifyUser("Those four taps do not define a quadrilateral. Tap the corners in order, going clockwise.")
                return
            }
            ScoringSession.saveRegistrationCorners(corners)
            r
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
        // Registering means the framing is settled, which is the moment to
        // stop the camera adjusting itself underneath it. Anything the camera
        // changes from here — brightness, colour, or the field of view when
        // the lens moves to refocus — invalidates the mapping just measured.
        lockCameraForScoring()
        Logger.i(
            "SessionActivity",
            "registered %s: face '%s', rules '%s', gauge %.2f mm, %s".format(
                if (binding.cbCornerMode.isChecked) "by card corners" else "by box",
                face.name, rules.name, rules.gaugeDiameterMm, transform.summary()
            )
        )
        reg.warnings.forEach { Logger.w("Registration", it) }

        // A box in the right place on the wrong face still scores everything
        // wrongly, and looks completely normal while doing it.
        runCatching {
            latestFrame.get()?.let { f ->
                TargetGeometryCheck.verifyRings(f, reg, face)?.let { problem ->
                    Logger.w("Registration", problem)
                    notifyUser(problem)
                }
            }
        }
        live = LiveHitDetector(reg, rules.gaugeDiameterMm)
        reg.warnings.forEach { Logger.w("SessionActivity", it) }
        if (reg.warnings.isNotEmpty()) notifyUser(reg.warnings.joinToString("\n\n"))
        else notifyUser("Registered. Capture the clean target next, then start live detection.")
        refreshStatus()
    }

    private fun doSetReference() {
        val reg = registration ?: run { notifyUser("Register the target first."); return }
        val frame = latestFrame.get()
            ?: run { notifyUser("No frame yet — is the source running?"); return }
        // THE ANALYSIS STREAM, deliberately, and not a full-resolution still.
        //
        // The reference is differenced against every later frame, and those
        // arrive continuously from the analysis stream. LiveHitDetector
        // rectifies the reference and each frame through ONE registration, so
        // the two must come from the same stream: a still reference would be
        // compared against analysis frames through geometry that fits neither.
        // The resolution is worth having, but not at the price of differencing
        // two images that are not aligned.
        val detector = live ?: LiveHitDetector(reg, currentRules().gaugeDiameterMm).also { live = it }
        detector.setReference(frame)
        notifyUser(
            "Reference captured at ${frame.width} x ${frame.height}. Everything that appears " +
                "from now on is treated as a shot. Do not move the phone or the card."
        )
        refreshStatus()
    }

    private fun toggleLive() {
        val detector = live
        if (detector == null || !detector.isArmed) {
            // Said with the reason when there is one: on a stream source the
            // usual cause of having no reference is having no picture, and
            // "capture a reference first" sends the shooter to a button that
            // will fail for the same reason.
            notifyUser(
                if (binding.spSource.selectedItemPosition != 0 && externalSource?.isRunning != true)
                    "The stream is not connected, so there is nothing to capture a reference " +
                        "from. Press Connect to the stream first."
                else "Capture the clean target as a reference first."
            )
            return
        }
        liveRunning = !liveRunning
        binding.btnLive.text = if (liveRunning) "Stop live detection" else "Start live detection"
        if (liveRunning && externalSource == null && binding.spSource.selectedItemPosition != 0) {
            startExternalSource()
        }
        refreshStatus()
    }

    /**
     * Scores whatever is on the target right now.
     *
     * If a reference exists this is the differential path and is accurate. If
     * not, the absolute detector runs and the user is told plainly that this
     * is the weaker mode — because the failure it produces (a printed ring
     * counted as a shot) looks exactly like a real detection on the plot.
     */
    private fun doScoreNow() {
        if (registration == null) { notifyUser("Register the target first."); return }

        // A reference exists, so this frame has to be comparable with it —
        // which means the analysis stream, at the analysis stream's
        // resolution. Only the referenceless case is free to take a
        // photograph, because there is nothing it has to align with.
        val detector = live
        if (detector != null && detector.isArmed) {
            latestFrame.get()?.let { scoreFrame(it, registration) }
                ?: notifyUser("No frame available yet.")
            return
        }

        notifyUser("Taking a full-resolution photograph\u2026")
        captureStill { frame, _ ->
            val stillReg = frame?.let { registrationForStill(it) }
            if (frame != null && stillReg != null) scoreFrame(frame, stillReg)
            else {
                if (frame != null) notifyUser(
                    "The photograph could not be matched to the registration, so the preview " +
                        "frame was scored instead. Re-register if this keeps happening."
                )
                latestFrame.get()?.let { scoreFrame(it, registration) }
                    ?: notifyUser("No frame available yet.")
            }
        }
    }

    private fun scoreFrame(frame: LumaFrame, regIn: TargetRegistration?) {
        val reg = regIn ?: return
        val rules = currentRules()
        val detector = live

        val holes = if (detector != null && detector.isArmed) {
            detector.onFrame(frame)
        } else {
            notifyUser(
                "No clean reference was captured, so the target is being read from a single frame. " +
                    "That is the less reliable mode — check every hole on the Results plot."
            )
            // No luminance frame here: the analysis stream hands over the
            // detection channel already built, and re-deriving luminance from
            // it is not possible. So a shot inside the aiming mark is still
            // at risk on this path, and the single-frame warning above is the
            // honest one to give.
            if (ScaleSettings.sourceDetector())
                SourceHoleDetector.detect(
                    reg, frame, rules.gaugeDiameterMm,
                    includeMisses = ScaleSettings.scoreOutsideArea()
                )
            else HoleDetector.detectAbsolute(reg, reg.rectify(frame), rules.gaugeDiameterMm)
        }

        if (holes.isEmpty()) {
            notifyUser("No new holes found.")
            return
        }
        recordShots(holes)
    }

    private fun recordShots(holes: List<DetectedHole>) {
        val face = currentFace()
        val rules = currentRules()
        val bullet = ProfileRepository(this).getBullet()
        val sighter = binding.cbSighter.isChecked

        holes.sortedBy { it.distanceFromCentreMm }.forEach { hole ->
            val nextIndex = ScoringSession.state.shots.count { !it.sighter } + 1
            val shot = ScoringEngine.scoreHole(
                hole = hole, face = face, rules = rules, bullet = bullet,
                index = nextIndex,
                series = ScoringEngine.seriesFor(nextIndex, rules),
                sighter = sighter
            )
            ScoringSession.addShot(shot, rules)
        }
        refreshShotList()
        refreshStatus()
        val last = ScoringSession.state.shots.lastOrNull()
        if (last != null) notifyUser("Shot ${last.index}: ${last.displayValue}  (${last.clockPosition})")
    }

    private fun startAudio() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_PERMISSIONS)
            binding.cbAcoustic.isChecked = false
            return
        }
        audio.start(
            onReport = { t -> live?.noteReport(t) },
            onError = { msg -> runOnUiThread { notifyUser(msg); binding.cbAcoustic.isChecked = false } }
        )
    }

    // ------------------------------------------------------------------
    //  State
    // ------------------------------------------------------------------

    private fun refreshAfterClear() {
        live?.reset()
        refreshShotList()
        refreshStatus()
    }

    /**
     * The face to score against.
     *
     * The SPINNER first, because it is what the user can see. selectedFace is
     * kept as a fallback for the moment before the adapter is populated, but
     * it must never outrank the visible selection: when the two disagreed the
     * screen showed one face and scored against another, with nothing to
     * indicate it.
     */
    /**
     * A registration valid for [still], or null if it cannot be trusted.
     *
     * The registration was measured on the analysis stream. Rescaling it to
     * the still's pixel grid is correct only if both streams frame the same
     * scene, which is usual and NOT guaranteed. So the rescaled mapping is
     * checked against the printed rings in the still itself before it is
     * used, and a failure falls back to the analysis frame rather than
     * scoring confidently against the wrong geometry.
     */
    private fun registrationForStill(still: LumaFrame): TargetRegistration? {
        val reg = registration ?: return null
        val analysis = latestFrame.get() ?: return null
        if (analysis.width <= 0 || analysis.height <= 0) return null
        val sx = still.width.toDouble() / analysis.width
        val sy = still.height.toDouble() / analysis.height
        if (kotlin.math.abs(sx - sy) / ((sx + sy) / 2.0) > 0.02) {
            Logger.w(
                "SessionActivity",
                ("the still is %dx%d and the analysis frame %dx%d — different shapes, so the " +
                    "registration cannot simply be rescaled; using the analysis frame")
                    .format(still.width, still.height, analysis.width, analysis.height)
            )
            return null
        }
        val scaled = reg.scaledToSource(sx, sy) ?: return null
        val problem = runCatching {
            TargetGeometryCheck.verifyRings(still, scaled, currentFace())
        }.getOrNull()
        if (problem != null) {
            Logger.w(
                "SessionActivity",
                "the rescaled registration does not match the rings in the still ($problem); " +
                    "falling back to the analysis frame"
            )
            return null
        }
        Logger.i(
            "SessionActivity",
            "scoring a %dx%d still through a registration rescaled by %.3f from %dx%d"
                .format(still.width, still.height, sx, analysis.width, analysis.height)
        )
        return scaled
    }

    /**
     * Takes a full-resolution photograph and hands it back as a frame.
     *
     * Asynchronous, because the sensor has to be read out — a still is not
     * simply the next preview frame. [then] runs on the main thread with the
     * captured frame, or with null if the capture failed, so callers can be
     * written as though it were ordinary code.
     */
    private fun captureStill(then: (LumaFrame?, android.graphics.Bitmap?) -> Unit) {
        val cap = stills ?: run { then(null, null); return }
        cap.takePicture(
            ContextCompat.getMainExecutor(this),
            object : androidx.camera.core.ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: androidx.camera.core.ImageProxy) {
                    val bmp = runCatching {
                        val buf = image.planes[0].buffer
                        val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }.getOrNull()
                    val rotated = runCatching {
                        val deg = image.imageInfo.rotationDegrees
                        if (bmp == null || deg == 0) bmp else {
                            val m = android.graphics.Matrix().apply { postRotate(deg.toFloat()) }
                            android.graphics.Bitmap.createBitmap(
                                bmp, 0, 0, bmp.width, bmp.height, m, true
                            )
                        }
                    }.getOrNull() ?: bmp
                    image.close()
                    if (rotated == null) { then(null, null); return }
                    Logger.i("SessionActivity",
                        "captured a still at ${rotated.width}x${rotated.height}")
                    then(LumaFrame.fromBitmapForDetection(rotated), rotated)
                }

                override fun onError(exception: androidx.camera.core.ImageCaptureException) {
                    Logger.e("SessionActivity", "still capture failed", exception)
                    then(null, null)
                }
            }
        )
    }

    /** The centre of the preview, as a metering point. */
    private fun centreMeteringPoint(): androidx.camera.core.MeteringPoint? = runCatching {
        binding.preview.meteringPointFactory.createPoint(
            binding.preview.width / 2f, binding.preview.height / 2f
        )
    }.getOrNull()

    /** Locks the camera when a registration is made, once, quietly. */
    private fun lockCameraForScoring() {
        if (CameraTuning.locked || camera == null) return
        if (CameraTuning.lock(camera, centreMeteringPoint())) {
            notifyUser(
                "Exposure, white balance and focus are now locked so that later frames can be " +
                    "compared with this one. Release them under the preview if the light changes."
            )
            refreshLockButton()
        }
    }

    /**
     * Checks the card in front of the camera against the SELECTED face, and
     * says so if they disagree.
     *
     * The ring guide makes a mismatch visible, but only to someone who knows
     * to look — and the natural response to circles that will not line up is
     * to walk until they do, which cannot work and wastes the shooter's time
     * at best. At worst they conclude it is close enough, fire a card, and
     * get a score that is wrong by however much the two faces differ.
     *
     * So the app looks as well. Run once when the guide is turned on and
     * whenever the face changes, on the analysis thread, and only advisory:
     * this is a live preview, not a registration, and a card half out of
     * frame should not produce an accusation.
     */
    /**
     * Switches the session to [target], everywhere it has to be switched.
     *
     * The spinner AND the field the scorer actually reads. currentFace()
     * prefers selectedFace over the spinner, and suppressing the listener
     * with pendingTargetSelection — which is what stops a programmatic
     * change being mistaken for the user's — also stopped the only code that
     * updated selectedFace. So the spinner showed the identified face while
     * every score was computed against the previous one, silently. Both are
     * set here, together, and both callers go through this one function so
     * they cannot drift apart again.
     *
     * Returns false when the face is not in the list, or is already current.
     */
    private fun adoptFace(target: TargetFace): Boolean {
        val idx = faces.indexOfFirst { it.id == target.id }
        if (idx < 0) return false
        val previous = runCatching { currentFace() }.getOrNull()
        if (previous?.id == target.id) return false
        if (previous != null) rejectedFaceIds.add(previous.id)
        pendingTargetSelection = idx
        binding.spTarget.setSelection(idx)
        selectedFace = faces[idx]
        refreshGuide()
        TargetRepository(this).setActiveFace(target.id)
        // The reference was built against the old face, so it is now scaled
        // by the wrong number of millimetres per pixel and must be rebuilt.
        registration = null
        live = null
        refreshStatus()
        return true
    }

    private fun checkFaceAgainstView() {
        if (ScaleSettings.aimGuide() == com.rfsat.sts.ui.AimGuide.NONE) return
        val frame = latestFrame.get() ?: return
        val face = runCatching { currentFace() }.getOrNull() ?: return
        val all = runCatching { TargetRepository(this).allFaces() }.getOrNull() ?: return
        if (faceCheckRunning) return

        // Do not compete with live detection. While a string is being scored
        // the frames belong to the detector, and a second full ring fit on the
        // same thread would drop frames — and a dropped frame is a shot the
        // persistence rule never sees.
        if (live?.isArmed == true) return

        val now = System.currentTimeMillis()
        if (now - lastFaceCheckMs < FACE_CHECK_INTERVAL_MS) return
        lastFaceCheckMs = now

        faceCheckRunning = true
        if (binding.crosshair.match == com.rfsat.sts.ui.GuideMatch.UNKNOWN) {
            binding.crosshair.match = com.rfsat.sts.ui.GuideMatch.CHECKING
        }
        analysisExecutor.execute {
            var state = com.rfsat.sts.ui.GuideMatch.UNKNOWN
            var adopt: TargetFace? = null
            val complaint = runCatching {
                val fit = RingFinder.find(frame) ?: return@runCatching null
                if (fit.confidence < 0.35) return@runCatching null
                val markPx = fit.shape?.model?.semiMajorPx ?: 0.0
                if (markPx <= 0.0) return@runCatching null
                val ratio = TargetGeometryCheck.faceMismatch(face, markPx, fit.pitchPx, all)
                val ranked = RingFinder.identify(
                    fit, markPx, all, currentRules().distanceM, face.id
                ).firstOrNull()
                val named = if (ranked != null && ranked.face.id != face.id &&
                    ranked.relativeError < IDENTIFY_TOLERANCE
                ) "The card in front of the camera looks like ${ranked.face.name}, not ${face.name}."
                else null
                val problem = ratio ?: named

                // ADOPT IT, rather than asking the user to go and do it.
                //
                // Telling someone their face is wrong and leaving it wrong is
                // the worst of both: the guide keeps drawing rings that cannot
                // line up, registration keeps scaling against the wrong card,
                // and no hits are found. Import has identified the face on
                // load since 1.22.0; the camera did not, so the same card
                // scored fine from a photograph and failed from the preview.
                //
                // Only on a clear, unambiguous reading: the selected face must
                // actually be wrong, the candidate must be well inside the
                // identification tolerance, and it must not be one already
                // rejected in this session.
                if (problem != null && ranked != null && ranked.face.id != face.id &&
                    ranked.relativeError < IDENTIFY_TOLERANCE &&
                    !rejectedFaceIds.contains(ranked.face.id)
                ) {
                    adopt = ranked.face
                }
                state = if (problem == null) com.rfsat.sts.ui.GuideMatch.MATCH
                        else com.rfsat.sts.ui.GuideMatch.MISMATCH
                problem
            }.getOrNull()
            runOnUiThread {
                faceCheckRunning = false
                val chosen = adopt
                if (chosen != null && adoptFace(chosen)) {
                    // The guide now draws the adopted face, so the rings on
                    // screen change under the shooter's eyes — which is the
                    // clearest possible statement of what just happened.
                    binding.crosshair.match = com.rfsat.sts.ui.GuideMatch.MATCH
                    lastAnnouncedMatch = com.rfsat.sts.ui.GuideMatch.MATCH
                    val msg = "Target face set to %s — that is what the camera sees. %s".format(
                        chosen.name, "Change it under Target if this is wrong."
                    )
                    Logger.i("SessionActivity", msg)
                    notifyUser(msg)
                    return@runOnUiThread
                }
                binding.crosshair.match = state
                // The word in the corner is continuous; the full explanation
                // is said ONCE per change of verdict, so a shooter lining up
                // for a minute is not told the same thing thirty times.
                if (complaint != null && state != lastAnnouncedMatch) {
                    val full = complaint + " " + TargetGeometryCheck.WRONG_FACE_COST
                    Logger.w("SessionActivity", full)
                    notifyUser(full)
                }
                lastAnnouncedMatch = state
            }
        }
    }

    /** Pushes the guide choice, its size and the SELECTED FACE to the
     *  overlay. The face matters: the rings drawn are that face's own, and
     *  the whole point is that a card which does not match will not line up. */
    private fun refreshGuide() {
        binding.crosshair.guide = ScaleSettings.aimGuide()
        binding.crosshair.preserveNightVision =
            com.rfsat.sts.ui.ThemeManager.mode() == com.rfsat.sts.ui.ThemeMode.NIGHT_RED
        binding.crosshair.sizeFraction = ScaleSettings.aimGuideSize()
        binding.crosshair.face = runCatching { currentFace() }.getOrNull()
        binding.lblGuideSize.text =
            "Guide size — %d%% of the screen".format((ScaleSettings.aimGuideSize() * 100).toInt())
    }

    private fun refreshLockButton() {
        binding.btnLockCamera.text =
            if (CameraTuning.locked) "Release exposure and focus" else "Lock exposure and focus"
    }

    private fun currentFace(): TargetFace =
        faces.getOrNull(binding.spTarget.selectedItemPosition)
        ?: selectedFace
        ?: TargetRepository(this).activeFace()

    private fun currentRules(): RuleSet =
        selectedRules ?: ruleSets.getOrNull(binding.spRules.selectedItemPosition)
        ?: RuleRepository(this).activeSet()


    /**
     * Identifies the target from the picture and registers it in one step.
     *
     * Replaces "find the black mark, multiply by the selected face's ratio,
     * hope the face is right" with "fit the ring family, let the fit say
     * which face it is, and take the scale from the ring pitch". Everything
     * that went wrong before — the box on the 9 ring, the box on the 5 ring,
     * every distance half its true size — came from the old order.
     */
    private fun doIdentifyTarget() {
        val frame = detectionFrame() ?: run {
            notifyUser("No picture to work from yet.")
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

        val matches = if (mark != null)
            RingFinder.identify(
                fit, mark.radiusPx, TargetRepository(this).allFaces(),
                currentRules().distanceM, currentFace().id
            )
        else emptyList()
        val best = matches.firstOrNull()

        if (best != null && best.relativeError < IDENTIFY_TOLERANCE) {
            adoptFace(best.face)
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
            notifyUser("${face.name} has unevenly pitched rings, so a fitted pitch cannot set its scale.")
            return
        }
        registration = reg
        // Registering means the framing is settled, which is the moment to
        // stop the camera adjusting itself underneath it. Anything the camera
        // changes from here — brightness, colour, or the field of view when
        // the lens moves to refocus — invalidates the mapping just measured.
        lockCameraForScoring()
        boxMeaning = TargetRegistration.BoxMeaning.OUTER_SCORING_RING
        val outerPx = (face.outerRadiusMm / (face.ringPitchMm!! / fit.pitchPx)).toFloat()
        binding.overlay.setBoxInSource(
            (fit.centreXPx - outerPx).toFloat(), (fit.centreYPx - outerPx).toFloat(),
            (fit.centreXPx + outerPx).toFloat(), (fit.centreYPx + outerPx).toFloat()
        )
        binding.overlay.detectedMarkers = fit.ringsPx.map {
            Triple(fit.centreXPx.toFloat(), fit.centreYPx.toFloat(), it.toFloat())
        }
        reg.warnings.forEach { Logger.w("Registration", it) }
        refreshStatus()
    }


    private fun detectionFrame(): LumaFrame? = latestFrame.get()

    private fun refreshStatus() {
        val reg = registration
        val detector = live
        val shots = ScoringSession.state.shots.size
        binding.tvStatus.text = buildString {
            append(if (reg == null) "Not registered" else "Registered (${reg.rectWidth}×${reg.rectHeight} px)")
            append("  |  ")
            append(if (detector?.isArmed == true) "reference set" else "no reference")
            append("  |  ")
            append(if (liveRunning) "LIVE" else "idle")
            append("  |  $shots shot(s)")
            if (reg == null) {
                if (binding.cbCornerMode.isChecked) {
                    val n = binding.overlay.cornerCount()
                    if (n in 1..3) append("\n$n of 4 corners tapped")
                } else if (binding.overlay.hasBox()) {
                    append("\nbox around: ${boxMeaning.label.lowercase()}, ${transform.summary()}")
                }
            }
        }
    }

    private fun refreshShotList() {
        val rules = currentRules()
        val face = currentFace()
        val shots = ScoringSession.state.shots

        // The distribution updates on every accepted shot, so the shooter can
        // see the shape of the string forming rather than waiting for the
        // Results screen. It is the same computation and the same view as
        // Results uses — one implementation, so the two can never disagree.
        val dist = ShotDistribution.of(shots, face, rules)
        binding.tvDistribution.text = dist.summary()
        binding.histogram.hideEmptyBuckets = face.zones.isNotEmpty()
        binding.histogram.distribution = dist

        if (shots.isEmpty()) {
            binding.tvShots.text = "No shots recorded yet."
            return
        }
        val res = ScoringEngine.aggregate(shots, face, rules, ScoringSession.state.stageSeconds)
        binding.tvShots.text = buildString {
            appendLine("Total ${res.displayTotal}" + if (res.maxScore > 0) " / ${"%.0f".format(res.maxScore)}" else "")
            if (rules.countInnerTens) appendLine("Inner tens ${res.innerTens}")
            appendLine()
            shots.takeLast(20).forEach { s ->
                appendLine(
                    "%3s  %-5s  %6.1f, %6.1f mm  %s".format(
                        if (s.sighter) "S" else s.index.toString(),
                        s.displayValue, s.xMm, s.yMm,
                        if (s.manual) "hand" else "auto"
                    )
                )
            }
            if (shots.size > 20) append("… ${shots.size - 20} earlier shot(s) on the Results screen")
        }
    }

    // ------------------------------------------------------------------


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
        binding.overlay, binding.spRules, binding.spTarget, binding.spSource,
        binding.sbRotation, binding.sbTiltX, binding.sbTiltY
    )

    override fun onPause() {
        super.onPause()
        // Persist whatever the user typed: leaving the screen must not
        // silently discard a distance they set two minutes ago.
        val typed = binding.etDistance.text.toString().toDoubleOrNull()
        if (typed != null && typed > 0) {
            ScoringSession.setDistance(UnitsManager.inputDistanceToMeters(typed))
        }
        // A COMPLETE address is kept even if it was never started. Typing one
        // and walking to the firing point without pressing Start is ordinary;
        // losing it for that is not. Anything that is not yet a URL is left
        // alone, so a half-typed address does not replace a working one.
        val url = binding.etStreamUrl.text.toString().trim()
        if (url.startsWith("rtsp", true) || url.startsWith("http", true)) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_STREAM_URL, url)
                .putInt(KEY_STREAM_SOURCE, binding.spSource.selectedItemPosition)
                .apply()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { audio.stop() }
        runCatching { stopAllSources() }
        runCatching { analysisExecutor.shutdown() }
    }
}
