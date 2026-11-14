# Agent edit persistence

Reference: VS Code commit `e646868227c3e3a9e3f24277c8c7220be42c0be7`.

- [`chatEditingModifiedDocumentEntry.acceptAgentEdits`](https://github.com/microsoft/vscode/blob/e646868227c3e3a9e3f24277c8c7220be42c0be7/src/vs/workbench/contrib/chat/browser/chatEditing/chatEditingModifiedDocumentEntry.ts#L244) saves after the final edit, with `SaveReason.AUTO` and `skipSaveParticipants`.
- The same entry implements explicit accept/reject separately from persistence. [`chatEditingSession`](https://github.com/microsoft/vscode/blob/e646868227c3e3a9e3f24277c8c7220be42c0be7/src/vs/workbench/contrib/chat/browser/chatEditing/chatEditingSession.ts#L725) saves an affected entry before external edits.

Droide follows the completed-edit boundary. Ordinary write/edit, completed source-backed live writes, successful patch batches, transparent LSP edits and completed live undo transitions request explicit persistence. `replaceText` and `replaceSourceText` themselves remain buffer-only primitives; rollback and cancellation cleanup never initiate Save. New/unopened files continue to use existing disk write paths. CLI shell tools already write disk and retain their existing mirror/conflict checks.

`WorkspaceDocumentSaveRequest` names an exact document instance, revision and change version. Automatic requests also carry their applied before/after snapshots. Pre-existing unsaved user text is preserved, requiring an explicitly reviewed save. Ordinary edit approval does not silently save such drafts. `save_files` accepts 1..32 explicit path/version tuples under existing edit permission and BUILD-mode checks; there is no workspace-wide Save All tool.

The retained editor authority uses `EditorSaveCoordinator`. Its mutex now belongs to `EditorWorkspaceState`, so screen/shortcut and agent saves serialize across coordinator instances. Each save captures the target native editor as well as retained state, validates identity/version, and uses the existing disk CAS and publication receipt. An already clean buffer also verifies its saved disk version before a no-op Save can report success; external replacement or deletion is preserved and reported. For agent/scoped saves, a short publication fence rechecks editor ownership, document version, caller cancellation and permissions immediately around the atomic rename. Encoding, staging and hashing remain outside that editor turn. Bridge rebinding cannot redirect an admitted save into a replacement editor.

A successful edit is committed to Undo history before persistence. Save failures do not reapply or roll back that edit. Multi-file saves make one bounded pass and report affected paths together. Later typing remains dirty, and later writes/closures cannot be mistaken for the original expected version. Optional LSP notification failure alone does not invalidate a successful local save; actual synchronization failure is reported separately.

Unresolved agent saves are retained on the editor owner. New agent command launches and disk-mutating IDE operations check this state; status, input and cancellation for existing command sessions remain usable. Explicit Save or the existing user Save clears resolved pending work. This is an editor readiness check, not a sandbox for arbitrary external processes. Terminal-only external CLI tools keep their existing disk behavior.

No controls, layout, branding or animation are changed. No Dropbox upload is authorized for this iteration.

Validation evidence is recorded separately with exact source hashes. Host tests do not establish APK, device, SAF-provider or OEM behavior.
