import {dateTimeFormat} from './formatters.js';
import { getLocale } from './i18n.js';
import { t as i18nText } from "./i18n.js";import { dateInZone, instantNanos, instantText } from './model.js';
import { monthCalendar, monthSessions, shiftMonth } from './month-model.js';
import { iconButton, projectIcon, isProjectIcon } from './icons.js';
import { MonthPicker } from './month-picker.js';
import {TrackHolidays} from './track-holidays.js';
import {TrackDayNotes} from './track-day-notes.js';
import {workSummaryCard} from './work-summary-card.js';
import {richTooltip} from './tooltips.js';
import {workSessionsTooltip} from './tooltip-content.js';

const node = (tag, text, className) => {const value = document.createElement(tag);if (text !== undefined) value.textContent = text;if (className) value.className = className;return value;};
function compactTime(value) {
  const seconds = value / 1000000000n,hours = seconds / 3600n,minutes = seconds / 60n % 60n;
  return hours ? i18nText('duration.short_hours', { hours, minutes: minutes || '' }) : minutes ? i18nText('duration.short_minutes', { minutes }) : seconds ? i18nText('duration.short_seconds', { seconds }) : i18nText('duration.less_than_second');
}
export class MonthTrack {
  constructor(root, callbacks) {
    this.callbacks = callbacks;
    this.root = root;this.input = root.querySelector('#track-month');
    this.grid = root.querySelector('#track-calendar');this.totals = root.querySelector('#track-clients');
    this.holidays=new TrackHolidays(root,this.grid,this.input);
    this.notes=new TrackDayNotes(root,this.grid,this.input,callbacks);
    this.monthPicker = new MonthPicker(root, () => this.currentMonth(), (month) => this.select(month));
    for (const [id, glyph, label, offset] of [['track-previous', 'left', i18nText("ui.previous_month"), -1], ['track-next', 'right', i18nText("ui.next_month"), 1]]) {
      const button = root.querySelector('#' + id);iconButton(button, glyph, label);
      button.addEventListener('click', () => this.select(shiftMonth(this.currentMonth(), offset)));
    }
    iconButton(root.querySelector('#track-today'), 'clock', i18nText("ui.current_month")).addEventListener('click', () => this.select(null));
    const choose = (report) => {
      try {
        if (this.input.value === this.currentMonth()) {this.input.setCustomValidity('');return;}
        this.calendar = monthCalendar(this.input.value, this.snapshot.profile.data.zoneId);this.select(this.input.value);
      } catch {if (report) {this.input.setCustomValidity(i18nText("ui.choose_a_valid_month"));this.input.reportValidity();}}
    };
    this.input.addEventListener('input', () => choose(false));
    this.input.addEventListener('change', () => choose(true));
    this.narrow = matchMedia('(max-width: 767px)');this.narrow.addEventListener('change', () => {if (this.snapshot) this.paint(false);});
    this.picker = node('dialog', undefined, 'app-dialog shadow-lg');this.picker.id = 'track-session-picker';
    const title = node('h2', i18nText("ui.choose_work_session"));title.id = 'track-session-picker-title';this.picker.setAttribute('aria-labelledby', title.id);
    this.pickerContext = node('p');this.choices = node('div', undefined, 'd-grid gap-2');
    const actions = node('div', undefined, 'dialog-actions'),cancel = node('button', i18nText("ui.cancel"), 'btn btn-outline-secondary');cancel.type = 'button';cancel.addEventListener('click', () => this.closePicker());actions.append(cancel);
    this.picker.append(title, this.pickerContext, this.choices, actions);root.append(this.picker);
    this.picker.addEventListener('close', () => {
      if (this.picker.open) return;
      const origin = this.pickerOrigin;this.pickerOrigin = null;this.picking = null;this.choices.replaceChildren();
      if (origin && this.snapshot && !this.root.hidden && !document.querySelector('#editor[open]')) (this.writable && this.dailyTimes.get(origin.date + '/' + origin.clientId)?.summary || this.input).focus();
    });
  }
  currentDate() {return dateInZone(Number(this.asOf / 1000000n), this.snapshot.profile.data.zoneId);}
  currentMonth() {return this.month || this.currentDate().slice(0, 7);}
  select(month) {this.notes.hide();this.closePicker();this.holidays.hide();this.monthPicker.close();this.month = month;this.input.setCustomValidity('');this.paint(true);}
  update(snapshot, asOf, format, writable, session) {
    this.session=session;
    this.snapshot = snapshot;this.sessionsById = new Map(snapshot.workSessions.map((session) => [session.id, session]));this.asOf = asOf;this.format = format;this.setWritable(writable);this.paint(true);
  }
  setWritable(writable) {
    this.writable = writable;this.notes.setWritable(writable);
    for (const button of this.grid.querySelectorAll('button[data-track-daily-client]')) button.disabled = !writable;
    if (!writable) this.closePicker();
  }
  hide() {this.notes.hide();this.closePicker();this.holidays.hide();this.monthPicker.close();}
  closePicker() {this.picking = null;if (this.picker.open) this.picker.close();this.choices.replaceChildren();}
  sessions(date, clientId) {
    const ids = new Set(this.days?.find((day) => day.date === date)?.clients.get(clientId)?.sessionIds || []);
    return [...ids].map((id) => this.sessionsById.get(id)).filter(Boolean).sort((a, b) => {const first = instantNanos(a.start),second = instantNanos(b.start);return first < second ? -1 : first > second ? 1 : a.id.localeCompare(b.id);});
  }
  open(date, clientId) {
    if (!this.writable) return;
    const sessions = this.sessions(date, clientId);
    if (sessions.length === 1) {this.callbacks.onEditSession(sessions[0].id);return;}
    if (!sessions.length) return;
    this.picking = { date, clientId };this.pickerOrigin = this.picking;this.paintPicker();this.picker.showModal();this.choices.querySelector('button')?.focus();
  }
  paintPicker() {
    if (!this.picking) return;
    const { date, clientId } = this.picking,sessions = this.sessions(date, clientId);
    if (!sessions.length) {this.closePicker();return;}
    const focused = document.activeElement?.dataset.trackSessionChoice;
    this.pickerContext.textContent = `${this.snapshot.clients.find((client) => client.id === clientId)?.name || i18nText("ui.client")} · ${date}`;
    this.choices.replaceChildren();
    for (const session of sessions) {
      const choice = node('button', undefined, 'btn btn-outline-secondary text-start text-break w-100');choice.type = 'button';choice.dataset.trackSessionChoice = session.id;
      choice.append(node('strong', session.summary || i18nText("ui.work_session_21aa04"), 'd-block'));
      const project = this.snapshot.projects.find((project) => project.id === session.projectId);
      if (project) {const line=node('span',undefined,'d-flex align-items-center gap-2 text-body-secondary'),glyph=projectIcon(project);if(glyph){glyph.setAttribute('aria-hidden','true');line.append(glyph);}line.append(node('span',project.name));choice.append(line);}
      choice.append(node('small', `${instantText(session.start, this.snapshot.profile.data.zoneId)} → ${session.end ? instantText(session.end, this.snapshot.profile.data.zoneId) : i18nText("ui.in_progress")}`, 'd-block text-body-secondary'));
      choice.addEventListener('click', () => {
        if (!this.writable || !this.sessions(date, clientId).some((value) => value.id === session.id)) return;
        this.closePicker();this.callbacks.onEditSession(session.id);
      });
      this.choices.append(choice);
    }
    if (focused) ([...this.choices.children].find((choice) => choice.dataset.trackSessionChoice === focused) || this.choices.firstElementChild)?.focus();
  }
  reset() {
    this.notes.reset();this.session=null;
    this.holidays.reset();
    this.monthPicker.close();
    this.closePicker();this.days = null;this.sessionsById?.clear();this.writable = false;this.pickerOrigin = null;this.pickerContext.textContent = '';
    this.snapshot = null;this.month = null;this.calendar = null;this.layout = null;this.grid.replaceChildren();this.totals.replaceChildren();
    this.dailyTimes?.clear();this.clientTimes?.clear();this.input.value = '';this.input.setCustomValidity('');
    this.root.querySelector('#track-total').textContent = '';this.root.querySelector('#track-title').textContent = i18nText("ui.your_month");
  }
  tick(asOf) {
    if (!this.snapshot || this.root.hidden) return;
    this.asOf = asOf;
    if (this.snapshot.activeWorkSession.data || this.currentDate() !== this.date) this.paint(false);
  }
  paint(force) {
    if (!this.snapshot) return;
    const month = this.currentMonth(),zone = this.snapshot.profile.data.zoneId,today = this.currentDate();
    if (this.calendar?.month !== month || this.calendar?.zone !== zone) this.calendar = monthCalendar(month, zone);
    const result = monthSessions(this.calendar, this.snapshot.workSessions, this.asOf);
    this.days = result.days;
    const layout = month + '|' + zone + '|' + today + '|' + [...result.clients.keys()].sort().join(',') + '|' + result.days.map((day) => [...day.clients.keys()].sort().join(',')).join(';');
    if (force || this.layout !== layout) {
      const active = document.activeElement,focused = active?.dataset.trackDailyClient && this.grid.contains(active) ? { date: active.closest('[data-track-date]').dataset.trackDate, client: active.dataset.trackDailyClient } : null;
      const noteFocused=this.grid.contains(active)?active?.dataset.trackNoteDate:null;
      const holidayFocused=this.grid.contains(active)?active?.dataset.trackHolidayDate:null;
      this.layout = layout;this.date = today;this.input.value = month;
      const title = dateTimeFormat(getLocale(), { month: 'long', year: 'numeric', timeZone: 'UTC' }).format(new Date(month + '-15T12:00:00Z'));
      this.root.querySelector('#track-title').textContent = title.charAt(0).toLocaleUpperCase(getLocale()) + title.slice(1);
      this.grid.replaceChildren();this.totals.replaceChildren();this.dailyTimes = new Map();this.clientTimes = new Map();
      const holidayCells=new Map();
      const table = node('table', undefined, 'track-month'),head = node('thead'),row = node('tr'),body = node('tbody');
      table.setAttribute('aria-label', i18nText("ui.work_hours_by_client_and_day"));
      for (let index = 0; index < 7; index++) {
        const date = new Date(Date.UTC(2024, 0, 1 + index)),th = node('th', dateTimeFormat(getLocale(), { weekday: 'short', timeZone: 'UTC' }).format(date));
        th.scope = 'col';th.title = dateTimeFormat(getLocale(), { weekday: 'long', timeZone: 'UTC' }).format(date);row.append(th);
      }
      head.append(row);table.append(head, body);this.grid.append(table);
      const clientsById = new Map(this.snapshot.clients.map((client) => [client.id, client]));
      const projectsById = new Map(this.snapshot.projects.map((project) => [project.id, project]));
      let week;
      for (const [index, day] of result.days.entries()) {
        if (index % 7 === 0) {week = node('tr');body.append(week);}
        const cell = node('td', undefined, day.inMonth ? 'track-day' : "track-day track-outside");cell.dataset.trackDate = day.date;
        holidayCells.set(day.date,cell);
        const date = node('time', String(Number(day.date.slice(-2))), 'track-date');date.dateTime = day.date;
        date.title = dateTimeFormat(getLocale(), { dateStyle: 'full', timeZone: 'UTC' }).format(new Date(day.date + 'T12:00:00Z'));
        if (day.date === today) {date.classList.add('track-today');date.setAttribute('aria-current', 'date');}
        const heading=node('div',undefined,'track-date-row');heading.append(date);cell.append(heading);week.append(cell);
        const entries = [...day.clients.values()].sort((a, b) => {
          const name = (id) => clientsById.get(id)?.name || i18nText("ui.client");
          return name(a.clientId).localeCompare(name(b.clientId),getLocale()) || a.clientId.localeCompare(b.clientId);
        });
        for (const entry of entries) {
          const client = clientsById.get(entry.clientId);
          const {card:summary,line,duration}=workSummaryCard({tag:this.writable?'button':'div',className:'track-client-day',title:client?.name||i18nText('ui.client'),color:client?.color});summary.dataset.trackDailyClient = entry.clientId;
          if (this.writable) {summary.type = 'button';summary.setAttribute('aria-haspopup', 'dialog');summary.addEventListener('click', () => this.open(day.date, entry.clientId));} else
          {summary.setAttribute('role', 'group');summary.tabIndex=0;}
          summary.setAttribute('aria-label', `${client?.name || i18nText("ui.client")} · ${day.date}`);
          duration.classList.add('track-client-day-time');const short = node('span', undefined, 'calendar-summary-time track-client-day-time-short');short.setAttribute('aria-hidden', 'true');
          this.dailyTimes.set(day.date + '/' + entry.clientId, { duration, short, summary, name: client?.name || i18nText("ui.client") });
          line.classList.add('track-client-day-total');const icons = node('span', undefined, 'track-project-icons'),projects = new Map();
          for (const session of this.sessions(day.date, entry.clientId)) {
            const project = projectsById.get(session.projectId);
            if (isProjectIcon(project?.icon)) {const names = projects.get(project.icon) || new Set();names.add(project.name);projects.set(project.icon, names);}
          }
          for (const [id, names] of projects) icons.append(projectIcon({ icon: id, name: [...names].join(', ') }));
          icons.setAttribute('aria-hidden', 'true');line.append(short, icons);
          if (entry.running) summary.append(node('span', i18nText("ui.running"), 'visually-hidden'));
          cell.append(summary);
        }
      }
      const clients = this.snapshot.clients.filter((client) => result.clients.has(client.id)).sort((a, b) => a.name.localeCompare(b.name,getLocale()));
      for (const client of clients) {
        const {card,duration}=workSummaryCard({className:'card track-client',title:client.name,color:client.color,swatch:true});
        card.dataset.trackClient = client.id;this.totals.append(card);this.clientTimes.set(client.id, duration);
        card.tabIndex=0;
      }
      if (!clients.length) this.totals.append(node('p', i18nText("ui.no_work_sessions_this_month"), 'hint'));
      if (focused) this.dailyTimes.get(focused.date + '/' + focused.client)?.summary.focus();
      this.notes.update(this.calendar,holidayCells,this.session,this.snapshot.revision,this.writable,noteFocused);
      this.holidays.update(this.calendar,holidayCells,holidayFocused,this.snapshot.profile.data.holidayCountry);
      this.paintPicker();
    }
    this.root.querySelector('#track-total').textContent = this.format(result.total);
    for (const [clientId, total] of this.clientTimes) {
      total.textContent = this.format(result.clients.get(clientId) || 0n);
      const ids=new Set(result.days.filter(day=>day.inMonth).flatMap(day=>day.clients.get(clientId)?.sessionIds||[]));
      richTooltip(total.closest('[data-track-client]'),()=>workSessionsTooltip({client:this.snapshot.clients.find(client=>client.id===clientId),sessions:[...ids].map(id=>this.sessionsById.get(id)).filter(Boolean),projects:this.snapshot.projects,zone,asOf:this.asOf,total:result.clients.get(clientId),format:this.format}));
    }
    for (const day of result.days) for (const entry of day.clients.values()) {
      const { duration, short, summary, name } = this.dailyTimes.get(day.date + '/' + entry.clientId);
      summary.setAttribute('aria-label', `${name} ${this.narrow.matches ? compactTime(entry.duration) : this.format(entry.duration)} · ${day.date}${this.narrow.matches ? ' · ' + this.format(entry.duration) : ''}${entry.running ? i18nText("ui.running_ceef44") : ''}`);
      richTooltip(summary,()=>workSessionsTooltip({client:this.snapshot.clients.find(client=>client.id===entry.clientId),sessions:this.sessions(day.date,entry.clientId),projects:this.snapshot.projects,zone:this.snapshot.profile.data.zoneId,asOf:this.snapshot.report.generatedAt,total:entry.duration,date:day.date,format:this.format}));duration.textContent = this.format(entry.duration);
      short.textContent = compactTime(entry.duration);
    }
  }
}
