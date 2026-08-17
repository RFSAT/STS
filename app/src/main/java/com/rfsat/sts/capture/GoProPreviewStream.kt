package com.rfsat.sts.capture

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import com.rfsat.sts.detect.SpsDimensions
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * GoPro (HERO9+) live PREVIEW stream. Open GoPro starts a low-resolution
 * (480p HERO9 / 720p HERO11) MPEG-TS stream over UDP 8554 on the camera's
 * Wi-Fi after GET /gopro/camera/stream/start. This reads that UDP stream,
 * demuxes the MPEG-TS transport packets to the H.264 elementary stream, and
 * decodes it to [surface] with MediaCodec. Preview only — full resolution is
 * only in the recorded file, so scoring should still use the download path.
 *
 * The caller must bind the GoPro Wi-Fi first (CameraWifi/acquireScopeNetwork).
 * All work is on its own threads; [status] receives progress for the Log.
 */
class GoProPreviewStream(
    private val surface: Surface,
    private val status: (String) -> Unit
) {
    private val running = AtomicBoolean(false)
    private var udp: DatagramSocket? = null
    private var decoder: MediaCodec? = null
    private val outInfo = MediaCodec.BufferInfo()
    private var reader: Thread? = null
    private var keepAlive: Thread? = null

    private var pmtPid = -1
    private var videoPid = -1
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private val au = ByteArrayOutputStream(1 shl 16)
    private var frames = 0L
    private val START = byteArrayOf(0, 0, 0, 1)

    fun start() {
        if (!running.compareAndSet(false, true)) return
        reader = Thread { runCatching { run() }.onFailure { status("stream error: ${it.message}") } }.also { it.start() }
        keepAlive = Thread {
            while (running.get()) { GoProClient.keepAlive { }; runCatching { Thread.sleep(2500) } }
        }.also { it.start() }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { GoProClient.streamStop { status(it) } }
        runCatching { udp?.close() }
        runCatching { decoder?.stop() }
        runCatching { decoder?.release() }
        decoder = null; udp = null
        status("stopped")
    }

    private fun run() {
        status("requesting preview stream…")
        GoProClient.streamStart { status(it) }
        val sock = DatagramSocket(8554).apply { soTimeout = 3000 }
        udp = sock
        status("listening on udp:8554")
        val buf = ByteArray(65536)
        val pkt = DatagramPacket(buf, buf.size)
        var lastData = System.currentTimeMillis()
        while (running.get()) {
            try {
                sock.receive(pkt)
            } catch (e: SocketTimeoutException) {
                if (System.currentTimeMillis() - lastData > 8000)
                    status("no data yet — confirm the phone is on the GoPro Wi-Fi")
                continue
            }
            lastData = System.currentTimeMillis()
            var off = pkt.offset
            val end = pkt.offset + pkt.length
            while (off + 188 <= end) {
                if (buf[off].toInt() and 0xFF != 0x47) { off++; continue }
                runCatching { handleTs(buf, off) }
                off += 188
            }
        }
    }

    private fun handleTs(b: ByteArray, o: Int) {
        val b1 = b[o + 1].toInt() and 0xFF
        val b2 = b[o + 2].toInt() and 0xFF
        val b3 = b[o + 3].toInt() and 0xFF
        val pusi = (b1 and 0x40) != 0
        val pid = ((b1 and 0x1F) shl 8) or b2
        val afc = (b3 shr 4) and 0x3
        var p = o + 4
        if (afc == 2) return
        if (afc == 3) { val alen = b[p].toInt() and 0xFF; p += 1 + alen }
        if (p >= o + 188) return
        when (pid) {
            0 -> parsePat(b, p, pusi)
            pmtPid -> parsePmt(b, p, pusi)
            videoPid -> if (videoPid >= 0) parseVideo(b, p, o + 188, pusi)
        }
    }

    private fun parsePat(b: ByteArray, start: Int, pusi: Boolean) {
        if (pmtPid >= 0) return
        var p = start
        if (pusi) p += (b[p].toInt() and 0xFF) + 1
        val programStart = p + 8
        if (programStart + 3 >= b.size) return
        pmtPid = ((b[programStart + 2].toInt() and 0x1F) shl 8) or (b[programStart + 3].toInt() and 0xFF)
        status("PAT: PMT pid=$pmtPid")
    }

    private fun parsePmt(b: ByteArray, start: Int, pusi: Boolean) {
        if (videoPid >= 0) return
        var p = start
        if (pusi) p += (b[p].toInt() and 0xFF) + 1
        val secLen = ((b[p + 1].toInt() and 0x0F) shl 8) or (b[p + 2].toInt() and 0xFF)
        val programInfoLen = ((b[p + 10].toInt() and 0x0F) shl 8) or (b[p + 11].toInt() and 0xFF)
        var es = p + 12 + programInfoLen
        val sectionEnd = p + 3 + secLen - 4
        while (es + 5 <= sectionEnd && es + 5 <= b.size) {
            val streamType = b[es].toInt() and 0xFF
            val ePid = ((b[es + 1].toInt() and 0x1F) shl 8) or (b[es + 2].toInt() and 0xFF)
            val esInfoLen = ((b[es + 3].toInt() and 0x0F) shl 8) or (b[es + 4].toInt() and 0xFF)
            if (streamType == 0x1B || streamType == 0x24) {
                videoPid = ePid; status("PMT: video pid=$ePid (type 0x${streamType.toString(16)})"); return
            }
            es += 5 + esInfoLen
        }
    }

    private fun parseVideo(b: ByteArray, start: Int, tsEnd: Int, pusi: Boolean) {
        if (pusi) {
            flushAu()
            var p = start
            if (p + 8 < tsEnd &&
                (b[p].toInt() and 0xFF) == 0 && (b[p + 1].toInt() and 0xFF) == 0 && (b[p + 2].toInt() and 0xFF) == 1) {
                val pesHeaderDataLen = b[p + 8].toInt() and 0xFF
                p += 9 + pesHeaderDataLen
            }
            if (p in start until tsEnd) au.write(b, p, tsEnd - p)
        } else {
            au.write(b, start, tsEnd - start)
        }
    }

    private fun flushAu() {
        if (au.size() == 0) return
        val data = au.toByteArray()
        au.reset()
        captureParamSets(data)
        ensureDecoder()
        feed(data)
    }

    private fun isStartCode(es: ByteArray, i: Int): Int {
        if (i + 3 < es.size && es[i].toInt() == 0 && es[i + 1].toInt() == 0) {
            if (es[i + 2].toInt() == 1) return 3
            if (es[i + 2].toInt() == 0 && es[i + 3].toInt() == 1) return 4
        }
        return 0
    }

    private fun captureParamSets(es: ByteArray) {
        if (sps != null && pps != null) return
        var i = 0
        while (i + 4 < es.size) {
            val sc = isStartCode(es, i)
            if (sc == 0) { i++; continue }
            val nalStart = i + sc
            val type = es[nalStart].toInt() and 0x1F
            var j = nalStart + 1
            while (j + 3 < es.size && isStartCode(es, j) == 0) j++
            val nalEnd = if (j + 3 >= es.size) es.size else j
            val nal = es.copyOfRange(nalStart, nalEnd)
            if (type == 7 && sps == null) sps = nal
            if (type == 8 && pps == null) pps = nal
            i = nalEnd
        }
    }

    private fun ensureDecoder() {
        if (decoder != null) return
        val s = sps ?: return
        val p = pps ?: return
        val dims = SpsDimensions.of(s) ?: Pair(848, 480)
        runCatching {
            val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, dims.first, dims.second).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(START + s))
                setByteBuffer("csd-1", ByteBuffer.wrap(START + p))
            }
            decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(fmt, surface, null, 0); start()
            }
            status("decoder started ${dims.first}x${dims.second}")
        }.onFailure { status("decoder init failed: ${it.message}") }
    }

    private fun feed(access: ByteArray) {
        val dec = decoder ?: return
        try {
            val inIdx = dec.dequeueInputBuffer(0)
            if (inIdx >= 0) {
                val ib = dec.getInputBuffer(inIdx)!!
                ib.clear(); ib.put(access)
                dec.queueInputBuffer(inIdx, 0, access.size, frames++ * 33_333L, 0)
            }
            var outIdx = dec.dequeueOutputBuffer(outInfo, 0)
            while (outIdx >= 0) { dec.releaseOutputBuffer(outIdx, true); outIdx = dec.dequeueOutputBuffer(outInfo, 0) }
        } catch (t: Throwable) { /* preview drops a frame; keep going */ }
    }
}
