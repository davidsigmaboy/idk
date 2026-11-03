plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Defaults = public credentials from official open-source Telegram Android (DrKLO/Telegram BuildVars).
// Override via env/gradle.properties TG_API_ID / TG_API_HASH if you want your own from my.telegram.org.
val tgApiId: String = (project.findProperty("TG_API_ID") as String?)
    ?: System.getenv("TG_API_ID")
    ?: "4"
val tgApiHash: String = (project.findProperty("TG_API_HASH") as String?)
    ?: System.getenv("TG_API_HASH")
    ?: "014b35b6184100b085b0d0572f9b5103"

android {
    namespace = "app.umbra.client"
    compileSdk = 34
    defaultConfig {
        applicationId = "app.umbra.client"
        minSdk = 24
        targetSdk = 34
        versionCode = 3
        versionName = "1.2.0"
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
    packaging {
        jniLibs { useLegacyPackaging = true }
        resources.excludes += setOf(
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
            "META-INF/*.kotlin_module"
        )
    }
    sourceSets["main"].java.srcDir("src/tdlib/java")
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
