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
        versionName = "1.0.0"
        // GeckoView 包含 4 个 CPU 架构的原生库(~600MB),只保留实际需要的以缩小 APK:
        //   arm64-v8a  — 真实 ARM 电视盒子 / 手机(主要目标)
        //   x86        — MuMu 模拟器调试
        ndk {
            abiFilters += listOf("arm64-v8a", "x86")
        }
    }

    // GeckoView 需要 Java 17
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.browser:browser:1.8.0")
    // GeckoView 130: 内嵌 Firefox 引擎(完整 Widevine DRM + MediaSource),兼容 compileSdk 34 / AGP 8.6.1
    // 130.x 是兼容 compileSdk 34 的最后稳定分支;153+ 需要 compileSdk 36
    implementation("org.mozilla.geckoview:geckoview:130.0.20240913135723")
}
