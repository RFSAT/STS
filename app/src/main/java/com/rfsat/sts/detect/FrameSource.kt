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
 * RTSP, decoded by ExoPlayer into a TextureView the app then reads frames
 * back out of.
 *
 * WHY NOT MediaPlayer, WHICH THIS USED TO BE. Reported from real use: a URL
 * that plays in VLC showed nothing here. The platform MediaPlayer's RTSP
 * support is RTP over UDP and nothing else — it cannot do RTSP interleaved
 * over TCP, which is what VLC falls back to and what a great many cameras
 * offer either by preference or exclusively. Its codec set is narrower too:
 * H.265, which is now common on cheap cameras, is not decoded on that path
 * at all. And it reported failure as a pair of integers, so the one thing
 * the shooter needed — WHY nothing appeared — was the one thing it could not
 * say.
 *
 * ExoPlayer's RTSP source does TCP as well as UDP, decodes whatever the
 * device's own decoders handle, and returns errors with names.
 *
 * UDP FIRST, THEN TCP. UDP is lower latency and is what most cameras answer
 * with when asked; TCP survives access-point client isolation and the
 * firewalls that silently drop the inbound RTP. Trying one and reporting
 * failure would leave the shooter to guess which of the two their camera
 * wanted, so the fallback is automatic and is stated when it happens.
 *
 * FRAMES COME BACK OFF THE TEXTURE, which needs a live TextureView: the view
 * owns the GL context, and creating a second one on the same surface is
 * asking for trouble. [attachTexture] must therefore be called with an
 * AVAILABLE SurfaceTexture before [start] — the caller waits for it rather
 * than failing, because a TextureView that has just been made visible does
 * not have one until the next layout pass.
 */
class RtspFrameSource(
    private val context: android.content.Context,
    private val url: String,
    private val frameIntervalMs: Long = 200L
) : FrameSource {

    override val label: String get() = "RTSP stream — $url"

    private var player: androidx.media3.exoplayer.ExoPlayer? = null
    private var texture: SurfaceTexture? = null
    private var ui: android.os.Handler? = null
    private var ticker: Runnable? = null
    private var workers: java.util.concurrent.ExecutorService? = null
    private val running = AtomicBoolean(false)
    private val framesSeen = AtomicBoolean(false)
    private var triedTcp = false

    // ---- what actually happened, so a failure can be told apart from ----
    // ---- another failure. All of it is logged as it occurs.          ----
    /** The socket answered at all. Distinguishes "not on the camera's
     *  network" from every other cause, which is the distinction the
     *  reported message got wrong. */
    private val reachable = AtomicBoolean(false)
    private val probed = AtomicBoolean(false)
    /** The player reached READY: the session was negotiated and there is a
     *  track to play. */
    private val ready = AtomicBoolean(false)
    /** A frame was actually decoded and drawn to the surface. If this is set
     *  and no picture reaches the detector, the fault is the read-back. */
    private val rendered = AtomicBoolean(false)
    /** An error was reported, so the watchdog must keep quiet: it would say
     *  something vaguer about the same event. */
    private val failed = AtomicBoolean(false)

    /** Supplies the frames; owned by the caller's TextureView. */
    private var readBitmap: (() -> Bitmap?)? = null

    override val isRunning: Boolean get() = running.get()

    fun attachTexture(surfaceTexture: SurfaceTexture, bitmapReader: () -> Bitmap?) {
        texture = surfaceTexture
        readBitmap = bitmapReader
    }

    /**
     * Can anything be reached at that address at all?
     *
     * Runs before the player, on its own thread, and is the single most
     * useful line in the log: it separates "the phone is not on the camera's
     * network" from every other cause. The reported message asserted a
     * connection that had never been made, which is worse than saying
     * nothing — it sent the shooter looking at the camera's video format
     * when the phone was on the wrong Wi-Fi.
     */
    private fun probeReachable() {
        val parsed = runCatching { java.net.URI(url) }.getOrNull()
        val host = parsed?.host
        val port = parsed?.port?.takeIf { it > 0 } ?: 554
        val path = parsed?.path.orEmpty()
        Logger.i("RtspFrameSource", "address: host=$host port=$port path=" +
            (if (path.isEmpty()) "(none)" else path))
        if (path.isEmpty()) {
            // VLC is forgiving about this; a stricter server answers a
            // path-less DESCRIBE with 400 or 404. Worth saying, since the
            // shooter is the only one who can find the real path.
            Logger.i("RtspFrameSource",
                "the address has no path — if this fails, try the full one VLC shows " +
                    "under Tools > Codec information, e.g. rtsp://host:554/live")
        }
        if (host == null) {
            Logger.w("RtspFrameSource", "the address could not be parsed as rtsp://host[:port]/path")
            probed.set(true)
            return
        }
        Thread {
            val began = System.currentTimeMillis()
            val ok = runCatching {
                java.net.Socket().use { sock ->
                    sock.connect(java.net.InetSocketAddress(host, port), PROBE_TIMEOUT_MS)
                    true
                }
            }.getOrElse { e ->
                Logger.w("RtspFrameSource",
                    "$host:$port did not answer: ${e.javaClass.simpleName}: ${e.message}")
                false
            }
            if (ok) {
                Logger.i("RtspFrameSource",
                    "$host:$port answered in ${System.currentTimeMillis() - began} ms")
            }
            reachable.set(ok)
            probed.set(true)
        }.also { it.isDaemon = true; it.name = "sts-rtsp-probe" }.start()
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun open(forceTcp: Boolean, onError: (String) -> Unit) {
        val tex = texture ?: run {
            Logger.w("RtspFrameSource", "no surface to decode into; not opening")
            return
        }
        val transport = if (forceTcp) "TCP" else "UDP"
        Logger.i("RtspFrameSource", "opening over $transport, timeout ${OPEN_TIMEOUT_MS} ms")
        val factory = androidx.media3.exoplayer.rtsp.RtspMediaSource.Factory()
            .setForceUseRtpTcp(forceTcp)
            // Some cameras answer the default "Exo" agent with 4xx. A plain
            // one is what the players they were tested against send.
            .setUserAgent("STS")
            .setTimeoutMs(OPEN_TIMEOUT_MS.toLong())
        val source = factory.createMediaSource(
            androidx.media3.common.MediaItem.fromUri(url)
        )
        val p = androidx.media3.exoplayer.ExoPlayer.Builder(context).build()
        p.setVideoSurface(Surface(tex))
        p.addListener(object : androidx.media3.common.Player.Listener {
            // EVERY TRANSITION IS LOGGED. The previous version logged an
            // error and nothing else, so a stream that failed to start
            // without erroring — which is what happened — left a log saying
            // only that it had been opened.
            override fun onPlaybackStateChanged(state: Int) {
                val name = when (state) {
                    1 -> "IDLE"; 2 -> "BUFFERING"; 3 -> "READY"; 4 -> "ENDED"
                    else -> "state $state"
                }
                Logger.i("RtspFrameSource", "$transport: playback $name")
                if (state == 3) ready.set(true)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                Logger.i("RtspFrameSource", "$transport: playing=$isPlaying")
            }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                Logger.i("RtspFrameSource",
                    "$transport: video ${videoSize.width} x ${videoSize.height}")
            }

            override fun onRenderedFirstFrame() {
                // The decoder produced a picture and drew it. Anything blank
                // after this is the read-back, not the stream.
                rendered.set(true)
                Logger.i("RtspFrameSource", "$transport: first frame rendered to the surface")
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Logger.w("RtspFrameSource", "$transport failed: ${chain(error)}")
                if (!forceTcp && !triedTcp) {
                    // The commonest single cause of "it plays in VLC and not
                    // here": the camera will not serve UDP to this client.
                    triedTcp = true
                    Logger.i("RtspFrameSource", "retrying the same address over TCP")
                    runCatching { p.release() }
                    open(forceTcp = true, onError = onError)
                    return
                }
                running.set(false)
                failed.set(true)
                onError(describe(error))
            }
        })
        p.setMediaSource(source)
        p.prepare()
        p.playWhenReady = true
        player = p
    }

    /** The whole cause chain on one line. An ExoPlayer error code says the
     *  category; the cause underneath it says what the server actually did. */
    private fun chain(t: Throwable): String {
        val sb = StringBuilder()
        if (t is androidx.media3.common.PlaybackException) sb.append(t.errorCodeName).append(": ")
        var e: Throwable? = t
        var depth = 0
        while (e != null && depth < 5) {
            if (depth > 0) sb.append(" <- ")
            sb.append(e.javaClass.simpleName)
            e.message?.takeIf { it.isNotBlank() }?.let { sb.append(" (").append(it).append(")") }
            e = e.cause
            depth++
        }
        return sb.toString()
    }

    /** Turns an ExoPlayer failure into something a shooter can act on. */
    private fun describe(error: androidx.media3.common.PlaybackException): String {
        val name = error.errorCodeName
        val detail = error.cause?.message?.takeIf { it.isNotBlank() }
        return when {
            name.contains("TIMEOUT") || name.contains("IO_NETWORK") ->
                "No answer from $url. Check the phone is on the camera's own network, and " +
                    "that the address is exactly the one that plays in VLC."
            name.contains("UNAUTHORIZED") || name.contains("AUTHENTICATION") ->
                "The camera refused the connection as unauthorised. Put the user name and " +
                    "password in the address itself: rtsp://user:password@host/path"
            name.contains("DECODER") || name.contains("DECODING") ->
                "The stream was reached but this phone cannot decode it${detail?.let { " ($it)" } ?: ""}. " +
                    "If the camera can be set to H.264 rather than H.265, try that."
            else -> "The stream could not be played: $name${detail?.let { " — $it" } ?: ""}"
        }
    }

    override fun start(onFrame: (LumaFrame) -> Unit, onError: (String) -> Unit) {
        val tex = texture
        val reader = readBitmap
        if (tex == null || reader == null) {
            onError("No preview surface attached — RTSP needs the stream view on screen to decode into.")
            return
        }
        if (running.getAndSet(true)) return
        framesSeen.set(false)
        triedTcp = false
        reachable.set(false); probed.set(false)
        ready.set(false); rendered.set(false); failed.set(false)
        probeReachable()
        val startedAt = System.currentTimeMillis()
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching { open(forceTcp = false, onError = onError) }.onFailure {
                    running.set(false)
                    onError("Could not open the stream: ${it.message ?: it.javaClass.simpleName}")
                }
            }
        } catch (t: Throwable) {
            running.set(false)
            onError("Could not open the stream: ${t.message ?: t.javaClass.simpleName}")
            return
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
     * connected to — including with the phone on the wrong Wi-Fi entirely,
     * where it sent the shooter to look at the camera's video format for a
     * fault that was two rooms away. A message that states something the app
     * has not established is worse than no message.
     *
     * Each branch below is a state the app has actually observed.
     */
    private fun diagnose(): String = when {
        probed.get() && !reachable.get() ->
            "Nothing answered at $url. The phone is probably not on the camera's own Wi-Fi, " +
                "or the address or port is wrong. Nothing was connected to, so the video " +
                "format is not the question yet."
        rendered.get() ->
            "The stream is playing and frames are being decoded, but the picture could not be " +
                "copied out for scoring. Keep the Session tab and the stream view on screen."
        ready.get() ->
            "The stream was negotiated but no video has been decoded in " +
                "${SILENT_TIMEOUT_MS / 1000} seconds. The camera may be sending a format this " +
                "phone cannot decode — if it can be set to H.264 rather than H.265, try that."
        reachable.get() ->
            "$url answered, but no RTSP session was established in ${SILENT_TIMEOUT_MS / 1000} " +
                "seconds. If the address has no path, try the full one VLC shows under " +
                "Tools > Codec information — some cameras refuse a path-less request."
        else ->
            "No picture in ${SILENT_TIMEOUT_MS / 1000} seconds, and the address was never " +
                "reached. Check the phone is on the camera's Wi-Fi and that the camera is " +
                "streaming."
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
        val p = player
        player = null
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching { p?.setVideoSurface(null) }
            runCatching { p?.release() }
        }
    }

    private companion object {
        const val OPEN_TIMEOUT_MS = 8000
        const val SILENT_TIMEOUT_MS = 10000L
        /** Long enough for a phone on the camera's own access point, short
         *  enough to answer before the watchdog does. */
        const val PROBE_TIMEOUT_MS = 3000
    }
}
