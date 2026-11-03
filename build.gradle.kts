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

    repositories {
        // TEMPORARY (U5a, 3/11): kap 5.0.0 (kap-stream with its Backpressure
        // ADT) resolves from this machine's mavenLocal until the Central
        // release — the dogfooding dependency is real, the publication is not
        // ours to make. Delete this line the moment kap 5.0.0 hits Central.
        //
        // Probed 3/11 (kap-first sweep 2): Central's maven-metadata.xml still
        // lists 4.2.0 as the release (lastUpdated 2026-10-29) and the
        // kap-core/5.0.0 POM answers 404 — mavenLocal stays the documented
        // fallback until the publication lands.
        mavenLocal()
        mavenCentral()
    }

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
