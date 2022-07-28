import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.7.0"
    application
}

allprojects {
    group = "com.github.corruptedinc"

    repositories {
        mavenCentral()
        maven("https://m2.dv8tion.net/releases")
        maven("https://jitpack.io")
    }

    tasks.withType<KotlinCompile> {
        kotlinOptions.jvmTarget = "11"
    }
}

application {
    mainClass.set("MainKt")
}
