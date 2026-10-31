pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "garment-colour"

// :color-engine is pure Kotlin with no Android dependency (D10); :app is
// everything Android.
include(":app", ":color-engine")
