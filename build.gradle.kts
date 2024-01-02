import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.9.20"
    java
    application
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

allprojects {
    group = "com.github.corruptedinc"

    repositories {
        mavenCentral()
        maven("https://m2.dv8tion.net/releases")
        maven("https://jitpack.io")
    }

    tasks.withType(Javadoc::class.java).all { enabled = false }

    tasks.withType<KotlinCompile>().configureEach {
        kotlinOptions {
            freeCompilerArgs = freeCompilerArgs + "-opt-in=kotlin.RequiresOptIn"
            freeCompilerArgs = freeCompilerArgs + "-Xcontext-receivers"
            jvmTarget = "17"
        }
    }
}

buildscript {
    dependencies {
        classpath(kotlin("gradle-plugin", version = "1.9.20"))
    }
}

application {
    mainClass.set("MainKt")
}
