import org.jetbrains.compose.desktop.application.dsl.TargetFormat

val desktopPackageName = "YinZhaoDesktop"
val desktopDisplayName = "音爪"
val desktopPackageVendor = "YinZhao"
val desktopPackageDescription = "YinZhao Desktop Client"
val desktopAppVersion = "0.2.0"
val desktopAppVersionCode = 11L
val javafxVersion = "17.0.2"
val javafxPlatform = when {
    System.getProperty("os.name").lowercase().contains("win") -> "win"
    System.getProperty("os.name").lowercase().contains("mac") -> "mac"
    else -> "linux"
}

plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.serialization") version "2.2.21"
    id("org.jetbrains.compose") version "1.9.2"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21"
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":shared"))

    implementation(compose.desktop.currentOs)
    implementation(compose.material3)

    implementation("io.ktor:ktor-client-cio:3.4.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.openjfx:javafx-base:$javafxVersion:$javafxPlatform")
    implementation("org.openjfx:javafx-graphics:$javafxVersion:$javafxPlatform")
    implementation("org.openjfx:javafx-swing:$javafxVersion:$javafxPlatform")
    implementation("org.openjfx:javafx-media:$javafxVersion:$javafxPlatform")
}

compose.desktop {
    application {
        mainClass = "com.openclaw.musicworker.desktop.MainKt"
        jvmArgs += listOf(
            "-Dmusicworker.desktop.version=$desktopAppVersion",
            "-Dmusicworker.desktop.versionCode=$desktopAppVersionCode",
        )

        nativeDistributions {
            targetFormats(TargetFormat.Exe)
            packageName = desktopPackageName
            packageVersion = desktopAppVersion
            description = desktopPackageDescription
            vendor = desktopPackageVendor

            windows {
                iconFile.set(project.layout.projectDirectory.file("src/main/resources/desktop-icon.ico"))
                menu = true
                shortcut = true
                menuGroup = desktopPackageVendor
            }
        }
    }
}
