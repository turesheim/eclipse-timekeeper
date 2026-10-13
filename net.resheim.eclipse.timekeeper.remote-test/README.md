# Installed remote Eclipse acceptance

This test bundle uses Tycho's `p2Installed` runtime with Eclipse Platform and
Timekeeper's base feature, without Mylyn. A test application prepares remote
mode and encrypted fixture credentials before launching the real workbench.
It starts the complete server ZIP in a separate Java 21 process after checking
the ZIP's SHA-256 sidecar. It delegates to `org.eclipse.ui.ide.workbench`, so the
tests operate the installed Workweek controls.

The runtime uses a private workspace, `-eclipse.keyring` and `-eclipse.password`.
The bootstrap verifies both arguments before accessing secure storage. The
password file bypasses OS password providers, as described in the
[Eclipse secure-storage runtime documentation](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.user/reference/ref-securestorage-options.htm).
No user keyring, IDE, credentials or database are selected by the fixture.

The fixture explicitly prepares a valid local database with an existing project
before launching the UI, then closes it. Timekeeper's app connection must remain
null in remote mode. The original local file's checksum is checked before and
after both Eclipse launches, independently of remote server data.

The first phase exercises installed project/task creation, task editing,
tracking start/stop, activity editing with rejected-save draft preservation,
exact timestamp retention, and CSV transfer through the selected client. CSV
folder selection and its preference-page buttons are not automated yet.
The second phase launches a new Eclipse JVM and a new server JVM, decrypts the
stored token, and verifies the original resource IDs and exact instants. The
restart execution retains the first workspace rather than letting Tycho clear
it before the second phase.

Run from the repository root after preparing a packaged server ZIP and sidecar:

```sh
./mvnw -Premote-client-tests clean verify \
  -Dtycho.localArtifacts=ignore \
  -Dtimekeeper.remote.distribution=/absolute/path/timekeeper-server.zip
```

CI's `Remote-Eclipse` job downloads the verified distribution from the Server
job and runs these phases plus the optional Mylyn module's seven phases under Xvfb. It uploads reports, server/runtime logs and
the credential-free `proof.properties`; secure storage, the password file and
token registry are excluded from uploaded evidence.

The optional Mylyn module covers installed activation, restart, account-token
switching, server/embedded switches, task-editor failure preservation and selected uncertain-update
outcomes. It also verifies automatic peer-change refresh and reconnect; see
[its README](../net.resheim.eclipse.timekeeper.remote-mylyn-test/README.md).
Still required before #220 is complete: remaining native preference flows,
other form failures and uncertain-command/
deletion outcomes. #219 web synchronization and #221 web UI remain required
before the 2.0 release.
