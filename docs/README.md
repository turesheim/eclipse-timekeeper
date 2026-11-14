# Dagsverk documentation

Dagsverk tracks work sessions and activities in a browser or Eclipse. Start with
the guide for your task; implementation contracts and dated test reports are
separate references.

## Install and use

| Task | Guide |
| --- | --- |
| Install the Synology package | [Package Center installation and recovery](operations/SYNOLOGY-PACKAGE.md) |
| Run locally with Docker | [Local Docker setup](operations/LOCAL-DOCKER.md) |
| Run the standalone Java server | [Server setup](operations/SERVER.md) |
| Deploy PostgreSQL and server replicas | [Compose deployment](operations/DEPLOYMENT.md) |
| Use the browser client | [Web client guide](operations/WEB-CLIENT.md) |
| Connect Eclipse | [Eclipse backend selection](operations/ECLIPSE-CLIENT.md) |
| Create accounts, passkeys or API tokens | [Accounts and recovery](operations/ACCOUNTS.md) |
| Set up Google or Microsoft | [OAuth app registration](operations/SHARED-SIGN-IN.md) and [Internet accounts](operations/TASK-PROVIDERS.md) |
| Back up or restore an installation | [Full-instance backup and restore](operations/INSTANCE-BACKUP.md) |
| Transfer one account | [Personal account archives](operations/ACCOUNT-DATA.md) |

See [all operational guides](operations/README.md) for Notes, WebDAV, Toggl import,
Tailscale, diagnostics and configuration names. Eclipse installation, tracking
and Mylyn workflows are also covered in the
[Eclipse user guide](https://github.com/turesheim/eclipse-timekeeper/wiki/Timekeeper-2.0-User-Guide).

## Develop and maintain

Start with [repository setup and build commands](development/REPOSITORY.md).
The [development index](development/README.md) links component guides, browser and
installed-client acceptance, packaging and release procedures.

[Architecture](architecture/README.md) describes component boundaries and links
the data model, API contract, Notes storage, task synchronization and backup
implementation. These are detailed references, not installation steps.

## Historical evidence

[Verification reports](verification/README.md) record results for specific source
commits and environments. They do not establish that the current checkout passes.
The [upgrade plan](development/UPGRADE-PLAN.md) preserves dated decisions;
current commands belong in the development guides.

New product copy uses Dagsverk. Existing package IDs, Java namespaces, API headers,
configuration aliases and storage identifiers retain `timekeeper` where required
for compatibility; see [the naming transition](development/BRANDING.md).

## Upgrade and rollback

Back up before changing a database or runtime. Embedded Eclipse and standalone
server storage have different schemas and upgrade procedures. Use
[embedded recovery](operations/DATABASE-RECOVERY.md),
[server/account upgrades](operations/ACCOUNTS.md#existing-data-and-accounts) or
[instance recovery](operations/INSTANCE-BACKUP.md) for the relevant deployment.
A binary downgrade across an incompatible schema needs its matching backup;
later work is not merged automatically.
