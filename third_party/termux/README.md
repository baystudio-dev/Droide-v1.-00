# Terminal components

The terminal uses the pinned upstream terminal-emulator and terminal-view components. Matching source archives, original license text and source provenance are retained here.

- `UPSTREAM_PROVENANCE.json` pins upstream artifacts and source identities.
- `patches/`, `derivatives/` and `native/` contain the corresponding modified sources.
- `tools/terminal-engine-v2.json` lists the active Java sources, compiler inputs and expected compiled class hashes.
- `tools/terminal_engine_v2.py` builds the Java overlay twice and verifies identical output.
- `tools/verify_source_release.py` verifies current native registration and the packaged APK.

The build compiles JNI separately. Host tests do not establish Android PTY or lifecycle behavior; test the intended variant on supported devices. See [Building](../../docs/BUILDING.md) and [Testing](../../docs/TESTING.md).

Upstream termux-app uses GPLv3 by default, with an Apache-2.0 Terminal Emulator exception for these components. Preserve the complete upstream license statement, matching source and attribution. Original artifact checksums remain unchanged.
