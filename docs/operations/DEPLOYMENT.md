# Timekeeper on Synology Container Manager

For Docker Desktop or Docker Engine on your own computer, see the
[local Docker setup guide](LOCAL-DOCKER.md).

This deployment runs two stateless Timekeeper servers, one PostgreSQL 18 instance
using the official `postgres:18.6-bookworm` image, and HAProxy for HTTP load balancing.
It targets the DS923+ (AMD64), DSM 7.4.1 and Container Manager. The published
server image and official PostgreSQL image support Linux AMD64 and ARM64.

The API updater drains and replaces one server at a time while the other keeps
serving requests. PostgreSQL is left running throughout an API update. There is
no database failover: a database restart or failure interrupts both servers until
PostgreSQL recovers. Container restarts preserve the host-mounted database files.
All containers also share the NAS's power, disks, Docker daemon and operating
system. For host-level availability, use independent hosts or an external HA
PostgreSQL service. VMM on the same NAS does not remove these shared failure points.

## Install on the NAS

1. Install **Container Manager** in Package Center. Create
   `/volume1/docker/timekeeper/deploy` and `/volume1/docker/timekeeper/state`.
   Copy this deployment bundle into the `deploy` directory. Keep the state
   directory outside the Container Manager project directory.
2. Copy `.env.example` to `.env`. Keep
   `TIMEKEEPER_STATE_DIR=/volume1/docker/timekeeper/state`; adjust it if using a
   different NAS volume. The HTTP listener defaults to `127.0.0.1:8080` for DSM's
   HTTPS reverse proxy or Tailscale Serve. Change the port if it is occupied.
3. Run the one-time setup from SSH or a DSM user-defined task with permission to
   create the state directories. With Python 3 available:

   ```sh
   cd /volume1/docker/timekeeper/deploy
   python3 setup.py --owner torkild
   ```

   Alternatively, use a temporary Python container; no Python installation is
   required on the NAS:

   ```sh
   cd /volume1/docker/timekeeper/deploy
   docker run --rm -i \
     -v /volume1/docker/timekeeper/deploy:/volume1/docker/timekeeper/deploy \
     -v /volume1/docker/timekeeper/state:/volume1/docker/timekeeper/state \
     -w /volume1/docker/timekeeper/deploy \
     python:3.13-alpine python setup.py --owner torkild
   ```

   Save the one-time administrator code (15-minute lifetime), open `/web/` and
   complete first setup. Then create a personal Eclipse token under **Account**.
   See the [account guide](ACCOUNTS.md) for migration and recovery. Setup preserves existing
   secrets, tokens and databases when run again. Do not regenerate missing
   database credentials for an existing database: restore the original files.
4. In **Container Manager → Project → Create**, set the project name to
   `timekeeper` (matching `compose.yaml` and `.env`), choose the `deploy` directory
   and its `compose.yaml`, then start the project. The equivalent SSH command is:

   ```sh
   docker compose pull
   sh set-deployment-time.sh
   docker compose up -d
   docker compose ps
   ```

   On older Compose clients, `docker compose pull` may include the optional
   maintenance client. This is harmless. Allow a few minutes for the first
   database to initialize and for all health checks to become healthy.

   Before the initial Container Manager deployment, run `sh set-deployment-time.sh`
   over SSH in the deployment directory. It records `TIMEKEEPER_DEPLOYED_AT` in
   `.env`, which both servers use. Open **About** in the web client's account menu
   for the running version, commit, deployment time, build time and process-start time.
   Keep this timestamp on ordinary restarts. `update.sh` records a new timestamp
   only after both replacement replicas are healthy, and preserves the earlier
   timestamps when rolling back. During a rolling update **About** describes
   the replica that served its latest information request.
5. For private access from your tailnet, follow the [shared Tailscale guide](TAILSCALE.md)
   on the NAS. It uses the same loopback gateway and provides HTTPS for Eclipse
   and the browser client.

   Alternatively, in **Control Panel → Login Portal → Advanced → Reverse Proxy**, create an
   HTTPS entry for your chosen Timekeeper hostname, targeting
   `http://127.0.0.1:8080`. Assign the hostname's certificate. Set proxy timeouts
   above 30 seconds because the API supports 25-second change polls. Test
   `/q/health/ready` and configure the client with `https://<hostname>/api/v1`.
   If using a separate reverse proxy host, bind to the NAS LAN address and
   restrict access to that proxy. The database and the gateway administrative
   socket are never published by this Compose file.

   The contract permits unusually long percent-encoded provider identities.
   HAProxy accepts the required request line size; any additional HTTPS proxy
   must also allow request lines up to 128 KiB for those extreme lookup URLs.

The server image is published by the **Server containers** workflow after successful
server/Compose tests. `edge` tracks tested `main`; semantic release tags use the
version without the leading `v`, and immutable `sha-<full-commit>` tags are also
published. Images first become available after that workflow runs on `main` or
a release tag. Use the source-build instructions below before publication.
If registry access is denied, make the repository-linked GHCR packages public,
or authenticate the NAS with `docker login ghcr.io` using an account that can
read those packages. The updater reuses Docker's registry authentication.

PR CI also supplies tested AMD64 image archives in `server-compose`, so
the DS923+ can run a candidate before registry publication. Download the artifact,
verify `checksums.sha256`, extract `timekeeper-compose.tar.gz` into `deploy`, and
import the images through Container Manager or SSH:

```sh
sha256sum -c checksums.sha256
docker load -i timekeeper-images-linux-amd64.tar.gz
```

Copy the image values from `images.env` into the deployment `.env` before
starting the project. These imported immutable candidates do not auto-update;
after the PR is merged and images are published, switch the API reference to
the GHCR channel you want to follow. Configure `.env` and run setup as described
above; deployment bundles and image archives contain no production credentials.

Keep the official PostgreSQL image on the explicit version tag in `.env`.
Updating the API does not automatically update database software.
Do not change PostgreSQL's major version against existing data files;
major upgrades require a planned migration and verified backup.

## Durable files and backups

The state directory contains `postgres`, `config`, `secrets` and `backups`.
PostgreSQL 18 stores its files under `postgres/18/docker`. Removing or recreating
containers leaves these directories in place. Keep them outside the Container
Manager project directory, and preserve them during project cleanup.
The application uses a separate, non-superuser database role.

The server database is separate from Eclipse's embedded H2 database. Existing
H2 files cannot be mounted as PostgreSQL storage. Move user data through the
API's supported export/import flow; this deployment never converts or merges
historical databases automatically.

Create a consistent logical backup:

```sh
sh backup.sh
```

The resulting directory contains a PostgreSQL custom-format dump, the token
registry, SMTP settings/password and the deployed image list. Configure a daily DSM task to run this
command, monitor failures, and back up `backups`, `config`, `.env` and `secrets`
to another host/location using Hyper Backup. Set retention for those backups.
Copying live PostgreSQL data directories is not a consistent backup.

Restore a verified backup during maintenance:

```sh
sh restore.sh /volume1/docker/timekeeper/state/backups/<backup-directory>
```

The script stops both API replicas, validates and restores schema-2/3/4/5/6 backups
into PostgreSQL in one transaction, upgrades supported older schemas to schema 6,
atomically restores the token registry and starts the replicas. A database restore failure
leaves the API stopped for inspection. It replaces current application data.
For a fresh NAS, first restore the original secrets and start the database,
then apply the logical backup. Test restores periodically on separate storage.

## Automatic server image updates

In **Control Panel → Task Scheduler**, create a scheduled **User-defined script**
with Docker access, for example every 10 minutes:

```sh
sh /volume1/docker/timekeeper/deploy/update.sh
```

The updater checks both replicas and their gateway routing, pulls the API image,
skips unchanged images and creates a logical backup. It tells HAProxy to drain
`api-1`, waits for existing requests to finish, replaces the container, and waits
for both application readiness and healthy gateway routing before touching
`api-2`. A failed replacement restores the previous images one at a time.
Original and replacement images receive immutable local
`timekeeper-deployed:<digest>` tags, retained for rollback even when a release
tag moves. Replacement and rollback use these local images without pulling.
It does not delete images or data, or restart/update PostgreSQL. Retain the
previous images for rollback and enable task failure notifications.
The `edge` channel is for tested development builds; choose a
release channel/tag for a deployment that should change only on release.
On DSM, choose `root` as the task user, or a deployment user with Docker access
and rights to the state directory. Authenticate to a private registry as that
same task user. The bootstrap container also runs as root so it can create the
private host directories and initialize their permissions.
If a task was forcibly killed and left `state/update.lock`, verify that no updater
is running before removing that empty lock directory and rerunning the task.

Automatic API updates require backward-compatible database schemas. Current
startup can upgrade canonical schema 1, 2, 3, 4 or 5 to schema 6 when explicitly enabled.
Stop both replicas and back up before this upgrade; follow the [account migration guide](ACCOUNTS.md).
Subsequent incompatible migrations must be coordinated separately with a restore plan;
rolling back an image alone cannot undo a migration.

For the first upgrade to a build supporting Work Sessions, disable scheduled
updates, take a verified backup and pause client writes while replacing both
replicas. Confirm that both replicas run the accepted Work Session build before
resuming writes. This first transition must not serve writes through a mixture
of old and new domain models. The generic SQL layout remains server schema 1;
its version marker alone does not prove that an older binary understands new
resource types or activity associations. Current builds use schema 6. Once sessions or taskless activities
have been recorded, use only server builds supporting them. Returning to a
pre-Work-Session build requires stopping client access and restoring a verified
backup from before those records existed; an image rollback alone is unsupported.
Later updates between compatible Work Session builds can use the rolling updater.

## Moving from the earlier three-node deployment

Use a logical backup and restore; the previous Patroni files cannot simply be
mounted at the new data path. This migration requires a maintenance window.

1. With the old deployment still running, run its `backup.sh`. Verify and retain
   the resulting backup, `.env`, `config` and original `secrets` outside the project.
2. Pause client access and stop the old project with `docker compose down` from
   its original deployment directory. Keep all old state directories intact.
3. Install this new bundle and set `TIMEKEEPER_STATE_DIR` to a **new empty** host
   directory. Copy the old `secrets` and `config` into it before running `setup.py`.
   Keep the PostgreSQL image set to `postgres:18.6-bookworm`.
4. Start the new project, then run its `restore.sh` with the absolute path to the
   verified old backup. Check the restored data before reopening client access.

Setup refuses to initialize over the old HA layout, and refuses to generate
replacement passwords for an existing single-instance database. It never deletes
old files. Retain the original deployment and backup until the migration is verified.

## Build and verify from source

From a source checkout:

```sh
docker build -t timekeeper-server:test -f server/Dockerfile .
docker pull postgres:18.6-bookworm
python3 -m venv /tmp/timekeeper-compose-venv
/tmp/timekeeper-compose-venv/bin/pip install -r common/api/tests/requirements.txt
/tmp/timekeeper-compose-venv/bin/python server/scripts/compose_acceptance.py
```

The test creates its own project, credentials, ports and state directories. It
checks both server replicas, all twelve HTTP contract cases, concurrent tracking,
long Unicode provider identities, exact nanoseconds, graceful and SIGKILL
database restart recovery, server failover,
durable command replay, backup/restore and a complete container teardown/restart.
It also publishes temporary images to an isolated local registry and verifies
rolling replacement and automatic rollback of a deliberately broken image while
continuous authenticated HTTP requests check availability. It verifies that the
database container remains unchanged during updates and rollback, and that
25-second change polls finish while replicas drain. Failures of either replacement
replica exercise rollback with the surviving replica still serving requests.
It never connects to the NAS's production project. Evidence is written under
`server/target/compose-acceptance`.

To run a source build locally, set the images and state path in a deployment
`.env`, run `setup.py`, then:

```sh
cd server/deploy
export TIMEKEEPER_BUILD_COMMIT="$(git rev-parse HEAD)"
export TIMEKEEPER_BUILD_DIRTY=$(if [ -z "$(git status --porcelain)" ]; then printf false; else printf true; fi)
docker compose -f compose.yaml -f compose.build.yaml build
sh set-deployment-time.sh
docker compose -f compose.yaml -f compose.build.yaml up -d
```

References: [Synology projects](https://kb.synology.com/en-global/DSM/help/ContainerManager/docker_project),
[Official PostgreSQL image](https://hub.docker.com/_/postgres),
[PostgreSQL backup](https://www.postgresql.org/docs/18/backup-dump.html).

Service settings are seeded from deployment configuration on first schema-5
startup, then edited centrally in [Administration](ACCOUNTS.md). Saved changes
require restarting both API instances. Set `TIMEKEEPER_SETTINGS_RECOVERY=true`
only for the documented offline recovery with a strictly newer epoch; remove it
before starting the other instance. Ordinary `.env` changes do not overwrite
saved service settings.
