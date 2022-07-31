rootProject.name = "corrupted-mainframe"
include("bot", "annotations", "annotationprocessor")


pluginManagement {
    val kotlinVersion = "1.7.0"
    val kspVersion = "1.7.0-1.0.6"
    plugins {
        id("com.google.devtools.ksp") version kspVersion
        kotlin("jvm") version kotlinVersion
    }
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
