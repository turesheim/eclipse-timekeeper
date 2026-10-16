import {Api, ApiError} from './api.js';
import {addDays, dateInZone, dayStart, durationText, durationNanos, instantText, instantNanos, weekRange, taskPresentation, activityDayNanos, nanosText, decimalHoursText, workweekGroups, sessionTimeNanos, localDateTime, localDateTimeToInstant} from './model.js';
import {authentication} from './auth.js';
import {icon, iconButton} from './icons.js';
import {WeekCalendar} from './calendar.js';
import {sameSession} from './calendar-model.js';

const $ = id => document.getElementById(id);
let api = null, snapshot = null, connected = false, refreshing = null, reloadAgain = false;
let editor = null, selectedActivity = null, selectedDay = null;
let watchSession = null;
let accountName = '';
let serverInfoRequest = 0;
let selectedWorkSession = null, snapshotAt = 0, sessionAsOf = 0n;
const collapsed = new Set();
let decimalHours = false, activitiesCollapsed = false;
const displayNanos = value => decimalHours ? decimalHoursText(value) : nanosText(value);
const displayDuration = value => decimalHours ? decimalHoursText(durationNanos(value)) : durationText(value);
const calendar = new WeekCalendar($('calendar-panel'), {
  taskLabel,
  onDate: date => {if(!date) return;$('week-date').value=date;selectedDay=null;void refresh().catch(()=>{});},
  onEditActivity: id => void openRecord('activities',id).catch(error=>setStatus(errorText(error))),
  onEditSession: id => void openRecord('work-sessions',id).catch(error=>setStatus(errorText(error))),
  onStartTask: id => void openStart(id).catch(error=>setStatus(errorText(error))),
  onNew: input => void openRecord('work-sessions',null,null,input).catch(error=>setStatus(errorText(error))),
  onPaste: input => void openRecord('work-sessions',null,null,input,'Paste work session').catch(error=>setStatus(errorText(error))),
  onChange: changeCalendarSession,
});
function startWatch(session) {
  if (watchSession === session) return;
  watchSession = session;
  void watch(session).finally(() => { if (watchSession === session) watchSession = null; });
}
function element(tag, text, className) {
  const node = document.createElement(tag); if (text !== undefined) node.textContent = text;
  if (className) node.className = className; return node;
}
function button(text, action) {
  const node = element('button', text, 'btn btn-outline-secondary'); node.type = 'button'; node.addEventListener('click', action); return node;
}
function errorText(error) { return error instanceof ApiError ? error.message : 'Check the entered values, then try again.'; }
function setStatus(text, state = 'error') { $('status').textContent = text; $('status').dataset.state = state; }
function clearServerInfo() {
  $('server-build').textContent = 'Server build information unavailable';
  for (const field of ['version', 'spk', 'commit', 'built', 'deployed', 'started']) $(`server-${field}`).textContent = '—';
}
async function refreshServerInfo(session) {
  const request = ++serverInfoRequest;
  try {
    const {data} = await session.request('/server');
    if (session !== api || request !== serverInfoRequest) return;
    const date = value => value ? instantText(value, snapshot.profile.data.zoneId) : 'Not recorded';
    const build = `${data.version}${data.commit ? ` · ${data.commit.slice(0, 8)}` : ' · commit not recorded'}${data.dirty ? ' · modified source' : ''}`;
    $('server-build').textContent = `Server ${build}${data.spkVersion ? ` · SPK ${data.spkVersion}` : ''} · Deployed ${date(data.deployedAt)}`;
    $('server-version').textContent = data.version;
    $('server-spk').textContent = data.spkVersion || 'Not a Synology package';
    $('server-commit').textContent = data.commit ? `${data.commit}${data.dirty ? ' (modified source)' : ''}` : 'Not recorded';
    $('server-built').textContent = date(data.builtAt);
    $('server-deployed').textContent = date(data.deployedAt);
    $('server-started').textContent = date(data.startedAt);
  } catch {
    if (session === api && request === serverInfoRequest) clearServerInfo();
  }
}
function readOnlyWhileDisconnected() {
  for (const id of ['add-client', 'add-task', 'add-project', 'add-label', 'add-activity', 'settings', 'stop', 'session-start', 'session-stop', 'session-new', 'session-edit', 'session-delete', 'session-add', 'session-associate']) $(id).disabled = !connected;
  for (const action of document.querySelectorAll('td.actions button')) action.disabled = !connected;
  calendar.setConnected(connected);
}
function tab(name) {
  for (const item of ['week', 'calendar', 'tasks', 'clients', 'projects', 'labels']) $(`${item}-panel`).hidden = item !== name;
  $('workspace').dataset.view=name;if(name==='calendar') calendar.show();
  for (const item of document.querySelectorAll('[data-tab]')) {
    item.classList.toggle('active', item.dataset.tab === name);
    if (item.dataset.tab === name) item.setAttribute('aria-current', 'page'); else item.removeAttribute('aria-current');
  }
}
for (const node of document.querySelectorAll('[data-tab]')) node.addEventListener('click', () => tab(node.dataset.tab));

const auth = authentication({
  async connect(session, account) {
    api = session; accountName = account.displayName;
    const {data:profile} = await session.request('/me');
    if(session !== api) return;
    $('week-date').value = dateInZone(Date.now(), profile.zoneId);
    $('login').hidden = true; $('workspace').hidden = false; $('session').hidden = false;
    await refresh(); startWatch(session);
  },
  updateAccount(account) {accountName = account.displayName;},
  clear() {
    refreshing = null; reloadAgain = false;
    ++serverInfoRequest; clearServerInfo(); api?.close(); api = null; snapshot = null; connected = false; editor = null; selectedActivity = null; selectedDay = null; accountName = '';
    calendar.reset(); collapsed.clear(); selectedWorkSession = null;
    $('editor').close(); $('fields').replaceChildren(); $('editor-error').textContent = ''; $('editor-notice').textContent = '';
    for(const id of ['clients','projects','tasks','labels','activities','days','session-activities','work-session-choice']) $(id).replaceChildren();
    for(const id of ['owner','status','active-title','active-details','work-session-current','work-session-state','work-session-started','session-warning','zone-caption','week-total','ws-duration','ws-tracked','ws-covered','ws-unallocated']) $(id).textContent = '';
    $('login').hidden = false; $('workspace').hidden = true; $('session').hidden = true; $('remember-notice').textContent = '';
    tab('week');
  }
});
$('refresh').addEventListener('click', () => { const session = api; void refresh().then(() => { if (session === api) startWatch(session); }).catch(() => {}); });
$('week-date').addEventListener('change', () => { selectedDay = null; void refresh().catch(() => {}); });
$('duration-format').addEventListener('change', () => {decimalHours = $('duration-format').value === 'decimal'; if (snapshot) render();});
$('collapse-activities').addEventListener('click', () => {activitiesCollapsed = true; collapsed.clear(); if (snapshot) render();});
$('expand-activities').addEventListener('click', () => {activitiesCollapsed = false; collapsed.clear(); if (snapshot) render();});

async function refresh() {
  if (!api) return;
  if (refreshing) { reloadAgain = true; await refreshing; return; }
  const session = api;
  const pending = (async () => {
    $('workspace').setAttribute('aria-busy', 'true');
    $('refresh').disabled = true;
    if (!snapshot || !connected) setStatus('Refreshing…', 'working');
    do {
      reloadAgain = false;
      const date = $('week-date').value, selected = selectedWorkSession;
      const current = await session.snapshot(zone => weekRange(date, zone), selected);
      if (session !== api) return;
      if (date !== $('week-date').value || selected !== selectedWorkSession) { reloadAgain = true; continue; }
      // A save may have queued another read while this older snapshot was in
      // flight. Publish only the final snapshot before enabling dependent forms.
      if (reloadAgain) continue;
      snapshot = current; snapshotAt = performance.now(); sessionAsOf = current.workSessionReport ? instantNanos(current.workSessionReport.generatedAt) : 0n; connected = true;
      render(); setStatus('Up to date', 'ready');
      void refreshServerInfo(session);
    } while (reloadAgain && session === api);
  })();
  refreshing = pending;
  try { await pending; }
  catch (error) {
    if (session === api) { if(error.status === 401) auth.clear(errorText(error)); else {connected = false; setStatus(errorText(error)); readOnlyWhileDisconnected();} }
    throw error;
  } finally { if(refreshing === pending) {refreshing = null; $('refresh').disabled = false; $('workspace').setAttribute('aria-busy', 'false');} }
}
async function watch(session) {
  let delay = 1000;
  while (session === api) {
    try {
      if (!connected || !snapshot) await refresh();
      const changes = await session.changes(snapshot.revision);
      if (session !== api) return;
      // Full consistent reads include changes made during pagination/report loading.
      if (changes.items.length || changes.hasMore || snapshot.active.data || snapshot.activeWorkSession.data) await refresh();
      else void refreshServerInfo(session);
      delay = 1000;
    } catch (error) {
      if (session !== api) return;
      if (error.code === 'RESYNC_REQUIRED') { connected = false; continue; }
      connected = false; setStatus(errorText(error)); readOnlyWhileDisconnected();
      if (error.status === 401) {auth.clear(errorText(error));return;}
      if (error.status === 403) return;
      await new Promise(resolve => setTimeout(resolve, delay)); delay = Math.min(delay * 2, 10000);
    }
  }
}
function table(container, headings, rows) {
  const root = $(container); root.replaceChildren();
  if (!rows.length) {
    const [title, hint] = {
      clients:['No clients yet', 'Add a client to organize projects and work sessions.'],
      projects:['No projects yet', 'Create a project and choose its client.'],
      tasks:['No tasks yet', 'Create a task to start tracking your work.'],
      labels:['No labels yet', 'Add labels to categorize your activities.'],
      activities:['No activities this week', 'Choose another week, start a task or add time.'],
      'session-activities':['No associated activities', 'Add an activity or associate one with this session.'],
    }[container];
    const empty = element('div', undefined, 'empty');
    empty.append(icon('clock'), element('h3', title), element('p', hint)); root.append(empty); return;
  }
  const node = element('table', undefined, `table table-hover align-middle mb-0${container === 'activities' ? '' : ' record-table'}`), thead = element('thead'), header = element('tr'), tbody = element('tbody');
  for (const heading of headings) { const th = element('th', heading); th.scope = 'col'; header.append(th); }
  thead.append(header); node.append(thead, tbody);
  for (const row of rows) {for (const [index,cell] of [...row.children].entries()) cell.dataset.label = headings[index];tbody.append(row);} root.append(node);
}
function row(values, actions = []) {
  const node = element('tr'); for (const value of values) { const cell = element('td'); cell.append(value instanceof Node ? value : document.createTextNode(value)); node.append(cell); }
  const cell = element('td', undefined, 'actions'); for (const action of actions) cell.append(action); node.append(cell); return node;
}
function action(text, operation) {
  const node = button('', () => { void operation().catch(error => setStatus(errorText(error))); });
  iconButton(node, text === 'Start' ? 'start' : text === 'Delete' ? 'delete' : text === 'Remove from session' ? 'unlink' : 'edit', text);
  node.disabled = !connected; return node;
}
function name(list, id, field = 'name') { const value = list.find(v => v.id === id); return value && field === 'summary' ? taskPresentation(value).title : value?.[field] ?? 'Unassigned'; }
function taskLabel(task) {
  if (!task) return element('span', 'Unassigned');
  const {title, url} = taskPresentation(task), node = element(url ? 'a' : 'span', title, 'task-title');
  node.dataset.taskId = task.id;
  if (url) { node.href = url; node.target = '_blank'; node.rel = 'noopener noreferrer'; }
  return node;
}
function render() {
  const s = snapshot, zone = s.profile.data.zoneId;
  $('owner').textContent = accountName;
  $('zone-caption').textContent = `${s.dates.first} – ${addDays(s.dates.first, 6)} · ${zone}`;
  const active = s.active.data;
  $('active-title').closest('.tracking').dataset.active=String(!!active);
  $('active-title').replaceChildren(active ? taskLabel(s.tasks.find(task => task.id === active.taskId)) : document.createTextNode('Ready when you are'));
  $('active-details').textContent = active ? `Started ${instantText(active.start, zone)} · ${active.summary || 'Tracking time'}` : 'Choose a task below to start tracking.';
  $('stop').hidden = !active; readOnlyWhileDisconnected();
  table('clients', ['Name', 'Color', 'Actions'], s.clients.map(value => row([value.name, colorSwatch(value.color)], [action('Edit', () => openRecord('clients', value.id)), action('Delete', () => openDelete('clients', value.id))])));
  table('projects', ['Name', 'Client', 'Actions'], s.projects.map(value => row([value.name, name(s.clients, value.clientId)], [action('Edit', () => openRecord('projects', value.id)), action('Delete', () => openDelete('projects', value.id))])));
  table('tasks', ['Task', 'Project', 'Parent', 'Actions'], s.tasks.map(value => row([taskLabel(value), name(s.projects, value.projectId), value.parentTaskId ? name(s.tasks, value.parentTaskId, 'summary') : '—'], [action('Start', () => openStart(value.id)), action('Edit', () => openRecord('tasks', value.id)), action('Delete', () => openDelete('tasks', value.id))])));
  table('labels', ['Name', 'Color', 'Actions'], s.labels.map(value => row([value.name, /^#[0-9a-f]{6}$/i.test(value.color) ? colorSwatch(value.color) : value.color || '—'], [action('Edit', () => openRecord('labels', value.id)), action('Delete', () => openDelete('labels', value.id))])));
  refreshLabelFields(); refreshClientChoices(); refreshSessionChoices(); renderSession();
  $('week-total').textContent = displayDuration(s.report.total); $('days').replaceChildren();
  for (let index = 0; index < 7; index++) {
    const date = addDays(s.dates.first, index), day = s.report.days.find(v => v.date === date);
    const card = element('div', undefined, `day${date === selectedDay ? ' selected' : ''}`);
    const pick = button('', () => { selectedDay = selectedDay === date ? null : date; render(); });
    pick.setAttribute('aria-pressed', String(selectedDay === date));
    pick.append(element('span', new Intl.DateTimeFormat(undefined, {weekday: 'short', timeZone: 'UTC'}).format(new Date(`${date}T12:00:00Z`))), element('span', date), element('strong', displayDuration(day?.total || 'PT0S')));
    card.append(pick); $('days').append(card);
  }
  $('activities-heading').textContent = selectedDay ? `Activities — ${selectedDay}` : 'Activities';
  renderWorkweek(s, zone);
  calendar.update(s,connected,api);
}
function renderWorkweek(s, zone) {
  const dates = Array.from({length: 7}, (_, index) => addDays(s.dates.first, index));
  const visible = value => !selectedDay || (instantNanos(value.start) < instantNanos(dayStart(addDays(selectedDay, 1), zone)) && (!value.end || instantNanos(value.end) > instantNanos(dayStart(selectedDay, zone))));
  const allActivities = node => [...node.activities, ...node.children.flatMap(allActivities)];
  const totals = activities => {
    const daily = dates.map(date => activities.reduce((sum, activity) => sum + activityDayNanos(activity, date, zone, s.report.generatedAt), 0n));
    return [...daily, daily.reduce((sum, value) => sum + value, 0n)].map((value, index) => value || index === 7 ? displayNanos(value) : '—');
  };
  const titleCell = (content, level, key, title) => {
    const node = element('div', undefined, 'tree-title'); node.style.setProperty('--level', level);
    if (key) {
      const toggle = button('', () => { if (collapsed.has(key)) collapsed.delete(key); else collapsed.add(key); render(); });
      toggle.append(icon(collapsed.has(key) ? 'right' : 'down'));
      toggle.className = 'btn btn-outline-secondary tree-toggle'; toggle.setAttribute('aria-label', `${collapsed.has(key) ? 'Expand' : 'Collapse'} ${title}`); toggle.title = toggle.getAttribute('aria-label'); toggle.setAttribute('aria-expanded', String(!collapsed.has(key))); node.append(toggle);
    } else { const marker = element('span', undefined, 'activity-icon'); marker.append(icon('clock')); node.append(marker); }
    node.append(content); return node;
  };
  const rows = [];
  const addActivity = (value, level) => {
    if (activitiesCollapsed) return;
    const title = element('span', undefined, 'activity-title'); title.append(element('span', value.summary || 'Activity'), element('small', `${instantText(value.start, zone)} → ${instantText(value.end, zone)}`));
    const node = row([titleCell(title, level), ...totals([value]), value.labelIds.map(id => name(s.labels, id)).join(', ') || '—'], [action('Edit', () => openRecord('activities', value.id)), action('Delete', () => openDelete('activities', value.id))]);
    node.dataset.kind = 'activity'; node.dataset.activityId = value.id; node.dataset.taskId = value.taskId;
    if (s.active.data?.id === value.id) node.classList.add('active-activity');
    node.tabIndex = 0; node.setAttribute('aria-selected', String(selectedActivity === value.id));
    const select = () => { selectedActivity = value.id; for (const item of $('activities').querySelectorAll('tr[aria-selected]')) item.setAttribute('aria-selected', String(item === node)); };
    node.addEventListener('click', event => { if (event.target.closest('button,a')) return; if (selectedActivity === value.id && connected) void openRecord('activities', value.id).catch(error => setStatus(errorText(error))); else select(); });
    node.addEventListener('keydown', event => { if (event.key !== 'Enter' || event.target !== node) return; event.preventDefault(); if (selectedActivity === value.id && connected) void openRecord('activities', value.id).catch(error => setStatus(errorText(error))); else select(); });
    rows.push(node);
  };
  const addTask = (node, level) => {
    const activities = allActivities(node); if (!activities.some(visible)) return;
    const key = `task:${node.task.id}`, title = taskPresentation(node.task).title;
    const taskRow = row([titleCell(taskLabel(node.task), level, key, title), ...totals(activities), ''], [action('Start', () => openStart(node.task.id)), action('Edit task', () => openRecord('tasks', node.task.id))]);
    taskRow.dataset.kind = 'task'; rows.push(taskRow);
    if (!collapsed.has(key)) { for (const child of node.children) addTask(child, level + 1); for (const value of node.activities.filter(visible)) addActivity(value, level + 1); }
  };
  for (const group of workweekGroups(s.tasks, s.projects, s.activities)) {
    const activities = [...group.tasks.flatMap(allActivities), ...(group.activities || [])]; if (!activities.some(visible)) continue;
    const key = `project:${group.id}`;
    const projectRow = row([titleCell(element('span', group.name), 0, key, group.name), ...totals(activities), '']);
    projectRow.dataset.kind = 'project'; rows.push(projectRow);
    if (!collapsed.has(key)) { for (const node of group.tasks) addTask(node, 1); for (const value of (group.activities || []).filter(visible)) addActivity(value, 1); }
  }
  const headings = dates.map(date => new Intl.DateTimeFormat(undefined, {weekday: 'short', day: 'numeric', timeZone: 'UTC'}).format(new Date(`${date}T12:00:00Z`)));
  table('activities', ['Activity', ...headings, 'Weekly total', 'Labels', 'Actions'], rows);
  $('activities').querySelector('table')?.classList.add('workweek-table');
}
function field(labelText, key, value = '', options = {}) {
  const label = element('label', labelText), input = element(options.choices ? 'select' : 'input'); input.name = key;
  input.id = `field-${key}`; label.htmlFor = input.id; input.setAttribute('aria-label', labelText);
  if (options.choices) for (const [id, text] of options.choices) { const option = element('option', text); option.value = id; input.append(option); }
  else { input.type = options.type || 'text'; if (options.max) input.maxLength = options.max; }
  input.value = value ?? ''; input.required = !!options.required; label.append(input);
  label.className = 'form-label'; input.className = options.choices ? 'form-select' : `form-control${input.type === 'color' ? ' form-control-color' : ''}`;
  if (options.hint) { const hint = element('p', options.hint, 'hint'); hint.id = `hint-${key}`; label.append(hint); input.setAttribute('aria-describedby', hint.id); }
  $('fields').append(label); return input;
}
function labelFields(ids = []) {
  const group = element('fieldset'); group.id = 'editor-labels'; group.append(element('legend', 'Labels'));
  const hint = element('p', 'Create labels in the Labels tab.', 'hint'); hint.id = 'label-choice-hint'; group.append(hint);
  $('fields').append(group); refreshLabelFields(ids);
}
function refreshLabelFields(ids = []) {
  const group = $('editor-labels'); if (!group || editor?.busy || editor?.uncertain) return;
  const available = new Set(snapshot.labels.map(value => value.id));
  const existing = new Map([...group.querySelectorAll('input')].map(input => [input.value, input]));
  for (const value of snapshot.labels) {
    let input = existing.get(value.id);
    if (!input) {
      input = element('input'); input.type = 'checkbox'; input.name = 'labelIds'; input.value = value.id; input.checked = ids.includes(value.id);
      input.className = 'form-check-input';
      const label = element('label'); label.append(input, element('span', value.name)); group.append(label);
    } else input.closest('label').querySelector('span').textContent = value.name;
    input.dataset.labelName = value.name;
  }
  for (const [id, input] of existing) if (!available.has(id)) {
    if (input.checked) input.closest('label').querySelector('span').textContent = `${input.dataset.labelName} (deleted)`;
    else input.closest('label').remove();
  }
  $('label-choice-hint').hidden = !!group.querySelector('input');
}
function colorSwatch(color) {
  const swatch=element('span',color,'client-color');
  if(/^#[0-9a-f]{6}$/i.test(color)) swatch.style.setProperty('--client-color',color);
  return swatch;
}
function clientField(value) {
  return field('Client','clientId',value || (snapshot.clients.length===1 ? snapshot.clients[0].id : ''),{
    required:true,choices:[['','Choose a client'],...snapshot.clients.map(client=>[client.id,client.name])],
    hint:snapshot.clients.length ? 'Each project and work session belongs to one client.' : 'Create a client in the Clients tab first.'
  });
}
function refreshClientChoices() {
  const input=$('field-clientId');if(!input || editor?.busy || editor?.uncertain) return;
  const selected=input.value;input.replaceChildren();
  for(const [id,label] of [['','Choose a client'],...snapshot.clients.map(client=>[client.id,client.name])]) {const option=element('option',label);option.value=id;input.append(option);}
  if(selected && !snapshot.clients.some(client=>client.id===selected)) {const option=element('option','Removed client — choose another');option.value=selected;input.append(option);}
  input.value=selected;
}
function sessionFields(value) {
  field('Title','title',value.title || '',{required:true,max:1000});clientField(value.clientId);
  field('Summary','summary',value.summary || '',{max:2000});
}
function begin(title, state) {
  if ($('editor').open) return false;
  editor = {...state, session: api, zone: snapshot.profile.data.zoneId, busy: false, uncertain: false}; $('fields').replaceChildren(); $('editor-error').textContent = ''; $('editor-notice').textContent = '';
  $('save').className = `btn ${state.remove ? 'btn-danger' : 'btn-primary'}`;
  $('editor-title').textContent = title; $('save').textContent = 'Save'; $('save').disabled = false; $('cancel').disabled = false; return true;
}
function showEditor() { $('editor').showModal(); $('fields').querySelector('input,select')?.focus(); }
async function openRecord(collection, id = null, activitySession = null, initial = {}, caption = null) {
  const session = api;
  const loaded = id ? await session.request(`/${collection}/${id}`) : {data: initial, etag: null};
  if (session !== api || !connected) return;
  const titles = {clients:'client', projects: 'project', tasks: 'task', labels: 'label', activities: 'activity', 'work-sessions': 'work session'}, value = loaded.data;
  if (!begin(caption || `${id ? 'Edit' : 'New'} ${titles[collection]}`, {collection, id, etag: loaded.etag})) return;
  if (['clients','projects','labels'].includes(collection)) field('Name', 'name', value.name, {required: true, max: 1000});
  if (collection === 'clients') field('Color','color',value.color || '#2563eb',{type:'color',required:true});
  if (collection === 'projects') clientField(value.clientId);
  if (collection === 'labels') field('Color (optional)', 'color', value.color, {hint: 'Hex color, for example #176653.'});
  if (collection === 'tasks') {
    field('Summary', 'summary', value.summary, {required: true, max: 1000});
    field('Project', 'projectId', value.projectId, {choices: [['', 'Unassigned'], ...snapshot.projects.map(v => [v.id, v.name])]});
    field('Parent task', 'parentTaskId', value.parentTaskId, {choices: [['', 'No parent'], ...snapshot.tasks.filter(v => v.id !== id).map(v => [v.id, taskPresentation(v).title])]});
    field('URL (optional)', 'url', value.url, {type: 'url', max: 4096});
  }
  if (collection === 'activities') {
    const task = field('Task', 'taskId', value.taskId, {choices: [['', 'No task'], ...snapshot.tasks.map(v => [v.id, taskPresentation(v).title])]}); task.disabled = !!id;
    if (!id) { field('Project (without task)', 'projectId', '', {choices:[['','No project'],...snapshot.projects.map(v => [v.id,v.name])]}); field('Work session', 'workSessionId', activitySession, {choices:sessionChoices()}); }
    field('Summary', 'summary', value.summary, {max: 2000});
    timeFields(value, 'tracking'); labelFields(value.labelIds);
  }
  if (collection === 'work-sessions') {
    sessionFields(value);timeFields(value, 'running');
  }
  if(collection==='activities' && !id) addSessionWarning();
  showEditor();
}
function timeFields(value, ongoing) {
  const hint = `Local date and time in ${editor.zone}, for example 2026-10-13T08:30:00. Fractional seconds are preserved. Repeated clock times include an offset. Start cannot be in the future.`;
  field('Start (local time)', 'start', localDateTime(value.start || new Date().toISOString(), editor.zone), {required: true, hint});
  field(`End (local time, blank while ${ongoing})`, 'end', localDateTime(value.end, editor.zone));
}
async function changeCalendarSession(original, proposal, scope) {
  if(scope!==api || !connected || !begin('Change work session',{collection:'work-sessions',id:original.id,etag:null,loading:true})) return;
  const state=editor;
  sessionFields(proposal);timeFields(proposal,'running');showEditor();freeze(true);$('save').disabled=true;
  $('editor-notice').textContent='Moving or resizing a work session leaves its activities unchanged.';
  try {
    const loaded=await scope.request(`/work-sessions/${original.id}`);
    if(scope!==api || editor!==state) return;
    if(!sameSession(original,loaded.data)) throw new ApiError('STALE_VERSION',412);
    state.etag=loaded.etag;state.loading=false;freeze(false);
    await saveEditor();
  } catch(error) {
    if(scope!==api || editor!==state) return;
    state.loading=false;state.reviewRequired=true;freeze(false);$('save').disabled=true;
    $('editor-error').textContent=errorText(error);$('editor-notice').textContent='Your proposed times are kept. Cancel and reopen the current session to review it before saving.';
  }
}
async function openDelete(collection, id) {
  const session = api, loaded = await session.request(`/${collection}/${id}`);
  if (session !== api || !connected || !begin('Delete record', {collection, id, etag: loaded.etag, remove: true})) return;
  $('fields').append(element('p', `Delete “${loaded.data.name || loaded.data.title || loaded.data.summary || 'Activity'}”? This cannot be undone. Records with dependencies may be rejected.`));
  $('save').textContent = 'Delete'; showEditor();
}
async function openStart(id) {
  if (!begin('Start tracking', {command: 'start', key: crypto.randomUUID(), expected: snapshot.active.data})) return;
  field('Task', 'taskId', id, {required: true, choices: snapshot.tasks.map(v => [v.id, taskPresentation(v).title])});
  field('Summary', 'summary', '', {max: 2000}); field('Work session','workSessionId',snapshot.activeWorkSession.data?.id || '',{choices:sessionChoices()}); labelFields(); $('save').textContent = 'Start';
  if (snapshot.active.data) $('editor-notice').textContent = 'Starting this task stops the current activity.'; showEditor();
}
$('stop').addEventListener('click', () => {
  const active = snapshot.active;
  if (!active.data || !begin('Stop tracking', {command: 'stop', id: active.data.id, etag: active.etag, key: crypto.randomUUID()})) return;
  $('fields').append(element('p', `Stop tracking “${name(snapshot.tasks, active.data.taskId, 'summary')}”?`)); $('save').textContent = 'Stop'; showEditor();
});
$('settings').addEventListener('click', () => {
  if (!begin('Time zone', {collection: 'me', id: '', etag: snapshot.profile.etag})) return;
  field('Time zone', 'zoneId', snapshot.profile.data.zoneId, {required: true, hint: 'IANA name such as Europe/Oslo. Reports use this zone in every client.'}); showEditor();
});
for (const [id, collection] of [['add-client','clients'], ['add-project','projects'], ['add-task','tasks'], ['add-label','labels'], ['add-activity','activities']]) $(id).addEventListener('click', () => { void openRecord(collection).catch(error => setStatus(errorText(error))); });
function values() {
  const get = key => $('fields').querySelector(`[name="${key}"]`)?.value;
  const labels = () => [...$('fields').querySelectorAll('[name="labelIds"]:checked')].map(node => node.value);
  if (editor.command === 'stop' || editor.command === 'session-stop' || editor.remove) return undefined;
  if (editor.command === 'session-start') return {summary:get('summary'),title:get('title'),clientId:get('clientId')};
  if (editor.association) return {workSessionId:get('workSessionId') || null};
  if (editor.command === 'start') return {taskId: get('taskId'), workSessionId:get('workSessionId') || null, summary: get('summary'), labelIds: labels(), expectedActiveId: editor.expected?.id ?? null, expectedActiveVersion: editor.expected?.version ?? null};
  switch (editor.collection) {
    case 'clients': return {name:get('name'),color:get('color')};
    case 'projects': return {name: get('name'),clientId:get('clientId')};
    case 'labels': return {name: get('name'), color: get('color') || null};
    case 'tasks': return {summary: get('summary'), projectId: get('projectId') || null, parentTaskId: get('parentTaskId') || null, url: get('url') || null};
    case 'activities': return {...(editor.id ? {} : {taskId: get('taskId') || null, projectId:get('taskId') ? null : get('projectId') || null, workSessionId:get('workSessionId') || null}), summary: get('summary'), start: localDateTimeToInstant(get('start'), editor.zone), end: get('end') ? localDateTimeToInstant(get('end'), editor.zone) : null, labelIds: labels()};
    case 'work-sessions': return {title:get('title'),clientId:get('clientId'),summary:get('summary'),start:localDateTimeToInstant(get('start'),editor.zone),end:get('end') ? localDateTimeToInstant(get('end'),editor.zone) : null};
    case 'me': return {zoneId: get('zoneId')};
  }
}
function freeze(state) { for (const input of $('fields').querySelectorAll('input,select')) input.disabled = state || (editor.collection === 'activities' && !!editor.id && input.name === 'taskId'); }
async function saveEditor(event) {
  event?.preventDefault(); const current = editor;
  if (!current || current.busy || current.loading || current.reviewRequired || current.session !== api) return;
  current.busy = true; $('save').disabled = true; $('cancel').disabled = true; $('editor-error').textContent = '';
  try {
    const body = current.uncertain ? current.payload : values();
    current.payload = body; freeze(true);
    const path = current.association ? `/activities/${current.id}/work-session` : current.command === 'session-start' ? '/work-sessions/start' : current.command === 'session-stop' ? `/work-sessions/${current.id}/stop` : current.command === 'start' ? '/activities/start' : current.command === 'stop' ? `/activities/${current.id}/stop` : `/${current.collection}${current.id ? '/' + current.id : ''}`;
    const method = current.command ? 'POST' : current.remove ? 'DELETE' : current.id !== null ? 'PUT' : 'POST';
    const saved = await current.session.request(path, {method, body, etag: current.etag, key: current.key});
    if (current.session !== api || current !== editor) return;
    editor = null; $('editor').close(); $('fields').replaceChildren();
    connected = false; readOnlyWhileDisconnected(); setStatus('Refreshing saved changes…', 'working');
    // Acknowledged saves remain successful even if the following read fails.
    await refresh().catch(() => {});
    if(saved.data?.outsideSession) setStatus('Saved. This activity extends outside the selected work session.', 'notice');
  } catch (error) {
    if (current !== editor || current.session !== api) return;
    if(error.status === 401) {auth.clear(errorText(error));return;}
    $('editor-error').textContent = error instanceof ApiError ? errorText(error) : error.message;
    if (error.uncertain) {
      current.uncertain = true;
      if (current.key) { $('save').textContent = 'Retry same command'; $('editor-notice').textContent = 'The outcome is unknown. Retry reuses this exact command and cannot record it twice.'; }
      else { $('editor-notice').textContent = 'The outcome is unknown. Cancel and refresh to review the records before making another change.'; }
    } else { current.uncertain = false; freeze(false); }
  } finally {
    if (current === editor) { current.busy = false; $('save').disabled = current.uncertain && !current.key; $('cancel').disabled = false; refreshLabelFields(); }
  }
}
$('editor-form').addEventListener('submit', saveEditor);
$('cancel').addEventListener('click', () => { if (editor?.busy) return; editor = null; $('editor').close(); $('fields').replaceChildren(); });
$('editor').addEventListener('cancel', event => { if (editor?.busy) event.preventDefault(); else { editor = null; $('fields').replaceChildren(); } });
function sessionLabel(value) {
  return `${instantText(value.start,snapshot.profile.data.zoneId)} · ${value.end ? 'Completed' : 'Running'} · ${value.title} · ${name(snapshot.clients,value.clientId)}`;
}
function sessionChoices() {return [['','Standalone'],...snapshot.workSessions.map(value => [value.id,sessionLabel(value)])];}
function refreshSessionChoices() {
  const input=$('field-workSessionId');if(!input || editor?.busy || editor?.uncertain) return;const selected=input.value;
  input.replaceChildren();for(const [id,label] of sessionChoices()) {const option=element('option',label);option.value=id;input.append(option);}
  if(selected && !snapshot.workSessions.some(value=>value.id===selected)) {const option=element('option','Removed work session');option.value=selected;input.append(option);}
  input.value=selected;
}
function renderSession() {
  const active=snapshot.activeWorkSession.data, report=snapshot.workSessionReport, choice=$('work-session-choice');
  $('work-session-state').textContent=active ? 'Running' : 'Stopped';
  $('work-session-state').className = `badge rounded-pill ${active ? 'text-bg-success' : 'text-bg-secondary'}`;
  $('work-session-title').textContent = report?.session.title || 'Your work session';
  $('work-session').style.setProperty('--session-color', snapshot.clients.find(client => client.id === report?.session.clientId)?.color || 'var(--accent)');
  $('work-session-current').textContent=active ? `${active.title} · ${name(snapshot.clients,active.clientId)} · Started ${instantText(active.start,snapshot.profile.data.zoneId)}` : 'Start a work session to track your day independently of tasks.';
  $('session-start').disabled=!connected || !!active; $('session-stop').hidden=!active;
  choice.replaceChildren();for(const value of [...snapshot.workSessions].sort((a,b) => b.start.localeCompare(a.start))) {const option=element('option',sessionLabel(value));option.value=value.id;choice.append(option);}
  if(!report) {const option=element('option','No sessions yet');option.value='';choice.append(option);}
  choice.value=report?.session.id || '';choice.disabled=!connected || !report;
  $('work-session-details').hidden=!report;
  if(!report) return;
  $('work-session-started').textContent=`${report.session.title} · ${name(snapshot.clients,report.session.clientId)}${report.session.summary ? ' · '+report.session.summary : ''} · ${instantText(report.session.start,snapshot.profile.data.zoneId)} → ${instantText(report.session.end,snapshot.profile.data.zoneId)}`;
  table('session-activities',['Activity','Task / project','Time','Actions'],report.activities.map(value => row([
    value.summary || 'Activity',value.taskId ? name(snapshot.tasks,value.taskId,'summary') : name(snapshot.projects,value.projectId),
    `${instantText(value.start,snapshot.profile.data.zoneId)} → ${instantText(value.end,snapshot.profile.data.zoneId)}`,
  ],[action('Edit',()=>openRecord('activities',value.id)),action('Remove from session',()=>openAssociation(value.id,''))])));
  $('session-warning').textContent=report.time.outsideSession.length ? 'Some activities extend outside this session. Only their overlap counts toward covered time.' : '';
  renderSessionTime();
}
function renderSessionTime() {
  if(!snapshot?.workSessionReport) return;const report=snapshot.workSessionReport;
  const elapsed=connected ? BigInt(Math.max(0,Math.floor(performance.now()-snapshotAt)))*1000000n : 0n;
  if(connected) sessionAsOf=instantNanos(report.generatedAt)+elapsed;
  const time=sessionTimeNanos(report.session,report.activities,sessionAsOf);
  for(const [id,key] of [['ws-duration','duration'],['ws-tracked','tracked'],['ws-covered','allocated'],['ws-unallocated','unallocated']]) $(id).textContent=displayNanos(time[key]);
}
function addSessionWarning() {
  const warning=element('p','', 'hint');warning.id='activity-session-warning';warning.setAttribute('role','status');$('fields').append(warning);
  const update=()=> {
    try {
      const session=snapshot.workSessions.find(value=>value.id===$('field-workSessionId').value);
      const from=instantNanos(localDateTimeToInstant($('field-start').value,editor.zone)),end=$('field-end').value ? instantNanos(localDateTimeToInstant($('field-end').value,editor.zone)) : instantNanos(snapshot.report.generatedAt);
      warning.textContent=session && (from<instantNanos(session.start) || (session.end && end>instantNanos(session.end))) ? 'This activity extends outside the session. Only the overlapping time counts toward covered time.' : '';
    } catch {warning.textContent='';}
  };
  for(const input of $('fields').querySelectorAll('input,select')) input.addEventListener('input',update);update();
}
async function openAssociation(id=null, defaultSession=snapshot.workSessionReport?.session.id || '') {
  const session=api, selected=id || snapshot.activities[0]?.id;
  if(!selected) {setStatus('Choose a week containing activities, or add an activity first.');return;}
  const loaded=await session.request(`/activities/${selected}`);
  if(session!==api || !connected || !begin('Associate activity',{collection:'activities',id:selected,etag:loaded.etag,association:true})) return;
  const state=editor;
  const selector=field('Activity','activityId',selected,{choices:[...new Map([...snapshot.activities,...(snapshot.workSessionReport?.activities || [])].map(value=>[value.id,value])).values()].map(value=>[value.id,`${value.summary || 'Activity'} · ${instantText(value.start,snapshot.profile.data.zoneId)}`])});
  field('Work session','workSessionId',defaultSession,{choices:sessionChoices()});
  const warning=element('p','', 'hint');warning.setAttribute('role','status');$('fields').append(warning);
  let record=loaded.data, generation=0;
  const warn=()=> {const target=snapshot.workSessions.find(value=>value.id===$('field-workSessionId').value);warning.textContent=target && (instantNanos(record.start)<instantNanos(target.start) || (target.end && instantNanos(record.end || snapshot.report.generatedAt)>instantNanos(target.end))) ? 'This activity extends outside the session. Only the overlapping time counts toward covered time.' : '';};
  $('field-workSessionId').addEventListener('change',warn);warn();
  selector.addEventListener('change',async()=> {
    const request=++generation;state.busy=true;$('save').disabled=true;
    try {const next=await session.request(`/activities/${selector.value}`);if(editor!==state || request!==generation) return;
      state.id=next.data.id;state.etag=next.etag;record=next.data;warn();}
    catch(error) {if(editor===state) $('editor-error').textContent=errorText(error);}
    finally {if(editor===state && request===generation) {state.busy=false;$('save').disabled=state.id!==selector.value;}}
  });showEditor();
}
$('work-session-choice').addEventListener('change',()=> {selectedWorkSession=$('work-session-choice').value || null;void refresh().catch(()=>{});});
$('session-start').addEventListener('click',()=> {
  if(!connected || snapshot.activeWorkSession.data || !begin('Start work session',{command:'session-start',key:crypto.randomUUID()})) return;
  sessionFields({});$('save').textContent='Start';showEditor();
});
$('session-stop').addEventListener('click',()=> {
  const active=snapshot.activeWorkSession;if(!connected || !active.data || !begin('Stop work session',{command:'session-stop',id:active.data.id,etag:active.etag,key:crypto.randomUUID()})) return;
  $('fields').append(element('p','Stopping your work session. Activity timers remain running.'));showEditor();void saveEditor();
});
for(const [id,work] of [['session-new',()=>openRecord('work-sessions')],['session-edit',()=>openRecord('work-sessions',snapshot.workSessionReport.session.id)],['session-delete',()=>openDelete('work-sessions',snapshot.workSessionReport.session.id)],['session-add',()=>openRecord('activities',null,snapshot.workSessionReport.session.id)],['session-associate',()=>openAssociation()]]) $(id).addEventListener('click',()=> {void work().catch(error=>setStatus(errorText(error)));});
for(const [id,glyph,label] of [['session-start','start','Start work session'],['session-stop','stop','Stop work session'],['session-new','add','Record work session'],['session-edit','edit','Edit work session'],['session-delete','delete','Delete work session'],['session-add','add','Add activity to session'],['session-associate','link','Associate activity']]) iconButton($(id),glyph,label);
setInterval(renderSessionTime,1000);
window.addEventListener('pagehide', () => auth.clear());
window.addEventListener('pageshow', event => {if(event.persisted) void auth.initialize();});
iconButton($('refresh'), 'refresh', 'Refresh');
iconButton($('collapse-activities'), 'collapse', 'Hide activities');
iconButton($('expand-activities'), 'expand', 'Show activities');
$('stop').prepend(icon('stop'));
void auth.initialize();
