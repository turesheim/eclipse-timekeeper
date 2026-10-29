# Journal WebDAV endpoint

The journal collection is `/dav/journal/` on the Timekeeper server. Each
`YYYY-MM-DD.md` day note or `YYYY-Www.md` ISO weekly note belongs to the account identified by the API token,
independent of the supplied WebDAV username. Use an API token with `read` and
`write` permissions as the HTTP Basic password. A read-only token can list and
download notes. The endpoint requires HTTPS outside loopback development.
Timekeeper also accepts the private HTTP hop behind a TLS reverse proxy when
`TIMEKEEPER_PUBLIC_ORIGIN` names its exact HTTPS authority. Restrict direct
access to that backend network, as in the supported Synology deployment.

Week numbers use the ISO week-based year, so dates around New Year may belong to
the previous or following year's weekly file. The collection supports `OPTIONS` and `PROPFIND`; note files support those
methods plus `GET`, `HEAD`, `PUT`, and `DELETE`. A present empty note is listed
as a zero-byte file. A deleted note is absent. Note-body edits in Calendar and REST share the WebDAV version. Day-label edits
have an independent version and do not invalidate WebDAV ETags. Replacing or
deleting a Markdown file preserves the day label. Existing `timekeeper-label`
frontmatter is inert legacy metadata, preserved byte-for-byte when untouched;
changing it no longer sets or clears the independent Calendar label. Current
account archives restore labels separately from note files.

The `assets/` child collection holds images and other files referenced by
Markdown. For example, an image link to `assets/photo.png` in `2026-10-25.md` resolves
to `/dav/journal/assets/photo.png`. Files have one account-scoped name each and
are visible only with that account's token. `assets/` supports listing,
`GET`/`HEAD`, `PUT` and `DELETE`; existing files require `If-Match` for changes
and deletion. New files can use `If-None-Match: *`. Only PNG, JPEG, GIF and WebP
with matching file signatures are displayed inline; other files download as
attachments. The web editor creates unique names when inserting an image or
attachment. Each file is limited to 5 MB, with at most 1,000 active files and
100 MB total per account. The root collection and `assets/` cannot be deleted.

For an existing file, send its strong ETag in `If-Match` when replacing or
deleting it. A stale ETag receives `412 Precondition Failed`. For creation,
`If-None-Match: *` prevents replacing an existing file. An unconditional `PUT`
can also create an absent file or retry an identical document; an unconditional
change to an existing file receives `428 Precondition Required`. This avoids
silently overwriting a Calendar edit. Read the current file and ETag, resolve
any conflict, then retry with `If-Match`.

An explicit delete leaves a versioned tombstone. A later create can restore
that date. As with `If-None-Match: *`, the server cannot tell an intentional
recreation from a stale client's upload after a delete; clients must track
their local deletions and issue `DELETE` deliberately.

[Milton's usual server entry point](https://github.com/miltonio/milton2/blob/master/milton-server-ce/src/main/java/io/milton/servlet/MiltonFilter.java)
is a Servlet filter. Timekeeper uses Quarkus REST, so these bounded journal
collections use [Quarkus REST custom HTTP methods](https://quarkus.io/version/3.40/guides/rest/#declaring-endpoints-http-methods)
without adding a Servlet stack or another authentication path.

## Client compatibility

The following results come from actual clients against an isolated packaged
Timekeeper server. MySync uses HTTPS with a trusted fixture CA; rclone and
Cyberduck were tested over loopback HTTP. Deployments must use HTTPS.

| Client | Verified behavior | Result |
| --- | --- | --- |
| Obsidian 1.14.5 with unmodified MySync 3.0.7 | Actual desktop clients over verified HTTPS: initial pull and push, daily and weekly notes, legacy records, empty files, guarded overwrite/delete, recreation, unchanged server bytes/ETags, two-device conflict resolution, offline retry, owner isolation and token revocation. | **Bidirectional journal synchronization verified** on Linux. Edited text uploads use LF; see setup and limits below. |
| rclone 1.75.2, WebDAV vendor `other` | List, download, and create worked. Initial `bisync --resync` uploaded a note. A subsequent local edit led to an unconditional overwrite, which Timekeeper rejected with `428`; bisync aborted and requested another resync. | **No bidirectional support** with this version. Do not use `bisync` for this collection. |
| Cyberduck CLI (`duck`) 9.5.4.45528 | List, upload a new note, and byte-exact download worked. Overwrite and delete omitted `If-Match` and received `428`. The overwrite command reported an error in its output yet exited with status 0. | **Read and create only**. Do not rely on its process exit status to detect a rejected overwrite. |

Obsidian Remotely Save has not been run against this endpoint. Its
[WebDAV implementation](https://github.com/remotely-save/remotely-save/blob/master/src/fsWebdav.ts)
creates a vault subdirectory and calls `putFileContents` with `overwrite: true`
without a per-file `If-Match`. This suggests that it will not support the
fixed, one-level journal collection or guarded updates without changes to the
plugin. This is an inference from its source, not an interoperability result.

### Obsidian with MySync

Use a dedicated journal vault containing dated daily notes, ISO weekly notes,
and an optional `assets/` folder. Arbitrary Obsidian vault synchronization is
outside this endpoint's scope.

1. Install [MySync 3.0.7](https://github.com/henriquemanduca/mysync-plugin/releases/tag/3.0.7)
   in Obsidian and enable the community plugin.
2. Select MySync's **Nextcloud** backend. Set its server URL to the Timekeeper
   origin, for example `https://timekeeper.example.org`, without `/dav/journal/`.
3. Set the remote path to `/`, select the vault root as the local sync folder,
   and leave Obsidian configuration synchronization disabled.
4. Use any username and a dedicated Timekeeper API token with `read` and `write`
   permissions as the password. Pull the remote notes before editing an existing
   journal on a new device, then use MySync's indexing and push/pull commands.
5. Resolve reported conflicts in MySync before retrying. A rejected write is not
   a successful synchronization. Keep both versions until choosing the intended
   document.

MySync constructs `/remote.php/webdav/` URLs. Timekeeper exposes that finite
collection alias, including `assets/`, through exactly the same authenticated
resources as `/dav/journal/`. Both paths share owner isolation, canonical data,
ETags and conditional writes; no Nextcloud service or second document store is
needed. Configure the reverse proxy to forward both prefixes to Timekeeper.
This alias does not provide arbitrary Nextcloud APIs or vault directories.

The tested releases use conditional writes and deletes. MySync normalizes text
line endings to LF, including its local copy of a downloaded CRLF document.
An unchanged synchronization retains the original server bytes and ETag; an
actual client edit uploads LF. Unknown frontmatter and trailing spaces survive
unchanged scans. MySync filters attachment types: PDFs were verified
byte-for-byte; arbitrary
`.bin` files are skipped by this release. Timekeeper itself retains arbitrary
attachment bytes through its asset API and WebDAV. Choose an editor/client
that preserves line endings if exact
CRLF copies are required on every device. Calendar labels are independent of
note files, including legacy `timekeeper-label` frontmatter.

The acceptance run uses real released desktop binaries and the unmodified
plugin, with a fresh private vault and trusted HTTPS certificate. It is Linux
interoperability evidence, not a claim that every macOS, Windows or mobile
release was exercised. Validate the relevant release before relying on automatic
sync there.
The server uses standard WebDAV and has no platform-specific client runtime.

### Reproducing client acceptance

With Docker and Python 3 available, build the normal PostgreSQL deployment
image using `server/Dockerfile`, then run:

```sh
docker build -t timekeeper-mysync-client:obsidian-1.14.5-mysync-3.0.7 server/scripts/clients/mysync
python3 server/scripts/mysync_acceptance.py --server-image timekeeper-server:test --expected-commit <source-commit>
```

The test image downloads checksum-verified official Obsidian and MySync releases;
it is never shipped with Timekeeper. The driver controls real Obsidian file
operations and MySync commands; it does not implement synchronization or replace
the client's network transport. Separate disposable clients exercise conflicts,
retry, isolation and revocation against PostgreSQL. No user vault or token is
used. The default run cleans up its containers, private network and secrets and
writes a credential-free receipt to `server/target/acceptance/mysync.json` only
when every assertion succeeds. `--keep-fixture` retains only that run's private
fixture for diagnosis; remove its containers, network and directory afterwards.

## Calendar-event notes

Event notes appear in the same collection as
`event-<subscription UUID>-<64-character occurrence key>.md`. Keep the
`timekeeper-event` frontmatter line intact; edit the Markdown below it. Filenames
remain stable when an event is renamed or rescheduled, and separate recurring
occurrences have separate files. Both `If-Match` overwrite/delete protection and
owner isolation apply. Account archives also export/import these documents.
Notes outlive removed subscriptions and remain available through WebDAV and
`GET /api/v1/event-notes`; importing does not recreate private calendar-feed URLs
or attach notes to unrelated subscriptions. Existing real-client certification
above predates event notes; protocol-level event-note round trips have separate
coverage.
