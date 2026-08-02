plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.cctvofficialnavigator"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.cctvofficialnavigator"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    compileOptions {
        // ExoPlayer 2.19.x 要求 Java 8+
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    // AndroidX AppCompat + Material (原有依赖补全,确保存在)
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // ExoPlayer 2.19.1 (最后一个 2.x 稳定版,minSdk=23,支持 HLS/MP4/FLV)
    // CCTV-3/6/8 yangshipin 桌面端专用:截到 m3u8 后不用 WebView <video>,直接原生播放,
    // 彻底绕过 Chromium SurfaceView overlay / 软件渲染 Canvas 绑定导致的「有声音没画面」bug
    implementation("com.google.android.exoplayer:exoplayer:2.19.1")
    implementation("com.google.android.exoplayer:exoplayer-hls:2.19.1")
    implementation("com.google.android.exoplayer:exoplayer-ui:2.19.1")
}
