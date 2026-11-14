# Language support and optional tools

Language registration, file artwork, syntax highlighting and local completion are available without installing a compiler. External language servers start when their features are requested for a matching document. Opening or editing a file does not install packages, probe every toolchain or download a runtime.

| Files | Editing features | Optional external tools |
| --- | --- | --- |
| `.bat`, `.cmd` | Grammar, snippets, completion, label and variable outline, same-file definition navigation | A custom language server for additional analysis. Execution requires Windows `cmd.exe`; Linux shell interpreters are not substitutes. |
| `.ini`, `.conf`, `.cfg` | Grammar, section/key outline; section syntax checks for `.ini` | A custom server for a specific dialect or schema. Generic `.conf`/`.cfg` documents receive no INI syntax errors. |
| `.properties` | Java Properties highlighting, decoded-key outline and Unicode escape checks | A custom server for framework-specific validation. |
| `.makefile` suffix | Syntax support with an explicit status message | The reviewed Make server accepts `Makefile`, `GNUmakefile`, `.mk` and `.mak`. The `.makefile` suffix remains syntax-only unless a custom server supports it. |
| `.mdx` | Markdown editing plus optional dedicated MDX intelligence | Install **MDX** from Extensions. Its Node runtime and compatible TypeScript SDK are installed separately on demand. |
| `.goose` | Official Goose grammar and file icon, completion, snippets and Run registration | Separately install the aardappel/goose compiler; its built-in server supplies language intelligence. |

Local document analysis uses the current unsaved editor buffer. It is read-only, bounded to 256 Ki characters and 2,000 symbols/diagnostics, and never scans the project. Held navigation results are invalidated when their document changes or closes. Local INI/Properties checks cover the rules listed above; a clean result does not certify application configuration. Batch local support does not claim syntax validation. Explicit custom server declarations take precedence.

## MDX

Use the existing Install/Update actions in Extensions. The reviewed server is `@mdx-js/language-server` 0.6.4; publisher updates remain available. Installation resolves a compatible TypeScript SDK, locks package integrity and performs an initialize/shutdown check before activation. A failed installation does not replace a working installation.

The registered built-in route uses the SDK belonging to the selected managed MDX installation, rather than a project `node_modules` copy. It does not enable the server's general TypeScript analysis. The upstream MDX server can load project-specified remark and virtual-code plugins, so project analysis uses the existing explicit Enable/Revoke controls. Opening a file grants neither installation nor project execution permission.

## Goose compiler and server

The language is [aardappel/goose](https://github.com/aardappel/goose), not an AI assistant with the same executable name. Droide deliberately invokes `goose-lang run <file>` and `goose-lang lsp`. Expose the selected compiler as `goose-lang` on the Ubuntu PATH. The compiler version is user-selected; updating it also updates the LSP server.

Only the small upstream editor grammar, icon and license are in the Droide source/APK. No Goose compiler, standard library, TinyCC payload or compiler source is bundled. The current v2026.4 release has no Linux ARM64 prebuilt. Its Linux x64 and macOS ARM64 artifacts cannot run in Droide's Linux ARM64 environment.

For Linux ARM64, use the upstream source build in Droide's Ubuntu terminal when needed. Prerequisites are Git, CMake 3.27 or newer, a C++20 toolchain and C development headers. Install missing prerequisites explicitly through Extensions or Ubuntu's package manager. Then run:

```sh
goose_version=v2026.4
goose_root="$HOME/.local/share/goose-lang/$goose_version"
git clone --depth 1 --branch "$goose_version" https://github.com/aardappel/goose.git "$goose_root"
cmake -S "$goose_root" -B "$goose_root/build" -DCMAKE_BUILD_TYPE=Release -DGOOSE_BUILTIN_LIBS=
cmake --build "$goose_root/build" --parallel 2
"$goose_root/build/goose" version
mkdir -p "$HOME/.local/bin"
ln -s "$goose_root/build/goose" "$HOME/.local/bin/goose-lang"
command -v goose-lang
```

Run each step only after the previous one succeeds. `ln -s` refuses to replace an existing destination; inspect an existing alias before changing it. Keep the source/build directory intact: the compiler uses its matching standard library and TinyCC support files. An explicit `GOOSE_STDLIB` override must point to the matching library. Optional native packages are not built by this command; a program importing them can invoke the compiler's upstream package installation behavior when explicitly run.

For an update, select a new upstream tag, build in a new version directory, verify it, then point the owned `goose-lang` alias at that build and restart the language server. Keep the previous version until the new one works. No update or source build runs during editor startup.

The integration is verified with the upstream v2026.4 Linux x64 compiler on the development host. ARM64 compilation, PRoot execution and on-device editor behavior require device validation; the catalog does not claim a verified one-click ARM64 installer.

## Extending coverage

`LanguageIntelligenceCoverageTest` checks every built-in filename/extension against an actual server registration or explicit editing support/limitations. `FileIconRegistryCoverageTest` checks file artwork for those registrations. These checks run during development, not on application startup. Custom language/server declarations remain supported, but their external tools are supplied and configured separately.
