package com.rfsat.sts.detect

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
        const val EXTRA_NEW = "start_new"
        private const val REQ_PERMISSIONS = 4711

        private val SOURCES = listOf(
            "Phone camera",
            "MJPEG stream (IP or action camera)",
            "RTSP stream (digital scope)"
        )
    }

    private lateinit var binding: ActivitySessionBinding
    private lateinit var analysisExecutor: ExecutorService

    private var provider: ProcessCameraProvider? = null
    private var externalSource: FrameSource? = null
    private val audio = AudioShotDetector()

    /** Most recent analysed frame, published for the on-demand paths. */
    private val latestFrame = AtomicReference<LumaFrame?>(null)
    private var analysisSize: Size? = null

    private var registration: TargetRegistration? = null
    private var live: LiveHitDetector? = null
    private var liveRunning = false

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

        runCatching { initScreen() }.onFailure {
            Logger.e("SessionActivity", "Screen init failed", it)
            notifyUser("This screen failed to start: ${it.message}")
        }
        setupBottomNav(R.id.nav_session)
    }

    private fun initScreen() {
        faces = TargetRepository(this).allFaces()
        ruleSets = RuleRepository(this).allSets()

        // ---- rules spinner ----
        binding.spRules.adapter = adapter(ruleSets.map { "${it.name}  (${it.governingBody})" })
        val activeRules = RuleRepository(this).activeSet()
        binding.spRules.setSelection(ruleSets.indexOfFirst { it.id == activeRules.id }.coerceAtLeast(0))
        binding.spRules.onItemSelectedListener = onSelected { i ->
            val r = ruleSets.getOrNull(i) ?: return@onSelected
            selectedRules = r
            RuleRepository(this).setActiveSet(r.id)
            // Selecting a course of fire also selects its face and distance:
            // those three are a single decision, and making the user restate
            // it twice is how mismatched sessions get recorded.
            faces.indexOfFirst { it.id == r.targetFaceId }.takeIf { it >= 0 }?.let {
                binding.spTarget.setSelection(it)
            }
            binding.etDistance.setText(fmt(UnitsManager.displayDistance(r.distanceM)))
            refreshStatus()
        }

        // ---- target spinner ----
        binding.spTarget.adapter = adapter(faces.map { it.name + if (it.verified) "" else "  (unverified)" })
        val activeFace = TargetRepository(this).activeFace()
        binding.spTarget.setSelection(faces.indexOfFirst { it.id == activeFace.id }.coerceAtLeast(0))
        binding.spTarget.onItemSelectedListener = onSelected { i ->
            val f = faces.getOrNull(i) ?: return@onSelected
            selectedFace = f
            TargetRepository(this).setActiveFace(f.id)
            registration = null   // the face changed; the old mapping is void
            live = null
            refreshStatus()
        }

        binding.lblDistance.text = "Distance (${UnitsManager.distanceUnitLabel()})"

        // ---- frame source ----
        binding.spSource.adapter = adapter(SOURCES)
        binding.spSource.onItemSelectedListener = onSelected { i ->
            binding.etStreamUrl.visibility = if (i == 0) View.GONE else View.VISIBLE
            binding.preview.visibility = if (i == 0) View.VISIBLE else View.GONE
            binding.streamView.visibility = if (i == 2) View.VISIBLE else View.GONE
            stopAllSources()
            if (i == 0) startCameraIfPermitted()
        }

        // ---- buttons ----
        binding.overlay.onCornersChanged = { refreshStatus() }
        binding.btnRegister.setOnClickListener { doRegister() }
        binding.btnUndoCorner.setOnClickListener { binding.overlay.undoCorner() }
        binding.btnReference.setOnClickListener { doSetReference() }
        binding.btnLive.setOnClickListener { toggleLive() }
        binding.btnScoreNow.setOnClickListener { doScoreNow() }
        binding.btnUndoShot.setOnClickListener {
            ScoringSession.undoLast(); refreshShotList(); refreshStatus()
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

        startCameraIfPermitted()
        refreshShotList()
        refreshStatus()
    }

    private fun startFreshSession() {
        val f = currentFace()
        val r = currentRules()
        ScoringSession.startNew(f, r, r.distanceM)
    }

    // ------------------------------------------------------------------
    //  Camera
    // ------------------------------------------------------------------

    private fun startCameraIfPermitted() {
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
                                    Size(1920, 1080),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                )
                            ).build()
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()

                analysis.setAnalyzer(analysisExecutor) { image ->
                    try {
                        val frame = LumaFrame.fromImageProxy(image)
                        if (frame != null) onFrame(frame)
                    } catch (t: Throwable) {
                        Logger.e("SessionActivity", "Frame analysis failed", t)
                    } finally {
                        image.close()
                    }
                }

                p.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                Logger.i("SessionActivity", "Camera bound")
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

    private fun startExternalSource() {
        val url = binding.etStreamUrl.text.toString().trim()
        if (url.isEmpty()) { notifyUser("Enter the stream address first."); return }
        val src = when (binding.spSource.selectedItemPosition) {
            1 -> MjpegFrameSource(url)
            2 -> RtspFrameSource(url).also { rtsp ->
                val tex = binding.streamView.surfaceTexture
                if (tex != null) rtsp.attachTexture(tex) { binding.streamView.bitmap }
            }
            else -> return
        }
        externalSource = src
        src.start(
            onFrame = { onFrame(it) },
            onError = { msg -> runOnUiThread { notifyUser(msg) } }
        )
        Logger.i("SessionActivity", "External source started: ${src.label}")
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

    private fun doRegister() {
        val corners = binding.overlay.cornersInSource()
        if (corners == null) {
            notifyUser("Tap all four corners of the card first — ${binding.overlay.cornerCount()} of 4 so far.")
            return
        }
        val face = currentFace()
        val rules = currentRules()
        val reg = TargetRegistration.fromCardCorners(face, corners, rules.gaugeDiameterMm)
        if (reg == null) {
            notifyUser("Those four taps do not define a quadrilateral. Tap the corners in order, going clockwise.")
            return
        }
        registration = reg
        live = LiveHitDetector(reg, rules.gaugeDiameterMm)
        ScoringSession.saveRegistrationCorners(corners)
        reg.warnings.forEach { Logger.w("SessionActivity", it) }
        if (reg.warnings.isNotEmpty()) notifyUser(reg.warnings.joinToString("\n\n"))
        else notifyUser("Registered. Capture the clean target next, then start live detection.")
        refreshStatus()
    }

    private fun doSetReference() {
        val reg = registration ?: run { notifyUser("Register the target first."); return }
        val frame = latestFrame.get() ?: run { notifyUser("No frame yet — is the source running?"); return }
        val detector = live ?: LiveHitDetector(reg, currentRules().gaugeDiameterMm).also { live = it }
        detector.setReference(frame)
        notifyUser("Reference captured. Everything that appears from now on is treated as a shot.")
        refreshStatus()
    }

    private fun toggleLive() {
        val detector = live
        if (detector == null || !detector.isArmed) {
            notifyUser("Capture the clean target as a reference first.")
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
        val reg = registration ?: run { notifyUser("Register the target first."); return }
        val frame = latestFrame.get() ?: run { notifyUser("No frame available yet."); return }
        val rules = currentRules()
        val detector = live

        val holes = if (detector != null && detector.isArmed) {
            detector.onFrame(frame)
        } else {
            notifyUser(
                "No clean reference was captured, so the target is being read from a single frame. " +
                    "That is the less reliable mode — check every hole on the Results plot."
            )
            HoleDetector.detectAbsolute(reg, reg.rectify(frame), rules.gaugeDiameterMm)
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

    private fun currentFace(): TargetFace =
        selectedFace ?: faces.getOrNull(binding.spTarget.selectedItemPosition)
        ?: TargetRepository(this).activeFace()

    private fun currentRules(): RuleSet =
        selectedRules ?: ruleSets.getOrNull(binding.spRules.selectedItemPosition)
        ?: RuleRepository(this).activeSet()

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
            if (reg == null && binding.overlay.cornerCount() in 1..3) {
                append("\n${binding.overlay.cornerCount()} of 4 corners tapped")
            }
        }
    }

    private fun refreshShotList() {
        val rules = currentRules()
        val shots = ScoringSession.state.shots
        if (shots.isEmpty()) {
            binding.tvShots.text = "No shots recorded yet."
            return
        }
        val res = ScoringEngine.aggregate(shots, currentFace(), rules, ScoringSession.state.stageSeconds)
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
        listOf(binding.overlay, binding.spRules, binding.spTarget, binding.spSource)

    override fun onPause() {
        super.onPause()
        // Persist whatever the user typed: leaving the screen must not
        // silently discard a distance they set two minutes ago.
        val typed = binding.etDistance.text.toString().toDoubleOrNull()
        if (typed != null && typed > 0) {
            ScoringSession.setDistance(UnitsManager.inputDistanceToMeters(typed))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { audio.stop() }
        runCatching { stopAllSources() }
        runCatching { analysisExecutor.shutdown() }
    }
}
