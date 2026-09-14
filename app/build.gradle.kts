plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.modnite.cuppa"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.modnite.cuppa"
        minSdk = 24
        targetSdk = 37
        versionCode = 150
        versionName = "5.6.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = file("rolloprint.jks")
            storePassword = "rolloprint123"
            keyAlias = "rolloprint"
            keyPassword = "rolloprint123"
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = true
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            optimization {
                enable = false
            }
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.material)
    implementation(libs.jipp.core)
    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set("Cuppa-v5.1.0.apk")
        }
    }
}
