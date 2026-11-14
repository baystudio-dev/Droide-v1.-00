# Fallback pipe terminal observations

The existing TerminalSession now implements the terminal observation contract. It reports execution job registrations, interactive-shell lease registrations and sticky command-cleanup failures from its existing executionLock authority. Lease insertion/removal and both immediate and late cleanup-failure publication paths invalidate the same opaque token. Interactive shell leases do not inventory all command captures; other captures retain their existing ownership ledgers.

InteractiveTerminalProcess reports existing current worker/process references, retiring workers, retained cleanup owners, reader/writer references, unconfirmed stops and queued input counts under its existing lock. These are retained references, never liveness claims. Launch/detach, queue transitions, process publication, cleanup-owner publication/removal, worker completion, stop confirmation and reader/writer publication invalidate captured tokens. No Process/Thread/Job status getter, cleanup attempt, pending-stop reconciliation or callback is invoked by observation.

Session and interactive locks are acquired separately. Both captures are validated after collection and through the existing final non-suspending metadata phase. No nested authority locks or new suspending final observer is introduced. Tokens detect reported owner changes, not arbitrary concurrent external mutations or a held writer barrier.

The native and fallback producers share immutable terminal summaries; fallback diagnostics never fabricate native cleanup receipts. Empty ledgers still retain catalog-level unknown launch/history/group/descriptor coverage. Published cleanup ownership is not evidence of successful termination, and sticky failure remains sticky.

## Verification and reference limits

The pinned VS Code utilityProcess and lifecycle references remain the primary comparison for registered-owner versus child-exit distinctions. No upstream cleanup implementation is copied; Claude Code/Codex remain supplementary references.

Host tests compile actual TerminalSession and InteractiveTerminalProcess. Interactive fixtures exercise controlled startup suspension, queue and worker ownership, real isolated host shells and retained failed cleanup. Fallback fixtures observe actual unstarted sessions, invalid-command registration/removal and the exact sticky-failure publication helper through reflection; that helper test does not claim a live late-capture callback integration. A registry fixture delegates observation to the actual session while suppressing shell startup. Existing shell lifecycle and all previous checkpoint tests are rerun. No Android device, native PTY/JNI cleanup, NDK build or full app runtime is exercised.

No process-control semantics, terminal capability, UI, default-store activation or physical release path is changed. Global launch/descendant/descriptor coverage, held admission and durable retirement/accounting remain unavailable.

The subsequent CATALOG_CHILD_OBSERVATION.md describes bounded direct active child-resource diagnostics, including child fallback sessions outside the visible terminal manager. Removed/nested histories and admission/closure remain unknown.
