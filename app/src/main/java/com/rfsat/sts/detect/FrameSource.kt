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
 * RTSP, by letting the platform's MediaPlayer decode into an off-screen
 * SurfaceTexture and pulling frames back out of it.
 *
 * BE HONEST ABOUT WHAT THIS IS. MediaPlayer's RTSP support is real but
 * narrow: RTP over UDP, a limited codec set, no authentication in the URL on
 * some vendor builds, and latency of a second or more. Frames are recovered
 * by reading the texture, which needs a GL context and is therefore driven
 * from the view that owns it — [attachTexture] must be called with a live
 * SurfaceTexture before [start].
 *
 * It is included because a digital scope that streams to its own phone app
 * almost always streams RTSP, and being able to score off that is worth a
 * compromised implementation. Where an MJPEG endpoint exists, prefer it.
 */
class RtspFrameSource(
    private val url: String,
    private val frameIntervalMs: Long = 200L
) : FrameSource {

    override val label: String get() = "RTSP stream — $url"

    private var player: MediaPlayer? = null
    private var texture: SurfaceTexture? = null
    private var grabber: Thread? = null
    private val running = AtomicBoolean(false)

    /** Supplies the frames; owned by the caller's TextureView. */
    private var readBitmap: (() -> Bitmap?)? = null

    override val isRunning: Boolean get() = running.get()

    /**
     * Hands over the SurfaceTexture MediaPlayer will render into, and a
     * closure that reads the current frame back. In practice the caller
     * passes a TextureView's texture and `textureView::getBitmap`, because
     * TextureView already owns the GL context this needs and re-creating one
     * here would be a second, competing context on the same surface.
     */
    fun attachTexture(surfaceTexture: SurfaceTexture, bitmapReader: () -> Bitmap?) {
        texture = surfaceTexture
        readBitmap = bitmapReader
    }

    override fun start(onFrame: (LumaFrame) -> Unit, onError: (String) -> Unit) {
        val tex = texture
        val reader = readBitmap
        if (tex == null || reader == null) {
            onError("No preview surface attached — RTSP needs a visible preview to decode into")
            return
        }
        if (running.getAndSet(true)) return
        try {
            player = MediaPlayer().apply {
                setDataSource(url)
                setSurface(Surface(tex))
                setOnErrorListener { _, what, extra ->
                    onError("RTSP error $what/$extra")
                    running.set(false)
                    true
                }
                setOnPreparedListener { it.start() }
                prepareAsync()
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
                    runCatching { onFrame(LumaFrame.fromBitmap(bmp)) }
                }
                Thread.sleep(frameIntervalMs)
            }
        }.also { it.isDaemon = true; it.name = "sts-rtsp"; it.start() }
    }

    override fun stop() {
        running.set(false)
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        grabber = null
    }
}
