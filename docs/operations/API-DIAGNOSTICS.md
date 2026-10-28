# Read-only API diagnostics

Use a separate personal token under **My Account → API tokens**, with
**Read-only** access and a short expiry. Administrator access is unnecessary for
inspecting your own labels and notes. Store its secret outside the repository
in an owner-readable curl configuration file, for example:

```text
header = "Authorization: Bearer <personal-token>"
```

Set the file's permissions to `0600`. Keep the configuration private and use a
certificate-validated HTTPS connection to the same Timekeeper server as the web
client. The operating system's curl may use its system certificate store when a
separate Python runtime does not trust the operator's local certificate issuer.

With `TIMEKEEPER_DIAGNOSTICS_CONFIG` naming that file and
`TIMEKEEPER_DIAGNOSTICS_URL` naming the HTTPS server, the following are read-only:

```sh
curl --config "$TIMEKEEPER_DIAGNOSTICS_CONFIG" "$TIMEKEEPER_DIAGNOSTICS_URL/api/v1/auth/session"
curl --config "$TIMEKEEPER_DIAGNOSTICS_CONFIG" "$TIMEKEEPER_DIAGNOSTICS_URL/api/v1/server"
curl --config "$TIMEKEEPER_DIAGNOSTICS_CONFIG" "$TIMEKEEPER_DIAGNOSTICS_URL/api/v1/day-labels?fromDate=2026-10-01&toDate=2026-10-31"
curl --config "$TIMEKEEPER_DIAGNOSTICS_CONFIG" "$TIMEKEEPER_DIAGNOSTICS_URL/api/v1/day-labels/2026-10-27"
curl --config "$TIMEKEEPER_DIAGNOSTICS_CONFIG" "$TIMEKEEPER_DIAGNOSTICS_URL/api/v1/day-notes/2026-10-27/content"
curl --config "$TIMEKEEPER_DIAGNOSTICS_CONFIG" "$TIMEKEEPER_DIAGNOSTICS_URL/api/v1/day-entries/2026-10-27"
```

Check `/auth/session` for the actual permissions; a token granted write access
must still be used only for reads during a read-only investigation. `/server`
identifies the deployed commit and startup/deployment times. `/day-labels`
reports the independent label version; `/day-notes/{date}/content` reports only
the note's body, presence/deletion state and version. `/day-entries` supplies the
editor projection plus the independent resource ETags. A cleared label retains
its version. A deleted note may coexist with a nonempty label. Body-only edits
must not change the label version or its ETag, and label-only edits must not
change the note version, ETag or WebDAV bytes.

On an older server without these independent routes, `/day-notes/{date}` and its
range listing expose the legacy combined projection. WebDAV `HEAD`, using a
separately configured Basic-auth client, exposes the latest note modification
time. The generic `/changes` feed contains content-free PROFILE invalidations;
it cannot identify which old label changed or attribute that change to a client.
Current-state reads and version numbers cannot reconstruct unrecorded history.

For an unexplained historical loss, compare a pre-update backup in an isolated
restored instance. Keep the restored instance separate from live clients and
compare label values, note versions and deployment identity. Do not restore it
over manually corrected live data as part of diagnosis. Record only synthetic
values in public issues; keep authorization headers and private account content
out of logs, screenshots and shared reports.
