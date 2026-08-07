package com.rfsat.sts.detect

import com.rfsat.sts.log.Logger
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URL

/**
 * Asks a Wi-Fi camera what it will answer, and writes down every reply.
 *
 * WHY A PROBE AND NOT A SETTINGS SCREEN. A Tactacam 5.0 has a dozen settings
 * worth fixing before a string — zoom, exposure compensation, white balance,
 * mains frequency, the red dot — and setting them from the app would make the
 * stream predictable in a way that matters for scoring. None of it is
 * documented: Tactacam publishes no API, and its specification sheet does not
 * even carry a field of view.
 *
 * Writing control code against a guess is how an app comes to send commands
 * that quietly do nothing, or worse, something else. So this sends only
 * harmless questions — GETs and an RTSP OPTIONS, nothing that sets anything —
 * and records what came back. One run against the real camera turns a guess
 * into a protocol.
 *
 * WHAT IT KNOWS TO ASK. The families that cover almost every action camera of
 * this generation:
 *
 *   Novatek CGI      http://host/?custom=1&cmd=NNNN — the NT96660 family and
 *                    everything built on it. Answers XML.
 *   Ambarella        a JSON socket on TCP 7878.
 *   Plain HTTP       a root page, a status endpoint, a cgi-bin directory:
 *                    enough to identify the web stack even when the commands
 *                    are unknown.
 *   ONVIF            /onvif/device_service, which if present brings a whole
 *                    standard with it.
 *   RTSP OPTIONS     the camera already speaks this; the methods it lists say
 *                    whether SET_PARAMETER is available, which is the
 *                    standards-based way to change a setting.
 *
 * Everything is logged with the exact request, so a reply nobody expected is
 * still evidence.
 */
object CameraProbe {

    private const val TAG = "CameraProbe"
    private const val CONNECT_MS = 2500
    private const val READ_MS = 2500
    /** Enough of a reply to identify it; a camera that answers with a page of
     *  XML should not fill the log with it. */
    private const val BODY_CHARS = 400

    data class Finding(val what: String, val detail: String, val promising: Boolean)

    /** Ports worth knocking on, and what an answer there would mean. */
    private val PORTS = listOf(
        80 to "HTTP",
        8080 to "HTTP (alternate)",
        7878 to "Ambarella control socket",
        554 to "RTSP",
        3333 to "vendor control"
    )

    private val HTTP_PATHS = listOf(
        "/?custom=1&cmd=3012",          // Novatek: query the setting menu
        "/?custom=1&cmd=9800",          // Novatek: firmware and model
        "/",
        "/index.html",
        "/status",
        "/cgi-bin/",
        "/onvif/device_service"
    )

    /**
     * Runs everything and returns what was found. Blocking — the caller runs
     * it on a worker; nothing here belongs on the main thread.
     */
    fun run(rtspUrl: String): List<Finding> {
        val host = runCatching { URI(rtspUrl).host }.getOrNull()
            ?: return listOf(Finding("address", "could not read a host out of $rtspUrl", false))
        Logger.i(TAG, "probing $host — questions only, nothing is changed on the camera")
        val out = ArrayList<Finding>()

        val open = ArrayList<Int>()
        for ((port, meaning) in PORTS) {
            val ms = timeToConnect(host, port)
            if (ms >= 0) {
                open += port
                Logger.i(TAG, "port $port open ($meaning) in $ms ms")
                out += Finding("port $port", "$meaning, answered in $ms ms", port != 554)
            } else {
                Logger.i(TAG, "port $port closed")
            }
        }

        if (open.contains(80) || open.contains(8080)) {
            val port = if (open.contains(80)) 80 else 8080
            for (path in HTTP_PATHS) {
                val r = get("http://$host:$port$path")
                if (r != null) {
                    Logger.i(TAG, "GET $path -> ${r.first}\n${r.second}")
                    out += Finding("GET $path", "${r.first}: ${r.second.take(120)}", r.first in 200..299)
                } else {
                    Logger.i(TAG, "GET $path -> no answer")
                }
            }
        }

        if (open.contains(7878)) {
            // Ambarella's control channel speaks JSON both ways; asking for a
            // token is the documented first message and changes nothing.
            val reply = ask(host, 7878, "{\"msg_id\":257,\"token\":0}")
            Logger.i(TAG, "Ambarella socket said: ${reply ?: "nothing"}")
            if (reply != null) out += Finding("Ambarella socket", reply.take(160), true)
        }

        rtspOptions(host)?.let {
            Logger.i(TAG, "RTSP OPTIONS -> $it")
            out += Finding("RTSP OPTIONS", it, it.contains("SET_PARAMETER"))
        }

        if (out.none { it.promising }) {
            Logger.i(TAG, "nothing answered a control question; this camera may only be " +
                "configurable from its own app or its own buttons")
        }
        return out
    }

    private fun timeToConnect(host: String, port: Int): Long {
        val began = System.currentTimeMillis()
        return runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), CONNECT_MS)
                System.currentTimeMillis() - began
            }
        }.getOrDefault(-1L)
    }

    private fun get(url: String): Pair<Int, String>? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_MS
            readTimeout = READ_MS
            setRequestProperty("user-agent", "STS")
        }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()
        code to body.replace(Regex("\\s+"), " ").take(BODY_CHARS)
    }.getOrNull()

    private fun ask(host: String, port: Int, message: String): String? = runCatching {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), CONNECT_MS)
            s.soTimeout = READ_MS
            s.getOutputStream().write(message.toByteArray())
            s.getOutputStream().flush()
            val buf = ByteArray(2048)
            val n = s.getInputStream().read(buf)
            if (n <= 0) null else String(buf, 0, n).replace(Regex("\\s+"), " ").take(BODY_CHARS)
        }
    }.getOrNull()

    /** The one question this app can already ask in the camera's own
     *  language. SET_PARAMETER in the reply would be the standards-based way
     *  to change a setting, and its absence is worth knowing too. */
    private fun rtspOptions(host: String): String? = runCatching {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, 554), CONNECT_MS)
            s.soTimeout = READ_MS
            s.getOutputStream().write(
                "OPTIONS rtsp://$host:554 RTSP/1.0\r\nCSeq: 1\r\nUser-Agent: STS\r\n\r\n".toByteArray()
            )
            s.getOutputStream().flush()
            val buf = ByteArray(2048)
            val n = s.getInputStream().read(buf)
            if (n <= 0) null else String(buf, 0, n).replace(Regex("\\s+"), " ").take(BODY_CHARS)
        }
    }.getOrNull()
}
