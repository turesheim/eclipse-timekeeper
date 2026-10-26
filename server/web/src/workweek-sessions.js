import {dateTimeFormat} from './formatters.js';
import { getLocale } from './i18n.js';
import { t as i18nText } from "./i18n.js";import { ApiError } from './api.js';
import { instantText } from './model.js';
import { sameSession } from './calendar-model.js';
import { iconButton, projectIcon } from './icons.js';
import { workweekSessionGroups, compactClock, sessionTimeEdit } from './workweek-session-model.js';

const node = (tag, text, css) => {const value = document.createElement(tag);if (text !== undefined) value.textContent = text;if (css) value.className = css;return value;};
export class WorkweekSessions {
  constructor(root, callbacks) {
    this.root = root;this.callbacks = callbacks;
    this.grid = node('div');this.feedback = node('p', '', 'week-session-feedback');this.feedback.setAttribute('role', 'alert');
    this.controls = node('div', undefined, 'week-session-draft');this.controls.hidden = true;
    this.saveButton = node('button', i18nText("ui.save"), 'btn btn-primary btn-sm');this.saveButton.type = 'button';this.saveButton.addEventListener('click', () => void this.save());
    this.cancelButton = node('button', i18nText("ui.cancel"), 'btn btn-outline-secondary btn-sm');this.cancelButton.type = 'button';this.cancelButton.addEventListener('click', () => this.cancel());
    this.reloadButton = node('button', i18nText("ui.reload_current_session"), 'btn btn-outline-secondary btn-sm');this.reloadButton.type = 'button';this.reloadButton.hidden = true;
    this.reloadButton.addEventListener('click', () => {this.cancel();void this.callbacks.onSaved();});
    this.controls.append(this.saveButton, this.cancelButton, this.reloadButton);root.append(this.grid, this.feedback, this.controls);
    this.dialog = node('dialog', undefined, 'app-dialog shadow-lg');this.dialog.id = 'week-session-details';
    const title = node('h2', i18nText("ui.work_session_21aa04"));title.id = 'week-session-details-title';this.dialog.setAttribute('aria-labelledby', title.id);
    this.details = node('div');const actions = node('div', undefined, 'dialog-actions');
    this.editButton = iconButton(node('button'), 'edit', i18nText("ui.edit_session"));this.editButton.type = 'button';this.editButton.addEventListener('click', () => {const id = this.detailId;this.close();this.callbacks.onEdit(id);});
    this.deleteButton = iconButton(node('button'), 'delete', i18nText("ui.delete_session_ba2b6c"));this.deleteButton.type = 'button';this.deleteButton.addEventListener('click', () => {const id = this.detailId;this.close();this.callbacks.onDelete(id);});
    const close = node('button', i18nText("ui.close"), 'btn btn-outline-secondary');close.type = 'button';close.addEventListener('click', () => this.close());
    actions.append(this.editButton, this.deleteButton, close);this.dialog.append(title, this.details, actions);root.after(this.dialog);
    this.dialog.addEventListener('close', () => {this.detailId = null;this.details.replaceChildren();});
  }
  update(snapshot, asOf, format, api, writable, selectedDay) {
    if (this.api && this.api !== api) this.reset();
    if (!this.root.contains(this.grid)) this.root.append(this.grid, this.feedback, this.controls);
    this.snapshot = snapshot;this.api = api;this.asOf = asOf;this.format = format;this.selectedDay = selectedDay;this.setWritable(writable);
    if (this.draft) {
      if (this.draft.first !== snapshot.dates.first || this.draft.zone !== snapshot.profile.data.zoneId) {
        this.draft.review = true;this.feedback.textContent = i18nText("ui.the_week_or_time_zone_changed_cancel_or_reload_to_review_your_proposed_times");this.showControls();
      }
    } else this.paint();
    if (this.detailId) this.paintDetails();
  }
  setWritable(value) {
    this.writable = value;
    for (const input of this.grid.querySelectorAll('input')) input.disabled = !value || !!this.draft?.busy;
    this.editButton.disabled = this.deleteButton.disabled = !value;
    this.showControls();
  }
  tick(asOf) {
    this.asOf = asOf;
    if (this.snapshot && !this.draft && !this.root.closest('#week-panel').hidden && this.snapshot.activeWorkSession.data) this.paint();
  }
  reset() {
    this.close();this.draft = null;this.snapshot = null;this.model = null;this.api = null;this.writable = false;this.asOf = 0n;this.grid.replaceChildren();this.feedback.textContent = '';this.controls.hidden = true;this.root.replaceChildren();
  }
  close() {if (this.dialog.open) this.dialog.close();this.detailId = null;this.details.replaceChildren();}
  hide() {this.close();}
  paint() {
    if (!this.snapshot || this.draft) return;
    const s = this.snapshot,zone = s.profile.data.zoneId;
    this.model = workweekSessionGroups({ sessions: s.workSessions, clients: s.clients, projects: s.projects, first: s.dates.first, zone, asOf: this.asOf, selectedDay: this.selectedDay });
    this.root.parentElement.hidden = !this.model.clients.length;this.grid.replaceChildren();
    if (!this.model.clients.length) {this.close();return;}
    const table = node('table', undefined, 'table align-middle mb-0 week-session-table');table.setAttribute('aria-label', i18nText("ui.work_sessions_by_client_and_project"));
    const head = node('thead'),header = node('tr');
    for (const caption of [i18nText("ui.client_project"), ...this.model.days.map((day) => dateTimeFormat(getLocale(), { weekday: 'short', day: 'numeric', timeZone: 'UTC' }).format(new Date(day.date + 'T12:00:00Z'))), i18nText("ui.weekly_total")]) {
      const th = node('th', caption);th.scope = 'col';header.append(th);
    }
    head.append(header);table.append(head);
    for (const client of this.model.clients) {
      const body = node('tbody'),row = node('tr', undefined, 'week-session-client');row.dataset.clientId = client.id;
      const label = node('th'),name = node('span', client.name, 'client-color');label.scope = 'rowgroup';
      if (/^#[0-9a-f]{6}$/i.test(client.color)) name.style.setProperty('--client-color', client.color);
      label.append(name);row.append(label, ...client.daily.map((value) => node('td', value ? this.format(value) : '—')), node('td', this.format(client.total)));body.append(row);
      for (const project of client.projects) {
        const row = node('tr');row.dataset.weekProject = project.id || '';row.dataset.clientId = client.id;
        const label = node('th', undefined, 'week-session-project'),caption = node('span', undefined, 'project-caption'),glyph = projectIcon(s.projects.find((value) => value.id === project.id));if (glyph) caption.append(glyph);caption.append(node('span', project.name));label.append(caption);label.scope = 'row';row.append(label);
        for (const day of project.days) {
          const cell = node('td');cell.dataset.sessionDate = day.date;
          if (!day.entries.length) cell.append(node('span', '—', 'text-body-secondary'));
          for (const entry of day.entries) cell.append(this.interval(entry, client, project));
          row.append(cell);
        }
        row.append(node('td', this.format(project.total)));body.append(row);
      }
      table.append(body);
    }
    const foot = node('tfoot'),row = node('tr'),label = node('th', i18nText("ui.total"));label.scope = 'row';row.append(label, ...this.model.daily.map((value) => node('td', value ? this.format(value) : '—')), node('td', this.format(this.model.total)));foot.append(row);table.append(foot);this.grid.append(table);
  }
  interval(entry, client, project) {
    const record = entry.session,box = node('div', undefined, 'week-session-interval');box.dataset.workSessionId = record.id;box.dataset.sessionDate = entry.date;
    if (entry.running) box.append(node('span', i18nText("ui.running"), 'visually-hidden'));
    box.title = [record.summary, `${client.name} · ${project.name}`, `${instantText(record.start, this.snapshot.profile.data.zoneId)} → ${instantText(record.end, this.snapshot.profile.data.zoneId)}`].filter(Boolean).join('\n');
    for (const key of ['start', 'end']) {
      if (key === 'end') box.append(node('span', '–', 'week-session-separator'));
      const input = node('input', undefined, 'form-control week-session-time');input.type = 'text';input.value = compactClock(entry[key]);input.placeholder = entry.running && key === 'end' ? i18nText("ui.running") : '';
      input.dataset.endpoint = key;input.readOnly = !entry[key + "Editable"];input.disabled = !this.writable;
      input.setAttribute('aria-label', `${key === 'start' ? i18nText("ui.start") : i18nText("ui.end")} ${record.summary || i18nText("ui.work_session_21aa04")} · ${client.name} · ${project.name} · ${entry.date}`);input.title = box.title;
      if (input.readOnly) input.setAttribute('aria-description', i18nText("ui.continues_across_midnight_edit_the_endpoint_on_its_own_day"));
      input.addEventListener('focus', () => {
        if (input.readOnly || !this.writable) return;
        if (this.draft && this.draft.original.id !== record.id) {input.blur();return;}
        if (!this.draft) this.draft = { original: record, api: this.api, zone: this.snapshot.profile.data.zoneId, first: this.snapshot.dates.first, edits: {} };
        input.value = this.draft.edits[key]?.text ?? (/^\d{2}:\d{2}:00$/.test(entry[key]) ? compactClock(entry[key]) : entry[key]);input.select();
      });
      input.addEventListener('input', () => {
        if (this.draft?.original.id !== record.id || input.readOnly) return;
        this.draft.edits[key] = { date: entry.date, text: input.value, original: entry[key] };if (!this.draft.review) this.feedback.textContent = '';this.showControls();
      });
      input.addEventListener('keydown', (event) => {
        if (event.key === "Enter") {event.preventDefault();void this.save(this.focusKey(input));}
        if (event.key === "Escape") {event.preventDefault();const focus = this.focusKey(input);this.cancel();this.restoreFocus(focus);}
      });
      box.append(input);
    }
    const more = iconButton(node('button'), 'more', i18nText("ui.session_actions", { value0: record.summary || i18nText("ui.work_session_21aa04"), value1: entry.date }));more.type = 'button';more.classList.add('week-session-more');
    more.addEventListener('click', async () => {if (!this.draft || (await this.save())) this.openDetails(record.id);});box.append(more);
    box.addEventListener('focusout', (event) => {
      if (box.contains(event.relatedTarget) || this.controls.contains(event.relatedTarget) || event.relatedTarget?.closest?.('[data-work-session-id]')?.dataset.workSessionId === record.id || this.draft?.original.id !== record.id || this.draft.busy) return;
      const next = this.focusKey(event.relatedTarget);
      if (Object.keys(this.draft.edits).length) void this.save(next);else
      {this.draft = null;queueMicrotask(() => {if (!this.draft) this.paint();});}
    });
    return box;
  }
  focusKey(input) {const entry = input?.closest?.('[data-work-session-id]');return input?.dataset.endpoint && entry ? { id: entry.dataset.workSessionId, date: entry.dataset.sessionDate, key: input.dataset.endpoint } : null;}
  restoreFocus(target) {
    if (!target || !this.writable) return;
    const entry = [...this.grid.querySelectorAll('[data-work-session-id]')].find((node) => node.dataset.workSessionId === target.id && node.dataset.sessionDate === target.date);
    entry?.querySelector(`[data-endpoint="${target.key}"]`)?.focus();
  }
  showControls() {
    this.controls.hidden = !this.draft || !Object.keys(this.draft.edits).length && !this.draft.review;
    this.saveButton.disabled = !this.writable || !!this.draft?.busy || !!this.draft?.review;
    this.cancelButton.disabled = !!this.draft?.busy;this.reloadButton.hidden = !this.draft?.review;
  }
  cancel() {if (this.draft?.busy) return;this.draft = null;this.feedback.textContent = '';this.controls.hidden = true;this.paint();}
  async save(focus = null) {
    const draft = this.draft;if (!draft) return true;
    if (draft.busy || draft.review || !this.writable || draft.api !== this.api) return false;
    try {
      const payload = sessionTimeEdit(draft.original, draft.edits, draft.zone);
      if (payload.start === draft.original.start && payload.end === draft.original.end) {this.cancel();this.restoreFocus(focus);return true;}
      draft.busy = true;this.setWritable(this.writable);this.feedback.textContent = i18nText("ui.saving_work_session_times");
      const loaded = await draft.api.request('/work-sessions/' + draft.original.id);
      if (this.draft !== draft || this.api !== draft.api) return false;
      if (!this.writable || !sameSession(draft.original, loaded.data)) throw new ApiError('STALE_VERSION', 412);
      const saved = await draft.api.request('/work-sessions/' + draft.original.id, { method: 'PUT', body: payload, etag: loaded.etag, undo: true });
      if (this.draft !== draft || this.api !== draft.api) return false;
      this.callbacks.onUndo?.(saved.undo, draft.api);this.draft = null;this.feedback.textContent = '';this.controls.hidden = true;
      await this.callbacks.onSaved();if (this.api === draft.api) this.restoreFocus(focus);return true;
    } catch (error) {
      if (this.draft !== draft || this.api !== draft.api) return false;
      if (error.status === 401) {this.callbacks.onUnauthorized(error);return false;}
      draft.review = error.status === 412 || error.uncertain;
      this.feedback.textContent = draft.review ? i18nText("ui.your_proposed_times_are_kept_reload_the_current_session_before_making_another_change") : error.message;
      return false;
    } finally {if (this.draft === draft) {draft.busy = false;this.setWritable(this.writable);}}
  }
  openDetails(id) {this.detailId = id;this.paintDetails();this.dialog.showModal();}
  paintDetails() {
    const record = this.snapshot.workSessions.find((value) => value.id === this.detailId);this.details.replaceChildren();
    if (!record) {this.details.append(node('p', i18nText("ui.this_work_session_was_removed")));this.editButton.disabled = this.deleteButton.disabled = true;return;}
    const client = this.snapshot.clients.find((value) => value.id === record.clientId),project = this.snapshot.projects.find((value) => value.id === record.projectId);
    this.details.append(node('p', record.summary || i18nText("ui.work_session_21aa04"), 'text-break'), node('p', [client?.name, project?.name].filter(Boolean).join(' · ')), node('p', `${instantText(record.start, this.snapshot.profile.data.zoneId)} → ${instantText(record.end, this.snapshot.profile.data.zoneId)}`));
    this.editButton.setAttribute('aria-label', i18nText("ui.edit_session_6f031a", { value0: record.summary || i18nText("ui.work_session_21aa04") }));this.deleteButton.setAttribute('aria-label', i18nText("ui.delete_session_aaa590", { value0: record.summary || i18nText("ui.work_session_21aa04") }));
    this.editButton.disabled = this.deleteButton.disabled = !this.writable;
  }
}
