# Centralized logging verification on local Docker

On 25 November 2026, the logging extra from commit `10bd11f8` ran on
Docker Engine 29.4.0 and Compose 5.1.1 on Linux arm64 (Docker Desktop).
A packaged Dagsverk server from the same commit ran in a Java 21 container
with synthetic data and its JSON console enabled.

| Check | Observed result |
| --- | --- |
| Standalone Compose project | Grafana, Loki, Alloy and the Docker socket proxy passed their health checks. Grafana bound to loopback. Loki had no published port. The optional syslog input published only its configured loopback UDP port. |
| Docker opt-in and RFC 5424 | Loki returned a marker from a labeled Alpine container and a separate UDP syslog marker. A matching container without the opt-in label was excluded. |
| Restart | The same Loki query returned the historical marker after a restart of the project. |
| Dagsverk diagnostics | Authenticated Grafana API calls found the provisioned Dagsverk dashboard and Loki source. A query filtered by service, environment and severity returned a deliberate HTTP 401 with its request ID. The synthetic bearer marker was absent from Loki and the application console logs. The application emitted the documented JSON fields. |
| Bounded Docker rotation | Docker reported `json-file` with `max-size=10m` and `max-file=3` on the Dagsverk fixture. |
| Collector outage | Dagsverk readiness and its authentication status endpoint both returned HTTP 200 after Alloy and Loki stopped. |

## Retention expiry

A separate Docker fixture ran the pinned Loki 3.7.10 image with the
repository's TSDB and filesystem configuration. It used a 24-hour retention
window, a five-second compaction interval and a one-second deletion delay.
The fixture allowed queries of older entries still held by the ingester and
flushed both entries before restarting Loki to recover the TSDB index.

A current entry and a 72-hour-old entry were both queryable after the flush.
After the restart, the old entry returned zero results and its chunk file
was gone. The recent entry returned one result and its chunk file remained.
The retention sweeper reported processing two marker files.

Representative log volume, retention expiry on the NAS, DSM Log Center forwarding and
the Container Manager installation, restart and access controls still need
verification on a supported Synology NAS. See the [target NAS checks in the
operator guide](../../extras/centralised-synology-logging/README.md#search-and-verify).
