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
// the official MCP Kotlin SDK, riding on ktor-client
// like the hand client below
val mcpSdkVersion = "0.15.0"
// ktor must stay uniform across the classpath: the MCP SDK depends on
// ktor-client-core 3.5.1, so the server artifacts follow the same version
val ktorVersion = "3.5.1"

dependencies {
    implementation("ch.qos.logback:logback-classic:1.5.38")
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.3")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-java-time:$exposedVersion")

    implementation("org.flywaydb:flyway-core:$flywayVersion")
    implementation("org.flywaydb:flyway-database-postgresql:$flywayVersion")

    implementation("com.zaxxer:HikariCP:7.1.0")
    implementation("org.postgresql:postgresql:42.7.13")

    // MCP tool servers (#8) through the official Kotlin SDK
    implementation("io.modelcontextprotocol:kotlin-sdk-client:$mcpSdkVersion")

    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktorVersion")

    // hand-pi client: CIO engine + the SSE plugin (the
    // plugin lives in ktor-client-core; the shared SSE protocol in ktor-sse)
    implementation("io.ktor:ktor-client-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-cio-jvm:$ktorVersion")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktorVersion")
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
    }
}

application {
    mainClass.set("info.skyblond.daapu.MainKt")
}

tasks.test {
    useJUnitPlatform()
}
