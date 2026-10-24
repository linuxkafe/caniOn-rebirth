// caniOn Rebirth — open-source ZEV OBD client.
//
// Two modules:
//   :core  — pure JVM protocol engine (no Android dependency, unit tested)
//   :app   — Android shell that binds Bluetooth SPP to :core

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

plugins {
    id("com.android.application") version "8.1.4" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}

include(":core")
include(":app")
