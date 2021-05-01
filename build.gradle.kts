import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.4.32"
    id("org.jetbrains.kotlin.plugin.jpa") version "1.4.32"
}

group = "com.github.blahblahbloopster"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://m2.dv8tion.net/releases")
}

dependencies {
    testImplementation(kotlin("test-junit"))
    implementation("net.dv8tion:JDA:4.2.1_253")
    implementation(group = "com.beust", name = "klaxon", version = "5.5")
    implementation(group = "org.hibernate", name = "hibernate-core", version = "6.0.0.Alpha7")
    implementation(group = "org.hibernate", name = "hibernate-testing", version = "6.0.0.Alpha7")
    implementation(group = "org.xerial", name = "sqlite-jdbc", version = "3.34.0")
}

tasks.test {
    useJUnit()
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}