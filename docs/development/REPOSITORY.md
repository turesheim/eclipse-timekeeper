# Repository layout and development

Run the commands below from the repository root. Use JDK 21, Python 3.13 and
Node.js 24 to match CI. The checked-in Maven wrapper selects Maven 3.9.16.
Python runs contract, packaging and acceptance tooling; Node.js is needed for
web tests and vendored asset maintenance. The production web client needs no
Node service.

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

Create a Python virtual environment and install the contract validators before
running either reactor. The server verification also invokes Python HTTP tests.
These commands use a sibling directory to keep the environment out of the
checkout:

```sh
python3 -m venv ../timekeeper-venv
. ../timekeeper-venv/bin/activate
python -m pip install -r common/api/tests/requirements.txt
```

Then build and run the standalone checks:

```sh
./mvnw -B -ntp clean verify
./mvnw -B -ntp -f server/pom.xml clean verify
python3 -m unittest discover -s common/api/tests -v
python3 -m unittest discover -s scripts/tests -v
npm ci --ignore-scripts --prefix server/web
npm test --prefix server/web
```

On Windows, activate the environment with
`..\timekeeper-venv\Scripts\Activate.ps1` in PowerShell and use `mvnw.cmd`
instead of `./mvnw`. With the environment active, use `python` if `python3` is
not available.

The root reactor runs Eclipse UI tests and needs a graphical session. On Linux,
install Xvfb and run its Maven command with
`xvfb-run --server-args="-screen 0, 1024x768x24" --auto-servernum ./mvnw -B -ntp clean verify`
when no display is available. Native idle detection additionally requires
X11/XScreenSaver. The standalone server and Node unit tests need no display.
The Node commands above run unit/asset checks; browser acceptance additionally
requires Playwright browser installation and a packaged server, as described
in the [web acceptance guide](WEB-CLIENT.md#automated-verification).

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

Both build workflows separate installable distributions from test results:

| Workflow | Artifact | Contents |
| --- | --- | --- |
| Build | `server-standalone` | Java 21 server ZIP with bundled web client and provenance, plus its SHA-256 checksum |
| Build | `server-test-results` | Server unit tests and packaged-server acceptance results |
| Server containers | `server-compose` | Docker Compose deployment files, Linux AMD64 images, checksums and image references |
| Server containers | `server-compose-test-results` | Server/database test reports and Compose acceptance results |
| Server containers | `synology-installer` | DSM `.spk` installer, checksums, source/image metadata, catalog, icons and manual acceptance template |
| Server containers | `synology-test-results` | PostgreSQL 17 test reports and deployment acceptance results |

For a DSM installation, download **synology-installer** from **Server containers**.
The Build workflow's Java ZIP is an alternative deployment format and contains
no SPK. Earlier Build runs called this Java artifact `timekeeper-server`.

Distribution artifacts are uploaded after successful validation and packaging.
Test results are retained even when validation fails. The Synology job downloads
`server-compose` from the same run and reuses its tested server image. A preview
installer is still identified by its `1.99.0-<revision>` version and metadata;
the artifact name stays `synology-installer`.

## Eclipse import

Import existing Maven projects recursively from the repository root. Open
`eclipse/default.target` and select it as the active PDE target. Launch
configurations live in `eclipse/launchconfigs`. The modules retain their project
names, so an existing workspace may need those projects reimported after moving
the checkout directories.

## Documentation

The [component overview](../architecture/README.md), data model and normative
API policy live in `docs/architecture`.
Operational instructions live in `docs/operations`; development and release
instructions live here. `docs/verification` preserves dated acceptance evidence,
including historical commands and source identities. Current commands use this
layout; historical evidence retains the paths used at the time.

The [upgrade plan](UPGRADE-PLAN.md) retains its dated checkpoint and separates
remaining release gates from historical upgrade results. Consult current
GitHub issues and the [project documentation](../README.md) alongside that
checkpoint rather than treating an old test receipt as a release verdict.
