import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.facebook.react")
}

val releaseSigningPropertiesFile = rootProject.file("keystore/release-signing.properties")
val releaseSigningProperties = Properties().apply {
    if (releaseSigningPropertiesFile.exists()) {
        releaseSigningPropertiesFile.inputStream().use(::load)
    }
}

android {
    namespace = "com.openclaw.musicworker"
    compileSdk = 34
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.openclaw.musicworker"
        minSdk = 26
        targetSdk = 34
        versionCode = 33
        versionName = "1.2.9"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseSigningPropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(releaseSigningProperties.getProperty("storeFile"))
                storePassword = releaseSigningProperties.getProperty("storePassword")
                keyAlias = releaseSigningProperties.getProperty("keyAlias")
                keyPassword = releaseSigningProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (signingConfigs.findByName("release") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            java.setSrcDirs(listOf("src/main/react-native"))
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

react {
    root.set(file(".."))
    reactNativeDir.set(file("../node_modules/react-native"))
    codegenDir.set(file("../node_modules/@react-native/codegen"))
    entryFile.set(file("../index.js"))
    autolinkLibrariesWithApp()
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.facebook.react:react-android")
    if ((findProperty("hermesEnabled") as? String).toBoolean()) {
        implementation("com.facebook.react:hermes-android")
    } else {
        implementation("io.github.react-native-community:jsc-android:2026004.0.1")
    }
}

val publishReleaseApkToMinio by tasks.registering(Exec::class) {
    group = "publishing"
    description = "Uploads the signed release APK to the configured local MinIO bucket."

    val repoRoot = rootProject.projectDir.parentFile
    val publishScript = repoRoot.resolve("bin/publish_android_release_to_minio.py")
    val releaseApk = layout.buildDirectory.file("outputs/apk/release/app-release.apk")
    val releaseMetadata = layout.buildDirectory.file("outputs/apk/release/output-metadata.json")

    inputs.file(releaseApk)
    inputs.file(releaseMetadata).optional()
    outputs.upToDateWhen { false }

    doFirst {
        if (!releaseSigningPropertiesFile.exists()) {
            throw GradleException("Refusing to publish release APK: release signing properties are missing.")
        }
        if (!releaseApk.get().asFile.exists()) {
            throw GradleException("Release APK does not exist: ${releaseApk.get().asFile}")
        }
        commandLine(
            "python3",
            publishScript.absolutePath,
            "--apk",
            releaseApk.get().asFile.absolutePath,
            "--metadata",
            releaseMetadata.get().asFile.absolutePath,
        )
    }
}

tasks.matching { it.name == "assembleRelease" }.configureEach {
    finalizedBy(publishReleaseApkToMinio)
}
