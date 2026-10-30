plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.smm.paymentsync"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.smm.paymentsync"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "2.5.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
}
