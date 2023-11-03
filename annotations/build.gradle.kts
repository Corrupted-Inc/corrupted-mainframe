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
}
