package com.rfsat.sts.detect

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import com.rfsat.sts.log.Logger
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Where frames come from.
 *
 * The phone's own camera is the common case, but it is the WRONG case for
 * most of the disciplines this app covers: a 10 m air-rifle target is small
 * and close, a 300 m target is neither, and nobody is putting a phone at the
 * butts. So an external feed is a first-class input, not an afterthought —
 * a spotting camera downrange, an action camera on the frame, or a digital
 * scope's own Wi-Fi stream.
 */
interface FrameSource {
    /** Human-readable, for the source selector and the log. */
    val label: String

    /** Begins delivery. [onFrame] is called on a background thread. */
    fun start(onFrame: (LumaFrame) -> Unit, onError: (String) -> Unit)

    fun stop()

    val isRunning: Boolean
}

/**
 * Motion JPEG over HTTP: `multipart/x-mixed-replace` with a JPEG per part.
 *
 * This is the format almost every IP camera, every "phone as webcam" app and
 * most cheap downrange cameras will emit, and it needs no decoder beyond the
 * platform's own JPEG one. It is also the only external format that can be
 * implemented correctly in a few hundred lines with no dependency, which
 * matters for an app whose whole build is otherwise CameraX plus Gson.
 *
 * The parser does not trust the boundary declared in the Content-Type header.
 * Cameras get it wrong — trailing whitespace, a missing `--`, a boundary that
 * appears verbatim inside the JPEG payload — so instead it scans for the JPEG
 * markers themselves, SOI (FFD8) to EOI (FFD9). Those cannot appear
 * unescaped inside compressed data, which makes them a stronger frame
 * delimiter than anything in the MIME envelope.
 */
class MjpegFrameSource(private val url: String) : FrameSource {

    override val label: String get() = "MJPEG stream — $url"

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var connection: HttpURLConnection? = null

    override val isRunning: Boolean get() = running.get()

    override fun start(onFrame: (LumaFrame) -> Unit, onError: (String) -> Unit) {
        if (running.getAndSet(true)) return
        thread = Thread {
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 15000
                    doInput = true
                }
                connection = conn
                conn.connect()
                if (conn.responseCode !in 200..299) {
                    onError("Stream returned HTTP ${conn.responseCode}")
                    running.set(false)
                    return@Thread
                }
                BufferedInputStream(conn.inputStream, 64 * 1024).use { input ->
                    readFrames(input, onFrame, onError)
                }
            } catch (t: Throwable) {
                if (running.get()) {
                    Logger.e("MjpegFrameSource", "Stream failed", t)
                    onError("Stream failed: ${t.message ?: t.javaClass.simpleName}")
                }
            } finally {
                running.set(false)
                runCatching { connection?.disconnect() }
            }
        }.also { it.isDaemon = true; it.name = "sts-mjpeg"; it.start() }
    }

    private fun readFrames(input: InputStream, onFrame: (LumaFrame) -> Unit, onError: (String) -> Unit) {
        val buf = ByteArrayOutputStream(256 * 1024)
        var previous = -1
        var inFrame = false
        while (running.get()) {
            val b = input.read()
            if (b < 0) { onError("Stream ended"); return }
            if (!inFrame) {
                if (previous == 0xFF && b == 0xD8) {   // SOI
                    inFrame = true
                    buf.reset()
                    buf.write(0xFF); buf.write(0xD8)
                }
            } else {
                buf.write(b)
                if (previous == 0xFF && b == 0xD9) {   // EOI
                    inFrame = false
                    val bytes = buf.toByteArray()
                    val bmp = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
                    if (bmp != null) {
                        runCatching { onFrame(LumaFrame.fromBitmap(bmp)) }
                        bmp.recycle()
                    }
                }
            }
            previous = b
        }
    }

    override fun stop() {
        running.set(false)
        runCatching { connection?.disconnect() }
        thread = null
    }
}

/**
 * RTSP, decoded by [RtspClient] into the stream view, with frames read back
 * off it for scoring.
 *
 * THE DECISIVE PART IS THE NETWORK, NOT THE DECODER. A camera's own access
 * point has no internet, and Android leaves the phone's DEFAULT route on
 * mobile data — so every socket this app opened went out over the cellular
 * network and 192.168.1.1 was, correctly, unreachable. Three releases of
 * decoder work were spent on a stream that was never being contacted. The
 * fix is to ask for the Wi-Fi transport WITHOUT the internet capability and
 * bind to it: a plain TRANSPORT_WIFI request implies NET_CAPABILITY_INTERNET
 * and is never satisfied by an access point that has none, so the callback
 * simply never fires and the failure is silent.
 *
 * That is not a deduction — it is what the same author's VTB does, against
 * the same class of camera, working.
 *
 * WHAT IS LEFT FOR THIS CLASS. Requesting and releasing that network, driving
 * the client, and copying the decoded picture back off the view at the frame
 * interval the detector wants. The copy runs on the MAIN thread: off it,
 * TextureView.getBitmap() returns a blank bitmap rather than failing.
 */
class RtspFrameSource(
    private val context: android.content.Context,
    private val url: String,
    private val frameIntervalMs: Long = 200L
) : FrameSource {

    override val label: String get() = "RTSP stream — $url"

    private var client: RtspClient? = null
    private var texture: SurfaceTexture? = null
    private var surface: Surface? = null
    private var ui: android.os.Handler? = null
    private var ticker: Runnable? = null
    private var workers: java.util.concurrent.ExecutorService? = null
    private var netCallback: android.net.ConnectivityManager.NetworkCallback? = null
    private val running = AtomicBoolean(false)
    private val framesSeen = AtomicBoolean(false)

    /** A frame reached the surface. If this is set and nothing reaches the
     *  detector, the fault is the copy and not the stream. */
    private val rendered = AtomicBoolean(false)
    private val failed = AtomicBoolean(false)

    /** Supplies the frames; owned by the caller's TextureView. */
    private var readBitmap: (() -> Bitmap?)? = null

    override val isRunning: Boolean get() = running.get()

    fun attachTexture(surfaceTexture: SurfaceTexture, bitmapReader: () -> Bitmap?) {
        texture = surfaceTexture
        readBitmap = bitmapReader
    }

    /**
     * Pins the app to the camera's access point, then starts.
     *
     * Falls back to the default route after [NETWORK_WAIT_MS] rather than
     * waiting for ever — on a phone with mobile data off, default routing is
     * the camera's Wi-Fi anyway, and refusing to try would be worse than
     * trying and reporting.
     */
    private fun withCameraWifi(onReady: (android.net.Network?) -> Unit) {
        val cm = runCatching {
            context.getSystemService(android.net.ConnectivityManager::class.java)
        }.getOrNull()
        if (cm == null) { onReady(null); return }
        val started = AtomicBoolean(false)
        fun go(net: android.net.Network?) {
            if (!started.compareAndSet(false, true)) return
            if (net != null) {
                runCatching { cm.bindProcessToNetwork(net) }
                    .onSuccess { Logger.i("RtspFrameSource", "bound to the camera's Wi-Fi") }
                    .onFailure { Logger.w("RtspFrameSource", "could not bind to that network: ${it.message}") }
            } else {
                Logger.w("RtspFrameSource",
                    "no Wi-Fi network without internet was offered; using the default route, " +
                        "which fails whenever mobile data is on")
            }
            onReady(net)
        }
        val request = android.net.NetworkRequest.Builder()
            .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
            // WITHOUT this the request is never satisfied by an access point
            // that has no internet, and nothing happens at all.
            .removeCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) = go(network)
        }
        netCallback = cb
        runCatching { cm.requestNetwork(request, cb) }.onFailure {
            Logger.w("RtspFrameSource", "requestNetwork refused: ${it.message}")
            go(null)
        }
        android.os.Handler(android.os.Looper.getMainLooper())
            .postDelayed({ go(null) }, NETWORK_WAIT_MS)
    }

    override fun start(onFrame: (LumaFrame) -> Unit, onError: (String) -> Unit) {
        val tex = texture
        val reader = readBitmap
        if (tex == null || reader == null) {
            onError("No preview surface attached — RTSP needs the stream view on screen to decode into.")
            return
        }
        if (running.getAndSet(true)) return
        framesSeen.set(false); rendered.set(false); failed.set(false)
        val startedAt = System.currentTimeMillis()
        val surf = Surface(tex)
        surface = surf

        withCameraWifi { net ->
            if (!running.get()) return@withCameraWifi
            val c = RtspClient(
                urlBase = url,
                network = net,
                surface = surf,
                onStatus = { msg ->
                    Logger.i("RtspFrameSource", msg)
                    // Only a failure is put in front of the shooter; progress
                    // belongs in the log, where it can be read afterwards
                    // without interrupting anyone.
                    if (msg.contains("refused") || msg.contains("no ") || msg.contains("error")) {
                        failed.set(true)
                        onError(msg)
                    }
                },
                onRendered = { rendered.set(true) }
            )
            client = c
            c.start()
        }

        // THE READ-BACK RUNS ON THE MAIN THREAD, and that is not a style
        // choice. TextureView.getBitmap() copies out of the view's own GL
        // surface, and off the thread that owns it the copy comes back BLANK
        // rather than failing — measured in the field as frames arriving at
        // the right size and rate with "contrast between mark and paper is
        // only 0" for every one of them. A silent wrong answer, from a call
        // that looked like it was working.
        //
        // The conversion and the detection stay off it: only the copy needs
        // the UI thread, and doing anything more there would drop the
        // viewfinder's own frame rate.
        val ui = android.os.Handler(android.os.Looper.getMainLooper())
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        val busy = AtomicBoolean(false)
        val tick = object : Runnable {
            override fun run() {
                if (!running.get()) return
                if (!busy.get()) {
                    val bmp = runCatching { reader() }.getOrNull()
                    if (bmp != null && !isBlank(bmp)) {
                        framesSeen.set(true)
                        busy.set(true)
                        worker.execute {
                            runCatching { onFrame(LumaFrame.fromBitmap(bmp)) }
                            busy.set(false)
                        }
                    } else if (!framesSeen.get() && !failed.get() &&
                        System.currentTimeMillis() - startedAt > SILENT_TIMEOUT_MS
                    ) {
                        framesSeen.set(true)   // report once, then stop nagging
                        onError(diagnose())
                    }
                }
                ui.postDelayed(this, frameIntervalMs)
            }
        }
        this.ui = ui
        this.ticker = tick
        this.workers = worker
        ui.post(tick)
    }

    /**
     * Says which of the several quite different failures this was.
     *
     * THE PREVIOUS MESSAGE ASSERTED A CONNECTION. It read "Connected, but no
     * picture has arrived", and it said that whether or not anything had been
     * connected to — including with the phone on the wrong Wi-Fi entirely.
     * A message that states something the app has not established is worse
     * than no message.
     */
    private fun diagnose(): String {
        val c = client
        return when {
            c == null ->
                "The stream was never opened. The log says why."
            rendered.get() ->
                "The stream is playing and frames are being decoded, but the picture could not " +
                    "be copied out for scoring. Keep the Session tab and the stream view on screen."
            c.framesDecoded > 0 ->
                "Frames are arriving but none has been drawn yet. Give it a moment, or press " +
                    "Connect to the stream again."
            c.bytesRead > 0 ->
                "The camera is sending data but no complete picture has been decoded in " +
                    "${SILENT_TIMEOUT_MS / 1000} seconds. The log carries what it announced."
            c.lastError != null -> c.lastError!!
            else ->
                "No answer from $url in ${SILENT_TIMEOUT_MS / 1000} seconds. Check the phone is " +
                    "joined to the camera's own Wi-Fi, and use the address VLC shows under " +
                    "Tools > Codec information."
        }
    }

    /**
     * A frame with no variation in it at all is not a picture.
     *
     * The blank read-back described above returns a bitmap of the right size
     * full of one value, and passing that to the detector produces a
     * confident measurement of nothing. Nine samples across the middle is
     * enough to tell it from any real photograph of a card, and costs
     * nothing at five frames a second.
     */
    private fun isBlank(bmp: Bitmap): Boolean {
        if (bmp.width < 3 || bmp.height < 3) return true
        val first = bmp.getPixel(bmp.width / 2, bmp.height / 2)
        for (dy in -1..1) for (dx in -1..1) {
            val x = (bmp.width / 2 + dx * bmp.width / 4).coerceIn(0, bmp.width - 1)
            val y = (bmp.height / 2 + dy * bmp.height / 4).coerceIn(0, bmp.height - 1)
            if (bmp.getPixel(x, y) != first) return false
        }
        return true
    }

    override fun stop() {
        running.set(false)
        ticker?.let { t -> ui?.removeCallbacks(t) }
        ticker = null
        ui = null
        runCatching { workers?.shutdown() }
        workers = null
        runCatching { client?.stop() }
        client = null
        runCatching { surface?.release() }
        surface = null
        // The whole process was pinned to the camera's access point; leaving
        // it there would take the rest of the app off the internet.
        runCatching {
            val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
            cm?.bindProcessToNetwork(null)
            netCallback?.let { cm?.unregisterNetworkCallback(it) }
        }
        netCallback = null
    }

    private companion object {
        const val SILENT_TIMEOUT_MS = 12000L
        /** How long to wait for Android to offer the camera's access point
         *  before falling back to the default route. */
        const val NETWORK_WAIT_MS = 6000L
    }
}
