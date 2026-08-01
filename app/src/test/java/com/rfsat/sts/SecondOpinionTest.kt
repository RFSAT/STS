package com.rfsat.sts

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
        OpinionReconciler.reconcile(op, measured, face.name, uMin, uMax, vMin, vMax)

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
        assertTrue(out.summary.contains("suggestions"))
    }

    @Test
    fun `a shot the model missed is reported both ways round`() {
        val op = SecondOpinion.Opinion(face.name, 1, listOf(spotAt(10.0, 10.0)), true, "")
        val out = run(op, listOf(hole(10.0, 10.0), hole(-55.0, -30.0)))
        assertEquals(1, out.unsupported.size)
        assertTrue("must say a false detection looks the same",
            out.summary.contains("false detection"))
    }

    @Test
    fun `a disagreement about the face is surfaced before the shots`() {
        val op = SecondOpinion.Opinion("ISSF 10 m Air Rifle", 0, emptyList(), true, "")
        val out = run(op, emptyList())
        assertFalse(out.faceAgrees)
        assertTrue(out.summary.contains("check the face before the shots"))
    }

    @Test
    fun `an unusable photograph is called out first`() {
        val op = SecondOpinion.Opinion(face.name, 0, emptyList(), false, "heavy glare")
        val out = run(op, emptyList())
        assertTrue(out.summary.contains("not really good enough to score"))
        assertTrue(out.summary.contains("heavy glare"))
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
