# Connect Eclipse to the Timekeeper server

Install **Timekeeper for Eclipse** from the [testing update site](https://turesheim.github.io/eclipse-timekeeper/updates/testing/)
for verified unreleased 2.0 main builds, or the [stable update site](https://turesheim.github.io/eclipse-timekeeper/updates/)
for published releases.
In **Help > Install New Software... > Add...**, paste that URL into **Location**,
select the feature and restart. Add **Timekeeper Mylyn integration** if you want
Task List integration. Timekeeper 2.0 requires Eclipse 2026-09 and Java 21;
see the [installation guide](https://github.com/turesheim/eclipse-timekeeper/wiki/Installing-Timekeeper-2.0)
for publication status and candidate installation.

## Select the remote backend

1. Obtain the server URL and a bearer token for your account from its operator.
2. Open **Preferences > Timekeeper > Backend** and select **Timekeeper server**.
3. Enter the server's API URL, ending in `/api/v1`, and the token.
4. Click **Test connection** to check the entered URL and token without saving
   them or changing the running backend. A successful test shows the account and
   time zone. Authentication, connection and timeout failures appear on the page;
   correct the fields and try again. The test runs in the background and makes
   no changes to server records.
5. Apply the settings and restart Eclipse. Open **Window > Show View > Other... >
   Timekeeper > Workweek**.

Use `http://127.0.0.1:8080/api/v1` for a server on the same computer. Other hosts
require HTTPS. The [local Docker guide](LOCAL-DOCKER.md#5-reach-the-test-server-through-tailscale)
describes HTTPS access through Tailscale. The server remains the authority for
records; selecting a backend does not copy or merge databases. Mylyn activation
uses the selected backend after restart.

Eclipse and the web client share projects, tasks, activities and labels when
their tokens belong to the same server account. Different accounts have separate
records, even on the same server. Open label preferences and activity editors
receive label changes while preserving unsaved fields and selected labels.
Conflicting label-definition edits retain their original version and are
rejected for review rather than overwriting another client's changes.

Use **New activity** or **Edit activity...** in Workweek to record or adjust an
interval. Its start cannot be in the future, whether it is ongoing or completed.
The selected backend validates its own clock; an invalid edit or import leaves
stored records unchanged.

## If the token cannot be saved

Timekeeper encrypts tokens using Eclipse secure storage. A storage error occurs
before the new backend settings are saved; resolve it and apply the settings
again. The error now identifies a missing provider, unavailable master password,
encryption/decryption failure, or an unwritable storage file.

Open **Preferences > General > Security > Secure Storage**. On the **Passwords**
tab, inspect the available master-password providers. On macOS, the operating
system provider obtains its master password from the OS keyring; allow the
corresponding Keychain prompt if it appears. If the provider malfunctions,
Eclipse allows selecting another enabled provider for new entries. An existing
encrypted entry still requires its original provider and master password.

For a file-write error, use the **Contents** tab to find the storage location
and check that your account can write it. Recover an existing master password
using Eclipse's recovery option when it was configured. Deleting the shared
secure-storage file removes other Eclipse credentials too, so preserve it when
troubleshooting. Timekeeper does not fall back to plaintext preferences.

See Eclipse's [secure-storage preferences](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.user/reference/ref-securestorage-prefs.htm)
and [how secure storage works](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.user/reference/ref-securestorage-works.htm).

## Conflicts and interrupted requests

Changes from other clients refresh Workweek automatically. Open editors retain
their original drafts and record version; if another client changes that
record, close the editor, review the refreshed record and reopen it before
saving. Authentication and connection errors appear in the client and preserve
the server's data. After a request times out or is cancelled, follow its review
instruction: a request may already have committed. Ordinary creates are not
automatically repeated. Start/stop and CSV import use owner-bound command keys
for the explicitly supported retry flows.
