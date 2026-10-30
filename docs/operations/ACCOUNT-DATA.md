# Download and restore your account data

Open **My Account → Your data**. Downloads and restores always belong to the
signed-in account. Administrator access does not include anyone else's records.

## Download

In **Download my data**, select **Prepare archive**. This creates and fetches a
consistent snapshot without opening a browser download. When it is ready, select
**Download archive** to save `timekeeper-account-YYYY-MM-DD.zip`; the date is the
server's UTC preparation date. Preparing again replaces the previous browser
copy. Leaving the page releases that copy; prepare again if you return later.
Read-only sessions can also prepare and download archives.

The private ZIP contains:

- `archive.json`: a versioned snapshot for restoring work and preferences, with a checksummed file index.
- `manifest.json`: archive identity, generation time, record counts and file descriptions.
- CSV files for clients, projects, tasks, labels, activities and work sessions.
- `README.txt`: format and privacy notes.

The snapshot includes all work records, project/client links, task relationships,
labels, descriptions, external references, exact timestamps, running intervals,
time-zone preferences and import audit history. Version 3 archives store day labels independently in `archive.json.dayLabels`,
including labels on days without a note and explicitly cleared labels. They keep day notes
as separate `YYYY-MM-DD.md` files and weekly notes as `YYYY-Www.md`, including
notes without timed work or a visible Calendar label. Markdown and frontmatter
are preserved exactly. Journal attachments are binary files under `assets/`, so
relative Markdown links work after extraction. Task attachments are binary files
under `assets/task attachments/<attachment-id>.bin`; their original filenames and metadata
remain in the task attachment records. Their JSON records and CSV entries
reference these files instead of embedding binary content.

The `journalFiles` index in `archive.json` records each separate file's path,
byte length and SHA-256 checksum. Restore verifies every indexed file and rejects
missing, altered, duplicate or unexpected entries. Files are read one at a time
without extracting ZIP paths to disk or assembling all bodies into one JSON object.
The snapshot also includes your account
identity, recovery addresses and credential-management metadata for reference.
Passwords and hashes, API/session token material, passkey keys/credential IDs,
recovery codes/links and deployment settings or SMTP secrets are excluded.

One consistent snapshot supplies every file. Ordinary list pagination and the
older transfer endpoint's 10,000-record/20 MiB limits do not truncate this download.
CSV cells are quoted and potential spreadsheet formulas receive an apostrophe;
JSON retains the original values. The ZIP is unencrypted; keep it on private
encrypted storage and protect its backups. See [journal storage and key
management](JOURNAL-STORAGE.md) for database, temporary-file and backup protection.

## Review and restore

1. Select the original Timekeeper ZIP. A version 2 or 3 archive with separate files
   requires the complete ZIP; its extracted `archive.json` alone is rejected.
   Legacy version 1 ZIPs and standalone JSON archives remain supported.
2. Choose whether to restore the archived time zone. The default keeps your
   current preference.
3. Select **Review archive**. Check the record counts, source account, time zone
   and any running-timer conflicts.
4. Select **Import reviewed records** to add the reviewed records.

All imported work receives new IDs, with client/project/task/label links remapped
together. Existing work remains. Your username, role, recovery addresses and
sign-in credentials remain under this account's control; the source account's
metadata is informational. Imported history is an audit copy and cannot authorize
old import jobs or Undo operations.

Day notes retain their calendar dates. Matching notes can be restored again; a
different existing note on the same date is reported as a conflict during review
and is never overwritten. Legacy label-only notes and archives created before day notes remain supported.
An omitted legacy body never erases the Markdown of an existing matching note.
Weekly notes retain their ISO week keys and are restored only when an existing
weekly note is identical or absent.
Attachments keep their filenames. A different existing attachment with the same
name is a review conflict; no file is silently replaced.

Closed intervals retain exact instants and nanoseconds. A source running activity
or work session can be restored when the corresponding timer is available in this
account. Stop a conflicting current timer and review again. Activities continue
to follow the account's local-midnight partitioning.

If your work or preference changes after review, review the archive again.
Validation and restore are atomic: rejected input leaves existing work and
preferences intact. Each archive identity restores at most once into an account,
including across server restarts, lost replies, changed preference choices and
ZIP/extracted-JSON selection. A changed document reusing an already restored
archive identity is rejected; keep the original file.

If the result is unknown, use **Check import result**. Timekeeper reconciles the
recorded result before allowing another attempt. Reloading or selecting the same
archive again also reconciles a completed restore.

The review lists record counts and the client, project, task, label and session
links to preserve. It validates these links before importing; linked records
receive new IDs together.

## Uploads and limits

Uploads are private to your account and can resume after a lost reply or reload:
select the unchanged file and choose **Review archive** again. Pending uploads
expire after 24 hours. **Discard upload** clears staging data and keeps existing
work records.

Restore accepts ZIP files up to 1 GiB and uncompressed JSON up to 256 MiB, up to 250,000 records
of each work type and 1,000,000 import audit records. Oversized input is rejected
explicitly. At most three uploads and 512 MiB of declared upload data can be
pending for one account. Read-only sessions can download their account and cannot
stage or restore an archive.

Use [protected deployment backups](DEPLOYMENT.md) or the
[Synology backup workflow](SYNOLOGY-PACKAGE.md) for disaster recovery, including
account credentials and service configuration. For Toggl CSV, use the separate
[Toggl Track import](TOGGL-IMPORT.md).

Day-label/note diagnostic history is included as private `ARCHIVE_AUDIT`
evidence in `importHistory`, with `data.kind: DAY_NOTE_HISTORY`, without bodies or authentication secrets. Restoring
an archive keeps source evidence as inert audit copies and records new local
restore events. Supplied event identities or client claims are never installed
as locally authenticated history. See [API diagnostics](API-DIAGNOSTICS.md) for
retention, legacy gaps and inspecting source versus local evidence.

### Notes and vaults in archive v4

The complete Notes namespace, stable IDs, configured roots, canonical
journal/event references, general Markdown/binary files and retained trash are
included. Raw general/recovery bytes are separate checksummed ZIP entries;
canonical journals/assets retain the existing portable framing. Versions v1–v3
remain readable. A restore rebuilds account-private derived indexes and enforces
the destination quotas. It requires a pristine general Notes collection and
refuses an implicit collection merge. Day labels remain separate. See
[Notes recovery limits and transition](NOTES-WEBDAV.md).
