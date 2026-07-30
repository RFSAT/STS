package android.os

object Build {
    val MODEL: String = "stub"
    val MANUFACTURER: String = "stub"

    object VERSION { @JvmStatic val SDK_INT: Int = 34; @JvmStatic val RELEASE: String = "14" }
    object VERSION_CODES { const val O = 26; const val Q = 29; const val R = 30; const val TIRAMISU = 33; const val P = 28; const val S = 31 }
}
