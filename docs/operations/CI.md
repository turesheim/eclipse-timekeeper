# CI selection and local verification

PR validation follows the files changed against the PR base. Main pushes use
the same rules against the previous main commit. Local verification
and its commands/results should be recorded in each PR description. A routine
layout change does not need the full deployment matrix repeated on GitHub.

| Change | Automatic PR/main checks |
| --- | --- |
| Documentation only | Packaging/CI tooling tests and website build |
| Web CSS/layout | Web unit/asset checks, packaged browser fixture, design/theme/Today/mobile scenarios |
| Known web feature | Web unit/asset checks and the feature's browser scenarios |
| Shared or unrecognized web behavior | Web unit/asset checks and complete ordinary browser suite |
| Ordinary Java server endpoint | Server tests, packaged HTTP/recovery checks, API contracts and ordinary browser suite |
| Server/web database, authentication, dependencies, packaging or CI/deployment tooling | Full server, HTTPS, Linux installed-client, container and Synology validation; no desktop platform matrix |
| Eclipse client, shared code/API, root Maven/wrapper inputs or unrecognized product paths | Full validation, including the Linux/macOS/Windows desktop matrix |

Known feature unions stay selected even when they contain more than six suites.
Changes to
browser fixtures/helpers run the complete ordinary browser suite. Deleted/renamed
code remains part of selection. Unavailable or empty comparison history selects
full validation rather than silently skipping checks.

Server validation and desktop platform certification have independent selection
flags. A sensitive server change still runs the Linux **Installed client tests**
against its packaged server, without recompiling the unchanged desktop product
on macOS or Windows. Mixed changes that include Eclipse/shared code or root build
inputs retain the complete desktop matrix. CI/deployment tooling is covered by
its tooling checks and full server validation; use **ci:full** when changing the
desktop build steps themselves or when cross-platform behavior is uncertain.
Server-only site updates reuse the existing verified Eclipse package rather than
publishing an Eclipse package without its platform certification.

## Full validation

Add the **ci:full** label to a PR to request all checks. Adding or removing a label
re-evaluates selection; later pushes retain the label's effect. The **Build**
workflow also supports **Run workflow**, and runs full validation nightly at
02:23 UTC. Scheduled/manual runs validate without publishing registry images.

Release tags retain full certification. Routine web-only main pushes skip Java
tests, Eclipse builds and database/native migration certification. The independent
main **Server containers** workflow builds the current image and Synology SPK,
after a short Compose smoke check of two actual unprivileged replicas, private
shared storage, authenticated API identity, exact commit and bundled web assets.
Its smoke receipt says `mode: smoke`; it does not certify backup, migration or
rolling-update behavior. Backend/deployment changes retain full container checks.
Documentation-only main pushes also build a current SPK, without selecting the
unchanged database/native certification matrix. No workflow publishes the retired
`eclipse-timekeeper-server` container package.

PRs and nightly runs call the container workflow only when full validation is
selected, after the H2 checks pass; they reuse that result instead of running the
same H2 suite again. The container workflow remains the source of deployment and
Synology release provenance. Full certification produces Synology candidates.
Routine main pushes package an installable SPK from the smoke-tested image.
Both validation paths upload **synology-installer**.
The preview channel requires the same commit's successful selected Build checks,
including every browser shard and **PR validation**. Its channel metadata records
`validation: web-smoke`; full candidates record `validation: full`. Stable NAS
promotion still requires full database certification and real-NAS acceptance.

The **Synology latest** workflow waits for the same commit's successful main
Build and Server containers runs. It verifies artifact and SPK digests, source
identity and Build revision before replacing the `synology-latest` prerelease.
Out-of-order completions cannot replace a newer revision. Only this rolling
prerelease loses old assets; all versioned releases remain intact. Older known
temporary SPK artifacts are removed after publication, preserving newer work and
artifacts associated with version tags.

Pages retains its three most recent deployment records, the latest successful
deployment and all in-progress deployments. Old records are made inactive before
deletion; this does not change the deployed website. The Pages workflow's manual
**maintenance_only** mode inventories or cleans the retired container registry
using its repository Actions token. Version-tagged container indexes and their
referenced platform manifests are retained. Use **apply_cleanup** only after
reviewing the inventory; normal website deployment does not run registry cleanup.

Publication gates accept both historical **Web** jobs and complete **Web (i/n)**
matrices. Missing, failed or mixed browser shards prevent publication.

Every Synology installer uses the repository's **Build** workflow run number.
Called container checks receive it directly; the independent main container
workflow resolves the matching source commit's Build run through the read-only
Actions API. It waits briefly for concurrent run creation and fails if no matching
run is available. The container workflow's separate counter is never used as a
package revision. PR and main packages therefore share one version sequence.
The installer artifact contains Timekeeper; Dagsverk migration fixtures have a
separate `synology-migration-test-packages` artifact.

Browser jobs download the exact server artifact produced by their run, with
web-only fixtures packaged without Java tests. Up to four jobs divide spec files
deterministically, with each spec running exactly once. Each job retains the
existing fresh-JVM/database/credential batches of at most six specs. No retries,
assertions or production authentication limits change. Each shard uploads its own
`web-acceptance-<index>` artifact; all shards must pass the aggregate gate.
Additional HTTPS/passkey/recovery checks run once on the first shard.
Web unit and bundled-asset checks also run once on that shard; browser scenarios
remain distributed across every selected shard.

Notes synchronization is verified through the packaged manual WebDAV client
over HTTPS. CI does not build Obsidian or test third-party synchronization
plugins; these are outside the supported client workflow.

Native Synology acceptance runs in seven independent groups (at most four in
parallel), alongside PostgreSQL/package validation: installer controls, early/late
interrupted schema upgrades, schema restore, early/late source migrations and the
runtime migration. All supported source schemas remain covered, including schema
9. Each subprocess has a ten-minute limit, each job a twenty-minute limit, and
per-group receipts record completed commands and timings. Go compilation reuses
the job's content-addressed build cache instead of recompiling its dependencies
in a new empty cache for every migration. Publication requires every selected
group to pass; intentional routine-build skips are explicit.

## Observed bottlenecks

On [run 41616348487](https://github.com/turesheim/eclipse-timekeeper/actions/runs/41616348487),
an editor-only merge spent 8m24s repeating H2 validation in the container workflow.
Its serial Synology controls/migrations step ran about 39 minutes before the job
was cancelled. The companion Build run spent 18m48s in its ordinary serial browser
suite, in addition to unrelated installed-Eclipse and platform validation.
Selection removes those unrelated checks from this type of merge; sharding
shortens the suites that remain. These are baseline measurements, not a promised
new wall-clock duration: cold caches, runner queues and uneven spec costs still
affect runtime.

## Required check

**PR validation** always runs after selection and all selected jobs, including
called container checks. It fails for a failed, cancelled, missing or unexpectedly
skipped selected job, or invalid selection output. Intentionally unselected jobs
may be skipped. The selector prints its decisions in the workflow summary.

After this workflow is merged, use **PR validation** as the branch protection
required status check. Do not require individual optional jobs: a CSS-only PR is
expected to skip database/API/platform checks. This PR does not change repository
branch protection settings.

## Maintaining selection

`scripts/ci_changes.py` owns path classification and the small feature-to-browser
mapping. Unmapped web features run the ordinary browser suite. Unknown product
paths retain desktop certification; sensitive server/CI changes retain full server
validation. No arbitrary paths or shell
commands from PR data are executed as browser selection arguments.

Run the selector/aggregate, release-tooling and workflow checks locally:

```sh
python3 -m unittest discover -s scripts/tests -v
actionlint .github/workflows/build.yml .github/workflows/server-containers.yml
```

The tooling tests cover realistic PR/main paths, full overrides, failed history,
code moved into documentation, complete/disjoint shard coverage, bounded browser
arguments, native schema coverage, and unexpected skipped or failed required jobs.
Changes to selection or workflows must pass these regression and workflow checks.
Use **ci:full** when changing desktop build behavior or when platform impact is uncertain.
