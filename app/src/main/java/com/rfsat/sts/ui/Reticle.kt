package com.rfsat.sts.ui

/**
 * Which reticle is drawn over the viewfinder.
 *
 * NOT AN AIMING DEVICE, and it matters that this is said. The app draws over
 * a picture of a card; it has no idea where the barrel points. A shot is
 * scored from the hole in the paper, and nothing here changes a score. What
 * these are for is lining the phone or the camera up with the card, and —
 * for someone whose camera looks through a scope — matching what they
 * already see so the two pictures do not fight each other.
 *
 * WHICH IS WHY [NONE] IS FIRST AND IS THE DEFAULT ON A STREAM. A camera
 * mounted on the scope is already showing the scope's own reticle. Drawing a
 * second one a few pixels away is worse than drawing neither.
 *
 * In its own file with no Android type in sight, because [ScaleSettings]
 * stores the choice and that is pure logic the offline harness compiles
 * without a framework.
 */
enum class Reticle(val label: String) {
    NONE("None"),
    CROSS("Simple crosshair"),
    DUPLEX("Duplex"),
    MIL_DOT("Mil-dot"),
    MOA_GRID("MOA grid"),
    GERMAN_4("German #4"),
    CIRCLE_DOT("Circle and dot"),
    CUSTOM("My own image…")
}
