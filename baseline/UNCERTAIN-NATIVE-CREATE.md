# Unknown native create outcomes

Issue #220. A real native task POST was committed by the packaged server, then
its successful response was withheld until the client timed out. The old dialog
kept Save enabled, so the same draft could issue a second ordinary create.
The regression failed with `Unknown create can be repeated from the same draft`.
No duplicate was sent to reproduce the unsafe affordance.

New project/task/activity dialogs now treat timeout, cancellation and transport
unavailability as an unknown create outcome. They retain enabled fields for
copying, show a persistent review instruction and disable Save for that dialog.
Cancel and refresh/review are required before starting another creation. The
same policy applies to label preference batches containing a creation; Apply and
Apply and Close stay disabled while the draft remains readable/editable.
Known validation/authorization rejections remain retryable. Existing record
editors retain their captured-snapshot conflict behavior. No writes are replayed
automatically and no local database fallback is introduced.

## Verified local candidate — 2026-10-13

Source: `6f761efe5da70f4fffcfc4dc988e9ad4e1fafb4d`.
ZIP SHA-256: `5914316a2bd09c6b8870987543da757855551fa70262ee0330655e594a7be8d1`.
Temurin 21.0.12.1 / macOS aarch64.

The fixture gate forwards exactly one actual ordinary POST for each collection,
observes the server's successful 201, then withholds its response. Actual native
controls create a task, one-hour activity, project and label. After timeout the
same still-open form must show the unknown-outcome instruction, retain enabled
draft fields, disable further Save/Apply, and leave exactly one committed record.
Cancel/review exposes the committed task in Workweek; cleanup preserves the
original account data and closed local file.

- Server reactor: 61 tests passed, no failures/errors/skips.
- Selected installed reactor: 108 main plus seven secondary tests passed,
  no failures/errors/skips. All nine installed reports passed, including the
  four uncertain-create and four review markers alongside real restore,
  browser/Eclipse, label/report and backend-switch evidence.
- All 23 recorded browser/server/proxy process IDs exited.
- The proxy never prints credential headers or request/response bodies. Its
  extension supports actual POST replies as well as the prior task PUT tests;
  successful responses are confirmed before being withheld.

Shared reactor counts overlap and are not summed. This covers confirmed-commit
timeouts for the four ordinary creates. It does not claim every cancellation,
lost import/start/stop/delete or existing-label-update outcome was accepted.
GTK pickers were not run on macOS. The current stack includes newer GTK driver
fixes and still requires successful inspected Linux CI, plus remaining native
operation acceptance, official Mylyn Error Log #243, human review/merge, final
tagged distributions and public GitHub p2 installation.


## Verified Linux CI — 2026-10-13

[Build run 38332322613](https://github.com/turesheim/eclipse-timekeeper/actions/runs/38332322613)
passed all eight jobs at source `63aadd9b57a6dee826361c49b896195df656c51f`.
The downloaded installed acceptance artifact contains nine successful reports
with zero failures/errors/skips. Its server ZIP SHA-256 is
`9903b6149e1cb6624759dd36fe9de0e34783130f8be5084b53eeade5d395cffe`.
The receipt records the actual Linux GTK folder-dialog scope as passed, all five
live-restore phases as passed, and all four ordinary-create timeout phases and
their four cancel/review phases as passed. This supersedes the pending Linux CI
qualification above; the local macOS evidence remains limited to that platform.

The final release workflow/tag execution, remaining native operation acceptance,
official Mylyn Error Log #243, human review/merge and public GitHub p2 installation
remain open. Successful branch CI does not establish final release acceptance.
