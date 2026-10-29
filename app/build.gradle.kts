import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing key, read from keystore.properties in the project root (never committed:
// it is in .gitignore). Without the file the release is signed with the debug key, which
// is fine for installing on your own phones but is rejected by Google Play.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseKey = keystoreProps.getProperty("storeFile") != null

android {
    namespace = "io.github.renzodambrosi.distilla"
    // Google Play: new apps and updates must target API 36 (Android 16) since 31 August 2026
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.renzodambrosi.distilla"
        minSdk = 26
        targetSdk = 36
        // Version: change only these two lines. They are written as plain literals on purpose:
        // F-Droid reads them from this file to detect new releases.
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (hasReleaseKey) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

// APK name: distilla-1.0-release.apk / distilla-1.0-debug.apk (default would be app-release.apk)
base {
    archivesName.set("distilla-${android.defaultConfig.versionName}")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Background reading: media session, notification, Bluetooth / steering-wheel buttons, Android Auto
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("androidx.media3:media3-common:1.5.1")
    implementation("com.google.guava:guava:33.3.1-android")

    // Download e parsing HTML con supporto XPath (selectXpath)
    implementation("org.jsoup:jsoup:1.18.3")
    // Solo l'API JsonElement: non serve il plugin di serializzazione
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}
