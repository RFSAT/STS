package com.rfsat.sts.capture

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Best-effort "download the latest clip from the camera's own Wi-Fi".
 *
 * TACTACAM and ShotKam both serve their SD card over their Wi-Fi AP — that is
 * how their own apps pull footage — but NEITHER publishes the protocol, so
 * this is a PROBE, in the same spirit as [com.rfsat.sts.detect.CameraProbe]:
 * it tries the endpoints common action-camera chipsets expose (Novatek CGI,
 * an HTTP DCIM directory listing), extracts the media-file URLs, picks the
 * newest by filename, downloads it, and LOGS every step so a single run
 * against the real camera turns a guess into a known endpoint.
 *
 * The process must already be bound to the camera's Wi-Fi network (see
 * CaptureActivity.acquireScopeNetwork) so these sockets do not leave over
 * mobile data. Hosts marked needsConfirm are best guesses pending a field
 * capture; the host is overridable in the download dialog.
 */
object CameraFileImporter {

    data class Preset(
        val name: String,
        val host: String,
        val listPaths: List<String>,
        val needsConfirm: Boolean
    )

    /** Candidate paths per preset. Novatek CarDV cams answer cmd=3015 with a
     *  file list; many others serve a plain HTTP directory. */
    val PRESETS: List<Preset> = listOf(
        Preset("Generic (Novatek)", "192.168.1.254",
            listOf("/?custom=1&cmd=3015", "/DCIM/", "/MOVIE/", "/"), false),
        Preset("TACTACAM 5.0", "192.168.1.254",
            listOf("/?custom=1&cmd=3015", "/DCIM/", "/MOVIE/", "/"), true),
        Preset("ShotKam Gen 4", "192.168.1.1",
            listOf("/DCIM/", "/video/", "/MOVIE/", "/"), true)
    )

    private val MEDIA = Regex("""[\w/.\-]+\.(?:mp4|mov|avi|mkv|m4v)""", RegexOption.IGNORE_CASE)
    private val HREF = Regex("""href\s*=\s*["']?([^"'>\s]+)""", RegexOption.IGNORE_CASE)

    /**
     * Runs on a background thread. Returns the downloaded file, or null (with a
     * logged reason). [hostOverride] wins over the preset host when non-blank.
     */
    fun downloadLatest(preset: Preset, hostOverride: String?, cacheDir: File, log: (String) -> Unit): File? {
        val url = latestUrl(preset, hostOverride, log) ?: return null
        log("Newest: $url")
        return downloadUrl(url, cacheDir, log)
    }

    /** Peek the newest media URL without downloading — used by auto-collect to
     *  detect a NEW clip (recording stopped) before pulling it. */
    fun latestUrl(preset: Preset, hostOverride: String?, log: (String) -> Unit): String? {
        val host = hostOverride?.trim()?.takeIf { it.isNotBlank() } ?: preset.host
        val base = "http://$host"
        val media = collectMedia(base, preset.listPaths, depth = 0, log)
        if (media.isEmpty()) {
            log("No media files found — the endpoint or host may differ on this camera.")
            return null
        }
        return media.maxByOrNull { it.substringAfterLast('/').uppercase() }
    }

    /** Download an exact media URL to cache. */
    fun downloadUrl(url: String, cacheDir: File, log: (String) -> Unit): File? {
        val ext = url.substringAfterLast('.', "mp4").lowercase().take(4)
        val out = File(cacheDir, "camera_latest.$ext")
        return if (download(url, out, log)) { log("Downloaded ${out.length()} bytes -> ${out.name}"); out } else null
    }

    /** Collect media URLs from the listing paths, descending one level into the
     *  greatest sub-directory if a listing yields folders but no media. */
    private fun collectMedia(base: String, paths: List<String>, depth: Int, log: (String) -> Unit): List<String> {
        val found = LinkedHashSet<String>()
        val subdirs = LinkedHashSet<String>()
        for (path in paths) {
            val url = if (path.startsWith("http")) path else base + path
            val body = getText(url, log) ?: continue
            MEDIA.findAll(body).forEach { found.add(absolutize(base, it.value)) }
            if (found.isEmpty()) {
                HREF.findAll(body).map { it.groupValues[1] }
                    .filter { it.endsWith("/") && !it.startsWith("..") && it != "/" }
                    .forEach { subdirs.add(absolutize(base, it)) }
            }
            if (found.isNotEmpty()) break
        }
        if (found.isEmpty() && subdirs.isNotEmpty() && depth < 2) {
            val deepest = subdirs.maxByOrNull { it.uppercase() }!!
            log("Descending into $deepest")
            return collectMedia(base, listOf(deepest), depth + 1, log)
        }
        return found.toList()
    }

    /** Download the newest media file from a listing URL the scanner found. */
    fun downloadFromListing(listingUrl: String, cacheDir: File, log: (String) -> Unit): File? {
        val base = Regex("^(https?://[^/]+)").find(listingUrl)?.groupValues?.get(1) ?: return null
        val body = getText(listingUrl, log) ?: return null
        val media = MEDIA.findAll(body).map { absolutize(base, it.value) }.distinct().toList()
        if (media.isEmpty()) { log("Listing had no media: $listingUrl"); return null }
        val newest = media.maxByOrNull { it.substringAfterLast('/').uppercase() }!!
        log("Newest of ${media.size}: $newest")
        val ext = newest.substringAfterLast('.', "mp4").lowercase().take(4)
        val out = File(cacheDir, "camera_latest.$ext")
        return if (download(newest, out, log)) out else null
    }

    private fun absolutize(base: String, ref: String): String = when {
        ref.startsWith("http") -> ref
        ref.startsWith("/") -> base + ref
        else -> "$base/$ref"
    }

    private fun getText(url: String, log: (String) -> Unit): String? = try {
        log("GET $url")
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 4000; readTimeout = 5000; requestMethod = "GET"
            setRequestProperty("User-Agent", "STS")
        }
        val code = c.responseCode
        if (code != 200) { log("  HTTP $code"); c.disconnect(); null }
        else c.inputStream.use { it.readBytes().toString(Charsets.ISO_8859_1) }.also { c.disconnect() }
    } catch (e: Exception) { log("  ${e.javaClass.simpleName}: ${e.message}"); null }

    private fun download(url: String, out: File, log: (String) -> Unit): Boolean = try {
        log("Download $url")
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000; readTimeout = 20000; requestMethod = "GET"
            setRequestProperty("User-Agent", "STS")
        }
        val code = c.responseCode
        if (code != 200) { log("  HTTP $code"); c.disconnect(); false }
        else {
            c.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
            c.disconnect()
            out.length() > 0
        }
    } catch (e: Exception) { log("  ${e.javaClass.simpleName}: ${e.message}"); false }
}
