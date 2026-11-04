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

Use the packaged `webdav_sync.py` client for an Obsidian vault or another
Markdown folder. It previews changes before applying guarded writes, retains
conflicting copies and supports recovery after interrupted operations. See
[Notes manual synchronization](NOTES-WEBDAV.md) for setup and folder layout.
Obsidian synchronization plugins are outside the supported workflow.

Existing guard requirements also apply to other WebDAV clients. The previously
exercised rclone 1.75.2 and Cyberduck CLI 9.5.4.45528 omitted `If-Match` on
updates; Cyberduck also omitted it on deletion. Listing, downloading or creating
a new file does not establish safe bidirectional synchronization. Keep the
conditional-write guards enabled.

## Calendar-event notes

Event notes appear in the same collection as
`event-<subscription UUID>-<64-character occurrence key>.md`. Keep the
`timekeeper-event` frontmatter line intact; edit the Markdown below it. Filenames
remain stable when an event is renamed or rescheduled, and separate recurring
occurrences have separate files. Both `If-Match` overwrite/delete protection and
owner isolation apply. Account archives also export/import these documents.
Notes outlive removed subscriptions and remain available through WebDAV and
`GET /api/v1/event-notes`; importing does not recreate private calendar-feed URLs
or attach notes to unrelated subscriptions. Event-note round trips have separate
protocol coverage.

## Hierarchical Notes collections

The existing flat journal endpoints remain canonical aliases. New vaults use
`/dav/vault/` or `/remote.php/webdav/vault/`, with configurable `journal`, `events`
and `assets` folders. Do not repoint a populated flat local folder without a
reviewed transition. See [Notes manual sync and migration](NOTES-WEBDAV.md),
including client limitations and relative attachment links.
