import org.jetbrains.compose.desktop.application.dsl.TargetFormat

val desktopPackageName = "YinZhaoDesktop"
val desktopDisplayName = "音爪"
val desktopPackageVendor = "YinZhao"
val desktopPackageDescription = "YinZhao Desktop Client"
val desktopAppVersion = "0.2.1"
val desktopAppVersionCode = 12L

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
    implementation("com.googlecode.soundlibs:basicplayer:3.0.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    runtimeOnly("org.slf4j:slf4j-nop:1.7.36")
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
