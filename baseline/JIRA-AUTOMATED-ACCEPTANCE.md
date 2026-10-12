# Automatic Jira connector acceptance (#230)

The `jira-connector-tests` Maven profile adds a separate test plugin and target
without changing `default.target` or making Jira a production dependency.
Linux PR/main builds and the release p2 build run this profile automatically.
The existing native-only runtime continues to verify operation without Mylyn.

## Actual connector and provisioning

The target pins JiraConnector **6.0.0.202605160149** from
`https://gnl42.github.io/JiraConnector-update/releases/6.0.0.202605160149/` and
uses the same dated Eclipse 2026-09 repository as the main target. Jira's
additional Mylyn Context and Confluence components are test-only dependencies.

Tycho's `p2Installed` runtime installs Eclipse Platform, the base and optional
Timekeeper features, the released Jira feature and the host `slf4j.simple`
2.0.18 provider. Tests inspect the installed connector bundle/version, require
resolved Jira bundles and exactly one SLF4J service-provider declaration.
The test plugin, connector and fixture are not included in the release site.

## Automatic coverage

`JiraConnectorTest` uses the registered, released repository connector, its
real REST client and attribute mapper, Mylyn's task model/editor/activity
manager and Timekeeper's public client API. It does not replace the connector
with a stub or directly call Timekeeper's activation handler.

A strict loopback-only HTTP fixture supplies synthetic Jira REST responses:

- Basic credentials and bearer-token header validation, including a `/jira`
  context path; rejected credentials return HTTP 401 and create no native task.
- Actual repository query, full issue retrieval and opening a Mylyn task editor.
- Numeric issue identity, issue key, summary, browse URL, project display name,
  real custom-field mapping and missing/null/blank-value **None** grouping.
- Activation, reactivation, task switching and deactivation through Mylyn's
  activity manager, with interval and owner checks in Timekeeper. Mylyn's
  `activateTask` deliberately deactivates/re-activates an already active task,
  yielding a new interval. Duplicate/late notification idempotence remains
  covered by the existing adapter fixtures.
- Deliberate HTTP 503 and issue 404 responses; offline history remains available
  to editing, labels, activity reports and CSV export.
- Reconnection after an issue-key change, preserving native UUID and history.

Unknown REST routes and unexpected mutations fail the test. Icon resources are
served locally; no Jira account, external test service or credentials are
required. Requests are retained with HTTP status and authentication mode,
without the Authorization header contents. Deliberate HTTP failures are
classified separately and do not authorize unrelated Timekeeper errors.

`JiraRestartTest` runs through a second Surefire execution in a **different
Eclipse process and workspace**, reopening the same file-backed H2 database.
It checks different process IDs, the saved task/activity UUIDs, interval end,
summary and labels, then reconnects to the same repository URL and numeric issue
ID through the real connector. Keeping the loopback port stable across the two
processes preserves the repository namespace; bind failure is a test failure.

## Run and evidence

```sh
./mvnw -B -ntp clean verify -Pjira-connector-tests -Dtycho.localArtifacts=ignore
```

Use Java 21; on Linux wrap this command in Xvfb, as in `build.yml`. Use `clean`
to remove a previous test installation and qualified bundle versions.

Evidence is retained under `net.resheim.eclipse.timekeeper.jira-test/target/`:

- `surefire-reports/`: connector tests.
- `surefire-reports-restart/`: second-process restart test.
- `acceptance-data/installed-versions.txt`: installed baseline and p2 source.
- `acceptance-data/rest-*.txt`: actual fixture request/status traces.
- `acceptance-data/expected-http-errors.txt`: expected logged HTTP failures.
- `acceptance-data/restart.properties` and `restart-verified.txt`: first-process
  identifiers and second-process verification.
- `work/**/.metadata/.log`: Eclipse runtime logs, when produced.

GitHub Actions uploads these alongside the existing test results. Current-schema
CSV evidence and the synthetic H2 file are generated locally for assertions;
they contain test-only data.

## Local verification

A clean Java 21 / macOS aarch64 build passed **127 tests**: 3 domain, 6 service,
81 database, 6 native-only UI, 26 Mylyn UI, 4 actual-connector cases and 1
second-process restart case. The publication helper's 10 tests and actionlint
also passed. The built release site contains no Jira or test plugins.

The first process recorded task/activity UUIDs and labels; the second process
reopened the same file-backed H2 schema and re-queried the issue through the
released connector. The generated evidence records both process IDs and exact
installed versions. CI repeats these checks on Linux/X11; its run is linked in
the pull request.

## Scope of the result

These are automatic conformance tests with the **actual released connector and
a synthetic REST server**, not certification of a deployed Jira service.
Live Jira Cloud authentication/query behavior, Data Center versions/permissions,
network/proxy/SSO configuration and optional platform behavior remain unverified
unless separately tested. In particular, the Basic fixture's Cloud deployment
label does not prove compatibility with Atlassian's live Cloud API.

The known Mylyn `E4ThemeColor` Error Log entry is not fixed by these tests.
The test suite checks Jira and Timekeeper errors during its scenarios; the
existing dependency-error gate remains in [ERROR-LOG.md](ERROR-LOG.md).
Issue #230 must retain these distinctions when its acceptance checklist is
updated. A green fixture run alone does not close every release acceptance item.
