package com.rfsat.sts

import com.rfsat.sts.detect.BlackMarkDetector
import com.rfsat.sts.detect.BoxTransform
import com.rfsat.sts.detect.DetectedHole
import com.rfsat.sts.detect.LumaFrame
import com.rfsat.sts.detect.RingFinder
import com.rfsat.sts.detect.ScaleSettings
import com.rfsat.sts.detect.SourceHoleDetector
import com.rfsat.sts.detect.TargetRegistration
import com.rfsat.sts.targets.TargetCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.math.hypot

/**
 * THE ONE REAL CARD WITH A KNOWN ANSWER.
 *
 * A photographed ISSF 10 m Air Pistol target, scored by hand independently of
 * this code: the centre found by fitting circles to the printed ring lines,
 * the scale from a least-squares fit of those circles against the catalogue
 * radii (ring pitch recovered as 7.999 mm where the true value is 8.000), and
 * every hole measured from that centre. The result is
 *
 *      9 at 12.55 mm    6 at 38.55    2 at 67.64    1 at 73.58    1 at 76.22
 *      and two shots off the rings entirely, at 93.50 and 97.39
 *      TOTAL 19 from 7 shots
 *
 * The 6 is the interesting one: its centre lies at 38.55 mm, OUTSIDE the
 * 6-ring at 37.75, so by centre alone it is a 5. A 4.5 mm pellet hole reaches
 * inward to 36.3 and breaks the line, and under ISSF that makes it a 6. It is
 * the shot that will catch a regression in the gauge rule.
 *
 * The 9 is the other one to watch. It sits inside the black, where the colour
 * detection channel is saturated and carries nothing, and every version of
 * this app up to 1.26.0 missed it — scoring the card 10 instead of 19.
 *
 * Stored as the two 760 px planes the detector actually consumes rather than
 * as a JPEG, because unit tests have no Android image decoder. 760 px because
 * the full 1536 px original gives an identical score, so the smaller one is
 * the honest choice for something that runs on every build.
 */
class T0002CorpusTest {

    private val face = TargetCatalog.builtIns.first { it.id == "issf_ap_10m" }
    private val gauge = 4.5

    /** score, millimetres from centre — the hand measurement. */
    private val truth = listOf(
        9 to 12.55, 6 to 38.55, 2 to 67.64, 1 to 73.58, 1 to 76.22
    )
    private val truthMisses = listOf(93.50, 97.39)

    private fun plane(name: String): LumaFrame {
        val res = javaClass.classLoader!!.getResourceAsStream("corpus/$name")
        assertNotNull("missing fixture corpus/$name", res)
        val d = DataInputStream(GZIPInputStream(res!!))
        // P5 header: magic, width, height, maxval
        fun token(): String {
            val sb = StringBuilder()
            var c = d.read()
            while (c == ' '.code || c == '\n'.code || c == '\r'.code || c == '\t'.code) c = d.read()
            while (c > 0 && c != ' '.code && c != '\n'.code && c != '\r'.code && c != '\t'.code) {
                sb.append(c.toChar()); c = d.read()
            }
            return sb.toString()
        }
        assertEquals("P5", token())
        val w = token().toInt(); val h = token().toInt(); token()
        val bytes = ByteArray(w * h)
        d.readFully(bytes)
        return LumaFrame(w, h, bytes)
    }

    private fun run(includeMisses: Boolean): Pair<List<DetectedHole>, Long> {
        ScaleSettings.forcePunctureCheck(true)
        ScaleSettings.forceRingFamilyFit(false)
        val colour = plane("T0002_colour.pgm.gz")
        val luma = plane("T0002_luma.pgm.gz")
        val mark = BlackMarkDetector.detect(colour)
        val fit = RingFinder.find(colour, seedX = mark?.centreXPx ?: -1.0, seedY = mark?.centreYPx ?: -1.0)
        assertNotNull("the ring family must be found on a clean flat scan", fit)
        val reg = TargetRegistration.fromRingFit(face, fit!!, gauge, BoxTransform.NONE, colour)
        assertNotNull("registration must succeed", reg)
        val t0 = System.currentTimeMillis()
        val holes = SourceHoleDetector.detect(reg!!, colour, gauge, luma, includeMisses)
        return holes to (System.currentTimeMillis() - t0)
    }

    private fun scoreOf(h: DetectedHole) = face.scoreInteger(hypot(h.xMm, h.yMm), gauge / 2.0)

    @Test
    fun `every scoring shot is found and the total is the hand score`() {
        val (holes, _) = run(includeMisses = false)
        val scoring = holes.filter { scoreOf(it) > 0 }
        assertEquals(
            "expected the five scoring shots; found ${scoring.map { scoreOf(it) }.sorted()}",
            5, scoring.size
        )
        assertEquals(19, scoring.sumOf { scoreOf(it) })
        assertEquals(
            listOf(1, 1, 2, 6, 9), scoring.map { scoreOf(it) }.sorted()
        )
    }

    @Test
    fun `each shot lands within a millimetre and a half of where it was measured`() {
        val (holes, _) = run(includeMisses = false)
        for ((value, rMm) in truth) {
            val match = holes.filter { scoreOf(it) == value }
                .minByOrNull { abs(hypot(it.xMm, it.yMm) - rMm) }
            assertNotNull("no shot scoring $value was found at all", match)
            val got = hypot(match!!.xMm, match.yMm)
            assertTrue(
                "the $value should sit at %.2f mm; found %.2f".format(rMm, got),
                abs(got - rMm) <= 1.5
            )
        }
    }

    @Test
    fun `the shot inside the black is found, which is what luminance is for`() {
        // Guards the 1.27.0 fix specifically. Without reading luminance inside
        // the aiming mark this card scores 10 rather than 19, and it does so
        // quietly, because every other stage did exactly what it was told.
        val (holes, _) = run(includeMisses = false)
        val nine = holes.firstOrNull { scoreOf(it) == 9 }
        assertNotNull("the 9 in the black was not found", nine)
        assertTrue(hypot(nine!!.xMm, nine.yMm) < face.blackDiameterMm / 2.0)
    }

    @Test
    fun `the six is decided by the gauge, not by its centre`() {
        // Centre at 38.55 mm, 6-ring at 37.75: outside it. The hole's edge
        // reaches 36.3 and breaks the line, so ISSF scores it a 6. Anything
        // that starts scoring from the centre alone turns this into a 5.
        val (holes, _) = run(includeMisses = false)
        val six = holes.firstOrNull { scoreOf(it) == 6 }
        assertNotNull("the 6 was not found", six)
        val r = hypot(six!!.xMm, six.yMm)
        val ringSix = face.rings.first { it.value == 6 }.diameterMm / 2.0
        assertTrue("this shot must lie OUTSIDE the 6 ring for the test to mean anything", r > ringSix)
        assertEquals(5, face.scoreInteger(r, 0.0))     // by centre alone
        assertEquals(6, face.scoreInteger(r, gauge / 2.0))  // with the gauge
    }

    @Test
    fun `the shots that missed the rings are found when asked for`() {
        val (holes, _) = run(includeMisses = true)
        for (rMm in truthMisses) {
            val near = holes.filter { scoreOf(it) == 0 }
                .minByOrNull { abs(hypot(it.xMm, it.yMm) - rMm) }
            assertNotNull("no miss found anywhere near %.1f mm".format(rMm), near)
            assertTrue(
                "expected a miss near %.1f mm; nearest was %.1f".format(
                    rMm, hypot(near!!.xMm, near.yMm)
                ),
                abs(hypot(near.xMm, near.yMm) - rMm) <= 2.0
            )
        }
        // The scoring shots must not be disturbed by widening the search.
        assertEquals(19, holes.sumOf { scoreOf(it) })
    }

    @Test
    fun `detection stays within a sane time budget`() {
        // Not a benchmark — a tripwire. This card takes about 130 ms at 760 px
        // on the offline rig; two seconds catches an accidental quadratic
        // without failing on a loaded CI runner.
        val (_, ms) = run(includeMisses = false)
        assertTrue("detection took $ms ms", ms < 2000)
    }
}
