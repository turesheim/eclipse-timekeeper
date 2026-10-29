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

## Google Tasks API v1

| Tasks concept | API source | Timekeeper field | Candidate | Fallback |
| --- | --- | --- | --- | --- |
| Title, notes | `title`, `notes` | `summary`, text `body` | RW | Assigned tasks can prohibit notes; narrow capability per task origin. |
| Completion | `status`, `completed` | `status`, `completedAt` | RW | Only `needsAction` and `completed` map directly. |
| Scheduled day | `due` | `scheduledDate` | RW | The API discards time; never map to a timed deadline. |
| Hierarchy and order | `parent`, `position`, `tasks.move` | `parentTaskId`, `siblingOrder` | RW | Respect provider restrictions on moves and assigned tasks. |
| Lists | task lists | connection and `taskListId` | RW | Keep Timekeeper project independent. |
| Source links and assignment | `links`, `webViewLink`, `assignmentInfo` | provider `assignment` source context and linked resources, separate from task `url` | R | Output-only; do not write as editable task URL. |
| Update and delete state | `updated`, `etag`, `hidden`, `deleted` | source metadata, tombstones | R | Include hidden/deleted/assigned items during reconciliation where permitted. |
| Start time, timed due, reminders, recurrence, importance, tags, checklist, attachments | No corresponding Tasks v1 fields | Local task fields | — | Retain locally and show a lossy-export warning. |

Google's [task resource](https://developers.google.com/workspace/tasks/reference/rest/v1/tasks)
specifies the date-only behavior of `due`, output-only links and assignment
context, and the limits on assigned tasks. Its [list method](https://developers.google.com/workspace/tasks/reference/rest/v1/tasks/list)
provides the completed, hidden, deleted, and update filters used for overlap
polling and periodic full reconciliation.

## Microsoft Graph To Do v1.0

| To Do concept | Graph source | Timekeeper field | Candidate | Fallback |
| --- | --- | --- | --- | --- |
| Title and body | `title`, `body` (`text`/`html`) | `summary`, `body` | RW | Preserve HTML when supplied; sanitize before rendering. |
| Status and completion | `status`, `completedDateTime` | `status`, `completedAt` | RW | Keep richer Graph status in provider data if no normalized equivalent exists. |
| Start and due | `startDateTime`, `dueDateTime` | `start`, `due` | RW | Retain Graph zone and wall time; do not collapse to UTC. |
| Importance | `importance` | `priority` | RW | Do not turn a flagged-email list into task `flagged`. |
| Recurrence | `patternedRecurrence` | `recurrence.series` | RW | Retain provider-only pattern/range details in provider data. |
| Timed reminder | `isReminderOn`, `reminderDateTime` | `reminders.ABSOLUTE` (one alert) | RW | Multiple, location, car, and message triggers remain local. |
| Categories | `categories` | `metadata.categories` | RW | Keep Outlook category identity distinct from Apple tags. |
| Checklist | `checklistItems` collection | `checklistItems` | RWD | A checklist item is not a child `todoTask`. |
| Attachments | `taskFileAttachment` collection | attachments | RWD | Apply size/content limits; use upload sessions for large files. |
| Linked resources | `linkedResources` collection | links | RW | Verify delete support and permissions before declaring D. |
| Lists and built-in/shared metadata | `todoTaskList`, `wellknownListName`, `isShared`, `isOwner` | connection/list flags and optional `sharing` context | R; name W when allowed | Smart and flagged-email views are not independent task ownership. Participant details remain unknown when Graph does not expose them. |
| Parent task, geofence, car, messaging | No corresponding `todoTask` fields | Local task fields | — | Retain locally and warn before lossy export. |
| Identity and change feed | `id`, delta feed, modification time | external reference, alias, cursor/source metadata | R | A list move can change the task ID; reconcile before creating. |

Graph documents the [todoTask resource](https://learn.microsoft.com/en-us/graph/api/resources/todotask?view=graph-rest-1.0),
[todoTaskList](https://learn.microsoft.com/en-us/graph/api/resources/todotasklist?view=graph-rest-1.0),
[file attachments](https://learn.microsoft.com/en-us/graph/api/resources/taskfileattachment?view=graph-rest-1.0),
and [task delta](https://learn.microsoft.com/en-us/graph/api/todotask-delta?view=graph-rest-1.0).

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
