plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.detekt)
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    api(project(":modules:contract-types"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.slf4j)
    implementation(libs.kap.core)
    implementation(libs.kap.resilience)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.kotlin.logging)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotest.property)
    testImplementation(libs.ktor.client.mock)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
