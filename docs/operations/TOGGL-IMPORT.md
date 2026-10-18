# Importing Toggl Track history

Open **My Account → Import** in the web client. Imports belong exclusively to
your signed-in account. The original CSV stays unchanged; passwords and raw CSV
data are not saved in browser storage.

## Export from Track

**Prefer Toggl Track’s Legacy CSV export for Timekeeper imports.** It preserves
source client names in a `Client` column, so you can map them directly to existing
Timekeeper clients or create clients under their source names, independently of
their projects.

Both **Legacy** and **Detailed Report** CSV exports are supported. The newer
Detailed Report export we verified omits client names. When that column is
missing, work-session imports suggest client names from projects instead; review
and correct those mappings before importing.

In classic Toggl Track, select the correct source workspace, open **Reports →
Detailed**, choose the date range. Include individual
start/end dates and times and descriptions. Toggl documents CSV export as a paid-plan feature;
PDF summaries and Toggl 2.0/Focus exports are different formats.
See [Detailed Reports](https://support.toggl.com/en-us/article/detailed-report-k9bzy2/)
and [export prerequisites](https://support.toggl.com/en-us/article/exporting-data-z53uki/).

Supported headers include `Member` with `Stop date`/`Stop time`, and the legacy
`User` with `End date`/`End time`. Files must be UTF-8; a BOM, CRLF and quoted
commas/newlines are supported. Dates can be ISO `YYYY-MM-DD`, `DD/MM/YYYY` or
`MM/DD/YYYY`, as explicitly selected. Timekeeper calculates duration from Start
and End, preserving up to nine fractional second digits. The exported Duration
column is ignored, including missing, rounded or conflicting values. Summary-only
reports without individual timestamps are unsupported.

## Review and import

1. Choose **Work sessions** (the default) or **Activities**, then select either export under **Toggl CSV** and its original IANA time zone, for example `Europe/Oslo`.
   Confirm its date format. Toggl workspace columns are ignored; Timekeeper has
   no workspaces and imports only into the signed-in user's account.
2. Select one source user. This selects whose entries to copy into your current
   account; it creates no login accounts, memberships or shared workspaces.
   Review row errors, unsupported columns and excluded rows. Incomplete timers,
   future intervals and invalid timestamps require correction or exclusion.
   After reviewing diagnostics,
   select **Exclude invalid rows** beside **Review mappings** to continue with
   the valid rows.
3. Under **Client mappings**, choose an existing Timekeeper client for each
   Toggl client, or choose **Create a new client** and review its name and color.
   Several Toggl clients can use the same existing client. Reusing a client
   preserves its name and color; Undo keeps that client. Review the destinations
   again on the confirmation screen.
   Work-session imports map clients, optional source projects and labels: a
   source Client is used when present; otherwise its Project suggests a client
   name for review, with a fallback for rows missing both. Activity imports map
   clients, projects, tasks and labels and require missing-name fallbacks. Existing projects are available
   under their existing client; reusing them does not move them. Review the
   defaults for client and label colors. Equally named projects under different
   clients stay distinct unless you explicitly reuse a destination.
4. Review matching entries, then confirm the additive import. Existing records
   and running timers remain intact. In work-session mode, each row becomes one
   completed session with its original start/end, client and description. The
   description is retained as the optional summary; there is no separate title. Source tags become
   reviewed work-session labels. Source projects have reviewed mappings to new or
   existing Timekeeper projects under the selected client. Entries without a
   source project remain projectless. No tasks or activities are created alongside
   work sessions; source task values remain in the audit. In activity mode,
   descriptions become activity summaries
   independently of task names; rows without a source Task stay taskless, and
   tags become labels. Unsupported billing/rate columns are reported and omitted.

Local source times are converted to exact UTC instants. Explicit offsets resolve
repeated DST times; ambiguous or nonexistent local times require correcting the
source rather than guessing. Displayed dates use your account time zone. The
import does not change that preference. Work sessions remain continuous across
midnight and contribute to Track's work-session totals per client. Activities
split at the account zone's local midnight, preserving exact elapsed time and
legitimate overlaps; activity-only imports do not add to those session totals.
No sessions are inferred from gaps or combined from separate CSV entries.

Files are limited to 20 MiB, 100,000 source rows and 1,000 catalog mappings.
Commits use at most 100 source entries and 1,000 resulting daily activities per
batch. In activity mode, an interval producing over 1,000 daily parts must be split or excluded.
Cancelling before creating the job writes no import or business records.

## Retry, resume and repeat exports

**Import history** records committed progress and exact imported/reused source
time. Pause after the current batch, or reload and choose **Resume**. Reselect
the unchanged CSV, original source user, time conversion and row exclusions.
The original mapping plan is retained. Jobs started before work-session labels
were supported keep their original tag handling when resumed, and re-importing
previously imported sessions does not overwrite their labels. If your account
time zone changed, undo
the partial job or start a newly reviewed import.
Changed destination records also require a newly reviewed job; an existing job
cannot silently adopt a different mapping or a newer destination version.

A lost reply does not mean a batch failed: the server's job history is
authoritative. Retrying the same batch returns its durable outcome. Re-imports
and overlapping exports recognize previous source entries regardless of
filename. The import mode is fixed for each job; deliberately importing the
same source as sessions and as activities uses separate duplicate histories.
Previously imported records are reused without overwriting edits.
Stable source IDs whose contents changed require review. Without IDs,
indistinguishable rows need an explicit decision to keep separate occurrences;
existing unclaimed matches require reviewed reuse or distinct creation.

## Undo a whole import

Choose **Undo import** in history and confirm. This stops further batches of
that job and reverses all committed batches, including a partial import. Undo
itself is bounded and resumable; **Resume Undo** continues after interruption.

An upgrade that moves old work-session titles into Summary preserves automatic
Undo for otherwise untouched imported sessions. Sessions you edited still require
review before deletion.

Created work sessions or activities are removed first. Newly created catalog records are removed
only when unused; reused records and unrelated dependants stay. Edited records
require explicit **Keep** or **Delete** after reviewing current values. Running
timers and records used by another active import cannot be deleted by Undo:
keep them or resolve the dependency first. Retained created records produce a
partial outcome. Do not remove unrelated work to make Undo complete.

Undo follows all daily parts, including later automatic repartitioning. Once
all original parts are gone, deliberately importing that source again is
possible. Retained records keep duplicate protection. **Download audit** provides
a JSON record of committed outcomes and Undo decisions. Protected server
database backups preserve this history; portable work-data exports omit its
private import authority.
The audit retains each selected entry's original interval, exact source duration,
source line/ID when available, selected source descriptions/project/task/tags,
import mode and imported/reused outcome. Reused source time can
differ from the current duration of a record that was edited after import.
