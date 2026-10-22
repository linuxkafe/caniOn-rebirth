// caniOn Rebirth — open-source ZEV OBD client.
//
// Two modules:
//   :core  — pure JVM protocol engine (no Android dependency, unit tested)
//   :app   — Android shell that binds Bluetooth SPP to :core

plugins {
    id("com.android.application") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}

include(":core")
include(":app")
