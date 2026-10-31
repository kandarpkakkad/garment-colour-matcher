import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The colour maths, with no Android dependency (D10): images are float planes,
// labels are byte maps. Its tests run on any JVM, and FixtureParityTest holds it
// to the Python reference in spike/.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(libs.junit)
}

tasks.test {
    // RealDngCheck renders real DNGs from this directory when it is set (tool/test_in_docker.sh dng).
    systemProperty("gcm.dngDir", System.getenv("GCM_DNG_DIR") ?: "")
    maxHeapSize = "3g"
    testLogging {
        events("failed", "skipped")
        exceptionFormat = TestExceptionFormat.FULL
        // FixtureParityTest prints the worst ΔE2000 it saw.
        showStandardStreams = true
    }
}
