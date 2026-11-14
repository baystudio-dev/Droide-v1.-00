# Terminal hyperlink overlay

This overlay adds OSC 8 targets to the existing terminal emulator. The original
sources, historical derivatives and their receipts remain unchanged. Licensing
is covered by [TERMUX_LICENSE.md](../TERMUX_LICENSE.md).

`TerminalEmulator` stores a bounded target table: at most 128 targets and 32,768
UTF-16 code units per emulator. The existing OSC parser limit also applies.
Targets are never logged. Identities are monotonic; eviction or reset cannot
redirect older cells to a newer target. The emulator exposes an additive
`getHyperlinkAt(column, row)` method; the app accepts only HTTP(S) destinations.

`TerminalRow` stores optional integer identities beside the rendered cells.
`TerminalBuffer` carries them through copy, scroll and reflow. Overwrite and
erase invalidate affected cells; combining marks keep the base glyph's target.
Allocated identity arrays are included in the existing retained-buffer budget.
The target table is separately bounded to about 64 KiB of character payload.

The current release engine recipe is `tools/terminal-engine-v2.json`. Both the
source build and the distinct host-test JAR select these three sources. Each
patch is the exact diff from the pinned upstream source JAR. Engine composition
retains the original public API and native ABI; there is no new native binary.

To validate a new build, use a fresh engine output directory with
`tools/terminal_engine_v2.py build`, then its `verify` action. Existing receipts
from before this overlay are intentionally rejected. The app's source debug
and source release Gradle modes build or verify this recipe automatically.

Contract tests can use that build's classes with
`DROIDE_TERMINAL_ENGINE_TEST_OUTPUT=/absolute/path/to/engine-output`.
Host tests and engine verification do not replace an Android device test.
