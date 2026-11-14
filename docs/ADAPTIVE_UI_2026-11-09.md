# Adaptive UI audit — 2026-11-09

Continue from `/tmp/droide-adaptive-ui-20261109/source`. This includes the prior browser actions/session-consent and Exit work. Do not upload to Dropbox until the owner asks again. No full APK build was requested for this pass.

## Changes

- Workbench panel height is bounded by the current window, including very short split-screen/IME windows. Resizing within the same width class updates the clamp. Invalid persisted sizes use the normal default.
- The main header measures its actual title height before reserving space. Cutout-aware placement and the title's natural width are preserved. Shared panel headers grow with text; action overflow scrolls independently of the close action.
- Editor/terminal tabs, breadcrumbs, composer actions, usage badges, task controls and sidebar headings no longer force scaled text into a fixed normal-font height. The collapsed bottom-panel toolbar scrolls horizontally. Draft attachment cards allow their filename to increase the card height.
- The Explorer toolbar moves Import, New file, New folder and Refresh into its existing overflow menu when width/font scale leave insufficient room. Directory loading, mutation authority and menu callbacks are unchanged. The row presentation is separate from those controllers. Deep indentation is capped to reserve room for a filename and its actions.
- Approval sheets measure the header and footer before allocating the reading area. With sufficient room, the preview scrolls independently of the footer. Otherwise, the entire sheet scrolls. Short contents still wrap to their content height. Buttons can wrap; no approval is inferred from resizing, scrolling or dismissal.
- Browser approval docks fall back to the existing Review indicator when the available height/width and font scale cannot support the dock.
- Browser address controls accommodate larger text. Recovery errors and the Keep active disclosure have bounded, scrollable areas. An unsent URL draft is no longer overwritten when restored or when unrelated navigation state changes. The same native view survives chrome expansion/reflow.
- Settings uses available window width/height and font scale for its two-pane breakpoint. Its header can scroll within a bounded share of a short window, leaving room for the settings list.

Normal typography, colors, icons and control ordering are retained. No new chat toolbar button, permission policy, automatic navigation or background work was added.

## Reference

Pinned VS Code checkout: `e646868227c3e3a9e3f24277c8c7220be42c0be7` at `/workspace/droide-code-intelligence-2026-10-23/references/vscode`.

- `src/vs/workbench/browser/parts/editor/editorGroupView.ts`: lay out the editor using the measured header and available group dimensions.
- `src/vs/workbench/contrib/browserView/electron-browser/browserEditor.ts`: respond to pane resizing and apply actual container/inset dimensions.
- `src/vs/platform/actions/browser/toolbar.ts`: retain access to actions through overflow when space is constrained.

Android implementation uses Compose constraints, scroll containers and measured content rather than copying DOM layout code.

## Verification and limits

Authoritative logs, source hashes and counts: `/workspace/droide-adaptive-ui-20261109/VALIDATION.json` and `WORK_STATE.md`.

Verification uses targeted Kotlin compilation and Robolectric SDK 35 with native graphics. Coverage includes compact/tablet widths, short/landscape heights, font scales 1.0/1.3/2.0, LTR/RTL, injected IME/cutout changes, live pane resizing, overflow actions and browser draft/native-view preservation. Workbench tests exercise 2,688 pane-budget combinations. Browser fixtures validate host behavior; they are not real Android WebView rendering tests.

A complete application compile was not performed. Broad AgentPane dependency-closure probes encountered incompatible historical host-cache APIs and a missing SnakeYAML library; these failures are recorded, not counted as passing tests. Composer controls are compiled and tested directly. The older compact permission fixture now scrolls to its footer at 170 dp before checking every action: the measured layout intentionally preserves a readable preview instead of pinning actions above an unusably short reading area. Its geometry and explicit-decision assertions remain intact. The one-line modifier changes in AgentChatMedia and the collapsed toolbar in IdeScreen are source-reviewed; those complete integration files are not claimed as compiled by the targeted UI harness.

Before release, verify a debug build on actual Android devices: small phone, tablet, landscape/split-screen, foldable window changes, display/font scaling, keyboard open/close, cutouts, gesture/three-button navigation and RTL. Test native WebView/editor/terminal input on those devices. Physical fold hinges and vendor-specific IME/inset behavior are not simulated by changing window size. There is no claim that every screen on every device has been physically tested.
