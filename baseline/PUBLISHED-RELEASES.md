# Published-release storage audit

> Historical report: the maintainer subsequently removed legacy migration support.
> Historical conversion steps and related release-acceptance requirements below
> are superseded by [migration simplification](MIGRATION-SIMPLIFICATION.md) and the
> [current database policy](../DATABASE-RECOVERY.md). Version/lifecycle infrastructure
> and current-storage tests remain; old test totals are historical results.

Checked September 21, 2026, while following step 4 of the upgrade plan.
This is a provenance audit, **not** a successful migration test of a published
H2-based release. No personal database was accessed.

## Evidence

- The [GitHub releases API](https://api.github.com/repos/turesheim/eclipse-timekeeper/releases)
  lists public releases `v1.1.0`, `v1.0.1a` and the `v1.0.0` prerelease.
  Authenticated maintainer access also shows a `v2.0.0` **draft**, without
  assets or a publication date. A draft is not evidence of a published binary.
- Downloaded the actual [1.1.0 release asset](https://github.com/turesheim/eclipse-timekeeper/releases/download/v1.1.0/net.resheim.eclipse.timekeeper-1.1.0.201509271109.zip),
  `net.resheim.eclipse.timekeeper-1.1.0.201509271109.zip`.
  SHA-256: `f4ef8dcc0f7cd2bd681ebb72215d9fa2fef5117779721291ec7cb6f77af6827d`.
  Its p2 inventory contains one UI plugin and one feature, both version
  `1.1.0.201509271109`; there is no database plugin.
  The UI binary's manifest includes only the plugin itself and JNA libraries
  on its bundle classpath. `javap -c` confirms that its `accumulateTime` method
  adds whole seconds through `getValue`/`setValue`, matching the tagged source.
- Tag `v1.1.0` points to `d00529d0ac31993f908cea4b3b5526512034148f`.
  Its [Activator implementation](https://github.com/turesheim/eclipse-timekeeper/blob/v1.1.0/net.resheim.eclipse.timekeeper.ui/src/net/resheim/eclipse/timekeeper/ui/Activator.java)
  stores daily second totals and active-task timestamps in the Mylyn task
  attribute `net.resheim.eclipse.timekeeper`, using semicolon-separated
  `key=value` pairs. It does not create an H2 database.
- The database project first appears in repository commit `da5ab90`
  (November 20, 2016), after 1.1.0. Repository commits and synthetic schemas
  alone do not establish what a published installation shipped.
- The Marketplace API endpoints `/content/timekeeper-eclipse/api/p` and
  `/node/2196325/api/p` both returned an empty `<node/>` during this check.
  This does not prove that there has never been a newer Marketplace release.
  The `gh-pages` branch contains old screenshots, not a p2 repository; the
  current project website presents the current README.

The binary was downloaded only into temporary storage for inspection, not
installed, executed or added to the repository.

## Consequences for the plan

Do not label the repository V1/V2 SQL fixtures or frozen current-model fixture
as “previous published-release databases”. Their provenance remains exactly
as documented in the existing baseline reports.

There is no H2 file to generate from the inspected 1.1.0 release. Its Mylyn
attribute format is a separate migration problem; daily totals alone cannot
reconstruct exact activity intervals. No automatic Mylyn-to-H2 conversion or
synthetic reconstruction of those intervals has been added here.

The published-release database checkbox stays open. To complete it, obtain a
specific previously distributed H2-based plugin/p2 ZIP or an archived update
site, record its version and checksum, and generate synthetic data using its
actual persistence implementation. A personal database is not required.
Confirm its engine, schema and any Flyway history before deciding whether the
current converter supports it. Preserve the resulting fixture and expected
counts, associations and durations, then test conversion, reopen and rollback.

While this artifact is unavailable, the independent shared-storage/server
checks in step 4 can proceed. See [storage-mode acceptance](STORAGE-MODES.md).
