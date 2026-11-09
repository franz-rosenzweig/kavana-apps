// Chataltul for Wear OS: a tile (start a block, see it, answer a check-in) and a complication (today's focus, or the minutes left),
// fed by the phone over the Wearable Data Layer. It needs the phone app (the same app id and the same signing key, or they can't talk).
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "xyz.tiresia.kavana.wear"
    compileSdk = 36

    defaultConfig {
        applicationId = "xyz.tiresia.kavana"
        minSdk = 30
        targetSdk = 34
        versionCode = 17
        versionName = "0.6.1"
        resValue("string", "app_name", "Chataltul")
    }

    // the same key as the phone app's (see app/build.gradle.kts)
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
            isMinifyEnabled = false
            if (secrets.exists()) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug" // beside the real one, paired with the phone's debug build
            resValue("string", "app_name", "Chataltul dev")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { checkReleaseBuilds = false }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation("androidx.wear.tiles:tiles:1.4.1")
    implementation("androidx.wear.protolayout:protolayout:1.2.1")
    implementation("androidx.wear.protolayout:protolayout-material:1.2.1")
    implementation("androidx.wear.protolayout:protolayout-expression:1.2.1")
    implementation("androidx.wear.watchface:watchface-complications-data-source-ktx:1.2.1")
    implementation("androidx.concurrent:concurrent-futures-ktx:1.2.0")
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
    debugImplementation("androidx.wear.tiles:tiles-renderer:1.4.1") // the tile lab draws the tile as the watch does
    debugImplementation("androidx.wear.watchface:watchface-complications-rendering:1.2.1") // and the complication lab, as a watch face does
    debugImplementation("com.google.guava:guava:33.3.1-android") // (the renderer wants all of Guava; the release keeps the small ListenableFuture)
}
