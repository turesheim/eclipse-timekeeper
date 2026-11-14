# Operations and user guides

Choose the deployment guide first, then the account or feature guide you need.

| Area | Guides |
| --- | --- |
| Install and run | [Synology Package Center](SYNOLOGY-PACKAGE.md), [local Docker](LOCAL-DOCKER.md), [standalone Java server](SERVER.md), [Compose deployment](DEPLOYMENT.md) |
| Connect clients | [Browser client](WEB-CLIENT.md), [Eclipse client](ECLIPSE-CLIENT.md), [Tailscale HTTPS](TAILSCALE.md) |
| Accounts and configuration | [Accounts, passkeys and API tokens](ACCOUNTS.md), [configuration names and upgrades](CONFIGURATION.md) |
| Google and Microsoft | [OAuth app registration](SHARED-SIGN-IN.md), [Internet accounts and tasks](TASK-PROVIDERS.md), [hosting an OAuth service](CONNECT-SERVICE.md) |
| Notes and editing | [Markdown editor](MARKDOWN-EDITOR.md), [WebDAV clients](NOTES-WEBDAV.md), [journal storage and encryption](JOURNAL-STORAGE.md) |
| Import and export | [Personal account archives](ACCOUNT-DATA.md), [Toggl import and Undo](TOGGL-IMPORT.md) |
| Backup and recovery | [Full-instance backup and restore](INSTANCE-BACKUP.md), [embedded Eclipse database recovery](DATABASE-RECOVERY.md) |
| Diagnose problems | [API diagnostics](API-DIAGNOSTICS.md), [CI check selection](CI.md) |

A personal account archive transfers that account's work. An encrypted instance
backup contains all accounts and installation security material. Embedded Eclipse
storage is a separate database; switching backends does not transfer it.

For implementation and test commands, use the [development guides](../development/README.md).
For past test results, use the [verification archive](../verification/README.md).
