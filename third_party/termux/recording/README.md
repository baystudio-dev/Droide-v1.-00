# Raw terminal output observation

These additive sources preserve the existing owned PTY and checked-resize engines. Historical source snapshots and their receipts remain unchanged.

- `owned/` derives from `../patches/src/com/termux/terminal/TerminalSession.java` and is used by the host JVM test recipe.
- `resize/` derives from `../derivatives/r63/src/com/termux/terminal/TerminalSession.java` and is the final source selected by the current source-build recipe.
- `owned-output-observer.patch` records the complete owned-session delta against the pinned upstream source archive, as verified by `tools/build_termux_host_test_jar.py`.

`setOutputObserver(BiConsumer<byte[], Integer>)` receives each dequeued PTY chunk once, before bounded emulator parsing, escape processing, or scrollback eviction. The callback runs on the owning main Looper. It must copy reused bytes and must not perform disk I/O or block. Exceptions detach the observer without disrupting terminal input or rendering. This API observes future output; it cannot reconstruct output before recording started or data lost by the process reader.

The app recorder copies into a bounded asynchronous queue and reports incomplete capture on queue overflow, storage failure, or its per-recording byte limit. It records output only; a shell may echo input into that output. Explicit user consent is required because raw logs are not redacted.

The current source recipe in `tools/terminal-engine-v2.json` pins both inputs and Java 8 compiler outputs. Only `TerminalSession.class` and `TerminalSession$MainThreadHandler.class` change in each stage. JNI ABI, native code, process-owner classes, emulator parser, and third-party dependencies remain unchanged. Use a fresh engine output directory when rebuilding after a source change; old receipts intentionally fail verification.

Original Termux attribution and license scope continue to apply; see `../README.md` and `../UPSTREAM_PROVENANCE.json`.
