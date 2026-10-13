# Timekeeper

[![Build](https://github.com/turesheim/eclipse-timekeeper/actions/workflows/build.yml/badge.svg)](https://github.com/turesheim/eclipse-timekeeper/actions/workflows/build.yml)

Timekeeper tracks work sessions projects, tasks and activities in Eclipse or through a
standalone server with a bundled browser interface. Optional Mylyn
integration connects Eclipse task activation and repository-backed tasks.

Timekeeper 2.0 is under development. See the [project documentation](docs/README.md)
and [user guide](https://github.com/turesheim/eclipse-timekeeper/wiki/Timekeeper-2.0-User-Guide)
for capabilities and release status.

## Choose how to use Timekeeper

The Eclipse plugin works on its own, or with optional Mylyn integration for task
repositories such as Jira.

| Mode | Where records are stored | When to use it |
| --- | --- | --- |
| Eclipse with a workspace database | H2 in the current workspace | Keep each workspace's records separate. Choose **Local database**, then **Database > Relative to workspace (in .timekeeper/)**. |
| Eclipse with a shared local database | H2 in your home directory | Use the same records when switching workspaces on this computer. This is the default local storage mode; choose **Database > Shared (in ~/.timekeeper/)**. |
| Eclipse connected directly to an H2 database | A database selected by its JDBC URL, including an H2 TCP server | Manage the database yourself. Choose **Local database**, then **Database > Specified by JDBC URL**, and enter the URL. This connection does not use the Timekeeper REST server. |
| Eclipse connected to a Timekeeper server | The server's database, scoped to your token's account | Share records with other Eclipse instances and the browser. Choose **Timekeeper server**, enter its URL and token, and use **Test connection** before applying. |
| Browser connected to a Timekeeper server | The same server account's records | Open the server's `/web/` page and sign in with a token. Eclipse, Mylyn and task repositories are optional. |

For local and direct-H2 storage, configure **Preferences > Timekeeper > Database**.
The Timekeeper server owns its database and exposes an authenticated HTTP API;
its database location is configured by the server administrator. Switching
backends does not copy or merge records. See the [Eclipse connection guide](docs/operations/ECLIPSE-CLIENT.md),
[database storage and backup guide](docs/operations/DATABASE-RECOVERY.md), and
[server/Docker setup](docs/operations/LOCAL-DOCKER.md).

## Install the Eclipse plugins

The project website and update sites are hosted together on GitHub Pages.
While 2.0 is under development, install a verified main build from the
[testing update site](https://turesheim.github.io/eclipse-timekeeper/updates/testing/):

```text
https://turesheim.github.io/eclipse-timekeeper/updates/testing/
```

1. In Eclipse, open **Help > Install New Software... > Add...**.
2. Enter **Timekeeper** as the name and paste the URL above into **Location**.
3. Select **Timekeeper for Eclipse**. Also select **Timekeeper Mylyn integration**
   if you want Mylyn task activation and Task List integration.
4. Complete the installation, restart Eclipse, and open
   **Window > Show View > Other... > Timekeeper > Workweek**.

Timekeeper 2.0 requires **Eclipse 2026-09 (4.41) and Java 21**. Keep the
Eclipse 2026-09 software site enabled so Eclipse can resolve dependencies.
The test channel updates only after all main platform, server, web and installed
client tests pass. It contains unreleased builds. Published stable versions use
the [stable update site](https://turesheim.github.io/eclipse-timekeeper/updates/);
published release candidates use the [preview update site](https://turesheim.github.io/eclipse-timekeeper/updates/preview/).
An empty release channel means no matching release has been published. See the
[installation guide](https://github.com/turesheim/eclipse-timekeeper/wiki/Installing-Timekeeper-2.0)
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
