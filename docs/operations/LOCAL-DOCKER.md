# Run Timekeeper locally with Docker

This runs one PostgreSQL database, two Timekeeper server instances and HAProxy
on your computer. The API is available at `http://127.0.0.1:8080/api/v1`.
Database files and credentials live in a host directory outside the checkout.

Use [Docker Desktop or Docker Engine with the Compose plugin](https://docs.docker.com/compose/install/).
The commands below use a Bash shell; on Windows, use WSL. You also
need Git and Python 3. Java and Maven run inside the Docker build.

## 1. Get the deployment files

For a new checkout:

```sh
git clone https://github.com/turesheim/eclipse-timekeeper.git
cd eclipse-timekeeper/server/deploy
```

If you already have the repository, open `server/deploy` in that checkout.
Confirm Docker is running:

```sh
docker version
docker compose version
```

Run the remaining commands from `server/deploy`.

## 2. Configure persistent storage

For a fresh installation, create `.env` with these values. If `.env` already
exists, edit it instead of replacing your existing settings.

```sh
cat > .env <<EOF
COMPOSE_PROJECT_NAME=timekeeper-local
TIMEKEEPER_STATE_DIR="$HOME/.local/share/timekeeper-server/state"
TIMEKEEPER_BIND_ADDRESS=127.0.0.1
TIMEKEEPER_HTTP_PORT=8080
TIMEKEEPER_SERVER_IMAGE=timekeeper-server:local
TIMEKEEPER_POSTGRES_IMAGE=postgres:18.6-bookworm
EOF
```

Choose a different HTTP port if 8080 is occupied. Use an absolute, writable
path for `TIMEKEEPER_STATE_DIR`; keep the same path when restarting or rebuilding.
On Docker Desktop, allow file sharing for that directory if requested.

Create credentials and the initial account:

```sh
python3 setup.py --owner alice
```

Replace `alice` with your stable account identifier. Save the bearer token
printed by setup: it is shown once and is used by the client. Setup generates
the database passwords and a token registry containing only the token hash.
Running setup again preserves existing credentials and data. Changing `--owner`
on a second run does not replace the existing token registry.

## 3. Build and start

Build the server from your checkout, then start the regular Compose deployment:

```sh
export TIMEKEEPER_BUILD_COMMIT="$(git rev-parse HEAD)"
export TIMEKEEPER_BUILD_DIRTY=$(if [ -z "$(git status --porcelain)" ]; then printf false; else printf true; fi)
docker compose -f compose.yaml -f compose.build.yaml build api-1
sh set-deployment-time.sh
docker compose up -d --wait --wait-timeout 180
docker compose ps
```

Both server instances use the same built image. Docker downloads the official
PostgreSQL and HAProxy images as needed. The first build needs internet access
and can take a few minutes. All four services should become healthy.

The web client's footer identifies the running version and source commit. Open
its details to see when it was built, deployed and last started. The build
commands record whether the checkout had modified source. `set-deployment-time.sh`
records the deployment time in `.env` for both replicas; use it when deploying a
new build, and keep the existing timestamp when simply restarting services.

If startup fails, inspect the logs:

```sh
docker compose logs --tail=100 postgres api-1 api-2 gateway
```

## 4. Open the web interface and connect Eclipse

Check readiness:

```sh
curl --fail --silent --show-error http://127.0.0.1:8080/q/health/ready
```

The response's `status` should be `UP`. If you changed the port, use that
port in all URLs below.

Open **http://127.0.0.1:8080/web/** in your browser and sign in with the bearer
token saved during setup. The web client is bundled in the server image; no
separate frontend service is needed. The token stays in memory for that tab,
so reloading the page or signing out ends the session.

To check the account, enter the saved token at the prompt. These Bash commands
keep it out of shell history and curl's command-line arguments:

```sh
printf 'Bearer token: '
read -r -s TIMEKEEPER_TOKEN
printf '\n'
printf 'Authorization: Bearer %s\n' "$TIMEKEEPER_TOKEN" \
  | curl --fail --silent --show-error --header @- http://127.0.0.1:8080/api/v1/me
unset TIMEKEEPER_TOKEN
```

The response's `ownerId` should match the account chosen during setup.

In Eclipse, open **Preferences → Timekeeper → Backend**, select **Timekeeper
server**, and set:

- **Server URL:** `http://127.0.0.1:8080/api/v1`
- **Token:** the bearer token from setup

Apply the settings and restart Eclipse. Tokens are kept in Eclipse secure
storage. Existing local Eclipse records remain in their original database;
selecting the server does not import them.

The Docker listener is local to its host. For other devices in your tailnet,
use the [shared Tailscale guide](TAILSCALE.md). Other remote deployments use an HTTPS reverse
proxy as described in the [deployment guide](DEPLOYMENT.md).

## 5. Reach the test server through Tailscale

Use the [shared Tailscale access guide](TAILSCALE.md) to reach the server from
other devices in your tailnet over HTTPS. The same setup works on Synology.
Keep `TIMEKEEPER_BIND_ADDRESS=127.0.0.1` in `.env`; Serve runs on the Docker host
and forwards requests to the existing local gateway.

## 6. Stop, restart and back up

```sh
docker compose stop
docker compose start
```

To remove the containers while retaining their host-mounted data:

```sh
docker compose down
docker compose up -d --wait --wait-timeout 180
```

The state directory contains `postgres`, `config`, `secrets` and `backups`.
Keep `.env` and the original secrets along with the data. To make a consistent
logical backup while the database is running:

```sh
sh backup.sh
```

The script prints the backup directory. Restore during maintenance using its
absolute path:

```sh
sh restore.sh /absolute/path/to/state/backups/<backup-directory>
```

Restore replaces current application data and stops both server instances for
the operation. Do not change PostgreSQL's major version against existing files;
database upgrades require a planned migration.

## Updating the server

For an image built locally, rebuild after updating your checkout:

```sh
export TIMEKEEPER_BUILD_COMMIT="$(git rev-parse HEAD)"
export TIMEKEEPER_BUILD_DIRTY=$(if [ -z "$(git status --porcelain)" ]; then printf false; else printf true; fi)
docker compose -f compose.yaml -f compose.build.yaml build api-1
sh backup.sh
sh set-deployment-time.sh
docker compose up -d --no-deps --wait --wait-timeout 180 api-1 api-2
```

This development update can briefly interrupt requests. For rolling updates,
use a server image in a registry: once the server image is published, set
`TIMEKEEPER_SERVER_IMAGE=ghcr.io/turesheim/eclipse-timekeeper-server:edge` in
`.env`, then run:

```sh
sh update.sh
```

The updater pulls the configured image, backs up the database, drains one
server at a time and waits for application health and gateway routing before
updating the other. Failed replacements restore the previous images.
PostgreSQL stays running. Run `update.sh` again whenever you want to check for
a newer image, or schedule it using your operating system's scheduler.

The updater pulls from a registry; it cannot update the local-only
`timekeeper-server:local` tag. `edge` follows tested main builds. For a fixed
version, use an immutable `sha-<full-commit>` image tag. If GHCR access is denied,
authenticate with `docker login ghcr.io` using an account with package access,
or use the source-build setup above.
