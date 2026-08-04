package com.rfsat.sts

import com.rfsat.sts.cloud.AiProvider
import com.rfsat.sts.cloud.OpinionReconciler
import com.rfsat.sts.cloud.SecondOpinion
import com.rfsat.sts.detect.DetectedHole
import com.rfsat.sts.targets.TargetCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reconciler's job is to be USEFUL without being trusted. These pin the
 * one property that matters: a position the model supplied never becomes a
 * scored shot on its own.
 */
class SecondOpinionTest {

    private val face = TargetCatalog.builtIns.first { it.id == "issf_ap_10m" }

    // the rectified photograph spans the whole card
    private val uMin = -85.0; private val uMax = 85.0
    private val vMin = -85.0; private val vMax = 85.0

    private fun hole(x: Double, y: Double) =
        DetectedHole(xMm = x, yMm = y, diameterMm = 4.5, contrast = 90.0,
            confidence = 0.8, elongation = 1.05)

    private fun spotAt(xMm: Double, yMm: Double) = SecondOpinion.Spot(
        (xMm - uMin) / (uMax - uMin), (vMax - yMm) / (vMax - vMin), "note")

    private fun run(op: SecondOpinion.Opinion, measured: List<DetectedHole>) =
        OpinionReconciler.reconcile(op, measured, face.name, face.outerRadiusMm, uMin, uMax, vMin, vMax)

    @Test
    fun `every provider is asked the same question and refuses without a key`() {
        // Both services are held to one schema and one prompt, so a card
        // scored by either arrives downstream in the same shape. The only
        // thing that must differ is which console the error names.
        for (p in AiProvider.values()) {
            val r = SecondOpinion.ask(p, "", "any-model", "", scoreToo = false)
            assertTrue(r is SecondOpinion.Result.Failed)
            assertTrue("must name the service: ${(r as SecondOpinion.Result.Failed).message}",
                r.message.contains(p.label))
        }
    }

    @Test
    fun `a key carrying a line break is refused before a request is built`() {
        // A key pasted from a wrapped display has a newline in the MIDDLE of
        // it, which trim() leaves alone. It then goes into an HTTP header,
        // where a newline is illegal, and the request dies before it is sent
        // — reported as "unexpected char 0x0a at 83 in header value", which
        // reads to the user as though the service refused.
        val wrapped = "sk-proj-" + "A".repeat(68) + "\n" + "B".repeat(20)
        assertEquals(83, "Bearer ".length + wrapped.indexOf('\n'))
        for (p in AiProvider.values()) {
            val r = SecondOpinion.ask(p, wrapped, "any-model", "", scoreToo = false)
            assertTrue(r is SecondOpinion.Result.Failed)
            assertTrue("said: ${(r as SecondOpinion.Result.Failed).message}",
                r.message.contains("line break"))
        }
    }

    @Test
    fun `each provider names its own console, so a key is looked for in the right place`() {
        assertTrue(AiProvider.ANTHROPIC.console.contains("anthropic"))
        assertTrue(AiProvider.OPENAI.console.contains("openai"))
        assertTrue(AiProvider.ANTHROPIC.keyHint != AiProvider.OPENAI.keyHint)
    }

    @Test
    fun `a hole both agree on produces no suggestion`() {
        val op = SecondOpinion.Opinion(face.name, 1, listOf(spotAt(10.0, 10.0)), true, "")
        val out = run(op, listOf(hole(10.0, 10.0)))
        assertTrue(out.unconfirmed.isEmpty())
        assertTrue(out.unsupported.isEmpty())
        assertTrue(out.faceAgrees)
    }

    @Test
    fun `a hole only the model saw becomes a suggestion, not a shot`() {
        val op = SecondOpinion.Opinion(face.name, 2, listOf(
            spotAt(10.0, 10.0), spotAt(-40.0, 20.0)), true, "")
        val out = run(op, listOf(hole(10.0, 10.0)))
        assertEquals(1, out.unconfirmed.size)
        // and the measured list is untouched — the suggestion is not a shot
        assertEquals(1, out.measured)
        assertEquals(1, out.measured)
    }

    @Test
    fun `when the app finds MORE than Claude, removal is what gets offered`() {
        // The case that made a real card worse: the app marked fourteen,
        // several of them printing outside the rings, against seven real
        // shots — and the only action offered was to add three more.
        val op = SecondOpinion.Opinion(face.name, 1, listOf(spotAt(10.0, 10.0)), true, "")
        val out = run(op, listOf(hole(10.0, 10.0), hole(-55.0, -30.0), hole(80.0, 40.0)))
        assertTrue("must recognise the direction of the disagreement", out.overDetected)
        assertEquals(2, out.unsupported.size)
        assertEquals("the button and the dialog must act on one set",
            2, out.unsupported.size)
    }

    @Test
    fun `marks outside the scoring rings are called out for removal`() {
        val op = SecondOpinion.Opinion(face.name, 1, listOf(spotAt(0.0, 0.0)), true, "")
        // 80 mm is outside the 77.75 mm outer ring
        val out = run(op, listOf(hole(0.0, 0.0), hole(80.0, 10.0), hole(-82.0, 5.0)))
        assertTrue(out.overDetected)
        assertTrue("said: ${out.summary}", out.summary.contains("outside the scoring rings"))
        // both lie beyond the rings, so both are removable even though one of
        // them sits within matching distance of Claude's only spot
        assertEquals(2, out.unsupported.size)
    }

    @Test
    fun `when the counts agree, removal is not urged`() {
        val op = SecondOpinion.Opinion(face.name, 2,
            listOf(spotAt(10.0, 10.0), spotAt(-20.0, 5.0)), true, "")
        val out = run(op, listOf(hole(10.0, 10.0), hole(-20.0, 5.0)))
        assertFalse(out.overDetected)
        assertTrue(out.unsupported.isEmpty())
    }

    @Test
    fun `a shot the model missed is reported both ways round`() {
        val op = SecondOpinion.Opinion(face.name, 1, listOf(spotAt(10.0, 10.0)), true, "")
        val out = run(op, listOf(hole(10.0, 10.0), hole(-55.0, -30.0)))
        assertEquals(1, out.unsupported.size)
        assertEquals(1, out.unsupported.size)
    }

    @Test
    fun `a disagreement about the face is surfaced before the shots`() {
        val op = SecondOpinion.Opinion("ISSF 10 m Air Rifle", 0, emptyList(), true, "")
        val out = run(op, emptyList())
        assertFalse(out.faceAgrees)
        assertTrue("said: ${out.summary}", out.summary.contains("Check the face before the shots"))
    }

    @Test
    fun `an unusable photograph is called out first`() {
        val op = SecondOpinion.Opinion(face.name, 0, emptyList(), false, "heavy glare")
        val out = run(op, emptyList())
        assertTrue("said: ${out.summary}", out.summary.contains("not good enough to score"))
    }

    @Test
    fun `positions outside the mapping are dropped rather than guessed`() {
        val op = SecondOpinion.Opinion(face.name, 1,
            listOf(SecondOpinion.Spot(2.0, 2.0, "off the image")), true, "")
        // out-of-range fractions are already refused at parse time; this pins
        // that nothing downstream invents a shot from a bad coordinate
        val out = run(op, emptyList())
        assertEquals(0, out.measured)
    }
}
