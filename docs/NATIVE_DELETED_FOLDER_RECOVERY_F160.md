# Native Agent: rescue locally deleted folders

F160 adds a production native Agent consumer for local directory-deletion custody. It creates an independent, create-only TAR archive while preserving the retained original and every deletion record. It does not change editor UI or LSP, add an external CLI route, restore the old pathname, implement directory Undo, or release disk space.

## User workflow

Ask the native Agent to find a deleted folder and save an archive, for example: “Recover the deleted src folder to recovered-src.tar.” The relevant advertised tools are:

1. `local_deleted_folders({"action":"list"})`. Continue with each exact `next_cursor` until `complete` is true. Pagination has no 128-operation ceiling. Membership changes invalidate the cursor and require a fresh listing; listing is not a frozen history snapshot.
2. `local_deleted_folders({"action":"inspect","operation":"<returned ID>","prepared_sha256":"<returned digest>"})`. Review the original path, custody state and synchronization-observer state. Prepared or ambiguous custody cannot be silently adopted.
3. `archive_deleted_folder({"operation":"<same ID>","prepared_sha256":"<same digest>","destination":"recovered-src.tar"})`. Use a new ordinary project-relative `.tar` filename whose parent already exists. No existing destination is overwritten.
4. After an interrupted or uncertain result, use the same inspect call with `"destination":"recovered-src.tar"`. It reads the exact known pair’s `NOT_STARTED`, `PREPARED`, `UNKNOWN` or `COMMITTED` evidence without creating an archive. Obtain the destination from the original request or conversation history; this increment does not enumerate forgotten archive destinations.

Repeating the exact archive token/destination after a sealed commit returns its historical receipt without capturing or copying again. An unsealed attempted publication remains unknown; matching current bytes are not proof of historical commit. Do not automatically retry an uncertain operation with another destination.

## Permissions and ownership

Discovery and inspection are read-only. Archive requires BUILD mode, an active service-owned request, private staging outside the project, source read permission and destination edit permission. Without those capabilities, archive is not advertised. PLAN, REVIEW and EXPLORE cannot mutate through stale injected calls either.

Normal ALLOW/ASK/DENY policy applies. Exact original-source descendant permissions are checked before reading each entry: `.env`, private keys or custom ASK paths retain their own approval; every DENY blocks the archive. Because retained paths live under protected `.droide` storage, the first retained descendant requiring ASK offers one explicit, request-local recursive read preview for that exact retained tree, deletion token and archive destination. It includes hidden and sensitive retained descendants, preserves all concrete DENY rules, and does not save a session wildcard grant. A prior root read or root ALLOW does not imply recursive consent. Ordinary large folders do not require one internal-storage prompt per file. At most 256 distinct exact read exceptions are retained per call; reaching that approval-resource limit stops before publication and asks for policy review, rather than implying a folder-size limit.

Approval suspension holds no custody lease or repository mutex. Source, destination parents, request ownership and permissions are revalidated. After approvals, observed effective policy state is compared throughout capture/publication. This is not an atomic policy compare-and-swap or a guarantee of detecting a transient revoke-and-restore entirely between checks. New entries requiring an ungranted original-path approval fail before reading their bytes. An unsaved destination editor document is checked before a new capture and before publication; this is a conservative admission check, not a claim of a separate atomic editor reservation. Historical receipt reads do not touch or reject the current destination because it has a dirty document.

## Result meaning and preservation

A fresh committed result proves local archive publication. `synchronization_status` separately reports `COMPLETED` or `PENDING`. A historical receipt reports `UNKNOWN_NOT_REPLAYED`, with `synchronization_pending:null`; it does not claim old synchronization completed or reverify the current file. `current_file_reverified:false` explicitly avoids that claim. `receipt_durability_reconfirmed` is true only for a fresh successful receipt callback with its durability barriers. Historical replay and every read-only inspection return false: they observe the receipt without new file or metadata durability checks. A valid historical chain proves prior local publication, whose publication barrier preceded the receipt; an earlier seal-force failure may still mean a later crash conservatively leaves `UNKNOWN`.

The archive captures observed bytes and topology, including hidden files, empty directories and supported links without following link targets. Metadata checks are not proof that all writers or inherited descriptors stopped. The retained folder remains mutable; later writes may not be in the archive. Unsupported entries, unsafe identities, denied permissions and incomplete I/O fail safely. Uncertain stages and all original/receipt evidence remain available for review. Only the invocation’s private capture staging is reclaimed.

## Concrete VS Code reference

The supplied Code-OSS 1.143.1 source, `src/vs/workbench/contrib/files/browser/fileActions.ts:87–125,214–250`, distinguishes Trash from permanent deletion, checks dirty working copies, routes through a real bulk-edit/Undo consumer and confirms a destructive fallback.

`src/vs/workbench/contrib/localHistory/browser/localHistoryCommands.ts:257–318` resolves an exact history entry, confirms consequential replacement, handles dirty working copies, calls `cloneFile`, reports errors, and records a history entry. Droide follows the selected-history/real-consumer/explicit-failure pattern but deliberately rescues to a new TAR rather than overwriting or reverting dirty editor state. This reference is not an Android durability or writer-exclusion protocol.

## Validation boundary

Tests exercise actual native `AgentTools.execute` and production `AgentService` advertisement/dispatch, not only an export helper. Layered host-JVM tests and fixtures verify code behavior; they are not an Android build, device fsync/power-loss certification, visual editor QA, or proof of unknown-writer exclusion. Exact run manifests and results are retained with the F160 checkpoint.
