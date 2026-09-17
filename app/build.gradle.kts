import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing: reads from a local, gitignored keystore.properties for developer machines,
// or from environment variables (set from GitHub Actions secrets) in CI — the actual secret
// values never need to live in this file or anywhere committed to the repo. See
// keystore.properties.example for the local file format.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}
fun signingValue(propKey: String, envKey: String): String? =
    keystoreProperties.getProperty(propKey) ?: System.getenv(envKey)

// CI passes these to derive versionCode/versionName from the pushed release tag rather than
// requiring a manual build.gradle.kts edit before every release; local/debug builds fall back
// to the hardcoded defaults below.
val releaseVersionCode = (project.findProperty("releaseVersionCode") as String?)?.toIntOrNull()
val releaseVersionName = project.findProperty("releaseVersionName") as String?

android {
    namespace = "com.cuppa.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.cuppa.app"
        minSdk = 26
        targetSdk = 37
        versionCode = releaseVersionCode ?: 4
        versionName = releaseVersionName ?: "0.4.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            val storeFilePath = signingValue("storeFile", "CUPPA_KEYSTORE_PATH")
            if (storeFilePath != null) {
                storeFile = file(storeFilePath)
                storePassword = signingValue("storePassword", "CUPPA_KEYSTORE_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "CUPPA_KEYSTORE_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "CUPPA_KEYSTORE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Only actually sign if real credentials were supplied (local keystore.properties or
            // CI env vars) — falls back to unsigned so `assembleRelease` still works for anyone
            // building from source without a signing key, e.g. to just try the app locally.
            if (signingValue("storeFile", "CUPPA_KEYSTORE_PATH") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // Project modules
    implementation(project(":cups-core"))

    // AndroidX Core
    implementation(libs.androidx.core.ktx)

    // Lifecycle
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.animation)

    // Activity & Navigation
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // Shizuku (elevated privileged access)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    // Bouncy Castle (self-signed cert generation for local IPPS/TLS listener)
    implementation(libs.bouncycastle.pkix)
    implementation(libs.bouncycastle.prov)

    // Debug
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
