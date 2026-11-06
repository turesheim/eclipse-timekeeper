---
name: dagsverk-backend
description: Change Dagsverk shared Java domain/services, API v1, HTTP clients or server persistence. Use for backend and cross-client contract work, including account isolation, time calculations and concurrency.
---

# Dagsverk backend

Read root-relative `docs/architecture/README.md`, the applicable sections of
`docs/architecture/API-V1.md` and `docs/architecture/DATA-MODEL.md`.
Use `docs/development/HTTP-CLIENT.md` when changing the remote Java client.

- Edit canonical shared sources under `common/`. `server/core-*` compiles those
  same sources; avoid introducing a second implementation. The root reactor
  builds Eclipse bundles and `server/pom.xml` builds the standalone server.
- Keep domain/service code free of Eclipse, HTTP and persistence dependencies.
  Adapt transport and storage at their established boundaries.
- Trace contract changes through OpenAPI, server, Java client and browser.
  Preserve existing `Timekeeper` headers and archive/configuration identifiers.
- Check ownership on reads and writes using the authenticated account. Include
  cross-account negative cases when changing access paths. Use synthetic data.
- Preserve ETags/optimistic concurrency and synchronization semantics. Keep
  activity and work-session concepts distinct; use account time zones for
  calendar-day boundaries. Exercise midnight/DST only where relevant.
- For schema/storage changes, follow the current database policy in
  `docs/operations/DATABASE-RECOVERY.md`; historical migration procedures are
  not current requirements. Verify supported storage paths affected by the change.
- Run the affected quality groups and targeted API/server tests. Shared Java
  changes need both reactors. Use `server/scripts/acceptance.py` and the
  component acceptance guides when runtime, authorization or restart behavior
  changes. Report any checks that could not execute.
