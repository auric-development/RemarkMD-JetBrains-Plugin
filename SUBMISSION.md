# JetBrains Marketplace Submission Package

This document is the ready-to-submit checklist for publishing **RemarkMD**
(`com.bpineau.remarkmd`, version `0.1.0`) to the [JetBrains Marketplace](https://plugins.jetbrains.com).
Claude prepared everything that does not require the vendor's JetBrains account; the manual steps
only you can perform are called out under **What only you can do** at the end.

---

## 1. The distributable

- **File:** `build/distributions/RemarkMD-JetBrains-0.1.0.zip` (~1.39 MB)
- **Rebuild it:** `./gradlew buildPlugin` (JDK 21 on PATH; Gradle 8.10 wrapper)
- **Contents (`unzip -l`):**

  ```
  RemarkMD-JetBrains/
  RemarkMD-JetBrains/lib/snakeyaml-2.6.jar          (340 KB)
  RemarkMD-JetBrains/lib/RemarkMD-JetBrains-0.1.0.jar (1.10 MB)
  ```

  This two-jar-in-a-folder layout is the standard IntelliJ Platform plugin ZIP. The Marketplace
  wants exactly this ZIP — do not repackage it.

- **What is inside the plugin jar** (`RemarkMD-JetBrains-0.1.0.jar`), all bundled as resources:
  `META-INF/plugin.xml`, `META-INF/pluginIcon.svg` (the Marketplace logo), `web/markdown.js`,
  `web/diagram.js`, `web/editor.css`, `web/shell.html`, `web/mermaid.min.js` (3.4 MB),
  `web/mermaid-license.txt`, plus all compiled `.class` files.
- **Not in the ZIP** (by design, standard for JetBrains plugins): repository `LICENSE` (MIT) and
  `THIRD-PARTY-NOTICES.md`. License metadata for the Marketplace lives in `plugin.xml`; the bundled
  `web/mermaid-license.txt` carries Mermaid's full text. This is not a packaging defect.

---

## 2. `plugin.xml` completeness

| Element | Value | Status |
|---|---|---|
| `<id>` | `com.bpineau.remarkmd` | Set. Immutable after first publish. |
| `<name>` | `RemarkMD` | Set. Avoids "JetBrains"/IDE product names and the word "plugin" per Marketplace naming rules. |
| `<vendor>` | `Bernie Pineau`, email `bernie.pineau@gmail.com`, url the GitHub repo | Set. |
| `<description>` | HTML/CDATA: what it does + bundled third-party notice + Anthropic trademark disclaimer | Set. Marketplace requires a meaningful description (min length enforced on upload). |
| `change-notes` | Patched in from `build.gradle.kts` `changeNotes` at build time (shown as "What's New") | Set. |
| `since-build` | `242` (from `ideaVersion.sinceBuild`) | Set. |
| `until-build` | **Open** (`untilBuild = provider { null }`) | Intentionally unbounded so the build installs on 2024.2 → current, not just the 242.* branch. |
| logo / icon | `META-INF/pluginIcon.svg` (798 bytes) | Present in the jar. Marketplace uses it as the listing logo. Optionally add `pluginIcon_dark.svg` for dark theme. |
| `<depends>` | `com.intellij.modules.platform` only | Makes it install in every JetBrains IDE, not just IntelliJ IDEA. |

**Compatibility range:** since-build `242` (2024.2) with an open upper bound — i.e. IntelliJ IDEA
2024.2 and every later JetBrains IDE that shares the platform. The plugin depends only on
`com.intellij.modules.platform`, so it is not IDE-specific.

---

## 3. Plugin Verifier result

`./gradlew verifyPlugin` was run against the compile baseline:

> **Plugin `com.bpineau.remarkmd:0.1.0` against `IC-242.20224.300`: Compatible**

- No deprecated / experimental / internal API usages.
- No Compatibility problems, no Structure problems.
- Full report: `build/reports/pluginVerifier/IC-242.20224.300/report.html` and
  `.../plugins/com.bpineau.remarkmd/0.1.0/verification-verdict.txt` (`Compatible`).

The Marketplace runs its own verifier on upload; a clean local `Compatible` is the strongest
pre-check you can do before submitting.

> Note: the verifier does not launch JCEF and `runIde` is intentionally not used in this
> environment, so the runtime browser behaviour (JCEF CSP, Mermaid rendering, `rmimg:` local image
> loads) is **unexercised**. A one-off manual `runIde` smoke test on a developer machine is the only
> way to confirm those before release. This is a runtime-QA gap, not a Marketplace blocker.

---

## 4. Security posture (for the Marketplace review)

The reviewers look for network calls, code execution, and data exfiltration. RemarkMD's answer to
all three is "none, and it is enforced, not merely intended":

- **No network.** The rendered preview is a JCEF (embedded Chromium) page carrying a strict
  Content-Security-Policy that permits only inline/`data:` resources — no `connect-src`, no remote
  `img-src`, no remote `script-src`. A navigation guard blocks the page from navigating anywhere.
  Mermaid (3.4 MB) is bundled and runs strictly offline inside that CSP; it is never fetched.
- **URL-scheme allowlisting.** The page's requests are gated to known schemes; the custom `rmimg:`
  scheme handler is the only path by which local bytes reach the page.
- **File handling is confined.** Local image reads are restricted to files within the document's
  own project — the handler will not read arbitrary paths off disk.
- **No arbitrary code execution.** The plugin does not download, compile, or run user-supplied code.
  It reads and writes the open `.md` file's text through the platform Document API
  (`WriteCommandAction`), which gives it undo/save for free and nothing more.
- **Third-party components** (both bundled, both permissive, both offline):
  - **Mermaid** 11.16.0 — MIT. Full text: `web/mermaid-license.txt` (bundled) and
    `THIRD-PARTY-NOTICES.md`.
  - **SnakeYAML** 2.6 — Apache License 2.0. Used to parse/serialize the `mdreview` YAML front matter.
  - Both are disclosed in the plugin `<description>`.
- **Trademark:** "Claude" is Anthropic's; the description states the plugin is independent and
  unaffiliated. Including this pre-empts a common Marketplace trademark hold.
- **Plugin's own license:** MIT (`LICENSE`).

---

## 5. Signing the plugin

JetBrains requires uploaded plugins to be signed. The build is already wired for it — the
`signPlugin` task is configured from environment variables (see `build.gradle.kts`, the
`signing { }` block), and the build stays green when they are absent because the providers are
simply empty until you run `signPlugin`.

### 5a. Generate a certificate + key (one time)

From the JetBrains signing docs (https://plugins.jetbrains.com/docs/intellij/plugin-signing.html):

```bash
# 1. Generate an encrypted 4096-bit RSA private key (you will be prompted for a password —
#    remember it; it becomes PRIVATE_KEY_PASSWORD).
openssl genpkey -aes-256-cbc -algorithm RSA -out private_encrypted.pem -pkeyopt rsa_keygen_bits:4096

# 2. Produce the decrypted RSA key the signer actually loads.
openssl rsa -in private_encrypted.pem -out private.pem

# 3. Self-signed certificate chain (valid 365 days; bump -days as you like).
openssl req -key private.pem -new -x509 -days 365 -out chain.crt
```

A self-signed certificate is accepted — JetBrains verifies the signature against the certificate
you upload with the plugin; it does not require a CA-issued cert.

### 5b. Set the environment variables and sign

```bash
export CERTIFICATE_CHAIN="$(cat chain.crt)"
export PRIVATE_KEY="$(cat private.pem)"
export PRIVATE_KEY_PASSWORD="the password you chose in step 1"

./gradlew signPlugin
```

The signed artifact lands in `build/distributions/` (a `*-signed.zip`). Upload the **signed** ZIP.

> Keep `private.pem`, `private_encrypted.pem`, and `chain.crt` OUT of git. The repo `.gitignore`
> already excludes `*.pem`, `*.key`, and `*token*.txt`. Never commit these.

> If your CI secret store mangles multi-line PEM values, base64-encode them and adjust; the JetBrains
> docs cover the base64 path. Locally, the `$(cat ...)` form above works as-is.

---

## 6. Upload / publish

### First publication — MUST be manual

Per the JetBrains publishing docs
(https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html):

1. Have (or create) a **JetBrains Account**: https://account.jetbrains.com.
2. Sign the plugin (section 5).
3. Go to https://plugins.jetbrains.com/author/me → **Add new plugin** ("Upload plugin").
4. Upload `build/distributions/RemarkMD-JetBrains-0.1.0-signed.zip`, fill in the listing form
   (category, tags, license, source URL, optional screenshots), and submit.

- **First-time vendor approval:** a brand-new author's first plugin is held for **manual moderation
  by the JetBrains Marketplace team** before it goes public. This is a human review; in practice it
  typically takes **around 2 business days**, sometimes longer. Subsequent updates from an approved
  vendor publish much faster (often automatically after the automated verifier passes).
- **Category / tags / license:** pick a category (e.g. *Editors* / *Markdown*), set the license to
  **MIT** on the listing, and point the source field at the GitHub repo.
- **Screenshots** are optional but strongly recommended for a good listing (see manual steps below).

### Later updates — can be automated

Once the plugin exists and the vendor is approved, subsequent versions can be pushed with:

```bash
export PUBLISH_TOKEN="<Marketplace token from Account → My Tokens>"
# optional: export PUBLISH_CHANNEL=beta   # default is the public stable channel
./gradlew publishPlugin
```

`publishPlugin` is already configured in `build.gradle.kts` (reads `PUBLISH_TOKEN` and optional
`PUBLISH_CHANNEL`). Do **not** use it for the first upload — that one must be manual.

---

## 7. Is it submission-ready?

**Yes, for a `0.1.0` preview upload**, with two caveats you should clear first:

- **Ready:** builds green (`buildPlugin` + `unitTest`), verifier says `Compatible`, `plugin.xml`
  has every required field, security posture is clean and documented, third-party licenses are
  bundled and disclosed, and signing/publishing are wired for env-var use.
- **Do before you actually upload:**
  1. **Sign** the ZIP (section 5) — the Marketplace requires it.
  2. **One manual `runIde` smoke test** on your machine to confirm the JCEF preview renders,
     Mermaid draws, and images load — the one thing the verifier cannot check.
  3. Optionally capture **2–3 screenshots** for the listing.

---

## What only you can do (Claude cannot)

1. **Create/own the JetBrains Account** and accept the vendor agreement.
2. **Generate the signing certificate + key** (section 5a) — these are your secrets; they must not
   live in the repo or be produced by an agent.
3. **Run the manual first upload** at https://plugins.jetbrains.com/author/me and complete the
   listing form.
4. **Run a `runIde` smoke test** to eyeball the live preview (agents here are barred from `runIde`).
5. **Take listing screenshots.**
6. **Obtain the `PUBLISH_TOKEN`** (Account → My Tokens) if/when you automate later updates.
