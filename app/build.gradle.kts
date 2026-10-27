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
        versionCode = 7
        versionName = "1.6.0"
    }

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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // org.json is part of Android but only stubbed in local unit tests.
    testImplementation("org.json:json:20240303")
}
