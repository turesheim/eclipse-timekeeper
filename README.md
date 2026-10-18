# Timekeeper

[![Build](https://github.com/turesheim/eclipse-timekeeper/actions/workflows/build.yml/badge.svg)](https://github.com/turesheim/eclipse-timekeeper/actions/workflows/build.yml)

Timekeeper is a self-hosted time-tracking application with a web client and
Eclipse plugins. Use the browser to track work for clients and projects, or
track tasks from Eclipse with optional Mylyn integration. Eclipse can use local
storage or connect to the same server account as the browser.

**Timekeeper 2.0 is under development.** Current development builds are available
through the testing update site and GitHub Actions artifacts described below.

[Project website](https://turesheim.github.io/eclipse-timekeeper/) ·
[Web client guide](docs/development/WEB-CLIENT.md) ·
[Eclipse user guide](https://github.com/turesheim/eclipse-timekeeper/wiki/Timekeeper-2.0-User-Guide) ·
[Documentation index](docs/README.md)

## Web client

A work session records time for one client, with an optional project, summary and
labels. Clients have colors; projects belong to clients and can have optional
icons. Task activities are tracked independently of work sessions; their time
overlap provides context.

- **Track** shows monthly work-session totals by client and day. Open a daily
  entry to edit its session, or choose one when several sessions contribute.
- **Workweek** groups sessions by client and project, with editable start/end
  times in daily cells. **Calendar** supports creating, moving, resizing and
  copying sessions. Dates and times use your account's time zone. Work-session
  edits and deletions offer **Undo** for ten minutes.
- **Activities** contains the project/task/activity table and its daily and weekly
  totals. Start a task timer or record time manually, including taskless work.
- **Clients and projects** organize your work with client colors and searchable
  project icons.
- **Themes** include the original Timekeeper palette, Timekeeper Dark, Solarized
  Light/Dark, Nord and Dracula. Choose a theme in the account menu.
- **Toggl Track import** maps source clients and projects to new or existing
  records and supports undoing a whole import. Prefer the Legacy CSV export
  because it includes client names. Duration is calculated from Start/End times;
  the exported Duration column is ignored. See the [import guide](docs/operations/TOGGL-IMPORT.md).
- **My Account** manages your profile, time zone, password, passkeys, recovery
  email addresses and personal API tokens. Download or restore your work through
  [Your data](docs/operations/ACCOUNT-DATA.md).

Each server account's records and preferences are private to that account.
Administrators manage account access without access to other users' work records. See [accounts and recovery](docs/operations/ACCOUNTS.md).

## Run the server

The server exposes API v1 at `/api/v1` and includes the web interface at `/web/`.
The browser client needs no separate frontend service or Node.js process.
Open **About** in the account menu for the server/SPK version, build identity and
deployment time.

Choose a deployment:

- **Docker Compose:** follow the [local Docker guide](docs/operations/LOCAL-DOCKER.md)
  to configure persistent storage, generate the first-administrator setup code
  and start the PostgreSQL-backed deployment. With the default local settings,
  open **http://127.0.0.1:8080/web/**.
- **Synology DSM:** download the `synology-installer` artifact from a successful
  [Server containers workflow](https://github.com/turesheim/eclipse-timekeeper/actions/workflows/server-containers.yml)
  run. Extract it and install the `.spk` through **Package Center → Manual Install**.
  The candidate targets DS923+ (r1000) with Container Manager and SynoCommunity
  PostgreSQL 17;
  follow the [Synology installation guide](docs/operations/SYNOLOGY-PACKAGE.md)
  for prerequisites, setup, upgrades and backups.
- **Standalone Java:** download `server-standalone` from a successful
  [Build workflow](https://github.com/turesheim/eclipse-timekeeper/actions/workflows/build.yml)
  run. It contains the Java 21 server ZIP, bundled web client and checksum.
  See [server configuration](docs/operations/SERVER.md) for H2 or PostgreSQL storage.

Complete **First administrator setup** using the generated one-time code, then
sign in with your account. For access from other devices, configure HTTPS using
[Tailscale](docs/operations/TAILSCALE.md) or the reverse-proxy instructions in the
[deployment and backup guide](docs/operations/DEPLOYMENT.md).

## Install the Eclipse plugins

Timekeeper 2.0 requires **Eclipse 2026-09 (4.41) and Java 21**. The project website
and update sites are hosted together on GitHub Pages. For current development
builds, use the [testing update site](https://turesheim.github.io/eclipse-timekeeper/updates/testing/):

```text
https://turesheim.github.io/eclipse-timekeeper/updates/testing/
```

1. Open **Help → Install New Software... → Add...** and paste the URL into **Location**.
2. Select **Timekeeper for Eclipse**. Also select **Timekeeper Mylyn integration**
   for Mylyn task activation and Task List integration. Repository connectors,
   such as Jira, are installed separately.
3. Keep the Eclipse 2026-09 software site enabled, complete installation and restart.
4. Open **Window → Show View → Other... → Timekeeper → Workweek**.

In **Preferences → Timekeeper → Backend**, choose **Local database** for local H2
storage, or **Timekeeper server** to connect to your server's `/api/v1` URL. Create
an Eclipse token under **My Account → API tokens** in the web client, enter it in
Eclipse and use **Test connection**, then apply settings and restart. See the [Eclipse connection guide](docs/operations/ECLIPSE-CLIENT.md)
for setup and the [storage guide](docs/operations/DATABASE-RECOVERY.md) for shared,
workspace-relative and explicit JDBC storage. Switching backends does not copy records.

The testing channel contains unreleased builds and updates after successful main
validation. Published releases use the [stable update site](https://turesheim.github.io/eclipse-timekeeper/updates/)
or the opt-in [preview channel](https://turesheim.github.io/eclipse-timekeeper/updates/preview/).
See the [installation guide](https://github.com/turesheim/eclipse-timekeeper/wiki/Installing-Timekeeper-2.0)
for release channels and installing a downloaded p2 archive.

## Repository and development

| Directory | Responsibility |
| --- | --- |
| [common](common/README.md) | Shared Java domain, service API, HTTP client and OpenAPI contract |
| [eclipse](eclipse/README.md) | Eclipse plugins, features, p2 repository, targets and installed tests |
| [server](server/README.md) | Quarkus server, Docker deployment and bundled [web client](server/web/README.md) |
| [docs](docs/README.md) | Architecture, operations, development guides and verification evidence |
| `scripts/` | Repository-wide release and publication tools |

Development uses **JDK 21, Python 3.13 and Node.js 24**. Follow the
[developer prerequisites](docs/development/REPOSITORY.md#build-and-test), including
the Python environment, then run from the repository root:

```sh
./mvnw -B -ntp clean verify
./mvnw -B -ntp -f server/pom.xml clean verify
npm ci --ignore-scripts --prefix server/web
npm test --prefix server/web
```

The root Maven build runs Eclipse UI tests and needs a graphical session or Xvfb
on Linux. Use `mvnw.cmd` on Windows. See the [repository guide](docs/development/REPOSITORY.md)
for focused and installed-client checks, [API v1](docs/architecture/API-V1.md) for
the contract, and [release instructions](docs/development/RELEASING.md) for packaging.
