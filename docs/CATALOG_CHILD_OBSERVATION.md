# Direct active child-resource ownership diagnostics

The existing SubagentManager job map is encapsulated by SubagentJobOwners. It is the same task/attempt authority, not a second registry. A resource binding must match the exact executing Job as well as task id and attempt; the executing Job comes from the dispatch context, not a fresh lookup that could bless a replacement. Exact record removal and idempotent resource detach cannot clear a newer attempt. Queued jobs have an unavailable resource root, never an inferred terminal owner.

SubagentOwnedResources now permits a typed terminal observer and the already-resolved child root on the existing terminal disposer entry. It retains metadata through the original copied LIFO disposer list, including suspended NonCancellable cleanup and failure reporting. Disposal callbacks run outside the metadata lock. Original all-disposers execution and primary/fatal/reporting-error suppression behavior is preserved. Return from use is not a successful cleanup receipt.

## Bounded retention and validation

Resource capture invokes terminal observers outside owner locks. The actual terminal validators remain in one bounded resource-local slot, replaced by newer capture and unconditionally cleared at use end, including cancellation and fatal/reporting failure. An exported result retains only immutable diagnostics, opaque tokens and weak owner references. It does not retain Job, terminal, disposer or terminal-validator closures, directly or via a saved job record.

Final validation resolves the weak resource authority, checks lifecycle/token/slot and copies the current validators under lock, releases the lock before validating terminal metadata, then rechecks the same slot. A cleared/replaced slot, removed job, newer attempt or unavailable owner fails closed. Concurrent collection can supersede an older observation; it does not reserve or exclude writers. Job/resource identities are checked around capture, and the established final non-suspending catalog metadata phase rechecks children before generic process ledgers.

The production route is coordinator → existing AgentService manager → exact current job/resource owners. No manager creation, provider call, stored-task transition, shutdown, checkpoint, process probe or cleanup is initiated by observation. A depth-limited absent manager is explicitly unavailable. Existing ExportWriterAdmission spans and ExitOperationFence behavior remain unchanged. This observer reports neither admission readiness nor Exit completion.

## Explicit missing coverage

Removed history, nested child resources, acquisition gaps, universal admission, descendants and inherited/external descriptors remain unknown. Job/dashboard completion and disposer completion never establish closure. Parent manager emptiness is not a clean global inventory. No global registry or post-lifetime terminal retention is introduced.

## Verification limits and references

Focused tests exercise the actual resource authority and the exact job-map helper used by SubagentManager: in-flight disposal, cancellation, failure reporting, bounded slot clearing, no callback under locks, snapshot supersession, exact attempt and same-attempt/job substitution, removal during disposal and final identity-callback changes. The actual SubagentManager, AgentService and coordinator integration are compiled. Existing cleanup, task transition and Agent Exit fixtures plus all previous observations are rerun. This does not claim a live provider-created isolated child, full coordinator startup or device/native execution.

The pinned VS Code lifecycle/utilityProcess sources remain the primary reference for explicit ownership and the difference between lifecycle events and cleanup permission. Claude Code/Codex remain supplementary. No upstream cleanup implementation or stronger shutdown claim is copied. Physical release, held universal admission and durable retirement/accounting remain unavailable.
