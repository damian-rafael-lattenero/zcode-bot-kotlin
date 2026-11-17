plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.detekt)
    alias(libs.plugins.sqldelight)
    application
}

kotlin {
    jvmToolchain(25)
}

application {
    mainClass.set("dev.lattenero.bot.daemon.MainKt")
}

dependencies {
    implementation(project(":modules:contract-types"))
    implementation(project(":modules:transport"))
    implementation(project(":modules:channel"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.slf4j)
    implementation(libs.kap.core)
    implementation(libs.kap.resilience)
    // kap-stream since the dogfooding: KapProcess (the effect mailbox)
    implementation(libs.kap.stream)
    implementation(libs.kotlin.logging)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.micrometer.core)
    implementation(libs.micrometer.registry.prometheus)
    // E3: OpenTelemetry lives ONLY in the daemon — the channel and transport
    // modules stay standard-library-pure (the span taps are injected lambdas)
    implementation(libs.opentelemetry.api)
    implementation(libs.opentelemetry.sdk)
    implementation(libs.opentelemetry.exporter.otlp)
    implementation(libs.logback.classic)
    implementation(libs.logstash.logback.encoder)
    implementation(libs.xerial.sqlite.jdbc)
    implementation(libs.sqldelight.jdbc.driver)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotest.property)
    testImplementation(libs.ktor.client.mock)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

// Compile-time checked SQL for the read-only SQLite reads (15 SELECTs over the
// app's tasks index and the CLI's db). The .sq CREATE TABLE blocks are
// TYPING-ONLY: both files belong to the ZCode app/CLI, the daemon never
// creates or migrates them (open_mode=1). The queries ride the caller's own
// read-only connection through a pass-through driver (db/Typed.kt).
sqldelight {
    databases {
        create("BotDb") {
            packageName.set("dev.lattenero.bot.daemon.sq")
            dialect("app.cash.sqldelight:sqlite-3-38-dialect:2.4.1")
        }
    }
}
