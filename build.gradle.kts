val kotlin_version: String by project
val logback_version: String by project
val serialization_version: String by project
val kmongo_version: String by project

plugins {
    kotlin("jvm") version "2.1.10"
    kotlin("plugin.serialization") version "1.8.20"
    id("io.ktor.plugin") version "3.2.0"
}

group = "com.capitalEugene"
version = "0.0.1"

application {
    mainClass = "io.ktor.server.netty.EngineMain"
    applicationDefaultJvmArgs = listOf(
        "-Dio.ktor.development=false",
        "-Dio.ktor.watch.paths="
    )
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-server-core-jvm")
    implementation("io.ktor:ktor-server-netty")
    implementation("ch.qos.logback:logback-classic:$logback_version")
    implementation("io.ktor:ktor-server-core")
    implementation("io.ktor:ktor-client-core")
    implementation("io.ktor:ktor-client-cio")
    implementation("io.ktor:ktor-client-content-negotiation")
    implementation("io.ktor:ktor-serialization-kotlinx-json")
    implementation("io.ktor:ktor-server-call-logging-jvm")
    implementation("io.ktor:ktor-client-websockets")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serialization_version")
    implementation("io.ktor:ktor-server-config-yaml")
    implementation("io.lettuce:lettuce-core:6.7.1.RELEASE")
    implementation("io.ktor:ktor-server-content-negotiation") // ← 核心依赖
    implementation("io.ktor:ktor-serialization-kotlinx-json")
    implementation("com.sun.mail:jakarta.mail:2.0.1")
    // KMongo
    implementation("org.litote.kmongo:kmongo-coroutine-serialization:$kmongo_version")
    implementation("org.litote.kmongo:kmongo-coroutine:$kmongo_version")
    testImplementation("io.ktor:ktor-server-test-host")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:$kotlin_version")
}

kotlin {
    sourceSets.all {
        languageSettings.optIn("kotlinx.serialization.ExperimentalSerializationApi")
    }
}