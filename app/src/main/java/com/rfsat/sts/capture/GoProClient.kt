package com.rfsat.sts.capture

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * GoPro support via the official Open GoPro HTTP API (HERO9 Black and later —
 * HERO9 was the first Open GoPro camera; the AP answers at 10.5.5.9:8080). Unlike TACTACAM/ShotKam this is
 * DOCUMENTED, so both media transfer and camera control are first-class:
 *
 *   media list   GET /gopro/media/list                      (JSON)
 *   download     GET /videos/DCIM/<dir>/<file>
 *   start/stop   GET /gopro/camera/shutter/{start,stop}
 *   digital zoom GET /gopro/camera/digital_zoom?percent=N
 *   load preset  GET /gopro/camera/presets/load?id=N
 *   state        GET /gopro/camera/state                     (JSON)
 *   keep awake   GET /gopro/camera/keep_alive
 *
 * The process must already be bound to the GoPro's Wi-Fi
 * (CaptureActivity.acquireScopeNetwork). Every call is logged.
 */
object GoProClient {

    const val BASE = "http://10.5.5.9:8080"
    private val VIDEO = Regex("""\.(mp4|mov|avi|m4v)$""", RegexOption.IGNORE_CASE)

    private val PHOTO = Regex("""\.(jpg|jpeg|gpr)$""", RegexOption.IGNORE_CASE)

    /** Newest media as a full download URL. [preferPhoto] picks the newest
     *  still (for scoring), otherwise the newest video (for trail analysis);
     *  either falls back to the newest media of any type. */
    fun latestUrl(preferPhoto: Boolean, log: (String) -> Unit): String? {
        val body = get("/gopro/media/list", log) ?: return null
        val media = runCatching { JSONObject(body).optJSONArray("media") }.getOrNull() ?: return null
        var vKey = -1L; var vUrl: String? = null
        var pKey = -1L; var pUrl: String? = null
        var aKey = -1L; var aUrl: String? = null
        for (i in 0 until media.length()) {
            val dir = media.optJSONObject(i) ?: continue
            val d = dir.optString("d")
            val fs = dir.optJSONArray("fs") ?: continue
            for (j in 0 until fs.length()) {
                val f = fs.optJSONObject(j) ?: continue
                val n = f.optString("n")
                if (n.isBlank()) continue
                val key = f.optString("cre").toLongOrNull() ?: f.optString("mod").toLongOrNull() ?: 0L
                val url = "$BASE/videos/DCIM/$d/$n"
                if (key >= aKey) { aKey = key; aUrl = url }
                if (VIDEO.containsMatchIn(n) && key >= vKey) { vKey = key; vUrl = url }
                if (PHOTO.containsMatchIn(n) && key >= pKey) { pKey = key; pUrl = url }
            }
        }
        val chosen = if (preferPhoto) (pUrl ?: aUrl) else (vUrl ?: aUrl)
        log("GoPro newest ${if (preferPhoto) "photo" else "video"}: ${chosen ?: "none"}")
        return chosen
    }

    fun downloadLatest(cacheDir: File, log: (String) -> Unit): File? =
        fetch(latestUrl(false, log), "gopro_latest", cacheDir, log)

    fun downloadLatestPhoto(cacheDir: File, log: (String) -> Unit): File? =
        fetch(latestUrl(true, log), "gopro_photo", cacheDir, log)

    private fun fetch(url: String?, stem: String, cacheDir: File, log: (String) -> Unit): File? {
        if (url == null) return null
        val ext = url.substringAfterLast('.', "mp4").lowercase().take(4)
        val out = File(cacheDir, "$stem.$ext")
        return if (download(url, out, log)) { log("GoPro download ${out.length()} bytes"); out } else null
    }

    fun shutter(start: Boolean, log: (String) -> Unit): Boolean =
        ok(if (start) "/gopro/camera/shutter/start" else "/gopro/camera/shutter/stop", log)

    /** Digital zoom. HERO9+ (Open GoPro); the parameter name differs across
     *  models/firmware — the spec uses percent=, some HERO9/10 builds use
     *  range_pcnt= — so try both and report success if either takes. */
    fun digitalZoom(percent: Int, log: (String) -> Unit): Boolean {
        val p = percent.coerceIn(0, 100)
        return ok("/gopro/camera/digital_zoom?percent=$p", log) ||
            ok("/gopro/camera/digital_zoom?range_pcnt=$p", log)
    }

    fun loadPreset(id: Int, log: (String) -> Unit): Boolean =
        ok("/gopro/camera/presets/load?id=$id", log)

    fun keepAlive(log: (String) -> Unit): Boolean = ok("/gopro/camera/keep_alive", log)

    fun streamStart(log: (String) -> Unit): Boolean = ok("/gopro/camera/stream/start", log)
    fun streamStop(log: (String) -> Unit): Boolean = ok("/gopro/camera/stream/stop", log)

    fun state(log: (String) -> Unit): String? = get("/gopro/camera/state", log)
    fun info(log: (String) -> Unit): String? = get("/gopro/camera/info", log)

    // --- HTTP helpers ---
    private fun ok(path: String, log: (String) -> Unit): Boolean {
        return try {
            val c = open(path)
            val code = c.responseCode
            log("GoPro GET $path -> $code")
            c.disconnect()
            code in 200..299
        } catch (e: Exception) { log("GoPro $path: ${e.javaClass.simpleName} ${e.message}"); false }
    }

    private fun get(path: String, log: (String) -> Unit): String? = try {
        val c = open(path)
        val code = c.responseCode
        val body = if (code in 200..299) c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) } else null
        log("GoPro GET $path -> $code (${body?.length ?: 0}B)")
        c.disconnect()
        body
    } catch (e: Exception) { log("GoPro $path: ${e.javaClass.simpleName} ${e.message}"); null }

    private fun download(url: String, out: File, log: (String) -> Unit): Boolean = try {
        log("GoPro download $url")
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000; readTimeout = 30000; requestMethod = "GET"
            setRequestProperty("User-Agent", "STS")
        }
        val code = c.responseCode
        if (code != 200) { log("  HTTP $code"); c.disconnect(); false }
        else { c.inputStream.use { i -> out.outputStream().use { i.copyTo(it) } }; c.disconnect(); out.length() > 0 }
    } catch (e: Exception) { log("  ${e.javaClass.simpleName}: ${e.message}"); false }

    private fun open(path: String): HttpURLConnection =
        (URL(BASE + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = 4000; readTimeout = 6000; requestMethod = "GET"
            setRequestProperty("User-Agent", "STS")
        }
}
