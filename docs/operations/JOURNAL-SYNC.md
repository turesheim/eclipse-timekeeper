# Synchronize daily Markdown over SSH

Timekeeper supports upload-only imports and explicitly enabled bidirectional
exchanges between a chosen daily-note directory and the database. Calendar and
account archives read the database. Existing keys remain upload-only. Calendar
edits and differing first imports produce recoverable conflicts in
**My account → Journal**. This is server-readable
storage, not end-to-end encryption. Follow [storage protection](JOURNAL-STORAGE.md)
for every database, incoming, conflict, temporary and backup location.

## Operator setup

Use the dedicated gateway container, independently of the web service. Do not
enable journal logins on an administrator SSH service or give the web service
root privileges. Each account gets a distinct unprivileged Unix UID, protected
home and private spool. Public-key lookup and every transfer are authorized by
the web service; there is no reusable user API token in a downloaded profile.

Build from the exact reviewed source checkout:

```sh
docker build -f server/journal/Dockerfile -t timekeeper-journal-gateway .
```

The image builds rsync/rrsync 3.5.1 from the checksum-pinned upstream source,
and includes its license and complete source archive. Its root entry point only
provisions the dedicated container's users, configuration and host key before
starting OpenSSH. Receiver and key lookup processes run without root.

Provision an encrypted, private gateway state mount at
`/var/lib/timekeeper-journal` and a root-owned secret directory mounted read-only
at `/run/secrets`. Generate one different high entropy broker secret per account
with `python3 -c 'import secrets; print(secrets.token_urlsafe(32))'` in a private
operator terminal. Do not put secrets in compose files, command arguments, git,
web assets, profiles or logs. Create `/run/secrets/journal-gateway.json`, root
owned with mode 600:

```json
{
  "format": 1,
  "brokerUrl": "https://timekeeper.example.org/internal/journal",
  "encryptedStorage": true,
  "backupKeysSeparated": true,
  "accounts": [
    {"username": "journal_alice", "uid": 20001, "token": "REPLACE_WITH_PRIVATE_BROKER_SECRET"}
  ]
}
```

Use the configured public HTTPS hostname (the application's Host policy also
applies to the broker) and normal CA validation. A private CA may be
mounted as a public certificate and selected with `"caFile":"/run/secrets/ca.pem"`.
The broker must be reachable from the gateway; route `/internal/journal/` to the
application without adding browser cookies or an Origin header. HTTP is allowed
only on actual loopback, not between containers. Reject redirects. Keep this
endpoint private to the gateway network where practical; its narrow credential
never grants normal API access.

Start the gateway with the two mounts and publish its container port 2222 on
the intended SSH interface. Preserve the gateway host key across upgrades.
Read its **public** key after startup:

```sh
docker exec timekeeper-journal-gateway cat /var/lib/timekeeper-journal/host/ssh_host_ed25519_key.pub
```

Configure the web service with `TIMEKEEPER_JOURNAL_CONFIGURATION_FILE` pointing
to a service-owned, mode 600 JSON file outside public assets. Its parent and
`incomingRoot` must be private, canonical directories on encrypted storage.
Use the existing account owner ID from Administration, the actual reachable SSH
hostname/port, the complete public host key, and the SHA-256 hex digest of that
account's broker secret (not the secret itself):

```json
{
  "format": 1,
  "hostname": "journal.example.org",
  "port": 2222,
  "hostKey": "ssh-ed25519 REPLACE_WITH_ACTUAL_PUBLIC_HOST_KEY",
  "incomingRoot": "/private/timekeeper/journal-incoming",
  "encryptedStorage": true,
  "backupKeysSeparated": true,
  "accounts": [
    {"ownerId": "EXISTING_ACCOUNT_OWNER_ID", "username": "journal_alice", "brokerTokenSha256": "REPLACE_WITH_SECRET_SHA256_HEX"}
  ]
}
```

The two booleans are operator attestations, not encryption performed or verified
by Timekeeper. Missing prerequisites leave account setup unavailable. Never
change a UID to reuse another account's spool. Removing a binding or changing
configuration invalidates existing transfers. Rotate a broker credential in
both protected configurations during a maintenance window and restart the
gateway. No application restart is needed to reload the trusted mapping.

For the two-instance compose deployment, configure the journal file in both
API services, and mount the same encrypted incoming filesystem read/write at
the same `incomingRoot`, with the same service UID and mode 700. Config remains
read-only and mode 600. Database owner locks serialize handoff/import across
instances. Separate, unrelated incoming mounts behind round-robin routing do
not work: a later upload must see the directory created by its begin request.
Alternatively, route the entire internal broker path to one configured API
instance. The gateway's HTTPS hostname must still pass the public Host policy.

For Synology Container Manager, run this as a separate project/container; do not
use the NAS administrator's SSH service. Map the encrypted shared-folder state
and root-owned configuration with the same numeric permissions. Publish only
the chosen SSH port and allow outbound certificate-verified HTTPS to Timekeeper.
Named Docker volumes and container isolation alone do not provide encryption.
Unlock and verify encrypted storage before starting either service. Back up the
gateway host key and mapping, database and recovery material on encrypted
storage; keep backup encryption keys separately. Test restoration without
reassigning account UIDs or silently replacing the pinned host key.

## Local client setup

Supported clients are macOS, Linux and Windows through WSL2, with Python 3.11+,
OpenSSH and rsync 3.5.1+. macOS's bundled rsync is too old. Install using Homebrew
(`brew install python rsync`). On supported Ubuntu/WSL distributions use the
[rsync project's stable PPA](https://rsync.samba.org/download.html),
`ppa:rsyncproject/rsync`, then install `python3 openssh-client rsync`. Check
`python3 --version` and `rsync --version` in the terminal that will run the job.

On supported Ubuntu releases, the installation commands are:

```sh
sudo apt-get update
sudo apt-get install software-properties-common
sudo add-apt-repository ppa:rsyncproject/rsync
sudo apt-get update
sudo apt-get install python3 openssh-client rsync
```

Generate a different Ed25519 key on each device, choose a passphrase, and load
it in your local agent. Never upload its private half or passphrase:

```sh
ssh-keygen -t ed25519 -f ~/.ssh/timekeeper_journal -C timekeeper-journal
chmod 600 ~/.ssh/timekeeper_journal
ssh-add ~/.ssh/timekeeper_journal
cat ~/.ssh/timekeeper_journal.pub
```

Sign in directly to your account over HTTPS. In **My account → Journal**, enable
sync, then enroll the public `.pub` text with a recognizable device label and
your current account password. Download that device's public JSON profile and
the client script. Put both in a private, user-owned mode 700 local directory
(`chmod 700`); the client checks this boundary. Keep the vault and private sync
state on encrypted local storage and encrypt their backups. The script is called
`timekeeper-journal.py` below. Use the actual profile filename in these commands:

```sh
python3 timekeeper-journal.py configure --profile profile.json
python3 timekeeper-journal.py trust --profile profile.json --fingerprint 'SHA256:REPLACE_WITH_HTTPS_FINGERPRINT'
python3 timekeeper-journal.py verify --profile profile.json
python3 timekeeper-journal.py sync --profile profile.json
```

Configure asks for the local daily-note folder and private-key filename. Those
paths stay in a mode 600 local sidecar, never in Timekeeper. Compare the full
fingerprint with the value displayed over authenticated HTTPS before trusting.
SSH refuses an unknown or changed host key, shell access and forwarding.
Upload-only keys cannot download notes. Bidirectional keys can download only
their own prepared exchanges, never arbitrary server directories.
For a host-key change, stop schedules and independently confirm the new key
with the operator, download an updated profile, then deliberately use
`trust --replace --fingerprint …`. Do not bypass verification or use ssh-keyscan
as evidence of authenticity.

Only top-level `YYYY-MM-DD.md` files are considered; real dates, strict UTF-8 and
safe bounded YAML are validated by the server. Optional YAML
`timekeeper-label: Label` supplies the Calendar label; the exact Markdown body
follows the frontmatter. Upload-only imports never rewrite local originals.
Bidirectional exports preserve unknown YAML properties and exact Markdown body
bytes; editing a label can normalize the YAML envelope. An unchanged document
keeps its original bytes. Unrelated files and `.obsidian` are excluded.
Symlinks and hard links are skipped;
oversized/changing/unreadable files produce per-file errors while independent
valid notes continue. Each file is limited to 220,000 input bytes, including at
most 20,000 frontmatter and 200,000 body bytes. A complete private snapshot and
manifest must arrive before any note becomes eligible for import.

After the first sync, check the date in Calendar and refresh Journal health.
Review per-file errors and conflicts there. Download an original candidate
before discarding it if needed. A conflict decision checks the current Calendar
version again; refresh and reconsider if somebody edited it. Retry after a
network interruption; identical files are no-ops. Do not delete a local original
to resolve a database conflict.

## Bidirectional access and first exchange

Enable **Allow bidirectional synchronization** in Journal settings with your
current password. Register a fresh key with **Allow this device to download
Calendar notes** selected, or rotate an existing key into a new device profile.
Both grants are required; the global setting does not upgrade old keys.
Download the new format-2 profile. Turning off the global setting stops these
devices while upload-only keys can continue importing.

Keep the profile and its entire private state directory outside `Daily` and
outside other vault synchronization. They must share the daily-note folder's
filesystem for atomic recovery moves and exclusive installation. Use an
encrypted local filesystem; on WSL2 use its Linux filesystem with working
Unix permissions and hard links. Configure checks folder identity and filesystem
placement. A second local process cannot run the same profile concurrently.

Run verify, then sync. A first exchange imports local-only notes, exports
database-only notes, and asks for a deliberate decision when both copies differ.
Review conflicts in Journal and sync again with the same profile to deliver the
chosen copy. Unrelated dates continue. The client transfers batches of at most
50 dates through private staging; rsync never targets the canonical local folder.
The common state advances only after a durable local installation and matching
server acknowledgement. Retry interrupted runs with the same private state.

An explicitly empty file is a present, empty note. After a date has an
acknowledged common state, deleting its local file can delete the database note;
Calendar deletion can remove the corresponding local file. Deleting against an
independent edit creates a conflict, including a visible deleted variant. A
missing untracked file never deletes a database note. Explicit recreation after
an acknowledged deletion creates a new present note. Calendar **Clear** produces
empty content; **Delete** records a deletion.

Do not reset, copy between devices, or reconstruct common state from file dates.
The server binds its device to the local client identity; the client also binds
the configured folder inode. If state is lost or a folder is moved/replaced,
preserve all local files and recovery material, revoke the old device, and enroll
a new key/profile. Its first exchange treats missing files as untracked and
requires review of differing copies. Keep other synchronization tools away from
private state; their independent edits to canonical daily files are treated as
local edits and can require conflict review.

## Local recovery

Exports retain replaced originals privately, including their original inodes.
An editor can still write through an old open descriptor after a rename; the
next run detects such a changed recovery copy and stops automatic installation.
An editor's atomic replacement is never overwritten by the exclusive installer.
Private recovery storage is limited to 500 retained variants; nothing removes
these originals automatically. Review them before explicit disposal.

```sh
python3 timekeeper-journal.py recoveries --profile profile.json
python3 timekeeper-journal.py recover --profile profile.json --id RECOVERY_UUID --variant original --output /private/review/original.md
python3 timekeeper-journal.py recover --profile profile.json --id RECOVERY_UUID --variant offered --output /private/review/offered.md
```

The list prints metadata and private locations, never note contents. Recovery
copies to a new mode-600 file outside `Daily` without replacing an existing file
or changing synchronization state. An offered private file is available only
while retained by the interrupted operation. Close the editor, compare variants,
and deliberately save the desired content in the canonical daily file. Preserve
any other variant you need before using:

```sh
python3 timekeeper-journal.py discard-recovery --profile profile.json --id RECOVERY_UUID
```

This explicitly removes that operation's retained original and prepared variant;
it keeps the current canonical file and does not advance common state. Retry
sync. If an interrupted exchange cannot continue against the chosen local file,
first resolve or discard its local recovery operations, then deliberately
abandon that exchange:

```sh
python3 timekeeper-journal.py abandon-exchange --profile profile.json --id EXCHANGE_UUID
```

Its immutable uploaded snapshot stays in the printed private directory for
manual review. Download/review and discard the corresponding retained server
copies in Journal. A fresh exchange uses the previous acknowledged common state,
so simultaneous server changes still require conflict review. Never delete the
SQLite database or edit its tokens as a recovery shortcut.

## Scheduling and disconnecting

Run `python3 timekeeper-journal.py schedule --profile profile.json` with the local
agent loaded. It generates private files under the local profile's
`.timekeeper-journal-DEVICE_ID/schedule` directory, and prints the unit name.
Generation does not install a persistent job. Keep the script, profile, sidecar
and private state at stable paths. No key contents or passphrases are included.

On macOS, copy the generated `.plist` to `~/Library/LaunchAgents/`, mode 600,
then run `launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/NAME.plist`
using its actual filename. Unload with `launchctl bootout gui/$(id -u)
~/Library/LaunchAgents/NAME.plist`. On Linux/WSL2 with systemd user services,
copy the `.service` and `.timer` to `~/.config/systemd/user/`, mode 600, then
run `systemctl --user daemon-reload` and `systemctl --user enable --now
NAME.timer`. Stop using `systemctl --user disable --now NAME.timer`.

The generated interval is five minutes (`--interval` accepts 60–86400 seconds).
Schedules use the local `SSH_AUTH_SOCK` available when generated; a changed agent
socket requires regeneration. Keep the agent available and unlocked in that
user session. If it is locked, expired or unavailable, the run fails rather
than prompting unattended or storing a passphrase. Review launchd/systemd
status and Journal health after enabling a schedule. WSL2 must be running and
have systemd enabled for its timer to execute.

To rotate a device, create a fresh local key, enroll its public half with the
old device selected for replacement, download/configure/pin its new profile,
verify and sync before rescheduling. Rotation immediately revokes the old key,
including in-flight work. Revoke a lost device in Journal and remove its local
schedule when possible. Disabling Journal stops all devices; disabling the
account also blocks transfers and import. Keep local originals and any wanted
recovery downloads. Never delete another device's files to disconnect it.
