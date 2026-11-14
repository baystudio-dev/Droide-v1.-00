# Trusted catalog preflight collector (opt-in, read-only)

`ProjectCatalogUpdate.collectReleaseReview()` now collects a snapshot from the explicitly enrolled catalog authority. It does not activate the journaled store or connect a UI route. `reviewRelease()` evaluates a previous selection against a fresh collected snapshot. Missing editor/process evidence defaults to unavailable and therefore blocks review. Supplied subsystem observations still do not certify writer exclusion.

## Provenance and consistency

- The journal's `collectRetentionReview()` reads raw retention entries and decoded intents together in one read-only SQLite transaction. Existing `readRetention()` verifies bounded durable membership, exact codec re-encoding, completed phase, IDs, and retained charges. `readStoredIntent()` verifies enrollment metadata, pending membership and known custody.
- Every completed retained file is inspected against its exact recorded identity/content/mode/size. The whole bounded set is rechecked after the observation callback, along with history and policy. Nothing is adopted from a pathname alone.
- The catalog collector uses the existing per-authority coroutine mutex. It validates bootstrap membership before/after journal captures, hashes the complete enrollment into the authority binding, and requires the persisted policy to equal the configured update policy. It checks application/root binding and catalog identity/content around two independent receipt/custody captures.
- Bootstrap inspections are outside the journal SQLite transaction. No nested public serialized call or transaction acquisition occurs in production collector code. Checkpoint callbacks are test hooks and must not re-enter the same serialized authority.
- The returned Snapshot/Record collections use the existing owned unmodifiable copies. This is an in-memory observation only. An external writer can still change state after the final check.

## Failure and scope

Absent retention, malformed receipts, unavailable files, changed identities, policy mismatches and cancellation do not return a trusted snapshot. Existing validation exceptions propagate; cancellation is not converted into success. Pending operations can be captured but produce a preflight blocker. The collector never enables retention or creates a missing authority.

The evaluator remains usable with synthetic DTOs for pure tests. Only fields obtained through this collector have the journal's verified raw/decoded provenance; arbitrary internal caller-created DTOs remain untrusted. No result authorizes release, retirement, capacity credit or receipt removal.

Real journal/catalog fixture tests cover exact raw/durable digest agreement, missing producer evidence, no-enable behavior, persisted-policy mismatch, pending operation, changed catalog/history, late custody mutation and inode replacement, catalog change during the final custody pass, unknown custody preservation, database replacement, cancellation, full-capacity receipt retry, and forged codec/accounting/path data even after recalculating the membership digest. Targeted Kotlin/JUnit/Robolectric execution is not an Android device or full application build, global admission protocol, durable release or live-provider validation.

The later CATALOG_PROCESS_OBSERVATION.md adds participating-ledger diagnostics only. Remaining integration: complete process launch/root/descendant/descriptor coverage; workspace admission shared by all writers, descendants and descriptors; durable retirement/accounting and bounded retry lifecycle; default-store migration and product routes. These are not supplied or implied by a clean observation.

The subsequent bounded native terminal producer is described in CATALOG_NATIVE_OBSERVATION.md. It inventories existing manager/retained-host owners and published receipt state without certifying native group, descriptor or complete launch coverage.
