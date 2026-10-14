# Synology Package Center distribution

The initial NAS acceptance target is **DS923+ / r1000, DSM 7.4.1-90080**.
The package uses DSM **7.2.2 or later** resource interfaces,
Container Manager with the Docker Project worker (**build 1432 or later**), and
SynoCommunity PostgreSQL **17.11-6** on its normal port **5433**. Real DSM acceptance
is required before this candidate can be described as supported or published in
a stable feed. The [generic Compose deployment](DEPLOYMENT.md) remains available.

## Installation flow

Install SynoCommunity PostgreSQL and Synology Container Manager first. Native
PostgreSQL must already have the administrator credentials chosen in its own
wizard. Use **Package Center → Manual Install** for the candidate `.spk`.

The Timekeeper wizard asks for those database administrator credentials, unused
dedicated database/role names, a local HTTP port and the public HTTPS origin from
DSM Reverse Proxy or [Tailscale Serve](TAILSCALE.md). Configure HTTPS forwarding
to that loopback port. It also displays a random one-time setup code: copy it
before finishing. No database administrator password is retained by Timekeeper.

Use **Open**, choose **First administrator setup**, and enter that code to create
your username and password. Codes expire 15 minutes after installation. New
identities use a random stable owner ID; claiming existing data requires its
explicit existing owner ID. [Account operations](ACCOUNTS.md) describes privacy,
sessions, named Eclipse tokens and recovery.

The low-privilege installer creates a dedicated non-superuser role and database
from PostgreSQL's `template0`, avoiding unrelated extensions in SynoCommunity's
`template1`. It refuses DSM's built-in PostgreSQL port 5432. Container Manager's
resource worker loads the bundled immutable Java 21 application image and owns
its lifecycle; there is no Timekeeper PostgreSQL container or host Java dependency.

The application uses host networking to reach native PostgreSQL on loopback and
binds its HTTP listener to **127.0.0.1**. HTTPS and the exact public origin are
required for browser sign-in. The selected HTTP port is also used by the container
health check. The application runs as UID 10001 with a read-only filesystem.

## Data and lifecycle

The `timekeeper-data` shared folder is managed through DSM's Data Share worker.
Its private `server` directory contains settings, the application database
credential, hashed setup/token configuration and backups. It survives package
uninstallation. PostgreSQL data remains with the separate PostgreSQL package.
Stopping or uninstalling Timekeeper never drops databases, roles or other packages.

Reinstallation and upgrades reuse retained credentials and owner mappings.
The retained-install wizard does not request the database administrator password.
Reinstallation renews an unfinished first-administrator setup code and invalidates
earlier pending codes; it never reopens completed account setup.
Provisioning records an installation ID on its dedicated role/database so an
interrupted operation can resume without taking over an unrelated object.
Same-schema upgrades replace the pinned application image and record deployment
time. Schema changes require explicit maintenance and are refused automatically.

Back up before upgrades. A failed application upgrade leaves native data and
retained configuration intact; retain the previous `.spk` for recovery. Real DSM
checks must verify package repair, reinstallation permissions and failure recovery.
Do not remove the native PostgreSQL package or the retained shared folder as part
of an application rollback.

## Native backup and restore

Stop Timekeeper in Package Center before backing up or restoring. The maintenance
helper refuses an active HTTP listener, other database clients, an incompatible
schema or a PostgreSQL major version other than 17. PostgreSQL itself stays running.

A DSM administrator can use **Control Panel → Task Scheduler → Create → Scheduled
Task → User-defined script**, run as root, to invoke the package account without
SSH. For an immediate backup, save and run this task:

```sh
sudo -u timekeeper env SYNOPKG_PKGNAME=timekeeper /var/packages/timekeeper/scripts/timekeeper-dsm backup
```

The task output gives the backup path in the retained private `server/backups`
directory. The mode-0600 archive contains a consistent native `pg_dump`, setup/token
configuration, retained settings and the database service credential. Keep the
archive private and copy it to your normal backup destination. The manifest records
SHA-256 for every member. No PostgreSQL administrator credential is included.

To restore, keep Timekeeper stopped and run a task with the absolute backup path:

```sh
sudo -u timekeeper env SYNOPKG_PKGNAME=timekeeper /var/packages/timekeeper/scripts/timekeeper-dsm restore /var/packages/timekeeper/shares/timekeeper-data/server/backups/timekeeper-EXAMPLE.tar
```

Restore validates the complete archive before touching data, then uses PostgreSQL
17 `pg_restore` in one transaction. It restores business/account data and setup/token
configuration into the current dedicated database, retaining the current service
role/password, HTTPS address and port. Start Timekeeper after success. If a restore
fails, leave the package stopped and resolve the error before retrying. Restoring
an older backup also restores its older password, token and session state; follow
[account recovery guidance](ACCOUNTS.md) for subsequent credential changes.

This command targets an existing, provisioned installation. Rebuilding a lost native
PostgreSQL installation from an archive still requires an explicit recovery flow;
do not replace PostgreSQL's data directory with files from another major version.

## Building a candidate

Build from a clean commit. The image must be `linux/amd64`, carry the same embedded
clean source identity, and have the immutable name shown below. The packager
checks those properties, compiles the small static installer, bundles the image,
and writes the `.spk`, SHA-256 checksum and provenance JSON:

```sh
docker build --platform linux/amd64 --build-arg TIMEKEEPER_BUILD_COMMIT="$COMMIT" --build-arg TIMEKEEPER_BUILD_DIRTY=false -t "ghcr.io/turesheim/eclipse-timekeeper-server:sha-$COMMIT" -f server/Dockerfile .
python3 server/scripts/package_synology.py --commit "$COMMIT" --image "ghcr.io/turesheim/eclipse-timekeeper-server:sha-$COMMIT" --revision 1 --output server/target/synology
```

Preview SPKs use `1.99.0-<revision>` so the eventual stable `2.0.0` package sorts
higher in DSM. Both identify the actual server build in the web footer. Preloading
the tested image avoids unpublished PR tags and moving registry aliases.

## Verification status

Automated PostgreSQL 17 schema/migration tests and an unprivileged installer test
against real PostgreSQL verify role privileges, retained credentials, interrupted
provisioning, explicit owner mapping and absence of plaintext credentials in SQL
statement logs. Native PostgreSQL 17 backup/restore and corrupt-archive refusal are
also tested in the isolated package layout. These do not emulate DSM Package Center
or its resource workers.

Still required before stable distribution: real DS923+ installation/start/stop/
restart/upgrade/reinstall testing, database migration from PostgreSQL 18, verification of native backup/restore on
the actual NAS and package repository discovery. SynoCommunity catalog inclusion
requires upstream acceptance. An independent Timekeeper feed will require users
to configure SynoCommunity separately; one configured source is not promised.

The packaging follows Synology's [Docker Project worker](https://help.synology.com/developer-guide/resource_acquisition/docker-project.html),
[Data Share worker](https://help.synology.com/developer-guide/resource_acquisition/data_share.html)
and [package privilege model](https://help.synology.com/developer-guide/privilege/privilege_config.html).
PostgreSQL connectivity follows [SynoCommunity's package documentation](https://docs.synocommunity.com/packages/postgresql/).
