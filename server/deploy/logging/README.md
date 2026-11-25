# Set up shared logging on Synology

This Container Manager project runs Grafana, Loki and Grafana Alloy for any
service on your NAS. Alloy collects selected Docker and syslog inputs, Loki
stores them, and Grafana provides Explore and Logs Drilldown. You can start
with an empty stack and connect services as you need them.

To connect Dagsverk to an existing stack, see
[Send Dagsverk logs to Grafana](../DAGSVERK-LOGGING.md).

## Install

Use a DSM 7.2 or later NAS model with Container Manager, a Compose v2 CLI,
Docker Engine API 1.40 or later, a local Btrfs or ext4 volume, and a 64-bit
x86 (`linux/amd64`) or Arm (`linux/arm64`) CPU supported by Container Manager.
The pinned images offer both architectures. Allow **2–4 GB of RAM** for this
stack in addition to DSM and the applications it hosts. Size disk from
measured growth and leave
headroom for compaction, backups and ingestion bursts. Check peak RAM and disk
usage during ingestion, queries and compaction before relying on that budget.

### 1. Copy the project folder

Copy the contents of this directory into a private NAS folder such as
`/volume1/docker/centralized-logging`. Keep the subfolders and filenames intact.
The project reads `loki.yaml`, `docker-logs.cfg`, `alloy/`, and `grafana/`
from that folder when the containers start.

### 2. Create the settings and password

For a fresh installation, open an administrative SSH session on the NAS and
run these commands once.

```sh
sudo -i
cd /volume1/docker/centralized-logging
cp .env.example .env
mkdir -m 0700 secrets &&
  (umask 077; openssl rand -hex 32 > secrets/grafana-admin-password) &&
  chmod 0444 secrets/grafana-admin-password
```

Use your actual NAS folder in the `cd` command. In `.env`, set `LOG_HOST`
to a stable name for the NAS. Keep the `secrets/` directory private, including
its DSM ACL. Grafana's container user (UID 472) needs to read the mounted file.
Treat its contents as the initial Grafana admin password.

### 3. Create the Container Manager project

Open **Container Manager → Project → Create**.

- **Project name** — `centralized-logging`
- **Path** — the NAS folder where you copied the project
- **Source** — upload `compose.yaml` from your copy of the folder

Leave the Web Station portal disabled and start the project. Expect four
services named `grafana`, `loki`, `alloy` and `docker-logs`. The wizard takes
one Compose file. Loki and Grafana read their configuration YAMLs from the
folder automatically. [Synology project wizard](https://kb.synology.com/en-global/DSM/help/ContainerManager/docker_project)

To start the project from SSH, run these commands in the same folder.

```sh
sudo docker compose -f compose.yaml config --quiet
sudo docker compose -f compose.yaml up -d
```

If you already created the stack as `dagsverk-logging`, keep that project
name in Container Manager and set `COMPOSE_PROJECT_NAME=dagsverk-logging` in
its `.env` for CLI commands. Docker uses the project name to locate the
existing volumes and starting with a different name creates separate volumes.

### 4. Sign in to Grafana

With the default loopback binding, open an SSH tunnel from your computer.

```sh
ssh -N -L 3000:127.0.0.1:3000 your-dsm-user@nas-address
```

While the tunnel is open, visit [http://localhost:3000](http://localhost:3000)
and sign in as `admin` with the password in `secrets/grafana-admin-password`.
Grafana reads the bootstrap password when it creates its initial admin
account. Manage passwords and Viewer access in Grafana after the first login.
To use a private HTTPS address instead, see the access settings below.

### Restrict access

Keep Grafana's published port bound to `127.0.0.1` for a NAS-local reverse
proxy. For example, configure DSM's reverse proxy to forward HTTPS for a
private hostname to `http://127.0.0.1:3000` and set `GRAFANA_ROOT_URL` to that
HTTPS origin with a trailing slash. Use a trusted certificate. On a Tailscale
deployment, limit access with tailnet grants to the intended users and
devices. A direct LAN or tailnet bind requires changing
`GRAFANA_BIND_ADDRESS` to that specific interface address and permitting only
trusted clients at the firewall. Use HTTPS when sending credentials across
the network. Avoid port forwarding the UI or receivers from the internet.

Check access from both an allowed client and a disallowed client. Docker's
port forwarding can interact with or bypass ordinary host firewall rules.
Docker versions before 28 can also expose some localhost-published ports
to peers on the same L2 segment. If the NAS cannot enforce the boundary, use
upstream filtering or a trusted dedicated network. Only Grafana is published
by the base project. Loki, Alloy's UI and the Docker proxy stay private.
Grafana requires authentication, and Grafana users with access to the Loki
data source can query all collected services. Apply the same trust boundary
to the NAS folders, logs and backups.

The proxy runs as root inside its container to read the host's Docker socket. A
read-only bind mount by itself does not make Docker API calls read-only. The
proxy limits requests to Docker discovery, inspection and log reads via
GET/HEAD, publishes no host port, and shares its network only with Alloy.
Its read access still exposes logs and inspect data, including container
environment variables, from the whole Docker host if the collector or proxy
is compromised. If your security model forbids that access, use a separate
collector with access only to dedicated app log files, or forward selected
logs via syslog and omit the Docker collector and proxy.

## Connect log sources

### Docker containers

Recreate each selected container with these labels and a bounded
`json-file` logging driver (for example `max-size: 10m`, `max-file: '3'`).

```yaml
labels:
  logs.collect: 'true'
  logs.service_name: my-service
  logs.environment: production
```

Use stable lowercase service and optional environment labels. Alloy falls
back to the logging project's environment if the container has no valid
environment label. The NAS name comes from `LOG_HOST`. Container IDs,
account identifiers and request identifiers are never promoted to indexed
labels. Each producer is responsible for the content of its log lines.

### Optional DSM and external syslog

From an administrative SSH session in the logging folder, add the syslog
override to the same project. Use both files for subsequent CLI updates
while syslog is enabled.

```sh
sudo docker compose -f compose.yaml -f compose.syslog.yaml up -d
```

In DSM **Log Center → Log Sending**, enable sending, enter `127.0.0.1` and
port `1514`, and select **UDP** with **IETF (RFC 5424)** format. Choose only
log categories appropriate for centralized retention. Use the DSM test-send
option where available and query `{service_name="syslog"}` in Explore. Alloy
retains the RFC 5424 message and uses its timestamp. Verify the exact format,
time and message on the target NAS. [Synology Log Sending](https://kb.synology.com/en-us/DSM/help/LogCenter/logcenter_client?version=7)

UDP syslog is unauthenticated, unencrypted and lossy. For remote senders, bind
`SYSLOG_BIND_ADDRESS` to a private VPN interface and allow UDP 1514 only from
approved sources. Test the firewall from a disallowed host. The `host` label
identifies the receiving NAS. Sender hostnames are in the line. To disable
ingestion, recreate Alloy with only `compose.yaml`.

For a remote Alloy, run a separate collector local to the source and point
its `loki.write` endpoint to an authenticated HTTPS reverse proxy dedicated
to `POST /loki/api/v1/push`. Attach the proxy to this project's `logs` network
so it can reach `http://loki:3100` internally. Use a private link or VPN,
verify certificates, restrict client identities and keep credentials in the
remote collector's secret store. Give the remote input its own stable `host`
label. This project ships with no published push endpoint or OTLP receiver.

## Search and verify

Open **Explore** in Grafana and select **Loki**. Use
**Drilldown → Logs** to browse by service and detected level. A fresh stack
shows results after a source has been enabled and emitted logs. The Logs
Drilldown plugin may need access to Grafana's plugin catalog at first start.

```logql
{service_name="fixture"} |= "logging-probe"
{service_name="syslog"} |= "logging-probe"
```

Run these checks on the target NAS.

1. Start the stack, check the four services and verify the Grafana listener
   is reachable only where intended. Test syslog separately if enabled.
2. Create a test container with the opt-in labels above and
   `logs.service_name: fixture`. Print a unique marker to stdout. Verify it
   appears in Grafana and that a marker from a matching unlabelled container
   is absent. Inspect Docker's logging driver and rotation options.
3. Restart the project. Verify Grafana login, historical results and fresh
   ingestion.
4. Confirm compactor activity and `LOG_RETENTION` on the target NAS. For an
   accelerated retention exercise on a disposable project set `24h` (the
   minimum), write a synthetic entry older than 24 hours and one current
   entry into different streams, and verify only the older one expires
   after compaction and the deletion delay. Observe RAM and volume growth
   with realistic log traffic and queries before setting capacity.

Docker retains only its configured local files and Alloy retries pushes for a
finite period. Reconnecting to Docker can replay recent lines. During outages,
older Docker logs can rotate away, Alloy can exhaust retries, and UDP messages
can be lost.

## Maintain and recover

- The project uses Docker-managed named volumes for Loki chunks, WAL, indexes
  and compactor state (`loki-data`), Grafana's SQLite database and plugins
  (`grafana-data`), and Alloy offsets (`alloy-data`). Use
  `sudo docker volume inspect <project>_<volume>` to locate the DSM-managed
  mountpoints. Keep these on local NAS storage. When restoring, keep numeric
  ownership and modes. The pinned images use UIDs 10001 for Loki, 472 for
  Grafana and 473 for Alloy.
- Loki's compactor retains 30 days by default (`LOG_RETENTION=720h`).
  Keep the 24-hour TSDB index period and set retention to at least 24 hours.
  Deletion is asynchronous and retention does not cap disk size. Configure DSM
  space alerts and monitor filesystem and volume growth. Review Loki logs for
  compactor, write and out-of-space errors.
- Back up configuration, the Grafana secret and all three named volumes.
  Stop the logging project first to take a consistent snapshot, or use an
  ownership-preserving offline archive. Store the backup under the same
  access restrictions as the live data. To restore, create the project with
  the backed-up configuration, populate its stopped volumes with the
  matching data, then start and verify a historical query, Grafana login and
  fresh ingestion. Avoid deleting the volumes during normal upgrades.
- Before an upgrade, back up, review the upstream release notes, update the
  image pins deliberately, pull, recreate and rerun the acceptance checks.
  Preserve Loki's existing schema entries when adding a future schema.
  Roll back from a compatible snapshot if a new version changes on-disk data.
- If logs are missing, inspect `docker compose ps` and
  `docker compose logs --tail=100 alloy docker-logs loki grafana`. Check the
  container's exact labels, the Docker logging driver, Grafana's time range
  and the NAS clock. Use the Grafana Loki data source health check to confirm
  Loki is reachable. If Grafana exits, check permissions on its password file
  and data volume. If a receiver is enabled, confirm its port binding and
  source ACLs. Keep logs from the base Compose deployment available for
  diagnosis while the centralized stack is offline.
