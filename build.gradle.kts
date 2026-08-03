plugins {
    kotlin("jvm") version "2.4.0"
    kotlin("plugin.serialization") version "2.4.0"
    application
}

group = "info.skyblond"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

val exposedVersion = "1.3.1"
val flywayVersion = "13.1.0"
val testContainerVersion = "2.0.5"
val koogVersion = "1.1.1"

dependencies {
    implementation("ch.qos.logback:logback-classic:1.5.38")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-java-time:$exposedVersion")

    implementation("org.flywaydb:flyway-core:$flywayVersion")
    implementation("org.flywaydb:flyway-database-postgresql:$flywayVersion")

    implementation("com.zaxxer:HikariCP:7.1.0")
    implementation("org.postgresql:postgresql:42.7.13")

    implementation("ai.koog:koog-agents:$koogVersion")
    implementation("ai.koog:agents-features-memory:$koogVersion")

    testImplementation(kotlin("test"))
    testImplementation("org.testcontainers:testcontainers-postgresql:$testContainerVersion")
    testImplementation("ai.koog:agents-test:$koogVersion")
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
