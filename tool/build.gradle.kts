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

        manifestPlaceholders["sdkVersion"] = property("sdkVersion") as String
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("lightsdkDev")
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
            for (key in listOf("calibrate", "calibrate.threads", "calibrate.rounds", "calibrate.karballo", "calibrate.karballo.nodes", "calibrate.levels", "calibrate.set", "calibrate.seeds", "calibrate.elos")) {
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
