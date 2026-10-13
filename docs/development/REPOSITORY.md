# Repository layout and development

Run the commands below from the repository root. Maven requires Java 21 and uses
the checked-in wrapper; Node is needed only for browser testing.

| Directory | Contents |
| --- | --- |
| `common/timekeeper-domain` | Platform-independent domain records |
| `common/timekeeper-service-api` | Shared service API and implementation |
| `common/timekeeper-http-client` | Shared Java HTTP client |
| `common/api` | OpenAPI contract and contract tests |
| `eclipse` | Eclipse plugins, features, p2 repository, targets and tests |
| `server/app` | Quarkus HTTP server and persistence |
| `server/core-*` | Plain Maven builds of the canonical shared Java sources |
| `server/web` | Bundled frontend and browser tests |
| `server/deploy` | Compose, database bootstrap and operational scripts |
| `docs` | General documentation, screenshots and verification reports |
| `scripts` | Cross-component release and publication tooling |

The root `pom.xml` remains the Eclipse/Tycho reactor. `server/pom.xml` remains an
independent plain Maven reactor. Shared sources are compiled by both reactors;
this layout change preserves bundle IDs, package names and Maven coordinates.

## Build and test

```sh
./mvnw -B -ntp clean verify
./mvnw -B -ntp -f server/pom.xml clean verify
python3 -m unittest discover -s common/api/tests -v
python3 -m unittest discover -s scripts/tests -v
npm ci --ignore-scripts --prefix server/web
npm test --prefix server/web
```

See [remote Eclipse acceptance](REMOTE-ECLIPSE.md),
[remote Mylyn acceptance](REMOTE-MYLYN.md),
[web acceptance](WEB-CLIENT.md) and [release packaging](RELEASING.md)
for the installed-client and distribution checks.
The **Installed client tests** CI job (formerly **Remote-Eclipse**) installs the
base and optional features in isolated Eclipse runtimes and verifies real
server/browser interaction, preferences, Mylyn tracking and retained-workspace
restart. Pages publishes its main p2 build only after the entire Build workflow,
including this job and the platform matrix, passes.
**Packaging and channel tests** can run in parallel. **Build website and update site** waits
for the matrix and all acceptance jobs, downloads their same-run p2 artifact,
and uploads the complete site artifact on main. The separate Pages workflow
verifies and deploys that artifact after the whole main Build workflow succeeds.

## Eclipse import

Import existing Maven projects recursively from the repository root. Open
`eclipse/default.target` and select it as the active PDE target. Launch
configurations live in `eclipse/launchconfigs`. The modules retain their project
names, so an existing workspace may need those projects reimported after moving
the checkout directories.

## Documentation

Architecture and the normative API policy live in `docs/architecture`.
Operational instructions live in `docs/operations`; development and release
instructions live here. `docs/verification` preserves dated acceptance evidence,
including historical commands and source identities. Current commands use this
layout; historical evidence retains the paths used at the time.

The [upgrade plan](UPGRADE-PLAN.md) retains its dated checkpoint and separates
remaining release gates from historical upgrade results. Consult current
GitHub issues and the [project documentation](../README.md) alongside that
checkpoint rather than treating an old test receipt as a release verdict.
