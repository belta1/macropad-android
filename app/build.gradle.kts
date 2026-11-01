import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing: keystore.properties (local, git-ignored) or environment variables (CI).
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun signingValue(key: String, env: String): String? = keystoreProps.getProperty(key) ?: System.getenv(env)

android {
    namespace = "dev.belta.macropad"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.belta.macropad"
        minSdk = 28
        targetSdk = 34
        versionCode = 10
        versionName = "1.9.0"
        // Gesture recognition ships native code; tablets are ARM (the Fire's is 32-bit).
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }

    // MediaPipe maps its models straight from the APK.
    androidResources { noCompress += listOf("task", "tflite") }
    // Compress the native libraries in the APK (~20 MB smaller download; Android unpacks them on install).
    packaging { jniLibs { useLegacyPackaging = true } }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            signingValue("storeFile", "MACROPAD_KEYSTORE")?.let { path ->
                storeFile = file(path)
                storePassword = signingValue("storePassword", "MACROPAD_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "MACROPAD_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "MACROPAD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val release = signingConfigs.getByName("release")
            // Without a keystore, release builds fall back to the debug key (installable, not for distribution).
            signingConfig = if (release.storeFile != null) release else signingConfigs.getByName("debug")
        }
    }

    // The PC link script rides in the app, so the tablet can update the computer's copy (LinkScript).
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/linkScript"))

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

val copyLinkScript by tasks.registering(Copy::class) {
    from(rootProject.file("tools/macropad-link.ps1"))
    into(layout.buildDirectory.dir("generated/linkScript"))
}
tasks.named("preBuild") { dependsOn(copyLinkScript) }

dependencies {
    // On-device hand gesture and face detection (camera gestures). 0.10.14 still ships 32-bit ARM.
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
    testImplementation("junit:junit:4.13.2")
    // org.json is part of Android but only stubbed in local unit tests.
    testImplementation("org.json:json:20240303")
}
