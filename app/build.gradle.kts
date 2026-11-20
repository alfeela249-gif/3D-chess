plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.alfeela.chess3d"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.alfeela.chess3d"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
}
