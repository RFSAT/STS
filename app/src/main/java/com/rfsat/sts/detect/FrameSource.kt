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
    private var grabber: Thread? = null
    private val running = AtomicBoolean(false)
    private val framesSeen = AtomicBoolean(false)
    private var triedTcp = false

    /** Supplies the frames; owned by the caller's TextureView. */
    private var readBitmap: (() -> Bitmap?)? = null

    override val isRunning: Boolean get() = running.get()

    fun attachTexture(surfaceTexture: SurfaceTexture, bitmapReader: () -> Bitmap?) {
        texture = surfaceTexture
        readBitmap = bitmapReader
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun open(forceTcp: Boolean, onError: (String) -> Unit) {
        val tex = texture ?: return
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
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Logger.w("RtspFrameSource", "${if (forceTcp) "TCP" else "UDP"}: ${error.errorCodeName}")
                if (!forceTcp && !triedTcp) {
                    // The commonest single cause of "it plays in VLC and not
                    // here": the camera will not serve UDP to this client.
                    triedTcp = true
                    runCatching { p.release() }
                    open(forceTcp = true, onError = onError)
                    return
                }
                running.set(false)
                onError(describe(error))
            }
        })
        p.setMediaSource(source)
        p.prepare()
        p.playWhenReady = true
        player = p
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
        grabber = Thread {
            while (running.get()) {
                val bmp = runCatching { reader() }.getOrNull()
                if (bmp != null) {
                    framesSeen.set(true)
                    runCatching { onFrame(LumaFrame.fromBitmap(bmp)) }
                } else if (!framesSeen.get() &&
                    System.currentTimeMillis() - startedAt > SILENT_TIMEOUT_MS
                ) {
                    // SAYING NOTHING IS THE FAILURE THAT WAS REPORTED. A
                    // stream that connects and never delivers a frame looked
                    // exactly like an app that had ignored the address.
                    framesSeen.set(true)   // report once, then stop nagging
                    onError(
                        "Connected, but no picture has arrived in ${SILENT_TIMEOUT_MS / 1000} " +
                            "seconds. The stream view must be visible for frames to be decoded; " +
                            "if it is, the camera may be sending a format this phone cannot decode."
                    )
                }
                Thread.sleep(frameIntervalMs)
            }
        }.also { it.isDaemon = true; it.name = "sts-rtsp"; it.start() }
    }

    override fun stop() {
        running.set(false)
        val p = player
        player = null
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching { p?.release() }
        }
        grabber = null
    }

    private companion object {
        const val OPEN_TIMEOUT_MS = 8000
        const val SILENT_TIMEOUT_MS = 10000L
    }
}
