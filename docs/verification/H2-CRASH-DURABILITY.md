# H2 crash-recovery durability follow-up

The daily-journal restart acceptance test exposed a lost acknowledged update
after `SIGKILL`. A GET before termination returned the new version; reopening
the file with an independent JDBC process returned the old version. An isolated
JDBC reproducer on a copy of the synthetic acceptance database reproduced this
outside Quarkus and its pool.

This matches [H2 #4446](https://github.com/h2database/h2database/issues/4446).
After freed chunk space is reused, a rewritten store header can point to the
previous chunk. The maintainer's
[upstream fix](https://github.com/h2database/h2database/commit/c02e1ec9719243c35e7291a667be3735ea1edc95)
updates that pointer before writing the header. As of October 25, the newest
published H2 release is 2.5.252; it still has this regression. Neither
`WRITE_DELAY=0` nor `CHECKPOINT SYNC` fixes it.

Timekeeper selects published H2 **2.4.240** for both the standalone server and
the Eclipse database bundle. It does not ship a private driver fork, change the
application schema, or disable freed-space reuse. Earlier reports selecting
2.5.250 describe historical verification and are superseded by this follow-up.

## Verification

- A deterministic MVStore test writes and crashes, reopens, forces freed-space
  reuse with zero retention, updates values and a marker, syncs and crashes
  again. It verifies every final value after reopening. The test fails on
  2.5.250 and 2.5.252 and passes on 2.4.240. Both server and bundled Eclipse
  driver tests exercise this behavior without sleeps.
- The server's binary compatibility fixture was created with H2 2.5.250 and
  the complete schema-6 DDL. The current runtime validates its layout, reads a
  synthetic journal's exact Markdown/version, updates it and reopens it with
  the data intact. The generator is
  `server/app/src/test/fixtures/GenerateH2CompatibilityFixture.java`; run it
  with the H2 2.5.250 JAR, a new output basename and
  `server/app/src/main/resources` as arguments. It refuses existing files.
- Existing Eclipse fixtures generated on H2 2.5.250 exercise current model
  reopen, verified schema upgrade and SQL backup/restore against the selected
  runtime. PostgreSQL behavior and driver remain unchanged.
- Packaged HTTP acceptance verifies exact journal content and ETag across
  graceful restart and an immediate forced restart after clearing its label.
  It also checks revoked and read-only journal requests after recovery.

The compatibility evidence covers supported application schemas and synthetic
fixtures. Operators must stop all clients and retain a closed-file backup
before changing the shared runtime; see
[database recovery](../operations/DATABASE-RECOVERY.md). Data already lost to
the upstream regression cannot be reconstructed by replacing the driver.
