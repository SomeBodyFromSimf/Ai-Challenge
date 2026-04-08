plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlin.serialization)
    application
}

group = "com.sbfs.mcp"
version = "1.0"


application {
    mainClass.set("com.sbfs.mcp.MainKt")
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.content.negotiation)
    implementation(libs.ktor.serialization)
    implementation(libs.mcp.sdk)
    implementation(libs.slf4j.nop)
    testImplementation(kotlin("test"))
}

tasks.jar {
    manifest.attributes["Main-Class"] = "com.sbfs.mcp.MainKt"
    archiveFileName = "WeatherMcp.jar"
    from(sourceSets.main.get().output)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) }
    })
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}