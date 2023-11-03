plugins {
    id("kotlin")
//    id("io.gitlab.arturbosch.detekt").version("1.18.0")
    id("com.google.devtools.ksp") version "1.9.20-1.0.14"
}

sourceSets.main {
    java.srcDirs("src")
    java.srcDirs("build/generated/ksp/main/kotlin/")
    resources.srcDirs("resources")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

repositories {
    mavenCentral()
    maven("https://jitpack.io/")
}

dependencies {
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.9.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.9.2")
    implementation("net.dv8tion:JDA:5.0.0-beta.17")
    implementation("com.github.minndevelopment:jda-ktx:0.9.5-alpha.19")
    implementation(group = "org.postgresql", name = "postgresql", version = "42.2.27")
    implementation(group = "org.slf4j", name = "slf4j-simple", version = "1.7.30")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.6.4")
    implementation(group = "org.jetbrains.exposed", name = "exposed-dao", version = "0.40.1")
    implementation(group = "org.jetbrains.exposed", name = "exposed-jdbc", version = "0.40.1")
    implementation(group = "org.jetbrains.exposed", name = "exposed-java-time", version = "0.40.1")
//    implementation("com.github.walkyst:lavaplayer-fork:1.3.98.4")
//    implementation("dev.arbjerg:lavaplayer:2.0.1")
//    runtimeOnly("com.sedmelluq:lavaplayer-common:1.0.6")
    implementation("ch.obermuhlner:big-math:2.3.2")
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.5")
    implementation("org.apache.httpcomponents.client5:httpclient5:5.2.1")
    implementation(project(":annotations"))
    compileOnly(project(":annotationprocessor"))
    ksp(project(":annotationprocessor"))

    // figure out how to replace/remove if possible
    implementation("org.ocpsoft.prettytime:prettytime-nlp:5.0.7.Final")
//    implementation("com.jagrosh:jda-utilities:3.0.5")

    // to remove
    implementation("ch.obermuhlner:kotlin-big-math:2.3.0")
//    implementation("com.google.guava:guava:31.1-jre")
//    implementation("dev.brachtendorf:JImageHash:1.0.0")
//    implementation(group = "org.xerial", name = "sqlite-jdbc", version = "3.34.0")
    implementation(group = "com.beust", name = "klaxon", version = "5.5")
}

//detekt {
//    buildUponDefaultConfig = true // preconfigure defaults
//    allRules = false // activate all available (even unstable) rules.
//    source = project.files("src/")
//    config = files("detekt.yml") // point to your custom config defining rules to run, overwriting default behavior
////    baseline = file("detekt.xml") // a way of suppressing issues before introducing detekt
//
//    reports {
//        html.enabled = true // observe findings in your browser with structure and code snippets
//        xml.enabled = true // checkstyle like format mainly for integrations like Jenkins
//        txt.enabled = true // similar to the console output, contains issue signature to manually edit baseline files
//        sarif.enabled = true // standardized SARIF format (https://sarifweb.azurewebsites.net/) to support integrations with Github Code Scanning
//    }
//}

tasks.jar {
    archiveBaseName.set("corrupted-mainframe")
    from(configurations.runtimeClasspath.get().filter { !it.path.endsWith(".pom") }.map { if (it.isDirectory) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes["Main-Class"] = "com.github.corruptedinc.corruptedmainframe.MainKt"
    }
}

//tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
//    // Target version of the generated JVM bytecode. It is used for type resolution.
//    jvmTarget = "11"
//}

// https://stackoverflow.com/a/55741901
tasks {
    val sourcesJar by creating(Jar::class) {
        archiveClassifier.set("sources")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        from(sourceSets.main.get().allSource, configurations.runtimeClasspath.get().filter { !it.path.endsWith(".pom") }.map { if (it.isDirectory) it else zipTree(it) }, sourceSets.main.get().output)
    }

//    val javadocJar by creating(Jar::class) {
//        dependsOn.add(javadoc)
//        archiveClassifier.set("javadoc")
//        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
//        from(javadoc)
//    }

    val classesJar by creating(Jar::class) {
        dependsOn.add(classes)
        archiveClassifier.set("classes")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        from(/*sourceSets.main.get().allSource, configurations.runtimeClasspath.get().filter { !it.path.endsWith(".pom") }.map { if (it.isDirectory) it else zipTree(it) },*/ sourceSets.main.get().output)
//        from(configurations.runtimeClasspath.get().filter { !it.path.endsWith(".pom") }.map { if (it.isDirectory) it else zipTree(it) })
    }

    artifacts {
        archives(sourcesJar)
//        archives(javadocJar)
        archives(classesJar)
        archives(jar)
    }
}
