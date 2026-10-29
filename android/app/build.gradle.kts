import org.gradle.api.tasks.Exec

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.deepfaceswap.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.deepfaceswap.android"
        minSdk = 26
        targetSdk = 34
        versionCode = 214
        versionName = "1.21.0"

        // The `ort` crate ships a prebuilt ONNX Runtime only for 64-bit ARM
        // Android, so we target arm64-v8a exclusively.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    // A single, stable signing identity so new builds install as *updates* over
    // the installed app instead of requiring an uninstall. Credentials are read
    // from the environment (CI secrets) — never hardcoded here.
    signingConfigs {
        create("release") {
            val storePath = System.getenv("SIGNING_STORE_FILE")
            if (storePath != null && file(storePath).exists()) {
                storeFile = file(storePath)
                storeType = "PKCS12"
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
                // Sign with every scheme, not just v2. A v2-only APK is valid on
                // Android 7+, but some OEM installers - ColorOS among them - are
                // stricter and report a signature conflict that is really a
                // missing v1/v3 signature.
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }

    // Use the shared identity for both variants when it is available; otherwise
    // fall back to the standard debug key so local builds still work.
    val sharedSigning = signingConfigs.getByName("release")
    val signingAvailable = System.getenv("SIGNING_STORE_FILE")?.let { file(it).exists() } == true

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (signingAvailable) sharedSigning else signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            if (signingAvailable) signingConfig = sharedSigning
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Prebuilt native libraries produced by cargo-ndk land in src/main/jniLibs.
    sourceSets["main"].jniLibs.srcDirs("src/main/jniLibs")
}

dependencies {
    // Intentionally dependency-free: the UI uses only framework classes so the
    // APK stays small and the build needs no AndroidX resolution.
}

// ---------------------------------------------------------------------------
// Rust cross-compilation
//
// Runs `cargo ndk ... build --release` and drops libdeep_faceswap_android.so
// into src/main/jniLibs/arm64-v8a. Skip with `-PskipRust=true` if you have
// already built the native library (e.g. in CI, before invoking Gradle).
// ---------------------------------------------------------------------------
val repoRoot = rootProject.file("..")

val cargoNdkBuild by tasks.registering(Exec::class) {
    group = "rust"
    description = "Cross-compile the Rust core and stage jniLibs via scripts/build-native.sh."
    workingDir = repoRoot
    commandLine("bash", "scripts/build-native.sh")
}

if (project.findProperty("skipRust") != "true") {
    tasks.named("preBuild") { dependsOn(cargoNdkBuild) }
}
