package com.rfsat.sts.capture

import android.content.Context

/** Persists the selected camera type and per-camera host/URL, shared by both
 *  tabs and Settings. */
object CameraConfig {
    private const val PREFS = "bas_camera"
    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun type(c: Context): CameraType = CameraType.fromName(p(c).getString("type", null))
    fun setType(c: Context, t: CameraType) = p(c).edit().putString("type", t.name).apply()

    /** Host/address for a type, falling back to its documented default. */
    fun host(c: Context, t: CameraType): String {
        val v = p(c).getString("host_${t.name}", null)
        return if (!v.isNullOrBlank()) v else t.defaultHost
    }
    fun setHost(c: Context, t: CameraType, h: String) =
        p(c).edit().putString("host_${t.name}", h.trim()).apply()

    /** The CameraFileImporter preset matching an action-cam type. */
    fun importerPreset(t: CameraType): CameraFileImporter.Preset {
        val key = when (t) {
            CameraType.TACTACAM -> "TACTACAM"
            CameraType.SHOTKAM -> "ShotKam"
            else -> "Generic"
        }
        return CameraFileImporter.PRESETS.firstOrNull { it.name.contains(key, true) }
            ?: CameraFileImporter.PRESETS.first()
    }
}
