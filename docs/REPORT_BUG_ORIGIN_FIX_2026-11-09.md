# Report Bug: official form origin

The native Settings form obtained a valid CSRF session but omitted `Origin` and
`Referer` on its multipart POST. The deployed service checks the form's origin
before accepting the report. The web form's same-origin `fetch` gets those headers
from the browser; OkHttp does not add them automatically.

Live protocol checks on 2026-11-09 confirmed the mismatch. The public form and
`/api/csrf.php` returned HTTP 200 with the expected JSON/session-cookie contract.
An empty multipart POST without cookies, CSRF or report fields returned
`403 {"ok":false,"error":"forbidden"}` without the form headers. With the fixed
official origin and form URL, the same invalid request reached session validation
and returned `403 {"ok":false,"error":"session_expired"}`. No report content or
valid submission was sent by these probes.

`OfficialBugReportTransport` now supplies the fixed official `Origin` and `Referer`
on submission. `BugReportPolicy.FORM_URL` keeps that form URL beside the other API
constants. Existing session/CSRF validation, one-shot request body, disabled
redirects, cancellation, duplicate prevention and draft handling remain intact.
The Settings UI and layout are unchanged.

The reference is the deployed
[official form](https://droide.rekacipta.biz.id/report-bug.html) and its
[submission script](https://droide.rekacipta.biz.id/assets/report-form.js).
The previous test explicitly required these headers to be absent; it now tests
the observed contract. A regression fixture models the observed origin rejection
and verifies one accepted, correctly authenticated native submission. Both checks
fail against the original production implementation.

Validation uses exact current report production sources and local HTTP interceptor
fixtures, with existing policy, coordinator and draft-restoration tests. These
fixtures do not send real email or establish mailbox delivery. Live evidence is
limited to the public reads and intentionally invalid rejection probes above.
No full Gradle/APK/native build or Android device run was performed.
