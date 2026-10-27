# Download and restore your account data

Open **My Account → Your data**. Downloads and restores always belong to the
signed-in account. Administrator access does not include anyone else's records.

## Download

**Download my data** creates a private ZIP containing:

- `archive.json`: a versioned snapshot for restoring work and preferences.
- `manifest.json`: archive identity, generation time, record counts and file descriptions.
- CSV files for clients, projects, tasks, labels, activities and work sessions.
- `README.txt`: format and privacy notes.

The snapshot includes all work records, project/client links, task relationships,
labels, descriptions, external references, exact timestamps, running intervals,
time-zone preferences and import audit history. Web day notes are included as
optional `dayNotes` in `archive.json`, including their complete canonical
Markdown documents even on dates with no timed work or visible Calendar label.
The optional `journalAssets` array contains image and file attachments as base64.
It also includes your account
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

1. Select the original Timekeeper ZIP or its extracted `archive.json`.
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

Restore accepts files and uncompressed JSON up to 256 MiB, up to 250,000 records
of each work type and 1,000,000 import audit records. Oversized input is rejected
explicitly. At most three uploads and 512 MiB of declared upload data can be
pending for one account. Read-only sessions can download their account and cannot
stage or restore an archive.

Use [protected deployment backups](DEPLOYMENT.md) or the
[Synology backup workflow](SYNOLOGY-PACKAGE.md) for disaster recovery, including
account credentials and service configuration. For Toggl CSV, use the separate
[Toggl Track import](TOGGL-IMPORT.md).
