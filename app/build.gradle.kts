import java.util.Base64

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
        versionCode = 6
        versionName = "0.4.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("junit:junit:4.13.2")
}

val generatedLauncherRes = layout.buildDirectory.dir("generated/res/launcherIcon")

val generateLauncherIcon by tasks.registering {
    val encodedIcon = layout.projectDirectory.file("src/main/icon/ic_launcher.jpg.b64")
    inputs.file(encodedIcon)
    outputs.dir(generatedLauncherRes)
    doLast {
        val outputDir = generatedLauncherRes.get().asFile.resolve("drawable-nodpi")
        outputDir.mkdirs()
        val encoded = encodedIcon.asFile.readText().trim()
        outputDir.resolve("ic_launcher_brand.jpg").writeBytes(
            Base64.getDecoder().decode(encoded)
        )
    }
}

android.sourceSets.getByName("main").res.srcDir(generatedLauncherRes)
tasks.named("preBuild").configure { dependsOn(generateLauncherIcon) }

