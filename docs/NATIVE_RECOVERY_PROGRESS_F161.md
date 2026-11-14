# F161: truthful progress for native retained-folder recovery

## Scope

Native Agent archive recovery now reports progress through the existing streamed tool activity. This closes the long-running recovery feedback gap without a new screen, setup step, permission prompt, timer, queued worker, or background job. It does not add an editor UI, LSP route, Android build, restore-to-original-path operation, or storage cleanup.

`LocalDirectoryRecovery` emits synchronous typed observations. `AgentLocalDirectoryRecoveryProgress` projects them into the existing `AgentToolProgress.detail`, at most once per 250 milliseconds for repeated phase updates and immediately when phase changes. No log/output text is appended and no growing event queue is retained.

## Stages and their meaning

1. Reading retained folder: source entries and bytes observed so far. The total is not known.
2. Creating recovery archive: writing the private staged archive. Counts are scoped to this stage.
3. Checking recovery archive: checking that private staged archive.
4. Archive checked, preparing to save: the private TAR passed verification. Nothing has yet been claimed as published to the project. This milestone omits counters because the exporter reports source bytes here rather than TAR bytes.
5. Preparing to save archive: repository admission and destination preparation.
6. Saving archive to project: copying the TAR into the repository's private sibling stage. The TAR byte total is known here.
7. Finishing archive save: final create-only publication is being attempted. This is not a success receipt.
8. Updating project: canonical publication and the local receipt have completed; repository synchronization is still in progress.

The existing final result remains authoritative. A committed archive can have synchronization pending. Progress never says that recovery is complete, that the current destination has been reverified, or that a historical archive was newly copied. Byte counts across phases are not a single cumulative total or percent.

## Cancellation, authority and callback isolation

The captured caller, current request and effective policy are checked before and after every core progress callback, even when its Agent projection is throttled. Display callback Exceptions, including a callback's own CancellationException, are isolated. Unexpected Errors propagate. At the repository callback boundary, an Error is transported through callback isolation and then the original Error is rethrown, never reported as success. Actual caller cancellation and request revocation remain authoritative and stop the operation.

FileRepository intentionally treats ordinary progress failures as best effort. Recovery therefore converts a real non-cancellation authority failure at that specific callback boundary into a dedicated CancellationException, preserving the cause. A failed display is never converted into authority revocation. A final full authority check remains outside the catch that reports an already committed receipt. None of these callbacks run inside the bounded non-cancellable publication section, and the final publication guard remains unchanged.

If cancellation or permission changes happen after publication, the durable receipt and retained originals remain intact. Result delivery may be suppressed; the user must inspect the exact token and destination receipt before retrying. An interrupted response is not proof of rollback or permission to make another archive. If copying stops after creating a sibling stage, the existing repository deliberately preserves that stage; inspection reports UNKNOWN even if no publication attempt or seal exists.

## Outcome inspection and deferred work

For a lost response, uncertain outcome or a status question, use the existing `local_deleted_folders(action=inspect, operation, prepared_sha256, destination)` route. It reads the exact archive journal without edit authority or re-copying. COMMITTED reports historical publication; UNKNOWN requires review. Neither asserts that the destination still exists.

This change does not remove the edit approval from `archive_deleted_folder` itself. That tool remains BUILD-only, edit-classified and subject to the existing catalog, owner, source read and destination edit rules. A proposed historical skip-edit optimization was deferred because a safe unconditional archive-journal preflight would add a new default read permission prompt to every fresh recovery. No current custody permission is silently treated as authority over the sibling archive journal.

## Primary architecture reference

The supplied Code-OSS 1.143.1 source is the primary comparison. `src/vs/workbench/contrib/files/browser/fileImportExport.ts` uses cancellable progress at lines 619–635, a coalescing RunOnceWorker at line 675, and streaming progress/cancellation at lines 713–746. Droide adapts the bounded latest-progress behavior using a synchronous per-request throttle rather than creating another timer lifetime. Its existing ExportController already uses a 250 ms throttle.

`files/browser/fileActions.ts` lines 214–250 connects operation progress to the actual bulk edit and keeps failure/retry decisions explicit. `localHistory/browser/localHistoryCommands.ts` lines 257–318 consumes exact selected history and reports restoration errors truthfully. Droide intentionally preserves deleted originals and writes a separate TAR instead of replacing a working copy.

## Verification boundary

F161 evidence must exercise actual repository deletion, the native Agent/provider/tool route, intermediate streamed progress before publication, and final archive verification. Focused controls cover throttling, display failure, cancellation, stale ownership, policy revocation, lost return and read-only outcome inspection. Assertions about callbacks are checked outside callbacks because callbacks are best effort.

Host JVM/JNI/Robolectric tests are not an Android/APK build, device test, arbitrary-writer exclusion proof or physical power-loss certification. Retained descendants can still change; the archive remains an observed capture, not a frozen point-in-time snapshot.
