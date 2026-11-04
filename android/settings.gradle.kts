pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "Kavana"
include(":app")
include(":wear") // Chataltul for Wear OS: a tile and a complication, fed by the phone
