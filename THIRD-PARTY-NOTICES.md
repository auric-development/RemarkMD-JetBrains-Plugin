# Third-Party Notices

The RemarkMD JetBrains plugin distribution bundles the third-party components listed below. Each is
the property of its respective authors and is used under the license shown. The plugin's own code is
licensed separately (see `LICENSE`).

---

## Mermaid

- Component: `mermaid` (diagram renderer)
- Version: 11.16.0
- File shipped: `src/main/resources/web/mermaid.min.js` (the standalone `dist/mermaid.min.js` IIFE)
- Project: https://github.com/mermaid-js/mermaid
- License: MIT
- Full provenance and license text: `src/main/resources/web/mermaid-license.txt`

Mermaid inlines a dependency tree (including DOMPurify, d3, dagre, cytoscape, js-yaml, lodash and
KaTeX). Those components' notices are carried within the Mermaid distribution and its bundled license
file. Mermaid is used strictly offline here: it is injected into a JCEF page whose Content-Security-
Policy forbids all network access, and it makes no outbound requests as bundled.

```
MIT License

Copyright (c) 2014 - 2022 Knut Sveidqvist

Permission is hereby granted, free of charge, to any person obtaining a copy of
this software and associated documentation files (the "Software"), to deal in
the Software without restriction, including without limitation the rights to
use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
the Software, and to permit persons to whom the Software is furnished to do so,
subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
```

---

## SnakeYAML

- Component: `org.yaml:snakeyaml`
- Version: 2.6
- Shipped as: a JAR in the plugin distribution's `lib/` folder (declared in `build.gradle.kts`)
- Project: https://bitbucket.org/snakeyaml/snakeyaml
- License: Apache License, Version 2.0

SnakeYAML is used to parse and serialize the `mdreview` YAML front matter, with a `SafeConstructor`
(no arbitrary type instantiation).

```
   Copyright (c) 2008, SnakeYAML

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
```

The full Apache License 2.0 text is available at https://www.apache.org/licenses/LICENSE-2.0.
