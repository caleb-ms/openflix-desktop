plugins {
    kotlin("jvm") version "2.1.10"
    id("org.jetbrains.compose") version "1.6.11"
    kotlin("plugin.compose") version "2.1.10"
    kotlin("plugin.serialization") version "2.1.10"
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    // Ktor WebSocket client
    val ktorVersion = "2.3.12"
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-websockets:$ktorVersion")

    // Coroutines Swing (Main Dispatcher)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Video playback engine
    implementation("uk.co.caprica:vlcj:4.8.3")

    // Zero-conf LAN discovery (mDNS / DNS-SD)
    implementation("org.jmdns:jmdns:3.5.9")
}

compose.desktop {
    application {
        mainClass = "com.calebms.openflix.desktop.MainKt"
        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Rpm,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.AppImage,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe,
            )
            packageName = "OpenFlixDesktop"
            packageVersion = "1.0.0"
            description = "OpenFlix Desktop Companion Player"
            vendor = "Caleb MS Group"

            appResourcesRootDir.set(project.layout.projectDirectory.dir("package-resources"))

            linux {
                rpmLicenseType = "GPL-3.0"
                appCategory = "AudioVideo"
                menuGroup = "AudioVideo"
            }

            windows {
                menuGroup = "OpenFlix"
                upgradeUuid = "6d8f8d9b-3e5f-4a87-b9c2-9e2c4d123456"
            }
        }
    }
}
