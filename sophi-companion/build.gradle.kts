import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.4.0"
    kotlin("plugin.serialization") version "2.4.0"
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
    implementation(compose.material3)
    implementation("dev.sophi:sophi-sdk:1.0.0-SNAPSHOT")
    implementation("dev.sophi:sophi-hub:1.0.0-SNAPSHOT")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
    testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
    testImplementation("io.mockk:mockk-jvm:1.13.12")
}

tasks.test {
    useJUnitPlatform()
}

// Gradle 9.x's stricter task-output validation flags an implicit dependency that Compose
// Multiplatform 1.9.0's Gradle plugin doesn't declare explicitly between the per-format
// packaging tasks and the shared :packageAppImage output directory they all read from.
//
// This MUST use lazy task configuration. An eager `tasks.findByName(name)?.dependsOn(...)`
// here silently does nothing: the Compose plugin registers packageDmg/packageDeb/packageMsi
// after this script is evaluated, so findByName returns null for every one of them and the
// dependency is never wired. `matching { }.configureEach { }` applies to tasks registered later.
val packageFormatTasks = setOf("packageDmg", "packageMsi", "packageDeb", "packageAppImageAsAppImage")
tasks.matching { it.name in packageFormatTasks }.configureEach {
    dependsOn("packageAppImage")
}

compose.desktop {
    application {
        mainClass = "dev.sophi.companion.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Deb, TargetFormat.AppImage, TargetFormat.Msi)
            packageName = "SophiCompanion"
            packageVersion = "1.0.0"
            description = "Sophi's OS tray companion — chat, sessions, MCP, and goals, at a glance."
            macOS {
                iconFile.set(project.file("src/main/resources/icons/logo.icns"))
                bundleID = "dev.sophi.companion"
            }
            windows {
                iconFile.set(project.file("src/main/resources/icons/logo.ico"))
            }
            linux {
                iconFile.set(project.file("src/main/resources/icons/logo.png"))
            }
        }
    }
}
