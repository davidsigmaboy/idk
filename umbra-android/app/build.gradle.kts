plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// api_id / api_hash come from https://my.telegram.org (gradle.properties or env TG_API_ID / TG_API_HASH).
val tgApiId: String = (project.findProperty("TG_API_ID") as String?) ?: System.getenv("TG_API_ID") ?: "0"
val tgApiHash: String = (project.findProperty("TG_API_HASH") as String?) ?: System.getenv("TG_API_HASH") ?: ""

android {
    namespace = "app.umbra.client"
    compileSdk = 34
    defaultConfig {
        applicationId = "app.umbra.client"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("int", "TG_API_ID", tgApiId)
        buildConfigField("String", "TG_API_HASH", "\"$tgApiHash\"")
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }
    buildFeatures { compose = true; buildConfig = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets["main"].java.srcDir("src/tdlib/java")   // TdApi.java + Client.java, produced by scripts/build-tdlib.sh
    sourceSets["main"].jniLibs.srcDir("src/tdlib/jniLibs")
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(bom)
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
