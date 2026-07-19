# How to build a JetBrains IDE plugin — working notes

Raw material for a future `jetbrains-plugin` skill. Written while porting RemarkMD (a macOS
Swift/SwiftUI app) to a plugin that runs in every JetBrains IDE. Everything here is verified
against a real build, not recalled from docs.

## The one-paragraph mental model

Every JetBrains IDE (IntelliJ IDEA, PyCharm, WebStorm, GoLand, CLion, Rider, RubyMine, PhpStorm…)
is the **IntelliJ Platform** with language modules bolted on. A plugin is Kotlin/Java (JVM) built
with Gradle. If it declares `<depends>com.intellij.modules.platform</depends>` and nothing
language-specific, one build runs in all of them. You hook into the IDE through **extension
points** declared in `META-INF/plugin.xml` (editors, tool windows, actions, …). `./gradlew runIde`
launches a sandbox IDE with the plugin loaded; `buildPlugin` makes a distributable ZIP.

## Toolchain

- **Language:** Kotlin (Kotlin-first docs/tooling; Java also works).
- **Build:** IntelliJ Platform Gradle Plugin **2.x** (`org.jetbrains.intellij.platform`). The 1.x
  `org.jetbrains.intellij` is legacy — do not use it for new projects.
- **JDK:** 2025.2 needs **JDK 21** (2024.2–2025.1 need 17). `kotlin { jvmToolchain(21) }`.
- **No system Gradle needed** — generate the wrapper. If bootstrapping with no gradle at all:
  download a distribution (`curl .../gradle-8.10.2-bin.zip`), unzip, run
  `.../bin/gradle wrapper --gradle-version 8.10.2` once in the project to write `gradlew` +
  `gradle/wrapper/*`.

## Minimal project layout

```
project/
  settings.gradle.kts          # rootProject.name = "..."
  build.gradle.kts
  gradle/wrapper/…, gradlew
  src/main/kotlin/…            # code
  src/main/resources/
    META-INF/plugin.xml        # manifest
    web/…                      # any bundled assets (JS/CSS/etc.)
  src/test/kotlin/…            # JUnit 5
```

## build.gradle.kts (the shape that works)

```kotlin
plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.1.0"   // 2.18.1 is newer; 2.1.0 builds fine
}
repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }              // REQUIRED for platform artifacts
}
dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2025.2")                     // the SDK you compile/runIde against
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
    implementation("org.yaml:snakeyaml:2.3")                // bundled libs are classloader-isolated
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
intellijPlatform {
    pluginConfiguration { ideaVersion { sinceBuild = "242" } }
}
kotlin { jvmToolchain(21) }
tasks.test { useJUnitPlatform() }
```

Gotchas found:
- **The Kotlin plugin version must match the platform's Kotlin metadata version.** IntelliJ 2025.2
  jars carry Kotlin metadata `2.2.0`; compiling with Kotlin `2.0.21` fails on every platform jar
  with *"Module was compiled with an incompatible version of Kotlin. The binary version of its
  metadata is 2.2.0, expected version is 2.0.0."* Fix: bump `org.jetbrains.kotlin.jvm` to `2.2.0`.
  Rule of thumb: newer IDE builds need a newer Kotlin plugin — match it, don't guess low.
- **Disable the auto-added stdlib.** The Kotlin Gradle plugin adds `kotlin-stdlib`, which can
  conflict with the platform's own. Put `kotlin.stdlib.default.dependency=false` in
  `gradle.properties` (the build even prints a jb.gg link telling you to).
- **The platform Gradle plugin version is tied to a minimum Gradle version.** `2.18.1` *requires
  Gradle 9.0+*; on a Gradle 8.10 wrapper it fails at apply-time with "requires Gradle 9.0.0 and
  higher". Either bump the wrapper to 9.x or stay on an older platform plugin (`2.1.0` works on
  Gradle 8.10 and still consumes the 2025.2 SDK fine). The "plugin is outdated" message is
  cosmetic — ignore it unless you actually need a newer-plugin feature.
- **`:instrumentCode` fails with "No Java Compiler dependency found"** on a pure-Kotlin plugin.
  The instrumenter (NotNull assertions, UI `.form` binding) needs a Java compiler dependency you
  don't otherwise have. Fix: either add `intellijPlatform { instrumentationTools() }` to
  `dependencies`, or — if you have no Java/forms — set `intellijPlatform { instrumentCode = false }`
  in the extension.
- **`:buildSearchableOptions` fails** (it launches a headless IDE to index Settings entries). If
  the plugin ships no Settings UI — or you're in a headless/CI environment — set
  `intellijPlatform { buildSearchableOptions = false }`.
- **Match the platform Gradle plugin version to the target IDE's era — this is the big one.**
  Pairing an old plugin (2.1.0) with a much newer IDE (2025.2) crashes *both* `runIde` and `test`
  with `ProductInfo.resolveIdeHomeVariable → IndexOutOfBoundsException: Index: 1, Size: 1`: the old
  plugin can't parse the newer `product-info.json` launch layout. Two ways out — (a) upgrade the
  plugin (but 2.18.1+ needs Gradle 9, so bump the wrapper too and check Kotlin-plugin/Gradle-9
  compat), or (b) target the IDE SDK contemporary with your plugin version (plugin 2.1.0 ↔ IDE
  2024.2). We took (b): `intellijIdeaCommunity("2024.2")`, `sinceBuild = "242"`. The built plugin
  still runs in 2024.2→current because compatibility is set by `<depends>` + sinceBuild, not by the
  compile SDK. Newer Kotlin compiler on an older SDK is fine (a 2.2.0 compiler reads 2024.2's older
  metadata; the reverse — old compiler, newer metadata — is what fails).
- **The plugin also decorates the built-in `test` task with platform JVM args** (same crash path on
  a mismatched pair). For *pure-logic* unit tests that need no IDE runtime, sidestep it regardless:
  register a plain `Test` task (`tasks.register<Test>("unitTest") { … }`) with
  `testClassesDirs`/`classpath` from the test source set; the plugin leaves undecorated tasks alone.
  (Tests that actually drive the IDE do need the platform test framework — different story.)
- **Out-of-IDE test tasks need kotlin-stdlib explicitly** once you set
  `kotlin.stdlib.default.dependency=false`: the plugin runtime gets stdlib from the IDE, but a plain
  JVM test task doesn't, so every test dies with `NoClassDefFoundError: kotlin/jvm/internal/Intrinsics`.
  Add `testImplementation(kotlin("stdlib"))`.
- The `repositories { intellijPlatform { defaultRepositories() } }` block is mandatory; without it
  the platform artifacts don't resolve.
- The first build downloads the full IDE SDK (~1 GB+) — slow once, cached after.
- `intellijIdeaCommunity(...)` is the compile/run base only; it does NOT restrict which IDEs the
  plugin runs in — that's governed by `plugin.xml` `<depends>`.

## plugin.xml (all-IDE minimal)

```xml
<idea-plugin>
  <id>com.bpineau.remarkmd</id>
  <name>RemarkMD for JetBrains</name>
  <vendor email="…">…</vendor>
  <depends>com.intellij.modules.platform</depends>   <!-- platform only = every JetBrains IDE -->
  <extensions defaultExtensionPointName="com.intellij">
    <fileEditorProvider implementation="com.bpineau.remarkmd.editor.RemarkFileEditorProvider"/>
  </extensions>
</idea-plugin>
```

## Custom editor: source + preview split

- A `FileEditorProvider` (+ `DumbAware`) with `accept()` on the file extension. `createEditor()`
  returns a `TextEditorWithPreview(textEditor, previewEditor, name, Layout.SHOW_EDITOR_AND_PREVIEW)`.
- Get the text (write) half for free: `TextEditorProvider.getInstance().createEditor(project, file)
  as TextEditor`. This is the IDE's own editor — real syntax highlighting and real undo, no code.
- The preview half is any `FileEditor` (extend `UserDataHolderBase`, implement the interface). Its
  `getComponent()` returns your Swing component.
- `getPolicy() = FileEditorPolicy.HIDE_DEFAULT_EDITOR` hides the plain text tab so your split is the
  editor. NOTE: in IDEs that bundle the Markdown plugin this can coexist with *their* editor tab;
  refine later if needed.

## Embedding a browser (JCEF) — the WKWebView analogue

- `JBCefApp.isSupported()` guard, then `val browser = JBCefBrowser()`; add `browser.component` to
  Swing. Load once with `browser.loadHTML(html)`; never reload (it resets scroll).
- **Kotlin → page:** `browser.cefBrowser.executeJavaScript(js, browser.cefBrowser.url, 0)`.
- **page → Kotlin:** `JBCefJSQuery.create(browser as JBCefBrowserBase)`, `.addHandler { payload ->
  …; null }`. Inject it as a JS function AFTER load:
  `"window.rmPost = function(p) { ${query.inject("p")} };"`. `inject("p")` wires the JS expression
  `p` back to the handler. A single query + a delimited payload replaces N message handlers.
- **Wait for load:** `browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
  override fun onLoadEnd(...) { … } }, browser.cefBrowser)`. Queue any JS emitted before `onLoadEnd`
  and drain it there — same discipline as WKWebView `didFinish`.
- **Threading:** `onLoadEnd` and query handlers run OFF the EDT. Any Swing/UI or document mutation
  must hop to EDT via `ApplicationManager.getApplication().invokeLater { … }`.
- **Disposal:** `JBCefBrowser` and `JBCefJSQuery` are `Disposable`; `Disposer.register(parent, it)`
  with the FileEditor as parent so they die with the editor.

## Mutating the document = undo + dirty + save for free

- Get the file's model: `FileDocumentManager.getInstance().getDocument(virtualFile)` →
  `com.intellij.openapi.editor.Document`.
- Change it inside `WriteCommandAction.runWriteCommandAction(project, "Add Comment", null) { … }`.
  This registers one undo step, marks the file dirty, and the IDE saves it. No hand-rolled undo /
  dirty-flag machinery (a whole class of the Mac app's invariants evaporates).
- React to edits (live preview) with a `DocumentListener` on the same `Document`.

## Passing control characters through the tools (meta-gotcha, not JetBrains)

Writing raw control chars (U+0001 as a payload delimiter, U+000C in a `when`) via the editor tools
silently strips them. Author those as explicit escapes — `''`, `''` — or set the line
with a small script. Bit me repeatedly; fix is to never type the raw char.

Worse: a raw control char that *does* survive into a file cannot be fixed with an inline `Bash`
command containing that char — the approval layer refuses it ("command contains control characters
that would be hidden in the approval dialog"). Write a **script file** (the editor tool strips the
char from your source too, so target the byte generically: `chr(1)` in Python, not a typed literal)
and run the script. Grep with `grep -aP '[\x01]'` afterwards to confirm the raw byte is gone.

## Tool windows and light services (the comments sidebar)

- Register a tool window with `<toolWindow id="…" anchor="right" factoryClass="…"/>` in plugin.xml
  and a `ToolWindowFactory.createToolWindowContent`. Wrap your panel with
  `ContentFactory.getInstance().createContent(panel, "", false)` and `content.setDisposer(panel)` so
  the panel's `dispose()` (and everything registered under it in the Disposer tree) is cleaned up.
- A `@Service(Service.Level.PROJECT)` class is a **light service**: auto-registered, no plugin.xml
  entry. Reach it with `project.getService(Foo::class.java)`. Perfect for per-file shared UI state
  (here: one `DocumentState` per `VirtualFile.url`, observed by both the preview and the sidebar).
- Track which file is in front with `FileEditorManagerListener.FILE_EDITOR_MANAGER` on
  `project.messageBus.connect(disposable)`; seed from
  `FileEditorManager.getInstance(project).selectedFiles.firstOrNull()`. Rebind the per-file
  `DocumentListener`/state listener on each `selectionChanged` under a fresh child `Disposable`.
- Two-way link without a scroll bug: keep focus in the shared state and have both panes *observe* it,
  each rendering from live state pulled fresh — not from the change event's payload. Because a
  synchronous listener fires the render, adding a comment renders the new marker AND scrolls to it in
  one pass (the marker exists by the time scroll runs).

## Extracting web assets from Swift string literals

RemarkMD's renderer JS/CSS lived as Swift `"""` literals. To get real files, pull the literal and
replace `\\` → `\` (Swift's escaped backslash → the one char JS/CSS wants). `md.split('\\n')` in
Swift is `md.split('\n')` in the shipped `.js`. Verified by grepping for stray `\\n`.

## YAML with SnakeYAML (bundled in the platform)

- SnakeYAML's default resolver **auto-converts ISO timestamps to `java.util.Date`** — so
  `date: 2026-07-18T00:00:00Z` won't match `value as? String`, and (for RemarkMD) breaks the exact
  round-trip the Swift side gets from Yams. Load with a `Resolver` subclass that keeps every default
  implicit type except `Tag.TIMESTAMP`, so those scalars stay strings.
- Dump with `DumperOptions { defaultFlowStyle = BLOCK }`; omit null optionals from the map to mirror
  how Codable/Yams drops nils. Use snake_case keys explicitly (`resolved_by`, not `resolvedBy`).

## Commands cheat-sheet

- `./gradlew runIde` — sandbox IDE with the plugin.
- `./gradlew buildPlugin` — distributable ZIP in `build/distributions/`.
- `./gradlew test` — JUnit.
- `./gradlew verifyPlugin` — structure/compat check.
