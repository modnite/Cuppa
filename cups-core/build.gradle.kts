plugins {
    alias(libs.plugins.android.library)
}

// Real Android devices are arm64-v8a or (rarely, older ones) armeabi-v7a. x86/x86_64 only matter
// for the emulator. Per-buildType `ndk.abiFilters` isn't honored for CMake-based
// externalNativeBuild in this AGP version (confirmed: release builds still produced all four
// ABIs), so this is decided from the invoked task name instead — a release build gets only the
// two ABIs a real phone can use, cutting the APK roughly in half; any other build (debug,
// emulator testing) still gets all four.
val isReleaseBuild = gradle.startParameter.taskNames.any { it.contains("Release") || it.contains("release") }

android {
    namespace = "com.cuppa.cups"
    compileSdk = 37

    defaultConfig {
        minSdk = 26

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        // NDK: Build for common ABIs
        ndk {
            abiFilters += if (isReleaseBuild) {
                listOf("arm64-v8a", "armeabi-v7a")
            } else {
                listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            }
        }

        // CMake arguments for the native build
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += listOf(
                    "-DANDROID_STL=c++_shared"
                )
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // Point to the CMakeLists.txt for native build
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
}
