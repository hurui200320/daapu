plugins {
    kotlin("jvm") version "2.4.0"
    kotlin("plugin.serialization") version "2.4.0"
    application
}

group = "info.skyblond"
version = "1.0-SNAPSHOT"

dependencies {
    implementation("ch.qos.logback:logback-classic:1.5.38")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    // Official Java NATS client: JetStream consumption + core request/reply
    implementation("io.nats:jnats:2.26.0")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(25)
}

application {
    mainClass.set("info.skyblond.daapu.MainKt")
}

tasks.test {
    useJUnitPlatform()
}
