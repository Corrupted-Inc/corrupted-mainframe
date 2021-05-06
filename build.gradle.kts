import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.4.32"
    id("org.jetbrains.kotlin.plugin.jpa") version "1.4.32"
    id("org.jetbrains.kotlin.plugin.noarg") version "1.4.32"
    id("org.jetbrains.kotlin.plugin.allopen") version "1.4.32"
}

buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-noarg:1.4.32")
        classpath("org.jetbrains.kotlin:kotlin-allopen:1.4.32")
    }
}

allOpen {
    annotation("javax.persistence.Entity")
    annotation("javax.persistence.MappedSuperclass")
    annotation("javax.persistence.Embeddable")
}

group = "com.github.blahblahbloopster"
version = "1.0-SNAPSHOT"

sourceSets.main {
    java.srcDirs("src/main/kotlin")
}

sourceSets.test {
    java.srcDirs("src/test/kotlin")
}

repositories {
    mavenCentral()
    maven("https://m2.dv8tion.net/releases")
    maven("http://repository.jboss.org/nexus/content/groups/public/")
    maven("https://jitpack.io")
}

dependencies {
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.6.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.6.0")
    implementation("net.dv8tion:JDA:4.2.1_253")
    implementation(group = "com.beust", name = "klaxon", version = "5.5")
    implementation(group = "org.xerial", name = "sqlite-jdbc", version = "3.34.0")
    implementation(group = "org.apache.logging.log4j", name = "log4j", version = "2.14.1")
    implementation(group = "org.slf4j", name = "slf4j-log4j12", version = "1.7.30")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.4.3")
    implementation(group = "org.jetbrains.exposed", name = "exposed-dao", version = "0.31.1")
    implementation(group = "org.jetbrains.exposed", name = "exposed-jdbc", version = "0.31.1")
    implementation("com.sedmelluq:lavaplayer:1.3.76")
}

tasks.test {
    useJUnit()
//    useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}