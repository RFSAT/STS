fun main() {
    val classes = listOf(
        "com.rfsat.sts.DetectorRegressionTest", "com.rfsat.sts.EllipseFitTest",
        "com.rfsat.sts.RegistrationBoxTest", "com.rfsat.sts.RingFinderTest",
        "com.rfsat.sts.PunctureCheckTest", "com.rfsat.sts.SourceHoleDetectorTest",
        "com.rfsat.sts.T0002CorpusTest", "com.rfsat.sts.FixedSightTest",
        "com.rfsat.sts.ScoringGeometryTest", "com.rfsat.sts.ScoringRulesTest",
        "com.rfsat.sts.ShotDistributionTest", "com.rfsat.sts.NameWrapTest", "com.rfsat.sts.ScaleChoiceTest", "com.rfsat.sts.NineMillimetreCatalogueTest", "com.rfsat.sts.HoleAccuracyTest"
    )
    var pass = 0; var fail = 0
    val failures = ArrayList<String>()
    for (cn in classes) {
        val c = Class.forName(cn)
        val inst = c.getDeclaredConstructor().newInstance()
        var p = 0; var f = 0
        for (m in c.declaredMethods.sortedBy { it.name }) {
            if (!m.isAnnotationPresent(org.junit.Test::class.java)) continue
            try { m.invoke(inst); p++ }
            catch (e: Throwable) { f++; failures += "${cn.substringAfterLast('.')}.${m.name}\n      ${e.cause ?: e}" }
        }
        pass += p; fail += f
        println("%-28s %2d passed %s".format(cn.substringAfterLast('.'), p, if (f > 0) "$f FAILED" else ""))
    }
    if (failures.isNotEmpty()) { println("\nFailures:"); failures.forEach { println("  - $it") } }
    println("\nTOTAL: $pass passed, $fail failed")
    if (fail > 0) System.exit(1)
}
