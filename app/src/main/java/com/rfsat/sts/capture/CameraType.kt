package com.rfsat.sts.capture

/**
 * The camera the shooter is using, chosen consistently on both the Ballistics
 * and Score tabs (and defaulted in Settings). Capability flags drive the
 * Configure menu so it only ever offers what the selected camera supports.
 */
enum class CameraType(
    val label: String,
    val defaultHost: String,
    val hasHost: Boolean,
    val canDownload: Boolean,
    val canScan: Boolean,
    val isGoPro: Boolean,
    val canLive: Boolean,
    val isStreamUrl: Boolean
) {
    PHONE("Phone camera", "", false, false, false, false, false, false),
    GOPRO("GoPro (HERO9+)", "10.5.5.9", false, true, true, true, true, false),
    TACTACAM("TACTACAM 5.0", "192.168.1.254", true, true, true, false, false, false),
    SHOTKAM("ShotKam Gen 4", "192.168.1.1", true, true, true, false, false, false),
    RTSP("RTSP / MJPEG stream", "", true, false, true, false, true, true);

    companion object {
        fun fromName(n: String?): CameraType =
            values().firstOrNull { it.name == n } ?: PHONE
    }
}
