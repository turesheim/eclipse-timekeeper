# Jira connector compatibility acceptance

Issue [#230](https://github.com/turesheim/eclipse-timekeeper/issues/230),
checked October 11, 2026 on macOS aarch64. Jira remains an optional Mylyn
provider; the base Timekeeper feature does not require the connector.

## Installed baseline

| Component | Verified version |
| --- | --- |
| Eclipse Platform | 4.41.0.I20260828-1142 |
| Java | Temurin 21.0.12.1 |
| Jira connector feature/core/UI and REST client API/core | 6.0.0.202605160149 |
| Mylyn Tasks core | 4.12.0.v20260826-1729 |
| Mylyn Tasks UI | 4.12.0.v20260721-0835 |
| Mylyn Commons UI | 4.12.0.v20260708-0909 |
| Host SLF4J provider | slf4j.simple 2.0.18 |

The upstream stable update site is
`https://gnl42.github.io/JiraConnector-update/`; the exact repository tested was
`https://gnl42.github.io/JiraConnector-update/releases/6.0.0.202605160149/`.
Its connector Mylyn requirements accept versions from 4.7 up to, but excluding,
5.0. The base and optional Timekeeper features installed and updated alongside
the released connector on Eclipse 4.41. Only one installed JAR declares an
SLF4J service provider, the host's `slf4j.simple`.

The installed candidate `2.0.0.202610111428` opened the real Jira repository
wizard and displayed the **Timekeeper** contribution. **Custom field** enabled
the grouping attribute input, and `customfield_10001` appeared in that input.
The wizard was cancelled without configuring credentials, connecting to Jira
or creating a repository. No workspace Error Log was created in that run.

## Metadata contract

The released connector uses kind `jira`. Its numeric issue ID is the stable
external identity; its human-readable issue key is separate. Timekeeper keeps
the numeric ID in the provider/repository/task identity and uses the issue key
for Workweek presentation. A moved issue can therefore change key, summary and
browse URL while retaining its native UUID and recorded intervals.

Jira's project attribute is Mylyn `TaskAttribute.PRODUCT`
(`task.common.product`): its value is a project ID and its options contain
project names. Custom fields use the prefix `attribute.jira.custom::`, followed
by the REST field ID, for example `customfield_10001`. The released core JAR's
constants and project attribute were inspected, in addition to these upstream
sources:

- [JiraCorePlugin](https://github.com/gnl42/JiraConnector/blob/main/jira.core/src/me/glindholm/connector/eclipse/internal/jira/core/JiraCorePlugin.java)
- [JiraConstants](https://github.com/gnl42/JiraConnector/blob/main/jira.core/src/me/glindholm/connector/eclipse/internal/jira/core/JiraConstants.java)
- [JiraAttribute](https://github.com/gnl42/JiraConnector/blob/main/jira.core/src/me/glindholm/connector/eclipse/internal/jira/core/JiraAttribute.java)
- [JiraTaskDataHandler](https://github.com/gnl42/JiraConnector/blob/main/jira.core/src/me/glindholm/connector/eclipse/internal/jira/core/JiraTaskDataHandler.java)

New imports default to the Jira project's display name. Repository settings
can instead select a Mylyn category or a custom field. Selected option labels
are used, including multiple values; a missing, blank or unavailable grouping
value yields **None**, addressing issue #62. Existing native project assignments
are preserved, including manually moved tasks and offline history.

When a task is unavailable in Task List, the optional adapter can derive its
key from the stored `/browse/<key>` URL. Missing or malformed URLs retain the
base UI's stable external-ID fallback. No Jira library or kind-specific code
is added to the base feature, persistence model or client service.

## Automated coverage

`JiraCompatibilityTest` uses JiraConnector 6-shaped task-data fixtures and Mylyn
objects. It does not contact Jira or depend on credentials. Five regressions
cover:

- Project display names, numeric IDs, issue keys, summaries and browse URLs.
- Missing, empty and unavailable custom fields; shorthand/full attribute IDs;
  multi-value options and raw-value fallback when no option label exists.
- Explicit category grouping without cached task data.
- Settings validation, persistence, restoration and resetting to the default.
- Duplicate activation, issue switching, late deactivation, reconnection after
  a key change, retained native UUID/history, Workweek issue-key rendering,
  offline activity editing/labels and CSV export of time and external identity.

The complete local suite passed with 122 tests: 3 domain, 6 service,
81 database, 6 native-only UI and 26 Mylyn UI tests. The native-only runtime
continues to verify that Mylyn/Jira are not required for native CRUD and tracking.
Build logs and installed-runtime evidence are retained temporarily under
`/private/tmp/timekeeper-jira-2-build*.log` and
`/private/tmp/timekeeper-2-release-runtime/`.

## Open acceptance gates

| Check | Status |
| --- | --- |
| Actual connector provisioning, restart and repository settings contribution | Passed on the baseline above |
| Timekeeper metadata, identity, tracking and offline regressions | Passed with fixtures |
| Live Jira query, issue editor and activation through the released connector | Not yet tested |
| Jira Cloud API-token authentication | Not yet tested |
| Jira Server/Data Center password/PAT authentication | Not yet tested |
| Installed restart with real queried issues and retained history | Not yet tested |
| Mylyn Task List theme-parser Error Log | Reproduced separately; still open |
| Published GitHub p2 installation of the final release | Pending publication |

The real connector wizard offers password and access-token authentication;
its access-token help specifies Jira Server 8.14 or newer. This records the
available control, not a successful authentication test. Timekeeper delegates
credential handling to the connector and does not publish untested Cloud or
Data Center support claims.

The known Mylyn `E4ThemeColor` exception occurs when Task List constructs its
tooltip colors. It is not resolved by the Jira compatibility changes; see
[ERROR-LOG.md](ERROR-LOG.md). Issue #230 and stable-release acceptance remain
open until the live-connector checks and dependency-error gate are resolved.
