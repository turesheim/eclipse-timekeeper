# Protecting journal storage

Daily labels and Markdown bodies are private account data. Every note read,
calendar summary, conditional write/delete, change notification and account
archive requires the existing account authorization. Read-only access can read
notes and download its own archive; it cannot modify notes, even with a no-op
request. Disabled accounts and revoked sessions cannot access journals. An
administrator role does not grant access to another account's journal through
the API. Dates and deterministic note IDs are identifiers, not credentials.

Use the supported HTTPS reverse-proxy or Tailscale deployment for browser/API
access outside loopback development. Retain the configured public origin,
Secure/HttpOnly session cookies, CSRF checks and owner binding. Note responses
use `Cache-Control: private, no-store`; calendar summaries contain a label and
`hasBody`, never the Markdown body. Change notifications carry invalidation
metadata, not note contents. The client clears its note metadata and editor
state when the authenticated account changes or signs out.

## At-rest protection and trust boundary

Timekeeper processes **readable Markdown**. It stores note text in the existing
owned database JSON records. It does not add application-level journal
ciphertext or an application-managed journal encryption key. The account ZIP
format is also unencrypted. HTTPS alone does not protect stored journals and
this design is not end-to-end encryption. Host administrators, the database
service and the Timekeeper process are trusted with readable contents.

For a deployment holding journals, the operator must provision encrypted
storage and restrictive service permissions **before** storing journal data:

- Put an H2 database and its recovery copies on encrypted filesystem/block
  storage accessible only to the Timekeeper service account and its operator.
- For PostgreSQL, cover the data directory, WAL, any external tablespaces and
  database temporary files. Database authentication and network isolation do
  not encrypt those files. Use certificate-verified encrypted database transport
  when database traffic crosses an untrusted host/network boundary. See
  [PostgreSQL's encryption options](https://www.postgresql.org/docs/18/encryption-options.html).
- Put database backup files, protected-restore staging, account download/upload
  temporary files and exported account ZIP/JSON files on encrypted private
  storage too. Configure the JVM's `java.io.tmpdir` to such a directory; a private
  directory in an unencrypted system temporary filesystem is insufficient for
  protection at rest. Account-download staging directories have owner-only
  access and are removed after use, but deletion is not a substitute for
  encryption of SSDs, snapshots or backups.
- For Synology/container deployments, use the host's supported encrypted storage
  for the relevant database/state and temporary mounts; container isolation,
  a named Docker volume and a private shared-folder name do not themselves
  provide encryption. Verify the mount is actually unlocked and available at
  service startup, and preserve the existing unprivileged service ownership.
- External WebDAV clients may keep local copies and their own synchronization
  state. Protect those files and client backups with the same encrypted/private
  storage policy. Follow the [WebDAV guide](JOURNAL-WEBDAV.md) for account
  authentication and conditional writes.

The operating system/storage platform manages volume unlock keys. Keep recovery
key material in a separately protected operator-controlled store, outside the
mounted journal directories, account archives and ordinary database backups.
Use a distinct backup-encryption key when backups leave the protected volume;
store it separately from those backups. Limit who can unlock storage, document
recovery custody, and rotate/revoke keys through the storage platform's supported
procedure. Test an encrypted backup restore with the authorized recovery key
before retiring an older key. Database passwords, session tokens and SSH client
keys are not substitutes for an at-rest encryption key.

The application cannot establish that a host mount or an external backup target
is encrypted. Operators must verify that deployment property; the API does not
advertise an encryption status it cannot prove. Preserve encrypted-storage and
key-recovery configuration during updates and disaster recovery. Avoid restoring
journal data onto an unencrypted temporary path just to test a backup.

## Content handling

Do not enable request/response-body logging, SQL bind-value logging or telemetry
that captures journals at the application, proxy, database or synchronization
layers. Application failures expose generic codes, and timing/transaction logs
record operational metadata without note bodies or labels. Public assets never
contain account notes. Account exports remain subject to the normal private
account boundary and explicitly include note bodies, so protect downloaded
copies and their destination backups as carefully as the database itself.

The rich editor and WebDAV endpoint retain the at-rest trust boundary above.
