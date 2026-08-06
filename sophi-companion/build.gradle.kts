import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.4.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0"
    id("org.jetbrains.compose") version "1.9.0"
}

group = "dev.sophi"
version = "1.0.0-SNAPSHOT"

repositories {
    google()
    mavenCentral()
    mavenLocal()
    // sophi-ai depends on Spring AI 2.0.0-RC1 (a milestone build), same repo the root pom.xml
    // declares under <repositories> — without this, resolving sophi-sdk's transitive deps
    // fails on a clean machine / with --refresh-dependencies even though `mvn install` succeeded.
    maven("https://repo.spring.io/milestone")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("dev.sophi:sophi-sdk:1.0.0-SNAPSHOT")
    testImplementation(kotlin("test"))
    testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
    testImplementation("io.mockk:mockk-jvm:1.13.12")
}

tasks.test {
    useJUnitPlatform()
}

compose.desktop {
    application {
        mainClass = "dev.sophi.companion.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Deb, TargetFormat.AppImage, TargetFormat.Msi)
            packageName = "SophiCompanion"
            packageVersion = "1.0.0"
        }
    }
}
