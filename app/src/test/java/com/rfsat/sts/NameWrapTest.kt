package com.rfsat.sts

import android.graphics.Paint
import com.rfsat.sts.ui.NameWrap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NameWrapTest {

    /** Measures one unit per character, so "fits" is a question of length. */
    private fun paintOf(): Paint = Paint()

    @Test
    fun `a name that fits is left alone`() {
        val p = paintOf()
        val short = "Anschutz 1907"
        assertEquals(short, NameWrap.wrapAtDash(short, p, 10_000f))
    }

    @Test
    fun `a long name breaks at the dash and nowhere else`() {
        val p = paintOf()
        val long = "Anschutz 1907 — match rifle, 22 LR, 26in barrel"
        val wrapped = NameWrap.wrapAtDash(long, p, 1f)
        assertEquals(1, wrapped.count { it == '\n' })
        assertEquals("Anschutz 1907", wrapped.substringBefore('\n'))
        assertTrue(wrapped.substringAfter('\n').startsWith("—"))
    }

    @Test
    fun `a long name with no dash is not broken`() {
        val p = paintOf()
        val long = "a name with no dash in it at all"
        assertEquals(long, NameWrap.wrapAtDash(long, p, 1f))
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
        assertEquals("— odd", NameWrap.wrapAtDash("— odd", paintOf(), 1f))
    }
}
