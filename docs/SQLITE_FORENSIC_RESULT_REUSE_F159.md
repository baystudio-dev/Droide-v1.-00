# SQLite forensic result reuse

The existing **Build unverified forensic candidate** action now consumes complete retained evidence before allocating another attempt. Eight successful Preserve actions no longer prevent the first Build. A completed candidate whose return was lost can be returned again, including after a new authority instance is constructed and when the attempt limits are full.

## Selection and validation

Build holds the existing cooperating metadata lease for selection, capture if needed, and candidate rendering/reopening. The source/working writer fence, application-current checks, and cancellation gates remain in the existing action/host path. No SQLite engine opens the authoritative database on this path.

1. Pin and hash the currently observed original database/journal pair. Refuse WAL/SHM, nonordinary inputs, unsupported aliases, or observed identity/version changes.
2. Enumerate retained attempts with anchored, no-follow directory operations. Validate exact v1 receipt bytes against the current workspace binding, authority identity, and original hashes. Validate private, single-link captured files and their hashes. A malformed or partial historical attempt is ineligible but remains untouched. Actual I/O, identity races, lease loss, and cancellation fail the invocation.
3. Search every eligible capture for a complete candidate before allocating anything. Revalidate the full journal parser, including page numbers, duplicate/lock-byte pages, checksums, and supported padding/tail rules. Compare the candidate's journal-overlay pages and all untouched database-prefix pages against the captured inputs. A modified candidate with a correspondingly modified report hash is not enough to pass.
4. Reconfirm copied-file, receipt, candidate, and report durability through read-only file force and anchored directory force. Check identities, versions, complete hashes, and sidecar absence again across completion boundaries. Check the currently observed original pair before and after completing the consumer's final output checks.
5. If no completed result exists, use an eligible capture with room. If none has room, a new independent capture is allowed only while raw capture capacity remains. This does not repair, replace, or remove any old attempt.

The caps remain eight raw attempts, four candidate attempts per raw attempt, and 64 MiB per bounded input/output. Invalid or interrupted attempts still consume their slots. Namespace inspection is also bounded. A full collection with no usable completed result fails clearly; no ninth raw attempt or fifth candidate within one capture is admitted.

## Meaning of the result

The existing v1 receipt and report remain unchanged. There is no new reuse seal or persistent ownership credential. v1 records never contained historical source/copied-file inode proofs, and reopening does not invent them. A same-byte replacement that predates a new call can be accepted as a new current observation after all checks pass. A replacement or mutation observed during validation is rejected.

The result remains **unverified speculative bytes**. Hashes and file identities do not establish database/journal coherence, committed SQLite state, external-writer exclusion, crash recovery, or fitness for activation. No original database or sidecar is overwritten, replayed, deleted, or activated. No retained attempt is cleaned up or released.

## Coverage

Focused tests exercise Build after eight Preserves and completed-result return loss. Independent tests cover repeated/restarted consumers, both capacity limits, changed originals, corrupt/partial attempts, coordinated candidate/report tampering, aliases, namespace replacements, late WAL/SHM, cancellation, lease changes, and mutation during parser rejection. The existing native-host rollback/forensic/metadata-lock/interrupted-recovery suites remain applicable. Real host/action-adapter tests cover the existing epoch, close, cancellation, and owner admission gates rather than substituting a fake recovery callback.

The tests use host SQLite and synthetic supported rollback fixtures where specified. They do not establish Android-device, APK, UI-wide, live recovery, or full-application correctness. The referenced VS Code storage lifecycle owns its close/backup/recovery operations and may replace databases; those behaviors are not authority for Droide to replay or discard retained evidence.
