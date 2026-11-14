# Explicit accepted no-change result expiry

The selected manager/store API expireAcceptedNoChange(token, exactOutcome) permits one narrowly scoped metadata lifecycle operation in existing schema3. It is never automatically invoked by acknowledgement, command admission, capacity errors, startup or UI code.

Eligibility is the exact fixed-recipient acknowledged NO_CHANGE receipt, with no operation UUID or frozen intent and a recomputed canonical outcome. The journal validates authority and full command/operation membership, deletes only the exact command row, updates command count/digest in the same transaction, and validates final membership before commit. Canonical operation tables/classifications/custody and catalog bytes are untouched. An operation-backed command is categorically ineligible, even when acknowledged.

EXPIRED is returned only after the transaction succeeds. Unknown/already-expired UUID returns OUTCOME_UNAVAILABLE; this does not prove it was previously issued or expired. Missing acknowledgement refuses with unknown-or-expired wording. No lookup or retry recreates a command. New admission continues minting UUID.randomUUID internally: uniqueness is probabilistic, not a deterministic rejection-floor guarantee. No sequence order, new schema, tombstone history or eternal no-reuse promise is introduced.

Explicit expiry releases only the removed command slot and its logical envelope charge. Repeated no-change/accept/expire cycles can therefore avoid filling the command window. Canonical128/retained-byte/metadata limits remain exactly as before. No payload is released, no retention credit is granted, and SQLite page reuse does not promise immediate disk-space reclamation. There is no VACUUM or physical cleanup.

Historical project-root liveness is not required for metadata-only expiry; enrolled journal/application authority remains required. Expiry does not adopt historical catalog state, select/open a project or clear a manager uncertainty guard. Pending/ordinary operation evidence must remain valid and unchanged.

Validation uses fresh synthetic roots and actual manager, selected store and native-mode host SQLite. Fault injection/recreation is not device power-loss testing. Existing UI callers/default routing/native code remain unchanged. The wider schema4 sequence/floor design is preserved only as an unused alternative, not activated by this increment.

A later separately enabled schema4 operation-envelope compaction API is documented in MEMBERSHIP_OPERATION_COMPACTION.md. This no-change API still categorically refuses operation-backed commands; it never converts their mapping to legacy or removes their canonical history.
