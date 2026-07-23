plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tropicalstream.tapvibe"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tropicalstream.tapvibe"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
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
    // Companion drag-and-drop upload server (same lib TapInsight/WanderQuest used).
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}
