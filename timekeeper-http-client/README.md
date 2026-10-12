# Timekeeper HTTP client

This Java 21 module implements `TimekeeperService` over the owner-scoped
`/api/v1` API. It contains no Eclipse, SWT, Mylyn, JPA or database access. Eclipse
dispatches it through background Jobs; synchronous calls belong on workers.

In Eclipse, **Preferences → Timekeeper → Backend** selects a local database or
server. Enter the server URL and token, then restart Eclipse. Tokens use Equinox
encrypted storage. Server mode keeps the embedded database closed. A failed
connection retains the last display snapshots and reports an error. Eclipse
automatically retries the connection after the problem is resolved; **Workweek →
Reconnect / refresh backend** also requests an immediate refresh.

Changes committed by another authorized session automatically refresh Workweek
and cached reports. Open editor drafts retain their original expected version;
if another session edits the same record, Save reports a conflict and asks you
to close and reopen the editor to review the current values. Reconnection reads
the current server state without repeating earlier commands.

The optional Mylyn adapter stores separate mappings for each backend/account.
Native Task List projections stay associated with their original scope; selecting
another server or account does not import those projections. Return to their
original backend to activate them. Provider tasks may be explicitly linked in
another scope without reusing that scope's UUIDs.

## Server CSV transfer

**Preferences → Timekeeper → Database** exports the signed-in server account
into a new `Timekeeper-export-<UUID>` subfolder of the chosen directory. Select
that subfolder when importing. Local database location controls are disabled in
server mode.

The CSV directory represents the domain transfer model:

| File | Contents |
| --- | --- |
| `projects.csv` | Project IDs and names |
| `tasks.csv` | Task IDs, project/parent IDs, summary, URL and external references |
| `labels.csv` | Label IDs, names and colors |
| `activities.csv` | Activity/task IDs, exact start/end instants, summary, labels and manual flag |
| `timekeeper.csv.json` | Format identifier and calendar zone |

Files use UTF-8 and quoted CSV fields. Unquoted empty fields represent null;
quoted empty fields represent empty text. Nested external references and label
ID lists use JSON inside the quoted field. Export writes the descriptor last,
so an interrupted export cannot be imported as a complete snapshot.

This is the server domain CSV format, separate from the existing embedded H2
table export. The embedded mode retains its existing CSV import/export format.
Neither format is copied automatically when selecting another backend.

Import creates fresh IDs in the signed-in account and remaps the complete
project/task/activity/label graph. Existing records are retained. The server
validates and commits the whole import atomically; a conflict, including an
already-linked provider reference, rolls back every change.

Unresolved imports retain a command key under the Eclipse plug-in's workspace
state directory. The journal contains only UUID command keys and hashed
backend/owner/payload scope, without tokens or records. Retrying the same CSV
data against the same backend/account, including after restarting the client,
reuses that key. Successful imports clear it. After a lost response, keep the
CSV files unchanged until the retry confirms the result.

The API permits at most 10,000 records of each type and 20 MiB of JSON data.
CSV input is bounded separately at 48 MiB to allow quoting overhead. Remote
transfer runs on a cancellable worker and does not open an embedded database.
