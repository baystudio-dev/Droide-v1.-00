# Participating process/writer observations, with explicit unknown coverage

This opt-in increment observes existing authorities. It creates no parallel process registry and never probes a PID, process name, liveness getter, exit code or descriptor table.

## Existing ownership sources

- `ExportWriterAdmission.observe(root)` reports the existing overlapping ordinary writer counts, managed registrations and active capture labels. Opaque version tokens change on each registration/count/capture transition, including same-label and empty/nonempty ABA. Unlike advisory snapshotBlockers(), nested counts are retained. Managed participant callbacks are never invoked by observation.
- `WorkspaceProcessWriteAdmission.observe(root)` reports the existing process tickets and unknown factory roots. Each existing ticket now retains its already-known canonical root string alongside its unchanged cleanup receipt. No root is inferred from a process handle or PID. The recorded canonical path is not an inode/descendant proof; coordinator directory checks and explicit coverage unknowns remain necessary. The in-flight factory interval before ticket publication is still represented by the existing ExportWriterAdmission writer ticket; it is never interpreted as closed.
- `ExitHostedProcessOwners.observe()` reports application-wide retained handles and pending/failed cleanup/completion receipts. The ledger has no workspace-root mapping, so that limitation remains explicit. Natural child exit does not erase pending/failed cleanup. Same-handle re-retain, completion, stop registration/outcome and removal invalidate observation tokens.

Snapshots copy immutable summaries under each ledger's existing monitor. No process properties, owner callbacks, IO or another ledger lock run under those monitors. The combined collector revalidates every ledger after all captures. Tokens are process-memory observations, not persisted locks. Global ledger tokens conservatively invalidate even on unrelated-root changes; retry is not guaranteed to succeed while other work is changing.

## Actual integration and result semantics

The existing coordinator captures `CatalogProcessObservation` for the active runtime root while retaining its editor/runtime/root/application-epoch binding. `CatalogEditorReview` revalidates owner and editor observations around the actual catalog collector. Editor validation is refreshed after the last suspending owner/root check. A separate required cheap coordinator identity check follows the final editor validator (runtime/epoch/handoff on Main, without root IO); non-suspending process ledger validations run last. These checks close the tested callback gaps without claiming a cross-owner atomic snapshot.

Ordinary/managed writers and active captures produce PARTICIPATING_WRITERS. Registered handles or retained cleanup receipts produce RETAINED_PROCESS_OWNERS. They do not produce LIVE_PROCESS_OWNERS, because this collector never observes OS liveness. The legacy liveOwners field remains for separately supplied live observations.

Every production process observation includes UNKNOWN_PROCESS_COVERAGE, UNKNOWN_DESCENDANT_OWNERSHIP and UNKNOWN_DESCRIPTOR_OWNERSHIP causes, even with empty participating ledgers. This records the incomplete launch inventory, missing Exit root mapping and unestablished arbitrary/inherited descriptor closure. Missing producers on lower-level calls still default to unavailable. Neither an empty ledger nor a child-exit receipt can become release permission.

No stop, await-exit, cleanup confirmation, stale-process reconciliation, capture reservation, managed quiescence, file deletion, accounting credit, default-store activation or visual UI change is performed by review. Existing lifecycle tests may exercise their own isolated fixture cleanup; that is not part of the production observer.

## Primary reference comparison

The preserved VS Code `utilityProcess.ts` at commit e13716ce975fc4597cb8c2228471ac4783bf4529 separates owned process lifecycle events (spawn, exit, crash and termination) and keeps a process registry for its own utility processes. Its lifecycle ownership is a comparison for using registered handles, not permission to infer arbitrary descendant/descriptor closure. The earlier pinned working-copy and lifecycle references remain primary. Claude Code/Codex session models are supplementary references, not replacements for Droide's exact local cleanup receipts. No upstream cleanup implementation was copied.

## Verification limits

New tests use actual writer/admission/Exit ledgers and actual catalog collection. They cover nested-count/empty ABA, managed callbacks not invoked, active captures left intact, known ticket root mapping, factory-in-flight/unknown creation, throwing process properties never called, same-handle re-retain, completion/removal, pending/failed cleanup retained after exit, cancellation without cleanup, immutable summaries, distinct retained-versus-live semantics and changes during final owner validation. Existing lifecycle/admission/export and prior catalog/editor cohorts are rerun from source.

This is targeted host testing with the modified coordinator compiled, not full application startup, Android/device/NDK validation or a global writer-admission proof. Arbitrary outside writers can change after any observation. Physical release, complete launch/root/descriptor coverage, held admission across all relevant writers, durable retirement/accounting and default migration remain unavailable.

The subsequent bounded native terminal producer is described in CATALOG_NATIVE_OBSERVATION.md. It inventories existing manager/retained-host owners and published receipt state without certifying native group, descriptor or complete launch coverage.
