# External CLI chat-panel recovery

## Report and diagnosis

The panel reported `CLI cleanup from an earlier runtime is unconfirmed` after an
earlier OpenCode task had applied workspace changes. This message originates in
Droide's persistent working-copy process custody, before a replacement CLI can
launch. It is not a model/provider response. Terminal sessions use a different
launch/lifecycle path, explaining why they can still work.

The runtime UUID is already process-global. The exact error means a persisted
owner belongs to a different application runtime and has no accepted cleanup
proof. A screenshot cannot establish whether its process still exists. Exact
process handles disappear when Android kills the application; the SQLite owner
must not be erased on an app restart, retry, timeout or missing PID.

Two concrete gaps were found: boot identity relied solely on a kernel file that
Android can restrict, and legacy launch-schema migration rejected nonempty owners
before consuming otherwise valid cleanup/reboot evidence.

## Changes

- Use Android's system boot counter when the kernel boot UUID cannot be read.
  The counter and kernel UUID have distinct identity domains; switching sources
  within one boot cannot be mistaken for reboot. Invalid device identity or
  storage outside the application's private root still produces no proof.
- Reconcile valid cleanup receipts, durably cancelled unentered reservations and
  reboot evidence before upgrading legacy launch metadata. Existing owners are
  never relabelled as newly prepared processes.
- Preserve an observation of the exact stranded owners in private, authority-bound
  metadata. This observation does not permit a launch. Only a later comparable
  restart of the same Android device can release those captured owners, including
  old rows with no original boot identity. Uncaptured/current-runtime/changed
  owners remain protected. Observation persists independently of a failed launch.
- Display an actionable session-recovery message in the chat panel instead of
  attributing this failure to OpenCode. No buttons or extra toolbar rows were added.

## Existing affected installations

Install a build containing this correction, open the affected project and retry
once. If the panel says to restart the Android device, it has saved a recovery
observation. Restart the device, reopen Droide and retry. Restarting only Droide
does not discharge an unknown process. Work and retained versions are preserved;
do not delete `.droide` or the private working copy to bypass the error.

This is not a promise to resume a process whose handle was lost. A replacement
session starts only after recovery has valid evidence. If trusted device evidence
is unavailable, changed to an incomparable source/device, or metadata is invalid,
the operation remains blocked with a diagnostic. Existing recovery tools remain
available for inspecting retained work.

## References

Read from the existing source snapshots; no upstream implementation was copied:

- [VS Code terminal revival/attachment](https://github.com/microsoft/vscode/blob/e646868227c3e3a9e3f24277c8c7220be42c0be7/src/vs/platform/terminal/node/ptyService.ts): revival creates a new process and maps its identity; attachment uses a retained process. Orphan cleanup uses the retained handle.
- [Codex confirmed termination](https://github.com/openai/codex/blob/7ee5efad4a30c9e05362bf3c87bf9f4670f25813/codex-rs/core/src/unified_exec/process.rs): completion is confirmed before releasing owned process state.
- [Codex process manager](https://github.com/openai/codex/blob/7ee5efad4a30c9e05362bf3c87bf9f4670f25813/codex-rs/core/src/unified_exec/process_manager.rs): failed termination retains the entry, and removal verifies process identity.

## Verification scope

64 focused tests passed: 28 boot/custody reconciliation, 9 boot identity, 15 launch
permission, 11 cleanup receipt file tests and 1 black-box Android boot fallback
regression. The same black-box regression fails on the previous source with the
expected missing-identity assertion and passes on this candidate. These counts do
not include repeated or earlier task tests.

See the accompanying `VALIDATION.json` and compiler/test logs for final counts and
exact input hashes. Verification uses focused host compilation and existing
Android/JNI/Robolectric caches. It does not constitute an APK/native/full build,
physical-device reboot test or live OpenCode/provider request. The device checks
still required are a completed panel task followed by another message, application
process death with an active CLI, and the recovery/reboot sequence above. The
terminal CLI path and MCP/chat-shortcut changes from the previous source are
preserved.
