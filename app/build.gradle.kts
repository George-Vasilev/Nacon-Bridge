import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    // Kotlin package / namespace stays com.vanzetta.gipbridge (the JNI symbol names in
    // uinput_gamepad.c depend on it). Only the installed app id changes, so this build can
    // sit next to the original GIP Bridge without clashing.
    namespace = "com.vanzetta.gipbridge"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.user.naconbridge"
        minSdk = 26
        targetSdk = 34
        versionCode = 110
        versionName = "1.1"
        ndk {
            // arm64 covers almost every phone; armeabi-v7a for older 32-bit phones,
            // x86_64 for Chromebooks / emulators.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                cppFlags += ""
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Release signing key lives OUTSIDE the repository (see keystore.properties). Point
    // NACON_BRIDGE_KEYSTORE at another properties file to sign with a different key.
    // Default: a folder named NaconBridge-key next to the project folder.
    val keystorePropsFile = System.getenv("NACON_BRIDGE_KEYSTORE")?.let { file(it) }
        ?: rootProject.file("../NaconBridge-key/keystore.properties")
    val keystoreProps = Properties().apply {
        if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
    }
    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
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
    packaging {
        // Extract lib/*.so to disk at install time: libgipuinputd.so is an executable that the
        // shell user runs from the app's native library directory.
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
