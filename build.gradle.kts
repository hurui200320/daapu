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

val ktorVersion = "3.5.1"
val exposedVersion = "1.3.1"
val flywayVersion = "13.1.0"
val testContainerVersion = "2.0.5"
val koogVersion = "1.1.1"

dependencies {
    implementation("ch.qos.logback:logback-classic:1.5.38")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
    implementation("io.ktor:ktor-server-sessions:$ktorVersion")

    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-java-time:$exposedVersion")

    implementation("org.flywaydb:flyway-core:$flywayVersion")
    implementation("org.flywaydb:flyway-database-postgresql:$flywayVersion")

    implementation("com.zaxxer:HikariCP:7.1.0")
    implementation("org.postgresql:postgresql:42.7.13")

    implementation("org.mindrot:jbcrypt:0.4")

    implementation("ai.koog:koog-agents:$koogVersion")
    implementation("ai.koog:agents-features-memory:$koogVersion")
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
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
