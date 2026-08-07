package com.rfsat.sts.detect

/**
 * How the Wi-Fi camera has been set up, as told to the app by the shooter.
 *
 * NOT REMOTE CONTROL. The app changes nothing on the camera — it cannot, and
 * it would be a poor idea to pretend otherwise against an undocumented
 * protocol. This is the other direction: the shooter states what they have
 * already set, and the app stops having to guess what the stream will look
 * like.
 *
 * WHY IT IS WORTH STATING. Several of these settings change what the DETECTOR
 * sees, not merely how the picture looks:
 *
 *   The red dot is burned into the video at the centre of the frame. On a
 *   card that has been lined up, the centre of the frame is where the ten
 *   ring is — so a permanent red mark sits exactly where a shot is most
 *   expected, and it is round, small and different from the paper. That is a
 *   false hit in the one place a shooter will not question it.
 *
 *   Stabilisation shifts and crops the picture between frames to cancel
 *   camera movement. Live detection compares each frame against a reference
 *   and treats what changed as a shot; a picture that moves under it is the
 *   one thing it cannot cope with.
 *
 *   Auto white balance and auto exposure drift with the light. The detector
 *   measures how far each pixel sits from the paper's OWN colour, re-measured
 *   per frame, so it survives a slow drift — but a step change between two
 *   frames of a differential pair is read as change everywhere.
 *
 *   Zoom changes the focal length and therefore the barrel distortion, which
 *   is measured separately and is much smaller at 8x than at 1x.
 *
 *   Video size says what the stream should be. When the arriving frames are
 *   a different size, one of the two is not what the shooter thinks it is —
 *   usually because the setting governs the card recording and the stream is
 *   a fixed sub-stream — and knowing that is better than wondering.
 *
 * Pure data and pure decisions, so the offline harness compiles it and the
 * warnings are unit tested rather than read.
 */

enum class CameraZoom(val label: String, val factor: Int) {
    X1("1x (wide)", 1),
    X8("8x (zoomed)", 8);
}

enum class CameraWhiteBalance(val label: String, val fixed: Boolean) {
    AUTO("Auto", false),
    DAYLIGHT("Daylight", true),
    CLOUDY("Cloudy", true),
    FLUORESCENT("Fluorescent", true),
    TUNGSTEN("Tungsten", true)
}

enum class CameraMains(val label: String, val hz: Int) {
    HZ_50("50 Hz", 50),
    HZ_60("60 Hz", 60)
}

/** The modes a Tactacam 5.0 offers, and their like on other cameras. */
enum class CameraVideoSize(val label: String, val width: Int, val height: Int, val fps: Int) {
    UHD_15("3840 x 2160 @ 15", 3840, 2160, 15),
    UHD_30("3840 x 2160 @ 30", 3840, 2160, 30),
    QHD_30("2720 x 1520 @ 30", 2720, 1520, 30),
    FHD_30("1920 x 1080 @ 30", 1920, 1080, 30),
    FHD_60("1920 x 1080 @ 60", 1920, 1080, 60),
    FHD_120("1920 x 1080 @ 120", 1920, 1080, 120),
    HD_30("1280 x 720 @ 30", 1280, 720, 30),
    HD_60("1280 x 720 @ 60", 1280, 720, 60),
    HD_120("1280 x 720 @ 120", 1280, 720, 120),
    HD_240("1280 x 720 @ 240", 1280, 720, 240),
    UNSTATED("Not stated", 0, 0, 0)
}

data class CameraProfile(
    val zoom: CameraZoom = CameraZoom.X1,
    val redDot: Boolean = false,
    val videoSize: CameraVideoSize = CameraVideoSize.UNSTATED,
    val stabilisation: Boolean = false,
    val exposureCompensationEv: Double = 0.0,
    val mains: CameraMains = CameraMains.HZ_50,
    val whiteBalance: CameraWhiteBalance = CameraWhiteBalance.AUTO
) {

    /**
     * What to tell the shooter before they shoot, in the order that matters.
     *
     * Each one is a thing that will change a SCORE, not a preference. Nothing
     * here is enforced: a shooter who wants the red dot on for alignment and
     * will delete the mark afterwards is entitled to do that, and being
     * refused by an app that thinks it knows better is worse than being told.
     */
    fun advice(): List<String> = buildList {
        if (redDot) add(
            "The camera's red dot is on. It sits at the centre of the frame, which is where " +
                "the ten ring is once the card is lined up, and it can be read as a shot. " +
                "Marks found within half a gauge of it are ignored while it is declared on."
        )
        if (stabilisation) add(
            "Image stabilisation is on. It moves the picture between frames to cancel camera " +
                "shake, and live detection reads what moved as a shot. Switch it off for a " +
                "camera on a stand."
        )
        if (!whiteBalance.fixed) add(
            "White balance is on auto. The detector measures how far each pixel sits from the " +
                "paper's own colour; a fixed setting keeps that steady through a string."
        )
        if (exposureCompensationEv != 0.0) add(
            "Exposure compensation is %+.1f EV. That is fine as long as it does not change " +
                "mid-string, but 0 with a fixed white balance is the steadiest.".format(
                    exposureCompensationEv
                )
        )
        if (videoSize.fps >= 120) add(
            "%s is a high frame rate for a stream. On most cameras that setting governs what is " +
                "recorded to the card, and the live stream stays at whatever the camera streams — " +
                "the app reports the size it actually receives."
                .format(videoSize.label)
        )
    }

    /**
     * Whether the received stream matches what was declared, as a sentence,
     * or null when there is nothing to say.
     */
    fun mismatch(actualWidth: Int, actualHeight: Int): String? {
        if (videoSize == CameraVideoSize.UNSTATED) return null
        if (actualWidth <= 0 || actualHeight <= 0) return null
        if (actualWidth == videoSize.width && actualHeight == videoSize.height) return null
        return ("The camera is set to ${videoSize.label} but the stream is arriving at " +
            "$actualWidth x $actualHeight. That is usual — the setting governs what is saved " +
            "to the card, and the stream is a separate, fixed one — and the app works from " +
            "what arrives.")
    }

    /** How much barrel distortion to expect, as a hint for the lens
     *  correction. A longer focal length distorts far less, which is the
     *  cheapest remedy available to a shooter. */
    fun distortionExpectation(): String = when (zoom) {
        CameraZoom.X8 ->
            "At 8x the lens distorts little; a correction is usually unnecessary."
        CameraZoom.X1 ->
            "At 1x a wide lens bows the picture, most at the edges. Measure it once on Import " +
                "and enter the figure under the stream lens correction, or use 8x from further " +
                "back, which avoids it."
    }

    /** Radius around the red dot, in gauges, within which a detection is not
     *  believed. Half a gauge: enough to cover the dot and the halo it draws,
     *  small enough that a genuine ten is still found beside it. */
    fun redDotSuppressionGauges(): Double = if (redDot) 0.5 else 0.0
}
