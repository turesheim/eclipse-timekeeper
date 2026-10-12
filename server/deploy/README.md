# Timekeeper on Synology Container Manager

This deployment runs two stateless Timekeeper servers, three PostgreSQL 18 nodes
managed by Patroni, three etcd members for leader election, and HAProxy for HTTP
load balancing and routing database connections to the current primary.
It targets the DS923+ (AMD64), DSM 7.4.1 and Container Manager. The published
server and database images support Linux AMD64 and ARM64.

Each PostgreSQL node has its own files. Acknowledged writes are synchronously
replicated to at least one standby, and automatic promotion is restricted to
eligible synchronized nodes. If no synchronous standby is available, writes
wait rather than silently falling back to asynchronous durability. Normal
primary failure causes a brief interruption while Patroni promotes a standby;
clients should retry a failed command with its original command ID.

All containers on this NAS share its power, disks, Docker daemon and operating
system. This setup tolerates individual container/member failures; it does not
provide availability during a NAS failure. HAProxy is also a single gateway.
For availability across host failures, distribute database/etcd members and API
replicas across independent hosts with redundant ingress, using an orchestrator
or a managed PostgreSQL HA service. Running these same containers in VMM on the
same NAS does not remove those shared failure points.

## Install on the NAS

1. Install **Container Manager** in Package Center. Create
   `/volume1/docker/timekeeper/deploy` and `/volume1/docker/timekeeper/state`.
   Copy this deployment bundle into the `deploy` directory. Keep the state
   directory outside the Container Manager project directory.
2. Copy `.env.example` to `.env`. Keep
   `TIMEKEEPER_STATE_DIR=/volume1/docker/timekeeper/state`; adjust it if using a
   different NAS volume. The HTTP listener defaults to `127.0.0.1:8080` for DSM's
   HTTPS reverse proxy. Change the port if it is occupied.
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

   Save the generated bearer token in the Eclipse client's credential store.
   It is shown once; the registry stores only its hash. Setup preserves existing
   secrets, tokens and databases when run again. Do not regenerate missing
   database credentials for an existing cluster: restore the original files.
4. In **Container Manager → Project → Create**, set the project name to
   `timekeeper` (matching `compose.yaml` and `.env`), choose the `deploy` directory
   and its `compose.yaml`, then start the project. The equivalent SSH command is:

   ```sh
   docker compose pull
   docker compose up -d
   docker compose ps
   ```

   On older Compose clients, `docker compose pull` may include the optional
   maintenance client. This is harmless. Allow a few minutes for the first
   PostgreSQL clones to initialize and for all health checks to become healthy.
5. In **Control Panel → Login Portal → Advanced → Reverse Proxy**, create an
   HTTPS entry for your chosen Timekeeper hostname, targeting
   `http://127.0.0.1:8080`. Assign the hostname's certificate. Set proxy timeouts
   above 30 seconds because the API supports 25-second change polls. Test
   `/q/health/ready` and configure the client with `https://<hostname>/api/v1`.
   If using a separate reverse proxy host, bind to the NAS LAN address and
   restrict access to that proxy. The database, etcd and Patroni management
   ports are never published by this Compose file.

   The contract permits unusually long percent-encoded provider identities.
   HAProxy accepts the required request line size; any additional HTTPS proxy
   must also allow request lines up to 128 KiB for those extreme lookup URLs.

The images are published by the **Server containers** workflow after successful
server/Compose tests. `edge` tracks tested `main`; semantic release tags use the
version without the leading `v`, and immutable `sha-<full-commit>` tags are also
published. Images first become available after that workflow runs on `main` or
a release tag. Use the source-build instructions below before publication.
If registry access is denied, make the repository-linked GHCR packages public,
or authenticate the NAS with `docker login ghcr.io` using an account that can
read those packages. The updater reuses Docker's registry authentication.

PR CI also supplies tested AMD64 image archives in `server-compose-evidence`, so
the DS923+ can run a candidate before registry publication. Download the artifact,
verify `checksums.sha256`, extract `timekeeper-compose.tar.gz` into `deploy`, and
import the images through Container Manager or SSH:

```sh
sha256sum -c checksums.sha256
docker load -i timekeeper-images-linux-amd64.tar.gz
```

Copy the two image values from `images.env` into the deployment `.env` before
starting the project. These imported immutable candidates do not auto-update;
after the PR is merged and images are published, switch the API reference to
the GHCR channel you want to follow. Configure `.env` and run setup as described
above; deployment bundles and image archives contain no production credentials.

Keep the PostgreSQL image on an explicit `sha-<commit>` or version tag after the
initial installation. Updating the API does not automatically update database
software. Do not change PostgreSQL's major version against existing data files;
major upgrades require a planned migration and verified backup.

## Durable files and backups

The state directory contains `postgres-1`, `postgres-2`, `postgres-3`, `etcd-1`,
`etcd-2`, `etcd-3`, `config`, `secrets` and `backups`. Removing or recreating the
containers leaves these directories in place. Do not delete them using File
Station or project cleanup. The database directories must remain separate.

The server database is separate from Eclipse's embedded H2 database. Existing
H2 files cannot be mounted as PostgreSQL storage. Move user data through the
API's supported export/import flow; this deployment never converts or merges
historical databases automatically.

Create a consistent logical backup through the current primary:

```sh
sh backup.sh
```

The resulting directory contains a PostgreSQL custom-format dump, the token
registry and the deployed image list. Configure a daily DSM task to run this
command, monitor failures, and back up `backups`, `config`, `.env` and `secrets`
to another host/location using Hyper Backup. Set retention for those backups.
Replication does not protect against accidental deletion or a bad application
change. Copying live PostgreSQL data directories is not a consistent backup.

Restore a verified backup during maintenance:

```sh
sh restore.sh /volume1/docker/timekeeper/state/backups/<backup-directory>
```

The script stops both API replicas, restores into the current PostgreSQL primary,
atomically restores the token registry and starts the replicas. A restore failure
leaves the API stopped for inspection. It replaces current application data.
For a fresh NAS/cluster, first restore the original secrets and start the cluster,
then apply the logical backup. Test restores periodically on separate storage.

## Automatic server image updates

In **Control Panel → Task Scheduler**, create a scheduled **User-defined script**
with Docker access, for example every 10 minutes:

```sh
sh /volume1/docker/timekeeper/deploy/update.sh
```

The updater checks both current replicas, pulls the API image, skips unchanged
images, creates a logical backup, updates `api-1`, waits for its readiness, and
then updates `api-2`. A failed replacement restores both previous image IDs.
It does not delete images or data and does not modify the PostgreSQL/etcd
cluster. Retain the previous images for rollback and enable task failure
notifications. The `edge` channel is for tested development builds; choose a
release channel/tag for a deployment that should change only on release.
If a task was forcibly killed and left `state/update.lock`, verify that no updater
is running before removing that empty lock directory and rerunning the task.

Automatic API updates require backward-compatible database schemas. Current
startup validates schema version 1 and does not upgrade existing schemas.
An incompatible image fails readiness and is rolled back. A future schema
migration must be coordinated separately, with a tested database restore plan;
rolling back an image alone cannot undo a migration.

View cluster membership and sync state from any running database member:

```sh
docker compose exec postgres-1 curl -fsS http://localhost:8008/cluster
docker compose exec postgres-1 psql -U postgres -c \
  'SELECT application_name,state,sync_state FROM pg_stat_replication'
```

Choose another member if `postgres-1` is down. Update persistent Patroni settings
using `patronictl`/the authenticated API; editing `bootstrap.dcs` in an image
only changes new clusters. Keep quorum (at least two etcd members) available.

## Build and verify from source

From a source checkout:

```sh
docker build -t timekeeper-server:test -f server/Dockerfile .
docker build -t timekeeper-postgres:test -f server/deploy/postgres/Dockerfile .
python3 -m venv /tmp/timekeeper-compose-venv
/tmp/timekeeper-compose-venv/bin/pip install -r api/tests/requirements.txt
/tmp/timekeeper-compose-venv/bin/python server/scripts/compose_acceptance.py
```

The test creates its own project, credentials, ports and state directories. It
checks both server replicas, all twelve HTTP contract cases, concurrent tracking,
long Unicode provider identities, exact nanoseconds, primary/server failover,
durable command replay, backup/restore and a complete container teardown/restart.
It also publishes temporary images to an isolated local registry and verifies
rolling replacement and automatic rollback of a deliberately broken image.
It never connects to the NAS's production project. Evidence is written under
`server/target/compose-acceptance`.

To run a source build locally, set the images and state path in a deployment
`.env`, run `setup.py`, then:

```sh
cd server/deploy
docker compose -f compose.yaml -f compose.build.yaml build
docker compose -f compose.yaml -f compose.build.yaml up -d
```

References: [Synology projects](https://kb.synology.com/en-global/DSM/help/ContainerManager/docker_project),
[Patroni replication](https://patroni.readthedocs.io/en/latest/replication_modes.html),
[PostgreSQL backup](https://www.postgresql.org/docs/18/backup-dump.html).
