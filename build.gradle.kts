plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.ktlint)
    id("maven-publish")
}

group = "com.github.ufec"
version = "0.2.0"

kotlin {
    android {
        namespace = "me.ethanxu.typesafe.sdk"
        compileSdk = 37
        minSdk = 29

        // AGP 9's KMP plugin does not enable host tests by default. Without this,
        // commonTest is silently not executed.
        withHostTest {}
    }

    // The library is named `-kotlin`, not `-android`, and the upstream JavaScript
    // SDK runs anywhere Node does. A JVM target keeps that promise: the same code
    // runs on a server, and it gives examples/ something it can actually execute
    // from the command line.
    jvm()

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

            // The OkHttp engine backs both of this project's targets, so it belongs
            // here rather than in a platform-specific source set. It cannot simply
            // live in a source set shared by android and jvm: Kotlin does not
            // support sharing a source set between JVM and Android targets at all.
            // See the note on defaultHttpClient.
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
