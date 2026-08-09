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
val langchain4jVersion = "1.18.1"
// the MCP module is still on the beta version line while core is GA
val langchain4jMcpVersion = "1.18.1-beta28"
val ktorVersion = "3.3.3"

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

    implementation("dev.langchain4j:langchain4j-open-ai:$langchain4jVersion")
    // explicit: the reasoning-dialect rewrite wraps JdkHttpClient (the
    // default SSE transport langchain4j-open-ai already pulls in at runtime)
    implementation("dev.langchain4j:langchain4j-http-client-jdk:$langchain4jVersion")
    // MCP tools (#8): pinned per the #3 spike (still beta while core is GA)
    implementation("dev.langchain4j:langchain4j-mcp:$langchain4jMcpVersion")

    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktorVersion")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktorVersion")
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
