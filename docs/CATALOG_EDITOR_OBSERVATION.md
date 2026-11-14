# Catalog editor observation: explicit ownership, no release authority

This increment extends the existing document authority contract instead of introducing another editor registry. `WorkspaceDocumentAuthority.observeAllDocuments()` defaults to unavailable. The bridge binds each observation to its exact delegate/generation and rechecks ownership around the adapter validator. An unbound or legacy adapter cannot supply clean evidence through an empty snapshot fallback.

The production editor adapter now shares a capture/validation implementation with path-scoped recovery. All-document capture includes every live document and retained recovery path. It records immutable snapshots, document instance identities, saved disk versions, native mutation bindings, individual pending-save/buffer/recovery/sync/notification flags and the existing editor lifecycle/native/recovery authority generations. It reads neither deferred recovery blobs nor native input. Uninitialized recovery is unavailable. Dirty work remains dirty; no save, native capture, acknowledgement or handoff is initiated.

`WorkspaceHandoffGate` exposes a read-only generation observation. Publication generation advances for every owner publication, including while clear, so publication and reserve/clear ABA changes cannot look unchanged. Existing retention and notice ownership rules remain in place.

`WorkspaceCoordinator.observeCatalogEditor()` binds the existing runtime reference, active project ID, workspace directory identity, application-work epoch and handoff observation. The shared `CatalogEditorOwnerValidator` checks the Main-owned state, checks the root on IO, then checks Main-owned state again. It performs no blocking IO on Main and holds no bridge monitor or transition mutex across collection. This is detection of observed changes, not exclusion of future changes.

`CatalogEditorReview` validates both owners around the real catalog collector and maps editor causes into separate preflight blockers. The coordinator's opt-in `reviewCatalogRelease()` route uses this producer. The subsequent participating process producer is described in CATALOG_PROCESS_OBSERVATION.md. It retains explicit unknown coverage/descendant/descriptor blockers, so this route cannot produce a clean overall review merely because the editor and registered ledgers are empty. No default store activation, visual UI change, deletion, retirement, receipt removal, capacity credit, shutdown or process cancellation is implemented.

## Primary reference comparison

Verified Microsoft VS Code commit `e13716ce975fc4597cb8c2228471ac4783bf4529`:

- `workingCopyService.ts` tracks the full registered working-copy set with identity-aware registration/disposal and dirty/content/save lifecycle events. This informed using the existing all-document registry, not querying the catalog pathname.
- `workingCopy.ts` distinguishes dirty/modified state and backup/save responsibilities. This informed retaining pending work and content versions rather than equating a saved flag with global cleanliness.
- `workingCopyBackupTracker.ts` separately tracks content versions and pending backup operations. Droide therefore compares the individual pending categories and retained recovery ownership, not only a merged path set.
- `lifecycle.ts` distinguishes pre-shutdown veto/error from joined shutdown work. Observation is neither a drain nor a successful shutdown.

Pinned source copies, URLs, SHA-256 hashes and MIT license are under the checkpoint references directory. These are design comparisons, not copied implementations or a claim of VS Code equivalence. Claude Code/Codex remain supplementary agent-session references from preceding work; their session capabilities are not substitutes for editor ownership or descriptor closure.

## Validation and remaining scope

Fresh host compilation includes the modified coordinator, contract/bridge, gate, shared review validator and real adapter, plus actual EditorWorkspaceState/EditorProjectHandoff dependencies. New tests exercise the real adapter, real overlapping save jobs, native-buffer pending capture, initialization/unavailable states, exact pending-category transitions, native/rebind/handoff ABA, immutable collections, cancellation, real catalog review and the same owner/root validator the coordinator invokes. Existing adapter/handoff and all prior collector/preflight/custody tests remain in the cohort.

This is not a full Android coordinator/service startup or visual/device test. Coordinator wiring is compiled and its shared validation logic is exercised, but the complete application runtime lifecycle is not launched. Catalog collection and editor observations can still become stale after their final checks. No global writer admission, descendant/descriptor proof, durable release/accounting lifecycle or default activation is provided; the subsequent process diagnostics do not change those limits.

The subsequent bounded native terminal producer is described in CATALOG_NATIVE_OBSERVATION.md. It inventories existing manager/retained-host owners and published receipt state without certifying native group, descriptor or complete launch coverage.
