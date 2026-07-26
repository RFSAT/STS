// STS — Shooting Target Scorer.
//
// Toolchain deliberately identical to the VTB project this app is modelled
// on: AGP 8.9.1 / Kotlin 2.1.0 / compileSdk 36, which is the combination
// Google Play's Android 16 (API 36) target requirement forces.
//   * compileSdk 36 needs AGP 8.9.0-rc01 or newer.
//   * The Kotlin plugin must be contemporaneous with that AGP/Gradle pair;
//     2.x also brings the K2 compiler.
// CI must run Gradle 8.11.1 or newer for AGP 8.9.x — see
// .github/workflows/android-ci.yml.
plugins {
    id("com.android.application") version "8.9.1" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
}
