# Installed native import receipt across restart

Issue #220; draft [#263](https://github.com/turesheim/eclipse-timekeeper/pull/263).

The base feature, without Mylyn, exports through the actual database preference
page and GTK folder chooser, then imports that same domain CSV folder through
the native chooser and confirmation. The isolated loopback proxy confirms the
server's real 201 before withholding its response. The client must reach its
normal request timeout, show the normal import error and retain exactly one
pending command key in its real plug-in state directory.

The test requires one committed import, doubled fixture record counts and
unchanged original DTOs/instants. It records hashes of the committed CSV graph,
resource IDs/versions, owner profile and revision, and pending key. A second real
Eclipse/server process retains the workspace, secure storage and server file.
Before retry it requires the same key hash and committed state. Selecting the
same folder and confirming import must acknowledge the original receipt,
remove the key, preserve the exact CSV graph/IDs/instants and leave record
versions and the owner revision unchanged. The installed snapshot must reconcile
to that canonical result; embedded storage remains closed and unchanged.

The GTK driver is shared by both installed test bundles. The base bundle now
forwards the explicit native-dialog flag already enabled by Linux CI and the
release workflow. Other platforms explicitly record
`phase.import.native=not-run-on-this-platform`.

## Verified Linux CI — 2026-10-13

All eight Build jobs passed for branch head
`8681c88f6c3277ecf1b20b615695a21fc251cef5` in
[run 38337550696](https://github.com/turesheim/eclipse-timekeeper/actions/runs/38337550696).
Containers passed in
[run 38337550657](https://github.com/turesheim/eclipse-timekeeper/actions/runs/38337550657).
The downloaded server manifest identifies the tested PR merge source as
`b70f0ef9003e8ce8e9c7b3c870a7cdfe90ad5569`.
Server ZIP SHA-256:
`306c9dc6175493dcb937be8bc9eeacae4b832ff98a1dd8c118ba3af87f5e2d78`.

Downloaded reports confirm all nine installed phases with no failures/errors/
skips. The base receipt records all four new scopes as passed:
`phase.import.committed-timeout`, `phase.import.retained-key`,
`phase.import.replayed-once` and `phase.import.native`, alongside restart.
Private CSV files, server databases, raw keys and credentials are excluded
from uploaded artifacts; receipts contain only hashes and synthetic fixture paths.

Local macOS verification separately passed 115 selected installed tests and
61 server tests; shared module counts overlap and are not summed. All 25 recorded
local fixture processes exited. macOS did not exercise GTK import controls.
Publication tests (13), actionlint and diff whitespace checks passed separately.

This closes the installed native lost-import-reply/restart test scope. It does
not establish final tagged release acceptance, resolve other remaining #220
operation checks or clear official Mylyn Error Log #243. Human review/merge,
the final release-tag workflow and public GitHub p2 installation remain open.
