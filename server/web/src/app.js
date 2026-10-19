import { getLocale, localeForPreference } from './i18n.js';
import { t as i18nText } from "./i18n.js";import { Api, ApiError } from './api.js';
import { addDays, dateInZone, dayStart, durationText, durationNanos, instantText, instantNanos, weekRange, taskPresentation, activityDayNanos, nanosText, decimalHoursText, workweekGroups, sessionTimeNanos, localDateTime, localDateTimeToInstant, localDateTimeOffsets } from './model.js';
import { authentication } from './auth.js';
import { icon, iconButton, projectIcon } from './icons.js';
import { WeekCalendar } from './calendar.js';
import { sameSession } from './calendar-model.js';
import { MonthTrack } from './track.js';
import { WorkweekSessions } from './workweek-sessions.js';
import { createProjectIconPicker } from './project-icon-picker.js';
import { UndoNotice } from './undo.js';
import { accountMenu } from './account-menu.js';
import { periodSwipe } from './period-swipe.js';
import { shiftMonth } from './month-model.js';

const $ = (id) => document.getElementById(id);
accountMenu($('account-menu'));
$('about-open').addEventListener('click', () => $('server-info').showModal());
$('about-close').addEventListener('click', () => $('server-info').close());
$('server-info').addEventListener('close', () => {if (!$('session').hidden) $('account-menu').querySelector('summary').focus();});
let api = null,snapshot = null,connected = false,refreshing = null,reloadAgain = false;
let editor = null,selectedActivity = null,selectedDay = null;
let watchSession = null;
let accountName = '',canWrite = false;
let serverInfoRequest = 0;
let snapshotAt = 0,activeAsOf = 0n;
const collapsed = new Set(),collapsedClients = new Set();
let decimalHours = false,activitiesCollapsed = false;
const displayNanos = (value) => decimalHours ? decimalHoursText(value) : nanosText(value);
const displayDuration = (value) => decimalHours ? decimalHoursText(durationNanos(value)) : durationText(value);
const calendar = new WeekCalendar($('calendar-panel'), {
  taskLabel,
  onDate: selectWeek,
  onEditActivity: (id) => void openRecord('activities', id).catch((error) => setStatus(errorText(error))),
  onEditSession: (id) => void openRecord('work-sessions', id).catch((error) => setStatus(errorText(error))),
  onNew: (input) => void openRecord('work-sessions', null, input).catch((error) => setStatus(errorText(error))),
  onPaste: (input) => void openRecord('work-sessions', null, input, i18nText("ui.paste_work_session")).catch((error) => setStatus(errorText(error))),
  onChange: changeCalendarSession
});
const track = new MonthTrack($('track-panel'), { onEditSession: (id) => void openRecord('work-sessions', id).catch((error) => setStatus(errorText(error))) });
const undoNotice = new UndoNotice($('undo-notice'), {
  available: (session) => session === api && connected && canWrite && !editor && (!weekSessions.draft || !weekSessions.draft.busy && !weekSessions.draft.review && !Object.keys(weekSessions.draft.edits).length),
  changed: async (session) => {if (session === api) {if (weekSessions.draft && !weekSessions.draft.busy && !weekSessions.draft.review && !Object.keys(weekSessions.draft.edits).length) weekSessions.cancel();connected = false;readOnlyWhileDisconnected();await refresh().catch(() => {});}},
  unauthorized: (error) => auth.clear(errorText(error))
});
const weekSessions = new WorkweekSessions($('week-sessions'), {
  onUndo: (info, session) => undoNotice.offer(info, session),
  onEdit: (id) => void openRecord('work-sessions', id).catch((error) => setStatus(errorText(error))),
  onDelete: (id) => void openDelete('work-sessions', id).catch((error) => setStatus(errorText(error))),
  onSaved: async () => {connected = false;readOnlyWhileDisconnected();await refresh().catch(() => {});},
  onUnauthorized: (error) => auth.clear(errorText(error))
});
function selectWeek(date) {
  if (!date) return;
  $('week-date').value = date;selectedDay = null;void refresh().catch(() => {});
}
periodSwipe($('workspace'), {
  enabled: () => snapshot && connected && !weekSessions.draft && !calendar.drag && !calendar.busy && !calendar.rangeMode &&
  ['track', 'week', 'activities', 'calendar'].includes($('workspace').dataset.view) &&
  !document.querySelector('dialog[open], [popover]:popover-open'),
  navigate: (direction) => {
    if ($('workspace').dataset.view === 'track') {
      track.select(shiftMonth(track.currentMonth(), direction));
      $('period-announcement').textContent = $('track-title').textContent;
    } else {
      const date = addDays(weekRange($('week-date').value, snapshot.profile.data.zoneId).first, direction * 7);selectWeek(date);
      $('period-announcement').textContent = i18nText("ui.week_of") + new Intl.DateTimeFormat(getLocale(), { dateStyle: 'long', timeZone: 'UTC' }).format(new Date(date + 'T12:00:00Z'));
    }
  }
});
function startWatch(session) {
  if (watchSession === session) return;
  watchSession = session;
  void watch(session).finally(() => {if (watchSession === session) watchSession = null;});
}
function element(tag, text, className) {
  const node = document.createElement(tag);if (text !== undefined) node.textContent = text;
  if (className) node.className = className;return node;
}
function button(text, action) {
  const node = element('button', text, 'btn btn-outline-secondary');node.type = 'button';node.addEventListener('click', action);return node;
}
function errorText(error) {return error instanceof ApiError ? error.message : i18nText("ui.check_the_entered_values_then_try_again");}
function setStatus(text, state = 'error') {
  $('status').textContent = text;$('status').dataset.state = state;
  $('status-icon').replaceChildren(icon({ ready: 'check', working: 'refresh', notice: 'warning', error: 'error' }[state] || 'warning'));
  $('refresh').dataset.state = state;$('refresh').title = i18nText("ui.click_to_refresh", { value0: text });
  $('refresh').setAttribute('aria-label', i18nText("ui.refresh", { value0: text }));
}
function clearServerInfo() {
  $('server-build').textContent = i18nText("ui.server_build_information_unavailable");
  for (const field of ['version', 'spk', 'commit', 'built', 'deployed', 'started']) $(`server-${field}`).textContent = '—';
}
async function refreshServerInfo(session) {
  const request = ++serverInfoRequest;
  try {
    const { data } = await session.request('/server');
    if (session !== api || request !== serverInfoRequest) return;
    const date = (value) => value ? instantText(value, snapshot.profile.data.zoneId) : i18nText("ui.not_recorded");
    const build = `${data.version}${data.commit ? ` · ${data.commit.slice(0, 8)}` : i18nText("ui.commit_not_recorded")}${data.dirty ? i18nText("ui.modified_source") : ''}`;
    $('server-build').textContent = i18nText("ui.server_deployed", { value0: build, value1: data.spkVersion ? i18nText("ui.spk", { value0: data.spkVersion }) : '', value2: date(data.deployedAt) });
    $('server-version').textContent = data.version;
    $('server-spk').textContent = data.spkVersion || i18nText("ui.not_a_synology_package");
    $('server-commit').textContent = data.commit ? `${data.commit}${data.dirty ? i18nText("ui.modified_source_55f866") : ''}` : i18nText("ui.not_recorded");
    $('server-built').textContent = date(data.builtAt);
    $('server-deployed').textContent = date(data.deployedAt);
    $('server-started').textContent = date(data.startedAt);
  } catch {
    if (session === api && request === serverInfoRequest) clearServerInfo();
  }
}
function readOnlyWhileDisconnected() {
  for (const id of ['add-client', 'add-task', 'add-project', 'add-label', 'add-activity', 'stop', 'session-start', 'session-stop', 'session-new', 'session-edit', 'session-delete']) $(id).disabled = !connected;
  for (const action of document.querySelectorAll('td.actions button')) action.disabled = !connected;
  calendar.setConnected(connected);
  track.setWritable(connected && canWrite);
  weekSessions.setWritable(connected && canWrite);
  updateSessionActions();
}
function tab(name) {
  for (const item of ['track', 'week', 'activities', 'calendar', 'tasks', 'clients', 'projects', 'labels']) $(`${item}-panel`).hidden = item !== name;
  $('week-toolbar').hidden = !['week', 'activities'].includes(name);$('week-report-title').textContent = name === 'activities' ? i18nText("ui.your_activities") : i18nText("ui.your_workweek");$('add-activity').hidden = name !== 'activities';
  $('workspace').dataset.view = name;if (name === 'calendar') calendar.show();else calendar.hide();
  if (name !== 'track') track.hide();
  if (name !== 'week') weekSessions.hide();
  for (const item of document.querySelectorAll('[data-tab]')) {
    item.classList.toggle('active', item.dataset.tab === name);
    if (item.dataset.tab === name) item.setAttribute('aria-current', 'page');else item.removeAttribute('aria-current');
  }
}
for (const node of document.querySelectorAll('[data-tab]')) node.addEventListener('click', () => tab(node.dataset.tab));

const auth = authentication({
  async connect(session, account) {
    api = session;accountName = account.displayName;canWrite = account.permissions.includes('write');
    const { data: profile } = await session.initializeTimeZone();
    if (session !== api) return;
    if (localeForPreference(profile.locale) !== getLocale()) {location.reload();return false;}
    $('week-date').value = dateInZone(Date.now(), profile.zoneId);
    $('login').hidden = true;$('workspace').hidden = false;$('session').hidden = false;
    await refresh();startWatch(session);
  },
  updateAccount(account) {accountName = account.displayName;},
  refreshProfile: (profile) => {
    // The preference write is already committed. New editors must use that
    // confirmed zone even while hidden reports are still being refreshed.
    if (profile && snapshot) snapshot.profile = profile;
    return refresh();
  },
  clear() {
    undoNotice.clear();weekSessions.reset();$('week-sessions-section').hidden = true;$('work-session-labels').replaceChildren();$('work-session-labels').hidden = true;
    refreshing = null;reloadAgain = false;
    ++serverInfoRequest;clearServerInfo();api?.close();api = null;snapshot = null;connected = false;editor = null;selectedActivity = null;selectedDay = null;accountName = '';
    $('server-info').close();
    calendar.reset();track.reset();collapsed.clear();collapsedClients.clear();canWrite = false;
    $('period-announcement').textContent = '';
    $('editor').close();$('fields').replaceChildren();$('editor-error').textContent = '';$('editor-notice').textContent = '';
    for (const id of ['clients', 'projects', 'tasks', 'labels', 'activities', 'days']) $(id).replaceChildren();
    for (const id of ['owner', 'status', 'active-title', 'active-details', 'work-session-current', 'work-session-state', 'work-session-elapsed', 'zone-caption', 'week-total']) $(id).textContent = '';
    $('login').hidden = false;$('workspace').hidden = true;$('session').hidden = true;$('account-menu').open = false;$('remember-notice').textContent = '';
    tab('track');
  }
});
$('refresh').addEventListener('click', () => {const session = api;setStatus(i18nText("ui.refreshing"), 'working');void refresh().then(() => {if (session === api) startWatch(session);}).catch(() => {});});
$('week-date').addEventListener('change', () => {selectedDay = null;void refresh().catch(() => {});});
$('duration-format').addEventListener('change', () => {decimalHours = $('duration-format').value === 'decimal';if (snapshot) render();});
$('collapse-activities').addEventListener('click', () => {activitiesCollapsed = true;collapsed.clear();if (snapshot) render();});
$('expand-activities').addEventListener('click', () => {activitiesCollapsed = false;collapsed.clear();if (snapshot) render();});

async function refresh() {
  if (!api) return;
  if (refreshing) {reloadAgain = true;await refreshing;return;}
  const session = api;
  const pending = (async () => {
    $('workspace').setAttribute('aria-busy', 'true');
    $('refresh').disabled = true;
    if (!snapshot || !connected) setStatus(i18nText("ui.refreshing"), 'working');
    do {
      reloadAgain = false;
      const date = $('week-date').value;
      const current = await session.snapshot((zone) => weekRange(date, zone));
      if (session !== api) return;
      if (date !== $('week-date').value) {reloadAgain = true;continue;}
      // A save may have queued another read while this older snapshot was in
      // flight. Publish only the final snapshot before enabling dependent forms.
      if (reloadAgain) continue;
      snapshot = current;snapshotAt = performance.now();activeAsOf = instantNanos(current.report.generatedAt);connected = true;
      render();setStatus(i18nText("ui.up_to_date"), 'ready');
      void refreshServerInfo(session);
    } while (reloadAgain && session === api);
  })();
  refreshing = pending;
  try {await pending;}
  catch (error) {
    if (session === api) {if (error.status === 401) auth.clear(errorText(error));else {connected = false;setStatus(errorText(error));readOnlyWhileDisconnected();}}
    throw error;
  } finally {if (refreshing === pending) {refreshing = null;$('refresh').disabled = false;$('workspace').setAttribute('aria-busy', 'false');}}
}
async function watch(session) {
  let delay = 1000;
  while (session === api) {
    try {
      if (!connected || !snapshot) await refresh();
      const changes = await session.changes(snapshot.revision);
      if (session !== api) return;
      // Full consistent reads include changes made during pagination/report loading.
      if (changes.items.length || changes.hasMore || snapshot.active.data || snapshot.activeWorkSession.data) await refresh();else
      void refreshServerInfo(session);
      delay = 1000;
    } catch (error) {
      if (session !== api) return;
      if (error.code === 'RESYNC_REQUIRED') {connected = false;continue;}
      connected = false;setStatus(errorText(error));readOnlyWhileDisconnected();
      if (error.status === 401) {auth.clear(errorText(error));return;}
      if (error.status === 403) return;
      await new Promise((resolve) => setTimeout(resolve, delay));delay = Math.min(delay * 2, 10000);
    }
  }
}
function table(container, headings, rows) {
  const root = $(container);root.replaceChildren();
  if (!rows.length) {
    const [title, hint] = {
      clients: [i18nText("ui.no_clients_yet"), i18nText("ui.add_a_client_to_organize_projects_and_work_sessions")],
      projects: [i18nText("ui.no_projects_yet"), i18nText("ui.create_a_project_and_choose_its_client")],
      tasks: [i18nText("ui.no_tasks_yet"), i18nText("ui.create_a_task_to_start_tracking_your_work")],
      labels: [i18nText("ui.no_labels_yet"), i18nText("ui.add_labels_to_categorize_your_activities")],
      activities: [i18nText("ui.no_activities_this_week"), i18nText("ui.choose_another_week_start_a_task_or_add_time")]
    }[container];
    const empty = element('div', undefined, 'empty');
    empty.append(icon('clock'), element('h3', title), element('p', hint));root.append(empty);return;
  }
  const node = element('table', undefined, `table table-hover align-middle mb-0${container === 'activities' ? '' : ' record-table'}`),thead = element('thead'),header = element('tr'),tbody = element('tbody');
  for (const heading of headings) {const th = element('th', heading);th.scope = 'col';header.append(th);}
  thead.append(header);node.append(thead, tbody);
  for (const row of rows) {for (const [index, cell] of [...row.children].entries()) cell.dataset.label = headings[index];tbody.append(row);}root.append(node);
}
function row(values, actions = []) {
  const node = element('tr');for (const value of values) {const cell = element('td');cell.append(value instanceof Node ? value : document.createTextNode(value));node.append(cell);}
  const cell = element('td', undefined, 'actions');for (const action of actions) cell.append(action);node.append(cell);return node;
}
function action(text, operation) {
  const node = button('', () => {void operation().catch((error) => setStatus(errorText(error)));});
  iconButton(node, text === i18nText("ui.start") ? 'start' : text === i18nText("ui.delete") || text.startsWith(i18nText("ui.delete_session")) ? 'delete' : text === i18nText("ui.remove_from_session") ? 'unlink' : 'edit', text);
  node.disabled = !connected;return node;
}
function name(list, id, field = 'name') {const value = list.find((v) => v.id === id);return value && field === 'summary' ? taskPresentation(value).title : value?.[field] ?? i18nText("ui.unassigned");}
function taskLabel(task) {
  if (!task) return element('span', i18nText("ui.unassigned"));
  const { title, url } = taskPresentation(task),node = element(url ? 'a' : 'span', title, 'task-title');
  node.dataset.taskId = task.id;
  if (url) {node.href = url;node.target = '_blank';node.rel = "noopener noreferrer";}
  return node;
}
function renderProjects(s) {
  if (!s.projects.length) {table('projects', [i18nText("ui.name"), i18nText("ui.actions")], []);return;}
  const root = $('projects'),grid = element('table', undefined, 'table align-middle mb-0 projects-table');
  grid.setAttribute('aria-label', i18nText("ui.projects_by_client"));
  const head = element('thead'),header = element('tr');
  for (const caption of [i18nText("ui.client_project"), i18nText("ui.actions")]) {const cell = element('th', caption);cell.scope = 'col';header.append(cell);}
  head.append(header);grid.append(head);
  const clients = [...s.clients].sort((a, b) => a.name.localeCompare(b.name, getLocale(), { numeric: true, sensitivity: 'base' }) || a.id.localeCompare(b.id));
  for (const client of clients) {
    const projects = s.projects.filter((project) => project.clientId === client.id).sort((a, b) => a.name.localeCompare(b.name, getLocale(), { numeric: true, sensitivity: 'base' }) || a.id.localeCompare(b.id));
    if (!projects.length) continue;
    const body = element('tbody'),group = element('tr', undefined, 'projects-client'),label = element('th');
    body.dataset.clientId = client.id;label.colSpan = 2;label.scope = 'rowgroup';
    const toggle = button('', () => {
      if (collapsedClients.has(client.id)) collapsedClients.delete(client.id);else collapsedClients.add(client.id);
      updateToggle();
    });
    toggle.className = 'btn projects-client-toggle';
    const name = element('span', client.name, 'client-color');
    if (/^#[0-9a-f]{6}$/i.test(client.color)) name.style.setProperty('--client-color', client.color);
    const count = element('span', String(projects.length), 'badge text-bg-secondary');count.setAttribute('aria-label', i18nText('count.projects',{count:projects.length}));
    label.append(toggle);group.append(label);body.append(group);
    for (const project of projects) {
      const caption = element('span', undefined, 'project-caption'),glyph = projectIcon(project);if (glyph) caption.append(glyph);caption.append(element('span', project.name));
      const record = row([caption], [action(i18nText("ui.edit"), () => openRecord('projects', project.id)), action(i18nText("ui.delete"), () => openDelete('projects', project.id))]);
      record.dataset.projectId = project.id;record.firstElementChild.className = 'projects-project-name';body.append(record);
    }
    function updateToggle() {
      const expanded = !collapsedClients.has(client.id);
      toggle.replaceChildren(icon(expanded ? 'down' : 'right'), name, count);
      toggle.setAttribute('aria-expanded', String(expanded));toggle.setAttribute('aria-label', `${expanded ? i18nText("ui.collapse") : i18nText("ui.expand")} ${client.name}`);
      for (const row of body.querySelectorAll('[data-project-id]')) row.hidden = !expanded;
    }
    updateToggle();grid.append(body);
  }
  root.replaceChildren(grid);
}
function render() {
  const s = snapshot,zone = s.profile.data.zoneId;
  $('owner').textContent = accountName;
  $('zone-caption').textContent = `${s.dates.first} – ${addDays(s.dates.first, 6)} · ${zone}`;
  const active = s.active.data;
  $('active-title').closest('.tracking').dataset.active = String(!!active);
  $('active-title').replaceChildren(active ? taskLabel(s.tasks.find((task) => task.id === active.taskId)) : document.createTextNode(i18nText("ui.ready_when_you_are")));
  $('active-details').textContent = active ? i18nText("ui.started", { value0: instantText(active.start, zone), value1: active.summary || i18nText("ui.tracking_time") }) : i18nText("ui.choose_a_task_below_to_start_tracking");
  $('stop').hidden = !active;readOnlyWhileDisconnected();
  table('clients', [i18nText("ui.name"), i18nText("ui.color"), i18nText("ui.actions")], s.clients.map((value) => row([value.name, colorSwatch(value.color)], [action(i18nText("ui.edit"), () => openRecord('clients', value.id)), action(i18nText("ui.delete"), () => openDelete('clients', value.id))])));
  renderProjects(s);
  table('tasks', [i18nText("ui.task"), i18nText("ui.project"), i18nText("ui.parent"), i18nText("ui.actions")], s.tasks.map((value) => row([taskLabel(value), name(s.projects, value.projectId), value.parentTaskId ? name(s.tasks, value.parentTaskId, 'summary') : '—'], [action(i18nText("ui.start"), () => openStart(value.id)), action(i18nText("ui.edit"), () => openRecord('tasks', value.id)), action(i18nText("ui.delete"), () => openDelete('tasks', value.id))])));
  table('labels', [i18nText("ui.name"), i18nText("ui.color"), i18nText("ui.actions")], s.labels.map((value) => row([value.name, /^#[0-9a-f]{6}$/i.test(value.color) ? colorSwatch(value.color) : value.color || '—'], [action(i18nText("ui.edit"), () => openRecord('labels', value.id)), action(i18nText("ui.delete"), () => openDelete('labels', value.id))])));
  refreshLabelFields();refreshClientChoices();renderSession();
  $('week-total').textContent = displayDuration(s.report.total);$('days').replaceChildren();
  for (let index = 0; index < 7; index++) {
    const date = addDays(s.dates.first, index),day = s.report.days.find((v) => v.date === date);
    const card = element('div', undefined, `day${date === selectedDay ? ' selected' : ''}`);
    const pick = button('', () => {selectedDay = selectedDay === date ? null : date;render();});
    pick.setAttribute('aria-pressed', String(selectedDay === date));
    pick.append(element('span', new Intl.DateTimeFormat(getLocale(), { weekday: 'short', timeZone: 'UTC' }).format(new Date(`${date}T12:00:00Z`))), element('span', date), element('strong', displayDuration(day?.total || 'PT0S')));
    card.append(pick);$('days').append(card);
  }
  $('activities-heading').textContent = selectedDay ? i18nText("ui.activities_a05abe", { value0: selectedDay }) : i18nText("ui.activities");
  renderWorkweek(s, zone);
  calendar.update(s, connected, api);
  track.update(s, activeAsOf, displayNanos, connected && canWrite);
}
function renderWorkweek(s, zone) {
  const dates = Array.from({ length: 7 }, (_, index) => addDays(s.dates.first, index));
  const headings = dates.map((date) => new Intl.DateTimeFormat(getLocale(), { weekday: 'short', day: 'numeric', timeZone: 'UTC' }).format(new Date(`${date}T12:00:00Z`)));
  const visible = (value) => !selectedDay || instantNanos(value.start) < instantNanos(dayStart(addDays(selectedDay, 1), zone)) && (!value.end || instantNanos(value.end) > instantNanos(dayStart(selectedDay, zone)));
  const allActivities = (node) => [...node.activities, ...node.children.flatMap(allActivities)];
  const totals = (activities) => {
    const daily = dates.map((date) => activities.reduce((sum, activity) => sum + activityDayNanos(activity, date, zone, s.report.generatedAt), 0n));
    return [...daily, daily.reduce((sum, value) => sum + value, 0n)].map((value, index) => value || index === 7 ? displayNanos(value) : '—');
  };
  weekSessions.update(s, activeAsOf, displayNanos, api, connected && canWrite, null);
  const titleCell = (content, level, key, title) => {
    const node = element('div', undefined, 'tree-title');node.style.setProperty('--level', level);
    if (key) {
      const toggle = button('', () => {if (collapsed.has(key)) collapsed.delete(key);else collapsed.add(key);render();});
      toggle.append(icon(collapsed.has(key) ? 'right' : 'down'));
      toggle.className = 'btn btn-outline-secondary tree-toggle';toggle.setAttribute('aria-label', `${collapsed.has(key) ? i18nText("ui.expand") : i18nText("ui.collapse")} ${title}`);toggle.title = toggle.getAttribute('aria-label');toggle.setAttribute('aria-expanded', String(!collapsed.has(key)));node.append(toggle);
    } else {const marker = element('span', undefined, 'activity-icon');marker.append(icon('clock'));node.append(marker);}
    node.append(content);return node;
  };
  const rows = [];
  const addActivity = (value, level) => {
    if (activitiesCollapsed) return;
    const title = element('span', undefined, 'activity-title');title.append(element('span', value.summary || i18nText("ui.activity")), element('small', `${instantText(value.start, zone)} → ${instantText(value.end, zone)}`));
    const node = row([titleCell(title, level), ...totals([value]), value.labelIds.map((id) => name(s.labels, id)).join(', ') || '—'], [action(i18nText("ui.edit"), () => openRecord('activities', value.id)), action(i18nText("ui.delete"), () => openDelete('activities', value.id))]);
    node.dataset.kind = 'activity';node.dataset.activityId = value.id;node.dataset.taskId = value.taskId;
    if (s.active.data?.id === value.id) node.classList.add('active-activity');
    node.tabIndex = 0;node.setAttribute('aria-selected', String(selectedActivity === value.id));
    const select = () => {selectedActivity = value.id;for (const item of $('activities').querySelectorAll('tr[aria-selected]')) item.setAttribute('aria-selected', String(item === node));};
    node.addEventListener('click', (event) => {if (event.target.closest('button,a')) return;if (selectedActivity === value.id && connected) void openRecord('activities', value.id).catch((error) => setStatus(errorText(error)));else select();});
    node.addEventListener('keydown', (event) => {if (event.key !== "Enter" || event.target !== node) return;event.preventDefault();if (selectedActivity === value.id && connected) void openRecord('activities', value.id).catch((error) => setStatus(errorText(error)));else select();});
    rows.push(node);
  };
  const addTask = (node, level) => {
    const activities = allActivities(node);if (!activities.some(visible)) return;
    const key = `task:${node.task.id}`,title = taskPresentation(node.task).title;
    const taskRow = row([titleCell(taskLabel(node.task), level, key, title), ...totals(activities), ''], [action(i18nText("ui.start"), () => openStart(node.task.id)), action(i18nText("ui.edit_task"), () => openRecord('tasks', node.task.id))]);
    taskRow.dataset.kind = 'task';rows.push(taskRow);
    if (!collapsed.has(key)) {for (const child of node.children) addTask(child, level + 1);for (const value of node.activities.filter(visible)) addActivity(value, level + 1);}
  };
  for (const group of workweekGroups(s.tasks, s.projects, s.activities)) {
    const activities = [...group.tasks.flatMap(allActivities), ...(group.activities || [])];if (!activities.some(visible)) continue;
    const key = `project:${group.id}`;
    const caption = element('span', undefined, 'project-caption'),glyph = projectIcon(s.projects.find((project) => project.id === group.id));if (glyph) caption.append(glyph);caption.append(element('span', group.name));
    const projectRow = row([titleCell(caption, 0, key, group.name), ...totals(activities), '']);
    projectRow.dataset.kind = 'project';rows.push(projectRow);
    if (!collapsed.has(key)) {for (const node of group.tasks) addTask(node, 1);for (const value of (group.activities || []).filter(visible)) addActivity(value, 1);}
  }
  table('activities', [i18nText("ui.activity"), ...headings, i18nText("ui.weekly_total"), i18nText("ui.labels"), i18nText("ui.actions")], rows);
  $('activities').querySelector('table')?.classList.add('workweek-table');
}
function field(labelText, key, value = '', options = {}) {
  const label = element('label', labelText),input = element(options.choices ? 'select' : options.multiline ? 'textarea' : 'input');input.name = key;
  input.id = `field-${key}`;label.htmlFor = input.id;input.setAttribute('aria-label', labelText);
  if (options.choices) for (const [id, text] of options.choices) {const option = element('option', text);option.value = id;input.append(option);} else
  {if (options.multiline) input.rows = 3;else input.type = options.type || 'text';if (options.max) input.maxLength = options.max;}
  input.value = value ?? '';input.required = !!options.required;label.append(input);
  label.className = 'form-label';input.className = options.choices ? 'form-select' : `form-control${input.type === 'color' ? ' form-control-color' : ''}`;
  if (options.hint) {const hint = element('p', options.hint, 'hint');hint.id = `hint-${key}`;label.append(hint);input.setAttribute('aria-describedby', hint.id);}
  $('fields').append(label);return input;
}
function labelFields(ids = []) {
  const group = element('fieldset');group.id = 'editor-labels';group.append(element('legend', i18nText("ui.labels")));
  const hint = element('p', i18nText("ui.create_labels_in_the_labels_tab"), 'hint');hint.id = 'label-choice-hint';group.append(hint);
  $('fields').append(group);refreshLabelFields(ids);
}
function refreshLabelFields(ids = []) {
  const group = $('editor-labels');if (!group || editor?.busy || editor?.uncertain) return;
  const available = new Set(snapshot.labels.map((value) => value.id));
  const existing = new Map([...group.querySelectorAll('input')].map((input) => [input.value, input]));
  for (const value of snapshot.labels) {
    let input = existing.get(value.id);
    if (!input) {
      input = element('input');input.type = 'checkbox';input.name = 'labelIds';input.value = value.id;input.checked = ids.includes(value.id);
      input.className = 'form-check-input';
      const label = element('label');label.append(input, element('span', value.name));group.append(label);
    } else input.closest('label').querySelector('span').textContent = value.name;
    input.dataset.labelName = value.name;
  }
  for (const [id, input] of existing) if (!available.has(id)) {
    if (input.checked) input.closest('label').querySelector('span').textContent = i18nText("ui.deleted", { value0: input.dataset.labelName });else
    input.closest('label').remove();
  }
  $('label-choice-hint').hidden = !!group.querySelector('input');
}
function colorSwatch(color) {
  const swatch = element('span', color, 'client-color');
  if (/^#[0-9a-f]{6}$/i.test(color)) swatch.style.setProperty('--client-color', color);
  return swatch;
}
function clientField(value) {
  return field(i18nText("ui.client"), 'clientId', value || (snapshot.clients.length === 1 ? snapshot.clients[0].id : ''), {
    required: true, choices: [['', i18nText("ui.choose_a_client")], ...snapshot.clients.map((client) => [client.id, client.name])],
    hint: snapshot.clients.length ? i18nText("ui.each_project_and_work_session_belongs_to_one_client") : i18nText("ui.create_a_client_in_the_clients_tab_first")
  });
}
function refreshClientChoices() {
  const input = $('field-clientId');if (!input || editor?.busy || editor?.uncertain) return;
  const selected = input.value;input.replaceChildren();
  for (const [id, label] of [['', i18nText("ui.choose_a_client")], ...snapshot.clients.map((client) => [client.id, client.name])]) {const option = element('option', label);option.value = id;input.append(option);}
  if (selected && !snapshot.clients.some((client) => client.id === selected)) {const option = element('option', i18nText("ui.removed_client_choose_another"));option.value = selected;input.append(option);}
  input.value = selected;
  const project = $('field-projectId');
  if (project && (editor?.collection === 'work-sessions' || editor?.command === 'session-start')) {
    const selectedProject = project.value;project.replaceChildren();
    for (const [id, label] of sessionProjectChoices()) {const option = element('option', label);option.value = id;project.append(option);}
    if (selectedProject && !snapshot.projects.some((value) => value.id === selectedProject)) {const option = element('option', i18nText("ui.removed_project_choose_another"));option.value = selectedProject;project.append(option);}
    project.value = selectedProject;
  }
}
function sessionProjectChoices() {return [['', i18nText("ui.no_project")], ...snapshot.projects.map((project) => [project.id, `${project.name} · ${name(snapshot.clients, project.clientId)}`])];}
function sessionFields(value) {
  const client = clientField(value.clientId || calendar.preferredClient());
  const project = field(i18nText("ui.project_optional"), 'projectId', value.projectId, { choices: sessionProjectChoices() });
  project.addEventListener('change', () => {const selected = snapshot.projects.find((value) => value.id === project.value);if (selected) client.value = selected.clientId;});
  client.addEventListener('change', () => {const selected = snapshot.projects.find((value) => value.id === project.value);if (selected && selected.clientId !== client.value) project.value = '';});
  field(i18nText("ui.summary"), 'summary', value.summary || '', { max: 4000, multiline: true });
  labelFields(value.labelIds);
}
function begin(title, state) {
  if (editor || $('editor').open) return false;
  editor = { ...state, session: api, zone: snapshot.profile.data.zoneId, busy: false, uncertain: false };$('fields').replaceChildren();$('editor-error').textContent = '';$('editor-notice').textContent = '';
  $('save').className = `btn ${state.remove ? 'btn-danger' : 'btn-primary'}`;
  $('editor-title').textContent = title;$('save').textContent = i18nText("ui.save");$('save').disabled = false;$('cancel').disabled = false;return true;
}
function showEditor() {$('editor').showModal();$('fields').querySelector('input,select,textarea')?.focus();}
async function openRecord(collection, id = null, initial = {}, caption = null) {
  const session = api;
  const loaded = id ? await session.request(`/${collection}/${id}`) : { data: initial, etag: null };
  if (session !== api || !connected) return;
  const value = loaded.data;
  if (!begin(caption || i18nText(`record.${id?'edit':'new'}.${collection}`), { collection, id, etag: loaded.etag })) return;
  if (['clients', 'projects', 'labels'].includes(collection)) field(i18nText("ui.name"), 'name', value.name, { required: true, max: 1000 });
  if (collection === 'clients') field(i18nText("ui.color"), 'color', value.color || '#2563eb', { type: 'color', required: true });
  if (collection === 'projects') {clientField(value.clientId);$('fields').append(createProjectIconPicker(value.icon));}
  if (collection === 'labels') field(i18nText("ui.color_optional"), 'color', value.color, { hint: i18nText("ui.hex_color_for_example_176653") });
  if (collection === 'tasks') {
    field(i18nText("ui.summary"), 'summary', value.summary, { required: true, max: 1000 });
    field(i18nText("ui.project"), 'projectId', value.projectId, { choices: [['', i18nText("ui.unassigned")], ...snapshot.projects.map((v) => [v.id, v.name])] });
    field(i18nText("ui.parent_task"), 'parentTaskId', value.parentTaskId, { choices: [['', i18nText("ui.no_parent")], ...snapshot.tasks.filter((v) => v.id !== id).map((v) => [v.id, taskPresentation(v).title])] });
    field(i18nText('record.url_optional'), 'url', value.url, { type: 'url', max: 4096 });
  }
  if (collection === 'activities') {
    const task = field(i18nText("ui.task"), 'taskId', value.taskId, { choices: [['', i18nText("ui.no_task")], ...snapshot.tasks.map((v) => [v.id, taskPresentation(v).title])] });task.disabled = !!id;
    if (!id) {field(i18nText("ui.project_without_task"), 'projectId', '', { choices: [['', i18nText("ui.no_project")], ...snapshot.projects.map((v) => [v.id, v.name])] });}
    field(i18nText("ui.summary"), 'summary', value.summary, { max: 2000 });
    timeFields(value, 'tracking');labelFields(value.labelIds);
  }
  if (collection === 'work-sessions') {
    sessionFields(value);timeFields(value, 'running');
  }
  showEditor();
}
function timeFields(value, ongoing) {
  if (editor.collection === 'work-sessions') {sessionTimeFields(value, ongoing);return;}
  const hint = i18nText("ui.local_date_and_time_in_for_example_2026_10_13t08_30_00_fractional_seconds_are_preserv", { value0: editor.zone });
  field(i18nText("ui.start_local_time"), 'start', localDateTime(value.start || new Date().toISOString(), editor.zone), { required: true, hint });
  field(i18nText("ui.end_local_time_blank_while", { value0: i18nText(ongoing==='running'?'state.running':'state.tracking') }), 'end', localDateTime(value.end, editor.zone));
}
function sessionTimeFields(value, ongoing) {
  const defaults = { start: value.start || new Date().toISOString().slice(0, 16) + ':00Z', end: value.end };
  const fields = ['start', 'end'].map((key) => {
    const precise = localDateTime(defaults[key], editor.zone);
    const input = field(key === 'start' ? i18nText("ui.start_local_time") : i18nText("ui.end_local_time_blank_while", { value0: i18nText(ongoing==='running'?'state.running':'state.tracking') }), key, precise.slice(0, 19), { type: 'datetime-local', required: key === 'start', hint: key === 'start' ? i18nText("ui.dates_and_times_use_start_cannot_be_in_the_future", { value0: editor.zone }) : undefined });
    input.step = '1';const displayed = input.value;
    const originalOffset = /([+-]\d{2}:\d{2}(?::\d{2})?)$/.exec(precise)?.[1] || '';
    const occurrence = field(i18nText("ui.time_occurrence", { value0: key === 'start' ? i18nText("ui.start") : i18nText("ui.end") }), key + 'Offset', '', { choices: [['', i18nText("ui.choose_occurrence")]] });
    const label = occurrence.closest('label');
    const update = () => {
      const offsets = localDateTimeOffsets(input.value, editor.zone),previous = occurrence.value;
      label.hidden = offsets.length < 2;occurrence.required = !label.hidden;occurrence.replaceChildren();
      for (const [offset, text] of [['', i18nText("ui.choose_occurrence")], ...offsets.map((offset, index) => [offset, i18nText("ui.occurrence_utc", { value0: index === 0 ? i18nText("ui.first") : i18nText("ui.second"), value1: offset })])]) {
        const option = element('option', text);option.value = offset;occurrence.append(option);
      }
      occurrence.value = input.value === displayed ? originalOffset : offsets.includes(previous) ? previous : '';
    };
    input.addEventListener('input', update);update();
    return { key, input, precise, displayed, originalOffset, occurrence, label };
  });
  // Native pickers show whole seconds. Preserve the original instant, including its
  // fractional seconds and DST occurrence, until that endpoint is changed.
  editor.timeValue = (key) => {
    const entry = fields.find((entry) => entry.key === key),offset = entry.label.hidden ? '' : entry.occurrence.value;
    return entry.input.value === entry.displayed && (entry.label.hidden || offset === entry.originalOffset) ?
    entry.precise : entry.input.value + (entry.input.value ? offset : '');
  };
}
async function changeCalendarSession(original, proposal, scope) {
  if (scope !== api || !connected || !begin(i18nText("ui.change_work_session"), { collection: 'work-sessions', id: original.id, etag: null, loading: true, calendarChange: true })) return;
  const state = editor;
  sessionFields(proposal);timeFields(proposal, 'running');freeze(true);$('save').disabled = true;
  setStatus(i18nText("ui.saving_work_session_changes"), 'working');
  $('editor-notice').textContent = i18nText("ui.moving_or_resizing_a_work_session_leaves_its_activities_unchanged");
  try {
    const loaded = await scope.request(`/work-sessions/${original.id}`);
    if (scope !== api || editor !== state) return;
    if (!sameSession(original, loaded.data)) throw new ApiError('STALE_VERSION', 412);
    state.etag = loaded.etag;state.loading = false;freeze(false);
    await saveEditor();
    if (scope === api && editor === state) {setStatus(i18nText("ui.work_session_change_needs_review"));showEditor();}
  } catch (error) {
    if (scope !== api || editor !== state) return;
    state.loading = false;state.reviewRequired = true;freeze(false);$('save').disabled = true;
    $('editor-error').textContent = errorText(error);$('editor-notice').textContent = i18nText("ui.your_proposed_times_are_kept_cancel_and_reopen_the_current_session_to_review_it_befor");
    setStatus(i18nText("ui.work_session_change_needs_review"));showEditor();
  }
}
async function openDelete(collection, id) {
  const session = api,loaded = await session.request(`/${collection}/${id}`);
  if (session !== api || !connected || !begin(i18nText("ui.delete_record"), { collection, id, etag: loaded.etag, remove: true })) return;
  $('fields').append(element('p', i18nText("ui.delete_681b54", { value0: loaded.data.name || loaded.data.summary || (collection === 'work-sessions' ? i18nText("ui.work_session_21aa04") : i18nText("ui.activity")), value1: collection === 'work-sessions' ? i18nText("ui.undo_will_be_available_for_ten_minutes") : i18nText("ui.this_cannot_be_undone_records_with_dependencies_may_be_rejected") })));
  $('save').textContent = i18nText("ui.delete");showEditor();
}
async function openStart(id) {
  if (!begin(i18nText("ui.start_tracking"), { command: 'start', key: crypto.randomUUID(), expected: snapshot.active.data })) return;
  field(i18nText("ui.task"), 'taskId', id, { required: true, choices: snapshot.tasks.map((v) => [v.id, taskPresentation(v).title]) });
  field(i18nText("ui.summary"), 'summary', '', { max: 2000 });labelFields();$('save').textContent = i18nText("ui.start");
  if (snapshot.active.data) $('editor-notice').textContent = i18nText("ui.starting_this_task_stops_the_current_activity");showEditor();
}
$('stop').addEventListener('click', () => {
  const active = snapshot.active;
  if (!active.data || !begin(i18nText("ui.stop_tracking"), { command: 'stop', id: active.data.id, etag: active.etag, key: crypto.randomUUID() })) return;
  $('fields').append(element('p', i18nText("ui.stop_tracking_1e5b16", { value0: name(snapshot.tasks, active.data.taskId, 'summary') })));$('save').textContent = i18nText("ui.stop");showEditor();
});
for (const [id, collection] of [['add-client', 'clients'], ['add-project', 'projects'], ['add-task', 'tasks'], ['add-label', 'labels'], ['add-activity', 'activities']]) $(id).addEventListener('click', () => {void openRecord(collection).catch((error) => setStatus(errorText(error)));});
function values() {
  const get = (key) => $('fields').querySelector(`[name="${key}"]`)?.value;
  const time = (key) => editor.timeValue ? editor.timeValue(key) : get(key);
  const labels = () => [...$('fields').querySelectorAll('[name="labelIds"]:checked')].map((node) => node.value);
  if (editor.command === 'stop' || editor.command === 'session-stop' || editor.remove) return undefined;
  if (editor.command === 'session-start') return { summary: get('summary'), clientId: get('clientId'), projectId: get('projectId') || null, labelIds: labels() };
  if (editor.command === 'start') return { taskId: get('taskId'), summary: get('summary'), labelIds: labels(), expectedActiveId: editor.expected?.id ?? null, expectedActiveVersion: editor.expected?.version ?? null };
  switch (editor.collection) {
    case 'clients':return { name: get('name'), color: get('color') };
    case 'projects':return { name: get('name'), clientId: get('clientId'), icon: $('fields').querySelector('[name=icon]:checked')?.value || null };
    case 'labels':return { name: get('name'), color: get('color') || null };
    case 'tasks':return { summary: get('summary'), projectId: get('projectId') || null, parentTaskId: get('parentTaskId') || null, url: get('url') || null };
    case 'activities':return { ...(editor.id ? {} : { taskId: get('taskId') || null, projectId: get('taskId') ? null : get('projectId') || null }), summary: get('summary'), start: localDateTimeToInstant(get('start'), editor.zone), end: get('end') ? localDateTimeToInstant(get('end'), editor.zone) : null, labelIds: labels() };
    case 'work-sessions':return { clientId: get('clientId'), projectId: get('projectId') || null, summary: get('summary'), labelIds: labels(), start: localDateTimeToInstant(time('start'), editor.zone), end: time('end') ? localDateTimeToInstant(time('end'), editor.zone) : null };
  }
}
function freeze(state) {for (const input of $('fields').querySelectorAll('input,select,textarea')) input.disabled = state || editor.collection === 'activities' && !!editor.id && input.name === 'taskId';}
async function saveEditor(event) {
  event?.preventDefault();const current = editor;
  if (!current || current.busy || current.loading || current.reviewRequired || current.session !== api) return;
  current.busy = true;$('save').disabled = true;$('cancel').disabled = true;$('editor-error').textContent = '';
  try {
    const body = current.uncertain ? current.payload : values();
    current.payload = body;freeze(true);
    const path = current.command === 'session-start' ? '/work-sessions/start' : current.command === 'session-stop' ? `/work-sessions/${current.id}/stop` : current.command === 'start' ? '/activities/start' : current.command === 'stop' ? `/activities/${current.id}/stop` : `/${current.collection}${current.id ? '/' + current.id : ''}`;
    const method = current.command ? 'POST' : current.remove ? 'DELETE' : current.id !== null ? 'PUT' : 'POST';
    const saved = await current.session.request(path, { method, body, etag: current.etag, key: current.key, undo: current.collection === 'work-sessions' && (method === 'PUT' || method === 'DELETE') });
    if (current.session !== api || current !== editor) return;
    undoNotice.offer(saved.undo, current.session, current.remove ? i18nText("ui.work_session_deleted") : i18nText("ui.work_session_updated"));
    if (!current.calendarChange && !current.remove && (current.collection === 'work-sessions' || current.command === 'session-start')) calendar.rememberClient(saved.data?.clientId);
    editor = null;$('editor').close();$('fields').replaceChildren();
    connected = false;readOnlyWhileDisconnected();setStatus(i18nText("ui.refreshing_saved_changes"), 'working');
    // Acknowledged saves remain successful even if the following read fails.
    await refresh().catch(() => {});
  } catch (error) {
    if (current !== editor || current.session !== api) return;
    if (error.status === 401) {auth.clear(errorText(error));return;}
    $('editor-error').textContent = error instanceof ApiError ? errorText(error) : error.message;
    if (error.uncertain) {
      current.uncertain = true;
      if (current.key) {$('save').textContent = i18nText("ui.retry_same_command");$('editor-notice').textContent = i18nText("ui.the_outcome_is_unknown_retry_reuses_this_exact_command_and_cannot_record_it_twice");} else
      {$('editor-notice').textContent = i18nText("ui.the_outcome_is_unknown_cancel_and_refresh_to_review_the_records_before_making_another");}
    } else {current.uncertain = false;freeze(false);}
  } finally {
    if (current === editor) {current.busy = false;$('save').disabled = current.uncertain && !current.key;$('cancel').disabled = false;refreshLabelFields();}
  }
}
$('editor-form').addEventListener('submit', saveEditor);
$('cancel').addEventListener('click', () => {if (editor?.busy) return;editor = null;$('editor').close();$('fields').replaceChildren();});
$('editor').addEventListener('cancel', (event) => {if (editor?.busy) event.preventDefault();else {editor = null;$('fields').replaceChildren();}});
function displayedSession() {return snapshot?.activeWorkSession.data || snapshot?.workSessionReport?.session || null;}
function updateSessionActions() {
  const active = snapshot?.activeWorkSession.data,session = displayedSession(),writable = connected && canWrite;
  $('session-start').disabled = !writable || !!active;$('session-start').hidden = !!active;
  $('session-stop').disabled = !writable || !active;$('session-stop').hidden = !active;
  $('session-new').disabled = !writable;
  for (const id of ['session-edit', 'session-delete']) $(id).disabled = !writable || !session;
}
function renderSession() {
  const active = snapshot.activeWorkSession.data,session = displayedSession();
  $('work-session-state').textContent = active ? i18nText("ui.running") : i18nText("ui.stopped");
  $('work-session-state').className = `badge rounded-pill ${active ? 'text-bg-success' : 'text-bg-secondary'}`;
  $('work-session-title').textContent = session?.summary || i18nText("ui.your_work_session");
  $('work-session').style.setProperty('--session-color', snapshot.clients.find((client) => client.id === session?.clientId)?.color || 'var(--accent)');
  const zone = snapshot.profile.data.zoneId;
  $('work-session-current').textContent = session ? `${[name(snapshot.clients, session.clientId), session.projectId ? name(snapshot.projects, session.projectId) : null].filter(Boolean).join(' · ')} · ${active ? i18nText("ui.started_fdc851") : ''}${instantText(session.start, zone)}${session.end ? ' → ' + instantText(session.end, zone) : ''}` : '';
  const labels = $('work-session-labels');labels.replaceChildren();
  for (const id of session?.labelIds || []) labels.append(element('span', name(snapshot.labels, id), 'badge rounded-pill text-bg-secondary'));
  labels.hidden = !labels.childElementCount;
  $('work-session-timing').hidden = !session;
  updateSessionActions();
  renderSessionTime();
}
function renderSessionTime() {
  if (!snapshot) return;
  const elapsed = connected ? BigInt(Math.max(0, Math.floor(performance.now() - snapshotAt))) * 1000000n : 0n;
  const session = displayedSession();
  if (connected) activeAsOf = instantNanos(snapshot.report.generatedAt) + elapsed;
  if (session) $('work-session-elapsed').textContent = displayNanos(sessionTimeNanos(session, [], activeAsOf).duration);
  track.tick(activeAsOf);
  weekSessions.tick(activeAsOf);
}
$('session-start').addEventListener('click', () => {
  if (!connected || snapshot.activeWorkSession.data || !begin(i18nText("ui.start_work_session"), { command: 'session-start', key: crypto.randomUUID() })) return;
  sessionFields({});$('save').textContent = i18nText("ui.start");showEditor();
});
$('session-stop').addEventListener('click', () => {
  const active = snapshot.activeWorkSession;if (!connected || !active.data || !begin(i18nText("ui.stop_work_session"), { command: 'session-stop', id: active.data.id, etag: active.etag, key: crypto.randomUUID() })) return;
  $('fields').append(element('p', i18nText("ui.stopping_your_work_session_activity_timers_remain_running")));showEditor();void saveEditor();
});
for (const [id, work] of [['session-new', () => openRecord('work-sessions')], ['session-edit', () => openRecord('work-sessions', displayedSession().id)], ['session-delete', () => openDelete('work-sessions', displayedSession().id)]]) $(id).addEventListener('click', () => {if (connected && canWrite) void work().catch((error) => setStatus(errorText(error)));});
for (const [id, glyph, label] of [['session-start', 'start', i18nText("ui.start_work_session")], ['session-stop', 'stop', i18nText("ui.stop_work_session")], ['session-new', 'add', i18nText("ui.record_work_session")], ['session-edit', 'edit', i18nText("ui.edit_work_session")], ['session-delete', 'delete', i18nText("ui.delete_work_session")]]) iconButton($(id), glyph, label);
setInterval(renderSessionTime, 1000);
window.addEventListener('pagehide', () => auth.clear());
window.addEventListener('pageshow', (event) => {if (event.persisted) void auth.initialize();});
$('status-icon').append(icon('refresh'));
iconButton($('collapse-activities'), 'collapse', i18nText("ui.hide_activities"));
iconButton($('expand-activities'), 'expand', i18nText("ui.show_activities"));
$('stop').prepend(icon('stop'));
void auth.initialize();
