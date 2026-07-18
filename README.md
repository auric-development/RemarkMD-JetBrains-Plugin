# RemarkMD for JetBrains

A JetBrains IDE plugin port of [RemarkMD](../RemarkMD) — Google-Docs-style commenting for
Markdown, where the other reviewer is Claude. Comments are stored inside the file itself, as YAML
front matter under an `mdreview` key, anchored to `[^cN]` footnote markers in the prose.

Because it depends only on `com.intellij.modules.platform`, one build runs in every JetBrains IDE
(IntelliJ IDEA, PyCharm, WebStorm, GoLand, CLion, Rider, RubyMine, PhpStorm, …).

## Status: walking skeleton

Open a `.md` file and it opens as a split editor: the IDE's own Markdown text editor (the write
side) next to RemarkMD's rendered preview (the read side, a JCEF browser hosting the app's own
renderer). Select a passage in the preview and click **Add Comment** — the comment is written into
the file as `mdreview` YAML and a `[^cN]` marker is inserted inline. Markers are clickable.

Deferred to later milestones: comment sidebar, resolve/reopen/delete, ⌘⇧K "Copy for Claude"
export, mermaid diagrams, local images.

## Build & run

```bash
./gradlew runIde        # launch a sandbox IDE with the plugin
./gradlew test          # JUnit ports of the pure parser/model/script suites
./gradlew buildPlugin   # distributable ZIP in build/distributions/
```

Requires JDK 21 (the 2025.2 platform baseline).

## Layout

- `src/main/kotlin/com/bpineau/remarkmd/core/` — the pure logic ported from Swift
  (`DocumentParser`, models, `EditorScript`, `Comments`).
- `src/main/kotlin/com/bpineau/remarkmd/editor/` — the JCEF preview panel, the `FileEditor`, and
  the split-editor provider.
- `src/main/resources/web/` — the renderer assets (`markdown.js`, `editor.css`, `shell.html`),
  extracted verbatim from the Mac app.
- `NOTES-jetbrains-plugin-howto.md` — working notes on how JetBrains plugins are built.
