package com.rfsat.sts.ui

/**
 * What is drawn over the viewfinder to line the phone up with the card.
 *
 * In its own file, free of any Android type, because [ScaleSettings] stores
 * the choice and that is pure logic which the offline harness compiles
 * without a framework. An enum tucked inside a View drags the whole of
 * android.view in behind it.
 */
enum class AimGuide(val label: String) {
    NONE("Nothing"),
    CROSS("Simple crosshair"),
    RINGS("Rings of the selected target"),
    RINGS_AND_CROSS("Rings and crosshair")
}

/**
 * How well the card in front of the camera matches the selected face.
 *
 * Shown continuously rather than as a message that appears once and is gone:
 * a shooter lining a phone up is looking at the preview, not at a toast that
 * fired while they were still walking to the firing point.
 */
enum class GuideMatch(val label: String) {
    /** Not looked yet, or nothing recognisable in frame. */
    UNKNOWN("Aim at the target"),
    CHECKING("Checking\u2026"),
    /** Rings and face agree closely enough to register against. */
    MATCH("Match OK"),
    /** They do not, and the score would be wrong by the difference. */
    MISMATCH("Wrong target face")
}
