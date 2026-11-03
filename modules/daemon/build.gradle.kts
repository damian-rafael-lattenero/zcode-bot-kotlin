plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.detekt)
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
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotest.property)
    testImplementation(libs.ktor.client.mock)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
