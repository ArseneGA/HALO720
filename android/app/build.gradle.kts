plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "fr.halo720.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "fr.halo720.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
    }
    signingConfigs {
        create("halo") {
            storeFile = file("halo720.keystore")
            // mot de passe hors du dépôt : ~/.gradle/gradle.properties -> HALO_KEY_PASSWORD=...
            val pw = providers.gradleProperty("HALO_KEY_PASSWORD").orNull ?: System.getenv("HALO_KEY_PASSWORD")
                ?: error("HALO_KEY_PASSWORD manquant (~/.gradle/gradle.properties)")
            storePassword = pw
            keyAlias = "halo720"
            keyPassword = pw
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("halo") }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("halo")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("camassets"))
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(bom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.ui:ui-tooling-preview")
}

// Le logiciel de la caméra (~/HALO720/camera) est embarqué dans l'APK pour la mise à jour depuis l'app.
val cameraAssets = tasks.register<Copy>("cameraAssets") {
    from(rootDir.resolve("../camera")) { include("halo_camera.py", "dng.py", "install.sh") }
    into(layout.buildDirectory.dir("camassets/camera"))
}
tasks.named("preBuild") { dependsOn(cameraAssets) }
