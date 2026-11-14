# Completed catalog custody at the result boundary

This increment hardens the explicitly selected journaled catalog path. It does not select that path by default, alter the editor, migrate existing catalogs, release history, or increase the existing 128-entry limit.

## Production change

`CanonicalFileOperationJournal.requireCompletedCustody` previously inspected each completed entry once. A later checkpoint could change an already checked retained file, introduce an unowned custody entry, or replace an inode before the method returned. It now makes one further callback-free verification of the complete bounded custody set and revalidates the exact recovery metadata and known custody membership.

`ProjectCatalogUpdate.readReadyCatalog` rechecks the current catalog's exact identity, mode, length and contents after custody validation, followed by the authority/history and cancellation checks. `readPublished`, used by new and resumed completions, validates the complete retained history rather than only the requested completed operation. It also rechecks the current catalog after custody validation.

The durable completion receipt remains historical evidence. A failed result after its commit keeps that receipt, retained bytes and the published catalog. A retry inspects the existing receipt and does not repeat the publication or append another completion. A retry at full capacity can succeed for that existing receipt; a new operation still fails admission without deleting old history.

These are bounded observations, not atomic exclusion of arbitrary writers. No dirty buffer state is acknowledged or changed. Existing editor/process ownership gates and default catalog selection remain unchanged. Hashing roughly doubles for a whole-history check and remains linear within existing bounds; mobile performance is not measured.

## Code OSS reference

The reference is the supplied F114 archive's Code OSS commit `e646868227c3e3a9e3f24277c8c7220be42c0be7`:

- `src/vs/workbench/services/workingCopy/common/workingCopyHistoryService.ts`, lines 424–472: serialize storing, check cancellation and track the stored model version. SHA-256 `ae50f040160e01ab14ccfc2e82ea8169dbd8114e99ca656727e185d261741eee`.
- `src/vs/workbench/services/workingCopy/common/workingCopyBackupTracker.ts`, lines 169–207: cancellation and modification checks after obtaining a backup, before recording it. SHA-256 `ba1bc7efab58a623107a78080bce1c69a6a397a46a36be90278ad357362e2486`.

The retained hashes match the F114 reference manifest. These lifecycle patterns inform current-state validation; no upstream code was copied. Code OSS history pruning at lines 475–505 does not authorize deleting Droide retained inodes, which may still be held by writers.

## Gates still open

- A complete held writer/editor/descriptor-closure barrier before any release, including pending saves and overlapping dirty document generations.
- Exact-version release approval, durable release intent/receipt, interrupted-release reconciliation and accounting that never forgets an unknown outcome.
- A complete bounded completion-history lifecycle and retained-catalog destination/resolution workflow before default journaled catalog activation.
- Physical Android, process death/reboot/power loss, device performance and full application packaging validation.

Export, acknowledgement, receipt presence and successful read-only custody review are not release permission. Mirror history, canonical catalog history and SQLite forensic captures are separate authorities and capacity domains.
