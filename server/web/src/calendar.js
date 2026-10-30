import {calendarItemStatus} from './calendar-item.js';
import {eventNoteIndicator} from './event-note-editor.js';
import {dateTimeFormat} from './formatters.js';
import { getLocale } from './i18n.js';
import { t as i18nText } from "./i18n.js";import { addDays, dateInZone, instantNanos, instantText, nanosInstant, localDateTime, nanosText } from './model.js';
import { calendarWeek, calendarPieces, calendarAnchor, calendarRange, calendarLanes, shiftSession, shiftSessionDays, pasteSession, clientColors, CALENDAR_STEP } from './calendar-model.js';
import { iconButton } from './icons.js';
import {CalendarSubscriptions,styleCalendarEvent} from './calendar-subscriptions.js';
import {TrackDayNotes} from './track-day-notes.js';
import {publicHolidayCalendar,holidayRegion,selectedHolidayCountry} from './holidays.js';

const ROW_HEIGHT = 16;
function node(tag, text, className) {
  const value = document.createElement(tag);if (text !== undefined) value.textContent = text;
  if (className) value.className = className;return value;
}
function button(label,operation,className='btn btn-outline-secondary') {
  const value=node('button',label,className);value.type='button';value.addEventListener('click',operation);return value;
}

/** Week presentation and gestures; all persistence remains in the application's versioned editor. */
export class WeekCalendar {
  constructor(root, callbacks) {
    this.root = root;this.callbacks = callbacks;this.connected = false;this.scope = null;
    this.selectedId = null;this.selection = null;this.clipboard = null;this.drag = null;this.busy = false;this.rangeMode = false;
    this.scroll = root.querySelector('.calendar-scroll');this.grid = root.querySelector('#calendar-grid');
    this.message = root.querySelector('#calendar-message');
    this.notes=new TrackDayNotes(root,this.grid,root.querySelector('#calendar-date'),{onUnauthorized:callbacks.onUnauthorized,onChanged:callbacks.onNotesChanged},{prefix:'week',headingSelector:'.calendar-date-row',alwaysShow:true,compact:true,statusAnchor:this.scroll});
    this.controls = Object.fromEntries(['previous', 'next', 'today', 'copy', 'paste', 'edit', 'new', 'range'].map((key) => [key, root.querySelector(`#calendar-${key}`)]));
    for (const [key, glyph, label] of [['previous', 'left', i18nText("ui.previous_week")], ['next', 'right', i18nText("ui.next_week")], ['copy', 'copy', i18nText("ui.copy_work_session")], ['paste', 'paste', i18nText("ui.paste_work_session")], ['edit', 'edit', i18nText("ui.edit_selected_work_session")], ['new', 'add', i18nText("ui.record_work_session_at_selected_time")], ['range', 'clock', i18nText("ui.select_time_range")]])
    iconButton(this.controls[key], glyph, label);
    this.controls.previous.addEventListener('click', () => callbacks.onDate(addDays(this.calendar.first, -7)));
    this.controls.next.addEventListener('click', () => callbacks.onDate(addDays(this.calendar.first, 7)));
    this.controls.today.addEventListener('click', () => callbacks.onDate(dateInZone(this.data.report.generatedAt, this.data.profile.data.zoneId)));
    this.controls.copy.addEventListener('click', () => this.copy());
    this.controls.paste.addEventListener('click', () => this.paste());
    this.controls.edit.addEventListener('click', () => this.edit());
    this.controls.new.addEventListener('click', () => this.create());
    this.controls.range.addEventListener('click', () => this.setRangeMode(!this.rangeMode));
    this.setRangeMode(false);
    root.querySelector('#calendar-date').addEventListener('change', (event) => callbacks.onDate(event.target.value));
    root.addEventListener('keydown', (event) => this.key(event));
    this.subscriptions=new CalendarSubscriptions(root,{
      canWrite:()=>callbacks.canWrite(),onUnauthorized:callbacks.onUnauthorized,onConvert:callbacks.onConvertEvent,onNotesChanged:callbacks.onNotesChanged,onSubscriptionsChanged:callbacks.onSubscriptionsChanged,
      onEvents:plans=>{this.plans=plans;if(this.data&&!root.hidden&&!this.drag&&!this.busy)this.render();}
    });
  }
  reset() {
    this.notes?.reset();
    this.holidayGeneration=(this.holidayGeneration||0)+1;this.holidayKey=null;this.holidayData=null;
    this.subscriptions?.reset();this.plans={items:[],sources:{}};
    this.cancelDrag();this.scope = null;this.data = null;this.calendar = null;this.calendarKey = null;this.scrollWeek = null;this.selectedId = null;
    this.selection = null;this.clipboard = null;this.lastClientId = null;this.busy = false;this.grid.replaceChildren();
    this.message.textContent = '';this.setConnected(false);
  }
  update(data, connected, scope) {
    if (this.scope !== scope) {this.reset();this.scope = scope;}
    this.data = data;this.connected = connected;
    this.subscriptions.update(data,connected,scope);
    if (!this.root.hidden && !this.drag && !this.busy) this.render();else this.buttons();
  }
  show() {this.subscriptions.show();if (this.data && !this.drag && !this.busy) this.render();}
  hide() {this.notes.hide();this.subscriptions.hide();this.cancelDrag();this.setRangeMode(false);}
  setConnected(value) {this.connected = value;this.notes?.setWritable(value&&this.callbacks.canWrite());this.subscriptions?.setConnected(value);if (!value) {this.cancelDrag();this.setRangeMode(false);}this.buttons();}
  setRangeMode(value) {
    this.rangeMode = value;this.root.classList.toggle('calendar-range-mode', value);
    this.controls.range.setAttribute('aria-pressed', String(value));this.controls.range.classList.toggle('active', value);
  }
  selected() {return this.data?.workSessions.find((value) => value.id === this.selectedId);}
  preferredClient() {
    const available = (id) => this.data?.clients.some((value) => value.id === id);
    return [this.lastClientId, this.selected()?.clientId, this.data?.activeWorkSession?.data?.clientId,
    ...[...(this.data?.workSessions || [])].sort((a, b) => b.start.localeCompare(a.start)).map((value) => value.clientId)].find(available);
  }
  rememberClient(id) {if (this.data?.clients.some((value) => value.id === id)) this.lastClientId = id;}
  buttons() {
    const available = this.connected && !this.busy && !this.drag;
    this.controls.copy.disabled = !this.selected() || !available;
    this.controls.edit.disabled = !this.selected() || !available;
    this.controls.paste.disabled = !this.clipboard || !this.selection || !available;
    this.controls.new.disabled = !this.selection || !available;
    this.controls.range.disabled = !available;
    for (const value of this.root.querySelectorAll('.calendar-resize,.calendar-session-select')) value.disabled = !available;
    for (const value of this.root.querySelectorAll('.calendar-session')) value.setAttribute('aria-disabled', String(!available));
  }
  text(value) {this.message.textContent = value;this.message.title = value;}
  render() {
    const data = this.data,zone = data.profile.data.zoneId,key = `${data.dates.first}/${zone}`;
    const active = document.activeElement;
    const focus = this.root.contains(active) ? { session: active.dataset.sessionId, edge: active.dataset.edge, day: active.dataset.date, planned:active.dataset.calendarEventKey, subscription:active.dataset.calendarSubscriptionId, note:active.dataset.weekNoteDate, select: active.classList.contains('calendar-session-select') } : null;
    if (this.calendarKey !== key) {
      this.calendarKey = key;this.calendar = calendarWeek(data.dates.first, zone);this.selection = null;
    }
    const calendar = this.calendar;
    const month = dateTimeFormat(getLocale(), { month: 'long', year: 'numeric', timeZone: 'UTC' });
    const firstMonth = month.format(new Date(`${calendar.first}T12:00:00Z`)),lastMonth = month.format(new Date(`${addDays(calendar.first, 6)}T12:00:00Z`));
    this.root.querySelector('#calendar-title').textContent = firstMonth === lastMonth ? firstMonth : `${firstMonth} – ${lastMonth}`;
    this.root.querySelector('#calendar-date').value = data.dates.first;
    this.root.querySelector('#calendar-zone').textContent = `${calendar.first} – ${addDays(calendar.first, 6)} · ${zone}`;
    this.root.querySelector('#calendar-dst').hidden = !calendar.hasTransitions;
    this.grid.style.setProperty('--calendar-height', `${calendar.rows.length * ROW_HEIGHT}px`);
    this.grid.replaceChildren();
    const corner = node('div', i18nText("ui.time"), 'calendar-corner');this.grid.append(corner);
    const today = dateInZone(data.report.generatedAt, zone);
    this.noteCells=new Map();
    for (const day of calendar.days) {
      const heading = node('div', undefined, `calendar-heading${day.date === today ? ' today' : ''}`);
      heading.dataset.date=day.date;heading.dataset.weekDate=day.date;this.noteCells.set(day.date,heading);
      const title = dateTimeFormat(getLocale(), { weekday: 'short', day: 'numeric', month: 'short', timeZone: 'UTC' }).format(new Date(`${day.date}T12:00:00Z`));
      const pick = button(title, () => {
        pick.focus({ preventScroll: true });
        const source = this.clipboard || this.selected();
        const clock = source ? localDateTime(source.start, zone).slice(11, 16) : null;
        const rounded = clock ? clock.slice(0, 3) + String(Math.floor(Number(clock.slice(3)) / 15) * 15).padStart(2, '0') : null;
        const row = rounded ? calendar.rows.indexOf(`${rounded}/0`) : this.defaultRow(day);
        this.select(day.date, day.byRow.has(row) ? row : this.defaultRow(day));
      });
      pick.classList.add('calendar-date-pick');pick.disabled = day.skipped;pick.setAttribute('aria-label', i18nText("ui.select", { value0: day.date }));const dateRow=node('div',undefined,'calendar-date-row');dateRow.append(pick);heading.append(dateRow);this.grid.append(heading);
    }
    const hours = node('div', undefined, 'calendar-hours');
    calendar.rows.forEach((key, index) => {
      const [clock, occurrence] = key.split('/');if (clock.endsWith(':00') || occurrence === '1' && calendar.rows[index - 1]?.split('/')[1] !== '1') {
        const label = node('span', clock + (occurrence === '1' ? ' (2)' : ''));label.style.top = `${index * ROW_HEIGHT}px`;hours.append(label);
      }
    });this.grid.append(hours);this.dayNodes = new Map();
    for (const day of calendar.days) {
      const weekend = [0, 6].includes(new Date(`${day.date}T12:00:00Z`).getUTCDay());
      const column = node('div', undefined, `calendar-day${weekend ? ' weekend' : ''}`);column.dataset.date = day.date;
      column.tabIndex = day.skipped ? -1 : 0;column.setAttribute('role', 'group');column.setAttribute('aria-label', `${day.date}, ${zone}`);
      calendar.rows.forEach((key, row) => {
        const slot = node('div', undefined, `calendar-slot${day.byRow.has(row) ? '' : ' unavailable'}${key.split('/')[0].endsWith(':00') ? ' hour' : ''}`);
        slot.style.top = `${row * ROW_HEIGHT}px`;slot.setAttribute('aria-hidden', 'true');column.append(slot);
      });
      if (day.skipped) column.append(node('p', i18nText("ui.date_skipped_in_this_time_zone"), 'calendar-skipped'));
      column.addEventListener('click', (event) => {
        if (document.querySelector('#editor').open || event.target.closest('.calendar-event,button,a')) return;
        const row = Math.floor((event.clientY - column.getBoundingClientRect().top) / ROW_HEIGHT);
        if (day.byRow.has(row)) this.select(day.date, row);else this.text(i18nText("ui.this_shaded_clock_time_does_not_occur_on_this_day"));
      });
      column.addEventListener('dblclick', (event) => {if (!event.target.closest('.calendar-event,button,a') && this.connected) this.create();});
      column.addEventListener('pointerdown', (event) => this.beginCreation(event, day));
      this.dayNodes.set(day.date, column);this.grid.append(column);
    }
    const sessions = [];
    for (const session of data.workSessions) for (const piece of calendarPieces(session.start, session.end || data.report.generatedAt, calendar)) sessions.push({ ...piece, session });
    for (const day of calendar.days) for (const piece of calendarLanes(sessions.filter((value) => value.date === day.date), 1.25))
    this.dayNodes.get(day.date).append(this.sessionNode(piece));
    const blocks = [];
    for(const event of this.plans?.items||[])if(!event.allDay)
      for(const piece of calendarPieces(event.start,instantNanos(event.end)===instantNanos(event.start)?nanosInstant(instantNanos(event.end)+1n):event.end,calendar))blocks.push({...piece,planned:event});
    for (const day of calendar.days) for (const piece of calendarLanes(blocks.filter((value) => value.date === day.date), 3))
    this.dayNodes.get(day.date).append(this.plannedNode(piece));
    if (!sessions.some((value) => value.session.id === this.selectedId)) this.selectedId = null;
    if (!this.selection || !calendar.days.some((day) => day.date === this.selection.date)) {
      const day = calendar.days.find((day) => day.date === today && !day.skipped) || calendar.days.find((day) => !day.skipped);
      this.selection = day ? { date: day.date, row: this.defaultRow(day) } : null;
    }
    this.paintSelection();this.buttons();this.notes.update(calendar,this.noteCells,this.scope,data.revision,this.connected&&this.callbacks.canWrite(),focus?.note);this.paintHolidays();
    if (this.scrollWeek !== key) {
      this.scrollWeek = key;this.scroll.scrollTop = Math.max(0, calendar.rows.indexOf('07:00/0') * ROW_HEIGHT);
    }
    if (focus) {
      const target = [...this.grid.querySelectorAll('[data-session-id],[data-calendar-event-key],[data-week-note-date],.calendar-day')].find((value) =>
      focus.note?value.dataset.weekNoteDate===focus.note:focus.planned?value.dataset.calendarEventKey===focus.planned&&value.dataset.calendarSubscriptionId===focus.subscription:
      focus.session ? value.dataset.sessionId === focus.session && value.dataset.edge === focus.edge && value.dataset.date === focus.day && value.classList.contains('calendar-session-select') === focus.select : focus.day && value.classList.contains('calendar-day') && value.dataset.date === focus.day);
      const fallback = focus.session ? [...this.grid.querySelectorAll('[data-session-id]')].find((value) => value.dataset.sessionId === focus.session && value.dataset.edge === focus.edge && value.classList.contains('calendar-session-select') === focus.select) : null;
      (target || fallback)?.focus({ preventScroll: true });
    }
  }
  paintHolidays() {
    // Older servers omit the setting; null explicitly follows the browser region.
    const preference=this.data.profile.data.holidayCountry;
    const country=preference===undefined?holidayRegion(getLocale()):selectedHolidayCountry(preference);
    const key=this.calendar.first+'|'+getLocale()+'|'+country;
    if(this.holidayKey!==key) {
      this.holidayKey=key;this.holidayData=null;
      const generation=this.holidayGeneration=(this.holidayGeneration||0)+1;
      void publicHolidayCalendar({month:this.calendar.first.slice(0,7),days:this.calendar.days},getLocale(),country).then(data=>{
        if(generation!==this.holidayGeneration||!this.data)return;
        this.holidayData=data;if(!this.root.hidden)this.applyHolidays();
      }).catch(()=>{if(generation===this.holidayGeneration)this.holidayKey=null;});
    } else if(this.holidayData)this.applyHolidays();
  }
  applyHolidays() {
    for(const [date,holidays] of this.holidayData.days) {
      const column=this.dayNodes.get(date),heading=this.grid.querySelector(`.calendar-heading[data-date="${date}"]`);
      if(!column||!heading)continue;
      const names=holidays.map(value=>value.name).join(' · ');
      column.classList.add('public-holiday');heading.classList.add('public-holiday');heading.title=names;
      column.setAttribute('aria-label',`${date}, ${this.calendar.zone}, ${names}`);
      if(!heading.querySelector('.calendar-holiday-name'))heading.querySelector('button').append(node('span',names,'calendar-holiday-name'));
    }
  }
  defaultRow(day) {return day.byRow.has(this.calendar.rows.indexOf('09:00/0')) ? this.calendar.rows.indexOf('09:00/0') : day.slots[0]?.row || 0;}
  bounds(value, piece, band) {
    value.style.top = `${piece.top * ROW_HEIGHT}px`;value.style.height = `${(piece.bottom - piece.top) * ROW_HEIGHT}px`;
    const width = band === 'session' ? 22 : 78,base = band === 'session' ? 0 : 22;
    value.style.left = `calc(${base + piece.lane * width / piece.lanes}% + 2px)`;
    value.style.width = `calc(${width / piece.lanes}% - 4px)`;
  }
  sessionNode(piece, preview = false) {
    const session = piece.session,bar = node('div', undefined, `calendar-event calendar-session${preview ? ' preview' : ''}`);
    bar.dataset.sessionId = session.id;bar.dataset.date = piece.date;bar.dataset.version = String(session.version);this.bounds(bar, piece, 'session');
    const client = this.data.clients.find((client) => client.id === session.clientId),colors = clientColors(client?.color);
    bar.style.setProperty('--client-color', colors.background);
    const elapsed = instantNanos(session.end || this.data.report.generatedAt) - instantNanos(session.start);
    const duration = nanosText(elapsed > 0n ? elapsed : 0n);
    const description = `${client?.name || i18nText("ui.client")} · ${session.summary || i18nText("ui.work_session_21aa04")} · ${instantText(session.start, this.calendar.zone)} → ${instantText(session.end, this.calendar.zone)} · ${duration}`;
    bar.title = description;bar.setAttribute('aria-label', description);bar.setAttribute('role', 'group');bar.tabIndex = -1;
    bar.dataset.selected = String(this.selectedId === session.id);
    const label = node('span', undefined, 'calendar-session-label');
    label.append(node('span', session.summary || i18nText("ui.work_session_21aa04"), 'calendar-session-title'), node('span', `${session.end ? '' : i18nText("ui.running_aca7ea")}${duration}`, 'calendar-session-duration'));
    if (preview) bar.append(label);
    if (!preview) {
      const select = button('', () => this.selectSession(session, piece.date, Math.floor(piece.top)), 'calendar-session-select');
      select.tabIndex = 0;
      select.dataset.sessionId = session.id;select.dataset.date = piece.date;
      select.setAttribute('aria-label', description);select.setAttribute('aria-pressed', String(this.selectedId === session.id));
      select.append(label);bar.append(select);
      bar.addEventListener('focusin', () => this.selectSession(session, piece.date, Math.floor(piece.top)));
      bar.addEventListener('click', () => this.selectSession(session, piece.date, Math.floor(piece.top)));
      bar.addEventListener('dblclick', () => this.edit());
      bar.addEventListener('pointerdown', (event) => this.beginDrag(event, session, piece));
      for (const edge of ['start', 'end']) {
        // Handles are shown only on the pieces containing the actual endpoint.
        const endpoint = edge === 'start' ? instantNanos(session.start) : instantNanos(session.end || this.data.report.generatedAt);
        if (edge === 'start' ? endpoint !== piece.start : endpoint !== piece.end) continue;
        const handle = button('', () => {}, `calendar-resize ${edge}`);handle.dataset.edge = edge;handle.dataset.sessionId = session.id;handle.dataset.date = piece.date;
        handle.tabIndex = 0;
        handle.setAttribute('aria-label', i18nText("ui.resize_of", { value0:i18nText('time.'+edge), value1: session.summary || i18nText("ui.work_session") }));handle.title = i18nText("ui.resize_arrow_keys_adjust_15_minutes", { value0:i18nText('time.'+edge) });
        bar.append(handle);
      }
    }return bar;
  }
  plannedNode(piece) {
    const event=piece.planned,value=button('',()=>this.subscriptions.openEvent(event,piece.date),'btn calendar-event calendar-planned');this.bounds(value,piece,'planned');
    value.dataset.calendarEventKey=event.key;value.dataset.calendarSubscriptionId=event.subscriptionId;value.dataset.date=piece.date;
    styleCalendarEvent(value,event);
    value.setAttribute('aria-haspopup','dialog');value.setAttribute('aria-label',i18nText('calendar.event_label',{title:event.title||i18nText('calendar.untitled'),calendar:event.calendarName,client:event.clientName,interval:this.subscriptions.eventDate(event)}));
    const marker=eventNoteIndicator(event);if(marker)value.append(marker);value.title=value.getAttribute('aria-label');value.append(node('small',calendarItemStatus(event)),node('strong',event.title||i18nText('calendar.untitled')),node('span',event.clientName+' · '+event.calendarName));return value;
  }
  select(date, row) {this.selection = { date, row };this.paintSelection();this.buttons();}
  selectSession(session, date, row) {this.selectedId = session.id;this.select(date, row);this.text('');}
  paintSelection() {
    for (const cursor of this.grid.querySelectorAll('.calendar-cursor')) cursor.remove();
    for (const bar of this.grid.querySelectorAll('.calendar-session')) {
      bar.dataset.selected = String(bar.dataset.sessionId === this.selectedId);
      bar.querySelector('.calendar-session-select')?.setAttribute('aria-pressed', bar.dataset.selected);
    }
    if (!this.selection) return;
    const cursor = node('div', undefined, 'calendar-cursor');cursor.style.top = `${this.selection.row * ROW_HEIGHT}px`;this.dayNodes.get(this.selection.date)?.append(cursor);
  }
  copy() {
    const value = this.selected();if (!value || !this.connected || this.busy) return;
    this.clipboard = structuredClone(value);this.text(i18nText("ui.work_session_copied"));this.buttons();
  }
  paste() {
    if (!this.clipboard || !this.connected || this.busy) return;
    try {this.callbacks.onPaste(pasteSession(this.clipboard, calendarAnchor(this.calendar, this.selection.date, this.selection.row), this.calendar.zone));}
    catch (error) {this.text(error.message);}
  }
  create() {
    if (!this.connected || this.busy || !this.selection) return;
    try {const start = calendarAnchor(this.calendar, this.selection.date, this.selection.row);this.callbacks.onNew({ clientId: this.preferredClient(), summary: '', start: nanosInstant(start), end: nanosInstant(start + 3600000000000n) });}
    catch (error) {this.text(error.message);}
  }
  edit() {const value = this.selected();if (value && this.connected && !this.busy) this.callbacks.onEditSession(value.id);}
  async commit(session, proposal) {
    if (this.busy || !this.connected) {this.text(i18nText("ui.this_change_was_not_saved_wait_for_the_connection_to_recover_before_trying_again"));return;}
    if (instantNanos(session.start) === instantNanos(proposal.start) && (session.end === null && proposal.end === null || session.end && proposal.end && instantNanos(session.end) === instantNanos(proposal.end))) return;
    const active = document.activeElement;
    const restore = active?.dataset.sessionId === session.id ? { edge: active.dataset.edge, note:active.dataset.weekNoteDate, select: active.classList.contains('calendar-session-select') } : null;
    const scope = this.scope;this.busy = true;this.buttons();
    try {await this.callbacks.onChange(structuredClone(session), proposal, scope);} finally
    {if (scope === this.scope) {
        this.busy = false;if (!this.root.hidden) {
          this.render();
          // Disabling a native button during the write blurs it. Restore its
          // replacement after a successful save, without escaping a review dialog.
          if (restore && !document.querySelector('#editor').open && (document.activeElement === document.body || this.root.contains(document.activeElement))) {
            const target = [...this.grid.querySelectorAll('[data-session-id]')].find((value) => value.dataset.sessionId === session.id && value.dataset.edge === restore.edge && value.classList.contains('calendar-session-select') === restore.select);
            target?.focus({ preventScroll: true });target?.scrollIntoView({ block: 'nearest', inline: 'nearest', behavior: 'instant' });
          }
        }
      }}
  }
  key(event) {
    if (event.key === "Escape" && this.drag) {event.preventDefault();this.cancelDrag();this.setRangeMode(false);this.text(i18nText("ui.time_selection_cancelled"));return;}
    if (!this.data || event.target.closest('input,select,textarea,a,[data-calendar-event-key],[data-week-note-date],.calendar-subscriptions') || document.querySelector('dialog[open]')) return;
    const command = event.ctrlKey || event.metaKey;
    if (command && event.key.toLowerCase() === 'c' && !window.getSelection()?.toString() && this.selected()) {event.preventDefault();this.copy();return;}
    if (command && event.key.toLowerCase() === 'v' && this.clipboard) {event.preventDefault();this.paste();return;}
    const bar = event.target.closest('[data-session-id]');
    if (bar && ["ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight", "Enter"].includes(event.key)) {
      event.preventDefault();const session = this.data.workSessions.find((value) => value.id === bar.dataset.sessionId);if (!session) return;
      this.selectedId = session.id;
      if (event.key === "Enter") {this.edit();return;}
      try {
        const mode = bar.dataset.edge || 'move',direction = ["ArrowUp", "ArrowLeft"].includes(event.key) ? -1 : 1;
        let delta = BigInt(direction) * CALENDAR_STEP;
        if (event.key === "ArrowLeft" || event.key === "ArrowRight") {
          const endpoint = mode === 'end' ? session.end || this.data.report.generatedAt : session.start;
          const moved = shiftSessionDays({ ...session, start: endpoint, end: null }, direction, this.calendar.zone);
          delta = instantNanos(moved.start) - instantNanos(endpoint);
        }
        const proposal = shiftSession(session, delta, mode, this.data.report.generatedAt);
        void this.commit(session, proposal);
      } catch (error) {this.text(error.message);}return;
    }
    const day = event.target.closest('.calendar-day');
    if (day && ["ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight", "Enter"].includes(event.key)) {
      event.preventDefault();
      if (event.key === "Enter") {this.create();return;}
      let row = this.selection?.row ?? this.defaultRow(this.calendar.days.find((value) => value.date === day.dataset.date));
      let index = this.calendar.days.findIndex((value) => value.date === day.dataset.date);
      if (event.key === "ArrowLeft" || event.key === "ArrowRight") index += event.key === "ArrowLeft" ? -1 : 1;else row += event.key === "ArrowUp" ? -1 : 1;
      const target = this.calendar.days[index];if (!target || target.skipped) return;
      const direction = event.key === "ArrowUp" ? -1 : 1;
      while (row >= 0 && row < this.calendar.rows.length && !target.byRow.has(row)) row += direction;
      if (target.byRow.has(row)) {this.selectedId = null;this.select(target.date, row);this.dayNodes.get(target.date).focus({ preventScroll: true });}
    }
  }
  beginDrag(event, session, piece) {
    if (event.button !== 0 || !this.connected || this.busy || document.querySelector('#editor').open) return;
    event.preventDefault();const bar = event.currentTarget,day = this.dayNodes.get(piece.date);
    (event.target.closest('[data-edge]') || bar.querySelector('.calendar-session-select') || bar).focus({ preventScroll: true });
    this.selectSession(session, piece.date, Math.floor(piece.top));
    const originRow = Math.max(0, Math.min(this.calendar.rows.length - 1, Math.floor((event.clientY - day.getBoundingClientRect().top) / ROW_HEIGHT)));
    this.drag = { scope: this.scope, session: structuredClone(session), piece, bar, pointer: event.pointerId, x: event.clientX, y: event.clientY, lastX: event.clientX, lastY: event.clientY, originTop: day.getBoundingClientRect().top, originRow, mode: event.target.closest('[data-edge]')?.dataset.edge || 'move', moved: false, proposal: null };
    this.captureDrag(event);
  }
  beginCreation(event, day) {
    if (event.button !== 0 || !this.connected || this.busy || this.drag || day.skipped || document.querySelector('#editor').open ||
    event.target.closest('.calendar-event,button,a') || event.pointerType === 'touch' && !this.rangeMode) return;
    const bar = event.currentTarget,row = Math.floor((event.clientY - bar.getBoundingClientRect().top) / ROW_HEIGHT);
    if (!day.byRow.has(row)) {this.text(i18nText("ui.this_shaded_clock_time_does_not_occur_on_this_day"));return;}
    event.preventDefault();bar.focus({ preventScroll: true });
    this.drag = { scope: this.scope, calendar: this.calendar, anchor: { date: day.date, row }, clientId: this.preferredClient(), bar, pointer: event.pointerId,
      x: event.clientX, y: event.clientY, lastX: event.clientX, lastY: event.clientY, mode: 'create', moved: false, proposal: null };
    this.captureDrag(event);
  }
  captureDrag(event) {
    const bar = this.drag.bar;
    bar.setPointerCapture(event.pointerId);
    const move = (value) => {if (this.drag?.pointer === value.pointerId) {this.drag.lastX = value.clientX;this.drag.lastY = value.clientY;this.drag.dirty = true;}};
    const finish = (value) => {
      if (this.drag?.pointer !== value.pointerId) return;
      // A release can arrive before the next animation frame. Commit its actual position.
      this.drag.lastX = value.clientX;this.drag.lastY = value.clientY;this.moveDrag();
      const state = this.drag;if (!state) return;this.cancelDrag();
      if (state.scope !== this.scope || this.root.hidden || !this.connected || !state.moved || !state.proposal) return;
      if (state.mode === 'create') {
        this.setRangeMode(false);this.selectedId = null;
        this.callbacks.onNew({ clientId: state.clientId, summary: '', ...state.proposal });
      } else void this.commit(state.session, state.proposal);
    };
    const cancel = (value) => {if (this.drag?.pointer === value.pointerId) {this.cancelDrag();this.show();}};
    this.drag.dispose = () => {bar.removeEventListener('pointermove', move);bar.removeEventListener('pointerup', finish);bar.removeEventListener('pointercancel', cancel);};
    bar.addEventListener('pointermove', move);bar.addEventListener('pointerup', finish);bar.addEventListener('pointercancel', cancel);
    const tick = () => {
      if (!this.drag) return;
      const rect = this.scroll.getBoundingClientRect(),state = this.drag;
      const vertical = state.lastY < rect.top + 45 ? -14 : state.lastY > rect.bottom - 45 ? 14 : 0;
      const horizontal = state.lastX < rect.left + 35 ? -14 : state.lastX > rect.right - 35 ? 14 : 0;
      if (state.moved && (vertical || horizontal)) {this.scroll.scrollBy(horizontal, vertical);state.dirty = true;}
      if (state.dirty) {state.dirty = false;this.moveDrag();}
      this.animation = requestAnimationFrame(tick);
    };this.animation = requestAnimationFrame(tick);this.buttons();
  }
  moveDrag() {
    const state = this.drag;if (!state) return;
    if (!state.moved && Math.abs(state.lastX - state.x) + Math.abs(state.lastY - state.y) < 5) return;state.moved = true;
    const columns = [...this.dayNodes.values()],target = columns.find((value) => state.lastX >= value.getBoundingClientRect().left && state.lastX < value.getBoundingClientRect().right);
    if (!target) return;
    if (state.mode === 'create') {this.moveCreation(target);return;}
    const origin = this.dayNodes.get(state.piece.date),originTop = origin.getBoundingClientRect().top;
    // The original pointer coordinate must follow scroll movement, not the viewport.
    const deltaRows = Math.round((state.lastY - state.y + state.originTop - originTop) / ROW_HEIGHT);
    const row = Math.max(0, Math.min(this.calendar.rows.length, state.originRow + deltaRows));
    try {
      const delta = calendarAnchor(this.calendar, target.dataset.date, row) - calendarAnchor(this.calendar, state.piece.date, state.originRow);
      const proposal = shiftSession(state.session, delta, state.mode, this.data.report.generatedAt);
      if (state.proposal?.start === proposal.start && state.proposal?.end === proposal.end) return;
      state.proposal = proposal;
      for (const old of this.grid.querySelectorAll('.preview')) old.remove();
      for (const original of this.grid.querySelectorAll('.calendar-session:not(.preview)')) original.classList.toggle('drag-source', original.dataset.sessionId === state.session.id);
      for (const piece of calendarPieces(proposal.start, proposal.end || this.data.report.generatedAt, this.calendar))
      this.dayNodes.get(piece.date)?.append(this.sessionNode({ ...piece, bottom: Math.max(piece.bottom, piece.top + 1.25), lane: 0, lanes: 1, session: { ...state.session, ...proposal } }, true));
      this.text(`${state.mode === 'move' ? i18nText("ui.move") : i18nText("ui.resize") + state.mode}: ${instantText(proposal.start, this.calendar.zone)} → ${instantText(proposal.end, this.calendar.zone)}`);
    } catch (error) {state.proposal = null;this.text(error.message);}
  }
  moveCreation(target) {
    const state = this.drag;
    if (state.calendar !== this.calendar || this.data.dates.first !== this.calendar.first || this.data.profile.data.zoneId !== this.calendar.zone) {
      this.cancelDrag();this.text(i18nText("ui.the_calendar_changed_select_the_time_range_again"));return;
    }
    const row = Math.max(0, Math.min(this.calendar.rows.length, Math.round((state.lastY - target.getBoundingClientRect().top) / ROW_HEIGHT)));
    for (const old of this.grid.querySelectorAll('.preview')) old.remove();
    try {
      state.proposal = calendarRange(this.calendar, state.anchor, { date: target.dataset.date, row });
      for (const piece of calendarPieces(state.proposal.start, state.proposal.end, this.calendar)) {
        const preview = node('div', i18nText("ui.new_work_session"), 'calendar-event calendar-create-preview preview');
        preview.setAttribute('aria-hidden', 'true');preview.style.top = `${piece.top * ROW_HEIGHT}px`;preview.style.height = `${(piece.bottom - piece.top) * ROW_HEIGHT}px`;
        this.dayNodes.get(piece.date).append(preview);
      }
      this.text(i18nText("ui.new_work_session_a5d786", { value0: instantText(state.proposal.start, this.calendar.zone), value1: instantText(state.proposal.end, this.calendar.zone) }));
    } catch (error) {state.proposal = null;this.text(error.message);}
  }
  cancelDrag() {
    if (this.animation) cancelAnimationFrame(this.animation);
    const state = this.drag;this.drag = null;
    state?.dispose?.();
    if (state?.bar.hasPointerCapture(state.pointer)) state.bar.releasePointerCapture(state.pointer);
    for (const value of this.grid.querySelectorAll('.preview')) value.remove();
    for (const value of this.grid.querySelectorAll('.drag-source')) value.classList.remove('drag-source');this.buttons();
  }
}
