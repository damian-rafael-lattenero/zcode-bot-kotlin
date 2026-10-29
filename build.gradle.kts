import dev.detekt.gradle.extensions.DetektExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.detekt) apply false
}

group = "dev.lattenero"
version = "0.1.0"

subprojects {
    group = rootProject.group
    version = rootProject.version

    repositories { mavenCentral() }

    // detekt 2.0 runs its analysis in isolated workers on any JDK >= 17 —
    // the whole build is one JVM now (toolchain 25, same as kap), no
    // daemon-version dance (the 1.23 guard that disabled detekt locally
    // on JDK >= 24 is gone with the upgrade).
    pluginManager.withPlugin("dev.detekt") {
        configure<DetektExtension> {
            buildUponDefaultConfig = true
            // JVM-only: analyze main sources; tests stay out of scope (kap house rule).
            source.setFrom("src/main/kotlin")
        }
    }
}
