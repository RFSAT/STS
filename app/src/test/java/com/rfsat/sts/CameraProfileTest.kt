package com.rfsat.sts

import com.rfsat.sts.detect.CameraMains
import com.rfsat.sts.detect.CameraProfile
import com.rfsat.sts.detect.CameraVideoSize
import com.rfsat.sts.detect.CameraWhiteBalance
import com.rfsat.sts.detect.CameraZoom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the app says about a camera the shooter has described.
 *
 * The point of these is that every warning is about a SCORE rather than a
 * preference: a setting that will put a mark where there was no shot, or move
 * the picture under a detector that assumes it is still. A warning nobody
 * needs is a warning everybody learns to ignore, so the quiet cases are
 * tested as carefully as the loud ones.
 */
class CameraProfileTest {

    @Test
    fun `a sensibly set up camera is not nagged at`() {
        val p = CameraProfile(
            zoom = CameraZoom.X8,
            redDot = false,
            videoSize = CameraVideoSize.HD_30,
            stabilisation = false,
            exposureCompensationEv = 0.0,
            mains = CameraMains.HZ_50,
            whiteBalance = CameraWhiteBalance.DAYLIGHT
        )
        assertTrue(p.advice().isEmpty())
    }

    @Test
    fun `the red dot is the first thing said, because it invents a shot`() {
        val p = CameraProfile(redDot = true, stabilisation = true)
        assertTrue(p.advice().first().contains("red dot"))
        assertEquals(0.5, p.redDotSuppressionGauges(), 1e-9)
    }

    @Test
    fun `nothing is suppressed when the dot is off`() {
        assertEquals(0.0, CameraProfile(redDot = false).redDotSuppressionGauges(), 1e-9)
    }

    @Test
    fun `stabilisation is called out, because live detection cannot survive it`() {
        val a = CameraProfile(stabilisation = true).advice()
        assertTrue(a.any { it.contains("stabilisation", ignoreCase = true) })
    }

    @Test
    fun `auto white balance is called out and a fixed one is not`() {
        assertTrue(CameraProfile(whiteBalance = CameraWhiteBalance.AUTO)
            .advice().any { it.contains("White balance") })
        assertTrue(CameraProfile(whiteBalance = CameraWhiteBalance.CLOUDY)
            .advice().none { it.contains("White balance") })
    }

    @Test
    fun `a stream that matches what was declared says nothing`() {
        val p = CameraProfile(videoSize = CameraVideoSize.HD_30)
        assertNull(p.mismatch(1280, 720))
    }

    @Test
    fun `a stream that differs is explained rather than merely flagged`() {
        val p = CameraProfile(videoSize = CameraVideoSize.UHD_30)
        val m = p.mismatch(1280, 720)
        assertTrue(m != null && m.contains("1280 x 720"))
        // It must say the usual REASON, or a shooter will go looking for a
        // fault that is not there.
        assertTrue(m!!.contains("saved to the card"))
    }

    @Test
    fun `an unstated size cannot disagree with anything`() {
        assertNull(CameraProfile(videoSize = CameraVideoSize.UNSTATED).mismatch(1280, 720))
        assertNull(CameraProfile(videoSize = CameraVideoSize.HD_30).mismatch(0, 0))
    }

    @Test
    fun `zoom changes what to expect of the lens`() {
        assertTrue(CameraProfile(zoom = CameraZoom.X8).distortionExpectation().contains("little"))
        assertTrue(CameraProfile(zoom = CameraZoom.X1).distortionExpectation().contains("bows"))
    }
}
