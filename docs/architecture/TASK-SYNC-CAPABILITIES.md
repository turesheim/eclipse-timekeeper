# Task synchronization capability matrix (version 1)

This is the conservative mapping used when building connectors for iCloud
Reminders, Google Tasks v1, and Microsoft Graph To Do v1.0. It describes what
their documented APIs can expose, not a promise that a particular account or
connector has permission to use a field. A connector publishes its tested,
effective `TaskCapabilities` on its Timekeeper connection. `version: 1` is the
schema for that declaration; `providerApiVersion` identifies the API and
connector behavior it was tested against. An omitted field is **unknown** and
must not be treated as unsupported or writable.

In the tables, `R` means read, `W` means write, `D` means an independent
delete/clear operation has been verified, `—` means the documented API lacks
the concept, and `?` means it still needs a connector capability check. These
are API-level candidates; the connection declaration can narrow them based on
permission, account type, task origin, and connector version. Deleting a task
itself is separate from deleting one field. Never clear a Timekeeper value
because a provider cannot read it, and never synthesize a field in a remote
provider to approximate an unsupported Timekeeper value.

## iCloud Reminders through the macOS companion

| Reminders concept | EventKit / companion source | Timekeeper field | Candidate | Fallback |
| --- | --- | --- | --- | --- |
| Title, notes, URL | `EKCalendarItem` title, notes, URL | `summary`, `body`, `url` | RW | Preserve rich formatting in provider data if EventKit only exposes plain text. |
| Completion | `EKReminder.isCompleted`, `completionDate` | `status`, `completedAt` | RW | Do not infer completion time if absent. |
| Start and due | `startDateComponents`, `dueDateComponents` | `start`, `due` | RW | Keep all-day and floating-date semantics. |
| Priority | `EKReminder.priority` | `priority` | RW | Keep separate from `flagged` and `urgent`. |
| Flag | Reminders UI; no mapping in the audited public EventKit declarations or existing companion bridge | `flagged` | ? | Preserve the remote flag and local value until an explicit read/write mapping is tested. Priority is not a flag. |
| Urgent alarm | Reminders UI; no mapping in the audited public EventKit declarations or existing companion bridge | `urgent` | ? | Preserve independently. Do not infer this state from priority, words in a title, or the presence of an ordinary alert. |
| Source timestamps | `EKCalendarItem.creationDate`, `lastModifiedDate` | external reference `source` | R | Preserve source time; do not substitute a Timekeeper revision. |
| Recurrence | `EKRecurrenceRule` | `recurrence.series` | RW | EventKit exposes only the first incomplete reminder in a recurring set; preserve unseen occurrences. |
| Time and location alerts | `EKAlarm` absolute/relative date, structured location and proximity | `reminders.ABSOLUTE`, `.RELATIVE`, `.LOCATION` | RW | Never turn a geofence into a timed alert. |
| List | Reminder calendar | `taskListId` | RW | Timekeeper project remains independent. |
| Tags, sections, grocery, attachments, shared assignment | Reminders UI / companion-specific bridge | `metadata.tags`, list/section `providerData`, `sectionId`, attachments, `assignment` | ? | Preserve in Reminders; declare only fields actually exposed by the companion. |
| Car and messaging triggers | Reminders UI / companion-specific bridge | `reminders.CAR`, `.MESSAGE` | ? | Preserve in Reminders until the companion proves read and write support. |
| Stable identity | EventKit calendar item identifier | External reference and aliases | R | A full sync can change local IDs; replace identity while retaining an alias. |

Apple documents [date components](https://developer.apple.com/documentation/eventkit/ekreminder/duedatecomponents),
[completion](https://developer.apple.com/documentation/eventkit/ekreminder/completiondate),
[recurrence](https://developer.apple.com/documentation/eventkit/creating-a-recurring-event),
[time/location alarms](https://developer.apple.com/documentation/eventkit/setting-an-alarm),
and [identifier instability](https://developer.apple.com/documentation/eventkit/ekcalendaritem/calendaritemidentifier).
The Reminders UI can contain fields beyond public EventKit. The companion must
audit those separately and avoid destructive whole-object updates.

### Audited companion boundary

The [macOS companion source](https://github.com/turesheim/mail-to-reminders/tree/2e15b2ba8daa9f798536b4a9817904d8fa57bb1f)
was reviewed at revision `2e15b2ba8daa9f798536b4a9817904d8fa57bb1f`.
Its ReminderKit bridge supplies guarded operations for pinning a list, setting
Mail source context, updating app-managed hashtags while preserving unrelated
tags, checking subtask placement and grouping existing reminders, and adding
images with stable IDs. These operations do not constitute a general bidirectional
reader for sections, grocery metadata, assignment, flags, urgent state, or
car/message triggers. Image identity checks do not prove that original bytes and
all attachment types can be downloaded. Reading tags internally before a managed
tag write does not establish a paginated tag observation API for synchronization.

The bridge checks framework/selector availability and can reject an operation
on a particular macOS release. Its existing grouping operation also checks list,
parent and nesting restrictions. A connector must publish the narrower effective
capability, not infer general read/write/delete support from a bridge symbol.
The current mail-to-reminder priority heuristic is not an urgent-state reader.
[Apple documents urgent alarms and flags as distinct UI features](https://support.apple.com/en-gb/102484).
The separate companion issue tracks implementing and testing the missing readers
and writes; Timekeeper retains their normalized values and opaque provider data
without claiming access to unreadable remote fields.

## Google Tasks API v1

| Tasks concept | API source | Timekeeper field | Candidate | Fallback |
| --- | --- | --- | --- | --- |
| Title, notes | `title`, `notes` | `summary` (up to 1,024 characters), text `body` | RW | Assigned tasks can prohibit notes; narrow capability per task origin. |
| Completion | `status`, `completed` | `status`, `completedAt` | RW | Only `needsAction` and `completed` map directly. |
| Scheduled day | `due` | `scheduledDate` | RW | The API discards time; never map to a timed deadline. |
| Hierarchy and order | `parent`, `position`, `tasks.move` | `parentTaskId`, `siblingOrder` | RW | Respect provider restrictions on moves and assigned tasks. |
| Lists | task lists | connection and `taskListId` | RW | Keep Timekeeper project independent. |
| Source links and assignment | `links`, `webViewLink`, `assignmentInfo` | provider `assignment` source context and linked resources, separate from task `url` | R | Output-only; do not write as editable task URL. |
| Update and delete state | `updated`, `etag`, `hidden`, `deleted` | source metadata, tombstones | R | Include hidden/deleted/assigned items during reconciliation where permitted. |
| Start time, timed due, reminders, recurrence, importance, flag/urgent, tags, checklist, attachments | No corresponding Tasks v1 fields | Local task fields | — | Retain locally and show a lossy-export warning. |

Google's [task resource](https://developers.google.com/workspace/tasks/reference/rest/v1/tasks)
specifies the date-only behavior of `due`, output-only links and assignment
context, and the limits on assigned tasks. Its [list method](https://developers.google.com/workspace/tasks/reference/rest/v1/tasks/list)
provides the completed, hidden, deleted, and update filters used for overlap
polling and periodic full reconciliation.

## Microsoft Graph To Do v1.0

| To Do concept | Graph source | Timekeeper field | Candidate | Fallback |
| --- | --- | --- | --- | --- |
| Title and body | `title`, `body` (`itemBody`) | `summary`, `body` (`TEXT`/`HTML`) | R; W in the accepted format | Preserve incoming content type; Graph task update documents HTML-only body writes. Escape plain text when encoding it as HTML and sanitize HTML before rendering. |
| Status and completion | `status`, `completedDateTime` | `status`, `completedAt` | RW | Keep richer Graph status in provider data if no normalized equivalent exists. |
| Start and due | `startDateTime`, `dueDateTime` | `start`, `due` | RW | Retain Graph zone and wall time; do not collapse to UTC. |
| Importance | `importance` | `priority` | RW | Do not turn a flagged-email list into task `flagged`. |
| Recurrence | `patternedRecurrence` | `recurrence.series` | RW | Retain provider-only pattern/range details in provider data. |
| Timed reminder | `isReminderOn`, `reminderDateTime` | `reminders.ABSOLUTE` (one alert) | RW | Multiple, location, car, and message triggers remain local. |
| Categories | `categories` | `metadata.categories` | RW | Keep Outlook category identity distinct from Apple tags. |
| Checklist | `checklistItems` collection | `checklistItems` | RWD | A checklist item is not a child `todoTask`. |
| Attachments | `taskFileAttachment` collection | attachments | RWD | Apply size/content limits; use upload sessions for large files. |
| Linked resources | `linkedResources` collection | `metadata.links` and provider data | RWD when permitted | Graph documents CRUD; use provider data for resources with no URL or a native deep link, and retain their application name and other unmapped properties. |
| Lists and built-in/shared metadata | `todoTaskList`, `wellknownListName`, `isShared`, `isOwner` | connection/list flags and optional `sharing` context | R; name W when allowed | Smart and flagged-email views are not independent task ownership. Participant details remain unknown when Graph does not expose them. |
| Parent task, geofence, car, messaging | No corresponding `todoTask` fields | Local task fields | — | Retain locally and warn before lossy export. |
| Identity and change feed | `id`, delta feed, modification time | external reference, alias, cursor/source metadata | R | A list move can change the task ID; reconcile before creating. |

Graph documents the [todoTask resource](https://learn.microsoft.com/en-us/graph/api/resources/todotask?view=graph-rest-1.0),
[todoTaskList](https://learn.microsoft.com/en-us/graph/api/resources/todotasklist?view=graph-rest-1.0),
[file attachments](https://learn.microsoft.com/en-us/graph/api/resources/taskfileattachment?view=graph-rest-1.0),
and [task delta](https://learn.microsoft.com/en-us/graph/api/todotask-delta?view=graph-rest-1.0).

Recurrence capabilities use `recurrence.series` and `recurrence.occurrences`.
A connector that can read only the rule omits occurrence observations, keeping
local completion/exception history. Explicit component entries override the
legacy aggregate `recurrence` declaration. Clears and exception exports must
satisfy the [recurrence dependency gate](TASK-SYNC-PROTOCOL.md#partial-recurrence-observations);
capability flags alone do not establish knowledge of unobserved history.

## Capability declaration and export gate

Connectors write a complete declaration with `PUT
/task-connections/{id}/capabilities` and the connection's `If-Match` ETag.
Each `fields` entry has `readable`, `writable`, `deletable`, and an optional
note explaining a restriction. Write implies read; delete implies write.
Fields not listed are unknown. The declaration is scoped to one Timekeeper
owner and provider account. It survives ordinary connection edits, account
archives, and portable transfers; it does not contain credentials.

Before exporting a task, a connector compares each nonempty Timekeeper field
against the effective connection capability. It may write only a field marked
writable. For unavailable or unknown fields, it keeps the value locally and
reports the field and reason to the user. Before clearing a remote field, it
requires `deletable=true`. The capability map is a safety gate, not a substitute
for per-item restrictions or remote API error handling.

Metadata capabilities can be declared independently as `metadata.tags`,
`metadata.categories`, and `metadata.links`. Category observations and exports
therefore do not need to include Apple tags or unrelated links. Omitted
components remain local; an empty array clears only the observed component.
An explicit component entry overrides the legacy aggregate `metadata` entry.
Do not claim the aggregate is readable merely because one component is exposed.

## Item-level representation checks

A connection capability grants eligibility, not permission to coerce an
individual value. Before writing, the connector checks the actual task origin,
list permission and representable value. In particular:

- Google notes are text; an HTML body must be retained locally with a lossy-export
  warning unless the user explicitly chooses a conversion. Assigned-task
  restrictions can narrow a connection's normal notes/move capability.
- Google `due` represents a scheduled civil day. A timed `due` or `start` stays
  local; it must not silently replace `scheduledDate` or acquire a midnight time.
- [Graph task update](https://learn.microsoft.com/en-us/graph/api/todotask-update?view=graph-rest-1.0)
  documents HTML-only body writes. A `TEXT` value must be encoded as escaped HTML
  when sent through that endpoint; plain text containing `<` is not HTML markup.
- Microsoft has one absolute reminder slot. A list of multiple absolute alerts
  is not an instruction to choose one; retain it and report the limitation.
  Relative, location, car and message triggers remain separate unsupported kinds.
- Recurrence rules must pass the destination's pattern/range validation. Preserve
  unsupported rules and exceptions rather than approximate their schedule.
- A linked resource's application context is not necessarily a browser URL.
  [Graph allows linked resources without a URL](https://learn.microsoft.com/en-us/graph/api/resources/linkedresource?view=graph-rest-1.0).
  Store unrepresentable resources in namespaced provider data; do not invent an
  HTTP URL or discard the resource to satisfy the normalized link schema.

The sync export API reports fields eligible under the declared capabilities;
it does not execute provider requests or certify these value-specific checks.
The connector reports its additional warnings before sending a write and only
advances the accepted baseline after the provider confirms it. This boundary
also applies to rate limits, attachment size/type limits and provider ETags.

Source links are output-only in Google Tasks. Declare `metadata.links` readable
but not writable/deletable for that adapter. A link's `providerId` and `remoteId`
do not establish account ownership: two accounts can use identical IDs. The
external reference and its baseline retain the account/list/item context.
Link arrays remain one conflict component. If a provider-only observation
conflicts with a local or other-account link, preserve the complete local array
and present the conflict for explicit reconciliation. Do not auto-merge by URL,
provider ID or title, or advance the baseline as if unrelated links had been
observed. This policy is covered by the two-account source-link HTTP regression;
it does not promise independent per-link automatic merges.
