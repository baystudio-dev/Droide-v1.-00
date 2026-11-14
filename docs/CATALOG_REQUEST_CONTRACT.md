# Dormant durable-request foundation

CatalogRequestContract is a pure value/codec contract, not a runtime ledger. No caller uses it to admit, retry, acknowledge or retire an operation. It neither opens SQLite nor changes schema, files, custody, accounting, default-store selection or editor/process ownership. Its constructors validate data shape, not trusted provenance or publication proof.

Included contracts:
- Canonical bounded token and catalog-only REPLACE/projects.json descriptor encoding. Original sequence/owner/before/proposal remain frozen; later allocation counters do not rewrite a retry fingerprint.
- Immutable bounded candidate window with contiguous issued rows above a rejection floor, at most one unresolved latest delivery, and no automatic sequence rollover.
- Lookup and acknowledgement classification only. At/below the floor, acknowledgement returns unavailable, never exact success. No method mutates acknowledgement, advances the floor or issues a token.
- Checked conservative metadata reservation for descriptor, delivery slot, pending, completed and acknowledgement envelopes. The helper only validates and sums caller-supplied bounds; it does not derive safe bounds from descriptors or actual serialization. A future producer must prove those bounds first. This is logical capacity, not physical disk availability or retained-payload credit. Existing custody limits remain independent.

The reviewed design is preserved in the checkpoint references. Activation still depends on a real durable caller acknowledgement owner, legacy admission freeze/reconciliation, migration authority and incomplete-preparation handling. The class is intentionally not wired into JournaledProjectStore.saveCatalog. Unknown outcome does not mean safe to repeat as new.

Validation distinguishes pure contract tests from real existing schema2 journal/catalog fixtures. Injected exceptions before/after admission, after staged preparation and after completion demonstrate current lost-return boundaries with fresh updater objects. They are not actual power-loss tests, schema3 integration, or proof of automatic early-phase recovery. Descriptor hash alone cannot reconstruct a proposal; current pre-staged resume remains refused. Unknown legacy UUID still refuses without recreating an operation. No file is deleted outside fresh synthetic fixture roots.

VS Code references are lifecycle analogies only: pinned workingCopyFileService participants/will/fail/did ordering and workingCopyBackupTracker's separate content version/pending operation state. They do not establish durable exactly-once or receipt eviction semantics. No such guarantee is claimed from VS Code, Claude Code or Codex.

Later integration note: RESTORE_MEMBERSHIP_COMMAND.md describes the separate concrete schema3 restore-only receipt ledger. It does not activate this dormant sequence/floor/retirement contract. Future sequence-based retirement must use an explicitly versioned compatible migration, not assume current schema3 implements it.
