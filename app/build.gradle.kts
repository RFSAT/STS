plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rfsat.sts"
    compileSdk = 36

    defaultConfig {
        // Play listing identity, and PERMANENT once published — an app's
        // applicationId cannot be changed afterwards without shipping a
        // different app and losing every install and review. The Kotlin and
        // resource namespace stays com.rfsat.sts, so no source moves.
        //
        // Play requires at least two segments separated by a dot, letters,
        // digits and underscores only, and a letter at the start of every
        // segment. "com.STS" satisfies all of that; uppercase is unusual but
        // legal, and matches the com.VTBC convention already in use.
        applicationId = "com.STS"
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
        // 1.6.0 — feature: a diagnostic log reachable from the Home screen,
        //         hideable in Settings, with a Report button that shares the
        //         log TOGETHER with the active face, rules, gauge and
        //         equipment. Detection now logs its own parameters and every
        //         candidate it accepted or rejected.
        //       — correction: the app no longer stays silent when the selected
        //         target face does not match the target in the picture.
        //         TargetGeometryCheck looks for printed rings outside the
        //         proposed registration box; if they are there, the box is too
        //         small and the wrong face is selected. Photographing an
        //         air-PISTOL card with the air RIFLE face chosen put the box
        //         on the FIVE ring (188 px against the ring's 194 px), halved
        //         every distance, scored the centre shot a 10 and every other
        //         shot a miss — a complete, confident, wrong score sheet from
        //         a wrong menu selection.
        //       — correction: the scoring-area limit went from 1.02x to 1.10x
        //         the outer ring, so a shot just outside the last ring is
        //         reported as the miss it is. Card furniture sits much further
        //         out; the logo that prompted the limit was at 1.21x.
        // 1.5.3 — corrections from running the detector against two real
        //         uploaded targets rather than synthetic data:
        //   - printed ring NUMERALS were being scored as shots. Radial
        //     normalisation removes rings but not numerals, which occupy four
        //     angles out of 360 and barely move a radial median. A synthetic
        //     face returned 22 candidates for 5 real shots, 17 of them digits.
        //     Now rejected by testing each candidate for rotational twins.
        //     A four-fold median over the whole image was tried first and
        //     LOST two of five real shots on a photograph — see the note in
        //     HoleDetector for why the per-candidate form survives real
        //     lighting and registration error where the global one does not.
        //   - absolute detection now looks only inside the outermost ring.
        //     A club logo on a real target was reported as a shot, and the
        //     card furniture was raising the noise floor: excluding it
        //     dropped the robust sigma from 4.4 to 3.0 on that photograph,
        //     which was enough to find a faint fifth hole that had been
        //     missed.
        // 1.5.2 — corrections for three failures found in the field:
        //   (1) auto-detect skewed the box on square-on targets. It was
        //       APPLYING a tilt inferred from how elliptical the aiming mark
        //       measured, and a shot-up mark measures a few percent
        //       elliptical from segmentation noise alone — which acos turns
        //       into 14 to 25 degrees. The estimate is now offered on a
        //       button, never imposed. Mark detection also gained a
        //       centrality weighting and a central-crop retry, so a dark
        //       bench behind a white card no longer wins the histogram.
        //   (2) ring numerals never appeared in the targets database: the
        //       gate demanded ~60 px of annulus against the 33 px a 230 dp
        //       preview actually has, on every face at every density. The
        //       glyph now scales to the annulus instead.
        //   (3) hit detection bore no resemblance to reality. Two causes:
        //       out-of-frame pixels were averaged into the contrast windows,
        //       inventing holes around the rim wherever the photo did not
        //       cover the whole card; and nothing suppressed the printed
        //       rings, which carry FOUR TIMES the contrast of a real hole.
        //       Fixed by a validity-masked integral image and by subtracting
        //       the radial median, which removes everything rotationally
        //       symmetric — every ring, exactly — and leaves the holes.
        // 1.5.1 — correction: the first real CI run compiled everything and
        //         passed 75 of 76 tests. The failure was the TEST, not the
        //         app: it asserted that a 10 degree tilt is recovered, but
        //         suggestedTransform deliberately ignores anything under 11.4
        //         degrees. That gate is now the named, documented constant
        //         MIN_ELLIPTICITY_TO_SUGGEST instead of a bare 1.02, and the
        //         test asserts the behaviour that exists.
        // 1.5.0 — feature: individual TILT and ROTATION controls on the
        //         registration box, laid out the way a phone camera app lays
        //         them out. Box plus these is seven degrees of freedom, which
        //         is every one a flat target through a normal lens needs; the
        //         eighth, shear, cannot arise with square pixels. The tilt is
        //         seeded from the ellipticity of the detected aiming mark.
        // 1.4.0 — feature: registration by a square bounding box with
        //         draggable top-left and bottom-right handles, placed
        //         automatically from the detected black aiming mark and
        //         expanded to the whole scoring area by the face's own
        //         published ratio. Corner registration stays available for
        //         angled views, and the detector says when it is needed.
        //       — feature: ring values printed on the target plot at all four
        //         cardinal points, so the targets database shows scores on
        //         the circles.
        // 1.3.1 — correction: the photo-upload scoring entry points existed
        //         since 1.2.0 but were styled borderless — flat text under a
        //         filled button — and users could not find them. Both are now
        //         ordinary buttons, the Home one comes FIRST, and both read
        //         "Upload a target photo to score".
        // 1.3.0 — feature: Play Store listing material — short and full
        //         descriptions and the 1024x500 feature graphic, in play/.
        //         The graphic is generated from the SAME drawing as the icon
        //         (tools/generate_feature_graphic.py imports the icon's
        //         artwork), so the listing and the home screen cannot drift.
        // 1.2.1 — correction: applicationId is now com.STS, and CI builds
        //         release artefacts only (no debug APK). Signing degrades to
        //         unsigned rather than failing when no keystore is present.
        // 1.2.0 — feature: score a target from a photograph after the session
        //         (ImportActivity), and a shot distribution histogram shown on
        //         Results, live on Session, on the import screen and in the
        //         shared report.
        //       — correction found while writing its tests: ImageLoader's
        //         sample size used the common idiom that stops one step early
        //         and returns an image LARGER than the requested cap, so a
        //         50 MP photo decoded at 4080 px instead of 3000.
        // 1.1.0 — feature: real launcher and Play Store icon, generated from
        //         one drawing (tools/generate_icons.py) into the 512 px store
        //         asset, the adaptive foreground at five densities, and the
        //         Android 13+ monochrome layer.
        // 1.0.1 — correction: removed res/mipmap-hdpi/README.txt, which the
        //         resource merger rejects (res accepts only .xml and .png).
        versionCode = 13
        versionName = "1.6.0"
    }

    // Resolved once, here, rather than re-read from the environment in two
    // places. CI sets these variables unconditionally, so an absent secret
    // arrives as an EMPTY STRING and not as null — and file("") resolves to
    // the project directory, which would be accepted as a keystore path and
    // then fail deep inside the signing task with an unhelpful message. Blank
    // and missing-on-disk are both treated as "no keystore".
    val keystoreFile = System.getenv("ANDROID_KEYSTORE_PATH")
        ?.takeIf { it.isNotBlank() }
        ?.let { file(it) }
        ?.takeIf { it.exists() }

    signingConfigs {
        create("release") {
            if (keystoreFile != null) {
                storeFile = keystoreFile
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        // Kept for local work in Android Studio. CI never builds it: the
        // workflow runs the unit tests against the RELEASE variant and
        // assembles only the release APK and the Play bundle, so nothing that
        // leaves the build machine is ever a debug artefact.
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
            // Unsigned when no keystore is configured: the build still
            // succeeds and still produces both artefacts, which is what makes
            // the workflow useful on a fork or a pull request. An unsigned
            // bundle cannot be uploaded to Play, and the workflow says so
            // rather than leaving it to be discovered at upload time.
            if (keystoreFile != null) {
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
