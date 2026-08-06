plugins {
    id("java")
    kotlin("jvm") version "2.3.10"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "com.twhite"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Path to a local Android Studio install, used to build/test against since recent/canary
// builds aren't always mirrored to a resolvable Maven artifact. Override per-machine via
// -PandroidStudioPath=... or the ANDROID_STUDIO_PATH env var — don't hardcode your own path
// here, since this file is checked in.
val androidStudioPath: String =
    (project.findProperty("androidStudioPath") as String?)
        ?: System.getenv("ANDROID_STUDIO_PATH")
        ?: "/Applications/Android Studio.app/Contents"

dependencies {
    intellijPlatform {
        local(androidStudioPath)

        bundledPlugin("Git4Idea")
        bundledModule("intellij.platform.vcs.log")
        bundledModule("intellij.platform.vcs.log.impl")
    }

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
}

intellijPlatform {
    pluginConfiguration {
        name = "Commit Spotlight"
        version = project.version.toString()
        ideaVersion {
            // Matches the exact platform branch this has actually been built/tested against
            // (Android Studio 2026.1, AI-261.x). Older branches are untested — some APIs used
            // here (codeInsight.hints presentations, certain VCS Log methods) may not exist
            // that far back. Lower this only after verifying against the real older build.
            sinceBuild = "261"
        }
    }
}
