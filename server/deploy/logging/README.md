# Centralized logging on Synology

This optional Container Manager project runs Grafana Alloy, Loki and Grafana
independently of Dagsverk. Alloy pulls selected containers' stdout and stderr
through a restricted Docker API proxy and sends them to Loki. Loki stores logs
on the NAS using a single process, a filesystem store and TSDB v13 indexes.
Grafana provides the Dagsverk operations dashboard, Explore and Logs Drilldown.

## Install

Use a DSM 7.2 or later NAS model with Container Manager, a Compose v2 CLI,
Docker Engine API 1.40 or later, a local Btrfs or ext4 volume, and a 64-bit
x86 (`linux/amd64`) or Arm (`linux/arm64`) CPU supported by Container Manager.
The pinned images offer both architectures. Allow **2–4 GB of RAM** for this
stack in addition to Dagsverk, DSM and other services. Size disk from measured growth and leave
headroom for compaction, backups and ingestion bursts. Check peak RAM and disk
usage during ingestion, queries and compaction before relying on that budget.

1. Put this directory in a private shared folder on the NAS. Keep the file
   layout intact. Copy `.env.example` to `.env` and set `LOG_HOST` to a stable
   NAS label and `LOG_ENVIRONMENT` to a short name such as `production`. Leave
   Grafana on loopback and syslog disabled while setting up the stack.
2. As an administrator, create the Grafana bootstrap password file:

   ```sh
   mkdir -m 0700 secrets
   (umask 077; openssl rand -hex 32 > secrets/grafana-admin-password)
   chmod 0444 secrets/grafana-admin-password
   ```

   The parent directory must be private on the NAS, including its DSM ACLs.
   Grafana's container user (UID 472) needs to read the mounted file. Project
   configuration files must be readable by their container users, with write
   access limited to administrators. Retrieve the generated password privately.
3. In **Container Manager → Project → Create**, choose a separate project name
   such as `dagsverk-logging`, select this directory, and use its
   `compose.yaml`. The other files must be present next to it on the NAS.
   Start the project. For the equivalent SSH workflow, run
   `sudo docker compose config --quiet` and `sudo docker compose up -d` from
   this directory. Use the same project name in both interfaces.
4. Reach Grafana through a private HTTPS reverse proxy or an SSH tunnel to the
   NAS's loopback port. Sign in as `admin` with the generated password.
   Add individual accounts and assign Viewer access for log consumers.
   Grafana reads the bootstrap password only when it creates its initial
   admin account. Change an existing password through Grafana.

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

## Collect inputs

### Dagsverk

The **DSM package** inherits the NAS logging driver. Check whether Docker can
read the package's output before enabling Alloy collection:

```sh
sudo docker inspect -f '{{.HostConfig.LogConfig.Type}}' timekeeper-server
sudo docker logs --tail 20 timekeeper-server
```

If Docker returns recent application logs, set
`DAGSVERK_COLLECT_DSM_PACKAGE=true` in the logging project's `.env` and
recreate Alloy. This follows the exact `timekeeper-server` container name
through package replacements. If the driver only makes logs available in
Container Manager, use its Log tab for this package. The standalone Compose
deployment below provides Docker-readable logs for automatic collection.
DSM's installer and service-management messages are separate.
[Synology package logging](../../../docs/operations/SYNOLOGY-PACKAGE.md#synchronization-diagnostics)

For the **standalone Dagsverk Compose deployment**, from `server/deploy` use
the normal deployment files and append the log labels override:

```sh
docker compose -f compose.yaml -f logging/dagsverk.compose.yaml up -d
```

Include this override on future Compose updates so the labels persist. Set
`DAGSVERK_LOG_ENVIRONMENT` for the application there if it differs from
`production`. The override sets both the JSON environment and the indexed
label on the two API replicas. For custom Docker deployments set the labels
and the environment variable yourself.

The Dagsverk image enables structured logging by default. Each application
line has `timestamp` (UTC), `level`, `loggerName`, `message`, `service_name`,
`environment`, and `version`. Existing request diagnostics include the
generated `request_id` in the message. Failures include a fixed error code,
exception category and where available SQL state and compiled stack locations.
Exception messages and raw SQL from handled API and day-boundary failures
are excluded. Access logging is off by default. Leave payload, HTTP wire and
SQL debugging disabled so upstream frameworks do not emit sensitive material.
Review each additional input before onboarding it. Preboot diagnostics emitted
by the container's shell wrapper may be plain text.

A **bare JVM** deployment can set `DAGSVERK_LOG_JSON=true` and
`DAGSVERK_LOG_ENVIRONMENT=production` in its service manager. Send its console
output to the service manager's journal or to a dedicated, access-controlled
rotating logfile. A host-local Alloy can follow that file with
[`loki.source.file`](https://grafana.com/docs/alloy/latest/reference/components/loki/loki.source.file/)
and forward it over the protected remote path described below.

### Other containers

Recreate each selected container with these labels and a bounded
`json-file` logging driver (for example `max-size: 10m`, `max-file: '3'`):

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

From an administrative SSH session, bring up the **same project** with the
syslog override. Use both files on subsequent updates while syslog is enabled:

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

Open **Dashboards → Dagsverk operations**. Set the service and environment
filters and time range. The panels display log volume, errors per second,
severity over time and recent errors. Use the Explore link for detail and
**Drilldown → Logs** to browse by service and detected level. Grafana provisions
Loki as the default data source. The default Logs Drilldown plugin may require
access to Grafana's plugin catalog when it is first installed.

```logql
{service_name="dagsverk"} | json | level="ERROR"
{service_name="dagsverk", environment="production"} |= "request_id="
{service_name="fixture"} |= "logging-probe"
{service_name="syslog"} |= "logging-probe"
```

For an acceptance run on the target NAS:

1. Start the stack, check the four services, and verify only the intended
   Grafana listener is reachable. Enable and verify syslog separately if
   wanted. Create a test container with the opt-in labels above and
   `logs.service_name: fixture`. Print a unique marker to stdout. Verify it
   appears in Grafana and that a marker from a matching unlabelled container
   does not appear. Inspect Docker's logging driver and rotation options.
2. On a disposable Dagsverk instance with a synthetic token, temporarily
   replace the configured token registry with malformed JSON and request
   `/api/v1/me` using that token. Verify the HTTP 503 and search for
   `API failure` in recent errors. Confirm the log shows the phase or origin
   and stack locations and contains neither the synthetic token nor the
   malformed input. Restore the valid registry and confirm the request succeeds.
3. Stop Alloy and Loki and make normal Dagsverk requests. Start the logging
   services, verify fresh markers are ingested and restart the project.
   Verify historical results, dashboard and Grafana accounts still exist.
4. Confirm compactor activity and `LOG_RETENTION` on the target NAS. For an
   accelerated retention exercise on a disposable project set `24h` (the
   minimum), write a synthetic entry older than 24 hours and one current
   entry into different streams, and verify only the older one expires
   after compaction and the deletion delay. Observe RAM and volume growth
   with realistic log traffic and dashboard queries before setting capacity.

Docker retains only its configured local files and Alloy retries pushes for a
finite period. Reconnecting to Docker can replay recent lines, so dashboard
counts can include duplicates. Dagsverk uses a bounded, asynchronous console
queue that can drop lines under local logging pressure. During outages, older
Docker logs can rotate away, Alloy can exhaust retries and UDP messages can be
lost. Dagsverk has no dependency on Grafana, Loki or Alloy at startup or in the
request path.

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
