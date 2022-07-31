plugins {
    id("kotlin")
}

sourceSets.main {
    java.srcDirs("src")
    resources.srcDirs("resources")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.google.devtools.ksp:symbol-processing-api:1.7.10-1.0.6")
    implementation("com.squareup:kotlinpoet-ksp:1.12.0")
    implementation("com.squareup:kotlinpoet:1.12.0")
    implementation(project(":annotations"))
    implementation("net.dv8tion:JDA:5.0.0-alpha.9")
    implementation("com.github.minndevelopment:jda-ktx:0.8.4-alpha.5")
    implementation(kotlin("reflect"))
}
