# Notes and manual vault synchronization

The **Notes** tab presents an account-private folder tree and the shared Markdown
editor. Search matches names and paths; expand folders to load their children.
Drag the divider, or focus it and use arrow keys, to resize navigation. On a
phone, use the folder button to open navigation. Rename/move preserves a
resource's identity. Recently deleted items retain their bytes and can be
restored if their destination is available. Trash counts against storage limits.

Markdown source, frontmatter, unknown metadata, empty files, attachment bytes and
unsupported Obsidian syntax are stored without rewriting. An unchanged editor
save does not normalize the file. Formatted edits can normalize Markdown body
syntax; frontmatter remains separate and read-only in the editor. Use a local
source editor to change frontmatter. Wikilinks and Obsidian embeds remain literal
source; Timekeeper does not implement Obsidian's link cache or execute plugins.
Standard relative Markdown links navigate within the collection. Verified raster
images use authenticated previews; other attachments can be downloaded. Renaming
a file does not rewrite links in other files. Review relative links after moves.

## Connection and folder choices

Create a dedicated read/write API token under **My Account**. Use any nonempty
username and the token as the WebDAV password over verified HTTPS. Use a
read-only token for browsing only. Revoke the token to disconnect; local files
remain. Never put the token in a URL or share it with a public vault repository.

The hierarchical root is `https://HOST/dav/vault/`. The compatible Nextcloud-style
alias is `https://HOST/remote.php/webdav/vault/`. A selected existing subfolder,
for example `/dav/vault/Research/`, is also a synchronization root. Create that
folder in Notes before connecting. Folder listings expose stable resource IDs,
strong ETags and raw file sizes. Overwrites and deletes require current
preconditions; moves do not overwrite existing destinations. A stale request
fails with 412, an unguarded destructive request with 428.

### Ordinary Markdown folder: packaged manual client

The distribution includes `webdav_sync.py` version 1.0, a Python 3 POSIX command
line client for macOS/Linux. It has no daemon, watcher or scheduled jobs. Back up
an existing local collection before its first synchronization. Choose a private
control directory **outside** the folder, on the same filesystem. It holds the
baseline, operation journal and retained copies of replaced/deleted local files,
never the token. The client rejects symlinks, nonportable collisions, replaced
roots, incomplete listings and redirects. It requires verified HTTPS; a private
CA can be supplied with `--ca-file`.

```sh
python3 webdav_sync.py --url https://HOST/dav/vault/Research/ \
  --folder "$PWD/Research" --state-dir "$PWD/.timekeeper-research"
```

Enter the token at the password prompt, or set `WEBDAV_TOKEN` privately in the
process environment. The first command is a preview: inspect `actions`,
`conflicts`, and the `review` hash. Run the same command with `--apply HASH` to
apply exactly that reviewed snapshot. Any changed input requires a new preview.
There is no implicit first-import winner. Resolve a listed collision explicitly
with `--prefer-local 'path.md'` or `--prefer-remote 'path.md'`, preview again, then
apply its hash. Replaced local bytes are retained in the private control folder.

Later runs compare both sides against the last complete common baseline. Deletes
require a preview with `--allow-deletes`, followed by applying that preview with
the same option. Without it, no deletion is applied. Do not reset the control
folder to resolve a conflict: preserve it and both versions. After an interrupted
operation, inspect the retained files and use `--resume` to review the interrupted
intention before applying it. An unavailable server or missing local root aborts
without treating it as an empty collection. A deleted/recreated remote root
requires a fresh reviewed profile. Distinct unchanged-content renames use a
conditional MOVE when unambiguous; ambiguous renames remain explicit changes.

The client uses a complete scan for each user-triggered invocation; it is not an
incremental/background indexing service. Only use one client profile for a given
local folder. Review large deletion sets rather than approving them mechanically.

### Obsidian

The released **MySync 3.0.7** plugin with **Obsidian 1.14.5** was exercised as an
unmodified desktop release over verified HTTPS against the PostgreSQL server.
In MySync settings, choose **Nextcloud**, set the server URL to `https://HOST`,
username to a nonempty value and password to a dedicated Timekeeper API token.
Set **remote path** to `/vault` (or `/vault/Research` for a selected existing
remote folder), and **sync folder** to the vault root or the local folder you
intend to synchronize. This uses `/remote.php/webdav/vault/` on the server.

Back up an existing vault first. Run **Sync now** to collect local work, then
**Pull from remote** to establish the remote baseline. Review any first-import
conflicts in MySync before choosing a version; both local and remote versions
were retained in the acceptance test. After local edits, run **Sync now** and
**Push to remote**; after Timekeeper edits, run **Pull from remote**. No Timekeeper
daemon or CouchDB service is needed. The tested plugin exposes manual commands;
keep synchronization user-triggered while reviewing an import or conflict.

Verified cases include nested Markdown/PDFs, Unicode/plus/percent filenames,
frontmatter/wiki syntax, no-op preservation of server CRLF bytes and ETags,
empty files, rename/delete/recreate, selected remote roots, first-import and
two-device conflicts, offline retry, journal/label independence and revocation.
The plugin uploads edited text with LF line endings. It implements rename using
its upload/delete workflow, so use Notes or a guarded MOVE client when retaining
identity across a general-note rename matters. Typed journal/event association
changes remain rejected.

MySync supports Markdown, its supported text/image formats and PDFs; **it skips
unknown extensions such as `.bin`**. The test confirmed those remote files remain
intact, but they are not copied into the local vault. Use the packaged manual
client for arbitrary files or configuration trees. Do not claim a complete
arbitrary-file mirror from MySync. Its configuration synchronization is limited
to supported top-level Obsidian configuration files, not arbitrary plugin trees.
Revoke the token under My Account to disconnect; unsynchronized local drafts
remain on disk. See [reproducible acceptance](../verification/NOTES-VAULT.md).

`.obsidian` is excluded by default on both server and manual client. To transfer
it, explicitly enable **Synchronize Obsidian configuration** in Notes settings
and use `--include-obsidian` in the manual client. This synchronizes opaque bytes;
Timekeeper never loads the plugins or configuration. Disable local third-party
plugins before reviewing configuration imported from another device. Active
configuration files must be removed before the server opt-in can be disabled.

rclone, Cyberduck and Obsidian Remotely Save remain unsupported for complete
synchronization because their observed writes/deletes omit required guards.
The tested davfs2 1.6.1-1 conditional overwrite succeeds, but its delete omits
If-Match and receives 428. Listing or an initial upload is not proof of safe
synchronization; do not disable Timekeeper's guards to accommodate these clients.

## Journal and event folders

Notes settings default to `journal`, `events` and `assets`. Only direct children
of the journal folder named `YYYY-MM-DD.md` or `YYYY-Www.md` bind to daily/ISO-week
notes. Date-looking files elsewhere remain ordinary Markdown. Event files in the
event folder use the existing `event-<subscription UUID>-<occurrence key>.md`
identity and required `timekeeper-event` metadata. Their source identity cannot
be changed by moving/renaming the file. Ordinary files cannot be moved into a
reserved association; use the journal/event interface to create that resource.

Changing folder settings moves the same indexed roots atomically; it neither
copies live notes nor changes content validators. Journal and event folders must
be disjoint. Assets may be nested under one of them; changing that parent also
moves nested assets unless an explicit assets destination is supplied. Conflicting
destinations are rejected. Day labels have independent storage and versions:
clearing, deleting or synchronizing Markdown never deletes a label.

The old `/dav/journal/` and flat `/remote.php/webdav/` endpoints remain available
with their original filenames and canonical versions. Avoid pointing two sync
profiles at the same canonical journal through different aliases. Keep the old
profile until its work is synchronized and backed up, then disconnect it and
review a clean hierarchical profile. Do not blindly repoint a populated flat
local root: it would otherwise create unrelated ordinary root-level files.

Legacy `assets/name` links are resolved to the canonical asset by Timekeeper
when unambiguous, without rewriting stored bytes. Standard external Markdown
renderers use relative paths from the containing folder, so review links when
changing from the flat journal root to `journal/` or `events/` (`../assets/name`
may be required). An ordinary relative asset and a legacy asset with the same
link text is an ambiguity requiring review, not an implicit selection.

## Deployment, quotas and recovery

Schema **10** is a logical compatibility gate using the existing H2 2.4.240 or
PostgreSQL tables. Stop older replicas before authorizing a schema-9 upgrade;
older binaries must not write canonical notes without the new namespace hooks.
Use the documented offline/native/Compose upgrade and rollback procedures.
No Cassandra, CouchDB or extra service is introduced.

Deployment properties (not writable account settings):

| Property | Default |
| --- | ---: |
| `timekeeper.notes.max-file-bytes` | 16,000,000 |
| `timekeeper.notes.max-markdown-bytes` | 1,000,000 |
| `timekeeper.notes.max-total-bytes` | 500,000,000 |
| `timekeeper.notes.max-entries` | 10,000 |
| `timekeeper.notes.max-page-size` | 200 |

If increasing per-file limits, also configure the HTTP body limit and plan
client/archive capacity; changing a Notes quota does not raise HTTP limits.

Historical canonical notes are indexed without truncating over-limit content;
further growth fails with 507 until usage is reduced. Native journal/event limits
still apply. Metadata pagination uses a namespace snapshot; repeat from the first
page after 412. File bodies are fetched only when selected; tree and search reads
load metadata. Paths use NFC, portable case-collision checks, 255 UTF-8 bytes per
component, 1,024 per path, and at most 32 components.

Account archive v4 includes logical paths/IDs/settings/associations and separate
checksummed content/recovery files, plus existing canonical journal and label
records. It reads v1–v3 too. Restore requires a pristine general Notes collection
and enforces target deployment quotas; it refuses implicit collection merges.
ZIP uploads are bounded to 1 GiB, archive JSON to 256 MiB and extracted content to
3 GiB. A deployment with larger quotas must plan for those portable archive
limits. Encrypted full-instance backup retains every owner's canonical records,
derived indexes, history and resolved configuration, including these quota
properties. Continue to test restores and keep a pre-upgrade backup.
