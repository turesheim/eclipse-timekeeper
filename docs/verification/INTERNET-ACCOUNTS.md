# Internet accounts verification — 2026-11-10

The implementation replaces administrator-enabled task connections with separate
Google and Microsoft Internet account sections. Users can select calendars,
tasks or both through shared sign-in, or add an ordinary calendar subscription
without signing in. Task and calendar drafts, mappings and checkpoints are
independent. Provider registrations belong to the separate shared service.

Implementation checkpoint: `8ef4eada725fa7b4c9992c2cf79dbebb36592184`.
Browser checkpoint: `c45649ebed7cbcc260e28ef0f2ef4f86b2936aa6`; only browser
tests differ between these commits. Subsequent screenshot/documentation and CI
changes do not change application sources. Current CI remains the merge gate.

Executed checks:

- All six affected quality groups passed: quick, tooling, 70 contract cases,
  167 web unit cases, the server reactor and the headless Eclipse reactor.
  The application reported 841 cases with no failures/errors and 16 intentional
  opt-in or storage-specific skips. The shared service passed all 10 cases.
- The 29 new calendar/shared-service cases passed without skips on disposable
  PostgreSQL 17.11 and 18.6 databases, including the production HTTP adapters,
  ownership, browser binding, separate configuration versions, interrupted
  calendar pages, expired Google sync tokens, encrypted central records,
  refresh-token rotation and durable grant-deletion retries.
- The PostgreSQL shared-service image built and became ready. A synthetic consent
  request retained its browser binding and nonce across an actual container/JVM
  restart. Calendar-only authorization requested no Tasks scope. The fixture
  did not follow the redirect to Google or contact a real provider account.
- All 74 Chromium scenarios passed against the checksum-verified package
  `ada9015d09aebe5808a1577b690d0f9bcc020958d4b739b22bc38a842fbc33a3`.
  These cover provider controls and callbacks, calendar subscriptions, Tasks,
  note checklist tasks, read-only sessions, stale edits, English/Norwegian,
  seven themes, accessibility and 320px/390px/1440px layouts.
- The packaging regression rejects missing, dirty or stale shared-service
  identities. Both JVM ZIPs were produced from the exact clean source commit.

[Review screenshots and the complete theme/language gallery](../assets/screenshots/internet-accounts/README.md)
are synthetic and contain no live account data. Desktop, Norwegian mobile and
Newspaper captures were visually inspected.

Live Google/Microsoft consent, refresh, reconnect and provider restrictions remain
unverified. The shared service has not been deployed; its public HTTPS hostname,
central registrations and project-owned discovery must be configured before
users can sign in. No per-NAS application registration is required in shared
mode. See [shared-service operations](../operations/SHARED-SIGN-IN.md).

Local Eclipse validation used `MAVEN_ARGS=-Dskip-ui-tests` because the desktop
session is locked. Installed UI checks belong to CI. Actual Synology/Hyper Backup
repository recovery remains separately untested; these connector checks do not
establish NAS acceptance.
