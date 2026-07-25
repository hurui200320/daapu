plugins {
    kotlin("jvm") version "2.4.0"
}

group = "info.skyblond"
version = "1.0-SNAPSHOT"

dependencies {
    implementation("ch.qos.logback:logback-classic:1.5.38")
    implementation(project(":xmppkt"))
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(25)
}

tasks.test {
    useJUnitPlatform()
}