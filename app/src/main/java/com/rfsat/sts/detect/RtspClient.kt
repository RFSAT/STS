package com.rfsat.sts.detect

import android.media.MediaCodec
import android.media.MediaFormat
import android.net.Network
import android.util.Base64
import android.view.Surface
import com.rfsat.sts.log.Logger
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * An RTSP/H.264 client written out by hand, because the library one could not
 * be made to work against the camera people actually own.
 *
 * WHY NOT ExoPlayer, WHICH THIS REPLACES. Three releases were spent on it and
 * it never delivered a frame from a Tactacam 5.0 whose stream VLC plays
 * without complaint. It offers no way to see what the server answered, so
 * each failure had to be guessed at. This is the same design already proven
 * in VTB against the same class of camera — a digital scope or an action
 * camera running an RTSP service on its own access point — and its whole
 * handshake is written to the log, so the next failure is diagnosed by
 * reading rather than by guessing.
 *
 * RTP OVER TCP, INTERLEAVED, ONLY. One socket, no inbound UDP for an access
 * point to drop, and no packet loss to conceal itself as a corrupt frame.
 * The latency cost does not matter here: the card is not going anywhere.
 *
 * THE PATH IS PROBED. Cameras of this kind publish no documentation and each
 * firmware puts the stream somewhere different, so DESCRIBE is sent to each
 * of [PATH_CANDIDATES] until one answers 200 — which is also why an address
 * with no path at all works here and is refused by stricter clients.
 *
 * Decoded straight to the caller's Surface. Nothing is muxed, nothing is
 * written to storage: this is a viewfinder, not a recorder.
 */
class RtspClient(
    private val urlBase: String,
    /** The camera's own Wi-Fi, when it could be pinned. Null means default
     *  routing, which fails whenever mobile data is on — see [RtspFrameSource]. */
    private val network: Network?,
    private val surface: Surface,
    private val onStatus: (String) -> Unit,
    /** Called after each frame is rendered, so the caller can tell "no
     *  picture" from "a picture that could not be copied". */
    private val onRendered: () -> Unit
) {

    companion object {
        private const val TAG = "RtspClient"
        /** Empty first: an address the shooter took from VLC usually carries
         *  the right path already, and asking for it verbatim is both faster
         *  and more likely to be right than any guess. */
        val PATH_CANDIDATES = listOf(
            "", "/", "/stream0", "/live", "/video0", "/h264", "/stream1", "/ch0", "/main"
        )
        private const val TIMEOUT_MS = 6000
    }

    private val running = AtomicBoolean(false)
    private var socket: Socket? = null
    private var thread: Thread? = null
    private var decoder: MediaCodec? = null
    private val decInfo = MediaCodec.BufferInfo()

    @Volatile var framesDecoded = 0; private set
    @Volatile var bytesRead = 0L; private set
    @Volatile var lastError: String? = null; private set

    val isRunning: Boolean get() = running.get()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread({
            runCatching { session() }.onFailure { fail("stream error: ${it.javaClass.simpleName}: ${it.message}") }
        }, "sts-rtsp-client").also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running.set(false)
        runCatching { socket?.close() }
        thread?.join(2000)
        thread = null
    }

    private fun fail(msg: String) {
        lastError = msg
        Logger.w(TAG, msg)
        onStatus(msg)
        running.set(false)
    }

    // ---------------- the RTSP conversation ----------------

    private fun session() {
        val uri = URI(if (urlBase.startsWith("rtsp://")) urlBase else "rtsp://$urlBase")
        val host = uri.host ?: return fail("that is not an rtsp://host[:port]/path address")
        val port = if (uri.port > 0) uri.port else 554
        Logger.i(TAG, "connecting to $host:$port over ${if (network != null) "the camera's Wi-Fi" else "the default route"}")
        val s = (network?.socketFactory?.createSocket() ?: Socket()).apply {
            soTimeout = TIMEOUT_MS
            connect(InetSocketAddress(host, port), TIMEOUT_MS)
            tcpNoDelay = true
        }
        socket = s
        Logger.i(TAG, "connected")
        onStatus("connected; asking what it streams")
        val input = DataInputStream(BufferedInputStream(s.getInputStream(), 1 shl 16))
        val output = s.getOutputStream()
        var cseq = 1

        val givenPath = uri.path?.takeIf { it.isNotBlank() && it != "/" }
        val candidates = if (givenPath != null) listOf(givenPath) else PATH_CANDIDATES
        var sdp: String? = null
        var contentBase = ""
        for (path in candidates) {
            if (!running.get()) return
            val url = "rtsp://$host:$port$path"
            val resp = request(output, input, "DESCRIBE $url RTSP/1.0", cseq++, "Accept: application/sdp")
                ?: return fail("no answer to DESCRIBE — connected, but it is not speaking RTSP")
            Logger.i(TAG, "DESCRIBE ${if (path.isEmpty()) "(no path)" else path} -> ${resp.statusLine}")
            if (resp.status == 200) {
                sdp = resp.body
                contentBase = resp.header("content-base") ?: url
                onStatus("stream found at ${if (path.isBlank()) "/" else path}")
                break
            }
        }
        val sdpText = sdp ?: return fail(
            "no path answered DESCRIBE. Tried: ${candidates.joinToString { if (it.isEmpty()) "(none)" else it }}. " +
                "Use the address VLC shows under Tools > Codec information."
        )
        Logger.i(TAG, "SDP:\n$sdpText")

        val (control, spsPps) = parseSdp(sdpText, contentBase)
            ?: return fail("the stream has no H.264 video track — the log holds the SDP it sent")

        val setup = request(
            output, input, "SETUP $control RTSP/1.0", cseq++,
            "Transport: RTP/AVP/TCP;unicast;interleaved=0-1"
        ) ?: return fail("no answer to SETUP")
        Logger.i(TAG, "SETUP -> ${setup.statusLine}")
        if (setup.status != 200) return fail("SETUP refused: ${setup.statusLine}")
        val sessionId = setup.header("session")?.substringBefore(';')?.trim() ?: ""

        val play = request(
            output, input, "PLAY $contentBase RTSP/1.0", cseq++,
            "Session: $sessionId", "Range: npt=0.000-"
        ) ?: return fail("no answer to PLAY")
        Logger.i(TAG, "PLAY -> ${play.statusLine}")
        if (play.status != 200) return fail("PLAY refused: ${play.statusLine}")
        onStatus("playing")

        try {
            readLoop(input, spsPps)
        } finally {
            runCatching {
                write(output, "TEARDOWN $contentBase RTSP/1.0\r\nCSeq: $cseq\r\nSession: $sessionId\r\n\r\n")
            }
            runCatching { s.close() }
            runCatching { decoder?.stop(); decoder?.release() }
            decoder = null
            Logger.i(TAG, "stopped after $framesDecoded frames, ${bytesRead / 1024} KiB")
        }
    }

    private class Resp(
        val statusLine: String, val status: Int,
        val headers: Map<String, String>, val body: String
    ) {
        fun header(name: String) = headers[name.lowercase()]
    }

    private fun write(out: OutputStream, text: String) { out.write(text.toByteArray()); out.flush() }

    private fun request(
        out: OutputStream, inp: DataInputStream, line: String, cseq: Int, vararg extra: String
    ): Resp? {
        write(out, line + "\r\nCSeq: $cseq\r\nUser-Agent: STS\r\n" + extra.joinToString("") { it + "\r\n" } + "\r\n")
        return runCatching {
            val head = StringBuilder()
            while (true) {
                val b = inp.read()
                if (b < 0) return null
                // A camera may already be sending interleaved data when the
                // reply comes; skip whole packets rather than mistaking their
                // bytes for headers.
                if (b == '$'.code && head.isEmpty()) {
                    inp.read(); val len = inp.readUnsignedShort(); inp.skipBytes(len); continue
                }
                head.append(b.toChar())
                if (head.endsWith("\r\n\r\n")) break
                if (head.length > 16384) return null
            }
            val lines = head.toString().split("\r\n").filter { it.isNotBlank() }
            val status = lines.first().split(" ").getOrNull(1)?.toIntOrNull() ?: 0
            val headers = lines.drop(1).mapNotNull {
                val i = it.indexOf(':')
                if (i < 0) null else it.substring(0, i).trim().lowercase() to it.substring(i + 1).trim()
            }.toMap()
            val clen = headers["content-length"]?.toIntOrNull() ?: 0
            val body = if (clen > 0) ByteArray(clen).also { inp.readFully(it) }.toString(Charsets.UTF_8) else ""
            Resp(lines.first(), status, headers, body)
        }.getOrNull()
    }

    /** Returns the control URL and, when the SDP carries them, the parameter
     *  sets the decoder needs before it can start. */
    private fun parseSdp(sdp: String, base: String): Pair<String, Pair<ByteArray, ByteArray>?>? {
        var inVideo = false
        var control: String? = null
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        for (raw in sdp.lines()) {
            val line = raw.trim()
            if (line.startsWith("m=")) inVideo = line.startsWith("m=video")
            if (!inVideo) continue
            if (line.startsWith("a=control:")) {
                val c = line.removePrefix("a=control:").trim()
                control = if (c.startsWith("rtsp://")) c else base.trimEnd('/') + "/" + c.trimStart('/')
            }
            Regex("sprop-parameter-sets=([^;\\s]+)").find(line)?.groupValues?.get(1)?.let { sprop ->
                val parts = sprop.split(",")
                if (parts.isNotEmpty()) sps = runCatching { Base64.decode(parts[0], Base64.DEFAULT) }.getOrNull()
                if (parts.size > 1) pps = runCatching { Base64.decode(parts[1], Base64.DEFAULT) }.getOrNull()
            }
        }
        val ctl = control ?: return null
        val s = sps
        val p = pps
        return Pair(ctl, if (s != null && p != null) Pair(s, p) else null)
    }

    // ---------------- RTP -> access units -> decoder ----------------

    private fun readLoop(inp: DataInputStream, sdpSpsPps: Pair<ByteArray, ByteArray>?) {
        var sps = sdpSpsPps?.first
        var pps = sdpSpsPps?.second
        val start = byteArrayOf(0, 0, 0, 1)
        val au = ArrayList<ByteArray>()
        var auTs = 0L
        var firstTs = -1L
        var lastTs = 0L
        var tsHigh = 0L
        var fuBuf: java.io.ByteArrayOutputStream? = null
        var lastLog = System.currentTimeMillis()

        fun startDecoderIfReady() {
            if (decoder != null || sps == null || pps == null) return
            val dims = SpsDimensions.of(sps!!) ?: Pair(1280, 720)
            runCatching {
                val fmt = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_AVC, dims.first, dims.second
                ).apply {
                    setByteBuffer("csd-0", ByteBuffer.wrap(start + sps!!))
                    setByteBuffer("csd-1", ByteBuffer.wrap(start + pps!!))
                }
                decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                    configure(fmt, surface, null, 0)
                    start()
                }
                Logger.i(TAG, "decoder started at ${dims.first} x ${dims.second}")
                onStatus("decoding ${dims.first} x ${dims.second}")
            }.onFailure { fail("this phone would not start an H.264 decoder: ${it.message}") }
        }

        fun flush() {
            if (au.isEmpty()) return
            startDecoderIfReady()
            val dec = decoder
            if (dec != null) {
                var size = 0
                var key = false
                au.forEach { size += 4 + it.size; if ((it[0].toInt() and 0x1F) == 5) key = true }
                val buf = ByteBuffer.allocate(size)
                au.forEach { buf.put(start); buf.put(it) }
                buf.flip()
                if (firstTs < 0) firstTs = auTs
                // Nothing is decoded before the first keyframe: feeding a
                // decoder mid-picture produces green rubbish that looks like
                // a broken camera.
                if (framesDecoded > 0 || key) {
                    feed(dec, buf, size, (auTs - firstTs) * 1000 / 90)
                }
            }
            au.clear()
        }

        while (running.get()) {
            val b = try { inp.read() } catch (t: Throwable) { break }
            if (b < 0) break
            if (b != '$'.code) continue          // keepalive text between packets
            inp.read()                           // channel
            val len = inp.readUnsignedShort()
            val pkt = ByteArray(len)
            inp.readFully(pkt)
            bytesRead += len + 4
            if (len < 13) continue
            val ts32 = ((pkt[4].toLong() and 0xFF) shl 24) or ((pkt[5].toLong() and 0xFF) shl 16) or
                ((pkt[6].toLong() and 0xFF) shl 8) or (pkt[7].toLong() and 0xFF)
            if (ts32 < (lastTs and 0xFFFFFFFFL) && ((lastTs and 0xFFFFFFFFL) - ts32) > 0x7FFFFFFF) {
                tsHigh += 1L shl 32
            }
            val ts = tsHigh or ts32
            val csrc = pkt[0].toInt() and 0x0F
            var off = 12 + csrc * 4
            if ((pkt[0].toInt() and 0x10) != 0) {
                val extLen = ((pkt[off + 2].toInt() and 0xFF) shl 8) or (pkt[off + 3].toInt() and 0xFF)
                off += 4 + extLen * 4
            }
            if (off >= len) continue
            if (ts != auTs && au.isNotEmpty()) flush()
            auTs = ts; lastTs = ts

            when (val nalType = pkt[off].toInt() and 0x1F) {
                in 1..23 -> {
                    val nal = pkt.copyOfRange(off, len)
                    when (nalType) { 7 -> sps = nal; 8 -> pps = nal; else -> au.add(nal) }
                }
                24 -> {                            // several NALs in one packet
                    var p = off + 1
                    while (p + 2 <= len) {
                        val sz = ((pkt[p].toInt() and 0xFF) shl 8) or (pkt[p + 1].toInt() and 0xFF)
                        p += 2
                        if (p + sz > len) break
                        val nal = pkt.copyOfRange(p, p + sz)
                        when (nal[0].toInt() and 0x1F) { 7 -> sps = nal; 8 -> pps = nal; else -> au.add(nal) }
                        p += sz
                    }
                }
                28 -> {                            // one NAL across several packets
                    val fu = pkt[off + 1].toInt()
                    if ((fu and 0x80) != 0) {
                        fuBuf = java.io.ByteArrayOutputStream().apply {
                            write((pkt[off].toInt() and 0xE0) or (fu and 0x1F))
                        }
                    }
                    fuBuf?.write(pkt, off + 2, len - off - 2)
                    if ((fu and 0x40) != 0 && fuBuf != null) {
                        val nal = fuBuf!!.toByteArray(); fuBuf = null
                        when (nal[0].toInt() and 0x1F) { 7 -> sps = nal; 8 -> pps = nal; else -> au.add(nal) }
                    }
                }
            }
            val now = System.currentTimeMillis()
            if (now - lastLog > 5000) {
                lastLog = now
                Logger.i(TAG, "$framesDecoded frames, ${bytesRead / 1024} KiB")
            }
        }
        flush()
    }

    private fun feed(dec: MediaCodec, annexB: ByteBuffer, size: Int, ptsUs: Long) {
        try {
            val inIdx = dec.dequeueInputBuffer(0)
            if (inIdx >= 0) {
                dec.getInputBuffer(inIdx)?.let { ib ->
                    ib.clear()
                    annexB.rewind()
                    ib.put(annexB)
                    dec.queueInputBuffer(inIdx, 0, size, ptsUs, 0)
                }
            }
            var outIdx = dec.dequeueOutputBuffer(decInfo, 0)
            while (outIdx >= 0) {
                dec.releaseOutputBuffer(outIdx, true)   // true = draw it
                framesDecoded++
                onRendered()
                outIdx = dec.dequeueOutputBuffer(decInfo, 0)
            }
        } catch (t: Throwable) {
            Logger.w(TAG, "a frame was dropped: ${t.message}")
        }
    }
}
