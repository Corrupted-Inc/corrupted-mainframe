plugins {
    id("kotlin")
    id("io.gitlab.arturbosch.detekt").version("1.18.0")
}

sourceSets.main {
    java.srcDirs("src")
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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.5.0")
    implementation(group = "org.jetbrains.exposed", name = "exposed-dao", version = "0.33.1")
    implementation(group = "org.jetbrains.exposed", name = "exposed-jdbc", version = "0.33.1")
    implementation(group = "org.jetbrains.exposed", name = "exposed-java-time", version = "0.33.1")
    implementation("com.sedmelluq:lavaplayer:1.3.76")
    implementation("com.google.guava:guava:30.1.1-jre")
    implementation("ch.obermuhlner:kotlin-big-math:2.3.0")
    implementation("ch.obermuhlner:big-math:2.3.0")
}

detekt {
    buildUponDefaultConfig = true // preconfigure defaults
    allRules = false // activate all available (even unstable) rules.
    source = project.files("src/")
//    config = files("$projectDir/config/detekt.yml") // point to your custom config defining rules to run, overwriting default behavior
//    baseline = file("$projectDir/config/baseline.xml") // a way of suppressing issues before introducing detekt

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

    manifest {
        attributes["Main-Class"] = "com.github.corruptedinc.corruptedmainframe.MainKt"
    }
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    // Target version of the generated JVM bytecode. It is used for type resolution.
    jvmTarget = "1.8"
}

// https://stackoverflow.com/a/55741901
tasks {
    val sourcesJar by creating(Jar::class) {
        archiveClassifier.set("sources")
        from(sourceSets.main.get().allSource, configurations.runtimeClasspath.get().filter { !it.path.endsWith(".pom") }.map { if (it.isDirectory) it else zipTree(it) }, sourceSets.main.get().output)
    }

    val javadocJar by creating(Jar::class) {
        dependsOn.add(javadoc)
        archiveClassifier.set("javadoc")
        from(javadoc)
    }

    artifacts {
        archives(sourcesJar)
        archives(javadocJar)
        archives(jar)
    }
}
