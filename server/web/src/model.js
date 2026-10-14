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
export function decimalHoursText(total) {
  // Round the exact aggregate, never its individual activities or daily cells.
  const hundredths = (total * 100n + 1800000000000n) / 3600000000000n;
  return `${hundredths / 100n}.${(hundredths % 100n).toString().padStart(2, '0')}h`;
}
export function instantText(instant, zone) {
  if (!instant) return 'In progress';
  return new Intl.DateTimeFormat(undefined, {timeZone: zone, year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit', timeZoneName: 'short'}).format(new Date(instant));
}

const inputFormats = new Map();
function civilTime(milliseconds, zone) {
  if (!inputFormats.has(zone)) inputFormats.set(zone, new Intl.DateTimeFormat('en-CA', {
    timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
  }));
  const parts = inputFormats.get(zone).formatToParts(new Date(milliseconds));
  const part = type => parts.find(value => value.type === type).value;
  return `${part('year')}-${part('month')}-${part('day')}T${part('hour')}:${part('minute')}:${part('second')}`;
}
function offsetSeconds(milliseconds, zone) {
  return (Date.parse(civilTime(milliseconds, zone) + 'Z') - Math.floor(milliseconds / 1000) * 1000) / 1000;
}
function localCandidates(civil, zone) {
  const nominal = Date.parse(civil + 'Z'), offsets = new Set();
  // Sample both sides of offset transitions, including half-hour changes and skipped dates.
  for (let hours = -36; hours <= 36; hours += 6) offsets.add(offsetSeconds(nominal + hours * 3600000, zone));
  return [...offsets].map(offset => nominal - offset * 1000).filter(value => civilTime(value, zone) === civil).sort((a,b) => a-b);
}
function offsetText(seconds) {
  const magnitude = Math.abs(seconds), pad = value => String(value).padStart(2, '0');
  return `${seconds < 0 ? '-' : '+'}${pad(Math.floor(magnitude / 3600))}:${pad(Math.floor(magnitude / 60) % 60)}${magnitude % 60 ? ':' + pad(magnitude % 60) : ''}`;
}
/** Editable wall time; an offset distinguishes the two occurrences of a repeated clock time. */
export function localDateTime(instant, zone) {
  if (!instant) return '';
  instantNanos(instant);
  const milliseconds = Date.parse(instant), civil = civilTime(milliseconds, zone);
  const fraction = /\.(\d+)Z$/.exec(instant)?.[1];
  return civil + (fraction ? '.' + fraction : '') + (localCandidates(civil, zone).length > 1 ? offsetText(offsetSeconds(milliseconds, zone)) : '');
}
/** Resolve local input strictly, preserving all nine fractional digits in the UTC API value. */
export function localDateTimeToInstant(value, zone) {
  const match = /^(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?(Z|[+-]\d{2}:\d{2}(?::\d{2})?)?$/.exec(value.trim());
  if (!match) throw new Error(`Enter a date and time in ${zone}, for example 2026-10-13T08:30:00.`);
  const civil = `${match[1]}T${match[2]}:${match[3] || '00'}`, nominal = Date.parse(civil + 'Z');
  if (!Number.isFinite(nominal) || new Date(nominal).toISOString().slice(0,19) !== civil) throw new Error('Choose a valid date and time.');
  let milliseconds;
  if (match[5]) {
    const offset = match[5] === 'Z' ? [0,0,0] : match[5].slice(1).split(':').map(Number);
    const seconds = offset[0] * 3600 + offset[1] * 60 + (offset[2] || 0);
    if (offset[1] > 59 || (offset[2] || 0) > 59 || seconds > 18 * 3600) throw new Error('Choose a valid UTC offset.');
    milliseconds = nominal - (match[5][0] === '-' ? -seconds : seconds) * 1000;
  } else {
    const candidates = localCandidates(civil, zone);
    if (!candidates.length) throw new Error(`This clock time does not exist in ${zone} because the clocks move forward.`);
    if (candidates.length > 1) throw new Error(`This clock time occurs twice in ${zone}. Include its UTC offset (${candidates.map(value => offsetText(offsetSeconds(value, zone))).join(' or ')}).`);
    [milliseconds] = candidates;
  }
  return new Date(milliseconds).toISOString().slice(0,19) + (match[4] ? '.' + match[4] : '') + 'Z';
}
export function instantNanos(instant) {
  const match = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?Z$/.exec(instant);
  if (!match) throw new Error('Invalid UTC instant.');
  return BigInt(Date.parse(match[1] + 'Z')) * 1000000n + BigInt((match[2] || '').padEnd(9, '0'));
}
export function nanosInstant(value) {
  let seconds = value / 1000000000n, fraction = value % 1000000000n;
  if (fraction < 0n) { seconds--; fraction += 1000000000n; }
  const date = new Date(Number(seconds * 1000n)).toISOString().slice(0,19);
  if (!/^\d{4}-/.test(date)) throw new Error('Choose a date between years 0000 and 9999.');
  return date + (fraction ? '.' + fraction.toString().padStart(9,'0').replace(/0+$/,'') : '') + 'Z';
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
  for (const activity of activities.filter(value => !value.taskId)) {
    const id = activity.projectId || '';
    if (!groups.has(id)) groups.set(id, {id, name: projects.find(project => project.id === id)?.name || 'Unassigned', tasks: []});
    (groups.get(id).activities ||= []).push(activity);
  }
  const sort = nodes => {
    nodes.sort((a, b) => taskPresentation(a.task).title.localeCompare(taskPresentation(b.task).title));
    for (const node of nodes) sort(node.children);
  };
  for (const group of groups.values()) sort(group.tasks);
  return [...groups.values()].sort((a, b) => a.name.localeCompare(b.name));
}

// Recompute live coverage from one server-derived instant without rounding records.
export function sessionTimeNanos(session, activities, asOf) {
  const start = instantNanos(session.start), end = session.end ? instantNanos(session.end) : asOf;
  const duration = end > start ? end - start : 0n, intervals = [];
  let tracked = 0n, allocated = 0n;
  for (const activity of activities) {
    const first = instantNanos(activity.start), last = activity.end ? instantNanos(activity.end) : asOf;
    const from = first > start ? first : start, to = last < end ? last : end;
    if (to > from) { intervals.push([from,to]); tracked += to - from; }
  }
  intervals.sort((a,b) => a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0);
  let first = null, last = null;
  for (const [from,to] of intervals) {
    if (last === null) { first = from; last = to; }
    else if (from <= last) { if (to > last) last = to; }
    else { allocated += last - first; first = from; last = to; }
  }
  if (last !== null) allocated += last - first;
  return {duration, tracked, allocated, unallocated: duration - allocated};
}
