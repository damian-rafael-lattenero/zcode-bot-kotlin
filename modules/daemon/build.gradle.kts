plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.detekt)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("dev.lattenero.bot.daemon.MainKt")
}

dependencies {
    implementation(project(":modules:contract-types"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.slf4j)
    implementation(libs.kap.core)
    implementation(libs.kap.resilience)
    implementation(libs.kotlin.logging)
    implementation(libs.logback.classic)
    implementation(libs.logstash.logback.encoder)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotest.property)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
