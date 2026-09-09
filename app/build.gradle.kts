plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystorePath: String? = System.getenv("KEYSTORE_PATH")

gradle.taskGraph.whenReady {
    val packagesRelease = allTasks.any { it.path.matches(Regex(""":app:package\w*Release(Bundle)?""")) }
    if (keystorePath == null && packagesRelease) {
        throw GradleException(
            "release ビルドには署名鍵が必要です。KEYSTORE_PATH / KEYSTORE_PASSWORD / " +
                "KEY_ALIAS / KEY_PASSWORD を設定してください (未署名の APK は配布しない)",
        )
    }
}

android {
    namespace = "com.grouppins.photobridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.grouppins.photobridge"
        minSdk = 29
        targetSdk = 35
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "1.0"
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
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
}

dependencies {
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.core:core:1.13.1")
}
