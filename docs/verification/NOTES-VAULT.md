# Notes/vault acceptance

The tests use private disposable accounts, databases, TLS material and local
folders. They do not connect to a deployed user's vault or store API tokens in
receipts. Runtime receipts include exact clean source commit and distribution or
client checksums; retain those generated artifacts with the CI run.

## Packaged browser and manual client

```sh
./mvnw -B -ntp -f server/pom.xml clean verify
python3 server/scripts/package_server.py --output server/target/distribution --commit "$(git rev-parse HEAD)"
npm ci --ignore-scripts --prefix server/web
npm test --prefix server/web
python3 server/web/tests/acceptance.py --distribution server/target/distribution/timekeeper-server.zip
python3 server/scripts/webdav_sync_acceptance.py --distribution server/target/distribution/timekeeper-server.zip
python3 -m unittest discover -s server/scripts/tests -v
```

Notes browser cases exercise exact source, retained drafts/stale writes, desktop
and mobile tree, resize, create/rename/delete/recovery, configured journal roots,
independent labels, authenticated images, all six themes, English/Norwegian,
read-only access and accessibility. The existing journal/event suites cover the
shared editor and alternate canonical interfaces. The new manual client's actual
subprocess workflow uses verified HTTPS and the packaged helper, not a substitute
sync implementation. It covers selected remote roots, nested Unicode files,
exact Markdown/binary/empty bytes, guarded MOVE identity, first-import collision
review, concurrent edits, explicit deletion review, recursive folder deletion,
restart, missing/replaced roots, incomplete XML, unavailable server, lost committed
replies and resume, opaque `.obsidian` opt-in, account switching, read-only writes
and revocation. Client unit tests additionally cover local editor/symlink races,
portable collisions and concurrent baseline locks.


## Databases and recovery

The same VaultIndex/Content/Sources/Settings/State/Catalog/Collection/DAV/archive
cases use isolated H2 and PostgreSQL 17.11/18.6 databases. CI supplies the archive
DB prefix/credentials and creates separate fixture databases. Portable account
archive v4 tests restore source IDs/paths, binary bytes, canonical event/day/week
bindings, deleted bytes and independent labels into a second owner; quota failures
roll back. Full-instance runtime backup tests seed multiple owners and compare all
vault, journal, label and history records after encrypted image preflight restore.
Schema tests and native upgrade acceptance fence schema 9→10 and supported older
transitions; no physical database-engine migration is performed.
