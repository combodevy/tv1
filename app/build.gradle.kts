plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.cctvofficialnavigator"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.cctvofficialnavigator"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // 仅依赖 AndroidX 核心,WebView 为系统组件无需额外库
    implementation("androidx.core:core:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
