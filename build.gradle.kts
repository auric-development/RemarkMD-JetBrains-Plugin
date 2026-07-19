import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

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

        // The IntelliJ Plugin Verifier CLI, resolved from JetBrains' repository (defaultRepositories()
        // above), so `verifyPlugin` has an executable to run. Without this the task fails immediately
        // with "No IntelliJ Plugin Verifier executable found".
        pluginVerifier()

        // The Marketplace ZIP Signer CLI, so `signPlugin` has an executable. Without it, once the
        // signing env vars are set, `signPlugin` fails with "No Marketplace ZIP Signer executable
        // found". Costs nothing when signing is not run.
        zipSigner()
    }
    // Bundled with the plugin (classloader-isolated). SnakeYAML parses/serializes the mdreview block.
    implementation("org.yaml:snakeyaml:2.6")

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
            // Leave the upper bound OPEN. The platform plugin otherwise defaults until-build to the
            // SDK's own branch (242.*), which would make the built plugin REFUSE to install on 2024.3
            // and every later IDE — the opposite of the "runs in 2024.2→current" intent. The 2.x
            // idiom for "no upper bound" is a provider that yields null.
            untilBuild = provider { null }
        }
        // Shown as "What's New" on the Marketplace listing and patched into plugin.xml at build.
        // The patcher wraps this in CDATA itself, so the value is HTML without a CDATA wrapper.
        changeNotes = """
            <ul>
              <li>Initial preview: Google-Docs-style Markdown commenting with comments stored in the file's <code>mdreview</code> YAML front matter.</li>
              <li>Rendered read/comment preview (JCEF) alongside the IDE's Markdown editor, with a comments tool window.</li>
              <li>Mermaid diagrams and local images in the preview.</li>
              <li>Copy Open Comments for Claude, for handing a review off to an AI reviewer.</li>
              <li>Hardened the preview: strict Content-Security-Policy (no network), a navigation guard, URL-scheme allowlisting, and confinement of local image reads to the document's project.</li>
            </ul>
        """.trimIndent()
    }

    // Signing & publishing are OPT-IN through environment variables. Each property is bound to a
    // `providers.environmentVariable(...)` provider, which is simply empty when the variable is
    // unset — so `buildPlugin` and `unitTest` (which never run `signPlugin`/`publishPlugin`) build
    // green with no secrets present. The values are consumed only when you actually invoke
    // `./gradlew signPlugin` or `./gradlew publishPlugin`; a missing value fails at that point, not
    // at configuration time, and never in day-to-day builds. See SUBMISSION.md for how to generate
    // the certificate chain / private key and obtain a Marketplace PUBLISH_TOKEN.
    //   CERTIFICATE_CHAIN     — the signing certificate chain (PEM text; base64 or a data: URI is
    //                           also accepted for multi-line safety in CI secret fields)
    //   PRIVATE_KEY           — the matching private key (PEM text; base64/data: URI accepted)
    //   PRIVATE_KEY_PASSWORD  — the password protecting the private key
    //   PUBLISH_TOKEN         — Marketplace token (Account → My Tokens); NOT needed for the manual
    //                           first upload, only for later `publishPlugin` automation.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // Marketplace release channel. Empty/"default" is the public stable channel; set e.g.
        // PUBLISH_CHANNEL=beta to push a pre-release build to a custom channel instead.
        channels = providers.environmentVariable("PUBLISH_CHANNEL")
            .map { listOf(it) }
            .orElse(listOf("default"))
    }

    // `verifyPlugin` runs the JetBrains Plugin Verifier against real IDE builds. We compile against
    // 2024.2 and declare an open until-build, so we verify against that same 2024.2 baseline — the
    // lower bound the plugin promises to run on. (recommended() would fan out across many IDE
    // downloads and, with an open until-build, keep chasing the latest release; pinning keeps the
    // check deterministic and offline-cache-friendly.)
    pluginVerification {
        ides {
            ide(IntelliJPlatformType.IntellijIdeaCommunity, "2024.2")
        }
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // Kotlin's default jvm-default mode emits, in every class that implements an interface with
        // default methods, a synthetic override bridge that delegates to the interface default. For
        // CommentsToolWindowFactory (which implements only createToolWindowContent) that produced 12
        // bridges over ToolWindowFactory's deprecated/experimental/internal defaults (isApplicable,
        // manage, getAnchor, getIcon, isDoNotActivateOnStart) — bridges we never wrote, that the
        // Plugin Verifier then flags as our usages. `no-compatibility` inherits the real JVM default
        // methods instead of re-delegating, so those bridges — and the verifier findings — disappear.
        freeCompilerArgs.add("-jvm-default=no-compatibility")
    }
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
