plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.grouppins.photobridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.grouppins.photobridge"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
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
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.core:core:1.13.1")
}
