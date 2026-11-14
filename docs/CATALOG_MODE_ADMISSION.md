# Cooperative catalog mode-choice admission

## Exact current reasons and separable scope

Default metadata publication has before/after mode checks, but checks are not a held transition boundary. FileProjectStore catalog writes hold a per-canonical-app coroutine mutex; active-ID writes currently do not. ProjectCatalogAdmission publishes explicit startup/update choices under a separate storage mutex. A real writer admitted before enrollment exists can pause after its final check; real enrollment and update choice can then publish; the writer can replace catalog or active-ID bytes afterward and only report uncertainty. Two exact fixtures reproduce this against accepted1017 production.

This is app-owned metadata coordination, not physical workspace retirement. It does not require knowing every process with an inherited descriptor. The pinned VS Code WorkingCopyFileService.delete coordinates registered working copies and provider deletion, including soft revert; it does not certify arbitrary OS descriptor closure. Droide cannot silently adopt its soft-discard semantics or turn namespace unlink into space reclamation. Physical release's current stronger guarantee remains unchanged, while this narrower mode boundary can advance independently.

## Implemented minimum contract

Reuse the existing canonical-app publication mutex. Both legacy catalog and active-ID FileProjectStore publications hold it through post-publication admission checks. Explicit choice attempts acquire that same mutex non-waitingly with an exact token before validation or creating choice staging; busy attempts fail and retain any already-created enrollment. Existing storage publication mutex remains nested inside app metadata admission. Hold until final identity/current-catalog validation and release in finally on all outcomes, including cancellation/fatal failures. Reads do not reacquire this mutex; no callback runs under the lock-map monitor.

Only explicit update choice is covered. Other startup choice routes remain unchanged until their own reachable conflict and call graph are reviewed. New properly mode-guarded default writers queued behind a successful choice subsequently fail their existing mode check. Plain/custom FileProjectStore constructors retain their current mode-authorization contract: participation in mutual exclusion is not implicit permission checking or activation. No new global registry or stale clean flag.

Do not wrap JournaledProjectStore.writing in this increment: its codec.saveActiveId would reenter a nonreentrant mutex. Selected catalog publication remains under its current authority mutex and cannot claim this as complete selected-mode writer exclusion. Any future unified selected-store/migration transaction must pass an exact owned lease to internal helpers, never a bypass boolean.

## Deliberately unresolved

Enrollment itself precedes explicit choice and can make an already admitted default writer's eventual result uncertain; preserve that evidence and existing uncertainty classification. No automatic retry, startup migration, default constructor change, receipt release/accounting credit, editor save/discard, process stop, native/UI change or actual user-data deletion.

Durable retirement still needs a real delivery-ack owner, persisted request-sequence issuance/floor, completion-envelope budget and crash-safe custody retirement before credit. These are distinct from cooperative metadata admission. A narrower physical-retirement product guarantee would require an explicit semantic decision, not more observational flags.

## Tests

Preserved RED2 establishes reachable catalog/active crossing with actual default guard, enrollment and update choice. GREEN must refuse before pending/choice publication while old writer is held. Verify post-drain explicit retry, choice-first guarded-write refusal, the explicit update route, two store instances, unrelated app independence, cancellation/failure release, unchanged choice evidence after refusal, identity substitution rejection and no default activation. Existing mode-guard and selected-store publication/recovery tests remain applicable.
