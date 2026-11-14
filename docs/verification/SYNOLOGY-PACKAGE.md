# Earlier Synology package acceptance

Preserved from the operator guide at source `072974b6` during the documentation
cleanup. The candidate and test descriptions below are historical evidence;
no new NAS acceptance is claimed. Current procedures are in the
[operator guide](../operations/SYNOLOGY-PACKAGE.md) and
[packaging guide](../development/SYNOLOGY-PACKAGING.md).



Automated PostgreSQL 17 schema/migration tests and an unprivileged installer test
against real PostgreSQL verify role privileges, retained credentials, interrupted
provisioning, explicit owner mapping and absence of plaintext credentials in SQL
statement logs. The static project is created before runtime files are supplied,
then the real server and its selected-port healthcheck are started against native
loopback PostgreSQL; migrated passwords must authenticate as their original owners. Native PostgreSQL 17 backup/restore and corrupt-archive refusal are
also tested in the isolated package layout. A real PostgreSQL 18.6 → 17.11 transfer
compares all 14 tables with two owners, shared resource UUIDs, nanosecond timestamps,
revisions above JavaScript's exact integer range and authentication state. A
fingerprint mismatch rolls back the entire import; used targets are refused.
These do not emulate DSM Package Center
or its resource workers.

Candidate **1.99.0-140** passed installation, 133 → 140 upgrade, start/stop,
retained-data uninstall/reinstall and automatic startup after a NAS reboot on the
recorded target. Native migration and restore matched all 14 table fingerprints;
web and shared Eclipse HTTP client access and user isolation were checked over
Tailscale HTTPS. Installed Eclipse UI tests passed in CI. The optional managed
Tailscale integration was added afterwards: its read-only detection, conflict checks and manual setup/cleanup
instructions have automated tests, with wizard validation tracked in PR #300. These checks do not constitute public-feed acceptance.
Repository discovery and public release publication are separate future work. Before a public package feed is
enabled, its discovery/update behavior must be tested; SynoCommunity catalog
inclusion also requires upstream acceptance. An independent Timekeeper feed
would require users to configure SynoCommunity separately.

The packaging follows Synology's [Docker Project worker](https://help.synology.com/developer-guide/resource_acquisition/docker-project.html),
[permanent appdata layout](https://help.synology.com/developer-guide/integrate_dsm/fhs.html)
and [package privilege model](https://help.synology.com/developer-guide/privilege/privilege_config.html).
PostgreSQL connectivity follows [SynoCommunity's package documentation](https://docs.synocommunity.com/packages/postgresql/).
