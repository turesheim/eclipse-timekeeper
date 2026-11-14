# Web client implementation

The bundled client at `/web/` uses native browser modules and Bootstrap 5.3.8.
Maven includes `server/web/src` in the server JAR; production needs no Node
service or CDN. Use Node 24 for development and the pinned npm lockfile.

For user workflows, see the [web client guide](../operations/WEB-CLIENT.md).
For build, package and Playwright commands, see [browser acceptance](WEB-ACCEPTANCE.md).
The [repository guide](REPOSITORY.md) covers the two Java reactors.

## Synchronization

The API session binds all requests to the authenticated owner. Refresh loads
independent collections in parallel, validates profile/revision consistency and
rejects mixed snapshots. It has a 30-second overall budget and up to three
attempts; failed or superseded reads are aborted. Polls share in-flight refreshes
and schedule a follow-up when that read predates a received change.

Preserve focused controls and drafts during background publication. Retain
unchanged rows/cards instead of repainting inactive views. Notes catalog changes
arriving during a read or busy operation stay pending for a follow-up. A
recoverable connection failure pauses writes while retaining an editable journal;
consistent recovery uses the original note/label validators. Sign-out or lost
authority clears owner data, requests and drafts. Never replay an ordinary write
because its response was lost; use durable command receipts where the API defines them.
See [API v1](../architecture/API-V1.md) and the synchronization browser scenarios.

## Language and region

Edit both `server/web/src/locales/en.json` and `server/web/src/locales/nb.json`,
then run:

```sh
npm run vendor:i18n --prefix server/web
```

The generator validates keys/interpolation and writes `resources.js`; do not edit
that output manually. Bundled i18next resolves the account preference or browser
fallback before UI initialization. `Intl` handles display formatting; the account
time zone is independent. Translate marked UI text and attributes, never user
records, identifiers, import fingerprints or CSV headers. Render values as text.

## Passkey sign-in and account management

The browser restores an HttpOnly Secure session cookie. CSRF and owner binding
remain memory-only. New tokens never enter browser storage; legacy saved tokens
are exchanged and removed. Token-derived sessions cannot perform protected
account/administrator operations. Reuse the account/settings view lifecycle so
late replies cannot repopulate a signed-out page. See [account operations](../operations/ACCOUNTS.md)
and the authoritative [API contract](../architecture/API-V1.md).

## Tasks

Native tasks and read-only calendar to-dos share filtering and presentation, not
write authority. Bulk completion is limited to rendered, unfinished native tasks
and retains each validator. Civil dates remain civil; ambiguous or unknown zones
must not acquire invented UTC instants. Provider imports and outbound changes
use their review/receipt flows. See [task synchronization](../architecture/TASK-SYNC-PROTOCOL.md),
[provider capabilities](../architecture/TASK-SYNC-CAPABILITIES.md) and
[operator setup](../operations/TASK-PROVIDERS.md).

## Shared Markdown component

Use the shared MarkdownField rather than another editor implementation. Preserve
canonical source, selection, undo history, attachment identity and pending-save
behavior across Today, Notes and dialogs. The [component guide](MARKDOWN-EDITOR.md)
and [journal integration](JOURNAL-EDITOR.md) own the detailed editor contracts.

## Today and Calendar views

Civil-day boundaries, timer displays and navigation use the account's zone.
Preserve Today cards when their content is unchanged, and retain the journal's
editor instance during refresh. Layout changes must cover narrow screens, event
warnings, empty days and running timers. See the [user guide](../operations/WEB-CLIENT.md#today-week-and-calendar)
and the Today/Calendar browser scenarios.

## Public holidays

The locally bundled holiday engine uses the selected country independently of
UI language and account zone. Keep displayed civil dates stable. After an
intentional engine/data update, run `npm run vendor:holidays --prefix server/web`.
Asset checks verify the pinned version, catalog and licenses.

## Themes

Use existing theme variables, Bootstrap 5.3.8 and locally bundled icons. Preserve
`timekeeper` theme IDs and storage keys. Test changed surfaces in both light and
dark themes. Regenerate intentional asset changes with their existing `vendor:*`
commands; verify with `npm run check:assets --prefix server/web`.

## Font sizes

Reuse the typography settings and semantic CSS variables. Changes to appearance
must not rewrite stored Markdown. Exercise restored account preferences,
responsive controls and editor content separately.

## Design baseline

Reuse account cards, dialogs, focus behavior, confirmations and navigation.
Do not expose implementation details in user workflows. Use Dagsverk in visible
copy while preserving [compatibility identifiers](BRANDING.md). Illustrations
belong with the [user guide](../operations/WEB-CLIENT.md), rather than test commands.

## Workweek and Activities

Workweek edits work sessions; Activities records task time. Preserve unchanged
endpoint precision and metadata. A session spanning midnight is displayed across
account days without creating duplicate stored sessions. Conditional or uncertain
writes keep the proposal for review. See [the data model](../architecture/DATA-MODEL.md).

## Personal settings and independent user accounts

Account switching must clear previous-owner records and asynchronous work.
Administrators manage access, not other accounts' business data. Keep personal
settings and installation settings distinct; use the existing session/CSRF proof
and ETag paths. The [account guide](../operations/ACCOUNTS.md) owns user instructions.

## Work sessions

A work session is independent of activity timers and is classified by client and
optional project. Labels belong to activities. Historical session edits must not
change the shared card's current target. Session edits omit legacy `labelIds`
rather than erasing older metadata. See [API v1](../architecture/API-V1.md).

## Automated verification

Use [browser acceptance](WEB-ACCEPTANCE.md) for prerequisites, clean-source
packaging, HTTPS/SMTP/passkey fixtures, scenario selection and evidence handling.
`npm test --prefix server/web` runs unit/asset checks; it does not establish browser acceptance.

## Week

Keep planned calendar events distinct from recorded work. Time-zone projection,
recurrence, all-day items, overlap and drag/resize edits need targeted scenarios.
Use the same conditional editors and retain drafts on conflict.

### Calendar subscriptions

Server fetching and provider credentials stay behind API v1. Feed subscriptions
can expose read-only events/to-dos without OAuth. Subscription URLs may contain
secrets; never place them in logs, account archives or displayed DTOs. Conversion
into recorded work requires review. See [server feed access](../operations/SERVER.md#calendar-feed-access).

## Clients, projects and work sessions

A client is a customer record, not a login account. Client/project mappings,
project icons, grouping and reports use owned records and the shared account
zone. Keep report windows owner-bound and reject late results after sign-out.

## Toggl Track import

Preview, apply and Undo use bounded, owner-bound operations and durable receipts.
Keep uncertain outcomes visible rather than resubmitting automatically. User
steps belong in [Toggl import](../operations/TOGGL-IMPORT.md); wire semantics belong
in [API v1](../architecture/API-V1.md).

## Account data portability

Account archives and full-instance backups have different authority and contents.
Reuse archive review and fresh-ID import; never accept source IDs as current local
authority. See [personal archives](../operations/ACCOUNT-DATA.md) and
[instance backup implementation](../architecture/INSTANCE-BACKUP.md).

## Undo work-session changes

Undo is a conditional operation against recorded history, not an inverse ordinary
write. Retain receipts and report conflicts or pending previews without replay.
See the API contract and session editor/import scenarios.

## Notes collections

Use the owner-bound Notes API, canonical entry IDs and shared MarkdownField.
Folder/document drafts, uploads and navigation guards must survive catalog
updates. Virtual journal/event folders are projections, not a second copy of a
note. See [Notes storage](../architecture/NOTES-VAULT.md) and [journal integration](JOURNAL-EDITOR.md).

### Account storage and administrator Notes quotas

User storage views report owned usage; administrator quotas are installation
policy. Validate limits and current authority on the server. Notes quota changes
are independent of pending authentication/SMTP settings.

### Markdown presentation and note actions (#548)

Presentation settings do not change source text. Open-in-workspace actions flush
pending edits before navigation; failed or conflicting saves keep the original
dialog and draft. See the shared editor guides for fidelity and popup behavior.

## Note search panel

Search generations reject late queries/account changes; signed cursors reject
stale paging. `note:`, `journal:` and `event:` use Notes search, while `file:` uses
the filename catalog. Details alone must not create a note. Opening a recently
indexed event retries `NOTES_SEARCH_INDEXING` within one 15-second budget and is
cancelled by closing, changing selection/query or ending the session. Other
failures remain recoverable without losing the draft. See [Notes architecture](../architecture/NOTES-VAULT.md).
