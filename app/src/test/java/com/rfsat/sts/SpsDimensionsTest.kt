package com.rfsat.sts

import com.rfsat.sts.detect.SpsDimensions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The decoder is told the picture size from this, and a wrong answer is a
 * decoder that starts and then produces nothing — indistinguishable, from
 * outside, from the camera not streaming at all.
 *
 * The parameter sets below were written by an INDEPENDENT encoder (a short
 * Python bit-writer following the H.264 syntax) rather than copied from
 * somewhere, so this is a round-trip against a second implementation and not
 * a test of the parser against itself. The cropping cases are the ones that
 * matter: 720 is 736 macroblock rows cropped to 720, 1080 is 1088 cropped to
 * 1080, and forgetting to apply that is the classic form of this bug.
 */
class SpsDimensionsTest {

    private fun sps(base64: String): ByteArray =
        java.util.Base64.getDecoder().decode(base64)

    @Test
    fun `720p, which is what the reported camera sends`() {
        val d = SpsDimensions.of(sps("Z0IAHpZUAoAtiA=="))
        assertEquals(1280, d!!.first)
        assertEquals(720, d.second)
    }

    @Test
    fun `1080p, cropped from 1088`() {
        val d = SpsDimensions.of(sps("Z0IAHpZUA8ARLyo="))
        assertEquals(1920, d!!.first)
        assertEquals(1080, d.second)
    }

    @Test
    fun `a size that needs no cropping at all`() {
        val d = SpsDimensions.of(sps("Z0IAHpZUBQHogA=="))
        assertEquals(640, d!!.first)
        assertEquals(480, d.second)
    }

    @Test
    fun `rubbish is refused rather than guessed at`() {
        assertNull(SpsDimensions.of(byteArrayOf(0x67)))
        assertNull(SpsDimensions.of(ByteArray(0)))
    }
}
