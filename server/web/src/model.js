// Dates and report totals are independent of the browser's local time zone.
export function dateInZone(instant, zone) {
  const parts = new Intl.DateTimeFormat('en-CA', {timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit'}).formatToParts(new Date(instant));
  const part = type => parts.find(p => p.type === type).value;
  return `${part('year')}-${part('month')}-${part('day')}`;
}
export function addDays(date, count) {
  const value = new Date(`${date}T12:00:00Z`);
  value.setUTCDate(value.getUTCDate() + count);
  return value.toISOString().slice(0, 10);
}
export function monday(date) {
  return addDays(date, -((new Date(`${date}T12:00:00Z`).getUTCDay() + 6) % 7));
}
export function dayStart(date, zone) {
  // Binary search the first instant whose civil date is the requested date.
  // This also handles midnight offset changes and 23/25-hour DST days.
  const noon = Date.parse(`${date}T12:00:00Z`);
  if (!Number.isFinite(noon)) throw new Error('Choose a valid date.');
  let low = noon - 48 * 3600000, high = noon + 48 * 3600000;
  while (low < high) {
    const middle = Math.floor((low + high) / 2);
    if (dateInZone(middle, zone) < date) low = middle + 1;
    else high = middle;
  }
  if (dateInZone(low, zone) !== date) throw new Error('This date does not exist in the selected time zone.');
  return new Date(low).toISOString();
}
export function weekRange(date, zone) {
  const first = monday(date);
  return {first, from: dayStart(first, zone), to: dayStart(addDays(first, 7), zone)};
}
export function durationNanos(duration) {
  const match = /^PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)(?:\.(\d{1,9}))?S)?$/.exec(duration);
  if (!match || !match.slice(1).some(Boolean)) throw new Error('Invalid report duration.');
  return (BigInt(match[1] || 0) * 3600n + BigInt(match[2] || 0) * 60n + BigInt(match[3] || 0)) * 1000000000n + BigInt((match[4] || '').padEnd(9, '0'));
}
export function durationText(duration) {
  return nanosText(durationNanos(duration));
}
export function nanosText(total) {
  const seconds = total / 1000000000n;
  let value = `${seconds / 3600n}h ${(seconds / 60n) % 60n}m`;
  if (seconds % 60n) value += ` ${seconds % 60n}s`;
  return value;
}
export function instantText(instant, zone) {
  if (!instant) return 'In progress';
  return new Intl.DateTimeFormat(undefined, {timeZone: zone, month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit'}).format(new Date(instant));
}
export function instantNanos(instant) {
  const match = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?Z$/.exec(instant);
  if (!match) throw new Error('Invalid UTC instant.');
  return BigInt(Date.parse(match[1] + 'Z')) * 1000000n + BigInt((match[2] || '').padEnd(9, '0'));
}

export function taskPresentation(task) {
  const references = [...(task.externalReferences || [])].sort((a, b) =>
    `${a.providerId}/${a.repositoryId}/${a.externalId}`.localeCompare(`${b.providerId}/${b.repositoryId}/${b.externalId}`));
  const reference = references[0];
  const webUrl = value => {
    try { const url = new URL(value); return ['https:', 'http:'].includes(url.protocol) ? url.href : null; }
    catch { return null; }
  };
  const url = webUrl(reference?.externalUrl) || webUrl(task.url);
  let id = reference?.externalId;
  if (reference && url) {
    const path = new URL(url).pathname;
    const jira = /\/browse\/([^/]+)\/?$/.exec(path);
    const github = /\/(?:issues|pull)\/(\d+)\/?$/.exec(path);
    if (jira) { try { id = decodeURIComponent(jira[1]); } catch { /* Keep the recorded ID. */ } }
    else if (github && (reference.providerId.toLowerCase().includes('github') || new URL(url).hostname === 'github.com')) id = `#${github[1]}`;
  }
  const summary = task.summary || 'Unassigned';
  return {title: id && !summary.startsWith(`${id}:`) ? `${id}: ${summary}` : summary, url};
}

export function activityDayNanos(activity, date, zone, generatedAt) {
  const from = instantNanos(dayStart(date, zone)), to = instantNanos(dayStart(addDays(date, 1), zone));
  const start = instantNanos(activity.start), end = instantNanos(activity.end || generatedAt);
  const first = start > from ? start : from, last = end < to ? end : to;
  return last > first ? last - first : 0n;
}

// Preserve parent tasks even when only a descendant has recorded time.
export function workweekGroups(tasks, projects, activities) {
  const byId = new Map(tasks.map(task => [task.id, task]));
  const needed = new Set();
  for (const activity of activities) {
    let id = activity.taskId;
    while (id && !needed.has(id)) { needed.add(id); id = byId.get(id)?.parentTaskId; }
  }
  const nodes = new Map([...needed].filter(id => byId.has(id)).map(id =>
    [id, {task: byId.get(id), children: [], activities: activities.filter(activity => activity.taskId === id)}]));
  const groups = new Map();
  for (const node of nodes.values()) {
    const parent = nodes.get(node.task.parentTaskId);
    if (parent) parent.children.push(node);
    else {
      const id = node.task.projectId || '';
      if (!groups.has(id)) groups.set(id, {id, name: projects.find(project => project.id === id)?.name || 'Unassigned', tasks: []});
      groups.get(id).tasks.push(node);
    }
  }
  const sort = nodes => {
    nodes.sort((a, b) => taskPresentation(a.task).title.localeCompare(taskPresentation(b.task).title));
    for (const node of nodes) sort(node.children);
  };
  for (const group of groups.values()) sort(group.tasks);
  return [...groups.values()].sort((a, b) => a.name.localeCompare(b.name));
}
