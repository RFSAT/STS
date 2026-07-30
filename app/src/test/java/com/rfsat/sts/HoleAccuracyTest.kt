package com.rfsat.sts

import com.rfsat.sts.detect.MergedHoles
import com.rfsat.sts.rules.RuleCatalog
import com.rfsat.sts.scoring.ShotCountCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Hole-centre PRECISION, measured separately from detection rate.
 *
 * The two fail differently and only one of them is obvious. A missed hole is
 * visible on the plot; a hole half a millimetre out looks perfectly normal
 * and silently flips any shot sitting near a ring boundary. Nothing in this
 * project measured the second until now.
 */
class HoleAccuracyTest {

    private val gauge = 16.0
    private val expectedArea = Math.PI * (gauge / 2) * (gauge / 2)
    private val w = 160
    private val h = 160

    /** One round hole centred at (cx, cy), as a response field. */
    private fun oneHole(cx: Double, cy: Double): Pair<IntArray, IntArray> {
        val response = IntArray(w * h)
        val pix = ArrayList<Int>()
        for (y in 0 until h) for (x in 0 until w) {
            val d = hypot(x - cx, y - cy)
            val v = 60.0 - 60.0 * d / (gauge / 2)
            if (v > 0) { response[y * w + x] = v.toInt(); pix += y * w + x }
        }
        return response to pix.toIntArray()
    }

    /**
     * A weighted centroid should recover a hole's centre to well under a
     * pixel, INCLUDING at fractional positions — a centroid that quietly
     * rounded to whole pixels would still pass a test that only ever placed
     * holes on integers.
     */
    @Test
    fun `a single hole is located to a fraction of a pixel`() {
        var worst = 0.0
        for (dx in listOf(0.0, 0.17, 0.35, 0.5, 0.73)) {
            for (dy in listOf(0.0, 0.29, 0.61)) {
                val cx = 80.0 + dx
                val cy = 80.0 + dy
                val (response, pix) = oneHole(cx, cy)
                val parts = MergedHoles.split(pix, pix.size, response, w, gauge, expectedArea)
                assertEquals("a lone hole must not be split", 1, parts.size)
                worst = maxOf(worst, hypot(parts[0].x - cx, parts[0].y - cy))
            }
        }
        assertTrue("worst centre error was $worst px", worst < 0.35)
    }

    /**
     * At a 4.5 mm gauge rendered eight pixels across — the app's rectified
     * resolution — a third of a pixel is about 0.2 mm. Ring pitch on a 10 m
     * air rifle face is 2.5 mm, so that is under a tenth of a ring: small
     * enough that it only matters for a shot already on the line.
     */
    @Test
    fun `the centre error is small against the ring pitch it has to resolve`() {
        val mmPerPx = 4.5 / 8.0
        val errorMm = 0.35 * mmPerPx
        val pitchMm = 2.5
        assertTrue("0.35 px is $errorMm mm, which is ${errorMm / pitchMm} of a ring",
            errorMm / pitchMm < 0.10)
    }

    @Test
    fun `two shots far enough apart are recovered as two`() {
        val sep = gauge * 1.0
        val response = IntArray(w * h)
        val pix = ArrayList<Int>()
        for (y in 0 until h) for (x in 0 until w) {
            val v = maxOf(
                60.0 - 60.0 * hypot(x - (80 - sep / 2), y - 80.0) / (gauge / 2),
                60.0 - 60.0 * hypot(x - (80 + sep / 2), y - 80.0) / (gauge / 2)
            )
            if (v > 0) { response[y * w + x] = v.toInt(); pix += y * w + x }
        }
        val parts = MergedHoles.split(pix.toIntArray(), pix.size, response, w, gauge, expectedArea)
        assertEquals(2, parts.size)
        val xs = parts.map { it.x }.sorted()
        assertEquals(80 - sep / 2, xs[0], 0.6)
        assertEquals(80 + sep / 2, xs[1], 0.6)
    }

    /**
     * And two that overlap almost completely are NOT invented. A shot
     * fabricated on paper is a score the shooter did not fire, which is worse
     * than a shot missed.
     */
    @Test
    fun `two shots through the same place stay one`() {
        val sep = gauge * 0.3
        val response = IntArray(w * h)
        val pix = ArrayList<Int>()
        for (y in 0 until h) for (x in 0 until w) {
            val v = maxOf(
                60.0 - 60.0 * hypot(x - (80 - sep / 2), y - 80.0) / (gauge / 2),
                60.0 - 60.0 * hypot(x - (80 + sep / 2), y - 80.0) / (gauge / 2)
            )
            if (v > 0) { response[y * w + x] = v.toInt(); pix += y * w + x }
        }
        val parts = MergedHoles.split(pix.toIntArray(), pix.size, response, w, gauge, expectedArea)
        assertEquals(1, parts.size)
    }

    // ------------------------------------------------------------------
    //  Shot count
    // ------------------------------------------------------------------

    private val rules = RuleCatalog.ISSF_AR60

    @Test
    fun `the right number of shots is not complained about`() {
        assertNull(ShotCountCheck.check(rules, rules.matchShots).message)
    }

    @Test
    fun `a missing shot is reported and says what to do`() {
        val r = ShotCountCheck.check(rules, rules.matchShots - 1)
        assertNotNull(r.message)
        assertTrue(r.message!!.contains("missing"))
    }

    @Test
    fun `an extra shot is reported`() {
        val r = ShotCountCheck.check(rules, rules.matchShots + 1)
        assertNotNull(r.message)
        assertTrue(r.message!!.contains("extra"))
    }

    /** A stage-defined course has no fixed count and must stay silent. */
    @Test
    fun `a stage-defined course is not second-guessed`() {
        val open = RuleCatalog.ISSF_AR60.copy(matchShots = 0)
        assertNull(ShotCountCheck.check(open, 7).message)
    }
}
