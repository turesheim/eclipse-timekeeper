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

## Client compatibility so far

An actual rclone 1.75.2 WebDAV remote with vendor `other` successfully listed,
downloaded, and created a day note. Its overwrite request did not include
`If-Match`, and the server returned `428`. Therefore rclone overwrite or
bidirectional sync is **not supported** with the current rclone behavior.
Compatibility with Obsidian and Cyberduck has not yet been validated. Do not
configure an automatic bidirectional client until its conditional overwrite
and delete behavior has been tested.
