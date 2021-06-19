import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.4.32"
    application
}

application {
    mainClass.set("MainKt")
}

group = "com.github.blahblahbloopster"
version = ""

sourceSets.main {
    java.srcDirs("src/main/kotlin")
}

sourceSets.test {
    java.srcDirs("src/test/kotlin")
}

repositories {
    mavenCentral()
    maven("https://m2.dv8tion.net/releases")
    maven("https://jitpack.io")
}

dependencies {
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.6.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.6.0")
    implementation("net.dv8tion:JDA:4.3.0_280")
    implementation(group = "com.beust", name = "klaxon", version = "5.5")
    implementation(group = "org.xerial", name = "sqlite-jdbc", version = "3.34.0")
    implementation(group = "org.postgresql", name = "postgresql", version = "42.2.16")
    implementation(group = "org.apache.logging.log4j", name = "log4j", version = "2.14.1")
    implementation(group = "org.slf4j", name = "slf4j-log4j12", version = "1.7.30")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.4.3")
    implementation(group = "org.jetbrains.exposed", name = "exposed-dao", version = "0.31.1")
    implementation(group = "org.jetbrains.exposed", name = "exposed-jdbc", version = "0.31.1")
    implementation("com.sedmelluq:lavaplayer:1.3.76")
    implementation("com.google.guava:guava:30.1.1-jre")
}

tasks.test {
    useJUnit()
}

tasks.jar {
    archiveBaseName.set("corrupted-mainframe")
    from(configurations.runtimeClasspath.get().filter { !it.path.endsWith(".pom") }.map { if (it.isDirectory) it else zipTree(it) })

    manifest {
        attributes["Main-Class"] = "MainKt"
    }
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}