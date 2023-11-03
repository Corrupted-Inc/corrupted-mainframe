plugins {
    id("kotlin")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

sourceSets.main {
    java.srcDirs("src")
    resources.srcDirs("resources")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.google.devtools.ksp:symbol-processing-api:1.9.20-1.0.14")
    implementation("com.squareup:kotlinpoet-ksp:1.12.0")
    implementation("com.squareup:kotlinpoet:1.12.0")
    implementation(project(":annotations"))
    implementation("net.dv8tion:JDA:5.0.0-beta.17")
    implementation("com.github.minndevelopment:jda-ktx:0.8.4-alpha.5")
    implementation(kotlin("reflect"))
}
