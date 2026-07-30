package com.rfsat.sts.scoring

import com.rfsat.sts.rules.RuleSet

/**
 * Compares the number of shots found against the number the course of fire
 * says should be there.
 *
 * The rule set has always known the string length and nothing has ever looked
 * at it. It is the cheapest check in the app and one of the most informative:
 * nine holes where ten were fired is a missed detection, and eleven is a
 * false one, and either way the shooter knows which before quoting a score.
 *
 * ADVISORY ONLY, and deliberately. The obvious next step — lowering the
 * threshold until the expected number appears — would manufacture shots on
 * demand, turning a detector into a machine that always agrees with the
 * course of fire. A miscount is information; a fabricated hole is a wrong
 * score that looks right.
 */
object ShotCountCheck {

    class Result(val expected: Int, val found: Int, val message: String?) {
        val agrees: Boolean get() = message == null
    }

    fun check(rules: RuleSet, found: Int, sighters: Int = 0): Result {
        val expected = rules.matchShots
        if (expected <= 0) return Result(0, found, null)     // stage-defined
        val scored = found - sighters
        if (scored == expected) return Result(expected, found, null)

        val msg = if (scored < expected) {
            ("%d shot%s found where %s expects %d. %s")
                .format(
                    scored, if (scored == 1) "" else "s", rules.name, expected,
                    if (expected - scored == 1)
                        "One is missing — look for a hole in the photograph with no marker on " +
                            "it, and add it by hand on the Results plot."
                    else
                        "%d are missing. Check the photograph against the plot; if several are ".format(expected - scored) +
                            "close together they may have been read as one, and if none are visible " +
                            "the target face or the scoring gauge is probably wrong."
                )
        } else {
            ("%d shots found where %s expects %d. The extra %s probably a mark on the card " +
                "rather than a hit — delete on the Results plot what does not belong.")
                .format(scored, rules.name, expected,
                    if (scored - expected == 1) "is" else "are")
        }
        return Result(expected, found, msg)
    }
}
