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
    implementation(libs.logback.classic)
    implementation(libs.logstash.logback.encoder)
    implementation(libs.xerial.sqlite.jdbc)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotest.property)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
