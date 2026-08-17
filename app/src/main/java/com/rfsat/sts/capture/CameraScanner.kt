package com.rfsat.sts.capture

import android.net.ConnectivityManager
import android.net.Network
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/**
 * Wide-net camera discovery. Neither TACTACAM nor ShotKam documents its
 * protocol, so rather than assume one endpoint this enumerates the things
 * action-camera Wi-Fi servers commonly expose — candidate gateway hosts, HTTP
 * ports, a Novatek CGI command set, GoPro-style paths, DCIM directory trees,
 * an Ambarella control socket and RTSP — and reports (and logs) exactly what
 * answered. The goal for now is breadth: find ANY way in, then compact it once
 * we know what the cameras actually serve.
 *
 * Runs on a background thread; the process must already be bound to the
 * camera's Wi-Fi (CaptureActivity.acquireScopeNetwork).
 */
object CameraScanner {

    data class Report(val lines: List<String>, val bestListing: String?, val mediaCount: Int)

    private val DEFAULT_HOSTS = listOf(
        "192.168.1.254", "192.168.1.1", "192.168.42.1", "192.168.0.1",
        "10.0.0.1", "192.168.42.129", "172.16.0.1", "192.168.100.1", "10.5.5.9"
    )
    private val HTTP_PORTS = listOf(80, 8080, 8192, 88, 8081, 8888)
    private val PATHS = listOf(
        "/", "/DCIM/", "/DCIM/100MEDIA/", "/DCIM/PHOTO/", "/MOVIE/", "/PHOTO/",
        "/NOVATEK/", "/NOVATEK/MOVIE/", "/tmp/SD0/", "/mnt/sd/", "/sd/", "/SD/",
        "/videos/", "/video/", "/media/", "/files/", "/storage/",
        "/gp/gpMediaList", "/gp/gpControl/status", "/cgi-bin/hi3510/param.cgi",
        "/gopro/media/list", "/videos/DCIM/", "/gopro/camera/state"
    )
    private val NOVATEK_CMDS = listOf(3001, 3015, 3016, 3025, 3026, 2001, 1001, 8001, 3012)
    private val MEDIA = Regex("""[\w/.\-]+\.(?:mp4|mov|avi|mkv|m4v|jpg|jpeg)""", RegexOption.IGNORE_CASE)
    private val HREF = Regex("""href\s*=\s*["']?([^"'>\s]+)""", RegexOption.IGNORE_CASE)

    /** Candidate hosts: the bound Wi-Fi's gateway (almost always the camera)
     *  first, then the common defaults. */
    fun candidateHosts(cm: ConnectivityManager?, net: Network?): List<String> {
        val gw = runCatching {
            val lp = cm?.getLinkProperties(net)
            lp?.routes?.firstOrNull { it.isDefaultRoute && it.gateway != null }
                ?.gateway?.hostAddress
        }.getOrNull()
        return (listOfNotNull(gw) + DEFAULT_HOSTS).distinct()
    }

    private class Probe(val code: Int, val ctype: String?, val len: Long, val hasHref: Boolean, val media: List<String>)

    fun scan(hosts: List<String>, log: (String) -> Unit): Report {
        val lines = mutableListOf<String>()
        log("=== Camera scan start: ${hosts.size} candidate hosts ===")

        // 1. Fast reachability sweep (TCP connect) across hosts/ports.
        val liveHttp = mutableListOf<Pair<String, Int>>()
        for (h in hosts.distinct()) {
            for (p in HTTP_PORTS.distinct()) {
                if (tcpOpen(h, p, 700)) { liveHttp.add(h to p); lines.add("HTTP port open: $h:$p"); log("open $h:$p") }
            }
            if (tcpOpen(h, 554, 700)) { lines.add("RTSP port open: $h:554"); log("open $h:554 (RTSP)") }
            if (tcpOpen(h, 7878, 600)) { lines.add("Ambarella socket: $h:7878"); log("open $h:7878 (Ambarella)") }
        }
        if (liveHttp.isEmpty()) {
            lines.add("No HTTP port answered. Confirm the phone is joined to the camera's Wi-Fi.")
            log("=== scan end: no HTTP ===")
            return Report(lines, null, 0)
        }

        // 2. Deep HTTP probe on the reachable host:port(s).
        var bestListing: String? = null
        var bestCount = 0
        val paths = PATHS + NOVATEK_CMDS.map { "/?custom=1&cmd=$it" }
        outer@ for ((h, p) in liveHttp) {
            val base = "http://$h" + if (p == 80) "" else ":$p"
            for (path in paths) {
                val url = base + path
                val r = httpProbe(url, log) ?: continue
                when {
                    r.media.isNotEmpty() -> {
                        lines.add("FILES: $url — ${r.media.size} media (HTTP ${r.code})")
                        if (r.media.size > bestCount) { bestCount = r.media.size; bestListing = url }
                    }
                    r.code == 200 -> lines.add(
                        "200: $url (${r.ctype ?: "?"}, ${r.len}B)" + if (r.hasHref) " [listing]" else "")
                    r.code in 301..401 -> lines.add("${r.code}: $url")
                }
            }
            if (bestListing != null) break@outer
        }
        if (bestCount == 0) lines.add("Ports answered but no media listing recognised — see the per-URL results above.")
        log("=== scan end: best=$bestListing ($bestCount media) ===")
        return Report(lines, bestListing, bestCount)
    }

    private fun tcpOpen(host: String, port: Int, timeoutMs: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs); true }
    } catch (e: Exception) { false }

    private fun httpProbe(url: String, log: (String) -> Unit): Probe? = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 1200; readTimeout = 2000; requestMethod = "GET"
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "STS")
        }
        val code = c.responseCode
        val ctype = c.contentType
        val body = runCatching {
            (if (code in 200..299) c.inputStream else c.errorStream)
                ?.use { it.readBytes().copyOf(65536).toString(Charsets.ISO_8859_1) } ?: ""
        }.getOrDefault("")
        c.disconnect()
        val media = MEDIA.findAll(body).map { it.value }.distinct().toList()
        log("  $code $url ${ctype ?: ""} media=${media.size}")
        Probe(code, ctype, body.length.toLong(), HREF.containsMatchIn(body), media)
    } catch (e: Exception) { null }
}
