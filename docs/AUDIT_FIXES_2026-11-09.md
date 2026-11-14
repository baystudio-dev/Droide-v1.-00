# CLI process ownership and editor LSP lifecycle fixes — 2026-11-09

Base: `Droide-Full-Source-2026-11-09-editor-reload-recovery84-lintfix.zip`.
Base archive SHA-256: `3c8517d93a2ed0ced46eb20ac1ac66aa0a9a7748697493f54b3fcf502d1e8950`.

## Changes

### Completed CLI handles no longer remain strongly owned for the whole session

`SessionHostedProcessOwner` now observes each process's completion receipt without blocking a native thread. A natural completion releases the owner's strong reference only after exit and workspace-write completion are confirmed. Weak identity keys deduplicate late handoffs without retaining process streams or callbacks. Reference equality is required because distinct process objects may compare equal by value.

Natural exit does not forcibly close stdout/stderr: readers may still be draining output. When the session stops, surviving weak handles are promoted back into explicit cleanup so their stream-close receipts are still checked. Confirmed explicit cleanup releases its strong record and retains only a weak deduplication key. Live handles, failed receipts, blocked close requests, late process births, and unsuccessful retries remain owned. Successful receipts are published outside the monitor before cancelling the natural observer.

This is deliberately more conservative than disposing all process resources immediately on OS exit. The local host's output-drain and workspace-write receipts still govern completion.

### Editor mode changes consistently synchronize LSP ownership

`EditorLspDocumentSync.kt` centralizes the immutable Open/Change/Close actions used by persisted mutations and live editor effects:

| Before | After | Notification |
| --- | --- | --- |
| Full intelligence | Full intelligence | Change |
| Full intelligence | Large-file/disabled | Close |
| Large-file/disabled | Full intelligence | Open |
| Disabled | Disabled | None |

Identical persisted writes remain quiet. Reloads are tracked using both revision and typing version, so a disk reload is not skipped merely because no new keystroke occurred. An open request is treated as potentially registered before it can suspend; cancellation halfway through opening must not prevent a subsequent Close. Changing tabs or leaving the editor pane does not itself close workspace documents. Existing save acknowledgement, dirty-buffer conflict, reload-generation, and deferred recovery guards remain in place.

## Verification actually performed

31 focused tests passed:

- 19 CLI ownership/shutdown tests: six new retention tests plus the existing independent-review, launch-starvation, receipt/thread-retention, and shutdown suites. Includes 512 completed handles, real garbage collection, identity deduplication, single IO lane, late births, blocked/throwing close, and retry after failed cleanup. The production owner and cleanup implementation are compiled whole; RPC/host/custody boundaries are isolated by a test harness. Some shutdown fixtures launch real host OS processes.
- 12 editor tests: five new background-document mode-transition tests, three new Compose effect tests under Robolectric SDK 35, and four existing `DeferredEditorReloadTest` tests. Covers line and byte limits, shrink/reopen, ordinary and identical writes, dirty-buffer rejection, unchanged typing version after reload, cancelled initial open, and deferred recovery after failure/cancellation.
- Focused Kotlin compilation of the changed editor files, including the whole `EditorPane.kt`, and their current dependency closure succeeded. There are 44 complete production source files plus an exact extracted current LSP contract/data prefix; the live `ProfessionalLspManager` backend is not instantiated by these tests. Unchanged dependencies outside that closure come from the existing host baseline JAR. This is not a fresh whole-application build.
- Final production/test input hashes match the files actually compiled and tested. The supplied original archive and its extraction are preserved.
- Negative control: running the same five background-mode tests against the original adapter produces exactly one failure: the line-limit write expects Close but receives no event. The corrected adapter passes all five. The original owner also fails the initial retention/collection probes; the final owner passes the complete 19-test selection.

No APK, native/full build, actual Android-device UI test, real language-server integration test, or reboot test was performed. These host checks do not establish Android performance or power-loss durability. Existing unrelated compiler warnings remain; this change does not claim a clean whole-project lint/build.

Evidence includes the successful runs and earlier negative/failed attempts. An initial Compose test fixture lacked its frame clock; that fixture was fixed and the complete 12-test selection then passed. The main compile attempted before the cancellation fix was superseded by a hash-stable compile of the final source.

## Upstream reference

VS Code checkout `e646868227c3e3a9e3f24277c8c7220be42c0be7`:

- `src/vs/platform/terminal/node/ptyService.ts`: process exit disposes/removes terminal ownership. Droide additionally requires its own cleanup receipts and output-drain rules.
- `src/vs/workbench/contrib/mcp/node/mcpStdioStateHandler.ts`: request-stop and confirmed completion are distinct lifecycle stages.
- `src/vs/workbench/services/textfile/common/textFileEditorModel.ts`: preserve model versions across asynchronous reads and reject stale publication. Droide's reload/recovery guards remain intact.

## Next verification on a configured Android environment

1. Run the normal project unit/lint checks, then a separately authorized full build.
2. Exercise a long external-agent session and stop it while terminals finish or continue draining output; verify no lost output and no unconfirmed cleanup acknowledged as complete.
3. With a real LSP server, grow and shrink an active and a background document across both file-size limits. Confirm diagnostics disappear on Close and recover from current text on Open.
4. Exercise reload with unsaved/deferred recovery text and interrupted IO; verify the recovery remains available until a replacement is accepted.
