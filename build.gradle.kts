plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    id("maven-publish")
}

group = "com.github.ufec"
version = "0.1.0"

kotlin {
    android {
        namespace = "me.ethanxu.typesafe.sdk"
        compileSdk = 37
        minSdk = 29

        // AGP 9's KMP plugin does not enable host tests by default. Without this,
        // commonTest is silently not executed.
        withHostTest {}
    }

    // KMP's android target does not inherit the Java version configured on the
    // Android plugin; it defaults to the build JDK's target (bytecode 70.0 on a
    // JDK 26 host), which consumers compiling with `--release 17` then reject.
    // jvmToolchain is the canonical KMP fix: pin the JDK used to compile, and the
    // bytecode target follows.
    jvmToolchain(17)

    sourceSets {
        commonMain.dependencies {
            // JsonElement (part of EntryType) and suspend functions appear in the
            // public API, so these are exposed with `api`.
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.coroutines.core)

            // TypeSafeClient accepts an injected HttpClient for testing, which puts
            // the Ktor core types on the API surface as well.
            api(libs.ktor.client.core)
        }

        // The HTTP engine is platform-specific: the OkHttp engine only runs on
        // JVM/Android, so it lives in androidMain and reaches commonMain through
        // expect/actual.
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
    }
}

publishing {
    repositories {
        // Kept intentionally empty. JitPack builds this repository on demand and
        // re-coordinates the artifacts published to the local Maven repository, so
        // no remote target is needed here. Add one only if you start publishing
        // somewhere else (for example Maven Central) as well.
    }
}
