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
    mainClass.set("dev.lattenero.bot.recorder.MainKt")
}

dependencies {
    implementation(project(":modules:contract-types"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
