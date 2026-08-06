plugins {
    // Lets Gradle auto-download a matching JDK toolchain (e.g. JDK 21) when one
    // isn't already installed, instead of requiring JAVA_HOME to be set manually.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "commit-spotlight"
