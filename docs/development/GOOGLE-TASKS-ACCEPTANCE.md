# Google Tasks connector acceptance

This guide verifies the Google connector for issue #621. The
[automatic synchronization specification](../architecture/AUTOMATIC-TASK-SYNC.md)
supersedes the original issue’s routine first-import/outgoing preview workflow:
subscriptions synchronize automatically, with review for exceptions. Read-only
OAuth access still prohibits provider writes. Microsoft acceptance is separate.

Use Java 21, Python 3.13, Node 24 and the Maven wrapper. Synthetic tests do not
contact Google or establish consent/behavior for a particular live deployment.

## Automated checks

Run the [affected quality checks](AGENT-WORKFLOW.md), then the focused connector
suite when investigating this boundary:

```sh
./mvnw -B -ntp -f server/pom.xml -pl app -am -Dtest=TaskProviderOAuthTest,TaskCloudProviderTest,TaskCloudPullTest,TaskProviderConnectionsTest,TaskProviderScansTest,GoogleTasksWriterTest,TaskProviderWritesTest -Dsurefire.failIfNoSpecifiedTests=false test
```

| Acceptance boundary | Existing fixture |
| --- | --- |
| Account-owned OAuth, scopes, reconnect, pause, disconnect, generation fences | `TaskProviderOAuthTest`, `TaskProviderConnectionsTest`, `TaskProviderResourceHttpTest` |
| All Google task states, opaque IDs, pages, civil scheduled dates, source context | `TaskCloudProviderTest`, `TaskCloudPullTest` |
| Acknowledged pages, overlap, full reconciliation, hierarchy and independent project mappings | `TaskProviderScansTest` |
| Conditional writes, supported field deltas and unsupported representation limits | `GoogleTasksWriterTest`, `TaskProviderHttpTest` |
| Automatic create/edit/completion/reopen/move/delete, no echo, conflicts and unknown results | `TaskProviderWritesTest` |
| Cross-owner/list boundaries and retained local fields/history | Pull, scan and write tests above |
| Owned account/list selection, one Save, access, status and exceptional decisions in both languages | `task-providers.spec.js`, `tasks.spec.js` |

`automaticGoogleScheduleAndCompletionRoundTripKeepsTheLocalDeadlineProjectAndIdentity`
crosses the domain command, durable intent, actual Google writer and incoming
reconciliation boundaries. It checks a conditional outbound civil-day/completion
PATCH, remote edits/reopening, a stable native identity/project, a retained local
timed deadline, and no repeated provider mutation. The adjacent read-only test
asserts that local edits cannot create an outbound provider operation.

Verify restart through two distinct JVMs and persistent storage:

```sh
python3 server/scripts/task_provider_restart.py --h2 --output server/target/verification/google-task-restart-h2.json
python3 server/scripts/task_provider_restart.py --output server/target/verification/google-task-restart-pg17.json
python3 server/scripts/task_provider_restart.py --postgres-image postgres:18.6-bookworm --output server/target/verification/google-task-restart-pg18.json
```

The PostgreSQL checks require Docker. They resume acknowledged pages and unsent
intents, preserve acknowledgements, and recover expired sending leases as unknown
without resending. Their JSON identifies the source and both JVMs. CI runs both
PostgreSQL versions. They do not simulate a power cut at every instruction.

Build a fresh clean distribution and run HTTPS browser acceptance as described in
[browser acceptance](WEB-ACCEPTANCE.md), selecting `task-providers.spec.js` and
`tasks.spec.js` in Chromium and WebKit. Retain each run’s source/checksum receipt;
do not count a stale package or a skipped live check as current evidence.

## Live round trip — opt in

Use a disposable Google list and synthetic tasks. Perform this only with the
account owner’s consent to the listed provider changes. Keep unrelated lists
unselected. Do not put OAuth/API tokens, account IDs, task contents or browser
traces in a committed report. User-observed results can be recorded explicitly
as user verification, with the build if known.

1. Record the installed Dagsverk build/source, browser, date and account zone.
   Complete [OAuth registration and Verify configuration](../operations/SHARED-SIGN-IN.md).
2. Connect Google with Tasks and **Read only**. Select the disposable list,
   choose **Save**, and verify initial import without a calendar subscription.
   Include an undated task and a task with a scheduled civil day.
3. Change a title/completion at Google and refresh or wait for polling. Verify
   the same Dagsverk task changes. A local edit must leave Google unchanged.
4. Reconnect with **Two-way synchronization** and approve write consent. Make a
   title, plain-note and scheduled-day change in Dagsverk. Verify Google receives
   the supported values without a separate Prepare/Send action. A local timed
   deadline must remain local and independent of the scheduled day.
5. Complete the task in Dagsverk and verify Google. Reopen it and change a title
   at Google; verify the same native task reopens in Dagsverk without duplicates.
6. Create a parent/child in the selected synchronized list and check supported
   placement/order. A separate project neighbor without list assignment must
   remain local. Check supported list moves only between disposable selected lists.
7. Pause, make a local edit and verify no new provider write starts. Resume and
   check convergence. Restart the test deployment only if independently
   authorized; otherwise rely on the persistent synthetic restart fixture above.
8. Deliberately delete a disposable unassigned task and verify the other side,
   no recreation. Retention of recorded work is checked in the synthetic fixtures
   above; this live procedure does not create work sessions or activities. Leave conflicts/uncertain outcomes
   visible and resolve them through the account UI; never resend to guess an
   unknown creation result. Destructive permission-loss/conflict fault injection
   belongs in the synthetic fixtures unless separately authorized.
9. Deselect/disconnect the test list and verify imported data remains. Clean up
   only the synthetic provider tasks/list whose removal was authorized.

Record each step as passed, failed, not performed or user-verified. Include
actual behavior and recovery, not credentials or assertions inferred from a
successful OAuth login. A successful OAuth login or import alone does not establish a
round trip. Keep any remaining live gaps explicit in the PR.

### Maintainer confirmation

On 2026-11-24 the maintainer reported that the Google Tasks connector was
working in use. This is an overall confirmation. The per-scenario round-trip
results come from the automated fixtures and CI recorded in [PR #696](https://github.com/turesheim/eclipse-timekeeper/pull/696).

## Provider contract

Google documents [read-only/write scopes](https://developers.google.com/workspace/tasks/auth),
[date-only schedules and task fields](https://developers.google.com/workspace/tasks/reference/rest/v1/tasks),
[page and completed/hidden/deleted/assigned filters](https://developers.google.com/workspace/tasks/reference/rest/v1/tasks/list),
and [placement/list-move restrictions](https://developers.google.com/workspace/tasks/reference/rest/v1/tasks/move).
These contracts bound the connector; local-only fields must survive synchronization.
