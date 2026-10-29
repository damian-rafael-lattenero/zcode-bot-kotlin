import io.gitlab.arturbosch.detekt.extensions.DetektExtension

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

    pluginManager.withPlugin("io.gitlab.arturbosch.detekt") {
        configure<DetektExtension> {
            buildUponDefaultConfig = true
            // JVM-only: analyze main sources; tests stay out of scope (kap house rule).
            source.setFrom("src/main/kotlin")
        }
        // detekt 1.23 embeds a Kotlin compiler whose bundled IntelliJ cannot parse
        // a daemon JDK >= 24 ("IllegalArgumentException: 25.0.5") and its targets
        // stop at 21. CI runs the Gradle daemon on JDK 21, so the gate is enforced
        // there; a local daemon on a newer JDK skips detekt loudly (kap's guard).
        val daemonMajor = JavaVersion.current().majorVersion.toInt()
        tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
            jvmTarget = "21"
            if (daemonMajor >= 24) {
                enabled = false
                doFirst { logger.warn("detekt skipped: daemon JDK $daemonMajor is newer than detekt 1.23 supports; CI runs it on JDK 21") }
            }
        }
    }
}
