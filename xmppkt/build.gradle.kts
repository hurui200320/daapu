plugins {
    kotlin("jvm") version "2.4.0"
}

group = "info.skyblond"
version = "1.0-SNAPSHOT"

// The latest release is 4.4.8 from 2024 Apr
// The 4.5.0-rc1 is release at 2025 Nov
val smackVersion = "4.5.0-rc1"

dependencies {
    // logging
    implementation("org.slf4j:slf4j-api:2.0.18")

    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

    // core (RFC 6120)
    api("org.igniterealtime.smack:smack-java11:${smackVersion}")
    // TPC connection
    api("org.igniterealtime.smack:smack-tcp:${smackVersion}")
    // im (RFC 6121)
    api("org.igniterealtime.smack:smack-im:${smackVersion}")
    // some extensions like PubSub, PEP and Carbons
    api("org.igniterealtime.smack:smack-extensions:${smackVersion}")
    // smack-omemo with signal impl
    api("org.igniterealtime.smack:smack-omemo:${smackVersion}")
    api("org.igniterealtime.smack:smack-omemo-signal:${smackVersion}")
    // The signal protocol library
    api("org.whispersystems:signal-protocol-java:2.8.1")
    // For encryption and decryption, modern JDK changes the type for encryption
    // bc provider keeps backward compatible
    api("org.bouncycastle:bcprov-jdk18on:1.84")

    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(25)
}

tasks.test {
    useJUnitPlatform()
}