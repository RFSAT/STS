package com.rfsat.sts

import com.rfsat.sts.profiles.AmmoCatalog
import com.rfsat.sts.profiles.FirearmType
import com.rfsat.sts.profiles.RifleCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 9x19 additions. These check the things a wrong entry would break
 * silently — the calibre it is filed under, the class of firearm it is taken
 * to be, and whether the numbers are physically sane.
 */
class NineMillimetreCatalogueTest {

    private val nine = AmmoCatalog.all.filter { it.caliber == "9x19" }
    private val pistols = RifleCatalog.all.filter { it.model.contains("9x19") }

    @Test
    fun `all three makers are present`() {
        for (m in listOf("Fiocchi", "Federal", "CCI")) {
            assertTrue("$m is missing from the 9x19 loads",
                nine.any { it.manufacturer == m })
        }
    }

    @Test
    fun `both makers of pistol are present, Beretta 92X included`() {
        assertTrue(pistols.any { it.brand == "Glock" })
        assertTrue(pistols.any { it.brand == "Beretta" && it.model.contains("92X") })
    }

    /**
     * A centrefire pistol typed as a rifle takes a rifle's default sight
     * height and zero, which are wrong by enough to matter at 25 m.
     */
    @Test
    fun `every 9x19 pistol is classed as a centrefire pistol`() {
        for (p in pistols) {
            assertEquals("${p.brand} ${p.model}", FirearmType.CENTREFIRE_PISTOL, p.firearmType)
        }
    }

    @Test
    fun `the calibre is read out of the model name`() {
        for (p in pistols) {
            assertEquals("${p.brand} ${p.model}", "9x19", p.caliber)
        }
    }

    /**
     * 1 turn in 250 mm is 9.84 inches, and truncating that to an integer
     * displayed it as 1:9 — a rate nothing is rifled at. Checked over every
     * fractional twist in the catalogue, not just the new ones: the CZ and
     * the Tanfoglio were already being shown wrongly.
     */
    @Test
    fun `a fractional twist survives into the label`() {
        val fractional = RifleCatalog.all.filter {
            it.twistRateInPerTurn != it.twistRateInPerTurn.toLong().toDouble()
        }
        assertTrue("expected some fractional twists", fractional.isNotEmpty())
        for (p in fractional) {
            val shown = p.label().substringAfter("1:").trim().trimEnd('"')
            assertEquals("${p.brand} ${p.model} label '${p.label()}'",
                p.twistRateInPerTurn, shown.toDouble(), 1e-9)
        }
    }

    @Test
    fun `the new service pistols are rifled one turn in 250 mm`() {
        val mine = pistols.filter { it.brand == "Glock" || it.brand == "Beretta" }
        assertTrue(mine.size >= 15)
        for (p in mine) assertEquals("${p.brand} ${p.model}", 9.84, p.twistRateInPerTurn, 1e-9)
    }

    @Test
    fun `barrel lengths are in the range a 9mm pistol occupies`() {
        for (p in pistols) {
            assertTrue("${p.brand} ${p.model} has a ${p.barrelLengthIn}in barrel",
                p.barrelLengthIn in 3.0..6.5)
        }
    }

    /**
     * Sanity on the loads. 9x19 factory ammunition runs roughly 850 to 1400
     * fps and 115 to 150 grains; anything outside that is a typing error, and
     * a wrong velocity is invisible on a target at 25 m while being quite
     * wrong at distance.
     */
    @Test
    fun `every 9x19 load is physically plausible`() {
        for (e in nine) {
            assertEquals("${e.manufacturer} ${e.product}", 0.355, e.diameterIn, 1e-9)
            assertTrue("${e.manufacturer} ${e.product} at ${e.weightGr}gr",
                e.weightGr in 90.0..160.0)
            assertTrue("${e.manufacturer} ${e.product} at ${e.mvFps}fps",
                e.mvFps in 800.0..1500.0)
            assertTrue("${e.manufacturer} ${e.product} BC ${e.bcG1}",
                e.bcG1 in 0.10..0.25)
        }
    }

    /** Heavier bullets leave slower, at the pressures these are loaded to. */
    @Test
    fun `within a product line the heavier bullet is the slower`() {
        nine.groupBy { it.manufacturer to it.product }
            .filterValues { it.size > 1 }
            .forEach { (line, loads) ->
                val byWeight = loads.sortedBy { it.weightGr }
                for (i in 1 until byWeight.size) {
                    assertTrue(
                        "$line: ${byWeight[i].weightGr}gr is not slower than ${byWeight[i-1].weightGr}gr",
                        byWeight[i].mvFps <= byWeight[i - 1].mvFps
                    )
                }
            }
    }

    /** The subsonic threshold is what decides suppressor suitability, and a
     *  147 gr load sitting the wrong side of it would be misleading. */
    @Test
    fun `the heavy loads are correctly flagged subsonic`() {
        val heavy = nine.filter { it.weightGr >= 147.0 }
        assertTrue("expected some 147gr or heavier loads", heavy.isNotEmpty())
        for (e in heavy) assertTrue("${e.manufacturer} ${e.product}", e.subsonic)
    }
}
