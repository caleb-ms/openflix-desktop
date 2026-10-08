import java.io.File
import java.net.HttpURLConnection
import java.net.URI

plugins {
    kotlin("jvm") version "2.1.10"
    id("org.jetbrains.compose") version "1.6.11"
    kotlin("plugin.compose") version "2.1.10"
    kotlin("plugin.serialization") version "2.1.10"
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
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
        buildTypes.release.proguard {
            isEnabled.set(false)
        }
        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Rpm,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.AppImage,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe,
            )
            packageName = "OpenFlixDesktop"
            packageVersion = "1.0.1"
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

// ============================================================================
// CUSTOM AUTOMATED TASK: Standalone AppImage Bundler
// ============================================================================
tasks.register("createAppImage") {
    group = "distribution"
    description = "Packages the unpacked jpackage directory into a true, standalone .AppImage file."

    // Ensures Compose finishes building the base native layout first
    dependsOn("packageReleaseAppImage")

    doLast {
        val buildDir = project.layout.buildDirectory.get().asFile
        val baseAppDir = listOf(
            File(buildDir, "compose/binaries/main-release/app"),
            File(buildDir, "compose/binaries/main/app")
        ).firstOrNull { it.exists() && it.listFiles()?.any { f -> f.isDirectory } == true }
            ?: throw GradleException("Could not locate the generated app structure inside build/compose/binaries/. Please check if package task succeeded.")

        val appDir = baseAppDir.listFiles()?.firstOrNull { it.isDirectory }
            ?: throw GradleException("Could not locate the app folder inside ${baseAppDir.absolutePath}")

        println("Processing AppDir directory: ${appDir.absolutePath}")

        // 1. Discover the exact executable binary within bin/
        val binDir = File(appDir, "bin")
        val binName = binDir.listFiles()?.firstOrNull { it.isFile && !it.name.startsWith(".") }?.name
            ?: throw GradleException("Unable to find the primary executable binary inside ${binDir.absolutePath}")

        println("Discovered executable name: $binName")

        // 2. Inject the standard AppRun shell wrapper into root
        val appRunFile = File(appDir, "AppRun")
        appRunFile.writeText(
            """
            #!/bin/sh
            HERE="${'$'}(dirname "${'$'}(readlink -f "${'$'}0")")"
            exec "${'$'}HERE/bin/$binName" "${'$'}@"
            """.trimIndent().trim() + "\n"
        )
        appRunFile.setExecutable(true)

        // 3. Inject the .desktop integration file
        val desktopFile = File(appDir, "$binName.desktop")
        desktopFile.writeText(
            """
            [Desktop Entry]
            Type=Application
            Name=OpenFlix Desktop
            Exec=AppRun
            Icon=$binName
            Categories=AudioVideo;Player;
            Terminal=false
            """.trimIndent().trim() + "\n"
        )

        // 4. Locate icon
        val targetIcon = File(appDir, "$binName.png")
        val iconSrc = File(project.projectDir, "src/main/resources/icon.png")
        if (iconSrc.exists()) {
            iconSrc.copyTo(targetIcon, overwrite = true)
        } else {
            targetIcon.createNewFile()
        }

        // 5. Download appimagetool binary into local build tooling cache if absent
        val toolFile = File(buildDir, "tools/appimagetool-x86_64.AppImage")
        if (!toolFile.exists()) {
            toolFile.parentFile.mkdirs()
            println("Downloading official appimagetool binary...")
            val initialUrl = "https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-x86_64.AppImage"
            var url = URI(initialUrl).toURL()
            var conn = url.openConnection() as HttpURLConnection
            var code = conn.responseCode
            var redirects = 0
            while ((code == HttpURLConnection.HTTP_MOVED_TEMP ||
                    code == HttpURLConnection.HTTP_MOVED_PERM ||
                    code == HttpURLConnection.HTTP_SEE_OTHER) && redirects < 5) {
                val location = conn.getHeaderField("Location")
                url = URI(location).toURL()
                conn = url.openConnection() as HttpURLConnection
                code = conn.responseCode
                redirects++
            }
            conn.inputStream.use { input ->
                toolFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            toolFile.setExecutable(true)
        }

        // 6. Fire up appimagetool to compress the files into a single bundle
        println("Generating compressed native .AppImage file...")
        val distributionFolder = File(buildDir, "distributions")
        distributionFolder.mkdirs()
        val finalAppImageFile = File(distributionFolder, "OpenFlixDesktop-1.0.1-x86_64.AppImage")

        providers.exec {
            environment(mapOf("ARCH" to "x86_64", "APPIMAGE_EXTRACT_AND_RUN" to "1"))
            commandLine(
                toolFile.absolutePath,
                appDir.absolutePath,
                finalAppImageFile.absolutePath
            )
        }.result.get()

        println("\n✅ Standalone AppImage ready! File location:")
        println(" 👉 ${finalAppImageFile.absolutePath}")
    }
}
