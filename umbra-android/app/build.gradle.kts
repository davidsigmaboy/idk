// Plugins (exteraGram .plugin files) need Python from Chaquopy. It is switched on by UMBRA_PLUGINS=1; the CI builds
// with it and falls back to a build without if Chaquopy can not be fetched. The Kotlin code reaches Python by
// reflection, so both builds compile.
buildscript {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
    dependencies {
        if (System.getenv("UMBRA_PLUGINS") == "1") classpath("com.chaquo.python:gradle:16.0.0")
    }
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val withPlugins = System.getenv("UMBRA_PLUGINS") == "1"
if (withPlugins) apply(plugin = "com.chaquo.python")

// Your own api_id / api_hash from https://my.telegram.org (env or gradle.properties: TG_API_ID, TG_API_HASH).
// Never commit them and never reuse another app's credentials: Telegram limits or bans such clients.
val tgApiId: String = (project.findProperty("TG_API_ID") as String?)
    ?: System.getenv("TG_API_ID")
    ?: "0"
val tgApiHash: String = (project.findProperty("TG_API_HASH") as String?)
    ?: System.getenv("TG_API_HASH")
    ?: ""

// CI numbers every build; the app compares it with the newest GitHub release to offer an update.
val umbraBuild: Int = (System.getenv("UMBRA_BUILD") ?: "3").toInt()
val umbraKeystore: String? = System.getenv("UMBRA_KEYSTORE")

android {
    namespace = "app.umbra.client"
    compileSdk = 34
    defaultConfig {
        applicationId = "app.umbra.client"
        minSdk = 24
        targetSdk = 34
        versionCode = umbraBuild
        versionName = "1.3.$umbraBuild"
        buildConfigField("int", "TG_API_ID", tgApiId)
        buildConfigField("String", "TG_API_HASH", "\"$tgApiHash\"")
        // Chaquopy's Python 3.12 has no 32-bit ARM build: the plugin build is for 64-bit phones only.
        ndk { abiFilters += if (withPlugins) listOf("arm64-v8a", "x86_64") else listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        if (withPlugins) {
            withGroovyBuilder { "python" { "version"("3.12") } }
        }
    }
    // One fixed key for every build: Android installs an update only over an app signed with the same key.
    signingConfigs {
        if (umbraKeystore != null) {
            create("umbra") {
                storeFile = file(umbraKeystore)
                storePassword = "8d97ee1aef2c9ae3094aa51ffc334069"
                keyAlias = "umbra"
                keyPassword = "8d97ee1aef2c9ae3094aa51ffc334069"
            }
        }
    }
    buildTypes {
        getByName("debug") {
            if (umbraKeystore != null) signingConfig = signingConfigs.getByName("umbra")
        }
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
