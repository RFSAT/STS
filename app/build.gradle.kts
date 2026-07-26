plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rfsat.sts"
    compileSdk = 36

    defaultConfig {
        // Play listing identity. The applicationId is PERMANENT once
        // published; the Kotlin/resource namespace stays com.rfsat.sts.
        applicationId = "com.STSC"
        minSdk = 26
        targetSdk = 36
        // VERSIONING RULE for this project — follow it on every release:
        //
        //   <brand>.<major>.<minor>
        //     brand  1 = STS. Does not change.
        //     major  incremented when a FEATURE is added; minor resets to 0.
        //     minor  incremented when a CORRECTION is made.
        //
        //   versionCode increments on EVERY build that leaves this machine,
        //   without exception. Play rejects a bundle whose code is not
        //   strictly greater than the last uploaded one, and a code reused
        //   during testing is impossible to tell apart afterwards.
        //
        // 1.1.0 — feature: real launcher and Play Store icon, generated from
        //         one drawing (tools/generate_icons.py) into the 512 px store
        //         asset, the adaptive foreground at five densities, and the
        //         Android 13+ monochrome layer.
        // 1.0.1 — correction: removed res/mipmap-hdpi/README.txt, which the
        //         resource merger rejects (res accepts only .xml and .png).
        versionCode = 3
        versionName = "1.1.0"
    }

    signingConfigs {
        create("release") {
            val ksPath = System.getenv("ANDROID_KEYSTORE_PATH")
            if (ksPath != null) {
                storeFile = file(ksPath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // R8 ON per Play's optimisation recommendation: it strips
            // unreachable library code and emits the mapping file Play asks
            // for (AGP packages that into the bundle automatically).
            //
            // SAFETY: proguard-rules.pro keeps ALL com.rfsat.sts classes and
            // members. Every persisted format in this app is Gson reflection
            // over field NAMES, so renaming a field silently changes a stored
            // JSON key — no crash, no build error, just vanished profiles,
            // targets and sessions.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

            // CameraX ships native code (libimage_processing_util_jni.so).
            // This packages its symbol table into the AAB metadata, where
            // Play picks it up; metadata only, not shipped to devices.
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
            if (System.getenv("ANDROID_KEYSTORE_PATH") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    packaging {
        resources.excludes.add("META-INF/*")
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // CameraX — live target observation and still capture
    val cameraxVersion = "1.4.2"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-video:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // Profile / target / session persistence
    implementation("com.google.code.gson:gson:2.11.0")

    testImplementation("junit:junit:4.13.2")
}
