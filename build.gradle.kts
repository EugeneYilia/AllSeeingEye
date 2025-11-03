import org.jetbrains.kotlin.gradle.dsl.JvmTarget

import org.gradle.api.artifacts.repositories.MavenArtifactRepository

val kotlin_version: String by project
val logback_version: String by project
val serialization_version: String by project
val kmongo_version: String by project

allprojects {
    repositories.all {
        (this as? MavenArtifactRepository)?.let { it.isAllowInsecureProtocol = true }
    }
}

plugins {
    kotlin("jvm") version "2.1.10"
    kotlin("plugin.serialization") version "1.8.20"
    id("io.ktor.plugin") version "3.2.0"
}

group = "com.capitalEugene"
version = "0.0.1"

application {
    mainClass.set("io.ktor.server.netty.EngineMain")
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
    implementation("io.netty:netty-resolver-dns-native-macos:4.2.2.Final:osx-x86_64")

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

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(23))
    }
}

kotlin {
    jvmToolchain(23)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_23)
    }
}

tasks.register<JavaExec>("downloadHistoricalKLine") {
    group = "application"
    mainClass.set("com.capitalEugene.backTest.DownloadHistoricalKLineKt")
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.register<JavaExec>("runApplication") {
    group = "application"
    mainClass.set("com.capitalEugene.ApplicationKt")
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.register<JavaExec>("runShengMartinStrategyTest") {
    group = "application"
    mainClass.set("com.capitalEugene.backTest.sheng.ShengBacktestSummaryFixed")
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.register<JavaExec>("runShengTradingFollowStrategyTest") {
    group = "application"
    mainClass.set("com.capitalEugene.backTest.sheng.ShengTrendBacktest")
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.register<JavaExec>("runShengAntiMartingaleBacktest") {
    group = "application"
    mainClass.set("com.capitalEugene.backTest.sheng.ShengAntiMartingaleBacktest")
    classpath = sourceSets["main"].runtimeClasspath
}