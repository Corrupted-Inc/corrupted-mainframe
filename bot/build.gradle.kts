plugins {
    id("kotlin")
    id("io.gitlab.arturbosch.detekt").version("1.18.0")
    id("org.openjfx.javafxplugin") version "0.0.12"
}

sourceSets.main {
    java.srcDirs("src")
    resources.srcDirs("resources")
}

repositories {
    mavenCentral()
    jcenter()  // shut
    maven("https://jitpack.io/")
}

dependencies {
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.8.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.8.2")
    implementation("net.dv8tion:JDA:5.0.0-alpha.9")
    implementation("com.github.minndevelopment:jda-ktx:0.8.4-alpha.5")
    implementation(group = "com.beust", name = "klaxon", version = "5.5")
    implementation(group = "org.xerial", name = "sqlite-jdbc", version = "3.34.0")
    implementation(group = "org.postgresql", name = "postgresql", version = "42.2.16")
    implementation(group = "org.slf4j", name = "slf4j-simple", version = "1.7.30")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.6.0")
    implementation(group = "org.jetbrains.exposed", name = "exposed-dao", version = "0.37.3")
    implementation(group = "org.jetbrains.exposed", name = "exposed-jdbc", version = "0.37.3")
    implementation(group = "org.jetbrains.exposed", name = "exposed-java-time", version = "0.37.3")
    implementation("com.sedmelluq:lavaplayer:1.3.73")
    runtimeOnly("com.sedmelluq:lavaplayer-common:1.0.6")
    implementation("com.google.guava:guava:31.0.1-jre")
    implementation("ch.obermuhlner:kotlin-big-math:2.3.0")
    implementation("ch.obermuhlner:big-math:2.3.0")
    implementation("com.jagrosh:jda-utilities:3.0.5")
    implementation("org.ocpsoft.prettytime:prettytime-nlp:5.0.2.Final")
    implementation("com.github.ben-manes.caffeine:caffeine:3.0.5")
    implementation("dev.brachtendorf:JImageHash:1.0.0")
}

javafx {
    version = "12"
    modules("javafx.media")
    configuration = "implementation"
}

detekt {
    buildUponDefaultConfig = true // preconfigure defaults
    allRules = false // activate all available (even unstable) rules.
    source = project.files("src/")
    config = files("detekt.yml") // point to your custom config defining rules to run, overwriting default behavior
//    baseline = file("detekt.xml") // a way of suppressing issues before introducing detekt

    reports {
        html.enabled = true // observe findings in your browser with structure and code snippets
        xml.enabled = true // checkstyle like format mainly for integrations like Jenkins
        txt.enabled = true // similar to the console output, contains issue signature to manually edit baseline files
        sarif.enabled = true // standardized SARIF format (https://sarifweb.azurewebsites.net/) to support integrations with Github Code Scanning
    }
}

tasks.jar {
    archiveBaseName.set("corrupted-mainframe")
    from(configurations.runtimeClasspath.get().filter { !it.path.endsWith(".pom") }.map { if (it.isDirectory) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes["Main-Class"] = "com.github.corruptedinc.corruptedmainframe.MainKt"
    }
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    // Target version of the generated JVM bytecode. It is used for type resolution.
    jvmTarget = "11"
}

// https://stackoverflow.com/a/55741901
tasks {
    val sourcesJar by creating(Jar::class) {
        archiveClassifier.set("sources")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        from(sourceSets.main.get().allSource, configurations.runtimeClasspath.get().filter { !it.path.endsWith(".pom") }.map { if (it.isDirectory) it else zipTree(it) }, sourceSets.main.get().output)
    }

    val javadocJar by creating(Jar::class) {
        dependsOn.add(javadoc)
        archiveClassifier.set("javadoc")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        from(javadoc)
    }

    artifacts {
        archives(sourcesJar)
        archives(javadocJar)
        archives(jar)
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions {
        freeCompilerArgs = freeCompilerArgs + "-Xopt-in=kotlin.RequiresOptIn"
        freeCompilerArgs = freeCompilerArgs + "-opt-in=kotlin.ExperimentalStdlibApi"
    }
}
