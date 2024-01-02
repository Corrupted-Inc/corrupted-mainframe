rootProject.name = "corrupted-mainframe"
include("bot", "annotations", "annotationprocessor")


pluginManagement {
    val kotlinVersion = "1.9.20"
    val kspVersion = "1.9.20-1.0.14"
    plugins {
        id("com.google.devtools.ksp") version kspVersion
        kotlin("jvm") version kotlinVersion
    }
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
