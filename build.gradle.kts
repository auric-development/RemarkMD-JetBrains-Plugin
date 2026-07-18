plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.2.0"
    // 2.18.1+ requires Gradle 9; staying on 2.1.0 keeps us on the Gradle 8.10 wrapper.
    id("org.jetbrains.intellij.platform") version "2.1.0"
}

group = "com.bpineau"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Build/run base. The plugin depends only on com.intellij.modules.platform, so it loads in
        // every JetBrains IDE; IntelliJ IDEA Community is just the SDK we compile and runIde against.
        // 2024.2 is the SDK contemporary with platform Gradle plugin 2.1.0 — pairing 2.1.0 with a
        // much newer IDE (2025.2) trips a product-info parsing bug (Index: 1, Size: 1) in both the
        // `test` and `runIde` tasks. sinceBuild=242 still lets the built plugin run in 2024.2→current.
        intellijIdeaCommunity("2024.2")
    }
    // Bundled with the plugin (classloader-isolated). SnakeYAML parses/serializes the mdreview block.
    implementation("org.yaml:snakeyaml:2.3")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // The plugin runtime gets kotlin-stdlib from the IDE, but our out-of-IDE unitTest JVM does not,
    // and `kotlin.stdlib.default.dependency=false` disabled the automatic one — add it for tests.
    testImplementation(kotlin("stdlib"))
}

intellijPlatform {
    // Pure Kotlin, no Java/UI-forms to instrument — skip the NotNull/form instrumenter (which
    // otherwise fails with "No Java Compiler dependency found").
    instrumentCode = false
    // We ship no Settings UI, and the task launches a headless IDE that fails in this environment.
    buildSearchableOptions = false
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "242"
        }
    }
}

kotlin {
    jvmToolchain(21)
}

// The IntelliJ Platform Gradle Plugin decorates the built-in `test` task with platform JVM args,
// and 2.1.0 crashes doing so against the 2025.2 product-info layout (ProductInfo.resolveIdeHomeVariable
// -> IndexOutOfBounds). Our tests are pure logic and need no IDE runtime, so run them in a plain
// Test task the plugin leaves alone.
val unitTest = tasks.register<Test>("unitTest") {
    description = "Runs the pure-logic unit tests without the IntelliJ Platform test harness."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
}

tasks.named("check") {
    dependsOn(unitTest)
}
