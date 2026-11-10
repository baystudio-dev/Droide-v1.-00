# External CLI brand marks

`manifest.json` records the sources and SHA-256 hashes of the four CLI marks
used by `ExtensionBrandIcon`. The two new PNG resources are original upstream
files, copied without alteration. DeepSeek and Muse reuse existing provider
vectors rather than duplicating assets. Muse uses the Meta identity displayed
on its official documentation; it is not a separately invented Muse logo.

The Kimi image comes from the official Kimi Code VS Code webview at the pinned
revision in the manifest. Its MIT notice is bundled in
`app/src/main/assets/legal/licenses/MoonshotAI-kimi-code-MIT.txt`.
Antigravity's transparent PNG is linked from Google's official product homepage.
Brand marks identify their products and publishers; they do not imply endorsement
or grant trademark rights.

The catalog uses product-specific keys for Antigravity, Kimi Code and Muse Code.
The shared renderer serves both the Extensions list and details. It preserves the
existing icon slots, aspect ratio and colors. No image download, filesystem scan
or package version lookup occurs at runtime. The images are `drawable-nodpi`
resources, so Android does not pre-scale their pixels for a source density.

For a future logo refresh, update the local resource and its source/hash record
together. Keep the mark's original aspect ratio; do not tint multicolor artwork.
