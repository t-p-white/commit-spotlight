import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    kotlin("jvm") version "2.3.10"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "com.twhite"
version = "1.0.5"

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

/**
 * CHANGELOG.md's newest released `## [x.y.z]` section (skipping `## [Unreleased]`), converted
 * to the light HTML `<change-notes>` expects — this is what the JetBrains Marketplace actually
 * shows as a version's release notes; it has no way to read CHANGELOG.md from the repo on its
 * own; a plugin descriptor with no `<change-notes>` (the state this project was in before) just
 * shows nothing there. Baking it in here, from the same file humans read on GitHub, means there's
 * one changelog to keep up to date instead of two.
 */
fun latestChangelogEntryAsHtml(): String {
    val lines = file("CHANGELOG.md").readText().lines()
    val versionHeader = Regex("""^## \[(.+?)](?: - .+)?$""")
    val startIndex = lines.indexOfFirst { line ->
        val version = versionHeader.find(line)?.groupValues?.get(1)
        version != null && version != "Unreleased"
    }
    if (startIndex < 0) return ""
    val endIndex = ((startIndex + 1) until lines.size)
        .firstOrNull { lines[it].startsWith("## [") }
        ?: lines.size

    val html = StringBuilder()
    var currentBullet: StringBuilder? = null
    var inList = false
    fun flushBullet() {
        currentBullet?.let { html.append("<li>").append(it.toString().trim()).append("</li>\n") }
        currentBullet = null
    }
    for (raw in lines.subList(startIndex + 1, endIndex)) {
        val line = raw.trimEnd()
        when {
            line.isBlank() -> Unit
            line.startsWith("### ") -> {
                flushBullet()
                if (inList) {
                    html.append("</ul>\n")
                    inList = false
                }
                html.append("<h3>").append(line.removePrefix("### ")).append("</h3>\n")
            }
            line.startsWith("- ") -> {
                flushBullet()
                if (!inList) {
                    html.append("<ul>\n")
                    inList = true
                }
                currentBullet = StringBuilder(line.removePrefix("- "))
            }
            // A wrapped continuation of the line above (indented, no leading "- ").
            currentBullet != null -> currentBullet?.append(' ')?.append(line.trim())
        }
    }
    flushBullet()
    if (inList) html.append("</ul>\n")
    return html.toString()
}

dependencies {
    intellijPlatform {
        local(androidStudioPath)

        bundledPlugin("Git4Idea")
        bundledModule("intellij.platform.vcs.log")
        bundledModule("intellij.platform.vcs.log.impl")

        // Resolves straight from the local IDE's own lib/testFramework.jar rather than a Maven
        // coordinate — same reasoning as `local(androidStudioPath)` above: a canary build's test
        // framework artifacts aren't reliably published either.
        testFramework(TestFrameworkType.Bundled)
    }

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")

    // BasePlatformTestCase is JUnit3-style (extends junit.framework.TestCase); the Vintage
    // engine is what lets the JUnit Platform launcher (useJUnitPlatform(), below) discover and
    // run those alongside the Jupiter tests.
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.2")
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
        changeNotes = latestChangelogEntryAsHtml()
        ideaVersion {
            // Matches the exact platform branch this has actually been built/tested against
            // (Android Studio 2026.1, AI-261.x). Older branches are untested — some APIs used
            // here (codeInsight.hints presentations, certain VCS Log methods) may not exist
            // that far back. Lower this only after verifying against the real older build.
            sinceBuild = "261"
        }
    }
}
