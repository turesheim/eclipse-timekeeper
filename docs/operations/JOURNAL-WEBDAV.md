# Journal WebDAV endpoint

The journal collection is `/dav/journal/` on the Timekeeper server. Each
`YYYY-MM-DD.md` resource belongs to the account identified by the API token,
independent of the supplied WebDAV username. Use an API token with `read` and
`write` permissions as the HTTP Basic password. A read-only token can list and
download notes. The endpoint requires HTTPS outside loopback development.
Timekeeper also accepts the private HTTP hop behind a TLS reverse proxy when
`TIMEKEEPER_PUBLIC_ORIGIN` names its exact HTTPS authority. Restrict direct
access to that backend network, as in the supported Synology deployment.

The collection supports `OPTIONS` and `PROPFIND`; note files support those
methods plus `GET`, `HEAD`, `PUT`, and `DELETE`. A present empty note is listed
as a zero-byte file. A deleted note is absent. Calendar and REST edits share
the same version as WebDAV edits, so their changes invalidate previously issued
WebDAV ETags.

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
is a Servlet filter. Timekeeper uses Quarkus REST, so this bounded one-level
collection uses [Quarkus REST custom HTTP methods](https://quarkus.io/version/3.40/guides/rest/#declaring-endpoints-http-methods)
without adding a Servlet stack or another authentication path.

## Client compatibility

The following results come from actual clients against an isolated packaged
Timekeeper server on loopback. The HTTP connection was limited to that local
test; deployments must use HTTPS.

| Client | Verified behavior | Result |
| --- | --- | --- |
| rclone 1.75.2, WebDAV vendor `other` | List, download, and create worked. Initial `bisync --resync` uploaded a note. A subsequent local edit led to an unconditional overwrite, which Timekeeper rejected with `428`; bisync aborted and requested another resync. | **No bidirectional support** with this version. Do not use `bisync` for this collection. |
| Cyberduck CLI (`duck`) 9.5.4.45528 | List, upload a new note, and byte-exact download worked. Overwrite and delete omitted `If-Match` and received `428`. The overwrite command reported an error in its output yet exited with status 0. | **Read and create only**. Do not rely on its process exit status to detect a rejected overwrite. |

Obsidian Remotely Save has not been run against this endpoint. Its
[WebDAV implementation](https://github.com/remotely-save/remotely-save/blob/master/src/fsWebdav.ts)
creates a vault subdirectory and calls `putFileContents` with `overwrite: true`
without a per-file `If-Match`. This suggests that it will not support the
fixed, one-level journal collection or guarded updates without changes to the
plugin. This is an inference from its source, not an interoperability result.

No off-the-shelf client has yet passed the complete bidirectional and conflict
test matrix. Do not configure automatic bidirectional synchronization until a
client is verified to send conditional overwrite and delete requests. A `428`
protects the current Calendar note; it does not mean that a client synchronized
successfully. The endpoint remains usable for clients that send the current
strong ETag in `If-Match`.
