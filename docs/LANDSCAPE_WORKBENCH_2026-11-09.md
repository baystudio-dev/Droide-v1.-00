# Landscape workbench — 2026-11-09

## Scope and backup

Only landscape workbench behavior changes. The approved portrait route, pane-budget policy, toolbar arrangement and terminal tab strip remain in place. No runtime, editor persistence, terminal process, browser tool, Exit or permission policy changes are included.

Before editing, all 4,439 files from the approved source manifest were archived and their archived bytes checked against that manifest. The 131,936,957-byte archive was uploaded to the authorized Dropbox account `zoviaaa777@gmail.com` and its remote size/account metadata verified:

`/Droide-Full-Source-2026-11-09-before-landscape.zip`

Archive SHA-256: `b735561644ed1e988f881b89fddd2dc23a42d05cb5d26b4dc5f525630d7c9599`.

The source after this change has not been uploaded. Continue development from this candidate; do not substitute the pre-change backup for it.

## Layout and ownership

- Landscape always uses the desktop workbench branch, including phone-sized windows. Sidebar, center editor/bottom panel and right agent pane have independent visibility.
- Companion widths are budgeted against available window width, independently of the other pane's visibility. Closing either companion releases its entire width and separator to the editor. The chat resize range grows for tablet/large windows. The portrait allocation function remains the original branch.
- Landscape consumes the keyboard inset at the common workbench boundary. Child editor/terminal/chat padding sees the consumed inset, avoiding a second subtraction that would erase the short terminal input viewport. Legacy parent-consumed IME insets are also respected.
- Editor focus plus a visible IME hides the workbench header and bottom panel. Optional existing landscape focus mode can additionally hide companion panes, but only during editor typing; chat never opts into it.
- Terminal focus plus the IME expands the center panel over the editor and top header. The X restores the normal size immediately and requests keyboard dismissal. This does not close a session or overwrite the saved normal height. Platform Back still follows observed IME dismissal, with a second Back as an escape for keyboards that ignore hide.
- Editor and terminal native views remain at stable composition sites during panel expansion/collapse. Hidden retained content is unplaced and excluded from focus/accessibility. An initially closed terminal is not initialized until opened.
- The agent composer registers its own input owner in landscape. Late editor/terminal blur notifications cannot clear a newer owner's focus. Moving to the agent or editor releases terminal expansion without issuing a keyboard hide request.
- Landscape terminal sessions share the bottom-panel title row through a menu (select, new, interrupt, close). Short panels omit the optional session summary so terminal input stays reachable. Narrow landscape session controls scroll horizontally with their touch targets preserved. Portrait session chrome uses the original branch.
- Window/IME changes clamp the rendered landscape panel height without rewriting the saved height. The workbench toolbar variant also stays stable through keyboard resizing.

## VS Code source reference

Pinned checkout: `microsoft/vscode` commit `e646868227c3e3a9e3f24277c8c7220be42c0be7`.

`src/vs/workbench/browser/layout.ts`:
- `resizePart` (line 1829) reads actual grid rectangles for resize operations.
- `toggleMaximizedPanel` (line 2253) remembers the normal panel dimensions separately and restores them after maximizing.
- `setPartHidden` (line 2331) independently controls sidebar, panel and auxiliary bar visibility.

Droide uses the same separation of visibility, normal dimensions and temporary maximization. Android IME/native-view handling is specific to Droide; desktop VS Code code was not copied wholesale.

## Verification and limits

The evidence directory contains the source manifest, normalized patch, compiler inputs/logs and per-class test results. Targeted Kotlin compilation covers all seven changed/new component units outside IdeScreen. Tests exercise pane geometry, native EditText view identity/drafts/cursor, input ownership, X/Back/rotation behavior, session menu actions, narrow/large-font controls and shared inset consumption. A golden digest captured from the approved baseline compares 20 portrait layout values and 1,920 portrait pane-budget values.

Full IdeScreen compilation through the available historical host cache is blocked by 16 dependency diagnostics. The approved baseline produces exactly the same 16 diagnostics (TerminalReadinessInvalidation, old extension/agent signatures and recovery APIs). This is not a successful full integration build. No production code was modified to fit stale cached APIs.

No Gradle/APK build or physical Android device test has been performed for this change. Host native-input fixtures do not replace device validation of Sora, PTY rendering, OEM keyboards or display hinges.

Before release, run the normal build and check on phone/tablet/foldable windows: both companions open; each independently closed; keyboard transitions among editor, terminal and agent; terminal X and platform Back; narrow multi-window; large font; hardware keyboard; cutouts/system navigation; rotation while a dirty editor and active terminal exist. Confirm portrait against the approved build. Foldable hinge-specific placement remains a physical-device verification item, not a claimed certification.
