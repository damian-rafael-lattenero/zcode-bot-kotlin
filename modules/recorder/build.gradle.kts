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
    mainClass.set("dev.lattenero.bot.recorder.MainKt")
}

dependencies {
    implementation(project(":modules:contract-types"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kap.core)
    implementation(libs.kap.resilience)
    testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
