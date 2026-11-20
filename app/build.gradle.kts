plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.alfeela.chess3d"
    compileSdk = 35
    buildFeatures { buildConfig = true }
    defaultConfig {
        buildConfigField("String", "SUPABASE_URL", "\"https://llkynfrcrmzwziakusgb.supabase.co\"")
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"sb_publishable_sNrwsvuytdDmWint2waEiA_PIo46k0Y\"")
        applicationId = "com.alfeela.chess3d"
        minSdk = 23
        targetSdk = 35
        versionCode = 2
        versionName = "0.3.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
