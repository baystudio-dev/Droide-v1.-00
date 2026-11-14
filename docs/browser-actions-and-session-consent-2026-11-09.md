# Browser actions and site-session consent

The integrated agent browser used to acknowledge DOM `.click()` even when a disabled or pointer-driven control did nothing. Entered values could bypass readonly state, element references could follow copied HTML attributes, and scrolling only targeted the window. Session interaction grants included each field/text argument, so the next form field required another approval.

This change separates bounded DOM discovery/preparation from native Android input. It retains the existing browser UI, renderer/document authority, permission checks, cancellation and output publication boundaries.

## Resulting behavior

- Click, check, key, hover, swipe and drag use native Android input after validating a unique, visible, enabled, unobstructed target. Pointer targets must settle across two samples. Tiny SVG controls no longer depend on a nonexistent `SVGElement.click()` method.
- A pointer/key is dispatched once. Cancellation terminates a partially delivered gesture with CANCEL; uncertain actions are not replayed. A swipe/drag below touch slop is rejected so it cannot become an accidental click.
- `type` validates field type, readonly/disabled state, maximum length, numeric/date normalization, select options and the value retained by the page. It checks again after focus. Empty text clears a field. `submit=true` sends native Enter after a successful fill; it does not claim a website accepted the submission.
- `check` sets a desired state without toggling an already-correct checkbox/radio/switch. `key` supports navigation keys and explicit modifier combinations; system navigation and OS clipboard shortcuts are excluded. Site consent does not grant clipboard access.
- `scroll` accepts a container and both axes, returning positions and whether it moved. `swipe` is finger movement; `drag` uses pointer movement between visible targets. `wait_for` waits for a ready target within a bounded timeout.
- Snapshot references are attached to actual nodes and a document-specific namespace, rather than page attributes. A replaced node or different document invalidates an old reference.
- Snapshots expose associated labels, state, scroll containers, open shadow controls and bounded select options with indices. Text/password/file field values remain excluded. Frame documents and closed shadow roots remain outside the snapshot; coverage is explicit rather than silently complete.
- The address bar observes navigation/title/loading state from WebView callbacks, including history changes. It defers updates while the user edits the address.

## Permissions

The existing approval UI now offers a site-session choice. The compact browser dock uses its existing approval button for this choice; Details retains the one-action choice. No chat toolbar or new browser panel was added.

Explicit site consent is in memory and scoped by agent session, permission scope, exact scheme/host/port, and capture contract. It covers later browser reads, form interactions, screenshots and console output from the approved site, including captured embedded content disclosed in the prompt. Navigating to another origin requires its own permission. An Allow once decision remains a one-action decision.

Concrete URL/action/target DENY rules still run before dispatch and publication; session consent is not a policy ALLOW. Ordinary web fetches do not inherit browser grants. Known embedded output sources still participate in DENY checks. Each screenshot/console operation creates a fresh, document-bound, one-output consent receipt even when the session grant avoids another prompt. Control of a dialog requested by another origin requires that origin's authority.

Revocation binds to the original grant object, so granting the same site again cannot revive an in-flight call that lost authority. Stopping the agent, changing its session, or retiring the approval owner removes its stored grants. The browser continues to follow Droide's foreground/Exit lifecycle; this change does not start background browser work after Exit.

## Implementation boundaries

- `AgentBrowserDom`: bounded semantic helpers and document/node references.
- `AgentBrowserSnapshot`: paginated observations and explicit coverage.
- `AgentBrowserInteraction`: typed acknowledgments, form editing, scroll and target preparation.
- `AgentBrowserActions`: readiness and single-dispatch orchestration.
- `AgentBrowserNativeInput`: Android pointer/key transport; every event uses the caller's document/authority guard.
- `BrowserActionOptions`: invocation-local options, carried in coroutine context without a shared mutable action slot.
- `BrowserSiteSession`, `ApprovalManager`, `AgentTools`: scoped grants, revocation and existing policy/publication enforcement.

## Reference

VS Code source was inspected at `e646868227c3e3a9e3f24277c8c7220be42c0be7`, notably `clickBrowserTool.ts`, `typeBrowserTool.ts`, `hoverElementTool.ts`, `dragElementTool.ts`, `playwrightService.ts`, and `browserNavigationFeatures.ts`. Its locator-based readiness checks, input operations and model-driven address bar informed this Android implementation; Electron/Playwright is not embedded into the APK.

## Validation and remaining scope

`tools/verify_browser_actions.py` compiles the exact production DOM generators and executes local-only Chromium fixtures, including a form discovered from snapshot labels, small HTML/SVG targets, pointer/trusted events, stale references, invalid inputs and nested scrolling. Playwright touch drives the prepared coordinates in this test; it is not an Android WebView integration test.

`tools/verify_agent_web_browser.py` retains jsdom coverage for privacy, pagination and bounded DOM work. Its geometry/hit testing is mocked and no longer pretends a prepared click executed a native action.

JUnit/Robolectric coverage exercises site-session reuse/revocation, concrete policy denial, native event ordering/cancellation, navigation state, and real AgentTools approval routing. Native transport fixtures use controlled View bounds because ShadowWebView does not run Chromium layout. Final results and source hashes are recorded in `/workspace/droide-browser-actions-20261109/VALIDATION.json`.

Real Android WebView testing remains necessary for device zoom/density/IME, website-specific touch/HTML drag behavior, renderer loss and Exit during gestures. Cross-origin/frame interaction, closed shadow roots, browser file chooser/download integration and a shared-browser tool bridge for external CLI agents are not introduced by this change. Those require their own source/session and file-authority handling; the external CLI can still use its own tools. There was no APK build or Dropbox upload during this work.
