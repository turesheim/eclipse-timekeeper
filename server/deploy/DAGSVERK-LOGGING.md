# Send Dagsverk logs to Grafana

Dagsverk containers write logs to Docker. These steps connect Dagsverk to
an existing Grafana, Loki and Alloy stack. If you need a stack,
[set up shared logging on Synology](logging/README.md) first.

## Synology package

The **DSM package** inherits the NAS logging driver. Check whether Docker can
read the package's output before enabling Alloy collection.

```sh
sudo docker inspect -f '{{.HostConfig.LogConfig.Type}}' timekeeper-server
sudo docker logs --tail 20 timekeeper-server
```

If Docker returns recent application logs, set
`DAGSVERK_COLLECT_DSM_PACKAGE=true` in the logging project's `.env` and
recreate Alloy from the logging folder.

```sh
sudo docker compose -f compose.yaml up -d alloy
```

Include `-f compose.syslog.yaml` when syslog is enabled. Alloy follows the exact
`timekeeper-server` container name through package replacements. If the driver only makes
logs available in Container Manager, use its Log tab for this package. The standalone Compose
deployment below provides Docker-readable logs for automatic collection.
DSM's installer and service-management messages are separate.
[Synology package logging](../../docs/operations/SYNOLOGY-PACKAGE.md#synchronization-diagnostics)

## Dagsverk Compose deployment

Use the usual Dagsverk Compose files and append the log labels override. Run
the command from `server/deploy`.

```sh
docker compose -f compose.yaml -f logging/dagsverk.compose.yaml up -d
```

Include this override on future Compose updates so the labels persist. Set
`DAGSVERK_LOG_ENVIRONMENT` for the application there if it differs from
`production`. The override sets both the JSON environment and the indexed
label on the two API replicas. For custom Docker deployments set the labels
and the environment variable yourself.

## Bare JVM deployment

A **bare JVM** deployment can set `DAGSVERK_LOG_JSON=true` and
`DAGSVERK_LOG_ENVIRONMENT=production` in its service manager. Send its console
output to the service manager's journal or to a dedicated, access-controlled
rotating logfile. A host-local Alloy can follow that file with
[`loki.source.file`](https://grafana.com/docs/alloy/latest/reference/components/loki/loki.source.file/)
and forward it over the [protected remote path](logging/README.md#optional-dsm-and-external-syslog).

## Log format

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

## Find and verify Dagsverk logs

Open **Dashboards → Dagsverk operations** in the shared Grafana instance.
Choose the service, environment and time range. The dashboard shows log
volume, errors per second, severity over time and recent errors. Open
**Explore** for individual lines and request IDs.

```logql
{service_name="dagsverk"} | json | level="ERROR"
{service_name="dagsverk", environment="production"} |= "request_id="
```

1. On a disposable Dagsverk instance with a synthetic token, temporarily
   replace the configured token registry with malformed JSON and request
   `/api/v1/me` using that token. Verify HTTP 503 and find `API failure` in
   Grafana. Confirm the event includes compiled stack locations and its
   request ID matches the response header. Search for the synthetic token
   and malformed input to confirm both are absent. Restore the valid
   registry and verify the request succeeds.
2. Stop Alloy and Loki and make normal Dagsverk requests. Start the logging
   services again and check that fresh Dagsverk logs arrive in Grafana.

Dagsverk writes to a bounded asynchronous console queue and can drop lines
under local logging pressure. The application continues to operate during
collector outages.
