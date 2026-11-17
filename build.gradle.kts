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
        // kap 5.0.0 hit Maven Central on 3/11 (release v5.0.0; the four
        // modules answer 200 on repo1) — the temporary mavenLocal fallback
        // from the dogfooding days is retired with it.
        mavenCentral()
    }

    // detekt 2.0 runs its analysis in isolated workers on any JDK >= 17 —
    // the whole build is one JVM now (toolchain 25, same as kap), no
    // daemon-version dance (the 1.23 guard that disabled detekt locally
    // on JDK >= 24 is gone with the upgrade).
    pluginManager.withPlugin("dev.detekt") {
        dependencies {
            // The doctrine's own rules (CE-arm, ManualWaitLoop, VolatileRMW) run
            // on this repo — the audit 17/11 P2: built+published, never
            // self-applied anywhere.
            "detektPlugins"("io.github.damian-rafael-lattenero:kap-detekt-rules:5.5.0")
        }
        configure<DetektExtension> {
            buildUponDefaultConfig = true
            config.setFrom(rootProject.files("config/detekt/kap-rules.yml"))
            // JVM-only: analyze main sources; tests stay out of scope (kap house rule).
            source.setFrom("src/main/kotlin")
        }
    }
}
