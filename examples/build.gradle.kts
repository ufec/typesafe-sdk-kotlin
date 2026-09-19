plugins {
    // Applied by bare id: the Kotlin Gradle Plugin is already on the build
    // classpath via the root project's kotlin.multiplatform plugin, and asking
    // for a versioned alias of a sibling plugin from the same artifact fails.
    id("org.jetbrains.kotlin.jvm")
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Deliberately depends on the module in this repository rather than on a
    // published coordinate, so the example always exercises the current source.
    // The upstream JavaScript SDK's demo imports from `../src` for the same
    // reason.
    implementation(project(":"))
}

application {
    mainClass.set("DemoKt")
}
