# Native terminal ownership observation

The opt-in coordinator now combines the existing TerminalManager inventory with the already-initialized PersistentLocalTerminalHost, without creating a host. It observes visible, retired, frozen Exit and stop-attempt owners, including failed cleanup and launch preparations. Owner references are copied under each existing monitor, then session observation runs outside it. Application-retained sessions from other projects are conservative diagnostics, never inferred root ownership.

NativePtyTerminalSession copies Main-owned pending/frozen agent receipt references and its human receipt on Main. The helper reads only first-accepted Deferred completion data, without waiting or completing it. Pending, unavailable, process custody failures and relay-input failures remain distinct. Duplicate completion cannot replace the accepted receipt. Opaque owner tokens plus atomic destroyed state invalidate changes. No native getter, cleanupReceipt, relay, stop, await-exit or process probe is used by observation.

A final non-suspending phase rechecks volatile/atomic session metadata, Deferred receipt stamps and monitor-protected registry tokens after all suspending editor/owner checks. Generic process ledgers are checked last. This detects the tested callback mutations without claiming atomicity across authorities or excluding concurrent writers. Main-owned mutable collections are never read from this final phase.

Every result retains unknown native launch/history coverage, detached-group ownership and inherited/external descriptor ownership. Native reason 0 is not detached-group closure; reason 1 means no child started, not global emptiness. Unsupported retained session implementations and absent hosts remain explicit unknowns. Retained owner references are not live-process evidence. Factories not yet published to these registries and previous removed history are not exhaustively inventoried.

## Reference and verification limits

The pinned VS Code utilityProcess lifecycle is the primary comparison for registered owner and exit-receipt distinctions. Its registered utility processes do not establish arbitrary native PTY/group closure. The previous workingCopyService and backup/lifecycle references remain primary; Claude Code and Codex are supplementary. No cleanup algorithm is copied.

Tests execute the production receipt observer and actual TerminalManager/PersistentLocalTerminalHost registries with controlled fake sessions/handles, plus existing lifecycle regression fixtures. NativePtyTerminalSession and the actual coordinator integration are compiled and source-reviewed; no real native session, JNI cleanup, Android device or full coordinator startup is exercised. No NDK build is performed. Cleanup in legacy lifecycle tests belongs to isolated fixtures, not review.

This adds diagnostics only. Physical release, held admission, complete launch/root/descriptor coverage, durable retirement/accounting and default-store activation remain unavailable. No UI, credentials, capability reduction or process-control behavior is added.

The later CATALOG_FALLBACK_OBSERVATION.md adds observations from existing fallback pipe-shell owners and execution metadata, without changing these native coverage limits.
