// Kavana for Android, native: Kotlin and Jetpack Compose. The block logic is the web app's own
// engine.js, run in the phone's JavaScript sandbox (assets/js, copied by tools/sync-web.sh), so the
// app and the web agree on kittens, treats and missions. Everything stays on the phone.
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "xyz.tiresia.kavana"
    compileSdk = 36

    defaultConfig {
        applicationId = "xyz.tiresia.kavana"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        resValue("string", "app_name", "Kavana")
    }

    // The signing key's properties: android/keystore.properties (never in git), or the file
    // -PsigningProperties names (release.sh uses Dvarim's, which signs Daf and Binah too).
    // storeFile is found beside the properties file. Without one the release build is unsigned.
    val secrets = if (project.hasProperty("signingProperties")) file(project.property("signingProperties") as String)
                  else rootProject.file("keystore.properties")
    signingConfigs {
        create("release") {
            if (secrets.exists()) {
                val p = Properties().apply { secrets.inputStream().use { load(it) } }
                val store = File(p.getProperty("storeFile", "android.keystore"))
                storeFile = if (store.isAbsolute) store else File(secrets.parentFile, store.path)
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias", "kavana")
                keyPassword = p.getProperty("keyPassword", p.getProperty("storePassword"))
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (secrets.exists()) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // beside the real app, with its own data (check.sh may speed its clock up)
            applicationIdSuffix = ".debug"
            resValue("string", "app_name", "Kavana dev")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    lint { checkReleaseBuilds = false }
    androidResources { noCompress += listOf("webp", "js") }
}

kotlin { jvmToolchain(17) }

dependencies {
    val bom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.javascriptengine:javascriptengine:1.1.1")
    implementation("androidx.concurrent:concurrent-futures-ktx:1.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
