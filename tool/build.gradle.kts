plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.light.sdk)
}

android {
    compileSdk = rootProject.ext["compileSdk"] as Int

    signingConfigs {
        create("lightsdkDev") {
            storeFile = file("../light-sdk/sdk/keys/lightsdk-dev.jks")
            storePassword = "android"
            keyAlias = "lightsdk-dev"
            keyPassword = "android"
            enableV3Signing = true
            enableV4Signing = true
        }
    }

    defaultConfig {
        minSdk = rootProject.ext["minSdk"] as Int
        targetSdk = rootProject.ext["targetSdk"] as Int

        // The Relay's URL is RelayConfig.URL, committed (W8). Only a debug build may point elsewhere.
        buildConfigField("String", "RELAY_URL", "\"\"")
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("lightsdkDev")
            // -Prelay.url=<url> points a debug build at another Relay, such as a local `wrangler dev`
            // Worker reached from the emulator as http://10.0.2.2:8787 (W8). It goes into the debug
            // variant's generated BuildConfig only; release, the build Light makes, never reads it.
            // src/debug's network security config allows the cleartext for the local addresses.
            val relayUrl = providers.gradleProperty("relay.url").orNull?.trimEnd('/') ?: ""
            require(relayUrl.isEmpty() || relayUrl.matches(Regex("""https://[A-Za-z0-9.:/_-]+|http://(127\.0\.0\.1|localhost|10\.0\.2\.2)(:\d+)?"""))) {
                "relay.url must be https://..., or http:// to 127.0.0.1, localhost or 10.0.2.2, got $relayUrl"
            }
            buildConfigField("String", "RELAY_URL", "\"$relayUrl\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("lightsdkDev")
        }
        // The engine benchmark (scripts/bench.sh, decision E11): the only variant with src/benchmark.
        // Not debuggable by default, since ART runs a debuggable app slower; -Pbench.debuggable=true
        // builds the same APK debuggable, for comparison. Light's builder builds release only and
        // extracts src/main only, so it never sees this code.
        create("benchmark") {
            isDebuggable = providers.gradleProperty("bench.debuggable").orNull == "true"
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("lightsdkDev")
            matchingFallbacks += listOf("release")
            // The trigger (BenchEntryPoint): the suite runs once per build id, with this many runs.
            val runs = providers.gradleProperty("bench.runs").orNull ?: "5"
            require(runs.toIntOrNull() in 1..50) { "bench.runs must be 1-50, got $runs" }
            val id = providers.gradleProperty("bench.id").orNull ?: ""
            require(id.matches(Regex("[A-Za-z0-9_-]*"))) { "bench.id must be letters, digits, _ or -" }
            buildConfigField("int", "BENCH_RUNS", runs)
            buildConfigField("String", "BENCH_ID", "\"$id\"")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        warningsAsErrors = false
        error += "RestrictedApi"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            // Perft runs millions of immutable Positions; give the test JVM room.
            it.maxHeapSize = "2g"
            // -Dperft.deep=true adds the two slowest perft counts (PerftTest).
            it.systemProperty("perft.deep", providers.systemProperty("perft.deep").getOrElse("false"))
            // -Dbench.runs=<n> runs the engine benchmark suite on this JVM (BenchSuiteJvmTest).
            providers.systemProperty("bench.runs").orNull?.let { runs -> it.systemProperty("bench.runs", runs) }
            // -Dcalibrate=<modes> runs the Level calibration (LevelCalibrationTest, docs/levels.md).
            // -Dcorrespondence.seeds=<n> runs more seeds of CorrespondencePropertyTest; -Drelay.e2e=<url>
            // runs RelayEndToEndTest against a local Worker (relay/README.md); -Drelay.phone=<url> with
            // relay.phone.dir and relay.phone.cmd runs SecondPhoneTest, a second phone against the emulator.
            for (key in listOf("correspondence.seeds", "relay.e2e", "relay.phone", "relay.phone.dir", "relay.phone.cmd", "calibrate", "calibrate.threads", "calibrate.rounds", "calibrate.karballo", "calibrate.karballo.nodes", "calibrate.levels", "calibrate.set", "calibrate.seeds", "calibrate.elos", "calibrate.stockfish", "calibrate.stockfish.nodes", "calibrate.plan", "calibrate.pgn", "calibrate.firstRound")) {
                providers.systemProperty(key).orNull?.let { value -> it.systemProperty(key, value) }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(rootProject.ext["jvmTarget"] as String)
        targetCompatibility = JavaVersion.toVersion(rootProject.ext["jvmTarget"] as String)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(rootProject.ext["jvmTarget"] as String))
    }
}

dependencies {
    implementation(project(":sdk:client"))
    testImplementation(libs.kotlin.test)
}

// Light's sdk:ui declares ML Kit and CameraX only for LightQrCodeScanner, which Chess never shows; ML
// Kit brings Play services, Firebase components and Google's Data Transport. None of it ships (decision
// log T1, lightphone/light-sdk#178). Calling the scanner would crash: a Tool that needs it drops this.
configurations.configureEach {
    exclude(group = "com.google.mlkit")
    exclude(group = "com.google.android.gms")
    exclude(group = "com.google.firebase")
    exclude(group = "com.google.android.datatransport")
    exclude(group = "com.google.android.odml")
    exclude(group = "androidx.camera")
}
