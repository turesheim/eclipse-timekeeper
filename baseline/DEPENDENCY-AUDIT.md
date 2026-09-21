# Embedded dependency audit

Verified September 21, 2026 on macOS aarch64 with Temurin Java 21.

The plugin previously embedded JNA 5.11.0, JNA Platform 5.11.0 and FreeMarker
2.3.27. The candidate updates are JNA 5.19.1 and FreeMarker 2.3.35. The jars
were downloaded from Maven Central into a temporary isolated source copy, with
manifest, build and PDE classpath references updated there before testing.

The isolated candidate build passed all six reactor projects, 63 database tests,
10 UI/integration tests and p2 repository assembly. The repository now carries
the candidate jars and matching bundle class paths. No API changes were needed
in the idle detectors or report/template classes.

The official projects identify JNA 5.19.1 as the current release and FreeMarker
2.3.35 as the current stable release at the time of this audit. Reconfirm these
versions before a later release. Native idle behavior on Windows, Linux/X11 and
Wayland remains platform acceptance work; this audit does not certify those
native environments.

Temporary candidate build: `/private/tmp/timekeeper-deps-test` (not committed).
