import org.jetbrains.compose.desktop.application.dsl.TargetFormat

val desktopPackageName = "YinZhaoDesktop"
val desktopAppVersion = "0.1.0"
val desktopAppVersionCode = 1L

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
    implementation("io.ktor:ktor-client-content-negotiation:3.4.1")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.4.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
}

compose.desktop {
    application {
        mainClass = "com.openclaw.musicworker.desktop.MainKt"
        jvmArgs += listOf(
            "-Dmusicworker.desktop.version=$desktopAppVersion",
            "-Dmusicworker.desktop.versionCode=$desktopAppVersionCode",
        )

        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            packageName = desktopPackageName
            packageVersion = desktopAppVersion
            description = "YinZhao Windows desktop client"
            vendor = "OpenClaw"
        }
    }
}
