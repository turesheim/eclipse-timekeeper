# Headless p2 provisioning acceptance

Checked October 11, 2026 on Eclipse Platform 4.41, Temurin 21.0.12.1 and
macOS aarch64, using isolated installations and databases.

## Failure and correction

Installing the optional Mylyn integration into an installation that already
contained the base Timekeeper feature completed successfully, but logged
Timekeeper startup failures. The immediate Declarative Services component
loaded the database bundle during p2 director startup. Its activator then tried
to use instance preferences and `ResourcesPlugin.getWorkspace()` before a
workspace existed. This produced `IllegalStateException`, bundle activation
failures and an unavailable embedded service.

The embedded service now uses Declarative Services' delayed activation. It is
advertised without constructing the persistence implementation until a client
requests it. Headless provisioning does not need the application service or
database; the normal UI service tracker still obtains it when the workbench
starts.

## Installed-package evidence

- The original main artifact, version `2.0.0.202610111346`, installed into a
  pristine Eclipse Platform 4.41 without Mylyn. Workweek opened; a native
  project and task were created. The project remained visible after restart.
- JiraConnector `6.0.0.202605160149` and the optional Timekeeper Mylyn feature
  resolved and installed together with Mylyn 4.12. The Jira repository was
  `https://gnl42.github.io/JiraConnector-update/releases/6.0.0.202605160149/`.
  This is provisioning evidence, not a completed Jira runtime acceptance run.
- The corrected base and optional features, version `2.0.0.202610111403`,
  replaced the original features in one p2 transaction.
- A subsequent p2 director launch **without `-data` or a database URL override**
  completed without Timekeeper startup exceptions. The log contained no
  database startup, unresolved component or bundle activation errors.
- A second workspace-free launch with `-listInstalledRoots` confirmed both
  corrected Timekeeper features and JiraConnector 6.0.0 remained installed.
- `./mvnw -B -ntp verify -Dtycho.localArtifacts=ignore` passed the complete
  local suite: 3 domain, 6 service, 81 database, 6 native-only UI and 20 Mylyn
  UI tests. Native CRUD/activity recording and provider reconnection continue
  to work with delayed service activation.

Local evidence is retained under
`/private/tmp/timekeeper-2-release-runtime/`: `jira-install.log` reproduces the
original failure, `jira-update.log` records the update, and
`jira-headless-after.log` records the clean headless launch. These temporary
files are not distributed release assets.

To repeat the headless check after installing a candidate, run its Eclipse
launcher with Java 21, `-nosplash -consoleLog`,
`-application org.eclipse.equinox.p2.director -listInstalledRoots`, and an
isolated `-Duser.home` after `-vmargs`. Do not supply `-data`; inspect the
console and configuration log for the startup failures above. Then launch the
workbench with an isolated workspace/database and exercise native CRUD and
activity recording to check normal service activation.

## Remaining release acceptance

This check does not resolve the Mylyn theme-parser finding in
[ERROR-LOG.md](ERROR-LOG.md), certify Jira Cloud/Data Center behavior, or verify
installation from the eventual published GitHub Pages release repository.
Those checks remain separate release gates.
