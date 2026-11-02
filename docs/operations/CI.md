# CI selection and local verification

PR validation follows the files changed against the PR base. Local verification
and its commands/results should be recorded in each PR description. A routine
layout change does not need the full deployment matrix repeated on GitHub.

| Change | Automatic PR checks |
| --- | --- |
| Documentation only | Packaging/CI tooling tests and website build |
| Web CSS/layout | Web unit/asset checks, packaged browser fixture, design/theme/Today/mobile scenarios |
| Known web feature | Web unit/asset checks and the feature's browser scenarios |
| Shared or unrecognized web behavior | Web unit/asset checks and complete ordinary browser suite |
| Ordinary Java server endpoint | Server tests, packaged HTTP/recovery checks, API contracts and ordinary browser suite |
| Database, authentication, dependencies, packaging, CI or unrecognized product paths | Full validation, including platform, HTTPS, containers and Synology checks |

Markdown editor CSS and feature unions larger than six suites use the complete
ordinary browser suite with its existing isolated fixture batches. Changes to
browser fixtures/helpers run the complete ordinary browser suite. Deleted/renamed
code remains part of selection. Unavailable or empty comparison history selects
full validation rather than silently skipping checks.

## Full validation

Add the **ci:full** label to a PR to request all checks. Adding or removing a label
re-evaluates selection; later pushes retain the label's effect. The **Build**
workflow also supports **Run workflow**, and runs full validation nightly at
02:23 UTC. Scheduled/manual runs validate without publishing containers.

Main-branch product pushes and release tags retain full certification. The
independent main **Server containers** workflow remains the source of deployment
and Synology release provenance. Documentation-only main pushes reuse previously
tested binaries. PRs and nightly runs call that container workflow only when full
validation is selected, after the H2 server checks pass; they reuse that result
instead of running the same H2 suite again. Browser jobs download the exact server
artifact produced by their run, with web-only fixtures packaged without Java tests.

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
mapping. Unmapped web features run the ordinary browser suite. Unknown non-web
paths and sensitive changes run full validation. No arbitrary paths or shell
commands from PR data are executed as browser selection arguments.

Run the selector/aggregate, release-tooling and workflow checks locally:

```sh
python3 -m unittest discover -s scripts/tests -v
actionlint .github/workflows/build.yml .github/workflows/server-containers.yml
```

The tooling tests cover realistic changed paths, full overrides, failed history,
code moved into documentation, bounded browser arguments, and unexpected skipped
or failed required jobs. Use full CI when changing the selector or workflows.
