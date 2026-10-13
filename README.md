# Timekeeper

[![Build](https://github.com/turesheim/eclipse-timekeeper/actions/workflows/build.yml/badge.svg)](https://github.com/turesheim/eclipse-timekeeper/actions/workflows/build.yml)

Timekeeper tracks projects, tasks and activities in Eclipse or through a
standalone server with a bundled browser interface at `/web/`. Optional Mylyn
integration connects Eclipse task activation and repository-backed tasks.

Timekeeper 2.0 is under development. See the [project documentation](docs/README.md)
and [user guide](https://github.com/turesheim/eclipse-timekeeper/wiki/Timekeeper-2.0-User-Guide)
for capabilities and release status.

## Install the Eclipse plugins

The project website and update site are hosted together on GitHub Pages:

```text
https://turesheim.github.io/eclipse-timekeeper/updates/
```

1. In Eclipse, open **Help > Install New Software... > Add...**.
2. Enter **Timekeeper** as the name and paste the URL above into **Location**.
3. Select **Timekeeper for Eclipse**. Also select **Timekeeper Mylyn integration**
   if you want Mylyn task activation and Task List integration.
4. Complete the installation, restart Eclipse, and open
   **Window > Show View > Other... > Timekeeper > Workweek**.

Timekeeper 2.0 requires **Eclipse 2026-09 (4.41) and Java 21**. Keep the
Eclipse 2026-09 software site enabled so Eclipse can resolve dependencies.
Published prereleases use the separate [preview update site](https://turesheim.github.io/eclipse-timekeeper/updates/preview/).
An empty channel means no matching release has been published; 2.0 is still
under development. See the [installation guide](https://github.com/turesheim/eclipse-timekeeper/wiki/Installing-Timekeeper-2.0)
for candidate builds and installing a downloaded p2 archive.

## Repository

| Directory | Responsibility |
| --- | --- |
| [common](common/README.md) | Shared Java domain, service API, HTTP client and OpenAPI contract |
| [eclipse](eclipse/README.md) | Eclipse plugins, features, p2 repository, targets and installed tests |
| [server](server/README.md) | Quarkus server, Docker deployment and bundled [web client](server/web/README.md) |
| [docs](docs/README.md) | Architecture, operations, development and verification evidence |
| `scripts/` | Repository-wide release and publication tools |

Run builds from the repository root using the Maven wrapper:

```sh
./mvnw -B -ntp clean verify
./mvnw -B -ntp -f server/pom.xml clean verify
npm test --prefix server/web
```

See [local Docker setup](docs/operations/LOCAL-DOCKER.md),
[repository layout and development](docs/development/REPOSITORY.md),
[API v1](docs/architecture/API-V1.md), and
[release instructions](docs/development/RELEASING.md).
