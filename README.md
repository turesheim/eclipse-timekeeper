# Timekeeper

[![Build](https://github.com/turesheim/eclipse-timekeeper/actions/workflows/build.yml/badge.svg)](https://github.com/turesheim/eclipse-timekeeper/actions/workflows/build.yml)

Timekeeper tracks projects, tasks and activities in Eclipse or through a
standalone server with a bundled browser interface at `/web/`. Optional Mylyn
integration connects Eclipse task activation and repository-backed tasks.

Timekeeper 2.0 is under development. See the [project documentation](docs/README.md)
and [user guide](https://github.com/turesheim/eclipse-timekeeper/wiki/Timekeeper-2.0-User-Guide)
for capabilities and release status.

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
