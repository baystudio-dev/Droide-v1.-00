# Exit recovery and shutdown

The reported `Editor recovery is not ready` failure came from an unconditional readiness veto in `EditorExitCheckpoint.prepare()`. A failed or interrupted restore could leave the editor uninitialized indefinitely, including when there were no new unsaved edits. Retrying Exit did not change that state.

A separate sequencing issue treated the 30-second observation budget as a failure. The real operation continued, but shutdown would not advance when it finished until the user pressed Retry. The Activity also finished before agent history, terminals and other background owners had finished shutting down.

## Reference behavior

- Acode `src/lib/commands.js`, commit `568286d5186ebf37334a86d24a93eb36c1f6daac`, implements Exit by calling `navigator.app.exitApp()`. Cordova Android `rel/15.1.0` routes this through `CoreAndroid.exitApp()` to `CordovaActivity.onMessage("exit")`, which calls `finish()`. This closes an Activity; it is not evidence of OS process-tree termination.
- VS Code commit `e646868227c3e3a9e3f24277c8c7220be42c0be7`, `workingCopyBackupTracker.ts`, preserves unrestored backups when closing a workspace/folder with no modified working copies. Modified working copies require successful preservation; already-current backups are reused by content version.
- VS Code `lifecycleService.ts` joins shutdown participants and reports slow operations separately from actual failures. `nativeTextFileService.ts` waits for pending saves.

Source URLs and hashes are retained in the task's `references/REFERENCES.json`. These are behavioral references; Electron/Cordova lifecycle APIs are not transplanted into Android Compose.

## Droide behavior

1. New work admission closes immediately. Independent export/transfer/maintenance shutdown starts concurrently.
2. An accepted file Save is joined before freezing its document and disk baseline. Original editor/native ownership remains required.
3. A clean editor whose recovery is uninitialized leaves the old recovery files byte-for-byte untouched, even if they cannot currently be decrypted. New dirty text is merged with the older recovery: live edits remain current, distinct older drafts retain their conflict slot, and untouched deferred blobs keep their exact ownership and original disk baselines. The existing single-conflict limit is respected; multiple distinct older copies are never silently discarded.
4. In-flight store IO drains before taking exclusive checkpoint ownership. Restore callbacks cannot mutate a sealed editor model.
5. After editor and chat drafts are preserved, independent runtime shutdown and both agent history checkpoints begin concurrently. History must be acknowledged before protocol/terminal ownership is retired.
6. Final native release/capture is still mandatory. An identical final snapshot reuses the already verified checkpoint; a final keyboard/native delta requires another durable write and readback.
7. Runtime work, terminal/protocol processes, scheduled workers, foreground service and CPU leases stop before `finishAndRemoveTask()` is called directly. There is no minimum display duration, polling delay, or dependency on a resumed Compose frame. Exact native destruction receipts still complete the lifecycle bookkeeping.
8. A slow operation remains the same running operation and automatically advances on completion. Real persistence/cleanup failures remain visible and retryable; successful stages are never replayed.

No `killProcess`, process-name matching, discarded drafts, or assumed child-process termination is used. Android may retain an idle cached process after the Activity and services stop; that is distinct from continuing agent/terminal work.

## Validation boundaries

Targeted Kotlin/JVM compilation and deterministic host tests cover encrypted checkpoint/recovery preservation, late native capture, pending Save/IO, shutdown concurrency, real host child processes, and Application/Activity/Service lifecycle ordering. See `VALIDATION.json` in the accompanying task evidence for exact executed suites and outcomes.

No full Gradle/APK/native application build, physical-device exit latency measurement, Android OS process-tree observation, or OEM-specific validation was performed. Storage failures and genuinely unconfirmed native cleanup cannot honestly be acknowledged as successful Exit.
