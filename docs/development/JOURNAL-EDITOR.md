# Daily journal editor layout decision

## Evaluation before implementation

Issue #395 asks for a dedicated Journal tab to be evaluated alongside a dialog
or side panel. The application currently has seven bottom navigation targets;
their icon-only mobile layout already uses the available width at 320px.

| Layout | Advantages | Costs |
| --- | --- | --- |
| Dedicated Journal tab | Persistent entry workspace; natural date picker, previous/next and Today actions; direct opening could default to the account's civil today. | Adds an eighth bottom target, requiring a new mobile navigation arrangement to retain usable touch targets. Tab/date/account navigation would need coordinated draft guards. Calendar-to-Journal and return navigation would also need explicit date/focus restoration. |
| Calendar side panel | Keeps the selected day visible next to the entry on a wide desktop. | Leaves less writing width and has to become a different overlay layout on mobile. |
| Expanded existing dialog | Reuses click, keyboard and long-press entry points and their civil date. Preserves the visible month and restores focus to the same day. A wide, tall writing surface scales to mobile without another bottom target. Native modal behavior keeps background navigation out of the active editing flow. | No independent Journal landing tab or persistent day-to-day navigation; the date is selected in Calendar. Browser navigation and dismissal still need unsaved-draft protection. |

**Decision:** expand the existing calendar day-note dialog into the journal
editor. This preserves the current navigation and provides the most writing
space without introducing a second mobile navigation pattern. The dedicated
tab's proposed account-time-zone default, date navigation and Calendar return
contract are useful requirements for a future independent Journal workspace;
they are not partially implemented as a separate destination in this change.

The short day label remains a dedicated field above the Markdown body. A
separate `journal-text` icon indicates a populated body even without a label;
`journal-plus` offers creation when there is no body. The label badge retains
its own visual meaning. No journal operation changes timed work or totals.

The implementation must retain exact source for unsupported syntax and no-edit
round trips, offer locally bundled formatting and a source toggle, load full
bodies on demand, retain failed/stale drafts with an explicit reload/download
path, protect dirty dismissal/navigation, support read-only viewing and clear
all journal/editor state when the account ends.
