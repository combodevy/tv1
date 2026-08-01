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
}

dependencies {
    implementation("androidx.browser:browser:1.8.0")
}
