# Testing and evidence

Normal regression tests belong in `app/src/test` and `app/src/androidTest`. Project build/dependency tooling has its own tests under `tools/tests` and `tools/host-regressions`. A test's age or revision in its name is not a reason to remove it; retain coverage until an equivalent replacement is verified.

Keep one current release ledger for the exact source inventory. Historical compiler output, test logs, temporary harnesses, fault-injection fixtures and benchmark evidence belong outside the application source. Preserve their hashes and failures when comparing before/after behavior.

Report these gates separately:

1. Source/dependency/native integrity and compilation.
2. Host unit/integration regressions, including test setup, skipped cases, timeouts and owned-process cleanup.
3. Full APK packaging for the selected build mode.
4. Physical Android workflows: editor/IME, navigation, terminal/PRoot, real external CLI, build/run/debug, foreground lifecycle and recovery.
5. Process-kill recovery, reboot/power-loss behavior and measured device performance; these are different claims.

For unexpected failures, compare the old and new source plus their test environments. Distinguish inherited defects, intended contract changes, test-fixture omissions, missing host dependencies and new regressions. Compilation success or a targeted rerun must not replace an unsuccessful full-suite result.

Preserve explicit Stop/Exit guards in tests. Standalone fixtures must initialize the same required application-admission state as a user-opened app, then restore the prior state. Never disable production guards or delete failing tests merely to produce a passing report.
