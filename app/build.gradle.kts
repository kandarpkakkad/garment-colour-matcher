import java.util.Properties
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Release signing lives in key.properties at the root, deliberately not in
// version control. Without it a release build falls back to the debug key; the
// release workflow refuses to publish that (release.yml, "Verify signature").
val keystoreProperties = Properties().apply {
    val f = rootProject.file("key.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseKey = keystoreProperties.getProperty("storeFile") != null

// One file holds the version (versioning LLD); the code is derived from it, so
// the two cannot drift.
val appVersionName: String = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}.getProperty("versionName")
val appVersionCode: Int = appVersionName.split('.').map { it.toInt() }
    .let { (major, minor, patch) -> major * 1_000_000 + minor * 1_000 + patch }

android {
    namespace = "com.garmentcolour.app"
    compileSdk = 36 // Android 16
    // What the Docker image carries; AGP's own default (35) sends it to the network
    // for package manifests.
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.garmentcolour.app"
        // Android 16 and above only (D2).
        minSdk = 36
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        manifestPlaceholders["appLabel"] = "Garment Colour"
    }

    signingConfigs {
        // The debug key is named, not left to the Android plugin to find: on a CI
        // runner its default lookup mints a random key per build, and an APK signed
        // by a different key cannot be installed over the last one — only after an
        // uninstall, which deletes every capture.
        val projectDebugKey = rootProject.file("debug.keystore")
        if (projectDebugKey.exists()) {
            getByName("debug") {
                storeFile = projectDebugKey
                // Not secrets: every Android debug keystore uses these.
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        if (hasReleaseKey) {
            create("release") {
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
            }
        }
    }

    buildTypes {
        debug {
            // A separate app: debug and release install side by side with
            // separate captures.
            applicationIdSuffix = ".debug"
            manifestPlaceholders["appLabel"] = "Garment Colour debug"
        }
        release {
            // Off: both model runtimes reach into their Java classes from native
            // code, and keep rules for them are one more thing to get wrong for
            // a few megabytes.
            isMinifyEnabled = false
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
            if (!hasReleaseKey) logger.warn("key.properties not found: the release build is signed with the debug key")
        }
    }

    // arm64 for phones, x86_64 for an emulator. Both model runtimes ship native
    // libraries per ABI, so one APK per ABI keeps each to its own (models LLD §5).
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    // MediaPipe maps its model straight from the APK and cannot from a
    // compressed entry.
    androidResources {
        noCompress.addAll(listOf("tflite", "onnx"))
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    // Committed, so a change to an entity shows in review (storage HLD).
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":color-engine"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.navigation.compose)

    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.coroutines.android)
    implementation(libs.mediapipe.tasks.vision)
    implementation(libs.onnxruntime.android)

    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("failed", "skipped")
        exceptionFormat = TestExceptionFormat.FULL
    }
}
