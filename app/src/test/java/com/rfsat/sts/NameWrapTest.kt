package com.rfsat.sts

import com.rfsat.sts.ui.NameWrap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NameWrapTest {

    /**
     * Tests the PREDICATE form, never the Paint one.
     *
     * Under plain unit tests android.jar is stubbed and Paint.measureText
     * returns 0.0f, so a test that measured with a real Paint concluded that
     * every string fits, wrapped nothing, and proved nothing — which is how
     * the first version of this test passed in a sandbox and failed in CI.
     */
    private val neverFits: (String) -> Boolean = { false }
    private val alwaysFits: (String) -> Boolean = { true }

    @Test
    fun `a name that fits is left alone`() {
        val short = "Anschutz 1907 — match rifle, 22 LR"
        assertEquals(short, NameWrap.wrapAtDash(short, alwaysFits))
    }

    @Test
    fun `a long name breaks at the dash and nowhere else`() {
        val long = "Anschutz 1907 — match rifle, 22 LR, 26in barrel"
        val wrapped = NameWrap.wrapAtDash(long, neverFits)
        assertEquals(1, wrapped.count { it == '\n' })
        assertEquals("Anschutz 1907", wrapped.substringBefore('\n'))
        assertTrue(wrapped.substringAfter('\n').startsWith("—"))
    }

    @Test
    fun `a long name with no dash is not broken`() {
        val long = "a name with no dash in it at all"
        assertEquals(long, NameWrap.wrapAtDash(long, neverFits))
    }

    @Test
    fun `short name keeps only the part before the dash`() {
        assertEquals("Anschutz 1907",
            NameWrap.shortName("Anschutz 1907 — match rifle, 22 LR, 26in barrel"))
        assertEquals("Vortex Viper", NameWrap.shortName("Vortex Viper — 3-9×40, 1/4 MOA"))
    }

    @Test
    fun `short name passes through a name with no dash`() {
        assertEquals("RWS R10 Match 8.2gr", NameWrap.shortName("RWS R10 Match 8.2gr"))
    }

    /** A leading dash would otherwise leave an empty first line. */
    @Test
    fun `a name starting with the dash is left alone`() {
        assertEquals("— odd", NameWrap.shortName("— odd"))
        assertEquals("— odd", NameWrap.wrapAtDash("— odd", neverFits))
    }
}
