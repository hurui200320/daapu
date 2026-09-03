plugins {
    kotlin("jvm") version "2.4.0"
    kotlin("plugin.serialization") version "2.4.0"
    id("io.insert-koin.compiler.plugin") version "1.1.0"
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
// dependency injection: the container + the compiler plugin DSL
// (org.koin.plugin.module.dsl.*), see di/AppModule.kt
val koinVersion = "4.2.2"

dependencies {
    implementation("ch.qos.logback:logback-classic:1.5.38")
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.3")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    implementation(platform("io.insert-koin:koin-bom:$koinVersion"))
    implementation("io.insert-koin:koin-core")

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
    // conditional requests (ETag → 304) for the static web UI, see
    // server/WebServer.kt staticWebUi
    implementation("io.ktor:ktor-server-conditional-headers-jvm:$ktorVersion")
    // HEAD requests on GET routes (the static web UI, the /api GET routes),
    // see server/WebServer.kt module
    implementation("io.ktor:ktor-server-auto-head-response-jvm:$ktorVersion")

    // hand-pi client + MCP transport: Java engine (JDK HttpClient, TLS 1.3
    // via JSSE, no read timeout by default) + the SSE plugin (the plugin
    // lives in ktor-client-core; the shared SSE protocol in ktor-sse)
    implementation("io.ktor:ktor-client-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-java-jvm:$ktorVersion")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktorVersion")
    // the DB-backed tests start their own throwaway PostgreSQL (pgvector
    // included) — see testutil/TestDb.kt; the singleton container lives for
    // the whole test JVM and Ryuk reaps it on exit
    testImplementation("org.testcontainers:postgresql:1.21.3")
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
    }
}

application {
    mainClass.set("info.skyblond.daapu.MainKt")
    // Netty probes native transports via System.loadLibrary (JEP 472): silence
    // the restricted-native-access warning on JDK 24+ before it becomes an error
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.test {
    useJUnitPlatform()
    // docker-java (bundled by testcontainers) falls back to API 1.32, which
    // Docker Engine 29 rejects ("Minimum supported API version is 1.40"):
    // pin a version inside the daemon's window — 1.40 is the Engine's new
    // floor and every daemon since 2019 accepts it. Override with
    // -Dapi.version=... if a specific daemon needs it.
    jvmArgs("-Dapi.version=1.40")
}

koinCompiler {
    aiAssist = false
}
