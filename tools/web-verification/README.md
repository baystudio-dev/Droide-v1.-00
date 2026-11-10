# Browser DOM regression probe

`verify_agent_web_browser.py` compiles `AgentBrowserSnapshot.kt` and
`AgentBrowserInteraction.kt` with the JVM probe exporter, then executes the exact
production JavaScript in actual jsdom documents. Model queries, targets and typed
text remain JSON data in this path.

Provision matching Kotlin compiler/runtime/serialization JARs and jsdom outside
the source tree. The verification dependency is not an Android dependency and
must not be included in the source archive or APK.

```sh
npm install --prefix /path/to/external-dom --no-audit --no-fund jsdom@26.1.0
python tools/verify_agent_web_browser.py \
  --jars /path/to/matching-jvm-jars \
  --node-modules /path/to/external-dom/node_modules \
  --output /path/to/browser-dom-proof.json
```

The runner accepts multiple `--jars` directories. Use compiler/runtime JARs
matching the Kotlin version declared by the application build.
Receipts identify the jsdom version and hash every production input and runner.

Coverage includes paged lines/find/links and stable targets, initial textarea and
selected-option text, contenteditable and ARIA textbox privacy, CSS visibility
overrides, SVG anchors, duplicate/oversized targets, bounded huge/deep DOMs,
encoded output size, SELECT/textarea setters and events, human-only password/file
inputs, clicks, and code-like target/text strings remaining literal data.

The snapshot traverses a rendered prefix, stopping at 20,000 nodes, 10,000 computed
style checks, 300,000 text characters, 100,000 source lines, 10,000 elements, or depth 256. Hidden
`display:none` subtrees and private form/editable subtrees are skipped. A
`visibility:hidden` parent is traversed so a child's explicit `visibility:visible`
can still be read. CSS can also override an HTML `hidden` attribute. Source,
elements and links report incompleteness and the reason when a budget stops work.
Links now represent rendered anchors with targets, rather than hidden anchors.
Offsets refer to this capped live DOM snapshot, not an immutable page archive.
New or repaired target ID attributes are written in one bounded batch after all
style/layout reads, avoiding interleaved attribute mutation and forced reflow.
Ordinary `read` requests skip URL normalization, while `find` skips control
rectangle reads, target-ID mutations, and link URL normalization. `find` counts
all matches in the bounded text prefix but retains only its requested result
page. Deterministic probe counters verify this removed work; they are not CPU,
RAM, battery or wall-clock measurements on a physical phone.

Normal outputs are capped at 18,000 encoded JavaScript characters. If even one
item cannot fit, a bounded failure retains the exact page URL and returns no
continuation cursor; it never shortens a navigation URL or emits a cursor that
cannot advance. The enclosing Kotlin tool still enforces its own output bound.

jsdom provides real DOM trees, attributes, input value setters and dispatched
events, but no Chromium layout. Element rectangles are explicitly mocked, and
the receipts report `realChromium:false` and `realAndroidWebView:false`. These
fixtures do not certify Android rendering, WebView behavior, or physical-device
performance. CSS generated content, closed shadow roots, child-frame documents,
canvas and virtualized content are outside this text snapshot.
